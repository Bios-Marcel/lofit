package lofit

import org.junit.Assert.assertEquals
import org.junit.Test

class MuscleWorkloadTest {
    private fun exercise(primary: String = "Chest", secondary: String = "Triceps") = Exercise(
        id = "test", name = "Test", type = ExerciseType.REPETITIONS, asymmetric = false,
        primaryMuscle = primary, secondaryMuscles = secondary,
    )

    @Test
    fun primarySetsIncreaseInEightStepsAndCapAtFullSaturation() {
        (0..12).forEach { sets ->
            val workload = MuscleWorkload(listOf(exercise() to sets))
            assertEquals((sets / 8f).coerceAtMost(1f), workload.saturationFor(setOf("Chest")), 0f)
        }
    }

    @Test
    fun secondarySetsContributeOneQuarterAndNeverExceedQuarterSaturation() {
        (0..32).forEach { sets ->
            val workload = MuscleWorkload(listOf(exercise() to sets))
            assertEquals((sets / 32f).coerceAtMost(0.25f), workload.saturationFor(setOf("Triceps")), 0f)
        }
    }

    @Test
    fun saturationDependsOnTotalSetsAcrossExercises() {
        val single = MuscleWorkload(listOf(exercise() to 8))
        val split = MuscleWorkload(List(4) { exercise() to 2 })
        listOf("Chest", "Triceps").forEach { group ->
            assertEquals(single.saturationFor(setOf(group)), split.saturationFor(setOf(group)), 0f)
        }
        assertEquals(0.25f, MuscleWorkload(List(32) { exercise() to 1 }).saturationFor(setOf("Triceps")), 0f)
        assertEquals(0f, MuscleWorkload(List(20) { exercise() to 0 }).saturationFor(setOf("Chest")), 0f)
    }

    @Test
    fun mixedWorkAddsPrimarySetsAndCappedSecondaryContribution() {
        val workload = MuscleWorkload(listOf(
            exercise(primary = "Triceps", secondary = "") to 2,
            exercise() to 100,
        ))
        assertEquals(0.5f, workload.saturationFor(setOf("Triceps")), 0f)
        assertEquals(1f, MuscleWorkload(listOf(
            exercise(primary = "Triceps") to 8, exercise() to 100,
        )).saturationFor(setOf("Triceps")), 0f)
    }

    @Test
    fun sharedRegionsCombineWorkWithoutCountingSameSetTwice() {
        val shoulders = setOf("Shoulders", "Front deltoids", "Lateral deltoids")
        val workload = MuscleWorkload(listOf(
            exercise(primary = "Shoulders", secondary = "Front deltoids, Lateral deltoids") to 2,
            exercise(primary = "Front deltoids", secondary = "Shoulders") to 2,
            exercise(secondary = "Shoulders, Front deltoids, Lateral deltoids") to 4,
        ))
        assertEquals(0.625f, workload.saturationFor(shoulders), 0f)
    }

    @Test
    fun normalizesAliasesAndIgnoresUnknownAndUnusedGroups() {
        val workload = MuscleWorkload(listOf(
            exercise(primary = " front delts ", secondary = "REAR DELTS, rear delts, —, unknown") to 4,
        ))
        assertEquals(0.5f, workload.saturationFor(setOf("Front deltoids")), 0f)
        assertEquals(0.125f, workload.saturationFor(setOf("rear delts")), 0f)
        assertEquals(0f, workload.saturationFor(setOf("Biceps")), 0f)
        assertEquals(0f, workload.saturationFor(setOf("unknown")), 0f)
        assertEquals(0f, workload.saturationFor(emptySet()), 0f)
    }
}
