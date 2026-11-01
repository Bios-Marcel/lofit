package lofit

import java.math.RoundingMode
import kotlin.math.abs

internal data class PullWeightReading(val atMillis: Long, val kilograms: Double)

/** Detect the two edge ramps from the pull's shape; average every raw reading between them. */
internal fun averagePullWeight(
    readings: List<PullWeightReading>,
    release: PullWeightReading? = null,
): Double? {
    if (readings.isEmpty()) return null
    val profile = if (release == null) readings else readings + release
    val typicalKg = readings.map { it.kilograms }.sorted()[(readings.size - 1) / 2]
    // Ignore scale noise when finding corners, without filtering it out of the actual average.
    val toleranceKg = maxOf(0.02, typicalKg * 0.03)
    val corners = pullCorners(profile, toleranceKg)
    val startCorner = rampEnd(profile, corners, typicalKg, toleranceKg)
    val endCorner = rampEnd(profile, corners.asReversed(), typicalKg, toleranceKg)
    var first = startCorner
    var last = minOf(endCorner, readings.lastIndex)
    // Corners may land on the last of several almost equal plateau readings. Keep all of them.
    while (
        first > 0 &&
            abs(readings[first - 1].kilograms - profile[startCorner].kilograms) <= toleranceKg
    ) first--
    while (
        last < readings.lastIndex &&
            abs(readings[last + 1].kilograms - profile[endCorner].kilograms) <= toleranceKg
    ) last++
    if (first > last) return null
    var meanKg = 0.0
    for (index in first..last) {
        meanKg += (readings[index].kilograms - meanKg) / (index - first + 1)
    }
    return meanKg.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toDouble()
}

/** Simplify vertically against elapsed time, retaining ramp/plateau corners at any sample rate. */
private fun pullCorners(readings: List<PullWeightReading>, toleranceKg: Double): List<Int> {
    val keep = BooleanArray(readings.size)
    keep[0] = true
    keep[readings.lastIndex] = true
    val pending = ArrayDeque<Pair<Int, Int>>()
    pending.addLast(0 to readings.lastIndex)
    while (pending.isNotEmpty()) {
        val (first, last) = pending.removeLast()
        val duration = readings[last].atMillis - readings[first].atMillis
        var largestDeviation = toleranceKg
        var corner = -1
        for (index in first + 1 until last) {
            val fraction =
                if (duration > 0)
                    (readings[index].atMillis - readings[first].atMillis).toDouble() / duration
                else (index - first).toDouble() / (last - first)
            val expected =
                readings[first].kilograms +
                    fraction * (readings[last].kilograms - readings[first].kilograms)
            val deviation = abs(readings[index].kilograms - expected)
            if (deviation > largestDeviation) {
                largestDeviation = deviation
                corner = index
            }
        }
        if (corner >= 0) {
            keep[corner] = true
            pending.addLast(first to corner)
            pending.addLast(corner to last)
        }
    }
    return keep.indices.filter { keep[it] }
}

/** Walk inward from one edge only. A flat or gentle segment ends the ramp search. */
private fun rampEnd(
    readings: List<PullWeightReading>,
    corners: List<Int>,
    typicalKg: Double,
    toleranceKg: Double,
): Int {
    var boundary = corners.first()
    var totalRiseKg = 0.0
    val minimumRiseKg = maxOf(0.05, typicalKg * 0.15)
    val minimumSlopeKgPerSecond = typicalKg * 0.25
    val preloadKg = maxOf(LiveWorkoutMeasurement.PULL_THRESHOLD_KG + toleranceKg, typicalKg * 0.2)
    for ((from, to) in corners.zipWithNext()) {
        val riseKg = readings[to].kilograms - readings[from].kilograms
        val millis = abs(readings[to].atMillis - readings[from].atMillis).coerceAtLeast(1)
        val steep = riseKg * 1_000 / millis >= minimumSlopeKgPerSecond
        if (steep && riseKg > toleranceKg) {
            totalRiseKg += riseKg
            boundary = to
        } else if (
            totalRiseKg < minimumRiseKg &&
                readings[from].kilograms <= preloadKg &&
                readings[to].kilograms <= preloadKg &&
                riseKg >= -toleranceKg
        ) {
            // A little pre-tension can precede the main rise (or follow the main drop).
            continue
        } else break
    }
    return if (totalRiseKg >= minimumRiseKg) boundary else corners.first()
}
