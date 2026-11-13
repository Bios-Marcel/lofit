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
class JsonStorageTest {
    private val context = RepositoryTestContext("json-storage")
    private val preferences = context.getSharedPreferences("lofit-data", 0)
    private val stateFile
        get() = context.repositoryStateFile

    private val jsonOnlyContext =
        object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                error("Repository must not access XML preferences")
        }

    @Before
    fun clearTestData() {
        context.clearRepositoryTestData()
    }

    @Test
    fun currentJson_loadsWithoutReadingPreferencesOrRewritingFiles() {
        val raw = sampleJson()
        val history = context.repositoryHistoryFile.readText()
        val xml = seedXml("stale XML data")
        val loaded = LofitRepository(jsonOnlyContext)

        assertEquals(raw, stateFile.readText())
        assertEquals(history, context.repositoryHistoryFile.readText())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        assertEquals(loaded.data, LofitRepository(jsonOnlyContext).data)
        assertEquals("custom.webp", loaded.data.exercises.single { it.id == "custom" }.imageFile)
        assertEquals(123L, loaded.data.exerciseLastSelectedAt["custom"])
        assertFalse(loaded.data.exerciseRecords.isEmpty())

        loaded.data.routines.single().name = "Edited JSON"
        loaded.save()
        assertEquals("Edited JSON", LofitRepository(jsonOnlyContext).data.routines.single().name)
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
    fun newInstall_savesAndRestartsWithoutReadingXml() {
        val repository = LofitRepository(jsonOnlyContext)
        assertEquals(AppData(), repository.data)
        assertFalse(repository.data.recordCelebrationsEnabled)
        val xml = context.legacyXmlFile.readBytes()
        repository.data.soundCuesEnabled = false
        repository.data.recordCelebrationsEnabled = true

        repository.save()

        assertFalse(JSONObject(stateFile.readText()).getBoolean("soundCuesEnabled"))
        assertFalse(LofitRepository(jsonOnlyContext).data.soundCuesEnabled)
        assertTrue(JSONObject(stateFile.readText()).getBoolean("recordCelebrationsEnabled"))
        assertTrue(LofitRepository(jsonOnlyContext).data.recordCelebrationsEnabled)
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        assertFalse(preferences.contains("state"))
    }

    @Test
    fun legacyRecordCelebrations_defaultToOffAndPersistExplicitChoice() {
        val raw = JSONObject(sampleJson()).apply { remove("recordCelebrationsEnabled") }
        stateFile.writeText(raw.toString())
        val repository = LofitRepository(context)
        assertFalse(repository.data.recordCelebrationsEnabled)
        repository.data.recordCelebrationsEnabled = true
        repository.save()
        assertTrue(LofitRepository(context).data.recordCelebrationsEnabled)
        repository.data.recordCelebrationsEnabled = false
        repository.save()
        assertFalse(LofitRepository(context).data.recordCelebrationsEnabled)
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
    fun unsupportedOldSchemas_leaveStateAndHistoryUntouched() {
        val current = sampleJson()
        val history = context.repositoryHistoryFile.readText()
        listOf(null, 0, 1, 2, 3).forEach { version ->
            val raw =
                JSONObject(current)
                    .apply {
                        if (version == null) remove("schemaVersion")
                        else put("schemaVersion", version)
                        put("history", JSONArray(history))
                    }
                    .toString()
            val xml = seedXml(current)
            stateFile.writeText(raw)

            assertThrows(IllegalStateException::class.java) { LofitRepository(jsonOnlyContext) }

            assertEquals(raw, stateFile.readText())
            assertEquals(history, context.repositoryHistoryFile.readText())
            assertArrayEquals(xml, context.legacyXmlFile.readBytes())
            assertFalse(File(context.filesDir, "state-before-migration-v4.json").exists())
            assertFalse(File(context.filesDir, "history-before-migration-v4.json").exists())
        }
    }

    @Test
    fun xmlWithoutJson_isIgnoredRegardlessOfContents() {
        val current = sampleJson()
        listOf(
                current,
                JSONObject(current).put("schemaVersion", 3).toString(),
                "broken JSON",
                """{"schemaVersion":5}""",
            )
            .forEach { raw ->
                val xml = seedXml(raw)
                AtomicFile(stateFile).delete()

                val repository = LofitRepository(jsonOnlyContext)

                assertEquals(AppData(), repository.data)
                assertFalse(stateFile.exists())
                assertEquals("[]", context.repositoryHistoryFile.readText())
                assertArrayEquals(xml, context.legacyXmlFile.readBytes())
                repository.save()
                assertEquals(4, JSONObject(stateFile.readText()).getInt("schemaVersion"))
                assertEquals(AppData(), LofitRepository(jsonOnlyContext).data)
                assertArrayEquals(xml, context.legacyXmlFile.readBytes())
            }
    }

    @Test
    fun failedFirstSave_preservesXmlAndCanRetry() {
        val xml = seedXml(sampleJson())
        AtomicFile(stateFile).delete()
        val repository = LofitRepository(jsonOnlyContext)
        repository.data.routines.add(Routine(name = "Fresh routine"))
        val pending = File("${stateFile.path}.new")
        assertTrue(pending.mkdir())

        assertThrows(IOException::class.java) { repository.save() }

        assertFalse(stateFile.exists())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        assertTrue(pending.delete())
        repository.save()
        assertEquals("Fresh routine", LofitRepository(jsonOnlyContext).data.routines.single().name)
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
    fun interruptedFirstWrite_startsFreshWithoutImportingXml() {
        val xml = seedXml(sampleJson())
        AtomicFile(stateFile).delete()
        File("${stateFile.path}.new").writeText("partial JSON")

        val repository = LofitRepository(jsonOnlyContext)

        assertEquals(AppData(), repository.data)
        assertFalse(stateFile.exists())
        assertArrayEquals(xml, context.legacyXmlFile.readBytes())
        repository.save()
        assertEquals(AppData(), LofitRepository(jsonOnlyContext).data)
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
    fun unsupportedSchema_keepsExistingBackups() {
        val raw = JSONObject(sampleJson()).put("schemaVersion", 3).toString()
        stateFile.writeText(raw)
        val history = context.repositoryHistoryFile.readText()
        val backup = File(context.filesDir, "state-before-migration-v4.json")
        backup.writeText("original backup")
        val historyBackup = File(context.filesDir, "history-before-migration-v4.json")
        historyBackup.writeText("original history backup")

        assertThrows(IllegalStateException::class.java) { LofitRepository(jsonOnlyContext) }

        assertEquals(raw, stateFile.readText())
        assertEquals(history, context.repositoryHistoryFile.readText())
        assertEquals("original backup", backup.readText())
        assertEquals("original history backup", historyBackup.readText())
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
        return stateFile.readText()
    }
}
