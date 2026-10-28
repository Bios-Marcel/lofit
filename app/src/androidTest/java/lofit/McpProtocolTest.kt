package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class McpProtocolTest {
    private val context = RepositoryTestContext("mcp")
    private lateinit var repository: LofitRepository
    private lateinit var protocol: McpProtocol
    private var changes = 0

    @Before
    fun setup() {
        context.clearRepositoryTestData()
        repository = LofitRepository(context)
        changes = 0
        protocol = McpProtocol(repository) { changes++ }
    }

    private fun request(method: String, params: JSONObject = JSONObject()): JSONObject =
        protocol.handle(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", "test")
                .put("method", method)
                .put("params", params)
                .toString()
        )!!

    private fun call(name: String, args: JSONObject = JSONObject()): JSONObject =
        request(
                "tools/call",
                JSONObject().put("name", name).put("arguments", args),
            )
            .getJSONObject("result")

    private fun routineArgs() =
        JSONObject()
            .put("name", "Strength")
            .put(
                "exercises",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("exerciseId", "bench-press")
                            .put("note", "Slow descent")
                            .put(
                                "plannedSets",
                                JSONArray()
                                    .put(
                                        JSONObject()
                                            .put("type", "WARMUP")
                                            .put("reps", 10)
                                            .put("weightKg", 20.25)
                                    ),
                            )
                    ),
            )

    @Test
    fun initializationDiscoveryAndNotificationsFollowMcp() {
        val initialized =
            request(
                    "initialize",
                    JSONObject()
                        .put("protocolVersion", "2025-11-25")
                        .put("capabilities", JSONObject())
                        .put("clientInfo", JSONObject().put("name", "test").put("version", "1")),
                )
                .getJSONObject("result")
        assertEquals("2025-11-25", initialized.getString("protocolVersion"))
        assertTrue(initialized.getJSONObject("capabilities").has("tools"))
        val tools = request("tools/list").getJSONObject("result").getJSONArray("tools")
        assertEquals(
            listOf("get_routines", "get_exercises", "get_history", "create_routine"),
            (0 until tools.length()).map { tools.getJSONObject(it).getString("name") },
        )
        assertTrue(tools.getJSONObject(0).getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertFalse(tools.getJSONObject(3).getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertNull(protocol.handle("""{"jsonrpc":"2.0","method":"notifications/initialized"}"""))
        // Even a tools/call notification must never perform a write.
        assertNull(
            protocol.handle(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "tools/call")
                    .put(
                        "params",
                        JSONObject().put("name", "create_routine").put("arguments", routineArgs()),
                    )
                    .toString()
            )
        )
        assertTrue(repository.data.routines.isEmpty())
        assertEquals(0, request("ping").getJSONObject("result").length())
    }

    @Test
    fun createsRoutineWithGeneratedIdsPersistsAndReadsAllData() {
        val created = call("create_routine", routineArgs())
        assertFalse(created.getBoolean("isError"))
        val routine = LofitRepository(context).data.routines.single()
        assertEquals("Strength", routine.name)
        assertEquals("Slow descent", routine.exercises.single().note)
        assertEquals(20.25, routine.exercises.single().plannedSets.single().weightKg, 0.0)
        assertEquals(SetType.WARMUP, routine.exercises.single().plannedSets.single().type)
        assertEquals(1, changes)
        val routines =
            call("get_routines").getJSONObject("structuredContent").getJSONArray("routines")
        assertEquals(routine.id, routines.getJSONObject(0).getString("id"))
        assertEquals(
            created.getJSONObject("structuredContent").toString(),
            created.getJSONArray("content").getJSONObject(0).getString("text"),
        )

        val exercises =
            call("get_exercises", JSONObject().put("limit", 1)).getJSONObject("structuredContent")
        assertEquals(1, exercises.getJSONArray("exercises").length())
        assertEquals(1, exercises.getInt("nextOffset"))
        assertEquals(repository.data.exercises.size, exercises.getInt("total"))
        val second =
            call("get_exercises", JSONObject().put("offset", 1).put("limit", 1))
                .getJSONObject("structuredContent")
        assertNotEquals(
            exercises.getJSONArray("exercises").getJSONObject(0).getString("id"),
            second.getJSONArray("exercises").getJSONObject(0).getString("id"),
        )

        repository.data.history.add(WorkoutSession(name = "Old", startedAt = 10, completedAt = 20))
        repository.data.history.add(
            WorkoutSession(
                name = "New",
                startedAt = 30,
                completedAt = 40,
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = "bench-press",
                            note = "Felt strong",
                            sets =
                                mutableListOf(
                                    LoggedSet(type = SetType.NORMAL, weightKg = 50.0, reps = 8)
                                ),
                        )
                    ),
            )
        )
        val history = call("get_history").getJSONObject("structuredContent").getJSONArray("history")
        assertEquals("New", history.getJSONObject(0).getString("name"))
        assertEquals(40L, history.getJSONObject(0).getLong("completedAt"))
        assertEquals(
            8,
            history
                .getJSONObject(0)
                .getJSONArray("exercises")
                .getJSONObject(0)
                .getJSONArray("sets")
                .getJSONObject(0)
                .getInt("reps"),
        )
    }

    @Test
    fun invalidRoutineArgumentsNeverPartiallySave() {
        val invalid =
            listOf(
                routineArgs().put("name", "  "),
                routineArgs().put("defaultRestSeconds", -1),
                routineArgs().put("defaultRestSeconds", 1.5),
                routineArgs().put("defaultRestSeconds", "120"),
                routineArgs().put("exercises", JSONArray()),
                routineArgs().put("id", "overwrite"),
                routineArgs().apply {
                    getJSONArray("exercises").put(JSONObject().put("exerciseId", "missing"))
                },
                routineArgs().apply {
                    getJSONArray("exercises")
                        .getJSONObject(0)
                        .put(
                            "plannedSets",
                            JSONArray().put(JSONObject().put("weightKg", -10)),
                        )
                },
                routineArgs().apply {
                    getJSONArray("exercises")
                        .getJSONObject(0)
                        .getJSONArray("plannedSets")
                        .getJSONObject(0)
                        .put("side", "INVALID")
                },
            )
        invalid.forEach { args ->
            assertTrue(args.toString(), call("create_routine", args).getBoolean("isError"))
            assertTrue(repository.data.routines.isEmpty())
            assertTrue(LofitRepository(context).data.routines.isEmpty())
        }
        assertEquals(0, changes)
        assertTrue(call("get_history", JSONObject().put("limit", 0)).getBoolean("isError"))
    }

    @Test
    fun malformedRequestsReturnJsonRpcErrors() {
        assertEquals(-32700, protocol.handle("{")!!.getJSONObject("error").getInt("code"))
        assertEquals(-32600, protocol.handle("[]")!!.getJSONObject("error").getInt("code"))
        assertEquals(
            -32600,
            protocol
                .handle("""{"jsonrpc":"1.0","id":1,"method":"ping"}""")!!
                .getJSONObject("error")
                .getInt("code"),
        )
        assertEquals(-32601, request("missing").getJSONObject("error").getInt("code"))
        assertEquals(
            -32602,
            request("tools/call", JSONObject().put("name", "missing"))
                .getJSONObject("error")
                .getInt("code"),
        )
    }

    @Test
    fun oldDataDefaultsOffAndEnabledStatePersistsAndResets() {
        repository.save()
        val old = JSONObject(context.repositoryStateFile.readText()).apply { remove("mcpEnabled") }
        context.repositoryStateFile.writeText(old.toString())
        assertFalse(LofitRepository(context).data.mcpEnabled)
        repository.data.mcpEnabled = true
        repository.save()
        assertTrue(LofitRepository(context).data.mcpEnabled)
        repository.reset()
        assertFalse(LofitRepository(context).data.mcpEnabled)
    }
}
