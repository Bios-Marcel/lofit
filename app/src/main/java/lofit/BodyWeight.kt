package lofit

import java.math.RoundingMode

data class BodyWeightMeasurement(val time: Long, val kilograms: Double)

fun roundBodyWeight(kilograms: Double): Double =
    kilograms.toBigDecimal().setScale(2, RoundingMode.HALF_UP).toDouble()

fun bodyWeightAt(time: Long, measurements: List<BodyWeightMeasurement>): Double? {
    val valid = measurements.filter { it.kilograms.isFinite() && it.kilograms > 0 }
    val before = valid.filter { it.time <= time }.maxByOrNull { it.time }
    if (before?.time == time) return roundBodyWeight(before.kilograms)
    val after = valid.filter { it.time > time }.minByOrNull { it.time }
    val kilograms = if (before != null && after != null) {
        (before.kilograms + after.kilograms) / 2
    } else {
        before?.kilograms ?: after?.kilograms
    }
    return kilograms?.let(::roundBodyWeight)
}
