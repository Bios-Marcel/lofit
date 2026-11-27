package lofit

import java.util.Locale

internal fun formatExerciseTime(totalSeconds: Int, showSecondsUnit: Boolean = false): String =
    formatDuration(totalSeconds, minuteThreshold = 100, showSecondsUnit = showSecondsUnit)

internal fun formatRestTime(totalSeconds: Int, showSecondsUnit: Boolean = false): String =
    formatDuration(totalSeconds, minuteThreshold = 60, showSecondsUnit = showSecondsUnit)

private fun formatDuration(
    totalSeconds: Int,
    minuteThreshold: Int,
    showSecondsUnit: Boolean,
): String =
    if (totalSeconds >= minuteThreshold) {
        "${totalSeconds / 60}m${(totalSeconds % 60).toString().padStart(2, '0')}"
    } else {
        totalSeconds.toString().padStart(2, '0') + if (showSecondsUnit) "s" else ""
    }

internal fun parseDurationInput(value: String): Int? {
    val text = value.trim()
    val separator = text.indexOf('m', ignoreCase = true)
    // LEGACY: Keep accepting plain seconds, including values of 100 seconds or more.
    if (separator == -1) return text.toIntOrNull()?.takeIf { it >= 0 }

    val minutes = text.substring(0, separator).toIntOrNull()?.takeIf { it >= 0 } ?: return null
    val seconds = text.substring(separator + 1).ifEmpty { "00" }
    if (seconds.length !in 1..2 || !seconds.all(Char::isDigit)) return null
    val remainder = seconds.toIntOrNull()?.takeIf { it < 60 } ?: return null
    val total = minutes * 60L + remainder
    return total.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

fun formatWorkoutTime(totalSeconds: Int): String = formatWorkoutTime(totalSeconds.toLong())

internal fun formatWorkoutTime(totalSeconds: Long): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return if (seconds >= 3_600) {
        "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
    } else {
        String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }
}
