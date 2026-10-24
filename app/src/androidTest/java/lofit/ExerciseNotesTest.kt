package lofit

import android.Manifest
import android.content.Intent
import android.widget.EditText
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.PositionAssertions.isCompletelyAbove
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExerciseNotesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val preferences = context.getSharedPreferences("lofit-data", 0)

    @Before fun resetData() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        preferences.edit().clear().commit()
        LofitRepository(context).apply {
            data.bodyMetricsEnabled = false
            data.onboardingComplete = true
            data.routines.add(Routine(name = "Strength", exercises = mutableListOf(
                RoutineExercise(exerciseId = "bench-press", plannedSets = mutableListOf(PlannedSet(weightKg = 60.0))),
            )))
            save()
        }
    }

    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
        .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun navigate(scenario: ActivityScenario<MainActivity>, label: Int) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        onView(allOf(withText(label), isClickable())).perform(click())
    }

    @Test fun routineAndWorkoutNotesPersistShowAboveSetsAndCarryForwardFromHistory() {
        launch().use { scenario ->
            navigate(scenario, R.string.nav_routines)
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withContentDescription("Bench press note")).check(matches(withHint("Type note here ...")))
                .check(isCompletelyAbove(withContentDescription("Set 1 weight")))
                .perform(scrollTo(), replaceText("Keep elbows tucked\nSlow descent"), closeSoftKeyboard())
            onView(withText("Save routine")).perform(scrollTo(), click())
            assertEquals("Keep elbows tucked\nSlow descent", LofitRepository(context).data.routines.single().exercises.single().note)
            scenario.recreate()
            navigate(scenario, R.string.nav_routines)
            onView(withText("Start")).perform(scrollTo(), click())
            onView(withContentDescription("Bench press note")).check(matches(withText("Keep elbows tucked\nSlow descent")))
                .check(isCompletelyAbove(withContentDescription("Set 1 weight")))
                .perform(scrollTo(), replaceText("Pause at bottom\nBreathe out"), closeSoftKeyboard())
            scenario.recreate()
            onView(withContentDescription("Bench press note")).check(matches(withText("Pause at bottom\nBreathe out")))
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Update sets, weights and notes in Strength")).check(matches(not(isChecked())))
            onView(withText("Finish and save")).perform(scrollTo(), click())
            val data = LofitRepository(context).data
            assertEquals("Pause at bottom\nBreathe out", data.history.single().exercises.single().note)
            assertEquals("Keep elbows tucked\nSlow descent", data.routines.single().exercises.single().note)
            navigate(scenario, R.string.nav_history)
            onView(withContentDescription("View Strength")).perform(scrollTo(), click())
            onView(withContentDescription("Bench press note")).check(matches(withText("Pause at bottom\nBreathe out")))
                .check(matches(not(isAssignableFrom(EditText::class.java))))
                .check(isCompletelyAbove(withContentDescription("Set 1 weight")))
            pressBack()
            onView(withContentDescription("Create routine from Strength")).perform(scrollTo(), click())
            onView(withContentDescription("Bench press note")).check(matches(withText("Pause at bottom\nBreathe out")))
            pressBack()
            navigate(scenario, R.string.nav_routines)
            onView(withText("Start")).perform(scrollTo(), click())
            onView(withContentDescription("Bench press note")).check(matches(withText("")))
                .check(matches(withHint("Pause at bottom\nBreathe out")))
            scenario.recreate()
            onView(withContentDescription("Bench press note")).check(matches(withText("")))
                .check(matches(withHint("Pause at bottom\nBreathe out")))
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Finish and save")).perform(scrollTo(), click())
            assertEquals("Pause at bottom\nBreathe out", LofitRepository(context).data.history.last().exercises.single().note)
        }
    }

    @Test fun openWorkoutLoadsPreviousNoteAsHintAndSavesItUntouched() {
        LofitRepository(context).apply {
            data.history.add(WorkoutSession(name = "Previous", completedAt = 200, exercises = mutableListOf(
                WorkoutExercise(exerciseId = "bench-press", note = "Pause at bottom", sets = mutableListOf(
                    LoggedSet(type = SetType.NORMAL, weightKg = 70.0, reps = 8))),
            )))
            save()
        }
        launch().use { scenario ->
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle")).perform(replaceText("Bench press"), closeSoftKeyboard())
            onView(withText("Add Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Bench press note")).check(matches(withText("")))
                .check(matches(withHint("Pause at bottom")))
            scenario.recreate()
            onView(withContentDescription("Bench press note")).check(matches(withText("")))
                .check(matches(withHint("Pause at bottom")))
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Finish and save")).perform(scrollTo(), click())
            val saved = LofitRepository(context).data.history.last().exercises.single()
            assertEquals("Pause at bottom", saved.note)
            assertEquals("", saved.previousNote)
        }
    }

    @Test fun typingNewNoteReplacesHintAndClearingRestoresIt() {
        LofitRepository(context).apply {
            data.activeWorkout = WorkoutSession(name = "Notes", exercises = mutableListOf(
                WorkoutExercise(exerciseId = "bench-press", previousNote = "Old cue",
                    plannedSets = mutableListOf(PlannedSet(weightKg = 60.0))),
            ))
            save()
        }
        launch().use { scenario ->
            onView(withContentDescription("Bench press note")).check(matches(withText("")))
                .check(matches(withHint("Old cue")))
                .perform(scrollTo(), replaceText("New cue"), closeSoftKeyboard())
            scenario.recreate()
            onView(withContentDescription("Bench press note")).check(matches(withText("New cue")))
                .perform(scrollTo(), replaceText(""), closeSoftKeyboard())
            scenario.recreate()
            onView(withContentDescription("Bench press note")).check(matches(withText("")))
                .check(matches(withHint("Old cue")))
                .perform(scrollTo(), replaceText("Final cue"), closeSoftKeyboard())
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Finish and save")).perform(scrollTo(), click())
            assertEquals("Final cue", LofitRepository(context).data.history.last().exercises.single().note)
        }
    }

    @Test fun existingDataWithoutNotesStillLoads() {
        LofitRepository(context).apply {
            data.activeWorkout = data.workoutFromRoutine(data.routines.single())
            data.history.add(data.activeWorkout!!.copy(id = "past", intervals = mutableListOf(WorkoutInterval(100, 200))))
            save()
        }
        val root = JSONObject(preferences.getString("state", null)!!)
        root.getJSONArray("routines").getJSONObject(0).getJSONArray("exercises").getJSONObject(0).remove("note")
        listOf(root.getJSONObject("activeWorkout"), root.getJSONArray("history").getJSONObject(0)).forEach { workout ->
            workout.getJSONArray("exercises").getJSONObject(0).remove("note")
            workout.getJSONArray("exercises").getJSONObject(0).remove("previousNote")
            workout.getJSONObject("sourceRoutine").getJSONArray("exercises").getJSONObject(0).remove("note")
        }
        assertTrue(preferences.edit().putString("state", root.toString()).commit())
        val loaded = LofitRepository(context).data
        assertEquals("", loaded.routines.single().exercises.single().note)
        assertEquals("", loaded.activeWorkout!!.exercises.single().note)
        assertEquals("", loaded.activeWorkout!!.exercises.single().previousNote)
        assertEquals("", loaded.activeWorkout!!.sourceRoutine!!.exercises.single().note)
        assertEquals("", loaded.history.single().exercises.single().note)
    }
}
