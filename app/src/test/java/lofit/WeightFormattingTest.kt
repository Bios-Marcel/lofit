package lofit

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class WeightFormattingTest {
    @Test
    fun bodyWeight_showsTwoDecimalPlacesInEachLocale() {
        assertEquals("64.02", formatBodyWeight(64.020001002032, Locale.US))
        assertEquals("64.03", formatBodyWeight(64.025, Locale.US))
        assertEquals("64.00", formatBodyWeight(64.0, Locale.US))
        assertEquals("64,02", formatBodyWeight(64.020001002032, Locale.GERMANY))
        assertEquals("64,50", formatBodyWeight(64.5, Locale.GERMANY))
    }

    @Test
    fun fractionalWeights_keepAllDecimalPlaces() {
        listOf("0", "60", "1.25", "1.125", "1.23456789", "0.0000125", "1234567890.25").forEach {
            text ->
            val weight = text.toDouble()
            assertEquals(text, formatWeight(weight, Locale.US))
            assertEquals(weight, parseWeight(formatWeight(weight, Locale.US), Locale.US)!!, 0.0)
        }
    }

    @Test
    fun localizedWeights_canBeEditedWithoutLosingPrecision() {
        assertEquals("1,25", formatWeight(1.25, Locale.GERMANY))
        assertEquals(1.25, parseWeight("1,25", Locale.GERMANY)!!, 0.0)
        assertEquals(1.25, parseWeight("1.25", Locale.GERMANY)!!, 0.0)
    }

    @Test
    fun volume_keepsFractionalWeightsWithoutBinaryRoundingArtifacts() {
        val set = LoggedSet(type = SetType.NORMAL, weightKg = 0.1, reps = 3)
        assertEquals(0.3, set.volume(), 0.0)
        val workout =
            WorkoutSession(
                name = "Fractional volume",
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = "bench-press",
                            sets =
                                mutableListOf(
                                    set,
                                    LoggedSet(type = SetType.NORMAL, weightKg = 1.25, reps = 3),
                                ),
                        ),
                        WorkoutExercise(
                            exerciseId = "squat",
                            sets =
                                mutableListOf(
                                    LoggedSet(type = SetType.NORMAL, weightKg = 0.2, reps = 1)
                                ),
                        ),
                    ),
            )
        assertEquals(4.25, workout.totalVolume(), 0.0)
        assertEquals("4.25", formatWeight(workout.totalVolume(), Locale.US))
    }
}
