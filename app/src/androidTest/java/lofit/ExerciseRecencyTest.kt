package lofit

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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

    @Before fun resetData() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        context.clearRepositoryTestData()
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
                if (view is Button && view.text == "Add") names.add(view.contentDescription.toString().removePrefix("Add "))
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
            onView(withContentDescription("Add Recent curl")).perform(scrollTo(), click())
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
            onView(withContentDescription("Add Bench press")).perform(scrollTo(), click())
            onView(withText("Save routine")).perform(scrollTo(), click())
            scenario.recreate()
            assertEquals(listOf("bench-press", "recent-custom"), LofitRepository(context).data.exercisesByRecency().take(2).map { it.id })
        }
    }

    @Test fun workoutMultiSelectSurvivesSearchAndAddsOnlyCheckedExercises() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withContentDescription("Add selected exercises")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withContentDescription("Search exercises")).perform(replaceText("Bench press"), closeSoftKeyboard())
            onView(withContentDescription("Add Bench press")).check(matches(withText("Add")))
            onView(withContentDescription("Select Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Add selected exercises")).check(matches(isEnabled()))
            onView(withContentDescription("Select Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Add selected exercises")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withContentDescription("Select Bench press")).perform(scrollTo(), click())
            assertTrue(LofitRepository(context).data.activeWorkout!!.exercises.isEmpty())

            onView(withContentDescription("Search exercises")).perform(replaceText("Recent curl"), closeSoftKeyboard())
            onView(withContentDescription("Select Recent curl")).perform(scrollTo(), click())
            onView(withContentDescription("Search exercises")).perform(replaceText("Bench press"), closeSoftKeyboard())
            onView(withContentDescription("Select Bench press")).check(matches(isChecked()))
            onView(withContentDescription("Select Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Add selected exercises")).check(matches(withText("Add selected (1)")))
            onView(withContentDescription("Select Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Search exercises")).perform(replaceText("no-such-exercise"), closeSoftKeyboard())
            onView(withContentDescription("Add selected exercises")).perform(click())

            assertEquals(listOf("recent-custom", "bench-press"),
                LofitRepository(context).data.activeWorkout!!.exercises.map { exercise -> exercise.exerciseId })
            assertTrue(LofitRepository(context).data.exerciseLastSelectedAt.keys.containsAll(listOf("bench-press", "recent-custom")))
            onView(withContentDescription("Search exercises")).check(androidx.test.espresso.assertion.ViewAssertions.doesNotExist())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withContentDescription("Add selected exercises")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            pressBack()
        }
    }

    @Test fun pickerHeaderAndAddSelectedStayVisibleWhileScrollingAndTyping() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withContentDescription("Select Bench press")).perform(scrollTo(), click())
            fun buttonTop(): Int {
                var top = 0
                onView(withContentDescription("Add selected exercises")).check(matches(isCompletelyDisplayed()))
                onView(withContentDescription("Add selected exercises")).check { view, _ ->
                    top = IntArray(2).also { view.getLocationOnScreen(it) }[1]
                }
                return top
            }
            fun headerPositions(): List<Int> = listOf(
                withText("Add exercise"),
                withContentDescription("Search exercises"),
                withContentDescription("Exercise results"),
            ).map { matcher ->
                var top = 0
                onView(matcher).check(matches(isCompletelyDisplayed()))
                onView(matcher).check { view, _ ->
                    top = IntArray(2).also { view.getLocationOnScreen(it) }[1]
                }
                top
            }
            val headerTop = headerPositions()
            val top = buttonTop()
            onView(isAssignableFrom(ScrollView::class.java)).perform(object : androidx.test.espresso.ViewAction {
                override fun getDescription() = "scroll exercise list to bottom"
                override fun getConstraints() = isAssignableFrom(ScrollView::class.java)
                override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
                    val scroll = view as ScrollView
                    scroll.scrollTo(0, scroll.getChildAt(0).height)
                    uiController.loopMainThreadUntilIdle()
                }
            })
            assertEquals(top, buttonTop())
            assertEquals(headerTop, headerPositions())

            onView(withContentDescription("Search exercises")).perform(typeText("Recent curl"))
            val deadline = android.os.SystemClock.uptimeMillis() + 5_000
            var keyboardVisible = false
            while (!keyboardVisible && android.os.SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    keyboardVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                if (!keyboardVisible) android.os.SystemClock.sleep(50)
            }
            assertTrue("Search keyboard opens", keyboardVisible)
            onView(withContentDescription("Add selected exercises")).check(matches(isCompletelyDisplayed()))
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                val keyboard = ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val buttons = arrayListOf<View>()
                decor.findViewsWithText(buttons, "Add selected exercises", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                val button = buttons.single()
                val location = IntArray(2).also { button.getLocationOnScreen(it) }
                assertTrue("Button stays above keyboard", location[1] + button.height <= decor.height - keyboard)
            }
            onView(withContentDescription("Add selected exercises")).perform(click())
            assertEquals(listOf("bench-press"),
                LofitRepository(context).data.activeWorkout!!.exercises.map { exercise -> exercise.exerciseId })
        }
    }

    @Test fun singleAddIgnoresCheckedExercisesAndBackCancelsSelection() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withContentDescription("Search exercises")).perform(replaceText("Bench press"), closeSoftKeyboard())
            onView(withContentDescription("Select Bench press")).perform(scrollTo(), click())
            pressBack()
            assertTrue(LofitRepository(context).data.activeWorkout!!.exercises.isEmpty())
            assertTrue(LofitRepository(context).data.exerciseLastSelectedAt.isEmpty())

            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withContentDescription("Add selected exercises")).check(matches(org.hamcrest.Matchers.not(isEnabled())))
            onView(withContentDescription("Search exercises")).perform(replaceText("Bench press"), closeSoftKeyboard())
            onView(withContentDescription("Select Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Search exercises")).perform(replaceText("Recent curl"), closeSoftKeyboard())
            onView(withContentDescription("Add Recent curl")).perform(scrollTo(), click())
            assertEquals(listOf("recent-custom"),
                LofitRepository(context).data.activeWorkout!!.exercises.map { exercise -> exercise.exerciseId })
        }
    }

    @Test fun routineMultiSelectSavesExercisesWithDefaultSets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
            }
            onView(allOf(withText(R.string.nav_routines), isClickable())).perform(click())
            onView(withText("Create routine")).perform(scrollTo(), click())
            onView(withHint("Routine name")).perform(replaceText("Multi routine"), closeSoftKeyboard())
            onView(withText("Add Exercise")).perform(scrollTo(), click())
            onView(withContentDescription("Search exercises")).perform(replaceText("Bench press"), closeSoftKeyboard())
            onView(withContentDescription("Select Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Search exercises")).perform(replaceText("Plank"), closeSoftKeyboard())
            onView(withContentDescription("Select Plank")).perform(scrollTo(), click())
            onView(withContentDescription("Add selected exercises")).perform(click())
            onView(withText("Save routine")).perform(scrollTo(), click())

            val exercises = LofitRepository(context).data.routines.single().exercises
            assertEquals(listOf("bench-press", "plank"), exercises.map { exercise -> exercise.exerciseId })
            assertTrue(exercises.all { exercise -> exercise.plannedSets.size == 3 })
            assertTrue(exercises.last().plannedSets.all { set -> set.holdSeconds == 45 })
        }
    }

    @Test fun oldDataUsesHistoryAndResetClearsSelectionRecency() {
        val oldState = JSONObject(context.repositoryStateFile.readText()).apply { remove("exerciseLastSelectedAt") }
        context.repositoryStateFile.writeText(oldState.toString())
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
