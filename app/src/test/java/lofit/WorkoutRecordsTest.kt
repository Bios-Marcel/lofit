package lofit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutRecordsTest {
    private val bench =
        Exercise("bench", "Bench Press", ExerciseType.REPETITIONS, false, "Chest", "")
    private val data =
        AppData(exercises = mutableListOf(bench)).apply {
            recordWorkout(
                WorkoutSession(
                    name = "Previous",
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = bench.id,
                                sets =
                                    mutableListOf(
                                        LoggedSet(type = SetType.NORMAL, weightKg = 80.0, reps = 5)
                                    ),
                            )
                        ),
                )
            )
        }
    private val set = LoggedSet(type = SetType.NORMAL, weightKg = 70.0, reps = 8)
    private val workout =
        WorkoutSession(
            name = "Current",
            exercises =
                mutableListOf(WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(set))),
        )

    @Test
    fun records_describeEachImprovedTypeAndCountBothOnOneSet() {
        val volume = data.workoutRecords(workout).single()
        assertEquals(TrendMetric.VOLUME, volume.metric)
        assertEquals("Bench Press 560 kg set volume", volume.message())
        set.weightKg = 90.0
        val records = data.workoutRecords(workout)
        assertEquals(listOf(TrendMetric.WEIGHT, TrendMetric.VOLUME), records.map { it.metric })
        assertEquals(listOf(90.0, 720.0), records.map { it.value })
        assertEquals(setOf(set.id), data.recordSetIds(workout))
        // The earlier snapshot must keep its value when a completed set is edited.
        assertEquals(560.0, volume.value, 0.0)
        workout.exercises.clear()
        assertTrue(data.workoutRecords(workout).isEmpty())
    }

    @Test
    fun records_ignorePlannedSetsTiesAndExercisesWithoutPreviousRecords() {
        set.weightKg = 80.0
        set.reps = 5
        workout.exercises.single().plannedSets.add(PlannedSet(weightKg = 100.0, reps = 10))
        assertTrue(data.workoutRecords(workout).isEmpty())
        set.weightKg = 90.0
        data.exerciseRecords.clear()
        assertTrue(data.workoutRecords(workout).isEmpty())
    }

    @Test
    fun records_useHoldAndBodyweightRepetitionTypes() {
        val hold = Exercise("hold", "Plank", ExerciseType.TIMED, false, "Core", "")
        val reps =
            Exercise(
                "reps",
                "Push-up",
                ExerciseType.REPETITIONS,
                false,
                "Chest",
                "",
                weighted = false,
            )
        data.exercises.addAll(listOf(hold, reps))
        val previous =
            WorkoutSession(
                name = "Previous",
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = hold.id,
                            sets =
                                mutableListOf(
                                    LoggedSet(
                                        type = SetType.NORMAL,
                                        weightKg = 0.0,
                                        holdSeconds = 30,
                                    )
                                ),
                        ),
                        WorkoutExercise(
                            exerciseId = reps.id,
                            sets =
                                mutableListOf(
                                    LoggedSet(type = SetType.NORMAL, weightKg = 0.0, reps = 10)
                                ),
                        ),
                    ),
            )
        data.recordWorkout(previous)
        previous.exercises[0].sets.single().holdSeconds = 45
        previous.exercises[1].sets.single().reps = 12
        assertEquals(
            listOf("Plank 45 s hold time", "Push-up 12 repetitions"),
            data.workoutRecords(previous).map { it.message() },
        )
    }

    @Test
    fun notifications_announceImprovementsWithoutRepeatingOnRedrawTieOrUndo() {
        val notifications = WorkoutRecordNotifications()
        assertTrue(notifications.update(workout.id, emptyList()).isEmpty())
        val records = data.workoutRecords(workout)
        assertEquals(records, notifications.update(workout.id, records))
        assertTrue(notifications.update(workout.id, records).isEmpty())
        assertTrue(notifications.update(workout.id, emptyList()).isEmpty())
        assertTrue(notifications.update(workout.id, records).isEmpty())
        set.weightKg = 90.0
        val improved = data.workoutRecords(workout)
        assertEquals(improved, notifications.update(workout.id, improved))
        assertTrue(notifications.update(workout.id, records).isEmpty())
    }

    @Test
    fun notifications_seedExistingRecordsAfterRestartAndResetForNextWorkout() {
        val notifications = WorkoutRecordNotifications()
        val records = data.workoutRecords(workout)
        assertTrue(notifications.update(workout.id, records).isEmpty())
        assertTrue(notifications.update(workout.id, records).isEmpty())
        assertTrue(notifications.update("next", emptyList()).isEmpty())
        assertEquals(records, notifications.update("next", records))
    }
}
