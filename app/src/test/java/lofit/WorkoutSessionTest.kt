package lofit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkoutSessionTest {
    @Test
    fun moveExercise_reordersItemsAndIgnoresInvalidMoves() {
        val workout = WorkoutSession(
            name = "Test",
            exercises = mutableListOf(
                WorkoutExercise(exerciseId = "squat"),
                WorkoutExercise(exerciseId = "press"),
                WorkoutExercise(exerciseId = "row"),
            ),
        )

        workout.moveExercise(2, 0)
        workout.moveExercise(0, -1)

        assertEquals(listOf("row", "squat", "press"), workout.exercises.map { it.exerciseId })
    }

    @Test
    fun totals_includeAllLoggedSets() {
        val workout = WorkoutSession(
            name = "Volume",
            exercises = mutableListOf(
                WorkoutExercise(
                    exerciseId = "bench",
                    sets = mutableListOf(
                        LoggedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10),
                        LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 5),
                    ),
                ),
                WorkoutExercise(
                    exerciseId = "plank",
                    sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 45)),
                ),
            ),
        )

        assertEquals(3, workout.setCount())
        assertEquals(500.0, workout.totalVolume(), 0.001)
    }

    @Test
    fun loggedSet_rejectsNegativeValues() {
        assertThrows(IllegalArgumentException::class.java) {
            LoggedSet(type = SetType.NORMAL, weightKg = -1.0, reps = 5)
        }
    }

    @Test
    fun defaultLibrary_coversRepsHoldsAndAsymmetry() {
        val exercises = DefaultData.exercises()

        assertEquals(8, exercises.size)
        assert(exercises.any { it.type == ExerciseType.WEIGHTED_HOLD })
        assert(exercises.any { it.type == ExerciseType.WEIGHTED_REPS })
        assert(exercises.any { it.asymmetric })
        assert(exercises.all { it.primaryMuscle.isNotBlank() })
    }

    @Test
    fun routineExercise_preservesPrescribedWork() {
        val prescription = RoutineExercise(
            exerciseId = "plank",
            targetSets = 4,
            targetReps = 0,
            targetHoldSeconds = 45,
            restSecondsOverride = 60,
        )

        assertEquals(4, prescription.targetSets)
        assertEquals(45, prescription.targetHoldSeconds)
        assertEquals(60, prescription.restSecondsOverride)
    }

    @Test
    fun routineExercise_allowsDifferentWeightAndRepsForEachSet() {
        val prescription = RoutineExercise(
            exerciseId = "bench",
            plannedSets = mutableListOf(
                PlannedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10),
                PlannedSet(weightKg = 60.0, reps = 5),
                PlannedSet(type = SetType.FAILURE, weightKg = 65.0, reps = 3),
            ),
        )

        assertEquals(listOf(20.0, 60.0, 65.0), prescription.plannedSets.map { it.weightKg })
        assertEquals(listOf(10, 5, 3), prescription.plannedSets.map { it.reps })
        assertEquals(listOf(SetType.WARMUP, SetType.NORMAL, SetType.FAILURE), prescription.plannedSets.map { it.type })
    }
}
