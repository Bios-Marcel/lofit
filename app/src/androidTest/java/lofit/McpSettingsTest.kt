package lofit

import android.content.Intent
import android.view.ViewGroup
import android.util.AtomicFile
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.Socket
import java.net.ServerSocket
import java.net.NetworkInterface
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpSettingsTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var savedState: ByteArray? = null

    @Before fun setup() {
        val file = AtomicFile(context.repositoryStateFile)
        savedState = runCatching { file.readFully() }.getOrNull()
        LofitRepository(context).apply {
            reset()
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            save()
        }
    }

    @After fun restore() {
        val file = AtomicFile(context.repositoryStateFile)
        val bytes = savedState
        if (bytes == null) file.delete() else file.finishWrite(file.startWrite().apply { write(bytes) })
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, label: Int) {
        scenario.onActivity {
            val root = it.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        onView(allOf(withText(label), isClickable())).perform(click())
    }

    @Test fun toggleControlsRealMcpRequestsPersistsAcrossRecreationAndShowsNewRoutines() {
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            navigate(scenario, R.string.nav_settings)
            onView(withContentDescription("MCP server")).check(matches(not(isChecked())))
            assertClosed()
            onView(withContentDescription("MCP server")).perform(scrollTo(), click())
            assertTrue(LofitRepository(context).data.mcpEnabled)
            onView(withText(McpHttpServer.URL)).check(matches(isDisplayed()))
            val initialized = post("initialize", JSONObject().put("protocolVersion", "2025-11-25")
                .put("capabilities", JSONObject()).put("clientInfo", JSONObject().put("name", "test").put("version", "1")))
            assertEquals("lofit", initialized.getJSONObject("result").getJSONObject("serverInfo").getString("name"))
            assertTrue(post("ping", host = "::1").has("result"))
            scenario.recreate()
            navigate(scenario, R.string.nav_settings)
            onView(withContentDescription("MCP server")).check(matches(isChecked()))
            assertEquals(4, post("tools/list").getJSONObject("result").getJSONArray("tools").length())

            navigate(scenario, R.string.nav_routines)
            val created = post("tools/call", JSONObject().put("name", "create_routine").put("arguments",
                JSONObject().put("name", "MCP strength").put("exercises", JSONArray()
                    .put(JSONObject().put("exerciseId", "bench-press")))))
            assertFalse(created.getJSONObject("result").getBoolean("isError"))
            onView(withText("MCP strength")).check(matches(isDisplayed()))
            assertEquals("MCP strength", LofitRepository(context).data.routines.single().name)
            navigate(scenario, R.string.nav_settings)
            onView(withContentDescription("MCP server")).perform(scrollTo(), click())
            assertFalse(LofitRepository(context).data.mcpEnabled)
            assertClosed()
            scenario.recreate()
            assertClosed()
        }
        assertClosed()
    }

    @Test fun resetClosesEnabledServer() {
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            navigate(scenario, R.string.nav_settings)
            onView(withContentDescription("MCP server")).perform(scrollTo(), click())
            post("ping")
            onView(withText("Reset all app data")).perform(scrollTo(), click())
            onView(withText("Reset everything")).perform(scrollTo(), click())
            assertFalse(LofitRepository(context).data.mcpEnabled)
            assertClosed()
        }
    }

    @Test fun portOverrideRestartsServerAndPersists() {
        val port = ServerSocket(0).use { it.localPort }
        val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            navigate(scenario, R.string.nav_settings)
            onView(withText("Port")).check(matches(isDisplayed()))
            onView(withContentDescription("MCP server")).perform(scrollTo(), click())
            onView(withContentDescription("MCP port")).perform(scrollTo(), replaceText("0"))
            onView(withText("Apply")).perform(scrollTo(), click())
            assertEquals(McpHttpServer.PORT, LofitRepository(context).data.mcpPort)
            onView(withContentDescription("MCP port")).perform(scrollTo(), replaceText(port.toString()))
            onView(withText("Apply")).perform(scrollTo(), click())
            assertEquals(port, LofitRepository(context).data.mcpPort)
            onView(withText("http://127.0.0.1:$port/mcp")).check(matches(isDisplayed()))
            assertClosed()
            assertTrue(post("ping", port = port).has("result"))
            scenario.recreate()
            navigate(scenario, R.string.nav_settings)
            assertEquals(port, LofitRepository(context).data.mcpPort)
            assertTrue(post("ping", port = port).has("result"))
            ServerSocket(0).use { occupied ->
                onView(withContentDescription("MCP port")).perform(scrollTo(), replaceText(occupied.localPort.toString()))
                onView(withText("Apply")).perform(scrollTo(), click())
                assertEquals(port, LofitRepository(context).data.mcpPort)
                assertTrue(post("ping", port = port).has("result"))
            }
            onView(withContentDescription("MCP server")).perform(scrollTo(), click())
            assertClosed(port)
        }
    }

    @Test fun lanConnectionsCannotDispatchEvenWithALocalhostHeader() {
        val address = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .first { it.address.size == 4 && !it.isLoopbackAddress && !McpHttpServer.isTailscaleAddress(it) }
        var calls = 0
        McpHttpServer(0) { calls++; "{}" }.use { server ->
            Socket(address, server.port).use { socket ->
                socket.soTimeout = 5_000
                socket.getOutputStream().write(("POST /mcp HTTP/1.1\r\nHost: localhost:${server.port}\r\n" +
                    "Content-Type: application/json\r\nAccept: application/json\r\nContent-Length: 2\r\n\r\n{}").toByteArray())
                assertTrue(socket.getInputStream().bufferedReader().readLine().startsWith("HTTP/1.1 403"))
            }
            assertEquals(0, calls)
        }
    }

    private fun assertClosed(port: Int = McpHttpServer.PORT) {
        val connection = runCatching { Socket("127.0.0.1", port) }.getOrNull()
        connection?.close()
        assertNull("MCP port should be closed", connection)
    }

    private fun post(method: String, params: JSONObject = JSONObject(), host: String = "127.0.0.1",
        port: Int = McpHttpServer.PORT): JSONObject {
        val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params)
            .toString().toByteArray()
        return Socket(host, port).use { socket ->
            socket.soTimeout = 15_000
            socket.getOutputStream().apply {
                val authority = if (':' in host) "[$host]" else host
                write(("POST /mcp HTTP/1.1\r\nHost: $authority:$port\r\n" +
                    "Content-Type: application/json\r\nAccept: application/json, text/event-stream\r\n" +
                    "MCP-Protocol-Version: 2025-11-25\r\nContent-Length: ${body.size}\r\n\r\n").toByteArray())
                write(body)
                flush()
            }
            val response = socket.getInputStream().bufferedReader().readText()
            assertTrue(response, response.startsWith("HTTP/1.1 200"))
            JSONObject(response.substringAfter("\r\n\r\n"))
        }
    }
}
