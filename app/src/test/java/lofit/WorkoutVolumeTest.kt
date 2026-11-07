package lofit

import org.junit.Assert.assertEquals
import org.junit.Test

class WorkoutVolumeTest {
    private val weightedHold =
        Exercise(
            id = "weighted-hold",
            name = "Weighted hold",
            type = ExerciseType.TIMED,
            asymmetric = false,
            primaryMuscle = "Abs",
            secondaryMuscles = "",
            weighted = true,
        )

    @Test
    fun weightedHolds_countWeightOnceInActiveAndFinishedWorkoutTotals() {
        val workout =
            WorkoutSession(
                name = "Mixed workout",
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = "bench-press",
                            sets =
                                mutableListOf(
                                    LoggedSet(type = SetType.NORMAL, weightKg = 20.0, reps = 10),
                                    LoggedSet(type = SetType.NORMAL, weightKg = 20.0, reps = 0),
                                ),
                        ),
                        WorkoutExercise(
                            exerciseId = weightedHold.id,
                            sets =
                                mutableListOf(
                                    LoggedSet(
                                        type = SetType.WARMUP,
                                        weightKg = 1.25,
                                        holdSeconds = 0,
                                    ),
                                    LoggedSet(
                                        type = SetType.NORMAL,
                                        weightKg = 2.5,
                                        reps = 99,
                                        holdSeconds = 45,
                                    ),
                                ),
                            plannedSets = mutableListOf(PlannedSet(weightKg = 50.0)),
                        ),
                        WorkoutExercise(
                            exerciseId = "plank",
                            sets =
                                mutableListOf(
                                    LoggedSet(
                                        type = SetType.NORMAL,
                                        weightKg = 0.0,
                                        holdSeconds = 60,
                                    )
                                ),
                        ),
                    ),
            )
        val catalog = listOf(weightedHold)

        assertEquals(203.75, workout.totalVolume(catalog), 0.0)

        workout.finish()

        assertEquals(203.75, workout.totalVolume(catalog), 0.0)
    }

    @Test
    fun weightedHolds_doubleBothSidesOnlyForAsymmetricExercises() {
        val hold = weightedHold.copy(asymmetric = true)
        val workout =
            WorkoutSession(
                name = "Both sides",
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = hold.id,
                            sets =
                                Side.entries
                                    .map { side ->
                                        LoggedSet(
                                            type = SetType.NORMAL,
                                            weightKg = 0.1,
                                            holdSeconds = 30,
                                            side = side,
                                        )
                                    }
                                    .toMutableList(),
                        )
                    ),
            )

        assertEquals(0.4, workout.totalVolume(listOf(hold)), 0.0)
        assertEquals(0.3, workout.totalVolume(listOf(weightedHold)), 0.0)
    }
}
