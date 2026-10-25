package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExerciseRecordsStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("lofit-data", 0)

    @Before
    fun clearData() {
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun records_surviveRestartWithoutHistoryAndAppendToExistingLists() {
        val repository = LofitRepository(context)
        val custom = Exercise(
            id = "custom-reps", name = "Custom reps", type = ExerciseType.REPETITIONS,
            asymmetric = true, primaryMuscle = "Chest", secondaryMuscles = "",
            custom = true, weighted = false,
        )
        repository.data.exercises.add(custom)
        val workout = WorkoutSession(name = "Records", exercises = mutableListOf(
            WorkoutExercise(exerciseId = "bench-press", sets = mutableListOf(
                LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 8, completedAt = 10),
                LoggedSet(type = SetType.FAILURE, weightKg = 80.0, reps = 2, completedAt = 20, side = Side.LEFT),
                LoggedSet(type = SetType.NORMAL, weightKg = 50.0, reps = 10, completedAt = 30),
            )),
            WorkoutExercise(exerciseId = "plank", sets = mutableListOf(
                LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 45, completedAt = 10),
            )),
            WorkoutExercise(exerciseId = custom.id, sets = mutableListOf(
                LoggedSet(type = SetType.NORMAL, weightKg = 0.0, reps = 12, completedAt = 10),
            )),
        ))
        repository.data.recordWorkout(workout)
        repository.save()

        val loaded = LofitRepository(context)
        assertTrue(loaded.data.history.isEmpty())
        assertEquals(repository.data.exerciseRecords, loaded.data.exerciseRecords)
        val benchRecords = loaded.data.exerciseRecords.getValue("bench-press")
        assertEquals(listOf(60.0, 80.0, 80.0), benchRecords.map { it.set.weightKg })
        assertEquals(listOf(480.0, 480.0, 500.0), benchRecords.map { it.volumeSet!!.volume() })
        assertSame(loaded.data.exercises.first { it.id == custom.id }, loaded.data.exerciseRecords.getValue(custom.id).last().exercise)
        assertNull(loaded.data.exerciseRecords.getValue(custom.id).last().volumeSet)
        assertNull(loaded.data.exerciseRecords.getValue("plank").last().volumeSet)

        loaded.data.recordWorkout(WorkoutSession(name = "Next", exercises = mutableListOf(
            WorkoutExercise(exerciseId = "bench-press", sets = mutableListOf(
                LoggedSet(type = SetType.NORMAL, weightKg = 90.0, reps = 6, completedAt = 40),
            )),
        )))
        loaded.save()
        val next = LofitRepository(context)
        assertEquals(4, next.data.exerciseRecords.getValue("bench-press").size)
        assertEquals(90.0, next.data.exerciseRecords.getValue("bench-press").last().set.weightKg, 0.0)
        assertEquals(3, next.data.exerciseRecords.size)
        next.reset()
        assertTrue(LofitRepository(context).data.exerciseRecords.isEmpty())
    }

    @Test
    fun oldData_startsWithEmptyMapAndNeverBackfillsHistory() {
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Old", completedAt = 200,
            exercises = mutableListOf(WorkoutExercise(exerciseId = "bench-press", sets = mutableListOf(
                LoggedSet(type = SetType.NORMAL, weightKg = 120.0, reps = 10),
            ))),
        ))
        repository.save()
        val oldState = JSONObject(preferences.getString("state", null)!!).apply { remove("exerciseRecords") }
        assertTrue(preferences.edit().putString("state", oldState.toString()).commit())

        val loaded = LofitRepository(context)
        assertEquals(1, loaded.data.history.size)
        assertTrue(loaded.data.exerciseRecords.isEmpty())
        assertTrue(loaded.data.oneRepMaxes().isEmpty())
        assertTrue(loaded.data.exerciseTrend("bench-press").series.all { it.points.isEmpty() })
        loaded.save()
        assertTrue(LofitRepository(context).data.exerciseRecords.isEmpty())

        loaded.data.recordWorkout(WorkoutSession(name = "New", exercises = mutableListOf(
            WorkoutExercise(exerciseId = "bench-press", sets = mutableListOf(
                LoggedSet(type = SetType.NORMAL, weightKg = 40.0, reps = 5),
            )),
        )))
        loaded.save()
        assertEquals(40.0, LofitRepository(context).data.exerciseRecords.getValue("bench-press").single().set.weightKg, 0.0)
    }
}
