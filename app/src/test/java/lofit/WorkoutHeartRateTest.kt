package lofit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutHeartRateTest {
    private val start = 1_000_000L

    private fun average(
        vararg samples: Pair<Long, Double>,
        intervals: List<WorkoutInterval> = listOf(WorkoutInterval(start, start + 60_000)),
    ): Double? =
        WorkoutHeartRateAverage(intervals)
            .apply {
                samples.forEach { (offset, bpm) -> add(start + offset * 1000, bpm) }
            }
            .average()

    @Test
    fun oneOrMoreInsideSamplesOverrideAllNearbyReadings() {
        assertEquals(110.0, average(-10L to 60.0, 20L to 110.0, 70L to 180.0)!!, 0.0)
        assertEquals(
            120.0,
            average(-10L to 60.0, 0L to 100.0, 20L to 120.0, 40L to 140.0, 60L to 200.0)!!,
            0.0,
        )
    }

    @Test
    fun nearbyReadingsAreInterpolatedAtWorkoutTimesRatherThanSimplyAveraged() {
        // Samples are 210 seconds apart; the workout midpoint is 60 seconds after the first.
        assertEquals(80.0 + 80.0 * 60.0 / 210.0, average(-30L to 80.0, 180L to 160.0)!!, 0.000001)
    }

    @Test
    fun singleNearbyReadingIsEnoughButAnythingBeyondTwoMinutesIsIgnored() {
        assertEquals(90.0, average(-120L to 90.0, 181L to 200.0)!!, 0.0)
        assertEquals(130.0, average(-121L to 50.0, 180L to 130.0)!!, 0.0)
        assertNull(average(-121L to 50.0, 181L to 200.0))
        assertNull(average())
    }

    @Test
    fun closestReadingOnEachSideWinsRegardlessOfRecordOrder() {
        assertEquals(
            100.0,
            average(90L to 120.0, -120L to 40.0, 180L to 200.0, -30L to 80.0)!!,
            0.0,
        )
        assertEquals(
            100.0,
            average(-30L to 80.0, 180L to 200.0, -120L to 40.0, 90L to 120.0)!!,
            0.0,
        )
    }

    @Test
    fun resumedWorkoutExcludesBreakSamplesAndWeightsInterpolationByActiveTime() {
        val intervals =
            listOf(
                WorkoutInterval(start, start + 10_000),
                WorkoutInterval(start + 30_000, start + 60_000),
            )
        assertEquals(
            60.0 + 120.0 * 95.0 / 180.0,
            average(-60L to 60.0, 20L to 250.0, 120L to 180.0, intervals = intervals)!!,
            0.000001,
        )
        assertEquals(
            110.0,
            average(
                -60L to 60.0,
                5L to 100.0,
                20L to 250.0,
                40L to 120.0,
                120L to 180.0,
                intervals = intervals,
            )!!,
            0.0,
        )
    }

    @Test
    fun invalidSamplesAndUnfinishedOrEmptyIntervalsDoNotProduceAnAverage() {
        assertNull(average(10L to 0.0, 20L to Double.NaN, 30L to Double.POSITIVE_INFINITY))
        assertNull(average(-30L to 80.0, 90L to 120.0, intervals = emptyList()))
        assertNull(
            average(
                -30L to 80.0,
                90L to 120.0,
                intervals = listOf(WorkoutInterval(start), WorkoutInterval(start, start)),
            )
        )
    }

    @Test
    fun missingHeartRatesAreNewestFirstAndStopAtFirstSavedAverage() {
        val oldest = workout(1)
        val boundary = workout(2).apply { averageHeartRateBpm = 100.0 }
        val newer = workout(3)
        val newest = workout(4)
        val pending = workoutsMissingHeartRate(listOf(newer, oldest, newest, boundary))
        assertEquals(listOf(newest, newer), pending)
        newest.averageHeartRateBpm = 120.0
        assertEquals(listOf(newest, newer), pending)
        assertTrue(workoutsMissingHeartRate(listOf(oldest, boundary, newest)).isEmpty())
    }

    @Test
    fun noSavedAverageChecksAllFinishedWorkoutsIncludingOlderPages() {
        val history = (1L..25L).map(::workout)
        assertEquals(
            history.reversed(),
            workoutsMissingHeartRate(history + WorkoutSession(name = "Active")),
        )
        assertTrue(workoutsMissingHeartRate(emptyList()).isEmpty())
    }

    @Test
    fun resumingClearsAverageSoFinishingCanReadAllIntervalsAgain() {
        val now = System.currentTimeMillis()
        val finished =
            WorkoutSession(
                name = "Continue",
                startedAt = now - 60_000,
                completedAt = now,
                averageHeartRateBpm = 110.0,
            )
        val data = AppData(history = mutableListOf(finished))
        val resumed = data.resumeWorkout(finished.id, now + 1_000)!!
        assertNull(resumed.averageHeartRateBpm)
        resumed.finish(now + 60_000)
        assertEquals(2, resumed.intervals.size)
        assertEquals(finished.intervals.first(), resumed.intervals.first())
    }

    private fun workout(end: Long) = WorkoutSession(name = "$end", startedAt = 0, completedAt = end)
}
