package lofit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BodyMeasurementGraphViewTest {
    @Test
    fun plotsSingleReadingsDecreasesAndUnevenDecimalsWithoutLosingThePlotArea() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val first =
                BodyMeasurement(
                    bodyPart = BodyPart.WAIST,
                    centimeters = 80.01,
                    measuredAt = 1000000000,
                )
            val second =
                first.copy(
                    id = "second",
                    centimeters = 79.1,
                    measuredAt = first.measuredAt + 86400000,
                )
            listOf(listOf(first), listOf(first, second)).forEach { points ->
                val view = BodyMeasurementGraphView(context, BodyPart.WAIST, points)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, 360, 260)
                val bitmap = Bitmap.createBitmap(360, 260, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val pixels = IntArray(360 * 260)
                bitmap.getPixels(pixels, 0, 360, 0, 0, 360, 260)
                assertTrue(
                    "Measurement dots and line must remain visible",
                    pixels.any { it == ContextCompat.getColor(context, R.color.coral) },
                )
                assertEquals(points, view.measurements)
                assertTrue(
                    view.stateDescription.toString().contains(formatWeight(first.centimeters))
                )
                bitmap.recycle()
            }
        }
    }
}
