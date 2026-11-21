package lofit

import org.junit.Assert.*
import org.junit.Test

class CardioDurationTest {
    private val run = Exercise("run", "Run", ExerciseType.TIMED, false, "", "", cardio = true)
    private val catalog = mapOf(run.id to run)

    private fun row(vararg seconds: Int) =
        WorkoutExercise(
            exerciseId = run.id,
            plannedSets = seconds.map { PlannedSet(holdSeconds = it) }.toMutableList(),
        )

    @Test
    fun shorterTargetKeepsEarlierProgressAndOnlyShortensTheLastSet() {
        val first = row(20, 50)
        val started = first.plannedSets.first()
        started.actualHoldSeconds = 18
        first.sets.add(loggedSetFrom(started, run))
        val later = row(40)
        val workout = WorkoutSession(name = "Run", exercises = mutableListOf(first, later))
        later.plannedSets.single().actualHoldSeconds = 0

        assertNotNull(workout.setCardioDuration(1, catalog))
        assertEquals(50, first.plannedSets.last().holdSeconds)
        assertEquals(2, workout.exercises.size)
        later.plannedSets.single().actualHoldSeconds = null
        assertNull(workout.setCardioDuration(1, catalog))
        assertSame(first, workout.exercises.single())
        assertSame(started, first.plannedSets.first())
        assertEquals(18, first.sets.single().holdSeconds)
        assertEquals(40, first.plannedSets.last().holdSeconds)
    }

    @Test
    fun exactMultipleCopiesPrescriptionAndStartsNewSetsFresh() {
        val first =
            row(15, 45)
                .copy(
                    restSecondsOverride = 90,
                    sourceRoutineExerciseId = "original",
                    note = "Steady",
                )
        first.plannedSets.first().actualHoldSeconds = 15
        first.sets.add(loggedSetFrom(first.plannedSets.first(), run))
        val workout = WorkoutSession(name = "Intervals", exercises = mutableListOf(first))
        repeat(2) { assertNull(workout.setCardioDuration(3, catalog)) }
        assertEquals(3, workout.exercises.size)
        assertEquals(3, workout.exercises.distinctBy { it.id }.size)
        assertEquals(6, workout.exercises.flatMap { it.plannedSets }.distinctBy { it.id }.size)
        for (copy in workout.exercises.drop(1)) {
            assertEquals(listOf(15, 45), copy.plannedSets.map { it.holdSeconds })
            assertTrue(copy.plannedSets.all { it.actualHoldSeconds == null })
            assertTrue(copy.sets.isEmpty())
            assertNull(copy.sourceRoutineExerciseId)
            assertEquals("Steady", copy.note)
            assertEquals(90, copy.restSecondsOverride)
        }
    }

    @Test
    fun repBasedCardioUsesEstimatedActiveTime() {
        val exercise = run.copy(type = ExerciseType.REPETITIONS)
        val workout = WorkoutSession(name = "Jumps", exercises = mutableListOf(row(0)))
        assertNull(workout.setCardioDuration(1, mapOf(exercise.id to exercise)))
        assertEquals(listOf(8, 4), workout.exercises.map { it.plannedSets.single().reps })
    }

    @Test
    fun missingDurationAndExcessiveExpansionLeaveWorkoutIntact() {
        val first = row(0)
        val workout = WorkoutSession(name = "Run", exercises = mutableListOf(first))
        assertNotNull(workout.setCardioDuration(1, catalog))
        first.plannedSets.single().holdSeconds = 1
        assertNotNull(workout.setCardioDuration(Int.MAX_VALUE, catalog))
        assertSame(first, workout.exercises.single())
    }
}
