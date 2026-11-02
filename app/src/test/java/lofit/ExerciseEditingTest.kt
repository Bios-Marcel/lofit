package lofit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseEditingTest {
    private val exercise =
        Exercise(
            id = "single-side",
            name = "Single side",
            type = ExerciseType.TIMED,
            asymmetric = true,
            primaryMuscle = "Core",
            secondaryMuscles = "",
            minimumHoldSeconds = 45,
            weighted = true,
        )

    @Test
    fun pairedSetsCopyLatestPrescriptionForEachSideAndResetActiveTime() {
        val sets =
            listOf(
                PlannedSet(side = Side.LEFT, weightKg = 5.0, reps = 12, holdSeconds = 30),
                PlannedSet(side = Side.RIGHT, weightKg = 7.5, reps = 10, holdSeconds = 35),
                PlannedSet(
                    side = Side.LEFT,
                    weightKg = 10.0,
                    reps = 8,
                    holdSeconds = 40,
                    actualHoldSeconds = 42,
                    type = SetType.WARMUP,
                ),
                PlannedSet(
                    side = Side.RIGHT,
                    weightKg = 12.5,
                    reps = 6,
                    holdSeconds = 50,
                    actualHoldSeconds = 55,
                    type = SetType.FAILURE,
                ),
            )

        val added = nextPlannedSets(sets, exercise)

        assertEquals(listOf(Side.LEFT, Side.RIGHT), added.map { it.side })
        assertEquals(listOf(10.0, 12.5), added.map { it.weightKg })
        assertEquals(listOf(8, 6), added.map { it.reps })
        assertEquals(listOf(40, 50), added.map { it.holdSeconds })
        assertEquals(listOf(SetType.WARMUP, SetType.FAILURE), added.map { it.type })
        assertTrue(added.all { it.actualHoldSeconds == null })
        assertEquals(6, (sets + added).map { it.id }.toSet().size)
        assertEquals(listOf(42, 55), sets.takeLast(2).map { it.actualHoldSeconds })
    }

    @Test
    fun repeatedAddsPreserveRightThenLeftOrderAndEachSidesValues() {
        val sets =
            mutableListOf(
                PlannedSet(side = Side.RIGHT, weightKg = 12.5, reps = 6, holdSeconds = 50),
                PlannedSet(side = Side.LEFT, weightKg = 10.0, reps = 8, holdSeconds = 40),
            )

        repeat(2) { sets.addAll(nextPlannedSets(sets, exercise)) }

        assertEquals(List(3) { listOf(Side.RIGHT, Side.LEFT) }.flatten(), sets.map { it.side })
        assertEquals(List(3) { listOf(12.5, 10.0) }.flatten(), sets.map { it.weightKg })
        assertEquals(6, sets.map { it.id }.toSet().size)
    }

    @Test
    fun oddOrNonAlternatingSetsStillAddOneSet() {
        val patterns =
            listOf(
                listOf(Side.LEFT),
                listOf(Side.LEFT, Side.RIGHT, Side.LEFT),
                listOf(Side.BOTH, Side.BOTH),
                listOf(Side.LEFT, Side.LEFT),
                listOf(Side.LEFT, Side.LEFT, Side.RIGHT, Side.RIGHT),
                listOf(Side.BOTH, Side.BOTH, Side.LEFT, Side.RIGHT),
                listOf(Side.LEFT, Side.RIGHT, Side.RIGHT, Side.LEFT),
            )
        patterns.forEach { sides ->
            val sets = sides.map {
                PlannedSet(
                    side = it,
                    weightKg = 12.5,
                    reps = 6,
                    holdSeconds = 50,
                    actualHoldSeconds = 55,
                )
            }

            val added = nextPlannedSets(sets, exercise).single()

            assertEquals(sets.last().copy(id = added.id, actualHoldSeconds = null), added)
            assertTrue(sets.none { it.id == added.id })
            assertNull(added.actualHoldSeconds)
        }
    }

    @Test
    fun emptyExerciseStartsWithOneDefaultSetAndItsMinimumHold() {
        val added = nextPlannedSets(emptyList(), exercise).single()

        assertEquals(PlannedSet(id = added.id, holdSeconds = 45), added)
    }
}
