package lofit

internal sealed interface VoiceCommand {
    data object TIME : VoiceCommand

    data object SKIP : VoiceCommand

    data object DONE : VoiceCommand
}

/** Only exact single-word controls are commands. */
internal fun parseVoiceCommand(text: String): VoiceCommand? =
    when (text) {
        "time" -> VoiceCommand.TIME
        "skip" -> VoiceCommand.SKIP
        "done" -> VoiceCommand.DONE
        else -> null
    }

/** Emit at most one command per utterance, including repeated partial results. */
internal class VoiceCommandGate {
    private var triggered = false

    fun reset() {
        triggered = false
    }

    fun accept(text: String, ended: Boolean): VoiceCommand? {
        val parsed = parseVoiceCommand(text)
        // LEGACY: Single-word controls still respond to partial results for fast timer toggles.
        val command = parsed?.takeIf { !triggered }
        if (command != null) triggered = true
        if (ended) triggered = false
        return command
    }
}
