package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
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
        protocol = McpProtocol(repository, ExercisePictures(context)) { changes++ }
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
            listOf(
                "get_routines",
                "get_exercises",
                "get_history",
                "create_routine",
                "create_exercise",
                "edit_exercise",
            ),
            (0 until tools.length()).map { tools.getJSONObject(it).getString("name") },
        )
        assertTrue(tools.getJSONObject(0).getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertFalse(tools.getJSONObject(3).getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertFalse(tools.getJSONObject(4).getJSONObject("annotations").getBoolean("readOnlyHint"))
        assertTrue(
            tools.getJSONObject(5).getJSONObject("annotations").getBoolean("destructiveHint")
        )
        assertFalse(
            tools.getJSONObject(5).getJSONObject("annotations").getBoolean("idempotentHint")
        )
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
    fun createsCustomExercisesWithDefaultsAndUsesThemInRoutines() {
        val result =
            call(
                "create_exercise",
                JSONObject().put("name", "  Custom press  ").put("primaryMuscle", " chest "),
            )
        assertFalse(result.getBoolean("isError"))
        val saved = result.getJSONObject("structuredContent").getJSONObject("exercise")
        val exercise =
            LofitRepository(context).data.exercises.single { it.id == saved.getString("id") }
        assertEquals("Custom press", exercise.name)
        assertEquals("Chest", exercise.primaryMuscle)
        assertEquals("", exercise.secondaryMuscles)
        assertEquals(ExerciseType.REPETITIONS, exercise.type)
        assertTrue(exercise.custom)
        assertTrue(exercise.weighted)
        assertFalse(exercise.asymmetric)
        assertFalse(exercise.liveMeasurement)
        assertFalse(exercise.cardio)
        assertFalse(saved.getBoolean("cardio"))
        assertEquals(30, exercise.minimumHoldSeconds)
        assertEquals(1, changes)
        assertEquals(
            result.getJSONObject("structuredContent").toString(),
            result.getJSONArray("content").getJSONObject(0).getString("text"),
        )
        val routine =
            call(
                "create_routine",
                JSONObject()
                    .put("name", "Custom routine")
                    .put("exercises", JSONArray().put(JSONObject().put("exerciseId", exercise.id))),
            )
        assertFalse(routine.getBoolean("isError"))
        assertEquals(
            exercise.id,
            LofitRepository(context).data.routines.single().exercises.single().exerciseId,
        )
        val timed =
            call(
                "create_exercise",
                JSONObject()
                    .put("name", "Custom hold")
                    .put("primaryMuscle", "Core")
                    .put("type", "TIMED"),
            )
        assertFalse(
            timed
                .getJSONObject("structuredContent")
                .getJSONObject("exercise")
                .getBoolean("weighted")
        )
        assertNotEquals(
            exercise.id,
            timed.getJSONObject("structuredContent").getJSONObject("exercise").getString("id"),
        )
    }

    @Test
    fun editsCustomExerciseAndPreservesOmittedFieldsPictureAndReferences() {
        val existing =
            Exercise(
                "custom-hold",
                "Hold",
                ExerciseType.TIMED,
                true,
                "Grip",
                "Forearms",
                minimumHoldSeconds = 45,
                custom = true,
                weighted = true,
                imageFile = "custom.webp",
                liveMeasurement = true,
                cardio = true,
            )
        repository.data.exercises.add(existing)
        repository.data.routines.add(
            Routine(
                name = "Holds",
                exercises = mutableListOf(RoutineExercise(exerciseId = existing.id)),
            )
        )
        repository.save()
        val args =
            JSONObject()
                .put("exerciseId", existing.id)
                .put("name", "New hold")
                .put("secondaryMuscles", "")
        val edited = call("edit_exercise", args)
        assertFalse(edited.getBoolean("isError"))
        val reloaded = LofitRepository(context).data
        val exercise = reloaded.exercises.single { it.id == existing.id }
        assertEquals(existing.copy(name = "New hold", secondaryMuscles = ""), exercise)
        assertEquals(existing.id, reloaded.routines.single().exercises.single().exerciseId)
        assertEquals(1, changes)
        val repeated = call("edit_exercise", args)
        assertEquals(
            edited.getJSONObject("structuredContent").toString(),
            repeated.getJSONObject("structuredContent").toString(),
        )
        val changed =
            call(
                "edit_exercise",
                JSONObject()
                    .put("exerciseId", existing.id)
                    .put("type", "REPETITIONS")
                    .put("liveMeasurement", false)
                    .put("weighted", false)
                    .put("asymmetric", false)
                    .put("cardio", false)
                    .put("primaryMuscle", "front delts")
                    .put("secondaryMuscles", "Triceps, chest, Triceps")
                    .put("minimumHoldSeconds", 0),
            )
        assertFalse(changed.getBoolean("isError"))
        val updated = LofitRepository(context).data.exercises.single { it.id == existing.id }
        assertEquals(ExerciseType.REPETITIONS, updated.type)
        assertFalse(updated.weighted)
        assertFalse(updated.asymmetric)
        assertFalse(updated.liveMeasurement)
        assertFalse(updated.cardio)
        assertEquals("Front deltoids", updated.primaryMuscle)
        assertEquals("Triceps, Chest", updated.secondaryMuscles)
        assertEquals(0, updated.minimumHoldSeconds)
        val page =
            call(
                    "get_exercises",
                    JSONObject()
                        .put(
                            "offset",
                            repository.data.exercises.indexOfFirst { it.id == existing.id },
                        ),
                )
                .getJSONObject("structuredContent")
        assertEquals(
            changed.getJSONObject("structuredContent").getJSONObject("exercise").toString(),
            page.getJSONArray("exercises").getJSONObject(0).toString(),
        )
    }

    @Test
    fun invalidExerciseWritesNeverChangeStoredData() {
        val existing =
            Exercise(
                "custom",
                "Hold",
                ExerciseType.TIMED,
                false,
                "Grip",
                "",
                custom = true,
                weighted = true,
                liveMeasurement = true,
            )
        repository.data.exercises.add(existing)
        repository.save()
        val stored = context.repositoryStateFile.readText()
        val before = repository.data.exercises.toList()
        val invalid =
            listOf(
                JSONObject().put("name", "  "),
                JSONObject().put("name", JSONObject.NULL),
                JSONObject().put("name", "a".repeat(201)),
                JSONObject().put("primaryMuscle", "Unknown"),
                JSONObject().put("secondaryMuscles", "Chest, Unknown"),
                JSONObject().put("type", "INVALID"),
                JSONObject().put("weighted", "true"),
                JSONObject().put("asymmetric", 1),
                JSONObject().put("cardio", "true"),
                JSONObject().put("cardio", JSONObject.NULL),
                JSONObject().put("liveMeasurement", JSONObject.NULL),
                JSONObject().put("minimumHoldSeconds", -1),
                JSONObject().put("minimumHoldSeconds", 1.5),
                JSONObject().put("minimumHoldSeconds", 2147483648L),
                JSONObject().put("id", "overwrite"),
                JSONObject().put("custom", false),
                JSONObject().put("imageFile", "/tmp/picture"),
                JSONObject().put("type", "REPETITIONS").put("liveMeasurement", true),
                JSONObject().put("weighted", false).put("liveMeasurement", true),
            )
        invalid.forEach { fields ->
            val create = JSONObject().put("name", "New exercise").put("primaryMuscle", "Chest")
            val edit = JSONObject().put("exerciseId", existing.id)
            fields.keys().forEach { key ->
                create.put(key, fields.get(key))
                edit.put(key, fields.get(key))
            }
            assertTrue(create.toString(), call("create_exercise", create).getBoolean("isError"))
            assertTrue(edit.toString(), call("edit_exercise", edit).getBoolean("isError"))
            assertEquals(before, repository.data.exercises)
            assertEquals(stored, context.repositoryStateFile.readText())
        }
        assertTrue(
            call("create_exercise", JSONObject().put("name", "Missing muscle"))
                .getBoolean("isError")
        )
        assertTrue(
            call("create_exercise", JSONObject().put("primaryMuscle", "Chest"))
                .getBoolean("isError")
        )
        assertTrue(
            call("edit_exercise", JSONObject().put("exerciseId", "missing")).getBoolean("isError")
        )
        assertTrue(
            call(
                    "edit_exercise",
                    JSONObject().put("exerciseId", "bench-press").put("name", "Changed"),
                )
                .getBoolean("isError")
        )
        assertTrue(
            call("edit_exercise", JSONObject().put("name", "Missing ID")).getBoolean("isError")
        )
        assertTrue(
            call(
                    "edit_exercise",
                    JSONObject().put("exerciseId", existing.id).put("type", "REPETITIONS"),
                )
                .getBoolean("isError")
        )
        assertEquals(before, repository.data.exercises)
        assertEquals(stored, context.repositoryStateFile.readText())
        assertEquals(0, changes)
    }

    @Test
    fun cardioPrimaryMuscleIsOptionalButDisablingCardioRequiresOne() {
        val created = call("create_exercise", JSONObject().put("name", "Run").put("cardio", true))
        assertFalse(created.getBoolean("isError"))
        val saved = created.getJSONObject("structuredContent").getJSONObject("exercise")
        val id = saved.getString("id")
        fun loaded() = LofitRepository(context).data.exercises.single { it.id == id }
        assertEquals("", loaded().primaryMuscle)
        assertEquals("", saved.getString("primaryMuscle"))
        assertFalse(
            call("edit_exercise", JSONObject().put("exerciseId", id).put("name", "Easy run"))
                .getBoolean("isError")
        )
        val before = loaded()
        assertTrue(
            call("edit_exercise", JSONObject().put("exerciseId", id).put("cardio", false))
                .getBoolean("isError")
        )
        assertEquals(before, loaded())
        assertTrue(
            call(
                    "edit_exercise",
                    JSONObject().put("exerciseId", id).put("primaryMuscle", "Unknown"),
                )
                .getBoolean("isError")
        )
        assertEquals(before, loaded())
        assertFalse(
            call(
                    "edit_exercise",
                    JSONObject()
                        .put("exerciseId", id)
                        .put("cardio", false)
                        .put("primaryMuscle", " core "),
                )
                .getBoolean("isError")
        )
        assertEquals("Core", loaded().primaryMuscle)
        assertFalse(loaded().cardio)
        assertFalse(
            call(
                    "edit_exercise",
                    JSONObject()
                        .put("exerciseId", id)
                        .put("cardio", true)
                        .put("primaryMuscle", "  "),
                )
                .getBoolean("isError")
        )
        assertEquals("", loaded().primaryMuscle)
        assertTrue(loaded().cardio)
        assertTrue(
            call(
                    "create_exercise",
                    JSONObject()
                        .put("name", "Bad cardio")
                        .put("cardio", true)
                        .put("primaryMuscle", "Unknown"),
                )
                .getBoolean("isError")
        )
    }

    @Test
    fun createsTimedExerciseWithAllOptions() {
        val result =
            call(
                "create_exercise",
                JSONObject()
                    .put("name", "Measured hold")
                    .put("primaryMuscle", "grip")
                    .put("secondaryMuscles", "Forearms, rear delts")
                    .put("type", "TIMED")
                    .put("weighted", true)
                    .put("liveMeasurement", true)
                    .put("asymmetric", true)
                    .put("cardio", true)
                    .put("minimumHoldSeconds", 15),
            )
        assertFalse(result.getBoolean("isError"))
        val id = result.getJSONObject("structuredContent").getJSONObject("exercise").getString("id")
        assertEquals(
            Exercise(
                id,
                "Measured hold",
                ExerciseType.TIMED,
                true,
                "Grip",
                "Forearms, Rear deltoids",
                minimumHoldSeconds = 15,
                custom = true,
                weighted = true,
                liveMeasurement = true,
                cardio = true,
            ),
            LofitRepository(context).data.exercises.single { it.id == id },
        )
    }

    @Test
    fun failedExerciseStorageWritesRollBackWithoutNotifying() {
        val existing =
            Exercise(
                "custom",
                "Original",
                ExerciseType.REPETITIONS,
                false,
                "Chest",
                "",
                custom = true,
            )
        repository.data.exercises.add(existing)
        repository.save()
        val stored = context.repositoryStateFile.readText()
        val before = repository.data.exercises.toList()
        val blockedWrite = File(context.repositoryStateFile.path + ".new")
        assertTrue(blockedWrite.mkdir())
        try {
            listOf(
                    "create_exercise" to
                        JSONObject().put("name", "New").put("primaryMuscle", "Chest"),
                    "edit_exercise" to
                        JSONObject().put("exerciseId", existing.id).put("name", "Changed"),
                )
                .forEach { (name, args) ->
                    val result =
                        request("tools/call", JSONObject().put("name", name).put("arguments", args))
                    assertEquals(-32603, result.getJSONObject("error").getInt("code"))
                    assertEquals(before, repository.data.exercises)
                    assertEquals(stored, context.repositoryStateFile.readText())
                    assertEquals(0, changes)
                }
        } finally {
            assertTrue(blockedWrite.delete())
        }
    }

    @Test
    fun exerciseWriteNotificationsNeverMutateData() {
        val before = repository.data.exercises.toList()
        listOf("create_exercise", "edit_exercise").forEach { name ->
            assertNull(
                protocol.handle(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("method", "tools/call")
                        .put(
                            "params",
                            JSONObject()
                                .put("name", name)
                                .put(
                                    "arguments",
                                    JSONObject()
                                        .put("name", "Ignored")
                                        .put("primaryMuscle", "Chest"),
                                ),
                        )
                        .toString()
                )
            )
        }
        assertEquals(before, repository.data.exercises)
        assertEquals(0, changes)
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
