package lofit

import java.time.Instant
import java.time.ZoneId

enum class TrendSpan(val label: String, val months: Long?) {
    MONTH("1M", 1), THREE_MONTHS("3M", 3), SIX_MONTHS("6M", 6), YEAR("1Y", 12), ALL("All", null);

    fun startedAt(now: Long, zone: ZoneId): Long? = months?.let {
        Instant.ofEpochMilli(now).atZone(zone).minusMonths(it).toInstant().toEpochMilli()
    }
}

enum class TrendMetric(val label: String, val unit: String) {
    WEIGHT("Highest weight", "kg"), HOLD("Longest hold", "s"), REPS("Most repetitions", "reps"),
    VOLUME("Highest set volume", "kg·reps");

    fun value(set: LoggedSet): Double = when (this) {
        WEIGHT -> set.weightKg
        HOLD -> set.holdSeconds.toDouble()
        REPS -> set.reps.toDouble()
        VOLUME -> set.volume()
    }
}

data class TrendPoint(val completedAt: Long, val value: Double)
data class TrendSeries(val metric: TrendMetric, val points: List<TrendPoint>)
data class ExerciseTrendData(val startedAt: Long, val endedAt: Long, val series: List<TrendSeries>)

fun LoggedSet.isRecordEligible(exercise: Exercise): Boolean =
    (!exercise.weighted || weightKg > 0) &&
        (if (exercise.type.tracksDuration) holdSeconds > 0 else reps > 0)

fun AppData.exerciseTrend(
    exerciseId: String,
    span: TrendSpan = TrendSpan.YEAR,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): ExerciseTrendData {
    val records = exerciseRecordHistory()[exerciseId].orEmpty()
    val exercise = exercises.firstOrNull { it.id == exerciseId }
    val metrics = when {
        exercise == null -> emptyList()
        exercise.type.tracksDuration -> listOf(TrendMetric.HOLD)
        exercise.weighted -> listOf(TrendMetric.WEIGHT, TrendMetric.VOLUME)
        else -> listOf(TrendMetric.REPS)
    }
    val series = metrics.map { metric ->
        val sets = records.mapNotNull { if (metric == TrendMetric.VOLUME) it.volumeSet else it.set }
            .distinctBy { it.id }
            .filter { it.completedAt <= now }
        TrendSeries(metric, sets.map { TrendPoint(it.completedAt, metric.value(it)) })
    }
    val startedAt = span.startedAt(now, zone)
        ?: series.flatMap { it.points }.minOfOrNull { it.completedAt }
        ?: TrendSpan.YEAR.startedAt(now, zone)!!
    return ExerciseTrendData(startedAt, now, series.map { seriesItem ->
        // A record set before the span remains the baseline until it is beaten.
        val baseline = seriesItem.points.lastOrNull { it.completedAt < startedAt }
        seriesItem.copy(points = listOfNotNull(baseline?.copy(completedAt = startedAt)) +
            seriesItem.points.filter { it.completedAt >= startedAt })
    })
}
