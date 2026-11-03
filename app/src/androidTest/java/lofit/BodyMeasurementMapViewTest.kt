package lofit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.min
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BodyMeasurementMapViewTest {
    @Test
    fun bothModelsExposeAllPartsAndGroupAllUpperArmMuscles() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            BiologicalGender.entries.forEach { gender ->
                val reachable = mutableSetOf<Pair<BodyPart, Side>>()
                MuscleMapSide.entries.forEach { side ->
                    var selected: Pair<BodyPart, Side>? = null
                    val view =
                        BodyMeasurementMapView(instrumentation.targetContext, side, gender) {
                            part,
                            bodySide ->
                            selected = part to bodySide
                        }
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
                    )
                    view.layout(0, 0, 400, 800)
                    val bitmap = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    bitmap.recycle()
                    for (x in 0 until 400 step 2) for (y in 0 until 800 step 2) {
                        view.targetAt(x.toFloat(), y.toFloat())?.let { reachable.add(it) }
                    }
                    val anatomy = MuscleMapView.anatomy(instrumentation.targetContext, side, gender)
                    val viewport = anatomy.viewport
                    val scale = min(400 / viewport.width(), 800 / viewport.height())
                    anatomy.regions
                        .filter { it.name in setOf("biceps", "triceps", "gluteal") }
                        .forEach { muscle ->
                            muscle.paths.forEach { path ->
                                val bounds = RectF().also { path.computeBounds(it, true) }
                                val region =
                                    Region().apply {
                                        setPath(
                                            path,
                                            Region(
                                                bounds.left.toInt(),
                                                bounds.top.toInt(),
                                                bounds.right.toInt() + 1,
                                                bounds.bottom.toInt() + 1,
                                            ),
                                        )
                                    }
                                var checked = false
                                for (x in bounds.left.toInt()..bounds.right.toInt() step 3) {
                                    for (y in bounds.top.toInt()..bounds.bottom.toInt() step 3) {
                                        if (
                                            region.contains(x - 2, y) &&
                                                region.contains(x + 2, y) &&
                                                region.contains(x, y - 2) &&
                                                region.contains(x, y + 2)
                                        ) {
                                            val hit =
                                                view.targetAt(
                                                    (x + .5f - viewport.left) * scale +
                                                        (400 - viewport.width() * scale) / 2,
                                                    (y + .5f - viewport.top) * scale +
                                                        (800 - viewport.height() * scale) / 2,
                                                )
                                            assertEquals(
                                                "$gender $side ${muscle.name} at $x,$y",
                                                if (muscle.name == "gluteal") BodyPart.HIPS
                                                else BodyPart.UPPER_ARM,
                                                hit?.first,
                                            )
                                            checked = true
                                        }
                                    }
                                }
                                assertTrue(checked)
                            }
                        }
                    val target =
                        (0 until 400 step 2).firstNotNullOf { x ->
                            (0 until 800 step 2)
                                .firstOrNull { y ->
                                    view.targetAt(x.toFloat(), y.toFloat())?.first ==
                                        BodyPart.UPPER_ARM
                                }
                                ?.let { y -> x.toFloat() to y.toFloat() }
                        }
                    val expected = view.targetAt(target.first, target.second)
                    listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                        MotionEvent.obtain(0, 0, action, target.first, target.second, 0).let { event
                            ->
                            assertTrue(view.dispatchTouchEvent(event))
                            event.recycle()
                        }
                    }
                    assertEquals(expected, selected)
                }
                assertEquals(
                    "$gender all body parts and sides reachable",
                    BodyPart.tracked.flatMap { part -> part.sides.map { part to it } }.toSet(),
                    reachable,
                )
            }
        }
    }
}
