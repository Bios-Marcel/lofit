package lofit

import org.junit.Assert.*
import org.junit.Test

class HistoryWorkoutsTest {
    @Test
    fun freshWorkoutResetsSessionStateWithoutLinkingToTheOldRoutine() {
        val history =
            WorkoutSession(
                name = "Push day",
                intervals = mutableListOf(WorkoutInterval(100, 200), WorkoutInterval(300, 400)),
                defaultRestSeconds = 90,
                bodyWeightKg = 75.0,
                averageHeartRateBpm = 120.0,
                sourceRoutine = Routine(name = "Strength"),
                autoStartCardio = false,
                voiceCommandsEnabled = true,
                note = "Tired today",
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = "bench-press",
                            sourceRoutineExerciseId = "routine-row",
                            restSecondsOverride = 45,
                            note = "Keep elbows tucked",
                            manualMeasurement = true,
                            pauseEverySecondSet = true,
                            sets =
                                mutableListOf(
                                    LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 8)
                                ),
                        )
                    ),
            )
        val beforeStart = System.currentTimeMillis()

        val fresh = history.freshWorkout()

        assertNotEquals(history.id, fresh.id)
        assertEquals("Push day", fresh.name)
        assertTrue(fresh.startedAt in beforeStart..System.currentTimeMillis())
        assertEquals(listOf(WorkoutInterval(fresh.startedAt)), fresh.intervals)
        assertNull(fresh.completedAt)
        assertNull(fresh.bodyWeightKg)
        assertNull(fresh.averageHeartRateBpm)
        assertNull(fresh.sourceRoutine)
        assertEquals("", fresh.note)
        assertEquals(90, fresh.defaultRestSeconds)
        assertFalse(fresh.autoStartCardio)
        assertFalse(fresh.voiceCommandsEnabled)
        val exercise = fresh.exercises.single()
        assertNotEquals(history.exercises.single().id, exercise.id)
        assertNull(exercise.sourceRoutineExerciseId)
        assertEquals(45, exercise.restSecondsOverride)
        assertTrue(exercise.manualMeasurement)
        assertTrue(exercise.pauseEverySecondSet)
        assertEquals("", exercise.note)
        assertEquals("Keep elbows tucked", exercise.previousNote)
        assertEquals(0, fresh.setCount())
        assertEquals(1, history.setCount())
        assertEquals(400L, history.completedAt)
    }

    @Test
    fun freshWorkoutUsesSelectedHistoryResultsInPlannedOrderAndCopiesUnloggedExercises() {
        val warmup = PlannedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10)
        val working = PlannedSet(weightKg = 60.0, reps = 8, side = Side.RIGHT)
        val timed = PlannedSet(holdSeconds = 45, actualHoldSeconds = 12)
        val history =
            WorkoutSession(
                name = "Mixed",
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = "bench-press",
                            plannedSets = mutableListOf(warmup, working, PlannedSet()),
                            sets =
                                mutableListOf(
                                    LoggedSet(
                                        type = SetType.FAILURE,
                                        weightKg = 67.5,
                                        reps = 6,
                                        side = Side.RIGHT,
                                        plannedSetId = working.id,
                                    ),
                                    LoggedSet(
                                        type = SetType.WARMUP,
                                        weightKg = 22.5,
                                        reps = 12,
                                        plannedSetId = warmup.id,
                                    ),
                                ),
                        ),
                        WorkoutExercise(
                            exerciseId = "plank",
                            previousNote = "Brace core",
                            plannedSets = mutableListOf(timed),
                        ),
                        WorkoutExercise(exerciseId = "bench-press"),
                    ),
            )

        val fresh = history.freshWorkout()

        assertEquals(
            listOf("bench-press", "plank", "bench-press"),
            fresh.exercises.map { it.exerciseId },
        )
        val bench = fresh.exercises.first()
        assertEquals(listOf(22.5, 67.5), bench.plannedSets.map { it.weightKg })
        assertEquals(listOf(12, 6), bench.plannedSets.map { it.reps })
        assertEquals(listOf(SetType.WARMUP, SetType.FAILURE), bench.plannedSets.map { it.type })
        assertEquals(listOf(Side.BOTH, Side.RIGHT), bench.plannedSets.map { it.side })
        val plank = fresh.exercises[1]
        assertEquals(45, plank.plannedSets.single().holdSeconds)
        assertEquals("Brace core", plank.previousNote)
        assertTrue(fresh.exercises.last().plannedSets.isEmpty())
        val originalIds = history.exercises.flatMap { it.plannedSets }.map { it.id }.toSet()
        assertTrue(
            fresh.exercises
                .flatMap { it.plannedSets }
                .all {
                    it.id !in originalIds && it.actualHoldSeconds == null
                }
        )

        bench.plannedSets.first().weightKg = 30.0
        plank.plannedSets.single().holdSeconds = 60
        fresh.exercises.clear()
        assertEquals(22.5, history.exercises.first().sets.last().weightKg, 0.0)
        assertEquals(45, timed.holdSeconds)
        assertEquals(12, timed.actualHoldSeconds)
        assertEquals(3, history.exercises.size)
    }
}
