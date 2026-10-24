package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupDataMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("lofit-data", 0)
    private val backupKey = "state-before-migration-v1"

    @Before
    fun clearData() {
        assertTrue(preferences.edit().clear().commit())
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

        assertEquals(raw, preferences.getString(backupKey, null))
        assertEquals(1, JSONObject(stored()).getInt("schemaVersion"))
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
        assertEquals(raw, preferences.getString(backupKey, null))
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
        data.activeWorkout = workout.copy(id = "active", completedAt = null)
        data.soundCuesEnabled = false
        repository.save()
        val root = JSONObject(stored()).apply { remove("schemaVersion") }
        val raw = root.toString()
        store(raw)

        assertEquals(data, LofitRepository(context).data)
        assertEquals(data, LofitRepository(context).data)
        assertEquals(raw, preferences.getString(backupKey, null))
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
        repository.save()
        val raw = stored()

        val loaded = LofitRepository(context)

        assertNull(loaded.data.history.single().exercises.single().plannedSets.single().actualHoldSeconds)
        assertEquals(raw, stored())
        assertFalse(preferences.contains(backupKey))
    }

    @Test
    fun startup_failedMigrationLeavesOriginalUntouchedAndCanRetry() {
        val raw = """{"exercises":[{"id":"bad","name":"Bad","type":"UNKNOWN"}]}"""
        store(raw)

        assertThrows(IllegalArgumentException::class.java) { LofitRepository(context) }
        assertEquals(raw, stored())
        assertFalse(preferences.contains(backupKey))

        store(raw.replace("UNKNOWN", "STRETCH"))
        assertEquals(ExerciseType.TIMED, LofitRepository(context).data.exercises.single { it.id == "bad" }.type)
        assertTrue(preferences.contains(backupKey))
    }

    @Test
    fun startup_badJsonAndNewerSchemaNeverBecomeEmptyData() {
        listOf("broken JSON", """{"schemaVersion":2,"exercises":[]}""").forEach { raw ->
            store(raw)
            assertThrows(Exception::class.java) { LofitRepository(context) }
            assertEquals(raw, stored())
            assertFalse(preferences.contains(backupKey))
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
        assertFalse(preferences.contains(backupKey))
    }

    private fun store(raw: String) {
        assertTrue(preferences.edit().putString("state", raw).commit())
    }

    private fun stored(): String = preferences.getString("state", null)!!
}
