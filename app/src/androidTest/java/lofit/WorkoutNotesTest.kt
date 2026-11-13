package lofit

import android.Manifest
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutNotesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val note = "Felt strong today\nRest more next time"

    @Before
    fun prepare() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.activeWorkout =
                WorkoutSession(
                    name = "Strength",
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                sets =
                                    mutableListOf(
                                        LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 8)
                                    ),
                            )
                        ),
                )
            save()
        }
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, label: Int) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        onView(allOf(withText(label), isClickable())).perform(click())
    }

    @Test
    fun finishSavesMultilineNoteInHistoryAndKeepsItWhenContinued() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note"))
                .check { view, _ ->
                    val field = view as EditText
                    assertFalse(field.isSingleLine)
                    assertTrue(field.minLines >= 3)
                    assertTrue(field.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
                }
                .perform(scrollTo(), replaceText(note))
            onView(withText("Finish and save")).perform(scrollTo(), click())
            assertEquals(note, LofitRepository(context).data.history.single().note)
            assertNull(LofitRepository(context).data.activeWorkout)

            scenario.recreate()
            navigate(scenario, R.string.nav_history)
            onView(withContentDescription("Workout note")).check(doesNotExist())
            onView(withText(note)).check(doesNotExist())
            onView(withContentDescription("View Strength")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note"))
                .check(matches(withText(note)))
                .check(matches(not(isAssignableFrom(EditText::class.java))))
            pressBack()
            onView(withContentDescription("Continue Strength")).perform(scrollTo(), click())
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note")).check(matches(withText(note)))
            onView(withText("Finish and save")).perform(scrollTo(), click())
            assertEquals(note, LofitRepository(context).data.history.single().note)
        }
    }

    @Test
    fun unfinishedNoteSurvivesCancelAndRecreationAndCanBeCleared() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note")).perform(scrollTo(), replaceText(note))
            onView(withText("Cancel")).perform(scrollTo(), click())
            scenario.recreate()
            assertEquals(note, LofitRepository(context).data.activeWorkout!!.note)
            assertTrue(LofitRepository(context).data.history.isEmpty())
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note"))
                .check(matches(withText(note)))
                .perform(replaceText(""))
            onView(withText("Finish and save")).perform(scrollTo(), click())
            assertEquals("", LofitRepository(context).data.history.single().note)
            navigate(scenario, R.string.nav_history)
            onView(withContentDescription("Workout note")).check(doesNotExist())
            onView(withContentDescription("View Strength")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note")).check(doesNotExist())
        }
    }

    @Test
    fun historyNotesCanBeEditedDiscardedAndCleared() {
        LofitRepository(context).apply {
            val workout = data.activeWorkout!!
            workout.note = note
            workout.finish()
            data.history.add(workout)
            data.activeWorkout = null
            save()
            saveHistory()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            navigate(scenario, R.string.nav_history)
            onView(withContentDescription("Edit Strength")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note"))
                .check(matches(withText(note)))
                .perform(scrollTo(), replaceText("Discard this"))
            onView(withText("Discard changes")).perform(scrollTo(), click())
            assertEquals(note, LofitRepository(context).data.history.single().note)
            onView(withContentDescription("Workout note")).check(doesNotExist())

            val changed = "New note\nAfter editing"
            onView(withContentDescription("Edit Strength")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note")).perform(scrollTo(), replaceText(changed))
            onView(withText("Save changes")).perform(scrollTo(), click())
            assertEquals(changed, LofitRepository(context).data.history.single().note)
            onView(withContentDescription("Workout note")).check(doesNotExist())
            onView(withContentDescription("View Strength")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note")).check(matches(withText(changed)))
            pressBack()

            onView(withContentDescription("Edit Strength")).perform(scrollTo(), click())
            onView(withContentDescription("Workout note")).perform(scrollTo(), replaceText(""))
            onView(withText("Save changes")).perform(scrollTo(), click())
            assertEquals("", LofitRepository(context).data.history.single().note)
            onView(withContentDescription("Workout note")).check(doesNotExist())
        }
    }
}
