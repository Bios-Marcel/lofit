package lofit

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.card.MaterialCardView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoutineWorkoutFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun resetTestData() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        context.getSystemService(NotificationManager::class.java).cancelAll()
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.bodyMetricsEnabled = false
            data.onboardingComplete = true
            data.routines.add(
                Routine(
                    name = "Strength",
                    exercises =
                        mutableListOf(
                            RoutineExercise(
                                exerciseId = "bench-press",
                                plannedSets = mutableListOf(PlannedSet(weightKg = 60.0, reps = 5)),
                            )
                        ),
                )
            )
            data.history.add(
                WorkoutSession(
                    name = "Previous",
                    completedAt = 200,
                    sourceRoutine = data.routines.single(),
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                restSecondsOverride = 45,
                                sets =
                                    mutableListOf(
                                        LoggedSet(
                                            type = SetType.WARMUP,
                                            weightKg = 20.0,
                                            reps = 12,
                                        ),
                                        LoggedSet(type = SetType.NORMAL, weightKg = 80.0, reps = 6),
                                    ),
                            )
                        ),
                )
            )
            saveHistory()
            save()
        }
    }

    private fun launch() =
        ActivityScenario.launch<MainActivity>(
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

    private fun inCard(name: String) =
        isDescendantOfA(
            allOf(
                isAssignableFrom(MaterialCardView::class.java),
                hasDescendant(withText(name)),
            )
        )

    private fun editRoutine(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        onView(allOf(withText(R.string.nav_routines), isClickable())).perform(click())
        onView(withText("Edit")).perform(scrollTo(), click())
    }

    @Test
    fun asymmetricRoutineSet_sideColumnSavesSelection() {
        LofitRepository(context).apply {
            data.exercises.add(
                Exercise(
                    id = "single-leg",
                    name = "Single leg",
                    type = ExerciseType.REPETITIONS,
                    asymmetric = true,
                    primaryMuscle = "Quads",
                    secondaryMuscles = "",
                    custom = true,
                    weighted = false,
                )
            )
            data.routines
                .single()
                .exercises
                .add(
                    RoutineExercise(
                        exerciseId = "single-leg",
                        plannedSets = mutableListOf(PlannedSet(reps = 12, side = Side.LEFT)),
                    )
                )
            save()
        }
        launch().use { scenario ->
            editRoutine(scenario)
            onView(allOf(withContentDescription("Set 1 side: Left"), inCard("Single leg")))
                .check(matches(withText("L")))
                .perform(scrollTo(), click())
            onView(allOf(withContentDescription("Set 1 side: Right"), inCard("Single leg")))
                .check(matches(withText("R")))
            onView(withText("Save routine")).perform(scrollTo(), click())
            assertEquals(
                Side.RIGHT,
                LofitRepository(context)
                    .data
                    .routines
                    .single()
                    .exercises
                    .last()
                    .plannedSets
                    .single()
                    .side,
            )
        }
    }

    @Test
    fun cancelUnchangedOrRevertedRoutineDoesNotAskToDiscard() {
        LofitRepository(context).apply {
            data.routines
                .single()
                .exercises
                .add(
                    RoutineExercise(
                        exerciseId = "plank",
                        plannedSets = mutableListOf(PlannedSet(actualHoldSeconds = 45)),
                    )
                )
            save()
        }
        val original = LofitRepository(context).data.routines.single()
        launch().use { scenario ->
            editRoutine(scenario)
            onView(withText("Cancel")).perform(scrollTo(), click())
            onView(withText("Create routine")).check(matches(isDisplayed()))
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withHint("Routine name"))
                .perform(scrollTo(), replaceText("Changed"), closeSoftKeyboard())
            onView(withContentDescription("Set 1 weight"))
                .perform(scrollTo(), replaceText("85"), closeSoftKeyboard())
            onView(withHint("Routine name"))
                .perform(scrollTo(), replaceText("Strength"), closeSoftKeyboard())
            onView(withContentDescription("Set 1 weight"))
                .perform(scrollTo(), replaceText("60"), closeSoftKeyboard())
            onView(withText("Cancel")).perform(scrollTo(), click())
            onView(withText("Create routine")).check(matches(isDisplayed()))
            assertEquals(original, LofitRepository(context).data.routines.single())
        }
    }

    @Test
    fun cancelChangedRoutineAsksAboutChangesAndKeepsSavedRoutine() {
        val original = LofitRepository(context).data.routines.single()
        launch().use { scenario ->
            editRoutine(scenario)
            onView(withContentDescription("Set 1 weight"))
                .perform(scrollTo(), replaceText("85"), closeSoftKeyboard())
            onView(withText("Cancel")).perform(scrollTo(), click())
            onView(withText("Discard changes?")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Cancel")).inRoot(isDialog()).perform(click())
            onView(withContentDescription("Set 1 weight"))
                .perform(scrollTo())
                .check(matches(withText("85")))
            assertEquals(original, LofitRepository(context).data.routines.single())
            onView(withText("Cancel")).perform(scrollTo(), click())
            onView(withText("Discard changes")).inRoot(isDialog()).perform(click())
            onView(withText("Create routine")).check(matches(isDisplayed()))
            assertEquals(original, LofitRepository(context).data.routines.single())
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 weight")).check(matches(withText("60")))
        }
    }

    @Test
    fun cancelRoutineAfterRemovingLastExerciseStillAsksAboutChanges() {
        val original = LofitRepository(context).data.routines.single()
        launch().use { scenario ->
            editRoutine(scenario)
            onView(withContentDescription("Remove Bench press")).perform(scrollTo(), click())
            onView(withText("Cancel")).perform(scrollTo(), click())
            onView(withText("Discard changes?")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Discard changes")).inRoot(isDialog()).perform(click())
            onView(withText("Create routine")).check(matches(isDisplayed()))
            assertEquals(original, LofitRepository(context).data.routines.single())
        }
    }

    private fun assertKeyboardVisible(scenario: ActivityScenario<MainActivity>, expected: Boolean) {
        var visible: Boolean? = null
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        do {
            scenario.onActivity { activity ->
                visible =
                    ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ?.isVisible(WindowInsetsCompat.Type.ime())
            }
            if (visible == expected) return
            android.os.SystemClock.sleep(50)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        assertEquals("Keyboard visibility", expected, visible)
    }

    @Test
    fun addingExerciseToWorkoutClosesSearchKeyboard() {
        launch().use { scenario ->
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle")).perform(typeText("Bench press"))
            assertKeyboardVisible(scenario, true)

            onView(withContentDescription("Add Bench press")).perform(scrollTo(), click())

            assertKeyboardVisible(scenario, false)
            onView(withText("Bench press")).check(matches(isDisplayed()))
            onView(withHint("Search name or muscle")).check(doesNotExist())
        }
    }

    @Test
    fun addingExerciseToRoutineClosesSearchKeyboard() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
            }
            onView(allOf(withText(R.string.nav_routines), isClickable())).perform(click())
            onView(withText("Create routine")).perform(click())
            onView(withText("Add Exercise")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle")).perform(typeText("Bench press"))
            assertKeyboardVisible(scenario, true)

            onView(withContentDescription("Add Bench press")).perform(scrollTo(), click())

            assertKeyboardVisible(scenario, false)
            onView(withText("New routine")).check(matches(isDisplayed()))
            onView(withHint("Search name or muscle")).check(doesNotExist())
        }
    }

    @Test
    fun routinePickerReturnsAtBottomOnFirstDrawForAddAndBack() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
            }
            onView(allOf(withText(R.string.nav_routines), isClickable())).perform(click())
            onView(withText("Create routine")).perform(click())
            onView(withText("Add Exercise")).perform(scrollTo(), click())
            onView(withContentDescription("Add Bench press")).perform(scrollTo(), click())

            fun checkFirstDrawAtBottom(returnFromPicker: () -> Unit) {
                val drawn = CountDownLatch(1)
                val firstPosition = AtomicReference<Pair<Int, Int>>()
                lateinit var root: View
                val listener = ViewTreeObserver.OnDrawListener {
                    val titles = arrayListOf<View>()
                    root.findViewsWithText(titles, "New routine", View.FIND_VIEWS_WITH_TEXT)
                    var parent = titles.firstOrNull()?.parent
                    while (parent != null && parent !is ScrollView) parent = parent.parent
                    (parent as? ScrollView)?.let { scroll ->
                        val bottom = scroll.getChildAt(0).height - scroll.height
                        if (firstPosition.compareAndSet(null, scroll.scrollY to bottom))
                            drawn.countDown()
                    }
                }
                scenario.onActivity { activity ->
                    root = activity.window.decorView
                    root.viewTreeObserver.addOnDrawListener(listener)
                }
                try {
                    returnFromPicker()
                    assertTrue("Routine editor never drew", drawn.await(5, TimeUnit.SECONDS))
                    val (scrollY, bottom) = firstPosition.get()
                    assertTrue("Routine must exceed the viewport", bottom > 0)
                    assertEquals("First frame must already show the bottom", bottom, scrollY)
                } finally {
                    scenario.onActivity { root.viewTreeObserver.removeOnDrawListener(listener) }
                }
                onView(withText("Cancel")).check(matches(isCompletelyDisplayed()))
            }

            onView(withText("Add Exercise")).perform(scrollTo(), click())
            checkFirstDrawAtBottom {
                onView(withContentDescription("Add Bench press")).perform(scrollTo(), click())
            }
            onView(withText("Add Exercise")).perform(click())
            checkFirstDrawAtBottom { pressBack() }
        }
    }

    @Test
    fun emptyWorkoutReusesFullLoggedExercise() {
        launch().use {
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle"))
                .perform(replaceText("Bench press"), closeSoftKeyboard())
            onView(withContentDescription("Add Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 weight")).check(matches(withText("20")))
            onView(withContentDescription("Set 2 weight")).check(matches(withText("80")))
            onView(withContentDescription("Set 2 Repetitions")).check(matches(withText("6")))
            onView(withContentDescription("Set 3 row")).check(doesNotExist())
            val item = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertEquals(listOf(SetType.WARMUP, SetType.NORMAL), item.plannedSets.map { it.type })
            assertEquals(45, item.restSecondsOverride)
            assertEquals(2, item.plannedSets.size)
            assertTrue(item.sets.isEmpty())
        }
    }

    @Test
    fun sameRoutineHistoryWinsAndOpenWorkoutStillUsesNewestHistory() {
        LofitRepository(context).apply {
            val source = data.routines.single()
            source.exercises.single().plannedSets.apply {
                clear()
                repeat(3) { add(PlannedSet(weightKg = 60.0, reps = 5)) }
            }
            val own =
                WorkoutSession(
                    name = "Older routine",
                    completedAt = 300,
                    sourceRoutine = source,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                sets =
                                    MutableList(2) {
                                        LoggedSet(type = SetType.NORMAL, weightKg = 70.0, reps = 8)
                                    },
                            ),
                            WorkoutExercise(
                                exerciseId = "plank",
                                sets =
                                    mutableListOf(
                                        LoggedSet(
                                            type = SetType.NORMAL,
                                            weightKg = 0.0,
                                            holdSeconds = 45,
                                        )
                                    ),
                            ),
                        ),
                )
            val other =
                WorkoutSession(
                    name = source.name,
                    completedAt = 500,
                    sourceRoutine = Routine(name = source.name),
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                sets =
                                    MutableList(4) {
                                        LoggedSet(type = SetType.NORMAL, weightKg = 150.0, reps = 1)
                                    },
                            )
                        ),
                )
            val open =
                WorkoutSession(
                    name = "Open workout",
                    completedAt = 600,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                sets =
                                    MutableList(5) {
                                        LoggedSet(type = SetType.NORMAL, weightKg = 120.0, reps = 2)
                                    },
                            ),
                            WorkoutExercise(
                                exerciseId = "plank",
                                sets =
                                    mutableListOf(
                                        LoggedSet(
                                            type = SetType.NORMAL,
                                            weightKg = 0.0,
                                            holdSeconds = 90,
                                        )
                                    ),
                            ),
                        ),
                )
            data.history.addAll(listOf(other, open, own))
            saveHistory()
            save() // Exercise source preference must survive storage, not rely on object identity.
        }
        launch().use {
            onView(withText("Start routine")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 weight")).check(matches(withText("70")))
            onView(withContentDescription("Set 3 Repetitions")).check(matches(withText("8")))
            onView(withContentDescription("Set 4 row")).check(doesNotExist())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle"))
                .perform(replaceText("Plank"), closeSoftKeyboard())
            onView(withContentDescription("Add Plank")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 Goal time in seconds"))
                .check(matches(withText("45")))
            onView(withText("Discard workout")).perform(scrollTo(), click())
            onView(withText("Discard workout")).inRoot(isDialog()).perform(click())
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle"))
                .perform(replaceText("Bench press"), closeSoftKeyboard())
            onView(withContentDescription("Add Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 weight")).check(matches(withText("120")))
            onView(withContentDescription("Set 5 Repetitions")).check(matches(withText("2")))
            assertEquals(
                5,
                LofitRepository(context).data.activeWorkout!!.exercises.single().plannedSets.size,
            )
        }
    }

    @Test
    fun routineWithoutOwnHistoryKeepsSavedSetsAndOpenWorkoutUsesLatestHistory() {
        LofitRepository(context).apply {
            val source = data.routines.single()
            source.exercises.single().plannedSets.apply {
                clear()
                add(PlannedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 12))
                add(PlannedSet(weightKg = 60.0, reps = 5))
                add(PlannedSet(type = SetType.FAILURE, weightKg = 65.0, reps = 3))
            }
            source.exercises.add(
                RoutineExercise(
                    exerciseId = "plank",
                    plannedSets =
                        mutableListOf(
                            PlannedSet(holdSeconds = 45),
                            PlannedSet(holdSeconds = 60),
                        ),
                )
            )
            data.history.clear()
            data.history.add(
                WorkoutSession(
                    name = source.name,
                    completedAt = 500,
                    sourceRoutine = Routine(name = source.name),
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                sets =
                                    MutableList(4) {
                                        LoggedSet(type = SetType.NORMAL, weightKg = 150.0, reps = 1)
                                    },
                            ),
                            WorkoutExercise(
                                exerciseId = "plank",
                                sets =
                                    MutableList(4) {
                                        LoggedSet(
                                            type = SetType.NORMAL,
                                            weightKg = 0.0,
                                            holdSeconds = 120,
                                        )
                                    },
                            ),
                        ),
                )
            )
            data.history.add(
                WorkoutSession(
                    name = "Open workout",
                    completedAt = 600,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                sets =
                                    MutableList(5) {
                                        LoggedSet(type = SetType.NORMAL, weightKg = 200.0, reps = 2)
                                    },
                            ),
                            WorkoutExercise(
                                exerciseId = "plank",
                                sets =
                                    mutableListOf(
                                        LoggedSet(
                                            type = SetType.NORMAL,
                                            weightKg = 0.0,
                                            holdSeconds = 180,
                                        )
                                    ),
                            ),
                        ),
                )
            )
            saveHistory()
            save()
        }
        launch().use {
            onView(withText("Start routine")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 weight")).check(matches(withText("20")))
            onView(withContentDescription("Set 2 weight")).check(matches(withText("60")))
            onView(withContentDescription("Set 3 weight")).check(matches(withText("65")))
            onView(withContentDescription("Set 1 Repetitions")).check(matches(withText("12")))
            onView(withContentDescription("Set 2 Repetitions")).check(matches(withText("5")))
            onView(withContentDescription("Set 3 Repetitions")).check(matches(withText("3")))
            onView(withContentDescription("Set 1 Goal time in seconds"))
                .check(matches(withText("45")))
            onView(withContentDescription("Set 2 Goal time in seconds"))
                .check(matches(withText("60")))
            onView(allOf(withContentDescription("Set 4 row"), inCard("Bench press")))
                .check(doesNotExist())
            onView(allOf(withContentDescription("Set 3 row"), inCard("Plank")))
                .check(doesNotExist())
            assertEquals(
                listOf(3, 2),
                LofitRepository(context).data.activeWorkout!!.exercises.map { it.plannedSets.size },
            )
            onView(withText("Discard workout")).perform(scrollTo(), click())
            onView(withText("Discard workout")).inRoot(isDialog()).perform(click())
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle"))
                .perform(replaceText("Bench press"), closeSoftKeyboard())
            onView(withContentDescription("Add Bench press")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 weight")).check(matches(withText("200")))
            onView(withContentDescription("Set 5 Repetitions")).check(matches(withText("2")))
        }
    }

    @Test
    fun unchangedRoutineDoesNotAskToUpdateJustBecauseHistoryPrefilledWeights() {
        launch().use {
            onView(withText("Start routine")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 weight")).check(matches(withText("80")))
            onView(withContentDescription("Set 1 done")).perform(click())
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Update sets, weights and notes in Strength")).check(doesNotExist())
            onView(withText("Add new exercises to Strength")).check(doesNotExist())
            onView(withText("Finish and save")).perform(click())
            assertEquals(
                60.0,
                LofitRepository(context)
                    .data
                    .routines
                    .single()
                    .exercises
                    .single()
                    .plannedSets
                    .single()
                    .weightKg,
                0.0,
            )
        }
    }

    private fun changedWorkout(includeUpdates: Boolean, includeAdded: Boolean) {
        launch().use { scenario ->
            onView(withText("Start routine")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 weight"))
                .perform(replaceText("85"), closeSoftKeyboard())
            onView(withText("Add set")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 done")).perform(click())
            onView(withText("Add exercise")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle"))
                .perform(replaceText("Plank"), closeSoftKeyboard())
            onView(withContentDescription("Add Plank")).perform(scrollTo(), click())
            onView(allOf(withText("Add set"), inCard("Plank"))).perform(scrollTo(), click())
            scenario
                .recreate() // Source identity and initial prescription must survive a saved-data
            // reload.
            assertNotNull(LofitRepository(context).data.activeWorkout!!.sourceRoutine)
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Update sets, weights and notes in Strength"))
                .check(matches(not(isChecked())))
            onView(withText("Add new exercises to Strength")).check(matches(not(isChecked())))
            if (includeUpdates)
                onView(withText("Update sets, weights and notes in Strength")).perform(click())
            if (includeAdded) onView(withText("Add new exercises to Strength")).perform(click())
            onView(withText("Finish and save")).perform(scrollTo(), click())
            val data = LofitRepository(context).data
            assertNull(data.activeWorkout)
            val source = data.routines.single()
            assertEquals(
                if (includeAdded) listOf("bench-press", "plank") else listOf("bench-press"),
                source.exercises.map { it.exerciseId },
            )
            assertEquals(if (includeUpdates) 2 else 1, source.exercises.first().plannedSets.size)
            assertEquals(
                if (includeUpdates) 85.0 else 60.0,
                source.exercises.first().plannedSets.first().weightKg,
                0.0,
            )
            assertEquals(2, data.history.size)
            assertEquals(85.0, data.history.last().exercises.first().sets.single().weightKg, 0.0)
            if (includeAdded) assertEquals(1, source.exercises.last().plannedSets.size)
        }
    }

    @Test fun finishCanDeclineAllRoutineChanges() = changedWorkout(false, false)

    @Test fun finishCanUpdateSetsWithoutAddingExercises() = changedWorkout(true, false)

    @Test fun finishCanAddExercisesWithoutUpdatingExistingSets() = changedWorkout(false, true)

    @Test fun finishCanUpdateSetsAndIncludeAddedExercises() = changedWorkout(true, true)

    @Test
    fun changedRoutineWithNoDoneSetsCannotFinish() {
        launch().use {
            onView(withText("Start routine")).perform(scrollTo(), click())
            onView(withText("Add set")).perform(scrollTo(), click())
            onView(withText("Finish workout")).perform(scrollTo()).check(matches(not(isEnabled())))
            val data = LofitRepository(context).data
            assertNotNull(data.activeWorkout)
            assertEquals(1, data.history.size)
            assertEquals(1, data.routines.single().exercises.single().plannedSets.size)
        }
    }

    @Test
    fun cancelThenDiscardNeverUpdatesSource() {
        launch().use {
            onView(withText("Start routine")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 weight"))
                .perform(replaceText("85"), closeSoftKeyboard())
            onView(withContentDescription("Set 1 done")).perform(click())
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Update sets, weights and notes in Strength")).perform(click())
            onView(withText("Cancel")).perform(click())
            onView(withText("Discard workout")).perform(scrollTo(), click())
            onView(withText("Update sets, weights and notes in Strength")).check(doesNotExist())
            onView(withText("Discard workout")).inRoot(isDialog()).perform(click())
            val data = LofitRepository(context).data
            assertNull(data.activeWorkout)
            assertEquals(1, data.history.size)
            assertEquals(
                60.0,
                data.routines.single().exercises.single().plannedSets.single().weightKg,
                0.0,
            )
        }
    }
}
