package lofit

import org.junit.Assert.*
import org.junit.Test

class VoiceCommandTest {
    @Test
    fun parsesWeightsAndRepsIncludingDecimalsAndHundreds() {
        for ((text, expected) in
            listOf(
                "set fifteen" to VoiceCommand.SetValues(null, 15),
                "set thirty" to VoiceCommand.SetValues(null, 30),
                "set four" to VoiceCommand.SetValues(null, 4),
                "set four times four" to VoiceCommand.SetValues(4.0, 4),
                "set fifty four" to VoiceCommand.SetValues(null, 54),
                "set twelve point five times thirty" to VoiceCommand.SetValues(12.5, 30),
                "set fifty times eight" to VoiceCommand.SetValues(50.0, 8),
                "set twelve point five times ten" to VoiceCommand.SetValues(12.5, 10),
                "set one hundred and twenty five times twelve" to VoiceCommand.SetValues(125.0, 12),
                "set two hundred times twenty one" to VoiceCommand.SetValues(200.0, 21),
                "set zero times fifteen" to VoiceCommand.SetValues(0.0, 15),
                "set two point two five times six" to VoiceCommand.SetValues(2.25, 6),
                "set one thousand five hundred times one" to VoiceCommand.SetValues(1500.0, 1),
            )) assertEquals(text, expected, parseVoiceCommand(text))
    }

    @Test
    fun rejectsIncompleteExtraAndInvalidNumbers() {
        for (text in
            listOf(
                "set",
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
    fun waitsForFinalNumbersAndDoesNotCompleteOrToggleTwice() {
        val gate = VoiceCommandGate()
        assertNull(gate.accept("set fifty", false))
        assertNull(gate.accept("set fifty times eight", false))
        assertNull(gate.accept("set fifty times eighty", false))
        assertEquals(VoiceCommand.SetValues(50.0, 80), gate.accept("set fifty times eighty", true))
        assertNull(gate.accept("", true))
        assertNull(gate.accept("set thirty", false))
        assertEquals(VoiceCommand.SetValues(null, 30), gate.accept("set thirty", true))
        assertEquals(VoiceCommand.DONE, gate.accept("done", false))
        assertNull(gate.accept("done", true))
        assertEquals(VoiceCommand.TIME, gate.accept("time", false))
        gate.reset()
        assertEquals(VoiceCommand.TIME, gate.accept("time", false))
        assertNull(gate.accept("time", true))
        assertEquals(VoiceCommand.SKIP, gate.accept("skip", true))
    }
}
