package lofit

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.view.WindowManager
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import android.view.View
import org.hamcrest.Matcher
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutFlowTest {
    @Before
    fun clearStoredData() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        context
            .getSharedPreferences("lofit-data", 0)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun emptyWorkout_canStartAddExerciseAndLogWarmupSet() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent) as MainActivity

        onView(withText(R.string.start_workout)).perform(click())
        assertEquals(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        onView(withText("This workout is empty. Add any exercise when you’re ready.")).check(matches(isDisplayed()))
        onView(withText("Add exercise")).perform(scrollTo(), click())
        onView(withHint("Search name or muscle")).perform(typeText("squat"), closeSoftKeyboard())
        onView(withContentDescription("Back squat illustration")).check(matches(isDisplayed()))
        onView(withText("Add Back squat")).perform(scrollTo(), click())
        onView(withText("Back squat")).check(matches(isDisplayed()))
        onView(withText("Add set")).perform(scrollTo(), click())
        val notificationManager = instrumentation.targetContext.getSystemService(NotificationManager::class.java)
        val activeNotification = notificationManager.activeNotifications.single().notification
        assertEquals("Back squat", activeNotification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Set 1 of 1", activeNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        onView(withContentDescription("Set 1 type: Normal")).perform(click())
        onView(withText("Warm-up")).perform(click())
        onView(withContentDescription("Set 1 weight")).perform(replaceText("20"), closeSoftKeyboard())
        onView(withContentDescription("Set 1 Repetitions")).perform(replaceText("10"), closeSoftKeyboard())
        onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
        assert(notificationManager.activeNotifications.single().notification.extras
            .getCharSequence(Notification.EXTRA_TEXT).toString().contains("Rest"))
        onView(withText("Add set")).perform(scrollTo(), click())
        onView(withContentDescription("Set 2 weight")).check(matches(withText("20")))
        onView(withContentDescription("Set 2 Repetitions")).check(matches(withText("10")))
        onView(withText("Finish workout")).perform(scrollTo(), click())
        onView(withText("Finish and save")).perform(click())
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertEquals(0, notificationManager.activeNotifications.size)
    }

    @Test
    fun routineSetType_isEditableAndUsedForWorkout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.routines.add(Routine(
            name = "Strength",
            exercises = mutableListOf(RoutineExercise(
                exerciseId = "bench-press",
                plannedSets = mutableListOf(PlannedSet(weightKg = 60.0, reps = 5)),
            )),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_routines)).perform(click())
        onView(withText("Edit")).perform(click())
        onView(withContentDescription("Set 1 type: Normal")).perform(scrollTo(), click())
        onView(withText("Failure")).perform(click())
        onView(withText("Save routine")).perform(scrollTo(), click())
        onView(withText("Start")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 type: Failure")).check(matches(isDisplayed()))
        onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
    }

    private fun openDrawer() = object : ViewAction {
        override fun getDescription() = "open navigation drawer"
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun perform(uiController: UiController, view: View) {
            (view as DrawerLayout).openDrawer(GravityCompat.START, false)
            uiController.loopMainThreadUntilIdle()
        }
    }
}
