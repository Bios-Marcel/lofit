package lofit

import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.DatePicker
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BodyMeasurementFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var original: ByteArray? = null

    @Before
    fun setup() {
        original = runCatching { AtomicFile(context.repositoryStateFile).readFully() }.getOrNull()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.activeWorkout = null
            data.bodyMetricsEnabled = false
            data.mcpEnabled = false
            data.bodyMeasurements.clear()
            save()
        }
    }

    @After
    fun restore() {
        val file = AtomicFile(context.repositoryStateFile)
        original?.let { bytes -> file.finishWrite(file.startWrite().apply { write(bytes) }) }
            ?: file.delete()
    }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun menuToPairedEntryDatedGraphEditDeleteAndRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                descendants(activity.findViewById(android.R.id.content))
                    .filterIsInstance<DrawerLayout>()
                    .single()
                    .openDrawer(GravityCompat.START, false)
            }
            onView(allOf(withText(R.string.nav_body_measurements), isDisplayed())).perform(click())
            onView(withContentDescription("Front body measurements")).check(matches(isDisplayed()))
            listOf(
                    "Head",
                    "Under chest / bust",
                    "Abdomen",
                    "Wrist",
                    "Hand",
                    "Knee",
                    "Ankle",
                    "Foot",
                )
                .forEach {
                    onView(withContentDescription("Track $it")).check(doesNotExist())
                }
            onView(withContentDescription("Track Upper arm")).perform(scrollTo(), click())
            onView(withText("Save measurement")).perform(scrollTo(), click())
            assertTrue(LofitRepository(context).data.bodyMeasurements.isEmpty())
            onView(withContentDescription("Left Upper arm in centimeters"))
                .perform(scrollTo(), replaceText("32,5"))
            onView(withContentDescription("Right Upper arm in centimeters"))
                .perform(scrollTo(), replaceText("33.5"))
            scenario.recreate()
            onView(withContentDescription("Left Upper arm in centimeters"))
                .check(matches(withText("32,5")))
            onView(withContentDescription("Right Upper arm in centimeters"))
                .check(matches(withText("33.5")))
            val day = LocalDate.now().minusDays(30)
            onView(withContentDescription("Measurement date")).perform(scrollTo(), click())
            onView(isAssignableFrom(DatePicker::class.java))
                .perform(
                    object : ViewAction {
                        override fun getConstraints() = isAssignableFrom(DatePicker::class.java)

                        override fun getDescription() = "Choose an earlier measurement date"

                        override fun perform(uiController: UiController, view: View) {
                            (view as DatePicker).updateDate(
                                day.year,
                                day.monthValue - 1,
                                day.dayOfMonth,
                            )
                        }
                    }
                )
            onView(withText("OK")).perform(click())
            onView(withText("Save measurement")).perform(scrollTo(), click())
            val saved = LofitRepository(context).data.bodyMeasurements
            assertEquals(listOf(32.5, 33.5), saved.map { it.centimeters })
            assertEquals(listOf(Side.LEFT, Side.RIGHT), saved.map { it.side })
            assertTrue(
                saved.all {
                    it.measuredAt ==
                        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }
            )
            onView(withContentDescription("Upper arm measurements graph in centimeters"))
                .perform(scrollTo())
                .check { view, _ ->
                    assertEquals(saved, (view as BodyMeasurementGraphView).measurements)
                }
            scenario.recreate()
            onView(withText("Upper arm")).check(matches(isDisplayed()))
            onView(withContentDescription("Edit measurement ${saved.first().id}"))
                .perform(scrollTo(), click())
            onView(withContentDescription("Edit circumference in centimeters"))
                .perform(replaceText("31"))
            closeSoftKeyboard()
            onView(withText("Save")).perform(click())
            assertEquals(
                31.0,
                LofitRepository(context).data.bodyMeasurements.first().centimeters,
                0.0,
            )
            onView(withContentDescription("Delete measurement ${saved.last().id}"))
                .perform(scrollTo(), click())
            onView(allOf(withText("Delete"), isDisplayed())).perform(click())
            assertEquals(Side.LEFT, LofitRepository(context).data.bodyMeasurements.single().side)
            pressBack()
            onView(withContentDescription("Front body measurements")).check(matches(isDisplayed()))
        }
    }
}
