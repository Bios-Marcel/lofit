package lofit

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExerciseEditingFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun resetData() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        context.getSystemService(NotificationManager::class.java).cancelAll()
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.activeWorkout =
                WorkoutSession(
                    name = "Exercise edits",
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                restSecondsOverride = 45,
                                note = "Bench setup",
                                plannedSets =
                                    mutableListOf(
                                        PlannedSet(
                                            weightKg = 60.0,
                                            reps = 5,
                                            type = SetType.WARMUP,
                                        ),
                                        PlannedSet(weightKg = 70.0, reps = 7),
                                    ),
                            ),
                            WorkoutExercise(exerciseId = "plank"),
                        ),
                )
            save()
        }
    }

    private fun launch() =
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))

    private fun active() = LofitRepository(context).data.activeWorkout!!

    private fun chooseAction(exercise: String, action: String) {
        onView(withContentDescription("Options for $exercise")).perform(scrollTo(), click())
        onView(withText(action)).perform(click())
    }

    private fun search(query: String) {
        onView(withHint("Search name or muscle")).perform(replaceText(query), closeSoftKeyboard())
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, label: Int) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        onView(allOf(withText(label), isAssignableFrom(Button::class.java))).perform(click())
    }

    @Test
    fun switchingDrawerScreensClosesKeyboard() {
        launch().use { scenario ->
            navigate(scenario, R.string.nav_exercises)
            KeyboardMonitor(scenario).use { keyboard ->
                onView(withHint("Search name or muscle")).perform(click(), replaceText("bench"))
                onView(withHint("Search name or muscle")).perform(keyboard.awaitVisible(true))
                navigate(scenario, R.string.nav_records)
                onView(isRoot()).perform(keyboard.awaitVisible(false))
            }
        }
    }

    @Test
    fun openingEditorClosesKeyboardAndAllowsTypingAgain() {
        launch().use { scenario ->
            navigate(scenario, R.string.nav_exercises)
            KeyboardMonitor(scenario).use { keyboard ->
                onView(withHint("Search name or muscle")).perform(click(), replaceText("bench"))
                onView(withHint("Search name or muscle")).perform(keyboard.awaitVisible(true))
                onView(withText("Create custom exercise")).perform(scrollTo(), click())
                onView(withHint("Exercise name")).perform(keyboard.awaitVisible(false))
                onView(withHint("Exercise name")).perform(click(), replaceText("Custom press"))
                onView(withHint("Exercise name")).perform(keyboard.awaitVisible(true))
            }
        }
    }

    @Test
    fun menuShowsRemoveFirstAndOpeningOrCancellingDoesNotChangeWorkout() {
        val before = active()
        launch().use {
            onView(withContentDescription("Options for Bench press")).perform(scrollTo(), click())
            val positions =
                listOf(
                        "Remove exercise",
                        "Replace exercise",
                        "Add exercise above",
                        "Add exercise below",
                    )
                    .map { label ->
                        var top = 0
                        onView(withText(label)).check { view, error ->
                            if (error != null) throw error
                            val location = IntArray(2)
                            view.getLocationOnScreen(location)
                            top = location[1]
                        }
                        top
                    }
            assertTrue(positions.zipWithNext().all { (first, second) -> first < second })
            assertEquals(before, active())
            pressBack()
            chooseAction("Bench press", "Replace exercise")
            onView(withText("Replace Bench press")).check(matches(isDisplayed()))
            onView(withContentDescription("Add selected exercises")).check(doesNotExist())
            onView(withContentDescription("Select Dumbbell curl")).check(doesNotExist())
            pressBack()
            assertEquals(before, active())
            chooseAction("Bench press", "Remove exercise")
            assertEquals(listOf("plank"), active().exercises.map { it.exerciseId })
        }
    }

    @Test
    fun insertAboveKeepsSelectionOrderAndUndoThenInsertBelowUsesAnchor() {
        launch().use {
            chooseAction("Plank", "Add exercise above")
            onView(withText("Add above Plank")).check(matches(isDisplayed()))
            search("Dumbbell curl")
            onView(withContentDescription("Select Dumbbell curl")).perform(scrollTo(), click())
            search("Back squat")
            onView(withContentDescription("Select Back squat")).perform(scrollTo(), click())
            onView(withContentDescription("Add selected exercises")).perform(click())
            assertEquals(
                listOf("bench-press", "dumbbell-curl", "back-squat", "plank"),
                active().exercises.map { it.exerciseId },
            )
            onView(withText("Undo")).perform(click())
            assertEquals(listOf("bench-press", "plank"), active().exercises.map { it.exerciseId })
            chooseAction("Bench press", "Add exercise below")
            search("Dumbbell curl")
            onView(withContentDescription("Add Dumbbell curl")).perform(scrollTo(), click())
            assertEquals(
                listOf("bench-press", "dumbbell-curl", "plank"),
                active().exercises.map { it.exerciseId },
            )
        }
    }

    @Test
    fun replacementKeepsPrescriptionAndUsesNewExercisesLoadThenUndoRestoresOriginal() {
        LofitRepository(context).apply {
            data.history.add(
                WorkoutSession(
                    name = "Previous curls",
                    completedAt = 200,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "dumbbell-curl",
                                note = "Curl setup",
                                sets =
                                    mutableListOf(
                                        LoggedSet(type = SetType.NORMAL, weightKg = 17.5, reps = 10)
                                    ),
                            )
                        ),
                )
            )
            saveHistory()
        }
        val before = active()
        launch().use { scenario ->
            chooseAction("Bench press", "Replace exercise")
            search("Dumbbell curl")
            onView(withContentDescription("Replace with Dumbbell curl"))
                .perform(scrollTo(), click())
            val replaced = active()
            assertEquals(listOf("dumbbell-curl", "plank"), replaced.exercises.map { it.exerciseId })
            val curl = replaced.exercises.first()
            assertEquals(listOf(5, 7), curl.plannedSets.map { it.reps })
            assertEquals(listOf(SetType.WARMUP, SetType.NORMAL), curl.plannedSets.map { it.type })
            assertEquals(listOf(17.5, 17.5), curl.plannedSets.map { it.weightKg })
            assertEquals(45, curl.restSecondsOverride)
            assertEquals("Curl setup", curl.effectiveNote)
            assertTrue(curl.sets.isEmpty())
            onView(withText("Undo")).perform(click())
            assertEquals(before, active())
            scenario.recreate()
            assertEquals(before, active())
        }
    }

    @Test
    fun replaceRemainingKeepsLoggedSetsOnOriginalAndUndoRestoresPendingSets() {
        LofitRepository(context).apply {
            val bench = data.activeWorkout!!.exercises.first()
            val planned = bench.plannedSets.first()
            bench.sets.add(
                LoggedSet(
                    type = planned.type,
                    weightKg = planned.weightKg,
                    reps = planned.reps,
                    plannedSetId = planned.id,
                )
            )
            save()
        }
        val before = active()
        launch().use {
            chooseAction("Bench press", "Replace remaining")
            search("Dumbbell curl")
            onView(withContentDescription("Replace with Dumbbell curl"))
                .perform(scrollTo(), click())
            val updated = active()
            assertEquals(
                listOf("bench-press", "dumbbell-curl", "plank"),
                updated.exercises.map { it.exerciseId },
            )
            assertEquals(before.exercises.first().sets, updated.exercises.first().sets)
            assertEquals(1, updated.exercises.first().plannedSets.size)
            assertEquals(7, updated.exercises[1].plannedSets.single().reps)
            assertEquals(0.0, updated.exercises[1].plannedSets.single().weightKg, 0.0)
            onView(withText("Undo")).perform(click())
            assertEquals(before, active())
        }
    }

    @Test
    fun fullyLoggedExerciseDisablesReplaceRemaining() {
        LofitRepository(context).apply {
            val bench = data.activeWorkout!!.exercises.first()
            bench.plannedSets.forEach { planned ->
                bench.sets.add(
                    LoggedSet(
                        type = planned.type,
                        weightKg = planned.weightKg,
                        reps = planned.reps,
                        plannedSetId = planned.id,
                    )
                )
            }
            save()
        }
        launch().use {
            onView(withContentDescription("Options for Bench press")).perform(scrollTo(), click())
            onView(withText("Replace remaining")).check(matches(isDescendantOfA(not(isEnabled()))))
            pressBack()
        }
    }

    @Test
    fun routineInsertAndReplaceStayInDraftUntilSave() {
        val routine =
            Routine(
                name = "Strength",
                exercises =
                    mutableListOf(
                        RoutineExercise(
                            exerciseId = "bench-press",
                            restSecondsOverride = 45,
                            plannedSets = mutableListOf(PlannedSet(weightKg = 60.0, reps = 5)),
                        )
                    ),
            )
        LofitRepository(context).apply {
            data.routines.add(routine)
            save()
        }
        val activeBefore = active()
        launch().use { scenario ->
            navigate(scenario, R.string.nav_routines)
            onView(withText("Edit")).perform(scrollTo(), click())
            chooseAction("Bench press", "Add exercise above")
            search("Dumbbell curl")
            onView(withContentDescription("Add Dumbbell curl")).perform(scrollTo(), click())
            chooseAction("Bench press", "Add exercise below")
            search("Plank")
            onView(withContentDescription("Add Plank")).perform(scrollTo(), click())
            chooseAction("Bench press", "Replace exercise")
            search("Back squat")
            onView(withContentDescription("Replace with Back squat")).perform(scrollTo(), click())
            assertEquals(routine, LofitRepository(context).data.routines.single())
            onView(withText("Save routine")).perform(scrollTo(), click())
            val saved = LofitRepository(context).data.routines.single()
            assertEquals(
                listOf("dumbbell-curl", "back-squat", "plank"),
                saved.exercises.map { it.exerciseId },
            )
            assertEquals(5, saved.exercises[1].plannedSets.single().reps)
            assertEquals(45, saved.exercises[1].restSecondsOverride)
            assertEquals(0.0, saved.exercises[1].plannedSets.single().weightKg, 0.0)
            assertEquals(activeBefore, active())
        }
    }

    @Test
    fun historyInsertAndReplaceStayInDraftAndKeepActiveWorkoutUntouched() {
        val finished =
            WorkoutSession(
                name = "Past workout",
                startedAt = 1000,
                completedAt = 121000,
                exercises =
                    mutableListOf(
                        WorkoutExercise(
                            exerciseId = "bench-press",
                            plannedSets = mutableListOf(PlannedSet(weightKg = 60.0, reps = 5)),
                        )
                    ),
            )
        LofitRepository(context).apply {
            data.history.add(finished)
            saveHistory()
        }
        val activeBefore = active()
        launch().use { scenario ->
            navigate(scenario, R.string.nav_history)
            onView(withContentDescription("Edit Past workout")).perform(scrollTo(), click())
            chooseAction("Bench press", "Add exercise below")
            search("Plank")
            onView(withContentDescription("Add Plank")).perform(scrollTo(), click())
            chooseAction("Bench press", "Replace exercise")
            search("Dumbbell curl")
            onView(withContentDescription("Replace with Dumbbell curl"))
                .perform(scrollTo(), click())
            assertEquals(finished, LofitRepository(context).data.history.single())
            onView(withText("Save changes")).perform(scrollTo(), click())
            val saved = LofitRepository(context).data.history.single()
            assertEquals(listOf("dumbbell-curl", "plank"), saved.exercises.map { it.exerciseId })
            assertEquals(finished.intervals, saved.intervals)
            assertEquals(activeBefore, active())
        }
    }
}
