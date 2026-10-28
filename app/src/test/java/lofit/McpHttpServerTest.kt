package lofit

import java.net.Socket
import java.net.SocketException
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class McpHttpServerTest {
    @Test fun onlyLoopbackAndTailscaleConnectionsAreAllowed() {
        val allowed = listOf("127.0.0.1", "::1", "100.64.0.1", "100.127.255.254", "fd7a:115c:a1e0::1")
        val denied = listOf("100.63.255.255", "100.128.0.1", "192.168.1.2", "10.0.0.1", "8.8.8.8",
            "fd7a:115c:a1df::1", "fd7a:115c:a1e1::1", "2001:db8::1")
        for (local in allowed + denied) for (remote in allowed + denied) {
            assertEquals("$local <- $remote", local in allowed && remote in allowed,
                McpHttpServer.allowsConnection(InetAddress.getByName(local), InetAddress.getByName(remote)))
        }
        assertFalse(McpHttpServer.isTailscaleAddress(InetAddress.getByName("127.0.0.1")))
        assertFalse(McpHttpServer.isTailscaleAddress(InetAddress.getByName("::1")))
    }

    private fun request(server: McpHttpServer, headers: String = "", body: String = "{}", method: String = "POST", path: String = "/mcp"): String =
        Socket("127.0.0.1", server.port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write(("$method $path HTTP/1.1\r\nHost: localhost:${server.port}\r\n" +
                "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n" +
                headers + "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }

    @Test fun postDispatchesAndNotificationsReturnAccepted() {
        val received = mutableListOf<String>()
        McpHttpServer(0) { received.add(it); if (it == "notification") null else "{\"result\":{}}" }.use { server ->
            val result = request(server, body = "{\"name\":\"力量\"}")
            assertTrue(result.startsWith("HTTP/1.1 200"))
            assertTrue(result.endsWith("{\"result\":{}}"))
            assertEquals("{\"name\":\"力量\"}", received.single())
            assertTrue(request(server, body = "notification").startsWith("HTTP/1.1 202"))
        }
    }

    @Test fun invalidOriginsHostsVersionsPathsAndMethodsNeverDispatch() {
        var calls = 0
        McpHttpServer(0) { calls++; "{}" }.use { server ->
            assertTrue(request(server, "Origin: https://evil.example\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(server, "Origin: null\r\n").startsWith("HTTP/1.1 403"))
            assertTrue(request(server, "MCP-Protocol-Version: unsupported\r\n").startsWith("HTTP/1.1 400"))
            assertTrue(request(server, method = "GET").startsWith("HTTP/1.1 405"))
            assertTrue(request(server, path = "/routines").startsWith("HTTP/1.1 404"))
            val badHost = raw(server, "POST /mcp HTTP/1.1\r\nHost: evil.example\r\n\r\n")
            assertTrue(badHost.startsWith("HTTP/1.1 403"))
            assertEquals(0, calls)
            assertTrue(request(server, "Origin: http://localhost:${server.port}\r\n").startsWith("HTTP/1.1 200"))
            assertEquals(1, calls)
            assertTrue(raw(server, "POST /mcp HTTP/1.1\r\nHost: 100.100.100.100:${server.port}\r\n\r\n")
                .startsWith("HTTP/1.1 403"))
            assertTrue(request(server, "Origin: http://localhost:${server.port}@evil.example\r\n")
                .startsWith("HTTP/1.1 403"))
            assertTrue(request(server, "Origin: http://localhost:${server.port}/mcp\r\n")
                .startsWith("HTTP/1.1 403"))
            assertEquals(1, calls)
        }
    }

    @Test fun chunkedRequestsWorkAndOversizedBodiesAreRejectedBeforeReading() {
        McpHttpServer(0) { assertEquals("{}", it); "{}" }.use { server ->
            val headers = "POST /mcp HTTP/1.1\r\nHost: localhost:${server.port}\r\n" +
                "Content-Type: application/json\r\nAccept: application/json\r\n"
            assertTrue(raw(server, headers + "Transfer-Encoding: chunked\r\n\r\n2\r\n{}\r\n0\r\n\r\n")
                .startsWith("HTTP/1.1 200"))
            assertTrue(raw(server, headers + "Content-Length: 1048577\r\n\r\n").startsWith("HTTP/1.1 413"))
            assertTrue(raw(server, headers + "Transfer-Encoding: chunked\r\nContent-Length: 2\r\n\r\n{}")
                .startsWith("HTTP/1.1 400"))
        }
    }

    @Test fun closeDisconnectsPendingClientsAndReleasesThePort() {
        val server = McpHttpServer(0) { fail("Incomplete request must not dispatch"); null }
        val port = server.port
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write("POST /mcp HTTP/1.1\r\n".toByteArray())
            server.close()
            try {
                assertEquals(-1, socket.getInputStream().read())
            } catch (_: SocketException) {
                // A reset also confirms that the server closed the pending connection.
            }
        }
        McpHttpServer(port) { "{}" }.use { assertTrue(request(it).startsWith("HTTP/1.1 200")) }
    }

    private fun raw(server: McpHttpServer, request: String): String = Socket("127.0.0.1", server.port).use {
        it.soTimeout = 2_000
        it.getOutputStream().write(request.toByteArray())
        it.getInputStream().bufferedReader().readText()
    }
}
