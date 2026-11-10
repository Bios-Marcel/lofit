package lofit

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {
    @Test
    fun parsesOnlyExactWorkoutControls() {
        for ((text, expected) in
            listOf(
                "time" to VoiceCommand.TIME,
                "done" to VoiceCommand.DONE,
                "skip" to VoiceCommand.SKIP,
            )) assertEquals(text, expected, parseVoiceCommand(text))
    }

    @Test
    fun rejectsRemovedSetCommandsAndExtraWords() {
        for (text in
            listOf(
                "set",
                "set fifteen",
                "set thirty",
                "set four",
                "set four times four",
                "set fifty four",
                "set twelve point five times thirty",
                "set fifty times eight",
                "set twelve point five times ten",
                "set one hundred and twenty five times twelve",
                "set two hundred times twenty one",
                "set zero times fifteen",
                "set two point two five times six",
                "set one thousand five hundred times one",
                "",
                "timer",
                "times",
                "done please",
                "skip rest",
                "time now",
                "set fifty for eight",
                "set zero",
                "set minus five",
                "set twelve point five",
                "set fifteen please",
                "set fifty times",
                "fifty times eight",
                "set fifty times eight please",
                "please set fifty times eight",
                "set minus five times eight",
                "set fifty times zero",
                "set fifty times eight point five",
                "set five point twenty five times eight",
                "set one two times eight",
                "set fifty eight eight times eight",
                "set zero hundred times eight",
                "set one hundred and times eight",
                "set one hundred hundred times eight",
                "set five times eight times ten",
                "set [unk] fifty times eight",
                "set five point times eight",
                "set five times ten thousand",
                "set fifty times minus eight",
            )) assertNull(text, parseVoiceCommand(text))
    }

    @Test
    fun ignoresSetCommandsInPartialAndFinalResults() {
        val gate = VoiceCommandGate()
        for (text in listOf("set fifty", "set fifty times eight", "set thirty")) {
            assertNull(gate.accept(text, false))
            assertNull(gate.accept(text, true))
        }
        assertEquals(VoiceCommand.TIME, gate.accept("time", false))
    }

    @Test
    fun controlsRespondToPartialResultsOncePerUtterance() {
        for ((text, expected) in
            listOf(
                "time" to VoiceCommand.TIME,
                "done" to VoiceCommand.DONE,
                "skip" to VoiceCommand.SKIP,
            )) {
            val gate = VoiceCommandGate()
            assertEquals(expected, gate.accept(text, false))
            assertNull(gate.accept(text, false))
            assertNull(gate.accept(text, true))
            assertEquals(expected, gate.accept(text, true))
        }
    }

    @Test
    fun resetAllowsAnotherCommandBeforeUtteranceEnds() {
        val gate = VoiceCommandGate()
        assertEquals(VoiceCommand.TIME, gate.accept("time", false))
        gate.reset()
        assertEquals(VoiceCommand.TIME, gate.accept("time", false))
    }
}
