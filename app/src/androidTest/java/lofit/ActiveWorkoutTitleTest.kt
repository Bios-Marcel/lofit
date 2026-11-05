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
            assertTimerAligned(withText("Original routine"))
            onView(withContentDescription("Edit workout title")).perform(click())
            assertTimerAligned(withContentDescription("Workout title"))
            onView(withContentDescription("Workout title"))
                .perform(replaceText("Evening strength"), pressImeActionButton())
            onView(allOf(withText("Evening strength"), isDisplayed())).check(matches(isDisplayed()))
            assertTimerAligned(withText("Evening strength"))
            onView(withContentDescription("Edit workout title")).check(matches(isDisplayed()))
            scenario.recreate()
            onView(allOf(withText("Evening strength"), isDisplayed())).check(matches(isDisplayed()))
            assertTimerAligned(withText("Evening strength"))
            val data = LofitRepository(context).data
            assertEquals("Evening strength", data.activeWorkout!!.name)
            assertEquals("Original routine", data.routines.single().name)
            assertEquals("Original routine", data.activeWorkout!!.sourceRoutine!!.name)
        }
    }

    private fun assertTimerAligned(title: Matcher<View>) {
        var titleBaseline = 0
        onView(allOf(title, isDisplayed())).check { view, error ->
            if (error != null) throw error
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            titleBaseline = position[1] + view.baseline
        }
        onView(withContentDescription("Workout timer")).check { view, error ->
            if (error != null) throw error
            val position = IntArray(2)
            view.getLocationOnScreen(position)
            assertEquals("Title and timer baselines", titleBaseline, position[1] + view.baseline)
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
