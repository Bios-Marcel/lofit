package lofit

import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseRecencyTest {
    private fun exercise(id: String) = Exercise(id, id, ExerciseType.REPETITIONS, false, "Chest", "")

    private fun logged(id: String, vararg times: Long) = WorkoutExercise(
        exerciseId = id,
        sets = times.map { LoggedSet(type = SetType.NORMAL, weightKg = 20.0, reps = 8, completedAt = it) }.toMutableList(),
    )

    @Test fun latestSetWinsAcrossUnsortedHistoryAndRepeatedExercises() {
        val data = AppData(exercises = mutableListOf(exercise("a"), exercise("b"), exercise("c")),
            history = mutableListOf(
                WorkoutSession(name = "New", completedAt = 500, exercises = mutableListOf(logged("a", 200, 100), logged("b", 300))),
                WorkoutSession(name = "Old", completedAt = 100, exercises = mutableListOf(logged("b", 50), logged("a", 20))),
            ))

        assertEquals(listOf("b", "a", "c"), data.exercisesByRecency().map { it.id })
        assertEquals(listOf("a", "b", "c"), data.exercises.map { it.id })
    }

    @Test fun activeSetsWaitForWorkoutFinishAndDoNotDuplicateResults() {
        val data = AppData(exercises = mutableListOf(exercise("a"), exercise("b"), exercise("c")),
            exerciseLastSelectedAt = mutableMapOf("c" to 300, "b" to 50, "deleted" to 900),
            activeWorkout = WorkoutSession(name = "Active", exercises = mutableListOf(logged("b", 400))),
            history = mutableListOf(WorkoutSession(name = "Old", completedAt = 200,
                exercises = mutableListOf(logged("a", 100), logged("b", 200)))))

        assertEquals(listOf("c", "b", "a"), data.exercisesByRecency().map { it.id })
        assertEquals(mapOf("c" to 300L, "b" to 50L, "deleted" to 900L), data.exerciseLastSelectedAt)
        val workout = data.activeWorkout!!
        workout.finish(400)
        data.recordExerciseRecency(workout)
        data.history.add(workout)
        data.activeWorkout = null
        assertEquals(400L, data.exerciseLastSelectedAt["b"])
        assertEquals(listOf("b", "c", "a"), data.exercisesByRecency().map { it.id })
        data.exerciseLastSelectedAt["a"] = 500
        assertEquals(listOf("a", "b", "c"), data.exercisesByRecency().map { it.id })
    }

    @Test fun finishRecordsIncludedExercisesAtFinishTimeAndIgnoresOlderSessions() {
        val data = AppData(exercises = mutableListOf(exercise("a"), exercise("b")),
            exerciseLastSelectedAt = mutableMapOf("a" to 500))
        val workout = WorkoutSession(name = "Active", startedAt = 100,
            exercises = mutableListOf(logged("a", 150), WorkoutExercise(exerciseId = "b")))
        data.activeWorkout = workout

        data.recordExerciseRecency(workout)
        assertEquals(mapOf("a" to 500L), data.exerciseLastSelectedAt)
        workout.finish(400)
        data.recordExerciseRecency(workout)
        assertEquals(mapOf("a" to 500L, "b" to 400L), data.exerciseLastSelectedAt)
    }

    @Test fun unusedExercisesAndTiesKeepCatalogOrderAndUnloggedPlansDoNotCount() {
        val data = AppData(exercises = mutableListOf(exercise("a"), exercise("b"), exercise("c"), exercise("d")),
            activeWorkout = WorkoutSession(name = "Unlogged", exercises = mutableListOf(WorkoutExercise(exerciseId = "b"))))

        assertEquals(listOf("a", "b", "c", "d"), data.exercisesByRecency().map { it.id })
        data.exerciseLastSelectedAt.putAll(mapOf("d" to 200, "c" to 200))
        assertEquals(listOf("c", "d", "a", "b"), data.exercisesByRecency().map { it.id })
    }
}
