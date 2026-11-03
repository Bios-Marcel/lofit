package lofit

import org.junit.Assert.*
import org.junit.Test

class BodyMeasurementsTest {
    @Test
    fun parsesDecimalsAndRejectsInvalidValues() {
        assertEquals(32.5, parseBodyMeasurement(" 32,5 ")!!, 0.0)
        listOf("", "no", "0", "-1", "NaN", "Infinity", "1e999").forEach {
            assertNull(parseBodyMeasurement(it))
        }
    }

    @Test
    fun historyKeepsDecreasesSameDayReadingsAndIndependentSides() {
        val entries =
            listOf(
                BodyMeasurement(
                    bodyPart = BodyPart.UPPER_ARM,
                    side = Side.LEFT,
                    centimeters = 31.0,
                    measuredAt = 300,
                ),
                BodyMeasurement(
                    bodyPart = BodyPart.UPPER_ARM,
                    side = Side.RIGHT,
                    centimeters = 34.0,
                    measuredAt = 200,
                ),
                BodyMeasurement(bodyPart = BodyPart.WAIST, centimeters = 80.0, measuredAt = 100),
                BodyMeasurement(
                    bodyPart = BodyPart.UPPER_ARM,
                    side = Side.LEFT,
                    centimeters = 33.0,
                    measuredAt = 100,
                ),
                BodyMeasurement(
                    bodyPart = BodyPart.UPPER_ARM,
                    side = Side.LEFT,
                    centimeters = 32.0,
                    measuredAt = 200,
                ),
            )
        assertEquals(
            listOf(33.0, 32.0, 31.0),
            entries.forBodyPart(BodyPart.UPPER_ARM, Side.LEFT).map { it.centimeters },
        )
        assertEquals(4, entries.forBodyPart(BodyPart.UPPER_ARM).size)
        assertEquals(
            34.0,
            entries.forBodyPart(BodyPart.UPPER_ARM, Side.RIGHT).single().centimeters,
            0.0,
        )
    }

    @Test
    fun rejectsInvalidCircumferencesAndSides() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                BodyMeasurement(bodyPart = BodyPart.WAIST, centimeters = value)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BodyMeasurement(bodyPart = BodyPart.UPPER_ARM, centimeters = 30.0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BodyMeasurement(bodyPart = BodyPart.NECK, side = Side.LEFT, centimeters = 30.0)
        }
    }
}
