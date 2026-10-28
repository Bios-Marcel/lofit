package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupDataMigrationTest {
    private val context = RepositoryTestContext("schema-migration")
    private val backupKey = "state-before-migration-v1"

    @Before
    fun clearData() {
        context.clearRepositoryTestData()
    }

    @Test
    fun onboarding_oldDataKeepsWorkoutsAndHealthChoiceAndNewChoicesPersist() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Existing workout", completedAt = 200))
        repository.data.bodyMetricsEnabled = true
        repository.saveHistory()
        repository.save()
        val root = JSONObject(stored()).apply {
            remove("biologicalGender")
            remove("onboardingComplete")
        }
        store(root.toString())

        val loaded = LofitRepository(context)
        assertFalse(loaded.data.onboardingComplete)
        assertEquals(BiologicalGender.MALE, loaded.data.biologicalGender)
        assertEquals(true, loaded.data.bodyMetricsEnabled)
        assertEquals("Existing workout", loaded.data.history.single().name)
        loaded.data.biologicalGender = BiologicalGender.FEMALE
        loaded.data.onboardingComplete = true
        loaded.save()

        val restarted = LofitRepository(context)
        assertTrue(restarted.data.onboardingComplete)
        assertEquals(BiologicalGender.FEMALE, restarted.data.biologicalGender)
        assertEquals(true, restarted.data.bodyMetricsEnabled)
        assertEquals("Existing workout", restarted.data.history.single().name)
    }

    @Test
    fun bodyMetricsConsent_remembersAllChoicesAndDefaultsToUndecidedForOldData() {
        listOf(null, false, true).forEach { choice ->
            val repository = LofitRepository(context)
            repository.data.bodyMetricsEnabled = choice
            repository.save()
            assertEquals(choice, LofitRepository(context).data.bodyMetricsEnabled)
        }
        val root = JSONObject(stored()).apply { remove("bodyMetricsEnabled") }
        store(root.toString())
        assertNull(LofitRepository(context).data.bodyMetricsEnabled)
    }

    @Test
    fun bodyWeight_survivesRestartFinishAndResume() {
        val repository = LofitRepository(context)
        repository.data.activeWorkout = WorkoutSession(name = "Weighted", bodyWeightKg = 72.25)
        repository.save()
        val restarted = LofitRepository(context)
        val workout = restarted.data.activeWorkout!!
        assertEquals(72.25, workout.bodyWeightKg!!, 0.0)
        workout.finish()
        restarted.data.activeWorkout = null
        restarted.data.history.add(workout)
        restarted.saveHistory()
        restarted.save()

        val saved = LofitRepository(context)
        assertEquals(72.25, saved.data.history.single().bodyWeightKg!!, 0.0)
        assertEquals(72.25, saved.data.resumeWorkout(workout.id)!!.bodyWeightKg!!, 0.0)
    }

    @Test
    fun bodyWeight_roundsStoredAndExistingWorkoutsToTwoDecimalPlaces() {
        val repository = LofitRepository(context)
        repository.data.activeWorkout = WorkoutSession(name = "Active", bodyWeightKg = 64.020001002032)
        repository.data.history.add(WorkoutSession(name = "Past", bodyWeightKg = 72.255))
        repository.saveHistory()
        repository.save()

        val root = JSONObject(stored())
        assertEquals(64.02, root.getJSONObject("activeWorkout").getDouble("bodyWeightKg"), 0.0)
        val history = org.json.JSONArray(context.repositoryHistoryFile.readText())
        assertEquals(72.26, history.getJSONObject(0).getDouble("bodyWeightKg"), 0.0)

        root.getJSONObject("activeWorkout").put("bodyWeightKg", 64.020001002032)
        history.getJSONObject(0).put("bodyWeightKg", 72.255)
        store(root.toString())
        context.repositoryHistoryFile.writeText(history.toString())
        val loaded = LofitRepository(context)
        assertEquals(64.02, loaded.data.activeWorkout!!.bodyWeightKg!!, 0.0)
        assertEquals(72.26, loaded.data.history.single().bodyWeightKg!!, 0.0)
        loaded.save()
        assertEquals(64.02, JSONObject(stored()).getJSONObject("activeWorkout").getDouble("bodyWeightKg"), 0.0)
    }

    @Test
    fun oldWorkoutsWithoutBodyWeight_stillLoad() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Old", completedAt = 200))
        repository.data.activeWorkout = WorkoutSession(name = "Active")
        repository.saveHistory()
        repository.save()
        val root = JSONObject(stored())
        val history = org.json.JSONArray(context.repositoryHistoryFile.readText())
        history.getJSONObject(0).remove("bodyWeightKg")
        root.getJSONObject("activeWorkout").remove("bodyWeightKg")
        store(root.toString())
        context.repositoryHistoryFile.writeText(history.toString())

        val loaded = LofitRepository(context).data
        assertNull(loaded.history.single().bodyWeightKg)
        assertNull(loaded.activeWorkout!!.bodyWeightKg)
    }

    @Test
    fun startup_migratesAllOldTypesAndFieldsAndKeepsExactBackup() {
        val raw = """{
            "exercises":[
                {"id":"reps","name":"Reps","type":"WEIGHTED_REPS","custom":true},
                {"id":"hold","name":"Hold","type":"WEIGHTED_HOLD","custom":true},
                {"id":"iso","name":"Iso","type":"ISOMETRIC","custom":true},
                {"id":"stretch","name":"Stretch","type":"STRETCH","custom":true},
                {"id":"retired","name":"Retired builtin","type":"REPETITIONS"}
            ],
            "routines":[{"id":"routine","name":"Keep routine","exercises":[
                {"exerciseId":"hold","targetSets":2,"targetReps":4,"targetHoldSeconds":45}
            ]}],
            "history":[{"id":"past","name":"Keep history","startedAt":100,"completedAt":200,
                "exercises":[{"id":"past-item","exerciseId":"hold","sets":[
                    {"id":"log","type":"FAILURE","weightKg":12.5,"holdSeconds":42,"side":"LEFT","completedAt":180}
                ]}]}],
            "activeWorkout":{"id":"live","name":"Keep live","startedAt":300,"exercises":[
                {"id":"live-item","exerciseId":"reps","targetSets":2,"targetReps":6,"sets":[
                    {"id":"live-log","type":"WARMUP","weightKg":22.5,"reps":5,"completedAt":310}
                ]}
            ]},
            "soundCuesEnabled":false
        }"""
        store(raw)

        val data = LofitRepository(context).data

        assertEquals(raw, File(context.filesDir, "$backupKey.json").readText())
        assertEquals(3, JSONObject(stored()).getInt("schemaVersion"))
        assertFalse(stored().contains("targetSets"))
        assertFalse(stored().contains("targetReps"))
        assertFalse(stored().contains("targetHoldSeconds"))
        assertEquals(ExerciseType.REPETITIONS, data.exercises.single { it.id == "reps" }.type)
        listOf("hold", "iso", "stretch").forEach { id ->
            assertEquals(ExerciseType.TIMED, data.exercises.single { it.id == id }.type)
        }
        assertEquals("Retired builtin", data.exercises.single { it.id == "retired" }.name)
        val routine = data.routines.single().exercises.single()
        assertEquals(2, routine.plannedSets.size)
        assertTrue(routine.plannedSets.all { it.reps == 4 && it.holdSeconds == 45 })
        assertFalse(data.soundCuesEnabled)
        val past = data.history.single()
        assertEquals(100L, past.startedAt)
        assertEquals(200L, past.completedAt)
        val pastItem = past.exercises.single()
        val log = pastItem.sets.single()
        assertEquals("log", log.id)
        assertEquals(180L, log.completedAt)
        assertEquals(SetType.FAILURE, log.type)
        assertEquals(12.5, log.weightKg, 0.0)
        assertEquals(Side.LEFT, log.side)
        assertEquals(42, log.holdSeconds)
        assertEquals(pastItem.plannedSets.single().id, log.plannedSetId)
        assertEquals(42, pastItem.plannedSets.single().actualHoldSeconds)
        val live = data.activeWorkout!!
        assertEquals("live", live.id)
        assertNull(live.completedAt)
        val liveItem = live.exercises.single()
        assertEquals(2, liveItem.plannedSets.size)
        assertEquals(22.5, liveItem.plannedSets.first().weightKg, 0.0)
        assertEquals(5, liveItem.plannedSets.first().reps)
        assertEquals(liveItem.plannedSets.first().id, liveItem.sets.single().plannedSetId)
    }

    @Test
    fun startup_runsOnceAndKeepsIdsAndLaterEditsAcrossRestarts() {
        val raw = """{"exercises":[],"routines":[{"id":"r","name":"Old","exercises":[{"exerciseId":"plank"}]}]}"""
        store(raw)
        val first = LofitRepository(context)
        val migrated = stored()
        val routineItem = first.data.routines.single().exercises.single()
        val ids = routineItem.plannedSets.map { it.id }

        val second = LofitRepository(context)
        assertEquals(migrated, stored())
        assertEquals(routineItem.id, second.data.routines.single().exercises.single().id)
        assertEquals(ids, second.data.routines.single().exercises.single().plannedSets.map { it.id })
        second.data.routines.single().name = "Edited"
        second.data.routines.single().exercises.single().plannedSets.clear()
        second.save()

        val saved = stored()
        val third = LofitRepository(context)
        assertEquals("Edited", third.data.routines.single().name)
        assertTrue(third.data.routines.single().exercises.single().plannedSets.isEmpty())
        assertEquals(saved, stored())
        assertEquals(raw, File(context.filesDir, "$backupKey.json").readText())
    }

    @Test
    fun startup_keepsExistingLinksAndMatchesHoldTimesById() {
        store("""{"exercises":[],"history":[{"id":"w","name":"Sparse","startedAt":100,"exercises":[
            {"id":"e","exerciseId":"plank","plannedSets":[
                {"id":"a","holdSeconds":30},
                {"id":"b","holdSeconds":45},
                {"id":"c","holdSeconds":60,"actualHoldSeconds":12}
            ],"sets":[
                {"id":"l1","type":"NORMAL","holdSeconds":44,"plannedSetId":"b"},
                {"id":"l2","type":"NORMAL","holdSeconds":29,"plannedSetId":"a"},
                {"id":"l3","type":"NORMAL","holdSeconds":55,"plannedSetId":"c"},
                {"id":"l4","type":"NORMAL","holdSeconds":70,"plannedSetId":"missing"},
                {"id":"l5","type":"NORMAL","holdSeconds":80}
            ]}
        ]}]}""")

        val item = LofitRepository(context).data.history.single().exercises.single()

        assertEquals(listOf("b", "a", "c", "missing"), item.sets.take(4).map { it.plannedSetId })
        assertEquals(listOf(29, 44, 12, 70, 80), item.plannedSets.map { it.actualHoldSeconds })
        assertEquals(5, item.plannedSets.size)
        assertTrue(item.sets.all { log -> item.plannedSets.any { it.id == log.plannedSetId } })
        val saved = stored()
        assertEquals(item, LofitRepository(context).data.history.single().exercises.single())
        assertEquals(saved, stored())
    }

    @Test
    fun startup_doesNotRebuildExplicitlyEmptyPlans() {
        store("""{"exercises":[],"routines":[{"id":"r","name":"Empty","exercises":[
            {"exerciseId":"plank","targetSets":3,"plannedSets":[]}
        ]}]}""")

        assertTrue(LofitRepository(context).data.routines.single().exercises.single().plannedSets.isEmpty())
        assertTrue(LofitRepository(context).data.routines.single().exercises.single().plannedSets.isEmpty())
    }

    @Test
    fun startup_preservesCurrentUnversionedData() {
        val repository = LofitRepository(context)
        val data = repository.data
        data.exercises.add(Exercise(
            id = "custom", name = "My hold", type = ExerciseType.TIMED, asymmetric = true,
            primaryMuscle = "Core", secondaryMuscles = "Glutes", minimumHoldSeconds = 55,
            custom = true, imageName = "my_image", weighted = false,
        ))
        val plan = PlannedSet(
            id = "plan", type = SetType.FAILURE, weightKg = 17.5, reps = 7,
            holdSeconds = 55, actualHoldSeconds = 49, side = Side.RIGHT,
        )
        data.routines.add(Routine(
            name = "My routine", defaultRestSeconds = 75,
            exercises = mutableListOf(RoutineExercise(
                exerciseId = "custom", restSecondsOverride = 40,
                plannedSets = mutableListOf(plan.copy(actualHoldSeconds = null)),
            )),
        ))
        val workout = WorkoutSession(
            name = "My workout", startedAt = 100, completedAt = 250, defaultRestSeconds = 80,
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "custom", restSecondsOverride = 35, plannedSets = mutableListOf(plan),
                sets = mutableListOf(LoggedSet(
                    id = "log", type = SetType.FAILURE, weightKg = 17.5, reps = 7,
                    holdSeconds = 49, side = Side.RIGHT, completedAt = 200, plannedSetId = plan.id,
                )),
            )),
        )
        data.history.add(workout)
        data.activeWorkout = workout.copy(id = "active", intervals = mutableListOf(WorkoutInterval(100)))
        data.soundCuesEnabled = false
        repository.saveHistory()
        repository.save()
        val root = JSONObject(stored()).apply {
            remove("schemaVersion")
            put("history", org.json.JSONArray(context.repositoryHistoryFile.readText()))
        }
        val raw = root.toString()
        store(raw)

        assertEquals(data, LofitRepository(context).data)
        assertEquals(data, LofitRepository(context).data)
        assertEquals(raw, File(context.filesDir, "$backupKey.json").readText())
    }

    @Test
    fun startup_migratesVersionOneWorkoutTimesAndKeepsBackup() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Past", startedAt = 100, completedAt = 250))
        repository.data.activeWorkout = WorkoutSession(name = "Active", startedAt = 300)
        repository.saveHistory()
        repository.save()
        val root = JSONObject(stored()).apply {
            put("schemaVersion", 1)
            put("history", org.json.JSONArray(context.repositoryHistoryFile.readText()))
        }
        listOf(root.getJSONArray("history").getJSONObject(0), root.getJSONObject("activeWorkout")).forEach { workout ->
            val interval = workout.getJSONArray("intervals").getJSONObject(0)
            workout.put("startedAt", interval.getLong("startedAt"))
            workout.put("completedAt", interval.get("endedAt"))
            workout.remove("intervals")
        }
        val raw = root.toString()
        store(raw)

        val loaded = LofitRepository(context)

        assertEquals(listOf(WorkoutInterval(100, 250)), loaded.data.history.single().intervals)
        assertEquals(listOf(WorkoutInterval(300)), loaded.data.activeWorkout!!.intervals)
        assertEquals(raw, File(context.filesDir, "state-before-migration-v2.json").readText())
        val migrated = stored()
        assertEquals(3, JSONObject(migrated).getInt("schemaVersion"))
        assertFalse(org.json.JSONArray(context.repositoryHistoryFile.readText()).getJSONObject(0).has("startedAt"))
        assertEquals(loaded.data, LofitRepository(context).data)
        assertEquals(migrated, stored())
    }

    @Test
    fun workoutIntervals_surviveRestartsWhileActiveAndAfterFinishing() {
        val repository = LofitRepository(context)
        repository.data.activeWorkout = WorkoutSession(
            name = "Resumed",
            intervals = mutableListOf(WorkoutInterval(100, 250), WorkoutInterval(500, 700), WorkoutInterval(900)),
        )
        repository.save()

        val restarted = LofitRepository(context)
        assertEquals(repository.data, restarted.data)
        val workout = restarted.data.activeWorkout!!
        assertEquals(450L, workout.durationMillis(1_000))
        workout.finish(1_100)
        restarted.data.history.add(workout)
        restarted.data.activeWorkout = null
        restarted.saveHistory()
        restarted.save()

        val saved = LofitRepository(context).data.history.single()
        assertEquals(listOf(WorkoutInterval(100, 250), WorkoutInterval(500, 700), WorkoutInterval(900, 1_100)), saved.intervals)
        assertEquals(100L, saved.startedAt)
        assertEquals(1_100L, saved.completedAt)
        assertEquals(550L, saved.durationMillis(5_000))
    }

    @Test
    fun startup_currentSchemaDoesNotRepairOrRewriteData() {
        val repository = LofitRepository(context)
        val plan = PlannedSet(id = "p", holdSeconds = 45)
        repository.data.history.add(WorkoutSession(name = "Current", exercises = mutableListOf(
            WorkoutExercise(exerciseId = "plank", plannedSets = mutableListOf(plan), sets = mutableListOf(
                LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 42, plannedSetId = plan.id),
            )),
        )))
        repository.saveHistory()
        repository.save()
        val raw = stored()

        val loaded = LofitRepository(context)

        assertNull(loaded.data.history.single().exercises.single().plannedSets.single().actualHoldSeconds)
        assertEquals(raw, stored())
        assertFalse(File(context.filesDir, "$backupKey.json").exists())
    }

    @Test
    fun startup_failedMigrationLeavesOriginalUntouchedAndCanRetry() {
        val raw = """{"exercises":[{"id":"bad","name":"Bad","type":"UNKNOWN"}]}"""
        store(raw)

        assertThrows(IllegalArgumentException::class.java) { LofitRepository(context) }
        assertEquals(raw, stored())
        assertFalse(File(context.filesDir, "$backupKey.json").exists())

        store(raw.replace("UNKNOWN", "STRETCH"))
        assertEquals(ExerciseType.TIMED, LofitRepository(context).data.exercises.single { it.id == "bad" }.type)
        assertTrue(File(context.filesDir, "$backupKey.json").exists())
    }

    @Test
    fun startup_badJsonAndNewerSchemaNeverBecomeEmptyData() {
        listOf("broken JSON", """{"schemaVersion":4,"exercises":[]}""").forEach { raw ->
            store(raw)
            assertThrows(Exception::class.java) { LofitRepository(context) }
            assertEquals(raw, stored())
            assertFalse(File(context.filesDir, "$backupKey.json").exists())
        }
    }

    @Test
    fun startup_versionedLegacyTypesAreRejectedWithoutChangingState() {
        val repository = LofitRepository(context)
        repository.data.exercises.add(Exercise(
            id = "custom", name = "Custom", type = ExerciseType.TIMED,
            asymmetric = false, primaryMuscle = "Core", secondaryMuscles = "", custom = true,
        ))
        repository.save()
        val root = JSONObject(stored())
        val exercises = root.getJSONArray("exercises")
        exercises.getJSONObject(exercises.length() - 1).put("type", "STRETCH")
        val raw = root.toString()
        store(raw)

        assertThrows(IllegalArgumentException::class.java) { LofitRepository(context) }
        assertEquals(raw, stored())
        assertFalse(File(context.filesDir, "$backupKey.json").exists())
    }

    private fun store(raw: String) {
        context.repositoryStateFile.writeText(raw)
    }

    private fun stored(): String = context.repositoryStateFile.readText()
}
