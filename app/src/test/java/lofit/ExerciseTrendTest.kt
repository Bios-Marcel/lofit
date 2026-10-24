package lofit

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseTrendTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val now = ZonedDateTime.of(2026, 10, 24, 12, 0, 0, 0, zone)
    private val bench = Exercise("bench", "Bench", ExerciseType.REPETITIONS, false, "Chest", "")

    private fun set(monthsAgo: Long, weight: Double, reps: Int = 5) = LoggedSet(
        type = SetType.NORMAL, weightKg = weight, reps = reps,
        completedAt = now.minusMonths(monthsAgo).toInstant().toEpochMilli(),
    )

    private fun data(vararg sets: LoggedSet) = AppData(
        exercises = mutableListOf(bench),
        history = mutableListOf(WorkoutSession(name = "Records", exercises = mutableListOf(
            WorkoutExercise(exerciseId = bench.id, sets = sets.toMutableList()),
        ))),
    )

    @Test
    fun defaultSpan_isCalendarYearWithEarlierBaseline() {
        val data = data(set(18, 40.0), set(6, 60.0), set(0, 80.0))

        val trend = data.exerciseTrend(bench.id, now = now.toInstant().toEpochMilli(), zone = zone)

        assertEquals(now.minusYears(1).toInstant().toEpochMilli(), trend.startedAt)
        assertEquals(listOf(40.0, 60.0, 80.0), trend.series.first().points.map { it.value })
        assertEquals(trend.startedAt, trend.series.first().points.first().completedAt)
        assertEquals(listOf(200.0, 300.0, 400.0), trend.series.last().points.map { it.value })
    }

    @Test
    fun spans_limitPointsAndAllIncludesEarliestRecord() {
        val first = set(18, 40.0)
        val data = data(first, set(6, 60.0), set(0, 80.0))
        val month = data.exerciseTrend(bench.id, TrendSpan.MONTH, now.toInstant().toEpochMilli(), zone)
        val all = data.exerciseTrend(bench.id, TrendSpan.ALL, now.toInstant().toEpochMilli(), zone)

        assertEquals(now.minusMonths(1).toInstant().toEpochMilli(), month.startedAt)
        assertEquals(listOf(60.0, 80.0), month.series.first().points.map { it.value })
        assertEquals(first.completedAt, all.startedAt)
        assertEquals(3, all.series.first().points.size)
        assertEquals(listOf(1L, 3L, 6L, 12L), TrendSpan.entries.mapNotNull { it.months })
        val leapDay = ZonedDateTime.of(2024, 2, 29, 12, 0, 0, 0, zone)
        assertEquals(leapDay.minusYears(1).toInstant().toEpochMilli(), TrendSpan.YEAR.startedAt(leapDay.toInstant().toEpochMilli(), zone))
    }

    @Test
    fun zeroRepsAndZeroWeight_cannotHideValidRecords() {
        val data = data(set(6, 60.0), set(3, 200.0, reps = 0), set(2, 0.0, reps = 100), set(0, 80.0))
        val trend = data.exerciseTrend(bench.id, TrendSpan.ALL, now.toInstant().toEpochMilli(), zone)

        assertEquals(listOf(60.0, 80.0), trend.series.first().points.map { it.value })
        assertEquals(listOf(300.0, 400.0), trend.series.last().points.map { it.value })
        assertEquals(80.0, data.oneRepMaxes().single().set.weightKg, 0.0)
        val workout = WorkoutSession(name = "Invalid attempt", exercises = mutableListOf(
            WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(set(0, 200.0, reps = 0))),
        ))
        assertTrue(data.recordSetIds(workout).isEmpty())
    }

    @Test
    fun independentRecords_doNotDuplicatePointsAndFutureSetsAreExcluded() {
        val data = data(set(6, 60.0), set(3, 80.0, reps = 2), set(1, 50.0, reps = 10), set(-1, 100.0))
        val trend = data.exerciseTrend(bench.id, TrendSpan.ALL, now.toInstant().toEpochMilli(), zone)

        assertEquals(listOf(60.0, 80.0), trend.series.first().points.map { it.value })
        assertEquals(listOf(300.0, 500.0), trend.series.last().points.map { it.value })
        assertTrue(trend.series.flatMap { it.points }.all { it.completedAt <= trend.endedAt })
    }

    @Test
    fun sameLocalDay_keepsHighestValuePerMetricAcrossWorkouts() {
        val morning = now.withHour(1)
        fun logged(time: ZonedDateTime, weight: Double, reps: Int) = set(0, weight, reps).copy(
            completedAt = time.toInstant().toEpochMilli(),
        )
        val first = logged(morning, 60.0, 5)
        val weight = logged(morning.plusHours(1), 80.0, 2)
        val volume = logged(morning.plusHours(2), 50.0, 10)
        val tiedWeight = logged(morning.plusHours(3), 80.0, 1)
        val nextDay = logged(morning.plusDays(1), 90.0, 6)
        val data = data(weight, first)
        data.history.addAll(data(nextDay, volume, tiedWeight).history)

        val trend = data.exerciseTrend(bench.id, TrendSpan.ALL,
            now.plusDays(1).toInstant().toEpochMilli(), zone)

        assertEquals(listOf(80.0, 90.0), trend.series.first().points.map { it.value })
        assertEquals(listOf(500.0, 540.0), trend.series.last().points.map { it.value })
        assertEquals(tiedWeight.completedAt, trend.series.first().points.first().completedAt)
        assertEquals(volume.completedAt, trend.series.last().points.first().completedAt)
        assertTrue(trend.series.all { series ->
            series.points.map { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
                .distinct().size == series.points.size
        })
    }

    @Test
    fun spanBaseline_onSameDayAsRecord_keepsOnlyHigherRecord() {
        val start = now.minusMonths(1)
        val earlier = set(2, 60.0)
        val beforeStart = set(1, 70.0).copy(completedAt = start.minusHours(1).toInstant().toEpochMilli())
        val afterStart = set(1, 80.0).copy(completedAt = start.plusHours(1).toInstant().toEpochMilli())
        val trend = data(earlier, beforeStart, afterStart).exerciseTrend(
            bench.id, TrendSpan.MONTH, now.toInstant().toEpochMilli(), zone,
        )

        assertEquals(listOf(80.0), trend.series.first().points.map { it.value })
        assertEquals(listOf(400.0), trend.series.last().points.map { it.value })
        assertEquals(afterStart.completedAt, trend.series.first().points.single().completedAt)
    }

    @Test
    fun bodyweightAndHolds_keepDailyHighAcrossDaylightSavingChange() {
        val pushUp = bench.copy(id = "push-up", weighted = false)
        val plank = bench.copy(id = "plank", type = ExerciseType.TIMED, weighted = false)
        val midnight = now.plusDays(1).toLocalDate().atStartOfDay(zone)
        val times = listOf(midnight.minusMinutes(30), midnight.plusMinutes(30),
            midnight.plusHours(2), midnight.plusHours(3))
        val data = AppData(exercises = mutableListOf(pushUp, plank), history = mutableListOf(
            WorkoutSession(name = "Daily records", exercises = mutableListOf(pushUp, plank).map { exercise ->
                WorkoutExercise(exerciseId = exercise.id, sets = times.mapIndexed { index, time ->
                    set(0, 0.0, reps = (index + 1) * 10).copy(
                        holdSeconds = (index + 1) * 15, completedAt = time.toInstant().toEpochMilli(),
                    )
                }.toMutableList())
            }.toMutableList()),
        ))
        val endedAt = midnight.plusDays(1).toInstant().toEpochMilli()

        assertEquals(listOf(10.0, 40.0), data.exerciseTrend(pushUp.id, TrendSpan.ALL, endedAt, zone)
            .series.single().points.map { it.value })
        assertEquals(listOf(15.0, 60.0), data.exerciseTrend(plank.id, TrendSpan.ALL, endedAt, zone)
            .series.single().points.map { it.value })
    }

    @Test
    fun bodyweightAndHolds_allowZeroWeightAndIgnoreUnusedReps() {
        val pushUp = bench.copy(id = "push-up", weighted = false)
        val plank = bench.copy(id = "plank", type = ExerciseType.TIMED, weighted = false)
        val reps = set(0, 0.0, reps = 20)
        val hold = set(0, 0.0, reps = 0).copy(holdSeconds = 45)
        val data = AppData(exercises = mutableListOf(pushUp, plank), history = mutableListOf(
            WorkoutSession(name = "Bodyweight", exercises = mutableListOf(
                WorkoutExercise(exerciseId = pushUp.id, sets = mutableListOf(reps)),
                WorkoutExercise(exerciseId = plank.id, sets = mutableListOf(hold)),
            )),
        ))

        assertTrue(reps.isRecordEligible(pushUp))
        assertTrue(hold.isRecordEligible(plank))
        assertFalse(reps.copy(reps = 0).isRecordEligible(pushUp))
        assertFalse(hold.copy(holdSeconds = 0).isRecordEligible(plank))
        assertEquals(TrendMetric.REPS, data.exerciseTrend(pushUp.id).series.single().metric)
        assertEquals(TrendMetric.HOLD, data.exerciseTrend(plank.id).series.single().metric)
    }

    @Test
    fun noValidSets_hasEmptySeries() {
        val trend = data(set(0, 0.0, reps = 0)).exerciseTrend(bench.id)
        assertTrue(trend.series.all { it.points.isEmpty() })
        assertTrue(AppData(exercises = mutableListOf()).exerciseTrend("missing").series.isEmpty())
    }
}
