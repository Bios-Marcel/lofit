package lofit

import java.util.UUID

enum class BodyPart(
    val label: String,
    val paired: Boolean = false,
    val selectable: Boolean = true,
) {
    NECK("Neck"),
    SHOULDERS("Shoulders"),
    CHEST("Chest"),
    WAIST("Waist"),
    HIPS("Glutes"),
    UPPER_ARM("Upper arm", true),
    FOREARM("Forearm", true),
    THIGH("Thigh", true),
    CALF("Calf", true),

    // LEGACY: Keep saved measurements readable; these regions are no longer offered for tracking.
    HEAD("Head", selectable = false),
    UNDER_CHEST("Under chest / bust", selectable = false),
    ABDOMEN("Abdomen", selectable = false),
    WRIST("Wrist", true, selectable = false),
    HAND("Hand", true, selectable = false),
    KNEE("Knee", true, selectable = false),
    ANKLE("Ankle", true, selectable = false),
    FOOT("Foot", true, selectable = false);

    val sides: List<Side>
        get() = if (paired) listOf(Side.LEFT, Side.RIGHT) else listOf(Side.BOTH)

    companion object {
        val tracked = entries.filter { it.selectable }
    }
}

data class BodyMeasurement(
    val id: String = UUID.randomUUID().toString(),
    val bodyPart: BodyPart,
    val side: Side = Side.BOTH,
    val centimeters: Double,
    val measuredAt: Long = System.currentTimeMillis(),
) {
    init {
        require(centimeters.isFinite() && centimeters > 0) { "Enter a positive measurement" }
        require(side in bodyPart.sides) { "Choose a valid body side" }
    }
}

fun List<BodyMeasurement>.forBodyPart(
    bodyPart: BodyPart,
    side: Side? = null,
): List<BodyMeasurement> = filter {
    it.bodyPart == bodyPart && (side == null || it.side == side)
}
    .sortedBy { it.measuredAt }

fun parseBodyMeasurement(value: String): Double? =
    value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
