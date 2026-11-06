package lofit

internal sealed interface VoiceCommand {
    data object TIME : VoiceCommand

    data object SKIP : VoiceCommand

    data object DONE : VoiceCommand

    /** Amount is repetitions or goal seconds, according to the current exercise. */
    data class SetValues(val weightKg: Double?, val amount: Int) : VoiceCommand
}

/** Parse the entire utterance; never guess at missing, extra, or malformed numbers. */
internal fun parseVoiceCommand(text: String): VoiceCommand? {
    when (text) {
        "time" -> return VoiceCommand.TIME
        "skip" -> return VoiceCommand.SKIP
        "done" -> return VoiceCommand.DONE
    }
    if (!text.startsWith("set ")) return null
    val parts = text.removePrefix("set ").split(" times ")
    if (parts.size !in 1..2) return null
    val weight = if (parts.size == 2) spokenWeight(parts[0]) ?: return null else null
    val amount = spokenInteger(parts.last().split(" ")) ?: return null
    if (amount !in 1..9999) return null
    return VoiceCommand.SetValues(weight, amount)
}

/** Shared by visible and locked-screen commands; never changes elapsed/measured time. */
internal fun applyVoiceSetValues(
    command: VoiceCommand.SetValues,
    exercise: Exercise,
    set: PlannedSet,
): Boolean {
    if (exercise.weighted != (command.weightKg != null) || command.amount !in 1..9999) return false
    val weight = command.weightKg
    if (weight != null && (!weight.isFinite() || weight < 0)) return false
    if (weight != null) set.weightKg = weight
    if (exercise.type.tracksDuration) set.holdSeconds = command.amount
    else set.reps = command.amount
    return true
}

private val smallNumbers =
    "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen"
        .split(" ")
private val tens = "twenty thirty forty fifty sixty seventy eighty ninety".split(" ")

private fun spokenInteger(words: List<String>): Int? {
    if (words.isEmpty()) return null
    if (words.size == 1) {
        smallNumbers
            .indexOf(words[0])
            .takeIf { it >= 0 }
            ?.let {
                return it
            }
        tens
            .indexOf(words[0])
            .takeIf { it >= 0 }
            ?.let {
                return (it + 2) * 10
            }
        return null
    }
    val scaleIndex = words.indexOf("thousand").takeIf { it >= 0 } ?: words.indexOf("hundred")
    if (scaleIndex >= 0) {
        if (scaleIndex != 1) return null
        val leading = smallNumbers.indexOf(words[0]).takeIf { it in 1..9 } ?: return null
        val scale = if (words[scaleIndex] == "thousand") 1000 else 100
        if (words.size == 2) return leading * scale
        val remainderWords = words.drop(if (words.getOrNull(2) == "and") 3 else 2)
        val remainder = spokenInteger(remainderWords) ?: return null
        return (leading * scale + remainder).takeIf { remainder in 1 until scale }
    }
    if (words.size != 2) return null
    val ten = tens.indexOf(words[0]).takeIf { it >= 0 } ?: return null
    val unit = smallNumbers.indexOf(words[1]).takeIf { it in 1..9 } ?: return null
    return (ten + 2) * 10 + unit
}

private fun spokenWeight(text: String): Double? {
    val parts = text.split(" point ")
    if (parts.size !in 1..2) return null
    val whole = spokenInteger(parts[0].split(" ")) ?: return null
    if (parts.size == 1) return whole.toDouble()
    val digits = parts[1].split(" ")
    if (digits.size !in 1..2) return null
    val decimal = digits.map { smallNumbers.indexOf(it).takeIf { n -> n in 0..9 } ?: return null }
    return "$whole.${decimal.joinToString("")}".toDouble()
}

/** Numbers must settle at the utterance end, e.g. eight may still become eighty. */
internal class VoiceCommandGate {
    private var triggered = false

    fun reset() {
        triggered = false
    }

    fun accept(text: String, ended: Boolean): VoiceCommand? {
        val parsed = parseVoiceCommand(text)
        // LEGACY: Single-word controls still respond to partial results for fast timer toggles.
        val command = parsed?.takeIf { !triggered && (ended || it !is VoiceCommand.SetValues) }
        if (command != null) triggered = true
        if (ended) triggered = false
        return command
    }
}
