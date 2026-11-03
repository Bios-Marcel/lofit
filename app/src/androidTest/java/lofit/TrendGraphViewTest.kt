package lofit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrendGraphViewTest {
    @Test
    fun recordLineStopsBeforeRightEdgeLeavingEmptySpace() {
        checkGraph(listOf(TrendPoint(1000000000, 60.0), TrendPoint(1604800000, 80.0)))
    }

    @Test
    fun singleRecordStaysCenteredWithoutLineToRightEdge() {
        checkGraph(listOf(TrendPoint(1000000000, 60.0)))
    }

    private fun checkGraph(points: List<TrendPoint>) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val density = context.resources.displayMetrics.density
            val padding =
                maxOf((points.last().completedAt - points.first().completedAt) / 20, 43200000L)
            val view =
                TrendGraphView(
                    context,
                    TrendSeries(TrendMetric.WEIGHT, points),
                    points.first().completedAt - padding,
                    points.last().completedAt + padding,
                )
            val width = (360 * density).toInt()
            val height = (240 * density).toInt()
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, width, height)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val coral = ContextCompat.getColor(context, R.color.coral)
            val plottedX = pixels.indices.filter { pixels[it] == coral }.map { it % width }
            assertTrue("Record points must be visible", plottedX.isNotEmpty())
            assertTrue(
                "Leave empty space after the final record",
                plottedX.max() < width - 20 * density,
            )
            assertTrue("Leave empty space before the first record", plottedX.min() > 40 * density)
            if (points.size == 1) {
                assertTrue("A single record must be centered", plottedX.min() > width / 3)
                assertTrue(
                    "A single record must stay a dot",
                    plottedX.max() - plottedX.min() < 10 * density,
                )
            }
            bitmap.recycle()
        }
    }
}
