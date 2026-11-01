package lofit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LazyMuscleMapsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun workload(muscle: String) =
        MuscleWorkload(
            listOf(
                Exercise(
                    id = muscle,
                    name = muscle,
                    type = ExerciseType.REPETITIONS,
                    asymmetric = false,
                    primaryMuscle = muscle,
                    secondaryMuscles = "",
                ) to 8
            )
        )

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(16)
        }
        throw AssertionError("Lazy muscle maps did not settle")
    }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    private fun frontBitmap(pager: MuscleTargetPager): Bitmap {
        val map = descendants(pager).single { it.contentDescription == "Front muscle map" }
        return Bitmap.createBitmap(map.width, map.height, Bitmap.Config.ARGB_8888).also {
            map.draw(Canvas(it))
        }
    }

    @Test
    fun scrollingReusesPagerAndRebindsMusclesWhilePreservingEachCardsPageAndHeight() {
        BiologicalGender.entries.forEach { gender ->
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var scroll: ScrollView
                lateinit var body: LinearLayout
                lateinit var first: FrameLayout
                lateinit var last: FrameLayout
                val loads = IntArray(30)
                lateinit var slots: List<FrameLayout>
                scenario.onActivity { activity ->
                    val content = activity.findViewById<ViewGroup>(android.R.id.content)
                    content.removeAllViews()
                    val density = activity.resources.displayMetrics.density
                    val maps = LazyMuscleMaps(activity, gender)
                    body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                    slots =
                        List(loads.size) { index ->
                            (maps.slot {
                                    loads[index]++
                                    workload(if (index == 0) "Chest" else "Quadriceps")
                                } as FrameLayout)
                                .also {
                                    body.addView(it)
                                    if (index < loads.lastIndex) {
                                        body.addView(
                                            View(activity),
                                            LinearLayout.LayoutParams(1, (600 * density).toInt()),
                                        )
                                    }
                                }
                        }
                    first = slots.first()
                    last = slots.last()
                    scroll = ScrollView(activity).apply { addView(body) }
                    maps.attach(scroll)
                    content.addView(
                        scroll,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            (400 * density).toInt(),
                        ),
                    )
                    assertTrue("No workloads before layout", loads.all { it == 0 })
                }
                await { first.childCount == 1 && first.getChildAt(0).width > 0 }
                lateinit var pager: MuscleTargetPager
                lateinit var firstImage: Bitmap
                var bodyHeight = 0
                var slotHeight = 0
                scenario.onActivity {
                    assertEquals(1, loads[0])
                    assertTrue("Offscreen workloads stay deferred", loads.drop(1).all { it == 0 })
                    pager = first.getChildAt(0) as MuscleTargetPager
                    assertTrue(
                        descendants(pager).filterIsInstance<MuscleMapView>().all {
                            it.biologicalGender == gender
                        }
                    )
                    assertTrue(
                        descendants(pager).any {
                            it.stateDescription?.contains("Chest 100%") == true
                        }
                    )
                    firstImage = frontBitmap(pager)
                    pager.currentPage = 1
                    bodyHeight = body.height
                    slotHeight = first.height
                    scroll.scrollTo(0, last.top)
                }
                await {
                    first.childCount == 0 && last.childCount == 1 && last.getChildAt(0).width > 0
                }
                scenario.onActivity {
                    assertSame("Live pager is recycled", pager, last.getChildAt(0))
                    assertEquals("New card starts on its own page", 0, pager.currentPage)
                    assertEquals(1, loads.last())
                    assertTrue(
                        "Jumped-over cards never load",
                        loads.drop(1).dropLast(1).all { it == 0 },
                    )
                    val graph =
                        descendants(pager).single {
                            it.contentDescription == "Muscle targeting graph"
                        }
                    assertTrue(graph.stateDescription.toString().contains("Legs 100%"))
                    assertTrue(graph.stateDescription.toString().contains("Chest 0%"))
                    assertFalse(
                        descendants(pager).filterIsInstance<MuscleMapView>().any {
                            it.stateDescription.toString().contains("Chest")
                        }
                    )
                    frontBitmap(pager).also {
                        assertFalse("Recycled map must repaint its muscles", firstImage.sameAs(it))
                        it.recycle()
                    }
                    assertEquals(bodyHeight, body.height)
                    assertEquals(slotHeight, last.height)
                    scroll.scrollTo(0, 0)
                }
                await {
                    first.childCount == 1 && last.childCount == 0 && first.getChildAt(0).width > 0
                }
                scenario.onActivity {
                    assertSame(pager, first.getChildAt(0))
                    assertEquals("Returning card keeps its page", 1, pager.currentPage)
                    assertEquals("Workload is cached per card", 1, loads[0])
                    val graph =
                        descendants(pager).single {
                            it.contentDescription == "Muscle targeting graph"
                        }
                    assertTrue(graph.stateDescription.toString().contains("Chest 100%"))
                    frontBitmap(pager).also {
                        assertTrue(
                            "Returning map restores its original shading",
                            firstImage.sameAs(it),
                        )
                        it.recycle()
                    }
                    firstImage.recycle()
                    assertEquals(bodyHeight, body.height)
                    assertEquals(slotHeight, first.height)
                    activityContent(it).removeAllViews()
                }
                await { slots.all { it.childCount == 0 } }
            }
        }
    }

    private fun activityContent(activity: MainActivity) =
        activity.findViewById<ViewGroup>(android.R.id.content)

    @Test
    fun neutralPlaceholderBitmapIsSharedAndMatchesPagerHeightForBothModelsAndLargeText() {
        instrumentation.runOnMainSync {
            val base = instrumentation.targetContext
            for (scale in listOf(1f, 1.5f)) {
                val configuration =
                    android.content.res.Configuration(base.resources.configuration).apply {
                        fontScale = scale
                    }
                val context =
                    androidx.appcompat.view.ContextThemeWrapper(
                        base.createConfigurationContext(configuration),
                        R.style.Theme_Lofit,
                    )
                val width = (360 * context.resources.displayMetrics.density).toInt()
                val images =
                    BiologicalGender.entries.map { gender ->
                        val placeholder = MuscleMapPlaceholder(context, gender)
                        val first = placeholder.bitmap(width)
                        assertSame(
                            "Cards share the same neutral bitmap",
                            first,
                            placeholder.bitmap(width),
                        )
                        assertNotSame(
                            "Resizing gets a correctly sized bitmap",
                            first,
                            placeholder.bitmap(width / 2),
                        )
                        val pager = MuscleTargetPager(context, workload("Chest"), gender)
                        pager.measure(
                            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        )
                        assertEquals(
                            "Placeholder must not move following cards",
                            pager.measuredHeight,
                            first.height,
                        )
                        first
                    }
                assertFalse("Placeholder honors the body model", images[0].sameAs(images[1]))
            }
        }
    }

    @Test
    fun detachingBeforeFirstFrameDoesNotLoadWorkloads() {
        var loads = 0
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = activityContent(activity)
                content.removeAllViews()
                val maps = LazyMuscleMaps(activity, BiologicalGender.MALE)
                val scroll =
                    ScrollView(activity).apply {
                        addView(
                            maps.slot {
                                loads++
                                workload("Chest")
                            }
                        )
                    }
                maps.attach(scroll)
                content.addView(scroll)
                content.removeView(scroll)
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, loads)
        }
    }
}
