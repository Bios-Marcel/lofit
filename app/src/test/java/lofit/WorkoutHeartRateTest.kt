package lofit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutHeartRateTest {
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
