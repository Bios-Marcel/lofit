package lofit

import android.Manifest
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveWorkoutTitleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepareWorkout() {
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.bodyMetricsEnabled = false
            data.onboardingComplete = true
            val routine = Routine(name = "Original routine")
            data.routines.add(routine)
            data.activeWorkout = data.workoutFromRoutine(routine)
            save()
        }
    }

    @Test
    fun rename_savesOnDoneAndSurvivesRestartWithoutChangingRoutine() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertHeaderLayout(withText("Original routine"))
            onView(withContentDescription("Edit workout title")).perform(click())
            assertHeaderLayout(withContentDescription("Workout title"))
            onView(withContentDescription("Workout title"))
                .perform(replaceText("Evening strength"), pressImeActionButton())
            onView(allOf(withText("Evening strength"), isDisplayed())).check(matches(isDisplayed()))
            assertHeaderLayout(withText("Evening strength"))
            onView(withContentDescription("Edit workout title")).check(matches(isDisplayed()))
            scenario.recreate()
            onView(allOf(withText("Evening strength"), isDisplayed())).check(matches(isDisplayed()))
            assertHeaderLayout(withText("Evening strength"))
            val data = LofitRepository(context).data
            assertEquals("Evening strength", data.activeWorkout!!.name)
            assertEquals("Original routine", data.routines.single().name)
            assertEquals("Original routine", data.activeWorkout!!.sourceRoutine!!.name)
        }
    }

    private fun assertHeaderLayout(title: Matcher<View>) {
        var titleBottom = 0
        var titleRight = 0
        onView(allOf(title, isDisplayed())).check { view, error ->
            if (error != null) throw error
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            titleBottom = position[1] + view.height
            titleRight = position[0] + view.width
        }
        var menuBottom = 0
        onView(withContentDescription("Workout options")).check { view, error ->
            if (error != null) throw error
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            assertTrue("Share menu to the right of title", position[0] > titleRight)
            menuBottom = position[1] + view.height
        }
        var timerBottom = 0
        onView(withContentDescription("Workout timer")).check { view, error ->
            if (error != null) throw error
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            assertTrue("Timer below title and menu", position[1] > maxOf(titleBottom, menuBottom))
            timerBottom = position[1] + view.height
        }
        onView(withContentDescription("Rest duration in seconds")).check { view, error ->
            if (error != null) throw error
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            assertTrue("Timer above rest setting", timerBottom < position[1])
        }
    }

    @Test
    fun rename_savesWhileTypingAndRejectsBlankTitles() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Edit workout title")).perform(click())
            onView(withContentDescription("Workout title"))
                .perform(replaceText("  Morning training  "))
            scenario.recreate()
            onView(allOf(withText("Morning training"), isDisplayed())).check(matches(isDisplayed()))
            onView(withContentDescription("Edit workout title")).perform(click())
            onView(withContentDescription("Workout title"))
                .perform(replaceText("   "), pressImeActionButton())
            onView(allOf(withText("Morning training"), isDisplayed())).check(matches(isDisplayed()))
            assertEquals("Morning training", LofitRepository(context).data.activeWorkout!!.name)
        }
    }
}
