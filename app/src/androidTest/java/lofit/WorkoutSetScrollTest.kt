package lofit

import android.Manifest
import android.app.NotificationManager
import android.graphics.Rect
import android.view.View
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutSetScrollTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setup() {
        context.clearRepositoryTestData()
        listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
    }

    private fun seed(timed: Boolean = false, rest: Int = 0, count: Int = 20) {
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.voiceCommandsEnabled = true
            val movement = data.exercises.first { it.id == if (timed) "plank" else "bench-press" }
            val sets = MutableList(count) { PlannedSet(holdSeconds = 1) }
            data.activeWorkout =
                WorkoutSession(
                    name = "Scroll workout",
                    defaultRestSeconds = rest,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = movement.id,
                                plannedSets = sets,
                                sets =
                                    sets
                                        .take(9)
                                        .map { loggedSetFrom(it, movement) }
                                        .toMutableList(),
                            )
                        ),
                )
            save()
        }
    }

    private fun scroll(view: View): ScrollView {
        var parent = view.parent
        while (parent !is ScrollView) parent = parent.parent
        return parent
    }

    /** Put row 10 near the bottom, with the specified fraction of row 11 visible. */
    private fun position(followingRowsVisible: Float = 0.5f) =
        object : ViewAction {
            override fun getConstraints() = isAssignableFrom(View::class.java)

            override fun getDescription() = "position completed row at viewport bottom"

            override fun perform(uiController: UiController, view: View) {
                val scroll = scroll(view)
                val bounds = Rect()
                view.getDrawingRect(bounds)
                scroll.offsetDescendantRectToMyCoords(view, bounds)
                scroll.scrollTo(
                    0,
                    bounds.bottom - scroll.height + (view.height * followingRowsVisible).toInt(),
                )
                uiController.loopMainThreadUntilIdle()
            }
        }

    private fun row(index: Int) = onView(withContentDescription("Set $index row"))

    private fun done(index: Int) = onView(withContentDescription("Set $index done"))

    private fun assertNextVisible() {
        row(11).perform(waitUntilMatches(isCompletelyDisplayed()))
        row(11).check(matches(isCompletelyDisplayed()))
    }

    @Test
    fun manualCompletion_revealsPartlyVisibleNextRow() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            row(10).perform(position())
            row(11).check(matches(not(isCompletelyDisplayed())))
            done(10).perform(click())
            assertNextVisible()
        }
    }

    @Test
    fun manualCompletion_accountsForRestBar() {
        seed(rest = 60)
        ActivityScenario.launch(MainActivity::class.java).use {
            row(10).perform(position(1f))
            row(11).check(matches(isCompletelyDisplayed()))
            done(10).perform(click())
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            assertNextVisible()
        }
    }

    @Test
    fun completion_keepsScrollWhenNextRowFullyVisible() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            row(10).perform(position(2f))
            var before = 0
            row(10).check { view, _ -> before = scroll(view).scrollY }
            done(10).perform(click())
            row(10).perform(waitForStableLayout()).check { view, _ ->
                assertEquals(before, scroll(view).scrollY)
            }
            assertNextVisible()
        }
    }

    @Test
    fun unchecking_doesNotScroll() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            row(9).perform(position())
            var before = 0
            row(9).check { view, _ -> before = scroll(view).scrollY }
            done(9).perform(click())
            row(9).perform(waitForStableLayout()).check { view, _ ->
                assertEquals(before, scroll(view).scrollY)
            }
        }
    }

    @Test
    fun lastSet_doesNotScroll() {
        seed(count = 10)
        ActivityScenario.launch(MainActivity::class.java).use {
            row(10).perform(position(0f))
            var before = 0
            row(10).check { view, _ -> before = scroll(view).scrollY }
            done(10).perform(click())
            row(10).perform(waitForStableLayout()).check { view, _ ->
                assertEquals(before, scroll(view).scrollY)
            }
        }
    }

    @Test
    fun voiceCompletion_revealsHiddenNextRow() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            row(10).perform(position(0f), waitForStableLayout())
            scenario.onActivity {
                it.onWorkoutVoiceCommand(VoiceCommand.DONE, it.visibleWorkoutVoiceTarget()!!)
            }
            done(10).check(matches(isChecked()))
            assertNextVisible()
        }
    }

    @Test
    fun completion_revealsFirstRowOfNextExercise() {
        seed(count = 10)
        val next =
            WorkoutExercise(exerciseId = "plank", plannedSets = MutableList(3) { PlannedSet() })
        LofitRepository(context).apply {
            data.activeWorkout!!.exercises.add(next)
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            row(10).perform(position(0f))
            done(10).perform(click())
            onView(
                    allOf(
                        withContentDescription("Set 1 row"),
                        isDescendantOfA(withTagValue(equalTo(next.id))),
                    )
                )
                .perform(waitUntilMatches(isCompletelyDisplayed()))
                .check(matches(isCompletelyDisplayed()))
        }
    }

    @Test
    fun notificationCompletion_revealsNextRowAfterRender() {
        seed(rest = 60)
        ActivityScenario.launch(MainActivity::class.java).use {
            row(10).perform(position(), waitForStableLayout())
            context
                .getSystemService(NotificationManager::class.java)
                .activeNotifications
                .single { it.id == 1 }
                .notification
                .actions
                .single { it.title.toString() == "Complete Set" }
                .actionIntent
                .send()
            // Completion rebuilds the view tree; wait on the next row in the new tree.
            instrumentation.waitForIdleSync()
            done(10).check(matches(isChecked()))
            assertNextVisible()
        }
    }

    @Test
    fun timerCompletion_revealsNextRow() {
        seed(timed = true, rest = 60)
        ActivityScenario.launch(MainActivity::class.java).use {
            row(10).perform(position())
            onView(withContentDescription("Start set 10 hold")).perform(click())
            done(10).perform(waitUntilMatches(isChecked()))
            assertNextVisible()
        }
    }
}
