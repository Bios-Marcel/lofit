package lofit

import java.util.Locale

fun formatWorkoutTime(totalSeconds: Int): String {
    val seconds = totalSeconds.coerceAtLeast(0)
    return if (seconds >= 3_600) {
        "${seconds / 3_600}h ${(seconds % 3_600) / 60}m"
    } else {
        String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60)
    }
}
