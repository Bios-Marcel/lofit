package lofit

/** A bounded trace for the visible live graph. It is never written to app data. */
internal class LiveMeasurementSeries {
    companion object {
        const val WINDOW_MILLIS = 60_000L
        const val MAX_SAMPLES = 6_000
    }

    data class Sample(val atMillis: Long, val kilograms: Double, val startsSegment: Boolean)

    private val buffer = ArrayDeque<Sample>()
    private var nextStartsSegment = true
    var lastPullAverageKg: Double? = null
    val samples: List<Sample>
        get() = buffer.toList()

    fun add(atMillis: Long, kilograms: Double) {
        if (!kilograms.isFinite() || kilograms < 0) return
        val last = buffer.lastOrNull()
        if (last != null && atMillis < last.atMillis) return
        trim(atMillis)
        buffer.addLast(
            Sample(
                atMillis,
                kilograms,
                nextStartsSegment || last == null || atMillis - last.atMillis > 5_000,
            )
        )
        nextStartsSegment = false
        while (buffer.size > MAX_SAMPLES) buffer.removeFirst()
    }

    fun endSegment() {
        nextStartsSegment = true
    }

    fun trim(nowMillis: Long) {
        val cutoff = nowMillis - WINDOW_MILLIS
        while (buffer.firstOrNull()?.let { it.atMillis < cutoff } == true) buffer.removeFirst()
    }
}
