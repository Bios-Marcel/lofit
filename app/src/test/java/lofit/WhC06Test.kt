package lofit

import org.junit.Assert.*
import org.junit.Test

class WhC06Test {
    @Test
    fun decodesUnsignedBigEndianHundredthsOfKilograms() {
        val payload = ByteArray(15)
        payload[10] = 0x04
        payload[11] = 0xd2.toByte()
        assertEquals(12.34, WhC06.kilograms(payload)!!, 0.0)
        payload[10] = 0x13
        payload[11] = 0x88.toByte()
        assertEquals(50.0, WhC06.kilograms(payload)!!, 0.0)
    }

    @Test
    fun acceptsZeroAndRatedMaximum() {
        assertEquals(0.0, WhC06.kilograms(ByteArray(15))!!, 0.0)
        val payload = ByteArray(15)
        payload[10] = 0x75
        payload[11] = 0x30
        assertEquals(300.0, WhC06.kilograms(payload)!!, 0.0)
    }

    @Test
    fun ignoresMissingTruncatedAndOutOfRangePackets() {
        assertNull(WhC06.kilograms(null))
        (0..11).forEach { assertNull(WhC06.kilograms(ByteArray(it))) }
        val payload = ByteArray(15)
        payload[10] = 0xff.toByte()
        payload[11] = 0xff.toByte()
        assertNull(WhC06.kilograms(payload))
    }
}
