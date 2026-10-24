package lofit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BodyWeightTest {
    private val measurements = listOf(
        BodyWeightMeasurement(400, 90.0),
        BodyWeightMeasurement(100, 70.0),
        BodyWeightMeasurement(300, 80.0),
    )

    @Test
    fun betweenMeasurements_usesArithmeticMeanOfNearestNeighbors() {
        // Deliberately off-center: this must be an average, not linear interpolation.
        assertEquals(75.0, bodyWeightAt(120, measurements)!!, 0.0)
    }

    @Test
    fun exactTime_usesThatMeasurement() {
        assertEquals(80.0, bodyWeightAt(300, measurements)!!, 0.0)
    }

    @Test
    fun outsideMeasuredRange_usesNearestMeasurement() {
        assertEquals(70.0, bodyWeightAt(50, measurements)!!, 0.0)
        assertEquals(90.0, bodyWeightAt(500, measurements)!!, 0.0)
    }

    @Test
    fun emptyOrInvalidMeasurements_leaveWeightUnknown() {
        assertNull(bodyWeightAt(200, emptyList()))
        assertNull(bodyWeightAt(200, listOf(
            BodyWeightMeasurement(100, Double.NaN),
            BodyWeightMeasurement(300, 0.0),
            BodyWeightMeasurement(400, -5.0),
        )))
    }
}
