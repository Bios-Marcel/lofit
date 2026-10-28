package lofit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MuscleMapViewTest {
    @Test
    fun bothBodyModelsAndSidesRenderDistinctSetLevelsAndCapSecondaryColor() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            BiologicalGender.entries.forEach { gender ->
                MuscleMapSide.entries.forEach { side ->
                    val group = if (side == MuscleMapSide.FRONT) "Chest" else "Triceps"
                    val offSideGroup = if (side == MuscleMapSide.FRONT) "Lats" else "Chest"
                    fun render(sets: Int, secondary: Boolean = false): Bitmap {
                        val exercise =
                            Exercise(
                                id = "test",
                                name = "Test",
                                type = ExerciseType.REPETITIONS,
                                asymmetric = false,
                                primaryMuscle = if (secondary) offSideGroup else group,
                                secondaryMuscles = if (secondary) group else "",
                            )
                        val view =
                            MuscleMapView(
                                instrumentation.targetContext,
                                side,
                                MuscleWorkload(listOf(exercise to sets)),
                                gender,
                            )
                        view.measure(
                            View.MeasureSpec.makeMeasureSpec(296, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(528, View.MeasureSpec.EXACTLY),
                        )
                        view.layout(0, 0, 296, 528)
                        return Bitmap.createBitmap(296, 528, Bitmap.Config.ARGB_8888).also {
                            view.draw(Canvas(it))
                        }
                    }
                    val levels = (0..8).map { render(it) }
                    levels.zipWithNext().forEach { (before, after) ->
                        assertFalse("$gender $side must show each set level", before.sameAs(after))
                    }
                    val secondaryAtCap = render(8, secondary = true)
                    val secondaryAboveCap = render(80, secondary = true)
                    val quarterPrimary = render(2)
                    val abovePrimaryCap = render(80)
                    assertTrue(
                        "Secondary color stops at 25%",
                        secondaryAtCap.sameAs(secondaryAboveCap),
                    )
                    assertTrue(
                        "Secondary cap equals two primary sets",
                        secondaryAtCap.sameAs(quarterPrimary),
                    )
                    assertTrue(
                        "Primary color stops at eight sets",
                        levels.last().sameAs(abovePrimaryCap),
                    )
                    (levels + secondaryAtCap + secondaryAboveCap + quarterPrimary + abovePrimaryCap)
                        .forEach(Bitmap::recycle)
                }
            }
        }
    }
}
