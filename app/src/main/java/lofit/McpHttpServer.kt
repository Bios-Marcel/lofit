package lofit

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Small, bounded HTTP transport. Each connection carries one request and is then closed. */
internal class McpHttpServer(
    port: Int = PORT,
    private val dispatch: (String) -> String?,
) : Closeable {
    companion object {
        const val PORT = BuildConfig.MCP_PORT
        const val URL = "http://127.0.0.1:$PORT/mcp"
        val PROTOCOL_VERSIONS = listOf("2025-03-26", "2025-06-18", "2025-11-25")
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val MAX_HEADER_BYTES = 16 * 1024

        fun isTailscaleAddress(address: InetAddress): Boolean {
            val bytes = address.address.map { it.toInt() and 255 }
            return when (bytes.size) {
                4 -> bytes[0] == 100 && bytes[1] in 64..127
                16 -> bytes.take(6) == listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)
                else -> false
            }
        }

        fun allowsConnection(local: InetAddress, remote: InetAddress): Boolean =
            (local.isLoopbackAddress || isTailscaleAddress(local)) &&
                (remote.isLoopbackAddress || isTailscaleAddress(remote))

        private fun tailscaleAddresses(): List<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .filter(::isTailscaleAddress)
        }.getOrDefault(emptyList())

        fun urls(port: Int = PORT): List<String> = listOf("http://127.0.0.1:$port/mcp") + tailscaleAddresses().map {
            val host = it.hostAddress!!.substringBefore('%')
            "http://${if (':' in host) "[$host]" else host}:$port/mcp"
        }
    }

    private val listener = ServerSocket()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(8),
        { task -> Thread(task, "lofit-mcp-request").apply { isDaemon = true } })
    val port: Int get() = listener.localPort

    init {
        try {
            listener.reuseAddress = true
            // A wildcard listener survives VPN reconnects. Gate both ends of each socket below.
            listener.bind(InetSocketAddress(port))
        } catch (failure: IOException) {
            close()
            throw failure
        }
        Thread({
            while (!listener.isClosed) {
                val client = try { listener.accept() } catch (_: IOException) { break }
                clients.add(client)
                try {
                    workers.execute {
                        try { client.use(::serve) } finally { clients.remove(client) }
                    }
                } catch (_: RejectedExecutionException) {
                    clients.remove(client)
                    client.close()
                }
            }
        }, "lofit-mcp-listener").apply { isDaemon = true; start() }
    }

    override fun close() {
        listener.close()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 5_000
            if (!allowsConnection(client.localAddress, client.inetAddress)) {
                throw HttpFailure(403, "Use loopback or Tailscale")
            }
            val input = BufferedInputStream(client.getInputStream())
            var headerBytes = 0
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val byte = input.read()
                    if (byte == -1) throw HttpFailure(400, "Incomplete request")
                    if (++headerBytes > MAX_HEADER_BYTES) throw HttpFailure(431, "Headers too large")
                    if (byte == 10) break
                    bytes.write(byte)
                }
                return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            }
            val request = line().split(' ')
            if (request.size != 3 || request[2] != "HTTP/1.1") throw HttpFailure(400, "Invalid request")
            val headers = mutableMapOf<String, String>()
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                val colon = header.indexOf(':')
                if (colon < 1) throw HttpFailure(400, "Invalid header")
                val name = header.substring(0, colon).lowercase(Locale.ROOT)
                if (headers.put(name, header.substring(colon + 1).trim()) != null) {
                    throw HttpFailure(400, "Duplicate header")
                }
            }
            val addresses = tailscaleAddresses() + InetAddress.getByName("127.0.0.1") + InetAddress.getByName("::1")
            if (!allowsAuthority(headers["host"], addresses)) throw HttpFailure(403, "Invalid host")
            headers["origin"]?.let { origin ->
                val uri = runCatching { URI(origin) }.getOrNull()
                if (uri == null || uri.scheme != "http" || !allowsAuthority(uri.rawAuthority, addresses) ||
                    !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
                    throw HttpFailure(403, "Invalid origin")
                }
            }
            if (request[1] != "/mcp") throw HttpFailure(404, "Not found")
            if (request[0] != "POST") {
                respond(client, 405, "Method not allowed", "Allow: POST\r\n")
                return
            }
            headers["mcp-protocol-version"]?.let {
                if (it !in PROTOCOL_VERSIONS) throw HttpFailure(400, "Unsupported MCP protocol version")
            }
            if (headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
                throw HttpFailure(415, "Use application/json")
            }
            val accept = headers["accept"].orEmpty().split(',').map { it.substringBefore(';').trim() }
            if ("application/json" !in accept && "*/*" !in accept) throw HttpFailure(406, "Accept application/json")
            val body = if (headers["transfer-encoding"] != null) {
                if (headers.containsKey("content-length") || headers["transfer-encoding"] != "chunked") {
                    throw HttpFailure(400, "Invalid transfer encoding")
                }
                val bytes = ByteArrayOutputStream()
                while (true) {
                    val size = line().substringBefore(';').toIntOrNull(16)
                        ?: throw HttpFailure(400, "Invalid chunk")
                    if (size < 0 || size > MAX_BODY_BYTES - bytes.size()) throw HttpFailure(413, "Body too large")
                    if (size == 0) {
                        if (line().isNotEmpty()) throw HttpFailure(400, "Trailers unsupported")
                        break
                    }
                    bytes.write(readBody(input, size))
                    if (input.read() != 13 || input.read() != 10) throw HttpFailure(400, "Invalid chunk")
                }
                bytes.toByteArray()
            } else {
                val size = headers["content-length"]?.toLongOrNull() ?: throw HttpFailure(411, "Length required")
                if (size < 0) throw HttpFailure(400, "Invalid length")
                if (size > MAX_BODY_BYTES) throw HttpFailure(413, "Body too large")
                readBody(input, size.toInt())
            }
            val response = dispatch(body.toString(Charsets.UTF_8))
            if (response == null) respond(client, 202, "") else respond(client, 200, response)
        } catch (failure: HttpFailure) {
            runCatching { respond(client, failure.status, failure.message.orEmpty()) }
        } catch (_: IOException) {
            // Client disconnected or the toggle closed this socket.
        } catch (_: Exception) {
            runCatching { respond(client, 503, "MCP unavailable") }
        }
    }

    private fun allowsAuthority(authority: String?, addresses: List<InetAddress>): Boolean {
        if (authority == null) return false
        val uri = runCatching { URI("http://$authority") }.getOrNull() ?: return false
        if (uri.port != port || uri.rawUserInfo != null || !uri.rawPath.isNullOrEmpty() ||
            uri.rawQuery != null || uri.rawFragment != null) return false
        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        if (host == "localhost") return true
        // Only numeric addresses: do not resolve untrusted DNS names from Host/Origin.
        if (!host.matches(Regex("[0-9.]+|\\[[0-9a-f:]+]"))) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return address in addresses
    }

    private fun readBody(input: BufferedInputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val count = input.read(bytes, read, size - read)
            if (count < 0) throw HttpFailure(400, "Incomplete body")
            read += count
        }
        return bytes
    }

    private fun respond(client: Socket, status: Int, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"
            202 -> "Accepted"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            406 -> "Not Acceptable"
            411 -> "Length Required"
            413 -> "Content Too Large"
            415 -> "Unsupported Media Type"
            431 -> "Request Header Fields Too Large"
            else -> "Service Unavailable"
        }
        val type = if (status == 200) "application/json" else "text/plain"
        client.getOutputStream().apply {
            write(("HTTP/1.1 $status $reason\r\nContent-Type: $type; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\n" +
                extraHeaders + "\r\n").toByteArray(Charsets.US_ASCII))
            write(bytes)
            flush()
        }
    }

    private class HttpFailure(val status: Int, message: String) : Exception(message)
}
