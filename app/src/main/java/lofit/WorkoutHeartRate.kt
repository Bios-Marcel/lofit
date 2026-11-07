package lofit

internal const val HEART_RATE_NEARBY_WINDOW_MILLIS = 120_000L

/** Keeps only the workout samples' sum and the nearest readings outside its edges. */
internal class WorkoutHeartRateAverage(intervals: List<WorkoutInterval>) {
    private val intervals = intervals.filter { it.endedAt != null && it.startedAt < it.endedAt }
    private val start = this.intervals.minOfOrNull { it.startedAt }
    private val end = this.intervals.maxOfOrNull { it.endedAt!! }
    private var totalBpm = 0.0
    private var count = 0L

    private data class Sample(val time: Long, val bpm: Double)

    private var before: Sample? = null
    private var after: Sample? = null

    fun add(time: Long, bpm: Double) {
        if (!bpm.isFinite() || bpm <= 0.0) return
        val start = start ?: return
        val end = end ?: return
        when {
            intervals.any { time >= it.startedAt && time < it.endedAt!! } -> {
                totalBpm += bpm
                count++
            }
            time < start && time >= start - HEART_RATE_NEARBY_WINDOW_MILLIS -> {
                if (before == null || time > before!!.time) before = Sample(time, bpm)
            }
            time >= end && time <= end + HEART_RATE_NEARBY_WINDOW_MILLIS -> {
                if (after == null || time < after!!.time) after = Sample(time, bpm)
            }
        }
    }

    fun average(): Double? {
        if (count > 0) return totalBpm / count
        val before = before ?: return after?.bpm
        val after = after ?: return before.bpm
        val sampleSpan = (after.time - before.time).toDouble()
        val activeDuration = intervals.sumOf { (it.endedAt!! - it.startedAt).toDouble() }
        return intervals.sumOf { interval ->
            val duration = (interval.endedAt!! - interval.startedAt).toDouble()
            val midpoint = (interval.startedAt - before.time).toDouble() + duration / 2
            val bpm = before.bpm + (after.bpm - before.bpm) * midpoint / sampleSpan
            bpm * duration
        } / activeDuration
    }
}

fun workoutsMissingHeartRate(history: List<WorkoutSession>): List<WorkoutSession> =
    history
        .filter { it.completedAt != null }
        .sortedByDescending { it.completedAt }
        .takeWhile { it.averageHeartRateBpm == null }
