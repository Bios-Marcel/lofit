package lofit

import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class JsonStorageMigrationTest {
    private val context = RepositoryTestContext("json-storage")
    private val preferences = context.getSharedPreferences("lofit-data", 0)
    private val stateFile get() = context.repositoryStateFile

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
        val jsonOnlyContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("JSON loads must not access XML preferences")
        }
        assertEquals(migrated.data, LofitRepository(jsonOnlyContext).data)
        assertEquals("custom.webp", migrated.data.exercises.single { it.id == "custom" }.imageFile)
        assertEquals(123L, migrated.data.exerciseLastSelectedAt["custom"])
        assertFalse(migrated.data.exerciseRecords.isEmpty())

        migrated.data.routines.single().name = "Edited after migration"
        migrated.save()
        assertEquals("Edited after migration", LofitRepository(jsonOnlyContext).data.routines.single().name)
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
    }

    @Test
    fun xmlMigration_upgradesOlderSchemasWithoutChangingTheirXmlOrBackups() {
        val current = sampleJson()
        val unversioned = """{"exercises":[],"routines":[{"id":"old","name":"Old","exercises":[{"exerciseId":"plank"}]}]}"""
        val versionOne = JSONObject(current).put("schemaVersion", 1)
            .put("history", org.json.JSONArray(context.repositoryHistoryFile.readText())).toString()
        listOf(unversioned, versionOne).forEachIndexed { index, raw ->
            context.clearRepositoryTestData()
            val xml = seedXml(raw)

            val loaded = LofitRepository(context)

            assertEquals(3, JSONObject(stateFile.readText()).getInt("schemaVersion"))
            assertEquals(raw, File(context.filesDir, "state-before-migration-v${index + 1}.json").readText())
            assertEquals(loaded.data, LofitRepository(context).data)
            assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        }
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
        val legacy = JSONObject(stateFile.readText()).apply {
            put("schemaVersion", 2)
            put("history", JSONArray(context.repositoryHistoryFile.readText()))
        }.toString()
        stateFile.writeText(legacy)
        AtomicFile(context.repositoryHistoryFile).delete()

        val loaded = LofitRepository(context)

        assertEquals("Past", loaded.data.history.single().name)
        assertFalse(JSONObject(stateFile.readText()).has("history"))
        assertEquals(3, JSONObject(stateFile.readText()).getInt("schemaVersion"))
        assertEquals(legacy, File(context.filesDir, "state-before-migration-v3.json").readText())
        assertEquals(loaded.data, LofitRepository(context).data)
    }

    @Test
    fun failedVersionTwoMigration_keepsEmbeddedHistoryForRetry() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Past", completedAt = 200))
        repository.saveHistory()
        repository.save()
        val legacy = JSONObject(stateFile.readText()).apply {
            put("schemaVersion", 2)
            put("history", JSONArray(context.repositoryHistoryFile.readText()))
        }.toString()
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
        listOf("broken JSON", """{"schemaVersion":4}""").forEach { raw ->
            stateFile.writeText(raw)

            assertThrows(Exception::class.java) { LofitRepository(context) }

            assertEquals(raw, stateFile.readText())
            assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        }
    }

    @Test
    fun invalidXmlState_doesNotCreateJsonAndCanRetryAfterCorrection() {
        val valid = sampleJson()
        listOf("broken JSON", """{"schemaVersion":4}""",
            """{"exercises":[{"id":"bad","name":"Bad","type":"UNKNOWN"}]}""").forEach { raw ->
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
        assertEquals(JSONObject(raw).getBoolean("soundCuesEnabled"), LofitRepository(context).data.soundCuesEnabled)
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
        val raw = """{"exercises":[],"routines":[]}"""
        val backup = File(context.filesDir, "state-before-migration-v1.json")
        backup.writeText("original backup")
        val xml = seedXml(raw)

        LofitRepository(context)

        assertEquals("original backup", backup.readText())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
    }

    private fun seedXml(raw: String): ByteArray {
        assertTrue(preferences.edit()
            .putString("state", raw)
            .putString("state-before-migration-v1", "existing XML backup")
            .putString("state-before-migration-v2", "another XML backup")
            .commit())
        return context.legacyXmlFile.readBytes()
    }

    private fun sampleJson(): String {
        val repository = LofitRepository(context)
        val exercise = Exercise("custom", "Hold 🏋 & <test>", ExerciseType.TIMED, true,
            "Core", "Glutes", custom = true, weighted = true, imageFile = "custom.webp")
        val planned = PlannedSet(weightKg = 12.5, holdSeconds = 30, actualHoldSeconds = 25, side = Side.LEFT)
        val routine = Routine(name = "Strength", exercises = mutableListOf(
            RoutineExercise(exerciseId = exercise.id, note = "Brace & breathe\n\"Slow\"",
                plannedSets = mutableListOf(planned.copy(actualHoldSeconds = null)))))
        val workout = WorkoutSession(name = "Past", startedAt = 100, completedAt = 200,
            bodyWeightKg = 72.25, sourceRoutine = routine,
            exercises = mutableListOf(WorkoutExercise(exerciseId = exercise.id,
                sourceRoutineExerciseId = routine.exercises.single().id,
                note = "Keep", previousNote = "Previous", plannedSets = mutableListOf(planned),
                sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 12.5,
                    holdSeconds = 25, side = Side.LEFT, plannedSetId = planned.id)))))
        repository.data.apply {
            exercises.add(exercise)
            routines.add(routine)
            history.add(workout)
            activeWorkout = workout.copy(id = "active", intervals = mutableListOf(WorkoutInterval(300)))
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
