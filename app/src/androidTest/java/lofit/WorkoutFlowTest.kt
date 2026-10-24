package lofit

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.health.connect.HealthPermissions
import android.view.WindowManager
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.pressBackUnconditionally
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.swipeLeft
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isSelected
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.card.MaterialCardView
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ScrollView
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
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
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, HealthPermissions.READ_WEIGHT)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, HealthPermissions.READ_HEART_RATE)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, HealthPermissions.READ_HEALTH_DATA_HISTORY)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        context
            .getSharedPreferences("lofit-data", 0)
            .edit()
            .clear()
            .commit()
        LofitRepository(context).apply {
            data.bodyMetricsEnabled = false
            data.onboardingComplete = true
            save()
        }
    }

    @Test
    fun onboarding_femaleAndHealthDeclinedPersistAcrossRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        LofitRepository(context).apply { data.bodyMetricsEnabled = null; data.onboardingComplete = false; save() }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)

        onView(withText("Welcome to Lofit")).check(matches(isDisplayed()))
        onView(withText("Biological gender")).check(matches(isDisplayed()))
        onView(withText("Female")).perform(click())
        onView(withContentDescription("Android health integration")).check(matches(not(isChecked())))
        onView(withText("Get started")).perform(click())
        assertEquals(BiologicalGender.FEMALE, LofitRepository(context).data.biologicalGender)
        assertTrue(LofitRepository(context).data.onboardingComplete)
        assertEquals(false, LofitRepository(context).data.bodyMetricsEnabled)
        instrumentation.runOnMainSync { activity.finish() }

        val restarted = instrumentation.startActivitySync(intent)
        onView(withText("Welcome to Lofit")).check(doesNotExist())
        onView(withText(R.string.start_workout)).perform(click())
        assertEquals(false, LofitRepository(context).data.bodyMetricsEnabled)
        assertEquals(null, LofitRepository(context).data.activeWorkout!!.bodyWeightKg)
        instrumentation.runOnMainSync { restarted.finish() }
    }

    @Test
    fun onboarding_healthIntegrationCanBeEnabled() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        LofitRepository(context).apply { data.bodyMetricsEnabled = null; data.onboardingComplete = false; save() }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        onView(withContentDescription("Android health integration")).perform(click())
        assertEquals(null, LofitRepository(context).data.bodyMetricsEnabled)
        onView(withText("Get started")).perform(click())
        assertEquals(true, LofitRepository(context).data.bodyMetricsEnabled)
        onView(withText("Welcome to Lofit")).check(doesNotExist())
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun muscleMaps_usePlannedRoutineSetsAndOnlyLoggedHistorySets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        LofitRepository(context).apply {
            data.routines.add(Routine(name = "Map routine", exercises = mutableListOf(
                RoutineExercise(exerciseId = "bench-press", plannedSets = MutableList(8) { PlannedSet() }),
            )))
            data.history.add(WorkoutSession(
                name = "Map workout", completedAt = 200,
                exercises = mutableListOf(
                    WorkoutExercise(exerciseId = "bench-press",
                        plannedSets = MutableList(8) { PlannedSet() },
                        sets = MutableList(4) { LoggedSet(type = SetType.NORMAL, weightKg = 20.0, reps = 8) }),
                    WorkoutExercise(exerciseId = "fedb-barbell-curl",
                        plannedSets = MutableList(8) { PlannedSet() }),
                ),
            ))
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            fun checkMaps(chest: Int, triceps: Int) {
                onView(withContentDescription("Front muscle map")).perform(scrollTo()).check { view, _ ->
                    val state = view.stateDescription.toString()
                    assertTrue(state, state.contains("Chest $chest%"))
                    assertFalse(state, state.contains("Biceps"))
                }
                onView(withContentDescription("Back muscle map")).perform(scrollTo()).check { view, _ ->
                    val state = view.stateDescription.toString()
                    assertTrue(state, state.contains("Triceps $triceps%"))
                }
            }
            checkMaps(chest = 100, triceps = 25)
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(allOf(withText(R.string.nav_routines), isClickable())).perform(click())
            checkMaps(chest = 100, triceps = 25)
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(withText(R.string.nav_history)).perform(click())
            checkMaps(chest = 50, triceps = 13)
        }
    }

    @Test
    fun settings_genderChangesBothMuscleMapsAndPersistsAcrossRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        LofitRepository(context).apply {
            data.history.add(WorkoutSession(
                name = "Map workout", completedAt = 200,
                exercises = mutableListOf(WorkoutExercise(exerciseId = "bench-press")),
            ))
            save()
        }
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)

        fun checkMaps(gender: BiologicalGender) {
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(withText(R.string.nav_history)).perform(click())
            listOf("Front", "Back").forEach { side ->
                onView(withContentDescription("$side muscle map")).perform(scrollTo()).check { view, _ ->
                    assertEquals(gender, (view as MuscleMapView).biologicalGender)
                    assertTrue(view.stateDescription?.startsWith(gender.label) == true)
                }
            }
        }

        checkMaps(BiologicalGender.MALE)
        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_settings)).perform(click())
        onView(withText("Female")).perform(click()).check(matches(isChecked()))
        assertEquals(BiologicalGender.FEMALE, LofitRepository(context).data.biologicalGender)
        checkMaps(BiologicalGender.FEMALE)
        instrumentation.runOnMainSync { activity.finish() }

        val restarted = instrumentation.startActivitySync(intent)
        onView(withText("Welcome to Lofit")).check(doesNotExist())
        checkMaps(BiologicalGender.FEMALE)
        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_settings)).perform(click())
        onView(withText("Female")).check(matches(isChecked()))
        onView(withText("Male")).perform(click())
        checkMaps(BiologicalGender.MALE)
        instrumentation.runOnMainSync { restarted.finish() }
    }

    @Test
    fun settings_manageHealthAccessOpensHealthConnectAndReturnsToSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(withText(R.string.nav_settings)).perform(click())
            onView(withText("Manage health access")).perform(scrollTo(), click())

            val automation = instrumentation.uiAutomation
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (automation.rootInActiveWindow?.packageName?.toString()?.endsWith(".healthconnect.controller") != true &&
                android.os.SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
            }
            assertTrue("Health Connect should open outside Lofit",
                automation.rootInActiveWindow?.packageName?.toString()?.endsWith(".healthconnect.controller") == true)

            automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            onView(withText("Manage health access")).check(matches(isDisplayed()))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun settings_bodyMetricsCanBeEnabledAndDisabledAfterDeclining() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_settings)).perform(click())
        onView(withContentDescription("Android health integration"))
            .check(matches(not(isChecked())))
            .perform(click())
            .check(matches(isChecked()))
        assertEquals(true, LofitRepository(context).data.bodyMetricsEnabled)
        onView(withContentDescription("Android health integration"))
            .perform(click())
            .check(matches(not(isChecked())))
        assertEquals(false, LofitRepository(context).data.bodyMetricsEnabled)
        instrumentation.runOnMainSync { activity.finish() }

        val restarted = instrumentation.startActivitySync(intent)
        onView(withText("Welcome to Lofit")).check(doesNotExist())
        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_settings)).perform(click())
        onView(withContentDescription("Android health integration")).check(matches(not(isChecked())))
        instrumentation.runOnMainSync { restarted.finish() }
    }

    @Test
    fun navigation_eachTabReturnsHomeThenBackLeavesAndWorksAfterReopening() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val activity = instrumentation.startActivitySync(intent)
        try {
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(withText(R.string.nav_workout)).check(matches(withText("Home"))).perform(click())
            listOf(R.string.nav_routines, R.string.nav_exercises, R.string.nav_records,
                R.string.nav_history, R.string.nav_settings).forEach { tab ->
                onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
                onView(allOf(withText(tab), isClickable())).perform(click())
                pressBack()
                onView(withText("Ready to move?")).check(matches(isDisplayed()))
            }

            pressBackUnconditionally()
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
            instrumentation.runOnMainSync { assertFalse(activity.hasWindowFocus()) }

            context.startActivity(intent)
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(withText(R.string.nav_settings)).perform(click())
            pressBack()
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun navigation_editorBackReturnsToRoutinesThenHome() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(allOf(withText(R.string.nav_routines), isClickable())).perform(click())
            onView(withText("Create routine")).perform(click())
            onView(withHint("Routine name")).perform(replaceText("Unsaved draft"), closeSoftKeyboard())
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(allOf(withText(R.string.nav_exercises), isClickable())).perform(click())
            pressBack()
            onView(withHint("Routine name")).check(matches(withText("Unsaved draft")))
            onView(withText("Add Exercise")).perform(scrollTo(), click())
            pressBack()
            onView(withHint("Routine name")).check(matches(withText("Unsaved draft")))
            pressBack()
            onView(withText("Create routine")).check(matches(isDisplayed()))
            assertTrue(LofitRepository(context).data.routines.isEmpty())
            pressBack()
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun navigation_backReturnsToPreviousTab() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(allOf(withText(R.string.nav_routines), isClickable())).perform(click())
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(allOf(withText(R.string.nav_exercises), isClickable())).perform(click())
            pressBack()
            onView(withText("Create routine")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun navigation_privacyBackReturnsToSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(withText(R.string.nav_settings)).perform(click())
            onView(withText("Health data privacy")).perform(scrollTo(), click())
            pressBack()
            onView(withText("Health data privacy")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun home_activeWorkoutTileReopensSavedSetsAndDisappearsAfterFinishing() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        LofitRepository(context).apply {
            data.activeWorkout = WorkoutSession(
                name = "Live workout",
                exercises = mutableListOf(WorkoutExercise(
                    exerciseId = "bench-press",
                    plannedSets = mutableListOf(PlannedSet(weightKg = 60.0, reps = 8)),
                )),
            )
            save()
        }
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        try {
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
            onView(withText("Active workout")).check(matches(isDisplayed()))
            onView(withText("Resume workout")).check(matches(isDisplayed())).check(matches(isClickable()))
            onView(withText(R.string.start_workout)).check(doesNotExist())
            onView(withText("Finish workout")).check(doesNotExist())
            onView(withContentDescription("Open active workout")).perform(click())
            onView(withContentDescription("Set 1 Repetitions")).perform(replaceText("12"), closeSoftKeyboard())
            onView(withContentDescription("Set 1 done")).perform(click())
            val storedState = context.getSharedPreferences("lofit-data", 0).getString("state", null)

            pressBack()
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
            onView(withContentDescription("Open active workout")).perform(click())
            onView(withContentDescription("Set 1 Repetitions")).check(matches(withText("12")))
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            assertEquals(storedState, context.getSharedPreferences("lofit-data", 0).getString("state", null))

            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(withText(R.string.nav_workout)).perform(click())
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
            val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single()
            notification.notification.contentIntent.send()
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Finish and save")).perform(click())
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
            onView(withContentDescription("Open active workout")).check(doesNotExist())
            onView(withText(R.string.start_workout)).perform(scrollTo()).check(matches(isDisplayed()))
            assertEquals(null, LofitRepository(context).data.activeWorkout)
            assertEquals(12, LofitRepository(context).data.history.single().exercises.single().sets.single().reps)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun home_leavingNewWorkoutShowsTileThenBackLeavesApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val activity = instrumentation.startActivitySync(intent)
        try {
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Add exercise")).check(matches(isDisplayed()))
            val workoutId = LofitRepository(context).data.activeWorkout!!.id
            pressBack()
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
            onView(withContentDescription("Open active workout")).check(matches(isDisplayed()))
            pressBackUnconditionally()
            instrumentation.uiAutomation.waitForIdle(100, 5_000)
            instrumentation.runOnMainSync { assertFalse(activity.hasWindowFocus()) }

            context.startActivity(intent)
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
            onView(withContentDescription("Open active workout")).perform(waitFor(500), click())
            onView(withText("Add exercise")).check(matches(isDisplayed()))
            assertEquals(workoutId, LofitRepository(context).data.activeWorkout!!.id)
            onView(withText("Discard workout")).perform(scrollTo(), click())
            onView(withText("Ready to move?")).check(matches(isDisplayed()))
            onView(withContentDescription("Open active workout")).check(doesNotExist())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun home_placesStartWorkoutInRoutinesSection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        val routinesTitle = arrayOfNulls<View>(1)
        val startButton = arrayOfNulls<View>(1)
        onView(allOf(withText("Routines"), isDisplayed())).check { view, _ -> routinesTitle[0] = view }
        onView(withText(R.string.start_workout)).check { view, _ -> startButton[0] = view }

        val parent = routinesTitle[0]?.parent as ViewGroup
        assertSame(parent, startButton[0]?.parent)
        assertEquals(parent.indexOfChild(routinesTitle[0]) + 1, parent.indexOfChild(startButton[0]))
    }

    @Test
    fun workoutTogether_scanCanBeCancelledAndLiveWorkoutCanBeShared() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            onView(withText("Workout Together")).perform(scrollTo(), click())
            onView(withText("Scanning for nearby workouts…")).check(matches(isDisplayed()))
            onView(withText("Cancel")).perform(click())
            onView(withText(R.string.start_workout)).perform(scrollTo(), click())
            onView(withText("Share workout")).perform(scrollTo(), click())
            onView(withText("Stop sharing")).check(matches(isDisplayed())).perform(click())
            onView(withText("Add exercise")).check(matches(isDisplayed()))
            assertEquals("Open workout", LofitRepository(context).data.activeWorkout?.name)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun workoutTogether_filePickerImportsExactPlan() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val incoming = workoutFileIntent("Shared file plan")
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            addDataType("*/*")
        },
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,
                Intent().setData(incoming.data)), true)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            onView(withContentDescription("Workout Together options")).perform(scrollTo(), click())
            onView(withText("Bluetooth")).check(matches(isDisplayed()))
            onView(withText("Open .lofit file")).perform(click())
            awaitWindowText("Shared file plan")
            assertEquals(1, monitor.hits)
            onView(withText("Shared file plan")).check(matches(isDisplayed()))
            onView(withText("Start workout")).perform(click())
            val saved = LofitRepository(context).data.activeWorkout!!
            assertEquals("Shared file plan", saved.name)
            assertEquals(45, saved.defaultRestSeconds)
            assertEquals(3, saved.exercises.single().plannedSets.size)
            assertEquals(42.5, saved.exercises.single().plannedSets.first().weightKg, 0.0)
            assertEquals(6, saved.exercises.single().plannedSets.first().reps)
            assertTrue(saved.exercises.single().sets.isEmpty())
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun workoutTogether_coldAndWarmFileIntentsKeepActiveWorkout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        LofitRepository(context).apply { data.activeWorkout = WorkoutSession(name = "Mine"); save() }
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        context.startActivity(workoutFileIntent("Cold file plan").setComponent(null).setPackage(context.packageName))
        val activity = monitor.waitForActivityWithTimeout(5_000)!!
        instrumentation.removeMonitor(monitor)
        try {
            awaitWindowText("Cold file plan")
            onView(withText("Cold file plan")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Cancel")).inRoot(isDialog()).perform(click())
            val warmIntent = workoutFileIntent("Warm file plan").setComponent(null).setPackage(context.packageName)
            instrumentation.runOnMainSync { activity.startActivity(warmIntent) }
            awaitWindowText("Warm file plan")
            onView(withText("Warm file plan")).inRoot(isDialog()).check(matches(isDisplayed()))
            assertEquals(null, activity.intent.data)
            assertFalse(activity.isFinishing)
            onView(withText("Start workout")).inRoot(isDialog()).perform(click())
            assertEquals("Mine", LofitRepository(context).data.activeWorkout?.name)
            onView(withText("Cancel")).inRoot(isDialog()).perform(click())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun workoutTogether_fileShareOpensSystemChooser() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER),
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null), true)
        try {
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                onView(withText(R.string.start_workout)).perform(scrollTo(), click())
                val workoutId = LofitRepository(context).data.activeWorkout!!.id
                onView(withContentDescription("Share workout options")).perform(scrollTo(), click())
                onView(withText("Bluetooth")).check(matches(isDisplayed()))
                onView(withText("Share .lofit file")).perform(click())
                assertEquals(1, monitor.hits)
                scenario.recreate()
                onView(withText("Share workout")).check(matches(isDisplayed()))
                assertEquals(workoutId, LofitRepository(context).data.activeWorkout?.id)
                onView(withText("Share workout")).perform(click())
                onView(withText("Stop sharing")).check(matches(isDisplayed()))
                scenario.recreate()
                onView(withText("Share workout")).check(matches(isDisplayed()))
                assertEquals(workoutId, LofitRepository(context).data.activeWorkout?.id)
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    private fun awaitWindowText(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val until = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < until) {
            if (automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.isNotEmpty() == true) return
            android.os.SystemClock.sleep(50)
        }
        throw AssertionError("Window did not show $text")
    }

    private fun workoutFileIntent(name: String): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val movement = DefaultData.exercises().first { it.id == "bench-press" }
        val workout = WorkoutSession(name = name, defaultRestSeconds = 45,
            exercises = mutableListOf(WorkoutExercise(exerciseId = movement.id,
                plannedSets = MutableList(3) { PlannedSet(weightKg = 42.5, reps = 6) })))
        val share = WorkoutPlanFile.shareIntent(context, workout, listOf(movement))
        return Intent(Intent.ACTION_VIEW).setDataAndType(
            share.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java), WorkoutPlanFile.MIME_TYPE)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun calendar_loggedDayOpensReadOnlyWorkoutAndReturnsToSameMonth() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = LofitRepository(context)
        val day = LocalDate.now().minusMonths(1).withDayOfMonth(15)
        val completedAt = day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.data.history.add(WorkoutSession(
            name = "Calendar workout", startedAt = completedAt - 120_000, completedAt = completedAt,
            bodyWeightKg = 72.5,
            exercises = mutableListOf(loggedWorkoutExercise(
                exerciseId = "bench-press",
                sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 67.5, reps = 6)),
            )),
        ))
        repository.save()
        val storedState = context.getSharedPreferences("lofit-data", 0).getString("state", null)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        onView(withContentDescription("Previous month")).perform(click())
        onView(withContentDescription("${day.minusDays(1)}, no workout"))
            .check(matches(not(isClickable())))
        onView(withContentDescription("$day, workout completed"))
            .check(matches(isClickable())).perform(scrollTo(), click())
        onView(withText("Calendar workout")).check(matches(isDisplayed()))
        onView(withContentDescription("Workout duration")).check(matches(withText("2:00")))
        onView(withContentDescription("Workout body weight")).check(matches(withText("Body weight: 72.5 kg")))
        onView(withContentDescription("Set 1 weight")).perform(scrollTo(), click())
            .check(matches(withText("67.5")))
        onView(withContentDescription("Set 1 done"))
            .check(matches(isChecked())).check(matches(not(isEnabled())))
        onView(isAssignableFrom(android.widget.EditText::class.java)).check(doesNotExist())
        onView(withText("Finish workout")).check(doesNotExist())

        pressBack()
        onView(withContentDescription("Workout calendar")).check(matches(isDisplayed()))
        onView(withText(day.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))))
            .check(matches(isDisplayed()))
        assertEquals(storedState, context.getSharedPreferences("lofit-data", 0).getString("state", null))
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun calendar_multipleWorkoutsOnSameDayCanEachBeViewed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = LofitRepository(context)
        val day = LocalDate.now()
        listOf("Morning" to 9, "Evening" to 17).forEach { (name, hour) ->
            val completedAt = day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            repository.data.history.add(WorkoutSession(
                name = name, startedAt = completedAt - 60_000, completedAt = completedAt,
            ))
        }
        repository.save()
        val storedState = context.getSharedPreferences("lofit-data", 0).getString("state", null)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        listOf("Morning", "Evening").forEach { name ->
            onView(withContentDescription("$day, 2 workouts completed")).perform(scrollTo(), click())
            onView(withText(startsWith("$name · "))).perform(click())
            onView(withText(name)).check(matches(isDisplayed()))
            onView(withContentDescription("Workout duration")).check(matches(withText("1:00")))
            pressBack()
            onView(withContentDescription("Workout calendar")).check(matches(isDisplayed()))
        }
        assertEquals(storedState, context.getSharedPreferences("lofit-data", 0).getString("state", null))
        instrumentation.runOnMainSync { activity.finish() }
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
            assertTrue(view.width >= (48 * density).toInt())
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
        val notificationDeadline = android.os.SystemClock.uptimeMillis() + 5_000
        while (notificationManager.activeNotifications.single().notification
                .extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Rest") &&
            android.os.SystemClock.uptimeMillis() < notificationDeadline
        ) {
            android.os.SystemClock.sleep(50)
        }
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
    fun addedSet_preservesFractionalWeightInFieldsStorageAndVolume() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LofitRepository(context).apply {
            data.activeWorkout = WorkoutSession(
                name = "Fractional weights",
                defaultRestSeconds = 0,
                exercises = mutableListOf(WorkoutExercise(
                    exerciseId = "bench-press",
                    plannedSets = mutableListOf(PlannedSet(weightKg = 1.25, reps = 1)),
                )),
            )
            save()
        }
        val weightText = "1${java.text.DecimalFormatSymbols.getInstance().decimalSeparator}25"
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)).use { scenario ->
            onView(withContentDescription("Set 1 weight")).check(matches(withText(weightText)))
            onView(withText("Add set")).perform(scrollTo(), click())
            onView(withContentDescription("Set 2 weight")).check(matches(withText(weightText)))
                .perform(replaceText(weightText), closeSoftKeyboard())
            onView(withContentDescription("Set 2 Repetitions")).perform(replaceText("2"), closeSoftKeyboard())
            scenario.recreate()
            onView(withContentDescription("Set 1 weight")).check(matches(withText(weightText)))
            onView(withContentDescription("Set 2 weight")).check(matches(withText(weightText)))
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            onView(withContentDescription("Set 2 done")).perform(scrollTo(), click())

            val active = LofitRepository(context).data.activeWorkout!!
            assertEquals(listOf(1.25, 1.25), active.exercises.single().plannedSets.map { it.weightKg })
            assertEquals(listOf(1.25, 1.25), active.exercises.single().sets.map { it.weightKg })
            assertEquals(3.75, active.totalVolume(), 0.0)

            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Finish and save")).perform(click())
            assertEquals(3.75, LofitRepository(context).data.history.single().totalVolume(), 0.0)
            onView(withContentDescription("${LocalDate.now()}, workout completed")).perform(scrollTo(), click())
            val volumeText = "2 sets · 3${java.text.DecimalFormatSymbols.getInstance().decimalSeparator}75 kg volume"
            onView(withText(volumeText)).check(matches(isDisplayed()))
            onView(withContentDescription("Set 2 weight")).perform(scrollTo()).check(matches(withText(weightText)))
        }
    }

    @Test
    fun routineAddedSet_preservesFractionalWeight() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LofitRepository(context).apply {
            data.routines.add(Routine(
                name = "Fractional routine",
                exercises = mutableListOf(RoutineExercise(
                    exerciseId = "bench-press",
                    plannedSets = mutableListOf(PlannedSet(weightKg = 1.25, reps = 1)),
                )),
            ))
            save()
        }
        val weightText = "1${java.text.DecimalFormatSymbols.getInstance().decimalSeparator}25"
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)).use {
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(allOf(withText(R.string.nav_routines), isAssignableFrom(android.widget.Button::class.java))).perform(click())
            onView(withText("Edit")).perform(click())
            onView(withContentDescription("Set 1 weight")).check(matches(withText(weightText)))
            onView(withText("Add set")).perform(scrollTo(), click())
            onView(withContentDescription("Set 2 weight")).check(matches(withText(weightText)))
            onView(withText("Save routine")).perform(scrollTo(), click())
            val sets = LofitRepository(context).data.routines.single().exercises.single().plannedSets
            assertEquals(listOf(1.25, 1.25), sets.map { it.weightKg })
        }
    }

    @Test
    fun bodyweightExercise_skipsWeightColumnAndLogsRepetitions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.activeWorkout = WorkoutSession(
            name = "Bodyweight workout",
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "push-up",
                plannedSets = mutableListOf(PlannedSet(reps = 8)),
            )),
        )
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)
        onView(withContentDescription("Open active workout")).perform(click())

        onView(withContentDescription("Set 1 weight")).check(doesNotExist())
        onView(withContentDescription("Set 1 Repetitions")).perform(replaceText("12"), closeSoftKeyboard())
        onView(withContentDescription("Set 1 done")).perform(click())

        val loggedSet = LofitRepository(instrumentation.targetContext).data.activeWorkout!!
            .exercises.single().sets.single()
        assertEquals(0.0, loggedSet.weightKg, 0.001)
        assertEquals(12, loggedSet.reps)
    }

    @Test
    fun weightedBodyweightCopy_showsWeightColumn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.activeWorkout = WorkoutSession(
            name = "Weighted bodyweight workout",
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "weighted-push-up",
                plannedSets = mutableListOf(PlannedSet(weightKg = 10.0, reps = 8)),
            )),
        )
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)
        onView(withContentDescription("Open active workout")).perform(click())

        onView(withText("Weighted Push-up")).check(matches(isDisplayed()))
        onView(withContentDescription("Set 1 weight")).check(matches(withText("10")))
        onView(withContentDescription("Set 1 Repetitions")).check(matches(withText("8")))
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
        onView(withHint("Search name or muscle")).perform(replaceText("weighted push"), closeSoftKeyboard())
        onView(withText("Add Weighted Push-up")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Add Push-up")).check(doesNotExist())

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
        onView(allOf(withText(R.string.nav_routines), isAssignableFrom(android.widget.Button::class.java))).perform(click())
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
        onView(withContentDescription("Open active workout")).perform(click())

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
        onView(allOf(withText(R.string.nav_routines), isAssignableFrom(android.widget.Button::class.java))).perform(click())
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
        onView(withContentDescription("Open active workout")).perform(click())

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
        onView(allOf(withText(R.string.nav_routines), isAssignableFrom(android.widget.Button::class.java))).perform(click())
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
    fun startingRoutine_keepsEachExercisesSavedSetCountWhenHistoryDiffers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(name = "Old session", completedAt = 200,
            exercises = mutableListOf(
                loggedWorkoutExercise("bench-press", mutableListOf(LoggedSet(type = SetType.FAILURE,
                    weightKg = 90.0, reps = 1))),
                loggedWorkoutExercise("plank", MutableList(4) { LoggedSet(type = SetType.NORMAL,
                    weightKg = 0.0, holdSeconds = 20) }),
            )))
        repository.data.routines.add(Routine(name = "Saved prescription", defaultRestSeconds = 80,
            exercises = mutableListOf(
                RoutineExercise(exerciseId = "bench-press", restSecondsOverride = 45,
                    plannedSets = MutableList(3) { PlannedSet(weightKg = 60.0, reps = 5) }),
                RoutineExercise(exerciseId = "plank", restSecondsOverride = 20,
                    plannedSets = mutableListOf(PlannedSet(holdSeconds = 60))),
            )))
        repository.data.history[0] = repository.data.history[0].copy(sourceRoutine = repository.data.routines.single())
        repository.save()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            onView(withText("Start routine")).perform(scrollTo(), click())
            val workout = LofitRepository(context).data.activeWorkout!!
            assertEquals(listOf(3, 1), workout.exercises.map { it.plannedSets.size })
            assertEquals(listOf(45, 20), workout.exercises.map { it.restSecondsOverride })
            fun inCard(name: String) = isDescendantOfA(allOf(isAssignableFrom(MaterialCardView::class.java),
                hasDescendant(withText(name))))
            onView(allOf(withContentDescription("Set 3 Repetitions"), inCard("Bench press")))
                .check(matches(withText("1")))
            onView(allOf(withContentDescription("Set 1 Target time in seconds"), inCard("Plank")))
                .check(matches(withText("20")))
            onView(allOf(withContentDescription("Set 2 row"), inCard("Plank"))).check(doesNotExist())
            assertEquals(listOf(3, 1), LofitRepository(context).data.routines.single().exercises.map { it.plannedSets.size })
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun startingRoutine_usesLatestValuesWithSavedSetCount() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.history.add(WorkoutSession(
            name = "Previous",
            startedAt = 100,
            completedAt = 200,
            exercises = mutableListOf(loggedWorkoutExercise(
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
        repository.data.history[0] = repository.data.history[0].copy(sourceRoutine = repository.data.routines.single())
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(allOf(withText(R.string.nav_routines), isAssignableFrom(android.widget.Button::class.java))).perform(click())
        onView(withText("Start")).perform(scrollTo(), click())

        val plannedSets = LofitRepository(instrumentation.targetContext).data.activeWorkout
            ?.exercises?.single()?.plannedSets!!
        assertEquals(3, plannedSets.size)
        assertEquals(listOf(67.5, 67.5, 67.5), plannedSets.map { it.weightKg })
        assertEquals(listOf(6, 6, 6), plannedSets.map { it.reps })
        assertEquals(List(3) { SetType.NORMAL }, plannedSets.map { it.type })
        onView(withContentDescription("Set 3 Repetitions")).check(matches(withText("6")))
    }

    @Test
    fun recordTrend_opensFromRecordsAndWorkoutHistoryAndChangesSpan() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val zone = ZoneId.systemDefault()
        val now = java.time.ZonedDateTime.now(zone)
        fun logged(monthsAgo: Long, weight: Double, reps: Int = 5) = LoggedSet(
            type = SetType.NORMAL, weightKg = weight, reps = reps,
            completedAt = now.minusMonths(monthsAgo).toInstant().toEpochMilli(),
        )
        val first = logged(18, 40.0)
        val repository = LofitRepository(context)
        repository.data.history.add(WorkoutSession(
            name = "Trend workout", completedAt = now.toInstant().toEpochMilli(),
            exercises = mutableListOf(loggedWorkoutExercise("bench-press", mutableListOf(
                first, logged(6, 60.0), logged(0, 70.0), logged(0, 80.0),
                logged(0, 50.0, reps = 8), logged(0, 200.0, reps = 0), logged(0, 0.0, reps = 100),
            ))),
        ))
        repository.save()
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_records)).perform(click())
        val name = repository.data.exercises.first { it.id == "bench-press" }.name
        onView(withContentDescription("View $name trend")).perform(scrollTo(), click())

        onView(withContentDescription("1 year")).check(matches(isSelected()))
        onView(withContentDescription("Highest weight trend")).check { view, _ ->
            val graph = view as TrendGraphView
            assertEquals(now.toLocalDate().minusYears(1), Instant.ofEpochMilli(graph.startedAt).atZone(zone).toLocalDate())
            assertEquals(listOf(40.0, 60.0, 80.0), graph.series.points.map { it.value })
        }
        onView(withContentDescription("Highest set volume trend")).perform(scrollTo()).check { view, _ ->
            assertEquals(listOf(200.0, 300.0, 400.0), (view as TrendGraphView).series.points.map { it.value })
        }
        listOf("1 month" to 1L, "3 months" to 3L, "6 months" to 6L).forEach { (label, months) ->
            onView(withContentDescription(label)).perform(scrollTo(), click()).check(matches(isSelected()))
            onView(withContentDescription("Highest weight trend")).check { view, _ ->
                assertEquals(now.toLocalDate().minusMonths(months),
                    Instant.ofEpochMilli((view as TrendGraphView).startedAt).atZone(zone).toLocalDate())
            }
        }
        onView(withContentDescription("All time")).perform(click()).check(matches(isSelected()))
        onView(withContentDescription("Highest weight trend")).check { view, _ ->
            assertEquals(first.completedAt, (view as TrendGraphView).startedAt)
        }
        pressBack()
        onView(withText("Your best logged set for each exercise.")).check(matches(isDisplayed()))
        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_history)).perform(click())
        onView(withContentDescription("View Trend workout")).perform(scrollTo(), click())
        onView(withContentDescription("View $name trend")).perform(scrollTo(), click())
        onView(withContentDescription("1 year")).check(matches(isSelected()))
        pressBack()
        onView(withContentDescription("Workout duration")).check(matches(isDisplayed()))
        onView(withContentDescription("View $name trend")).perform(scrollTo(), click())
    }

    @Test
    fun records_listsHighestWeightLongestHoldAndSetVolume() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.history.add(WorkoutSession(
            name = "Records",
            completedAt = 200,
            exercises = mutableListOf(
                loggedWorkoutExercise(
                    exerciseId = "bench-press",
                    sets = mutableListOf(
                        LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 8),
                        LoggedSet(type = SetType.NORMAL, weightKg = 82.5, reps = 2),
                    ),
                ),
                loggedWorkoutExercise(
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
        onView(withText("480 kg")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Highest set volume")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("65s")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun completedSets_withoutEarlierWorkoutDoNotShowCrown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.activeWorkout = WorkoutSession(
            name = "Record attempt",
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "bench-press",
                plannedSets = mutableListOf(
                    PlannedSet(weightKg = 50.0, reps = 5),
                    PlannedSet(weightKg = 55.0, reps = 5),
                ),
            )),
        )
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)
        onView(withContentDescription("Open active workout")).perform(click())

        onView(withContentDescription("Set 1 record")).check(matches(not(isDisplayed())))
        onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 record")).check(matches(not(isDisplayed())))
        onView(withContentDescription("Set 2 done")).perform(scrollTo(), click())
        onView(withContentDescription("Set 2 record")).check(matches(not(isDisplayed())))
    }

    @Test
    fun completedRecordSet_withEarlierWorkoutShowsCrownImmediately() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.history.add(WorkoutSession(
            name = "Old record",
            completedAt = 100,
            exercises = mutableListOf(loggedWorkoutExercise(
                exerciseId = "bench-press",
                sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 5)),
            )),
        ))
        repository.data.activeWorkout = WorkoutSession(
            name = "Record attempt",
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "bench-press",
                plannedSets = mutableListOf(
                    PlannedSet(weightKg = 61.0, reps = 5),
                    PlannedSet(weightKg = 62.0, reps = 5),
                ),
            )),
        )
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)
        onView(withContentDescription("Open active workout")).perform(click())

        onView(withContentDescription("Set 1 record")).check(matches(not(isDisplayed())))
        onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 record")).check(matches(isDisplayed()))
        val doneLocation = IntArray(2)
        val crownLocation = IntArray(2)
        onView(withContentDescription("Set 1 done")).check { view, _ ->
            view.getLocationOnScreen(doneLocation)
            assertTrue(view.width <= (48 * view.resources.displayMetrics.density).toInt())
        }
        onView(withContentDescription("Set 1 record")).check { view, _ -> view.getLocationOnScreen(crownLocation) }
        assertTrue(doneLocation[0] < crownLocation[0])
        onView(withContentDescription("Set 2 done")).perform(scrollTo(), click())
        onView(withContentDescription("Set 1 record")).check(matches(not(isDisplayed())))
        onView(withContentDescription("Set 2 record")).check(matches(isDisplayed()))
    }

    @Test
    fun historyEntry_canCreatePrefilledRoutineWithEveryExercise() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        repository.data.history.add(WorkoutSession(
            name = "Push day",
            completedAt = 200,
            defaultRestSeconds = 120,
            exercises = mutableListOf(
                loggedWorkoutExercise(
                    exerciseId = "bench-press",
                    restSecondsOverride = 75,
                    sets = mutableListOf(
                        LoggedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10),
                        LoggedSet(type = SetType.NORMAL, weightKg = 67.5, reps = 6),
                    ),
                ),
                WorkoutExercise(
                    exerciseId = "plank",
                    plannedSets = mutableListOf(PlannedSet(holdSeconds = 45)),
                ),
            ),
        ))
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_history)).perform(click())
        onView(withContentDescription("Front muscle map")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withContentDescription("Back muscle map")).check(matches(isDisplayed()))
        onView(withText("Bench press")).check(doesNotExist())
        onView(withContentDescription("Create routine from Push day")).perform(scrollTo(), click())

        onView(withText("New routine")).check(matches(isDisplayed()))
        onView(withHint("Routine name")).check(matches(withText("Push day")))
        onView(withText("Plank")).perform(scrollTo()).check(matches(isDisplayed()))
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
        onView(withContentDescription("Routine actions separator")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Save routine")).perform(scrollTo(), click())

        val routine = LofitRepository(instrumentation.targetContext).data.routines.single()
        assertEquals("Push day", routine.name)
        assertEquals(120, routine.defaultRestSeconds)
        assertEquals(listOf("bench-press", "plank"), routine.exercises.map { it.exerciseId })
        assertEquals(75, routine.exercises[0].restSecondsOverride)
        assertEquals(listOf(20.0, 67.5), routine.exercises[0].plannedSets.map { it.weightKg })
        assertEquals(listOf(10, 6), routine.exercises[0].plannedSets.map { it.reps })
        assertEquals(listOf(45), routine.exercises[1].plannedSets.map { it.holdSeconds })
    }

    @Test
    fun historyEntry_showsFullReadOnlyWorkoutWhileAnotherWorkoutIsActive() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = LofitRepository(context)
        val benchSet = PlannedSet(weightKg = 60.0, reps = 8)
        val holdSet = PlannedSet(holdSeconds = 45)
        repository.data.exercises.add(Exercise(
            id = "single-leg", name = "Single leg", type = ExerciseType.REPETITIONS,
            asymmetric = true, primaryMuscle = "Quads", secondaryMuscles = "", custom = true,
            weighted = false,
        ))
        repository.data.activeWorkout = WorkoutSession(name = "Live workout")
        val workout = WorkoutSession(
            name = "Past workout", startedAt = 100_000, completedAt = 220_000,
            defaultRestSeconds = 90,
            exercises = mutableListOf(
                WorkoutExercise(
                    exerciseId = "bench-press", restSecondsOverride = 75,
                    plannedSets = mutableListOf(benchSet, PlannedSet(type = SetType.FAILURE, weightKg = 50.0, reps = 10)),
                    sets = mutableListOf(LoggedSet(
                        type = SetType.WARMUP, weightKg = 67.5, reps = 6, plannedSetId = benchSet.id,
                    )),
                ),
                WorkoutExercise(
                    exerciseId = "plank", plannedSets = mutableListOf(holdSet),
                    sets = mutableListOf(LoggedSet(
                        type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 42, plannedSetId = holdSet.id,
                    )),
                ),
                loggedWorkoutExercise(
                    exerciseId = "single-leg",
                    sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 0.0, reps = 12, side = Side.LEFT)),
                ),
            ),
        )
        repository.data.history.add(workout)
        repository.save()
        val storedState = context.getSharedPreferences("lofit-data", 0).getString("state", null)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_history)).perform(click())
        onView(withContentDescription("View Past workout")).perform(scrollTo(), click())

        onView(withContentDescription("Workout duration")).check(matches(withText("2:00")))
        onView(allOf(withText("Set Rest: 90s"), not(isDescendantOfA(isAssignableFrom(MaterialCardView::class.java)))))
            .check(matches(isDisplayed()))
        val benchCard = allOf(isAssignableFrom(MaterialCardView::class.java), hasDescendant(withText("Bench press")))
        val holdCard = allOf(isAssignableFrom(MaterialCardView::class.java), hasDescendant(withText("Plank")))
        fun benchField(description: String) = allOf(withContentDescription(description), isDescendantOfA(benchCard))
        fun holdField(description: String) = allOf(withContentDescription(description), isDescendantOfA(holdCard))
        onView(benchField("Set 1 weight")).perform(scrollTo(), click()).check(matches(withText("67.5")))
        onView(benchField("Set 1 Repetitions")).check(matches(withText("6")))
        onView(benchField("Set 1 type: Warm-up")).perform(click()).check(matches(withText("W")))
        onView(benchField("Set 1 done")).check(matches(isChecked())).check(matches(not(isEnabled())))
        onView(benchField("Set 2 type: Failure")).check(matches(withText("F")))
        onView(benchField("Set 2 done")).check(matches(not(isChecked())))
        onView(benchField("Set 2 weight")).check(matches(withText("50")))
        onView(withText("Set Rest: 75s")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(holdField("Set 1 Target time in seconds")).perform(scrollTo()).check(matches(withText("45")))
        onView(holdField("Set 1 Active time in seconds")).check(matches(withText("42")))
        onView(withContentDescription("Set 1 side")).perform(scrollTo(), click()).check(matches(withText("Left")))
        onView(allOf(withContentDescription("Set 1 Repetitions"), isDescendantOfA(
            allOf(isAssignableFrom(MaterialCardView::class.java), hasDescendant(withText("Single leg"))),
        ))).check(matches(withText("12")))
        onView(isAssignableFrom(android.widget.EditText::class.java)).check(doesNotExist())
        listOf("Add exercise", "Add set", "Finish workout", "Discard workout").forEach {
            onView(withText(it)).check(doesNotExist())
        }
        onView(withContentDescription("Start set 1 hold")).check(doesNotExist())
        onView(withContentDescription("Remove Bench press")).check(doesNotExist())
        onView(withContentDescription("Delete set 1")).check(doesNotExist())
        pressBack()
        onView(withContentDescription("View Past workout")).check(matches(isDisplayed()))
        assertEquals(storedState, context.getSharedPreferences("lofit-data", 0).getString("state", null))
        pressBack()
        onView(withText("Ready to move?")).check(matches(isDisplayed()))
        onView(withContentDescription("Open active workout")).check(matches(isDisplayed()))
        onView(withContentDescription("Open active workout")).perform(click())
        onView(withText("Live workout")).check(matches(isDisplayed()))
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun history_usesFirstStartAndSumsIntervalsAcrossMidnight() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = LofitRepository(context)
        val firstDay = LocalDate.now().minusDays(1)
        val startedAt = firstDay.atTime(23, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val resumedAt = firstDay.plusDays(1).atTime(1, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.data.history.add(WorkoutSession(
            name = "Two spans",
            intervals = mutableListOf(WorkoutInterval(startedAt, startedAt + 60_000), WorkoutInterval(resumedAt, resumedAt + 120_000)),
        ))
        repository.save()
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val dateText = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(startedAt))

        if (firstDay.month != LocalDate.now().month) {
            onView(withContentDescription("Previous month")).perform(click())
        }
        onView(withContentDescription("$firstDay, workout completed")).perform(scrollTo(), click())
        onView(withText(dateText)).check(matches(isDisplayed()))
        onView(withContentDescription("Workout duration")).check(matches(withText("3:00")))
        pressBack()
        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_history)).perform(click())
        onView(withText(dateText)).check(matches(isDisplayed()))
        onView(withText("0 sets · 0 kg volume · 3:00")).check(matches(isDisplayed()))
        onView(withContentDescription("View Two spans")).perform(scrollTo(), click())
        onView(withText(dateText)).check(matches(isDisplayed()))
        onView(withContentDescription("Workout duration")).check(matches(withText("3:00")))
        instrumentation.runOnMainSync { activity.finish() }
    }

    @Test
    fun historyEntry_canContinueFinishedWorkout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val repository = LofitRepository(instrumentation.targetContext)
        val completedAt = System.currentTimeMillis() - 60_000
        val plannedSet = PlannedSet(weightKg = 67.5, reps = 6)
        val workout = WorkoutSession(
            name = "Push day",
            startedAt = completedAt - 120_000,
            completedAt = completedAt,
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
        val olderWorkout = WorkoutSession(name = "Older workout", startedAt = 10, completedAt = 50)
        repository.data.history.add(olderWorkout)
        repository.save()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(intent)

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_history)).perform(click())
        onView(withContentDescription("Continue Older workout")).check(doesNotExist())
        onView(withContentDescription("View Older workout")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withContentDescription("Create routine from Older workout")).check(matches(isDisplayed()))
        onView(withContentDescription("Continue Push day")).perform(scrollTo(), click())

        onView(withText("Workout in progress")).check(matches(isDisplayed()))
        onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
        onView(withContentDescription("Set 1 weight")).check(matches(withText("67.5")))
        val savedData = LofitRepository(instrumentation.targetContext).data
        assertEquals(workout.id, savedData.activeWorkout?.id)
        assertEquals(null, savedData.activeWorkout?.completedAt)
        assertEquals(workout.startedAt, savedData.activeWorkout?.startedAt)
        assertEquals(2, savedData.activeWorkout?.intervals?.size)
        val notification = instrumentation.targetContext.getSystemService(NotificationManager::class.java)
            .activeNotifications.single().notification
        assertTrue(kotlin.math.abs(System.currentTimeMillis() - notification.`when` - 120_000) < 5_000)
        assertEquals(listOf(olderWorkout.id), savedData.history.map { it.id })

        onView(withText("Finish workout")).perform(scrollTo(), click())
        onView(withText("Finish and save")).perform(click())
        val finished = LofitRepository(instrumentation.targetContext).data.history.single { it.id == workout.id }
        assertEquals(workout.startedAt, finished.startedAt)
        assertEquals(workout.intervals.single(), finished.intervals.first())
        assertTrue(finished.intervals.all { it.endedAt != null })
        assertEquals(2, finished.intervals.size)
    }

    @Test
    fun historyEntry_cannotContinueYesterdayWorkout() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repository = LofitRepository(context)
        val completedAt = LocalDate.now().minusDays(1).atTime(23, 59)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val workout = WorkoutSession(name = "Yesterday", startedAt = completedAt - 60_000, completedAt = completedAt)
        repository.data.history.add(workout)
        repository.save()
        val storedState = context.getSharedPreferences("lofit-data", 0).getString("state", null)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )

        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(withText(R.string.nav_history)).perform(click())
        onView(withContentDescription("Continue Yesterday")).check(doesNotExist())
        onView(withContentDescription("View Yesterday")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withContentDescription("Create routine from Yesterday")).check(matches(isDisplayed()))
        assertEquals(storedState, context.getSharedPreferences("lofit-data", 0).getString("state", null))
        instrumentation.runOnMainSync { activity.finish() }
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
        onView(allOf(withText(R.string.nav_routines), isAssignableFrom(android.widget.Button::class.java))).perform(click())
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
        onView(allOf(withText(R.string.nav_routines), isAssignableFrom(android.widget.Button::class.java))).perform(click())
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

private fun loggedWorkoutExercise(
    exerciseId: String,
    sets: MutableList<LoggedSet>,
    restSecondsOverride: Int? = null,
): WorkoutExercise {
    val plannedSets = sets.map { set ->
        PlannedSet(
            type = set.type,
            weightKg = set.weightKg,
            reps = set.reps,
            holdSeconds = set.holdSeconds,
            actualHoldSeconds = set.holdSeconds.takeIf { it > 0 },
            side = set.side,
        ).also { set.plannedSetId = it.id }
    }.toMutableList()
    return WorkoutExercise(
        exerciseId = exerciseId,
        restSecondsOverride = restSecondsOverride,
        sets = sets,
        plannedSets = plannedSets,
    )
}
