package lofit

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExerciseRecencyTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.getSharedPreferences("lofit-data", 0)

    @Before fun resetData() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(preferences.edit().clear().commit())
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.exercises.add(Exercise("recent-custom", "Recent curl", ExerciseType.REPETITIONS, false, "Biceps", "", custom = true))
            data.history.add(WorkoutSession(name = "Past", completedAt = 30, exercises = mutableListOf(
                WorkoutExercise(exerciseId = "bench-press", sets = mutableListOf(
                    LoggedSet(type = SetType.NORMAL, weightKg = 20.0, reps = 8, completedAt = 20))),
                WorkoutExercise(exerciseId = "recent-custom", sets = mutableListOf(
                    LoggedSet(type = SetType.NORMAL, weightKg = 10.0, reps = 8, completedAt = 10))),
            )))
            save()
        }
    }

    private fun pickerNames(scenario: ActivityScenario<MainActivity>): List<String> {
        val names = mutableListOf<String>()
        scenario.onActivity { activity ->
            fun visit(view: View) {
                if (view is Button && view.text.startsWith("Add ")) names.add(view.text.removePrefix("Add ").toString())
                if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
            visit(activity.findViewById(android.R.id.content))
        }
        return names
    }

    @Test fun bothSelectorsPrioritizeHistoryAndPersistNewPicksAcrossRestartAndSearch() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            assertEquals(listOf("Bench press", "Recent curl"), pickerNames(scenario).take(2))
            assertEquals(40, pickerNames(scenario).size)

            onView(withContentDescription("Search exercises")).perform(replaceText("Recent curl"), closeSoftKeyboard())
            onView(withText("Add Recent curl")).perform(scrollTo(), click())
            scenario.recreate()
            onView(withText("Add exercise")).perform(scrollTo(), click())
            assertEquals(listOf("Recent curl", "Bench press"), pickerNames(scenario).take(2))
            onView(withContentDescription("Search exercises")).perform(replaceText("Chest"), closeSoftKeyboard())
            assertEquals("Bench press", pickerNames(scenario).first())
            assertFalse(pickerNames(scenario).contains("Recent curl"))
            onView(withContentDescription("Search exercises")).perform(replaceText("no-such-exercise"), closeSoftKeyboard())
            onView(withText("No matching exercises.")).check(matches(isDisplayed()))
            onView(withContentDescription("Search exercises")).perform(replaceText(""), closeSoftKeyboard())
            assertEquals("Recent curl", pickerNames(scenario).first())
            pressBack()

            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
            }
            onView(allOf(withText(R.string.nav_routines), isClickable())).perform(click())
            onView(withText("Create routine")).perform(scrollTo(), click())
            onView(withHint("Routine name")).perform(replaceText("Recent routine"), closeSoftKeyboard())
            onView(withText("Add Exercise")).perform(scrollTo(), click())
            assertEquals(listOf("Recent curl", "Bench press"), pickerNames(scenario).take(2))
            onView(withText("Add Bench press")).perform(scrollTo(), click())
            onView(withText("Save routine")).perform(scrollTo(), click())
            scenario.recreate()
            assertEquals(listOf("bench-press", "recent-custom"), LofitRepository(context).data.exercisesByRecency().take(2).map { it.id })
        }
    }

    @Test fun oldDataUsesHistoryAndResetClearsSelectionRecency() {
        val oldState = JSONObject(preferences.getString("state", null)!!).apply { remove("exerciseLastSelectedAt") }
        assertTrue(preferences.edit().putString("state", oldState.toString()).commit())
        val loaded = LofitRepository(context)
        assertTrue(loaded.data.exerciseLastSelectedAt.isEmpty())
        assertEquals(listOf("bench-press", "recent-custom"), loaded.data.exercisesByRecency().take(2).map { it.id })
        loaded.data.exerciseLastSelectedAt["recent-custom"] = 100
        loaded.save()
        assertEquals(100L, LofitRepository(context).data.exerciseLastSelectedAt["recent-custom"])
        loaded.reset()
        assertTrue(LofitRepository(context).data.exerciseLastSelectedAt.isEmpty())
    }
}
