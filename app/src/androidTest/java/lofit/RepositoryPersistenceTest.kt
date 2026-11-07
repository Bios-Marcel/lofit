package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryPersistenceTest {
    private val context = RepositoryTestContext("repository-persistence")
    private val backupKey = "state-before-migration-v3"

    @Before
    fun clearData() {
        context.clearRepositoryTestData()
    }

    @Test
    fun cardioPersistsAndDefaultsOffForExistingExercises() {
        val repository = LofitRepository(context)
        assertTrue(repository.data.exercises.all { !it.cardio })
        val exercise =
            repository.data.exercises
                .first()
                .copy(id = "custom-cardio", custom = true, cardio = true)
        repository.data.exercises.add(exercise)
        repository.save()
        val loaded = LofitRepository(context)
        assertEquals(exercise, loaded.data.exercises.single { it.id == exercise.id })
        assertTrue(loaded.data.exercises.filter { !it.custom }.all { !it.cardio })

        loaded.data.exercises.single { it.id == exercise.id }.cardio = false
        loaded.save()
        assertEquals(
            exercise.copy(cardio = false),
            LofitRepository(context).data.exercises.single { it.id == exercise.id },
        )

        val root = JSONObject(stored())
        val exercises = root.getJSONArray("exercises")
        for (index in 0 until exercises.length()) exercises.getJSONObject(index).remove("cardio")
        store(root.toString())
        val legacy = LofitRepository(context).data
        assertTrue(legacy.exercises.all { !it.cardio })
        assertEquals(
            exercise.copy(cardio = false),
            legacy.exercises.single { it.id == exercise.id },
        )
    }

    @Test
    fun pairedRestPersistsAndDefaultsOffForOldPlans() {
        val repository = LofitRepository(context)
        val item = WorkoutExercise(exerciseId = "pistol-squat", pauseEverySecondSet = true)
        val workout = WorkoutSession(name = "Legs", exercises = mutableListOf(item))
        repository.data.activeWorkout = workout
        repository.data.routines.add(workout.toRoutine())
        repository.save()
        val loaded = LofitRepository(context)
        assertTrue(loaded.data.activeWorkout!!.exercises.single().pauseEverySecondSet)
        assertTrue(loaded.data.routines.single().exercises.single().pauseEverySecondSet)
        val root = JSONObject(stored())
        root
            .getJSONObject("activeWorkout")
            .getJSONArray("exercises")
            .getJSONObject(0)
            .remove("pauseEverySecondSet")
        root
            .getJSONArray("routines")
            .getJSONObject(0)
            .getJSONArray("exercises")
            .getJSONObject(0)
            .remove("pauseEverySecondSet")
        store(root.toString())
        val old = LofitRepository(context)
        assertFalse(old.data.activeWorkout!!.exercises.single().pauseEverySecondSet)
        assertFalse(old.data.routines.single().exercises.single().pauseEverySecondSet)
    }

    @Test
    fun exerciseOptionsPersistInPlansButAreOmittedFromHistory() {
        val repository = LofitRepository(context)
        val item =
            WorkoutExercise(
                exerciseId = "pistol-squat",
                manualMeasurement = true,
                pauseEverySecondSet = true,
            )
        val routine = WorkoutSession(name = "Legs", exercises = mutableListOf(item)).toRoutine()
        val workout =
            WorkoutSession(
                name = "Legs",
                exercises = mutableListOf(item),
                sourceRoutine = routine,
            )
        repository.data.routines.add(routine)
        repository.data.activeWorkout = workout
        repository.data.history.add(
            workout.copy(intervals = mutableListOf(WorkoutInterval(startedAt = 100, endedAt = 200)))
        )
        repository.save()
        repository.saveHistory()

        val history = org.json.JSONArray(context.repositoryHistoryFile.readText()).getJSONObject(0)
        listOf(history, history.getJSONObject("sourceRoutine")).forEach { saved ->
            val exercise = saved.getJSONArray("exercises").getJSONObject(0)
            assertFalse(exercise.has("manualMeasurement"))
            assertFalse(exercise.has("pauseEverySecondSet"))
        }

        val loaded = LofitRepository(context).data
        assertTrue(loaded.activeWorkout!!.exercises.single().manualMeasurement)
        assertTrue(loaded.activeWorkout!!.exercises.single().pauseEverySecondSet)
        assertTrue(loaded.activeWorkout!!.sourceRoutine!!.exercises.single().manualMeasurement)
        assertTrue(loaded.activeWorkout!!.sourceRoutine!!.exercises.single().pauseEverySecondSet)
        assertTrue(loaded.routines.single().exercises.single().manualMeasurement)
        assertTrue(loaded.routines.single().exercises.single().pauseEverySecondSet)
        assertFalse(loaded.history.single().exercises.single().manualMeasurement)
        assertFalse(loaded.history.single().exercises.single().pauseEverySecondSet)
        assertFalse(loaded.history.single().sourceRoutine!!.exercises.single().manualMeasurement)
        assertFalse(loaded.history.single().sourceRoutine!!.exercises.single().pauseEverySecondSet)
    }

    @Test
    fun craneScaleSelectionIsOptionalPersistsAndResets() {
        val repository = LofitRepository(context)
        assertNull(repository.data.craneScale)
        val scale = CraneScaleDevice("AA:BB:CC:DD:EE:FF", "IF_B7")
        repository.data.craneScale = scale
        repository.save()
        assertEquals(scale, LofitRepository(context).data.craneScale)
        val root = JSONObject(stored()).apply { remove("craneScale") }
        store(root.toString())
        assertNull(LofitRepository(context).data.craneScale)
        root.put("craneScale", JSONObject().put("address", "bad address").put("name", "IF_B7"))
        store(root.toString())
        assertNull(LofitRepository(context).data.craneScale)
        repository.reset()
        assertNull(LofitRepository(context).data.craneScale)
    }

    @Test
    fun onboarding_oldDataKeepsWorkoutsAndHealthChoiceAndNewChoicesPersist() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Existing workout", completedAt = 200))
        repository.data.bodyMetricsEnabled = true
        repository.saveHistory()
        repository.save()
        val root =
            JSONObject(stored()).apply {
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
    fun voiceConsentDefaultsOffForNewAndExistingInstallsAndPersistsChoices() {
        assertFalse(LofitRepository(context).data.voiceCommandsEnabled)
        val repository = LofitRepository(context)
        repository.data.onboardingComplete = true
        repository.data.activeWorkout = WorkoutSession(name = "Existing workout")
        for (enabled in listOf(true, false, true)) {
            repository.data.voiceCommandsEnabled = enabled
            repository.save()
            assertEquals(enabled, LofitRepository(context).data.voiceCommandsEnabled)
        }
        store(JSONObject(stored()).apply { remove("voiceCommandsEnabled") }.toString())
        val loaded = LofitRepository(context)
        assertFalse(loaded.data.voiceCommandsEnabled)
        assertTrue(loaded.data.onboardingComplete)
        assertEquals("Existing workout", loaded.data.activeWorkout!!.name)
        loaded.save()
        assertFalse(LofitRepository(context).data.voiceCommandsEnabled)
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
        repository.data.activeWorkout =
            WorkoutSession(name = "Active", bodyWeightKg = 64.020001002032)
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
        assertEquals(
            64.02,
            JSONObject(stored()).getJSONObject("activeWorkout").getDouble("bodyWeightKg"),
            0.0,
        )
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
    fun workoutIntervals_surviveRestartsWhileActiveAndAfterFinishing() {
        val repository = LofitRepository(context)
        repository.data.activeWorkout =
            WorkoutSession(
                name = "Resumed",
                intervals =
                    mutableListOf(
                        WorkoutInterval(100, 250),
                        WorkoutInterval(500, 700),
                        WorkoutInterval(900),
                    ),
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
        assertEquals(
            listOf(
                WorkoutInterval(100, 250),
                WorkoutInterval(500, 700),
                WorkoutInterval(900, 1_100),
            ),
            saved.intervals,
        )
        assertEquals(100L, saved.startedAt)
        assertEquals(1_100L, saved.completedAt)
        assertEquals(550L, saved.durationMillis(5_000))
    }

    @Test
    fun startup_currentSchemaDoesNotRepairOrRewriteData() {
        val repository = LofitRepository(context)
        val plan = PlannedSet(id = "p", holdSeconds = 45)
        repository.data.history.add(
            WorkoutSession(
                name = "Current",
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = "plank",
                            plannedSets = mutableListOf(plan),
                            sets =
                                mutableListOf(
                                    LoggedSet(
                                        type = SetType.NORMAL,
                                        weightKg = 0.0,
                                        holdSeconds = 42,
                                        plannedSetId = plan.id,
                                    )
                                ),
                        )
                    ),
            )
        )
        repository.saveHistory()
        repository.save()
        val raw = stored()

        val loaded = LofitRepository(context)

        assertNull(
            loaded.data.history.single().exercises.single().plannedSets.single().actualHoldSeconds
        )
        assertEquals(raw, stored())
        assertFalse(File(context.filesDir, "$backupKey.json").exists())
    }

    @Test
    fun startup_badJsonAndNewerSchemaNeverBecomeEmptyData() {
        listOf("broken JSON", """{"schemaVersion":5,"exercises":[]}""").forEach { raw ->
            store(raw)
            assertThrows(Exception::class.java) { LofitRepository(context) }
            assertEquals(raw, stored())
            assertFalse(File(context.filesDir, "$backupKey.json").exists())
        }
    }

    @Test
    fun startup_versionedLegacyTypesAreRejectedWithoutChangingState() {
        val repository = LofitRepository(context)
        repository.data.exercises.add(
            Exercise(
                id = "custom",
                name = "Custom",
                type = ExerciseType.TIMED,
                asymmetric = false,
                primaryMuscle = "Core",
                secondaryMuscles = "",
                custom = true,
            )
        )
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
