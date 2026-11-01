package lofit

import org.junit.Assert.*
import org.junit.Test

class LiveMeasurementSeriesTest {
    @Test
    fun keepsTimestampedRepeatedWeightsOnlyWithinLastMinute() {
        val series = LiveMeasurementSeries()
        series.add(0, 10.0)
        series.add(1_000, 10.0)
        series.add(60_000, 20.0)
        assertEquals(listOf(0L, 1_000L, 60_000L), series.samples.map { it.atMillis })
        series.trim(60_001)
        assertEquals(listOf(1_000L, 60_000L), series.samples.map { it.atMillis })
        series.trim(120_001)
        assertTrue(series.samples.isEmpty())
    }

    @Test
    fun breaksLineAcrossStoppedScansAndLostSignal() {
        val series = LiveMeasurementSeries()
        series.add(0, 10.0)
        series.add(1_000, 15.0)
        series.endSegment()
        series.add(2_000, 20.0)
        series.add(3_000, 25.0)
        series.add(10_000, 30.0)
        assertEquals(
            listOf(true, false, true, false, true),
            series.samples.map { it.startsSegment },
        )
    }

    @Test
    fun boundsMemoryAndIgnoresInvalidOrOlderReadings() {
        val series = LiveMeasurementSeries()
        (0..LiveMeasurementSeries.MAX_SAMPLES).forEach { series.add(it.toLong(), 20.0) }
        assertEquals(LiveMeasurementSeries.MAX_SAMPLES, series.samples.size)
        assertEquals(1L, series.samples.first().atMillis)
        val before = series.samples
        series.add(0, 40.0)
        series.add(10_000, Double.NaN)
        series.add(10_000, Double.POSITIVE_INFINITY)
        series.add(10_000, -1.0)
        assertEquals(before, series.samples)
    }
}
