package lofit

internal object WhC06 {
    const val MANUFACTURER_ID = 0x0100

    // WH-C06 manufacturer payload: big-endian hundredths of a kilogram at bytes 10–11.
    // https://github.com/Stevie-Ray/hangtime-grip-connect/blob/main/packages/core/src/models/device/wh-c06.model.ts
    fun kilograms(payload: ByteArray?): Double? {
        if (payload == null || payload.size < 12) return null
        val raw = ((payload[10].toInt() and 0xff) shl 8) or (payload[11].toInt() and 0xff)
        return raw.takeIf { it <= 30_000 }?.div(100.0)
    }
}

data class CraneScaleDevice(val address: String, val name: String)
