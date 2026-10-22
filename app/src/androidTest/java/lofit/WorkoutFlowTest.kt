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
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import android.view.View
import android.widget.CheckBox
import android.widget.ScrollView
import java.time.LocalDate
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun settings_soundCuesAreOnByDefaultAndPersistOff() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_settings)).perform(click())
        onView(withContentDescription("Sound cues"))
            .check(matches(isChecked()))
            .perform(click())
            .check(matches(not(isChecked())))

        assertFalse(LofitRepository(instrumentation.targetContext).data.soundCuesEnabled)
        activity.finish()
    }

    @Test
    fun oldDurationTypes_migrateToTimed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val legacyTypes = listOf("WEIGHTED_HOLD", "ISOMETRIC", "STRETCH")
        val exercises = legacyTypes.mapIndexed { index, type ->
            """{"id":"legacy-$index","name":"Legacy $index","type":"$type","asymmetric":false,"primaryMuscle":"Core","secondaryMuscles":"","custom":true}"""
        }.joinToString(",")
        context.getSharedPreferences("lofit-data", 0).edit()
            .putString("state", """{"exercises":[$exercises],"routines":[],"history":[],"activeWorkout":null}""")
            .commit()

        val migrated = LofitRepository(context).data.exercises.filter { it.id.startsWith("legacy-") }

        assertEquals(3, migrated.size)
        assertTrue(migrated.all { it.type == ExerciseType.TIMED })
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
        onView(withText("Add set")).check { view, _ ->
            var ancestor = view.parent
            while (ancestor !is MaterialCardView) ancestor = ancestor.parent
            val buttonLocation = IntArray(2)
            val cardLocation = IntArray(2)
            view.getLocationOnScreen(buttonLocation)
            ancestor.getLocationOnScreen(cardLocation)
            assertEquals(cardLocation[0], buttonLocation[0])
            assertEquals(cardLocation[0] + ancestor.width, buttonLocation[0] + view.width)
        }
        onView(withText("Add set")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 row")).check { view, _ ->
            var ancestor = view.parent
            while (ancestor !is MaterialCardView) ancestor = ancestor.parent
            val rowLocation = IntArray(2)
            val cardLocation = IntArray(2)
            view.getLocationOnScreen(rowLocation)
            ancestor.getLocationOnScreen(cardLocation)
            assertEquals(cardLocation[0], rowLocation[0])
            assertEquals(cardLocation[0] + ancestor.width, rowLocation[0] + view.width)
        }
        onView(withContentDescription("Set 1 weight"))
            .perform(click(), typeText("7"), closeSoftKeyboard())
            .check(matches(withText("7")))
        onView(withContentDescription("Set 1 Repetitions"))
            .perform(click(), typeText("8"), closeSoftKeyboard())
            .check(matches(withText("8")))
        var setTypeRight = 0
        onView(withContentDescription("Set 1 type: Normal")).check { view, _ ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            setTypeRight = location[0] + view.width
        }
        onView(withContentDescription("Set 1 weight")).check { view, _ ->
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            assertTrue(location[0] > setTypeRight)
        }
        onView(withContentDescription("Set 1 done")).check { view, _ ->
            val density = view.resources.displayMetrics.density
            val checkbox = view as CheckBox
            assertTrue(view.width >= (56 * density).toInt())
            assertTrue(view.height >= (56 * density).toInt())
            assertTrue((checkbox.buttonDrawable?.intrinsicWidth ?: 0) >= (40 * density).toInt())
        }
        val notificationManager = instrumentation.targetContext.getSystemService(NotificationManager::class.java)
        val activeNotification = notificationManager.activeNotifications.single().notification
        assertEquals("Back squat", activeNotification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Set 1 of 1", activeNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        onView(withContentDescription("Set 1 type: Normal")).perform(click())
        onView(withContentDescription("Set 1 type: Warm-up")).check(matches(isDisplayed()))
        onView(withContentDescription("Set 1 weight")).perform(replaceText("20"), closeSoftKeyboard())
        onView(withContentDescription("Set 1 Repetitions")).perform(replaceText("10"), closeSoftKeyboard())
        onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
        val restNotification = notificationManager.activeNotifications.single().notification
        assert(restNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Rest"))
        assertEquals(listOf("−15s", "+15s", "Skip"), restNotification.actions.map { it.title.toString() })
        restNotification.actions.single { it.title.toString() == "Skip" }.actionIntent.send()
        instrumentation.waitForIdleSync()
        val skippedNotification = notificationManager.activeNotifications.single().notification
        assert(!skippedNotification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Rest"))
        assertEquals(0, skippedNotification.actions?.size ?: 0)
        onView(withContentDescription("Rest timer overlay")).check(matches(not(isDisplayed())))
        onView(withText("Add set")).perform(scrollTo(), click())
        onView(withContentDescription("Set 2 weight")).check(matches(withText("20")))
        onView(withContentDescription("Set 2 Repetitions")).check(matches(withText("10")))
        onView(withText("Finish workout")).perform(scrollTo(), click())
        onView(withText("Finish and save")).perform(click())
        assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertEquals(0, notificationManager.activeNotifications.size)
        onView(withContentDescription("Rest timer overlay")).check(matches(not(isDisplayed())))
        onView(withContentDescription("${LocalDate.now()}, workout completed")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun exerciseSearch_matchesEveryPartialWordIgnoringCase() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)

        onView(withText(R.string.start_workout)).perform(click())
        onView(withText("Add exercise")).perform(scrollTo(), click())
        onView(withHint("Search name or muscle")).perform(replaceText("SqUa BACK"), closeSoftKeyboard())
        onView(withText("Add Back squat")).check(matches(isDisplayed()))

        activity.finish()
    }

    @Test
    fun addingExercise_keepsWorkoutScrollPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(withText(R.string.start_workout)).perform(click())
        onView(withText("Add exercise")).perform(scrollTo(), click())
        onView(withHint("Search name or muscle")).perform(typeText("squat"), closeSoftKeyboard())
        onView(withText("Add Back squat")).perform(scrollTo(), click())
        repeat(2) { onView(withText("Add set")).perform(scrollTo(), click()) }

        onView(withText("Add exercise")).perform(scrollTo(), click())
        onView(withHint("Search name or muscle")).perform(typeText("plank"), closeSoftKeyboard())
        onView(withText("Add Plank")).perform(scrollTo(), click())
        val plankAddSet = allOf(
            withText("Add set"),
            isDescendantOfA(allOf(
                isAssignableFrom(MaterialCardView::class.java),
                hasDescendant(withText("Plank")),
            )),
        )
        repeat(2) { onView(plankAddSet).perform(scrollTo(), click()) }

        var scrollBeforeAdd = 0
        onView(withText("Add exercise")).perform(scrollTo())
        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ ->
            scrollBeforeAdd = view.scrollY
            assertTrue(scrollBeforeAdd > 0)
        }
        onView(withText("Add exercise")).perform(click())
        onView(withHint("Search name or muscle")).perform(typeText("dumbbell curl"), closeSoftKeyboard())
        onView(withText("Add Dumbbell curl")).perform(scrollTo(), click())

        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ ->
            assertEquals(scrollBeforeAdd, view.scrollY)
        }
    }

    @Test
    fun finishingWorkoutWithNoDoneSets_discardsWithoutConfirmation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.routines.add(Routine(
            name = "Strength",
            exercises = mutableListOf(RoutineExercise(
                exerciseId = "bench-press",
                plannedSets = MutableList(3) { PlannedSet(weightKg = 60.0, reps = 5) },
            )),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(withText("Start routine")).perform(scrollTo(), click())
        onView(withText("Finish workout")).perform(scrollTo(), click())

        onView(withText(R.string.start_workout)).check(matches(isDisplayed()))
        val savedData = LofitRepository(instrumentation.targetContext).data
        assertEquals(null, savedData.activeWorkout)
        assertEquals(0, savedData.history.size)
    }

    @Test
    fun routineSetType_isEditableAndUsedForWorkout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.routines.add(Routine(
            name = "Strength",
            exercises = mutableListOf(RoutineExercise(
                exerciseId = "bench-press",
                plannedSets = MutableList(3) { PlannedSet(weightKg = 60.0, reps = 5) },
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
        onView(withContentDescription("Set 1 type: Warm-up")).perform(click())
        onView(withContentDescription("Set 1 type: Failure")).perform(click())
        onView(withContentDescription("Set 1 type: Normal")).perform(click())
        onView(withContentDescription("Set 1 type: Warm-up")).perform(click())
        onView(withContentDescription("Set 1 type: Failure")).check(matches(isDisplayed()))
        onView(withText("Save routine")).perform(scrollTo(), click())
        onView(withText("Start")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 type: Failure")).check(matches(isDisplayed()))
        onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
        onView(withContentDescription("Set 2 row")).perform(scrollTo(), swipeLeft())
        onView(withContentDescription("Delete set 2 and all below")).perform(click())
        onView(withContentDescription("Set 1 row")).perform(swipeLeft())
        onView(withContentDescription("Delete set 1")).perform(click())
    }

    @Test
    fun workoutSetDelete_keepsSurvivingRowsMounted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.activeWorkout = WorkoutSession(
            name = "Swipe actions",
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "bench-press",
                plannedSets = MutableList(4) { PlannedSet(weightKg = 60.0, reps = 5) },
            )),
        )
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        val page = arrayOfNulls<View>(1)
        val survivingRow = arrayOfNulls<View>(1)
        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> page[0] = view }
        onView(withContentDescription("Set 2 row")).check { view, _ -> survivingRow[0] = view }
        onView(withContentDescription("Set 1 row")).perform(swipeLeft())
        onView(withContentDescription("Delete set 1")).check(matches(isDisplayed())).perform(click())
        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> assertSame(page[0], view) }
        onView(withContentDescription("Set 1 row")).check { view, _ -> assertSame(survivingRow[0], view) }
        assertEquals(3, LofitRepository(instrumentation.targetContext).data.activeWorkout
            ?.exercises?.single()?.plannedSets?.size)

        onView(withContentDescription("Set 2 row")).perform(swipeLeft())
        onView(withContentDescription("Delete set 2 and all below")).check(matches(isDisplayed())).perform(click())
        onView(withContentDescription("Set 1 row")).check { view, _ -> assertSame(survivingRow[0], view) }
        assertEquals(1, LofitRepository(instrumentation.targetContext).data.activeWorkout
            ?.exercises?.single()?.plannedSets?.size)
    }

    @Test
    fun routineSetDelete_keepsSurvivingRowsMounted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.routines.add(Routine(
            name = "No flicker",
            exercises = mutableListOf(RoutineExercise(
                exerciseId = "bench-press",
                plannedSets = MutableList(4) { PlannedSet(weightKg = 60.0, reps = 5) },
            )),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_routines)).perform(click())
        onView(withText("Edit")).perform(click())
        val page = arrayOfNulls<View>(1)
        val survivingRow = arrayOfNulls<View>(1)
        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> page[0] = view }
        onView(withContentDescription("Set 2 row")).check { view, _ -> survivingRow[0] = view }
        onView(withContentDescription("Set 1 row")).perform(swipeLeft())
        onView(withContentDescription("Delete set 1")).perform(click())

        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> assertSame(page[0], view) }
        onView(withContentDescription("Set 1 row")).check { view, _ -> assertSame(survivingRow[0], view) }
        onView(withContentDescription("Set 2 row")).perform(swipeLeft())
        onView(withContentDescription("Delete set 2 and all below")).perform(click())
        onView(withContentDescription("Set 1 row")).check { view, _ -> assertSame(survivingRow[0], view) }
    }

    @Test
    fun workoutExerciseMoveAndDelete_keepCardsMounted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.activeWorkout = WorkoutSession(
            name = "No exercise flicker",
            exercises = mutableListOf(
                WorkoutExercise(exerciseId = "bench-press"),
                WorkoutExercise(exerciseId = "plank"),
                WorkoutExercise(exerciseId = "back-squat"),
            ),
        )
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        val page = arrayOfNulls<View>(1)
        val survivingHeading = arrayOfNulls<View>(1)
        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> page[0] = view }
        onView(withText("Plank")).check { view, _ -> survivingHeading[0] = view }
        onView(withContentDescription("Move Plank up")).perform(click())
        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> assertSame(page[0], view) }
        onView(withText("Plank")).check { view, _ -> assertSame(survivingHeading[0], view) }
        onView(withContentDescription("Move Plank down")).perform(click())
        onView(withText("Plank")).check { view, _ -> assertSame(survivingHeading[0], view) }
        onView(withContentDescription("Remove Bench press")).perform(click())

        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> assertSame(page[0], view) }
        onView(withText("Plank")).check { view, _ -> assertSame(survivingHeading[0], view) }
    }

    @Test
    fun routineExerciseMoveAndDelete_keepCardsMounted() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.routines.add(Routine(
            name = "No exercise flicker",
            exercises = mutableListOf(
                RoutineExercise(exerciseId = "bench-press"),
                RoutineExercise(exerciseId = "plank"),
                RoutineExercise(exerciseId = "back-squat"),
            ),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_routines)).perform(click())
        onView(withText("Edit")).perform(click())
        val page = arrayOfNulls<View>(1)
        val survivingHeading = arrayOfNulls<View>(1)
        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> page[0] = view }
        onView(withText("Plank")).check { view, _ -> survivingHeading[0] = view }
        onView(withContentDescription("Move Plank up")).perform(click())
        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> assertSame(page[0], view) }
        onView(withText("Plank")).check { view, _ -> assertSame(survivingHeading[0], view) }
        onView(withContentDescription("Move Plank down")).perform(click())
        onView(withText("Plank")).check { view, _ -> assertSame(survivingHeading[0], view) }
        onView(withContentDescription("Remove Bench press")).perform(click())

        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ -> assertSame(page[0], view) }
        onView(withText("Plank")).check { view, _ -> assertSame(survivingHeading[0], view) }
    }

    @Test
    fun startingRoutine_usesLastKnownSetsForEachExercise() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.history.add(WorkoutSession(
            name = "Previous",
            startedAt = 100,
            completedAt = 200,
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "bench-press",
                sets = mutableListOf(
                    LoggedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10),
                    LoggedSet(type = SetType.NORMAL, weightKg = 67.5, reps = 6),
                ),
            )),
        ))
        repository.data.routines.add(Routine(
            name = "Strength",
            exercises = mutableListOf(RoutineExercise(
                exerciseId = "bench-press",
                plannedSets = MutableList(3) { PlannedSet(weightKg = 60.0, reps = 5) },
            )),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_routines)).perform(click())
        onView(withText("Start")).perform(scrollTo(), click())

        val plannedSets = LofitRepository(instrumentation.targetContext).data.activeWorkout
            ?.exercises?.single()?.plannedSets!!
        assertEquals(2, plannedSets.size)
        assertEquals(listOf(20.0, 67.5), plannedSets.map { it.weightKg })
        assertEquals(listOf(10, 6), plannedSets.map { it.reps })
        assertEquals(listOf(SetType.WARMUP, SetType.NORMAL), plannedSets.map { it.type })
    }

    @Test
    fun records_listsHighestWeightAndLongestHold() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.history.add(WorkoutSession(
            name = "Records",
            completedAt = 200,
            exercises = mutableListOf(
                WorkoutExercise(
                    exerciseId = "bench-press",
                    sets = mutableListOf(
                        LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 8),
                        LoggedSet(type = SetType.NORMAL, weightKg = 82.5, reps = 2),
                    ),
                ),
                WorkoutExercise(
                    exerciseId = "plank",
                    sets = mutableListOf(
                        LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 30),
                        LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 65),
                    ),
                ),
            ),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_records)).perform(click())

        onView(withText("Your best logged set for each exercise.")).check(matches(isDisplayed()))
        onView(withText("82.5 kg")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("65s")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun historyEntry_canCreatePrefilledRoutine() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.history.add(WorkoutSession(
            name = "Push day",
            completedAt = 200,
            defaultRestSeconds = 120,
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "bench-press",
                restSecondsOverride = 75,
                sets = mutableListOf(
                    LoggedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10),
                    LoggedSet(type = SetType.NORMAL, weightKg = 67.5, reps = 6),
                ),
            )),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_history)).perform(click())
        onView(withContentDescription("Create routine from Push day")).perform(scrollTo(), click())

        onView(withText("New routine")).check(matches(isDisplayed()))
        onView(withHint("Routine name")).check(matches(withText("Push day")))
        onView(withContentDescription("Set 1 weight")).check(matches(withText("20")))
        onView(withContentDescription("Set 2 weight")).check(matches(withText("67.5")))
        onView(withText("Add Exercise")).perform(scrollTo())
        var exerciseCardBottom = 0
        onView(withText("Bench press")).check { view, _ ->
            var ancestor = view.parent
            while (ancestor !is MaterialCardView) ancestor = ancestor.parent
            val cardLocation = IntArray(2)
            ancestor.getLocationOnScreen(cardLocation)
            exerciseCardBottom = cardLocation[1] + ancestor.height
        }
        onView(withText("Add Exercise")).check { view, _ ->
            val buttonLocation = IntArray(2)
            view.getLocationOnScreen(buttonLocation)
            assertTrue(buttonLocation[1] >= exerciseCardBottom)
        }
        onView(withContentDescription("Routine actions separator")).check(matches(isDisplayed()))
        onView(withText("Save routine")).perform(scrollTo(), click())

        val routine = LofitRepository(instrumentation.targetContext).data.routines.single()
        assertEquals("Push day", routine.name)
        assertEquals(120, routine.defaultRestSeconds)
        assertEquals(75, routine.exercises.single().restSecondsOverride)
        assertEquals(listOf(20.0, 67.5), routine.exercises.single().plannedSets.map { it.weightKg })
        assertEquals(listOf(10, 6), routine.exercises.single().plannedSets.map { it.reps })
    }

    @Test
    fun historyEntry_canContinueFinishedWorkout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        val plannedSet = PlannedSet(weightKg = 67.5, reps = 6)
        val workout = WorkoutSession(
            name = "Push day",
            startedAt = 100,
            completedAt = 200,
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "bench-press",
                sets = mutableListOf(LoggedSet(
                    type = SetType.NORMAL,
                    weightKg = 67.5,
                    reps = 6,
                    plannedSetId = plannedSet.id,
                )),
                plannedSets = mutableListOf(plannedSet),
            )),
        )
        repository.data.history.add(workout)
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_history)).perform(click())
        onView(withContentDescription("Continue Push day")).perform(scrollTo(), click())

        onView(withText("Workout in progress")).check(matches(isDisplayed()))
        onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
        onView(withContentDescription("Set 1 weight")).check(matches(withText("67.5")))
        val savedData = LofitRepository(instrumentation.targetContext).data
        assertEquals(workout.id, savedData.activeWorkout?.id)
        assertEquals(null, savedData.activeWorkout?.completedAt)
        assertEquals(0, savedData.history.size)
    }

    @Test
    fun holdTimer_runsInsideWorkoutSetRow() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.routines.add(Routine(
            name = "Hold",
            exercises = mutableListOf(RoutineExercise(
                exerciseId = "plank",
                plannedSets = mutableListOf(PlannedSet(holdSeconds = 45)),
            )),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_routines)).perform(click())
        onView(withText("Start")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 Target time in seconds")).check(matches(withText("45")))
        onView(withContentDescription("Set 1 Active time in seconds")).check(matches(withText("0")))
        onView(withContentDescription("Start set 1 hold")).perform(scrollTo(), click())
        onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
        onView(withContentDescription("Stop set 1 hold")).perform(click())
        onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
        onView(withContentDescription("Set 1 Target time in seconds")).check(matches(withText("45")))
        onView(withContentDescription("Set 1 Active time in seconds"))
            .check(matches(isEnabled()))
            .perform(replaceText("12"), closeSoftKeyboard())
            .check(matches(withText("12")))
        assertEquals(12, LofitRepository(instrumentation.targetContext).data.activeWorkout
            ?.exercises?.single()?.plannedSets?.single()?.actualHoldSeconds)
    }

    @Test
    fun holdTimer_reachingTargetCompletesSetButKeepsRunning() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.routines.add(Routine(
            name = "Short hold",
            exercises = mutableListOf(RoutineExercise(
                exerciseId = "plank",
                plannedSets = mutableListOf(PlannedSet(holdSeconds = 1)),
            )),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_routines)).perform(click())
        onView(withText("Start")).perform(scrollTo(), click())
        onView(withContentDescription("Start set 1 hold")).perform(scrollTo(), click())
        onView(withContentDescription("Stop set 1 hold")).perform(waitFor(1_300))
        onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
        onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed())).perform(click())
        onView(withContentDescription("Set 1 Active time in seconds")).check(matches(isEnabled()))
        val savedSet = LofitRepository(instrumentation.targetContext).data.activeWorkout
            ?.exercises?.single()?.sets?.single()
        assert(savedSet != null && savedSet.holdSeconds >= 1)
        onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
        onView(withContentDescription("Start set 1 hold")).perform(click())
        onView(withContentDescription("Rest timer overlay")).check(matches(not(isDisplayed())))
        onView(withContentDescription("Stop set 1 hold")).perform(click())
    }

    private fun waitFor(milliseconds: Long) = object : ViewAction {
        override fun getDescription() = "wait for $milliseconds milliseconds"
        override fun getConstraints(): Matcher<View> = isDisplayed()
        override fun perform(uiController: UiController, view: View) {
            uiController.loopMainThreadForAtLeast(milliseconds)
        }
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
