package lofit

import android.net.wifi.WifiManager
import android.view.ViewGroup
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.card.MaterialCardView
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryWorkoutStartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun prepare() {
        instrumentation.grantWorkoutStartPermissions()
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.history.add(
                WorkoutSession(
                    name = "Push day",
                    startedAt = 100,
                    completedAt = 200,
                    defaultRestSeconds = 90,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                restSecondsOverride = 75,
                                sets =
                                    mutableListOf(
                                        LoggedSet(type = SetType.NORMAL, weightKg = 67.5, reps = 6)
                                    ),
                            ),
                            WorkoutExercise(
                                exerciseId = "plank",
                                plannedSets = mutableListOf(PlannedSet(holdSeconds = 45)),
                            ),
                        ),
                )
            )
            saveHistory()
            save()
        }
    }

    private fun openHistory(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        onView(allOf(withText(R.string.nav_history), isClickable())).perform(click())
    }

    private fun benchField(description: String) =
        allOf(
            withContentDescription(description),
            isDescendantOfA(
                allOf(
                    isAssignableFrom(MaterialCardView::class.java),
                    hasDescendant(withText("Bench press")),
                )
            ),
        )

    private fun startFreshWorkout(fromDetails: Boolean) {
        val historyBefore = context.repositoryHistoryFile.readText()
        val source = LofitRepository(context).data.history.single()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openHistory(scenario)
            if (fromDetails)
                onView(withContentDescription("View Push day")).perform(scrollTo(), click())
            onView(withContentDescription("Options for Push day")).perform(scrollTo(), click())
            var deriveBottom = 0
            onView(withText("Derive Routine")).check { view, _ ->
                deriveBottom = IntArray(2).also(view::getLocationOnScreen)[1] + view.height
            }
            onView(withText("Start new workout"))
                .check { view, _ ->
                    assertTrue(IntArray(2).also(view::getLocationOnScreen)[1] >= deriveBottom)
                }
                .perform(click())

            onView(withContentDescription("Workout timer")).check(matches(isDisplayed()))
            onView(benchField("Set 1 done")).check(matches(not(isChecked())))
            onView(benchField("Set 1 weight")).check(matches(withText("67.5")))
            var saved = LofitRepository(context).data
            val fresh = saved.activeWorkout!!
            assertNotEquals(source.id, fresh.id)
            assertEquals("Push day", fresh.name)
            assertTrue(fresh.startedAt > source.completedAt!!)
            assertNull(fresh.completedAt)
            assertNull(fresh.sourceRoutine)
            assertEquals(0, fresh.setCount())
            assertEquals(90, fresh.defaultRestSeconds)
            assertEquals(listOf("bench-press", "plank"), fresh.exercises.map { it.exerciseId })
            assertEquals(75, fresh.exercises.first().restSecondsOverride)
            assertEquals(45, fresh.exercises.last().plannedSets.single().holdSeconds)
            assertTrue(saved.routines.isEmpty())

            onView(benchField("Set 1 weight")).perform(replaceText("70"))
            scenario.recreate()
            onView(benchField("Set 1 weight")).check(matches(withText("70")))
            onView(benchField("Set 1 done")).check(matches(not(isChecked())))
            saved = LofitRepository(context).data
            assertEquals(fresh.id, saved.activeWorkout!!.id)
            assertTrue(saved.routines.isEmpty())
            assertEquals(listOf(source.id), saved.history.map { it.id })
            assertEquals(historyBefore, context.repositoryHistoryFile.readText())
        }
    }

    @Test fun historyMenuStartsFreshWorkout() = startFreshWorkout(fromDetails = false)

    @Test fun historyDetailMenuStartsFreshWorkout() = startFreshWorkout(fromDetails = true)

    @Test
    @Suppress("DEPRECATION")
    fun debugHistoryWifiStartsSuggestedWorkoutWithoutSavedCoordinates() {
        LofitRepository(context).apply {
            data.debugMode = true
            val sets = data.history.single().exercises.first().sets
            sets[0] = sets.single().copy(completedAt = 100)
            saveHistory()
            save()
        }
        lateinit var wifiName: String
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val ssid = context.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
            assumeTrue(
                "Requires a connected Wi-Fi network",
                !ssid.isNullOrEmpty() && ssid != WifiManager.UNKNOWN_SSID,
            )
            wifiName = ssid!!.removeSurrounding("\"")
            openHistory(scenario)
            onView(withContentDescription("Options for Push day")).perform(scrollTo(), click())
            onView(withText("Set current Wi-Fi and location")).perform(click())
            assertEquals(wifiName, LofitRepository(context).data.history.single().startWifiName)
        }
        LofitRepository(context).apply {
            data.history.single().startLocation = null
            saveHistory()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("${context.getString(R.string.start_workout)} options"))
                .perform(scrollTo(), click())
            onView(withText("Start suggested workout")).perform(click())
            onView(withContentDescription("Workout timer")).check(matches(isDisplayed()))
            val active = LofitRepository(context).data.activeWorkout!!
            assertEquals(wifiName, active.startWifiName)
            assertEquals(listOf("bench-press"), active.exercises.map { it.exerciseId })
        }
    }

    @Test
    fun startingFromHistoryKeepsAnExistingActiveWorkout() {
        val active = WorkoutSession(name = "In progress")
        LofitRepository(context).apply {
            data.activeWorkout = active
            save()
        }
        val historyBefore = context.repositoryHistoryFile.readText()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openHistory(scenario)
            onView(withContentDescription("Options for Push day")).perform(scrollTo(), click())
            onView(withText("Start new workout")).perform(click())

            onView(withContentDescription("Workout timer")).check(matches(isDisplayed()))
            val saved = LofitRepository(context).data
            assertEquals(active, saved.activeWorkout)
            assertTrue(saved.routines.isEmpty())
            assertEquals(historyBefore, context.repositoryHistoryFile.readText())
        }
    }
}
