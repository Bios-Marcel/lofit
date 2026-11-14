package lofit

import android.Manifest
import android.util.AtomicFile
import android.view.ViewGroup
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CraneScaleScreensTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val selected = CraneScaleDevice("AA:BB:CC:DD:EE:01", "IF_B7")
    private val other = CraneScaleDevice("AA:BB:CC:DD:EE:02", "WH-C06")
    private var savedState: ByteArray? = null
    private lateinit var activityScanner: FakeScanner
    private val fakeActivityScanner = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.CREATED) {
            // Replace the radio before onStart, including after activity recreation.
            val delegate =
                MainActivity::class
                    .java
                    .getDeclaredField("craneScaleScreens\$delegate")
                    .apply { isAccessible = true }
                    .get(activity) as Lazy<*>
            val screens = delegate.value as CraneScaleScreens
            activityScanner = FakeScanner()
            CraneScaleScreens::class
                .java
                .getDeclaredField("scanner")
                .apply { isAccessible = true }
                .set(screens, activityScanner)
            val withBluetooth: (() -> Unit) -> Unit = { it() }
            CraneScaleScreens::class
                .java
                .getDeclaredField("withBluetooth")
                .apply { isAccessible = true }
                .set(screens, withBluetooth)
        }
    }

    @Before
    fun setup() {
        instrumentation.runOnMainSync { WorkoutVoiceService.stop() }
        instrumentation.waitForIdleSync()
        listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
            .forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
        savedState = runCatching { AtomicFile(context.repositoryStateFile).readFully() }.getOrNull()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.mcpEnabled = false
            data.craneScale = null
            data.activeWorkout = null
            save()
        }
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(fakeActivityScanner)
        }
    }

    @After
    fun restore() {
        instrumentation.runOnMainSync { WorkoutVoiceService.stop() }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance()
                .removeLifecycleCallback(fakeActivityScanner)
        }
        val file = AtomicFile(context.repositoryStateFile)
        val bytes = savedState
        if (bytes == null) file.delete()
        else file.finishWrite(file.startWrite().apply { write(bytes) })
    }

    private class FakeScanner : CraneScaleScanner {
        var address: String? = null
        var running = false
        var starts = 0
        lateinit var reading: (CraneScaleDevice, Double) -> Unit
        lateinit var error: (String) -> Unit

        override fun start(
            address: String?,
            onReading: (CraneScaleDevice, Double) -> Unit,
            onError: (String) -> Unit,
        ) {
            this.address = address
            reading = onReading
            error = onError
            running = true
            starts++
        }

        override fun stop() {
            running = false
        }
    }

    @Test
    fun selectedScaleControlsLiveMenuAcrossRecreationAndForgetting() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openDrawer(scenario)
            onView(withText(R.string.nav_live_measurement)).check(matches(not(isDisplayed())))
            pressBack()
            navigate(scenario, R.string.nav_settings)
            onView(withText("Select scale")).perform(scrollTo(), click())
            scenario.onActivity {
                assertNull(activityScanner.address)
                activityScanner.reading(selected, 5.0)
                activityScanner.reading(selected, 6.0)
                activityScanner.reading(other, 7.0)
            }
            onView(withText("${other.name}\n${other.address}"))
                .perform(waitForStableLayout(), click())
            assertFalse(activityScanner.running)
            assertEquals(other, LofitRepository(context).data.craneScale)
            scenario.recreate()
            navigate(scenario, R.string.nav_settings)
            onView(withText(other.address)).perform(scrollTo()).check(matches(isDisplayed()))
            navigate(scenario, R.string.nav_live_measurement)
            onView(withContentDescription("Live weight graph")).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withContentDescription("Live weight graph")).check(matches(isDisplayed()))
            navigate(scenario, R.string.nav_settings)
            onView(withText("Forget scale")).perform(scrollTo(), click())
            assertNull(LofitRepository(context).data.craneScale)
            openDrawer(scenario)
            onView(withText(R.string.nav_live_measurement)).check(matches(not(isDisplayed())))
            scenario.recreate()
            openDrawer(scenario)
            onView(withText(R.string.nav_live_measurement)).check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun liveUsesOnlySavedScaleUpdatesRepeatedReadingsAndNeverWritesMeasurements() {
        val scanner = FakeScanner()
        val repository =
            LofitRepository(context).apply {
                data.craneScale = selected
                save()
            }
        val before = context.repositoryStateFile.readBytes()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var screens: CraneScaleScreens
            scenario.onActivity { activity ->
                val ui = LofitViews(activity, ExercisePictures(activity)) { BiologicalGender.MALE }
                screens =
                    CraneScaleScreens(
                        activity,
                        ui,
                        { repository.data },
                        repository::save,
                        { it() },
                        {},
                        scanner,
                    )
                activity.setContentView(screens.liveScreen())
                screens.onStart()
                assertEquals(selected.address, scanner.address)
                scanner.reading(selected, 12.34)
            }
            onView(withContentDescription("Live scale weight"))
                .check(matches(withText(formatBodyWeight(12.34))))
            scenario.onActivity {
                scanner.reading(selected, 13.5)
                scanner.reading(other, 99.0)
            }
            onView(withContentDescription("Live scale weight"))
                .check(matches(withText(formatBodyWeight(13.5))))
            onView(withContentDescription("Scale status")).check(matches(not(isDisplayed())))
            onView(withContentDescription("Live weight graph")).check { view, _ ->
                val graph = view as LiveMeasurementGraphView
                assertEquals(listOf(12.34, 13.5), graph.series.samples.map { it.kilograms })
                val bitmap =
                    android.graphics.Bitmap.createBitmap(
                        graph.width,
                        graph.height,
                        android.graphics.Bitmap.Config.ARGB_8888,
                    )
                graph.draw(android.graphics.Canvas(bitmap))
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                val coral = androidx.core.content.ContextCompat.getColor(context, R.color.coral)
                assertTrue("Graph must draw its weight line", pixels.any { it == coral })
                bitmap.recycle()
            }
            scenario.onActivity {
                val oldReading = scanner.reading
                screens.onStop()
                assertFalse(scanner.running)
                oldReading(selected, 100.0)
            }
            onView(withContentDescription("Live scale weight")).check(matches(withText("—")))
            scenario.onActivity {
                screens.onStart()
                assertTrue(scanner.running)
                scanner.reading(selected, 14.0)
            }
            onView(withContentDescription("Live scale weight"))
                .check(matches(withText(formatBodyWeight(14.0))))
            assertArrayEquals(before, context.repositoryStateFile.readBytes())
            scenario.onActivity { screens.clear() }
            assertFalse(scanner.running)
        }
    }

    @Test
    fun scanFailureCanRetryAndLateCallbacksCannotReplaceNewReadings() {
        val scanner = FakeScanner()
        val repository = LofitRepository(context).apply { data.craneScale = selected }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var screens: CraneScaleScreens
            lateinit var oldReading: (CraneScaleDevice, Double) -> Unit
            scenario.onActivity { activity ->
                val ui = LofitViews(activity, ExercisePictures(activity)) { BiologicalGender.MALE }
                screens =
                    CraneScaleScreens(activity, ui, { repository.data }, {}, { it() }, {}, scanner)
                activity.setContentView(screens.liveScreen())
                screens.onStart()
                oldReading = scanner.reading
                scanner.error("Bluetooth is off")
            }
            onView(withContentDescription("Scale status"))
                .check(matches(withText("Bluetooth is off")))
            onView(withText("Retry")).perform(click())
            scenario.onActivity {
                assertEquals(2, scanner.starts)
                scanner.reading(selected, 20.0)
                oldReading(selected, 100.0)
            }
            onView(withContentDescription("Live scale weight"))
                .check(matches(withText(formatBodyWeight(20.0))))
            scenario.onActivity { screens.onStop() }
        }
    }

    @Test
    fun signalLossClearsOldWeightAndNextAdvertisementRestoresIt() {
        val scanner = FakeScanner()
        val repository = LofitRepository(context).apply { data.craneScale = selected }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var screens: CraneScaleScreens
            scenario.onActivity { activity ->
                val ui = LofitViews(activity, ExercisePictures(activity)) { BiologicalGender.MALE }
                screens =
                    CraneScaleScreens(
                        activity,
                        ui,
                        { repository.data },
                        {},
                        { it() },
                        {},
                        scanner,
                        staleMillis = 50,
                    )
                activity.setContentView(screens.liveScreen())
                screens.onStart()
                scanner.reading(selected, 25.0)
            }
            onView(withContentDescription("Scale status"))
                .perform(waitUntilMatches(withText("No signal · waiting for scale…")))
            onView(withContentDescription("Live scale weight")).check(matches(withText("—")))
            onView(withContentDescription("Scale status"))
                .check(matches(withText("No signal · waiting for scale…")))
            scenario.onActivity {
                assertTrue(scanner.running)
                scanner.reading(selected, 26.0)
                assertEquals("", activityText(it, "Scale status"))
                assertEquals(formatBodyWeight(26.0), activityText(it, "Live scale weight"))
                screens.onStop()
            }
        }
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, label: Int) {
        openDrawer(scenario)
        onView(allOf(withText(label), isClickable())).perform(click())
    }

    private fun openDrawer(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity {
            val root = it.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
    }

    private fun activityText(activity: MainActivity, description: String): String {
        fun find(view: android.view.View): android.widget.TextView? {
            if (view is android.widget.TextView && view.contentDescription == description)
                return view
            if (view is ViewGroup)
                for (index in 0 until view.childCount) {
                    find(view.getChildAt(index))?.let {
                        return it
                    }
                }
            return null
        }
        return find(activity.findViewById(android.R.id.content))!!.text.toString()
    }
}
