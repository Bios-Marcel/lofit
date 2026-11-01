package lofit

import org.junit.Assert.*
import org.junit.Test

class PullWeightAverageTest {
    private fun average(vararg samples: Pair<Long, Double>, releaseAt: Long? = null): Double? =
        averagePullWeight(
            samples.map { (time, kilograms) -> PullWeightReading(time, kilograms) },
            releaseAt?.let { PullWeightReading(it, 0.0) },
        )

    @Test
    fun emptyPullHasNoAverage() {
        assertNull(average())
    }

    @Test
    fun fastFlatPullKeepsReadingsRightAtTheEdges() {
        assertEquals(
            31.23,
            average(0L to 31.23, 40L to 31.23, 90L to 31.23, releaseAt = 100)!!,
            0.0,
        )
    }

    @Test
    fun instantLoadingAndReleaseKeepBothFullLoadSamples() {
        assertEquals(20.0, average(0L to 20.0, 2_000L to 20.0, releaseAt = 2_010)!!, 0.0)
    }

    @Test
    fun excludesLongOpeningAndClosingRampsByShape() {
        assertEquals(
            30.0,
            average(
                0L to 0.6,
                300L to 3.0,
                600L to 10.0,
                900L to 23.0,
                1_200L to 30.0,
                2_000L to 30.0,
                7_000L to 30.0,
                7_300L to 24.0,
                7_600L to 14.0,
                7_900L to 4.0,
                releaseAt = 8_000,
            )!!,
            0.0,
        )
    }

    @Test
    fun adaptsToVeryFastRampsWithoutDroppingTheNearbyHold() {
        assertEquals(
            30.0,
            average(
                0L to 0.6,
                10L to 10.0,
                20L to 30.0,
                40L to 30.0,
                60L to 30.0,
                70L to 10.0,
                releaseAt = 80,
            )!!,
            0.0,
        )
    }

    @Test
    fun keepsEveryDipSpikeAndLaterRampBetweenTheOpeningAndClosingRamps() {
        assertEquals(
            32.86,
            average(
                0L to 0.6,
                200L to 30.0,
                1_000L to 30.0,
                1_100L to 10.0,
                1_200L to 30.0,
                1_500L to 70.0,
                1_600L to 30.0,
                2_000L to 30.0,
                releaseAt = 2_200,
            )!!,
            0.0,
        )
    }

    @Test
    fun keepsGradualFatigueInsteadOfSelectingOnlyTheHighestPlateau() {
        assertEquals(
            22.5,
            average(
                0L to 0.6,
                200L to 30.0,
                5_000L to 28.0,
                10_000L to 25.0,
                15_000L to 22.0,
                20_000L to 18.0,
                25_000L to 12.0,
                25_100L to 5.0,
                releaseAt = 25_200,
            )!!,
            0.0,
        )
    }

    @Test
    fun doesNotTreatAnInteriorSpikeAsAnOpeningRampWhenThePullStartsFlat() {
        assertEquals(
            33.33,
            average(
                0L to 20.0,
                2_000L to 20.0,
                2_100L to 100.0,
                2_200L to 20.0,
                4_000L to 20.0,
                6_000L to 20.0,
                releaseAt = 6_100,
            )!!,
            0.0,
        )
    }

    @Test
    fun roundsTheWholeRetainedIntervalOnceAndAllowsScaleNoise() {
        assertEquals(
            15.12,
            average(
                0L to 0.6,
                100L to 15.113,
                200L to 15.133,
                300L to 15.123,
                400L to 15.115,
                500L to 15.125,
                releaseAt = 600,
            )!!,
            0.0,
        )
    }

    @Test
    fun handlesRepeatedReadingsAndEqualTimestampsWithoutRemovingThePlateau() {
        assertEquals(
            15.12,
            average(
                0L to 0.6,
                100L to 15.113,
                100L to 15.133,
                500L to 15.113,
                500L to 15.133,
                600L to 2.0,
                releaseAt = 600,
            )!!,
            0.0,
        )
    }

    @Test
    fun canSkipPreTensionBeforeTheActualRamp() {
        assertEquals(
            30.0,
            average(
                0L to 0.6,
                1_000L to 0.6,
                1_100L to 3.0,
                1_200L to 15.0,
                1_300L to 30.0,
                3_000L to 30.0,
                releaseAt = 3_100,
            )!!,
            0.0,
        )
    }

    @Test
    fun interruptedPullDoesNotInventAFallOff() {
        assertEquals(
            20.0,
            average(
                0L to 0.6,
                100L to 20.0,
                1_000L to 20.0,
                2_000L to 20.0,
            )!!,
            0.0,
        )
    }

    @Test
    fun gradualChangeWithoutStrongRampsKeepsTheWholeInterval() {
        assertEquals(
            22.0,
            average(
                0L to 20.0,
                5_000L to 21.0,
                10_000L to 22.0,
                15_000L to 23.0,
                20_000L to 24.0,
            )!!,
            0.0,
        )
    }
}
