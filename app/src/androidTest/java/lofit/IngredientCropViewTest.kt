package lofit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class IngredientCropViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun landscapeShowsBothMarginsAndAllowsChoosingTheFarRightEdge() {
        instrumentation.runOnMainSync {
            val source = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.GREEN)
            Canvas(source).apply {
                drawRect(0f, 0f, 100f, 200f, Paint().apply { color = Color.RED })
                drawRect(300f, 0f, 400f, 200f, Paint().apply { color = Color.BLUE })
            }
            var selected = Rect()
            val view = cropView(source) { selected = it }
            assertEquals(Rect(100, 0, 300, 200), selected)
            val screen = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(screen))
            val center = view.width / 2
            val left = screen.getPixel(center - 360, view.height / 2)
            val right = screen.getPixel(center + 360, view.height / 2)
            assertTrue(Color.red(left) in 30..190 && Color.green(left) == 0)
            assertTrue(Color.blue(right) in 30..190 && Color.green(right) == 0)
            assertEquals(Color.GREEN, screen.getPixel(center, view.height / 2))
            // Grab the lower-right handle, then move the resulting square to the image boundary.
            drag(view, 200, 200, 40, 40)
            assertEquals(Rect(100, 0, 220, 120), selected)
            drag(view, -80, -80, 400, 200)
            assertEquals(Rect(280, 80, 400, 200), selected)
        }
    }

    @Test
    fun portraitCornerResizesAroundItsOppositeAndStopsAtTheSourceEdges() {
        instrumentation.runOnMainSync {
            var selected = Rect()
            val view =
                cropView(Bitmap.createBitmap(200, 400, Bitmap.Config.ARGB_8888)) { selected = it }
            assertEquals(Rect(0, 100, 200, 300), selected)
            drag(view, -200, -200, -100, -100)
            assertEquals(Rect(50, 150, 200, 300), selected)
            drag(view, -100, -100, -260, -260)
            assertEquals(Rect(0, 100, 200, 300), selected)
        }
    }

    private fun cropView(bitmap: Bitmap, changed: (Rect) -> Unit): IngredientCropView {
        val context = instrumentation.targetContext
        val margins = (24 * context.resources.displayMetrics.density).roundToInt()
        return IngredientCropView(context) { region, _ -> changed(region) }
            .apply {
                val longer = 2 * maxOf(bitmap.width, bitmap.height) + margins
                layout(0, 0, longer, longer)
                setImage(bitmap, null)
            }
    }

    private fun drag(view: IngredientCropView, x1: Int, y1: Int, x2: Int, y2: Int) {
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, dt: Long, x: Int, y: Int) {
            val event =
                MotionEvent.obtain(
                    start,
                    start + dt,
                    action,
                    view.width / 2f + x,
                    view.height / 2f + y,
                    0,
                )
            try {
                assertTrue(view.dispatchTouchEvent(event))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0, x1, y1)
        send(MotionEvent.ACTION_MOVE, 16, x2, y2)
        send(MotionEvent.ACTION_UP, 32, x2, y2)
    }
}
