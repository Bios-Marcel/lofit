package lofit

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JsonStorageMigrationTest {
    private val context = RepositoryTestContext("json-storage")
    private val preferences = context.getSharedPreferences("lofit-data", 0)
    private val stateFile
        get() = context.repositoryStateFile

    @Before
    fun clearTestData() {
        context.clearRepositoryTestData()
    }

    @Test
    fun xmlMigration_copiesExactJsonAndPreservesEveryXmlByteAndAllData() {
        val raw = sampleJson()
        val xml = seedXml(raw)

        val migrated = LofitRepository(context)

        assertEquals(raw, stateFile.readText())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        val jsonOnlyContext =
            object : ContextWrapper(context) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    error("JSON loads must not access XML preferences")
            }
        assertEquals(migrated.data, LofitRepository(jsonOnlyContext).data)
        assertEquals("custom.webp", migrated.data.exercises.single { it.id == "custom" }.imageFile)
        assertEquals(123L, migrated.data.exerciseLastSelectedAt["custom"])
        assertFalse(migrated.data.exerciseRecords.isEmpty())

        migrated.data.routines.single().name = "Edited after migration"
        migrated.save()
        assertEquals(
            "Edited after migration",
            LofitRepository(jsonOnlyContext).data.routines.single().name,
        )
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
    }

    @Test
    fun reset_writesFreshJsonWithoutDeletingXmlOrRestoringOldWorkouts() {
        val xml = seedXml(sampleJson())
        val repository = LofitRepository(context)
        assertFalse(repository.data.history.isEmpty())

        repository.reset()

        assertEquals(AppData(), repository.data)
        assertEquals(AppData(), LofitRepository(context).data)
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
    }

    @Test
    fun newInstall_savesAndRestartsUsingJsonWithoutCreatingXml() {
        val repository = LofitRepository(context)
        assertEquals(AppData(), repository.data)
        val xml = context.legacyXmlFile.readBytes()
        repository.data.soundCuesEnabled = false

        repository.save()

        assertFalse(JSONObject(stateFile.readText()).getBoolean("soundCuesEnabled"))
        assertFalse(LofitRepository(context).data.soundCuesEnabled)
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        assertFalse(preferences.contains("state"))
    }

    @Test
    fun unchangedHistory_isNeverRewrittenAndChangesSurviveRestart() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Past", completedAt = 200))
        repository.saveHistory()
        repository.save()
        assertFalse(JSONObject(stateFile.readText()).has("history"))
        val savedHistory = context.repositoryHistoryFile.readText()
        val pending = File("${context.repositoryHistoryFile.path}.new")
        assertTrue(pending.mkdir())

        repository.data.routines.add(Routine(name = "New routine"))
        repository.data.activeWorkout = WorkoutSession(name = "Active")
        repository.save()
        assertEquals(savedHistory, context.repositoryHistoryFile.readText())

        repository.data.history.add(WorkoutSession(name = "New past", completedAt = 300))
        repository.save()
        assertEquals(savedHistory, context.repositoryHistoryFile.readText())
        assertThrows(IOException::class.java) { repository.saveHistory() }
        assertTrue(pending.delete())
        repository.saveHistory()
        val loaded = LofitRepository(context).data
        assertEquals("New routine", loaded.routines.single().name)
        assertEquals(listOf("Past", "New past"), loaded.history.map { it.name })
    }

    @Test
    fun saveHistory_doesNotWriteMainState() {
        val repository = LofitRepository(context)
        repository.save()
        val state = stateFile.readText()
        val pending = File("${stateFile.path}.new")
        assertTrue(pending.mkdir())

        repository.data.history.add(WorkoutSession(name = "Past"))
        repository.saveHistory()

        assertEquals(state, stateFile.readText())
        assertTrue(pending.delete())
        assertEquals("Past", LofitRepository(context).data.history.single().name)
    }

    @Test
    fun versionTwoMigration_movesHistoryAndKeepsOriginalBackup() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Past", completedAt = 200))
        repository.saveHistory()
        repository.save()
        val legacy =
            JSONObject(stateFile.readText())
                .apply {
                    put("schemaVersion", 2)
                    put("history", JSONArray(context.repositoryHistoryFile.readText()))
                }
                .toString()
        stateFile.writeText(legacy)
        AtomicFile(context.repositoryHistoryFile).delete()

        val loaded = LofitRepository(context)

        assertEquals("Past", loaded.data.history.single().name)
        assertFalse(JSONObject(stateFile.readText()).has("history"))
        assertEquals(4, JSONObject(stateFile.readText()).getInt("schemaVersion"))
        assertEquals(legacy, File(context.filesDir, "state-before-migration-v3.json").readText())
        assertEquals(loaded.data, LofitRepository(context).data)
    }

    @Test
    fun versionThreeMigration_rewritesLegacyExerciseIdsAcrossStateAndHistory() {
        val repository = LofitRepository(context)
        val ids = listOf("barbell-curl", "weighted-crunches")
        val routine =
            Routine(
                name = "Old routine",
                exercises = ids.map { RoutineExercise(exerciseId = it) }.toMutableList(),
            )
        repository.data.routines.add(routine)
        repository.data.activeWorkout =
            WorkoutSession(
                name = "Active",
                sourceRoutine = routine,
                exercises = ids.map { WorkoutExercise(exerciseId = it) }.toMutableList(),
            )
        repository.data.history.add(
            WorkoutSession(
                name = "Past",
                completedAt = 200,
                sourceRoutine = routine,
                exercises = ids.map { WorkoutExercise(exerciseId = it) }.toMutableList(),
            )
        )
        val exercise = repository.data.exercises.first { it.id == "barbell-curl" }
        val set = LoggedSet(type = SetType.NORMAL, weightKg = 12.0, reps = 8)
        repository.data.exerciseRecords[exercise.id] =
            mutableListOf(ExerciseRecord(exercise, set, set))
        repository.data.exerciseLastSelectedAt[exercise.id] = 123L
        repository.saveHistory()
        repository.save()

        val legacyIds =
            mapOf(
                "barbell-curl" to "fedb-barbell-curl",
                "weighted-crunches" to "weighted-fedb-crunches",
            )
        fun oldId(id: String) = legacyIds[id] ?: id
        fun changeRoutine(value: JSONObject) {
            val items = value.getJSONArray("exercises")
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                item.put("exerciseId", oldId(item.getString("exerciseId")))
            }
        }
        fun changeWorkout(value: JSONObject) {
            changeRoutine(value.getJSONObject("sourceRoutine"))
            val items = value.getJSONArray("exercises")
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                item.put("exerciseId", oldId(item.getString("exerciseId")))
            }
        }
        val root = JSONObject(stateFile.readText()).put("schemaVersion", 3)
        val exercises = root.getJSONArray("exercises")
        for (index in 0 until exercises.length()) {
            val item = exercises.getJSONObject(index)
            item.put("id", oldId(item.getString("id")))
        }
        changeRoutine(root.getJSONArray("routines").getJSONObject(0))
        changeWorkout(root.getJSONObject("activeWorkout"))
        val records = root.getJSONObject("exerciseRecords")
        records.put(oldId(exercise.id), records.remove(exercise.id))
        val selected = root.getJSONObject("exerciseLastSelectedAt")
        selected.put(oldId(exercise.id), selected.remove(exercise.id))
        val history = JSONArray(context.repositoryHistoryFile.readText())
        changeWorkout(history.getJSONObject(0))
        val oldState = root.toString()
        val oldHistory = history.toString()
        stateFile.writeText(oldState)
        context.repositoryHistoryFile.writeText(oldHistory)

        val migrated = LofitRepository(context).data

        assertEquals(4, JSONObject(stateFile.readText()).getInt("schemaVersion"))
        assertEquals(ids, migrated.routines.single().exercises.map { it.exerciseId })
        assertEquals(ids, migrated.activeWorkout!!.exercises.map { it.exerciseId })
        assertEquals(ids, migrated.activeWorkout!!.sourceRoutine!!.exercises.map { it.exerciseId })
        assertEquals(ids, migrated.history.single().exercises.map { it.exerciseId })
        assertEquals(ids, migrated.history.single().sourceRoutine!!.exercises.map { it.exerciseId })
        assertEquals(1, migrated.exerciseRecords.getValue(exercise.id).size)
        assertEquals(123L, migrated.exerciseLastSelectedAt[exercise.id])
        assertFalse(migrated.exercises.any { it.id in legacyIds.values })
        assertEquals(oldState, File(context.filesDir, "state-before-migration-v4.json").readText())
        assertEquals(
            oldHistory,
            File(context.filesDir, "history-before-migration-v4.json").readText(),
        )
        assertEquals(migrated, LofitRepository(context).data)
    }

    @Test
    fun failedVersionTwoMigration_keepsEmbeddedHistoryForRetry() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Past", completedAt = 200))
        repository.saveHistory()
        repository.save()
        val legacy =
            JSONObject(stateFile.readText())
                .apply {
                    put("schemaVersion", 2)
                    put("history", JSONArray(context.repositoryHistoryFile.readText()))
                }
                .toString()
        stateFile.writeText(legacy)
        AtomicFile(context.repositoryHistoryFile).delete()
        val pending = File("${context.repositoryHistoryFile.path}.new")
        assertTrue(pending.mkdir())

        assertThrows(IOException::class.java) { LofitRepository(context) }
        assertEquals(legacy, stateFile.readText())
        assertTrue(pending.delete())
        assertEquals("Past", LofitRepository(context).data.history.single().name)
    }

    @Test
    fun missingHistory_rejectsCurrentState() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Past"))
        repository.saveHistory()
        repository.save()
        val state = stateFile.readText()
        AtomicFile(context.repositoryHistoryFile).delete()

        assertThrows(Exception::class.java) { LofitRepository(context) }
        assertEquals(state, stateFile.readText())
    }

    @Test
    fun invalidOrFutureJson_doesNotFallBackToStaleXmlOrOverwriteEitherFile() {
        val xml = seedXml(sampleJson())
        listOf("broken JSON", """{"schemaVersion":5}""").forEach { raw ->
            stateFile.writeText(raw)

            assertThrows(Exception::class.java) { LofitRepository(context) }

            assertEquals(raw, stateFile.readText())
            assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        }
    }

    @Test
    fun unsupportedOldSchemas_leaveStateUntouched() {
        listOf("""{"exercises":[]}""", """{"schemaVersion":1}""").forEach { raw ->
            stateFile.writeText(raw)

            assertThrows(IllegalStateException::class.java) { LofitRepository(context) }

            assertEquals(raw, stateFile.readText())
            assertFalse(File(context.filesDir, "state-before-migration-v3.json").exists())
        }
    }

    @Test
    fun invalidXmlState_doesNotCreateJsonAndCanRetryAfterCorrection() {
        val valid = sampleJson()
        listOf(
                "broken JSON",
                """{"schemaVersion":5}""",
                """{"exercises":[{"id":"bad","name":"Bad","type":"UNKNOWN"}]}""",
            )
            .forEach { raw ->
                val xml = seedXml(raw)

                assertThrows(Exception::class.java) { LofitRepository(context) }

                assertFalse(stateFile.exists())
                assertArrayEquals(xml, context.legacyXmlFile.readBytes())
            }
        val xml = seedXml(valid)
        LofitRepository(context)
        assertEquals(valid, stateFile.readText())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
    }

    @Test
    fun failedMigrationWrite_preservesXmlAndRetriesOnNextLoad() {
        val raw = sampleJson()
        val xml = seedXml(raw)
        val pending = File("${stateFile.path}.new")
        assertTrue(pending.mkdir())

        assertThrows(IOException::class.java) { LofitRepository(context) }

        assertFalse(stateFile.exists())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        assertTrue(pending.delete())
        LofitRepository(context)
        assertEquals(raw, stateFile.readText())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
    }

    @Test
    fun failedSave_keepsPreviousJsonAndXml() {
        val raw = sampleJson()
        val xml = seedXml(raw)
        val repository = LofitRepository(context)
        repository.data.soundCuesEnabled = !repository.data.soundCuesEnabled
        val pending = File("${stateFile.path}.new")
        assertTrue(pending.mkdir())

        assertThrows(IOException::class.java) { repository.save() }

        assertEquals(raw, stateFile.readText())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        assertTrue(pending.delete())
        assertEquals(
            JSONObject(raw).getBoolean("soundCuesEnabled"),
            LofitRepository(context).data.soundCuesEnabled,
        )
    }

    @Test
    fun interruptedFirstWrite_retriesMigrationFromIntactXml() {
        val raw = sampleJson()
        val xml = seedXml(raw)
        File("${stateFile.path}.new").writeText("partial JSON")

        LofitRepository(context)

        assertEquals(raw, stateFile.readText())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
    }

    @Test
    fun interruptedReplacement_recoversAtomicBackupInsteadOfStaleXml() {
        val raw = sampleJson()
        val xml = seedXml(raw)
        val repository = LofitRepository(context)
        repository.data.routines.single().name = "Newer JSON"
        repository.save()
        val saved = stateFile.readText()
        assertTrue(stateFile.renameTo(File("${stateFile.path}.bak")))
        stateFile.writeText("partial replacement")

        assertEquals("Newer JSON", LofitRepository(context).data.routines.single().name)

        assertEquals(saved, stateFile.readText())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
    }

    @Test
    fun schemaMigration_keepsAnExistingJsonBackup() {
        val raw =
            JSONObject(sampleJson())
                .put("schemaVersion", 2)
                .put("history", JSONArray(context.repositoryHistoryFile.readText()))
                .toString()
        stateFile.writeText(raw)
        val backup = File(context.filesDir, "state-before-migration-v3.json")
        backup.writeText("original backup")

        LofitRepository(context)

        assertEquals("original backup", backup.readText())
        assertEquals(4, JSONObject(stateFile.readText()).getInt("schemaVersion"))
    }

    private fun seedXml(raw: String): ByteArray {
        assertTrue(preferences.edit().putString("state", raw).commit())
        return context.legacyXmlFile.readBytes()
    }

    private fun sampleJson(): String {
        val repository = LofitRepository(context)
        val exercise =
            Exercise(
                "custom",
                "Hold 🏋 & <test>",
                ExerciseType.TIMED,
                true,
                "Core",
                "Glutes",
                custom = true,
                weighted = true,
                imageFile = "custom.webp",
            )
        val planned =
            PlannedSet(weightKg = 12.5, holdSeconds = 30, actualHoldSeconds = 25, side = Side.LEFT)
        val routine =
            Routine(
                name = "Strength",
                exercises =
                    mutableListOf(
                        RoutineExercise(
                            exerciseId = exercise.id,
                            note = "Brace & breathe\n\"Slow\"",
                            plannedSets = mutableListOf(planned.copy(actualHoldSeconds = null)),
                        )
                    ),
            )
        val workout =
            WorkoutSession(
                name = "Past",
                startedAt = 100,
                completedAt = 200,
                bodyWeightKg = 72.25,
                sourceRoutine = routine,
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = exercise.id,
                            sourceRoutineExerciseId = routine.exercises.single().id,
                            note = "Keep",
                            previousNote = "Previous",
                            plannedSets = mutableListOf(planned),
                            sets =
                                mutableListOf(
                                    LoggedSet(
                                        type = SetType.NORMAL,
                                        weightKg = 12.5,
                                        holdSeconds = 25,
                                        side = Side.LEFT,
                                        plannedSetId = planned.id,
                                    )
                                ),
                        )
                    ),
            )
        repository.data.apply {
            exercises.add(exercise)
            routines.add(routine)
            history.add(workout)
            activeWorkout =
                workout.copy(id = "active", intervals = mutableListOf(WorkoutInterval(300)))
            recordWorkout(workout)
            exerciseLastSelectedAt[exercise.id] = 123
            soundCuesEnabled = false
            bodyMetricsEnabled = true
            biologicalGender = BiologicalGender.FEMALE
            onboardingComplete = true
        }
        repository.saveHistory()
        repository.save()
        val raw = stateFile.readText()
        AtomicFile(stateFile).delete()
        return raw
    }
}
