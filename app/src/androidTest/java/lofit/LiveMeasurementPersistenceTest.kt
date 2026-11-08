package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveMeasurementPersistenceTest {
    private val context = RepositoryTestContext("live-measurement-persistence")

    private fun movement() =
        Exercise(
            "live",
            "Scale pull",
            ExerciseType.TIMED,
            false,
            "Forearms",
            "—",
            weighted = true,
            liveMeasurement = true,
            custom = true,
        )

    @Before fun clear() = context.clearRepositoryTestData()

    @Test
    fun modifierAndOverridesSurviveRestartFinishAndResumeWithoutGraphData() {
        val repository = LofitRepository(context)
        val exercise = movement()
        repository.data.craneScale = CraneScaleDevice("AA:BB:CC:DD:EE:01", "Scale")
        repository.data.exercises.add(exercise)
        val routine =
            Routine(
                name = "Pulls",
                exercises =
                    mutableListOf(
                        RoutineExercise(exerciseId = exercise.id, manualMeasurement = true)
                    ),
            )
        repository.data.routines.add(routine)
        val workout = repository.data.workoutFromRoutine(routine)
        repository.data.activeWorkout = workout
        val item = workout.exercises.single()
        assertTrue(item.manualMeasurement)
        item.manualMeasurement = false
        val runtime = LiveWorkoutMeasurement { repository.data }
        runtime.read(0, 1.1)
        runtime.read(1_000, 15.113)
        runtime.read(30_000, 15.133)
        runtime.read(30_950, 2.0)
        runtime.read(31_000, 0.0)
        assertEquals(5, runtime.seriesFor(item.id).samples.size)
        repository.save()
        val restarted = LofitRepository(context)
        assertTrue(restarted.data.exercises.first { it.id == exercise.id }.supportsLiveMeasurement)
        assertTrue(restarted.data.routines.single().exercises.single().manualMeasurement)
        assertFalse(restarted.data.activeWorkout!!.exercises.single().manualMeasurement)
        assertTrue(LiveWorkoutMeasurement { restarted.data }.seriesFor(item.id).samples.isEmpty())
        assertEquals(
            15.12,
            restarted.data.activeWorkout!!.exercises.single().sets.single().weightKg,
            0.0,
        )
        workout.finish()
        repository.data.history.add(workout)
        repository.data.activeWorkout = null
        repository.saveHistory()
        repository.save()
        val historyJson = context.repositoryHistoryFile.readText()
        assertFalse(historyJson.contains("samples"))
        assertFalse(historyJson.contains("kilograms"))
        val saved = LofitRepository(context)
        val history = saved.data.history.single()
        assertFalse(history.exercises.single().manualMeasurement)
        assertEquals(31, history.exercises.single().sets.single().holdSeconds)
        assertEquals(15.12, history.exercises.single().sets.single().weightKg, 0.0)
        assertFalse(saved.data.resumeWorkout(history.id)!!.exercises.single().manualMeasurement)
    }

    @Test
    fun missingFieldsKeepOldExercisesManualAndDefaultModifiedExercisesToLive() {
        val repository = LofitRepository(context)
        repository.data.exercises.add(movement())
        repository.data.routines.add(
            Routine(name = "Old", exercises = mutableListOf(RoutineExercise(exerciseId = "live")))
        )
        repository.data.activeWorkout =
            repository.data.workoutFromRoutine(repository.data.routines.single())
        repository.save()
        val root = JSONObject(context.repositoryStateFile.readText())
        val exercises = root.getJSONArray("exercises")
        for (index in 0 until exercises.length()) exercises
            .getJSONObject(index)
            .remove("liveMeasurement")
        root
            .getJSONArray("routines")
            .getJSONObject(0)
            .getJSONArray("exercises")
            .getJSONObject(0)
            .remove("manualMeasurement")
        root
            .getJSONObject("activeWorkout")
            .getJSONArray("exercises")
            .getJSONObject(0)
            .remove("manualMeasurement")
        context.repositoryStateFile.writeText(root.toString())
        val loaded = LofitRepository(context).data
        assertFalse(loaded.exercises.first { it.id == "live" }.supportsLiveMeasurement)
        assertFalse(loaded.activeWorkout!!.exercises.single().manualMeasurement)
        assertFalse(loaded.routines.single().exercises.single().manualMeasurement)
        loaded.exercises.first { it.id == "live" }.liveMeasurement = true
        assertFalse(
            LiveWorkoutMeasurement { loaded }.isLive(loaded.activeWorkout!!.exercises.single())
        )
        loaded.craneScale = CraneScaleDevice("AA:BB:CC:DD:EE:01", "Scale")
        assertTrue(
            LiveWorkoutMeasurement { loaded }.isLive(loaded.activeWorkout!!.exercises.single())
        )
    }
}
