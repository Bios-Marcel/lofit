package lofit

import android.Manifest
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.card.MaterialCardView
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardioTimerFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun setup() {
        context.clearRepositoryTestData()
        listOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
            .forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
    }

    @After
    fun cleanup() {
        instrumentation.runOnMainSync { WorkoutVoiceService.stop() }
    }

    private fun seed(firstSeconds: Int = 1, restSeconds: Int = 0, voice: Boolean = false) {
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.voiceCommandsEnabled = voice
            data.mcpEnabled = false
            val run =
                Exercise("cardio-run", "Run", ExerciseType.TIMED, false, "", "", cardio = true)
            val cycle = run.copy(id = "cardio-cycle", name = "Cycle")
            data.exercises.addAll(listOf(run, cycle))
            data.activeWorkout =
                WorkoutSession(
                    name = "Cardio sequence",
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = run.id,
                                restSecondsOverride = restSeconds,
                                plannedSets = mutableListOf(PlannedSet(holdSeconds = firstSeconds)),
                            ),
                            WorkoutExercise(
                                exerciseId = cycle.id,
                                plannedSets = mutableListOf(PlannedSet(holdSeconds = 60)),
                            ),
                        ),
                )
            save()
        }
    }

    private fun control(exercise: String, description: String): Matcher<View> =
        allOf(
            withContentDescription(description),
            isDescendantOfA(
                allOf(
                    isAssignableFrom(MaterialCardView::class.java),
                    hasDescendant(withText(exercise)),
                )
            ),
        )

    private fun startRun() {
        onView(control("Run", "Start set 1 hold")).perform(scrollTo(), click())
    }

    private fun awaitCycleStarted() {
        onView(isRoot())
            .perform(waitUntilMatches(hasDescendant(control("Cycle", "Stop set 1 hold"))))
        onView(control("Run", "Set 1 done")).check(matches(isChecked()))
        onView(control("Cycle", "Set 1 done")).check(matches(not(isChecked())))
        onView(control("Cycle", "Set 1 Active time in seconds")).check(matches(not(isEnabled())))
        onView(withContentDescription("Rest timer overlay")).check(matches(not(isDisplayed())))
    }

    @Test
    fun nextCardioStartsAutomaticallyWithoutVoiceAndCanBeStopped() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use {
            startRun()
            awaitCycleStarted()
            onView(control("Cycle", "Stop set 1 hold")).perform(scrollTo(), click())
            onView(control("Cycle", "Start set 1 hold")).check(matches(isDisplayed()))
            val items = LofitRepository(context).data.activeWorkout!!.exercises
            assertEquals(1, items[0].sets.single().holdSeconds)
            assertTrue(items[1].plannedSets.single().actualHoldSeconds!! >= 1)
            assertTrue(items[1].sets.isEmpty())
        }
    }

    @Test
    fun workoutMenuDisablesAutoStartAndKeepsChoiceAfterRecreation() {
        seed()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Workout options")).perform(click())
            onView(withText("Auto-start cardio")).perform(click())
            assertFalse(LofitRepository(context).data.activeWorkout!!.autoStartCardio)
            scenario.recreate()
            startRun()
            onView(control("Run", "Set 1 done")).perform(waitUntilMatches(isChecked()))
            onView(control("Cycle", "Start set 1 hold")).check(matches(isEnabled()))
            assertNull(
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises[1]
                    .plannedSets
                    .single()
                    .actualHoldSeconds
            )
            onView(withContentDescription("Workout options")).perform(scrollTo(), click())
            onView(withText("Auto-start cardio")).perform(click())
            assertTrue(LofitRepository(context).data.activeWorkout!!.autoStartCardio)
            startRun()
            awaitCycleStarted()
        }
    }

    @Test
    fun zeroRestAlsoStartsNextSetWithinSameCardioExercise() {
        seed()
        LofitRepository(context).apply {
            data.activeWorkout!!.exercises.first().plannedSets.add(PlannedSet(holdSeconds = 60))
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            startRun()
            onView(isRoot())
                .perform(waitUntilMatches(hasDescendant(control("Run", "Stop set 2 hold"))))
            onView(control("Run", "Set 1 done")).check(matches(isChecked()))
            onView(control("Run", "Set 2 Active time in seconds")).check(matches(not(isEnabled())))
            onView(control("Cycle", "Start set 1 hold")).check(matches(isEnabled()))
            onView(control("Run", "Stop set 2 hold")).perform(scrollTo(), click())
        }
    }

    @Test
    fun restKeepsNextCardioTimerStopped() {
        seed(restSeconds = 30)
        ActivityScenario.launch(MainActivity::class.java).use {
            startRun()
            onView(control("Run", "Set 1 done")).perform(waitUntilMatches(isChecked()))
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            onView(control("Cycle", "Start set 1 hold")).perform(scrollTo())
            onView(control("Cycle", "Set 1 Active time in seconds"))
                .check(matches(allOf(isEnabled(), withText("0"))))
        }
    }

    @Test
    fun manualStopAndDoneKeepNextCardioTimerStopped() {
        seed(firstSeconds = 60)
        ActivityScenario.launch(MainActivity::class.java).use {
            startRun()
            onView(control("Run", "Stop set 1 hold")).perform(click())
            onView(control("Cycle", "Start set 1 hold")).check(matches(isEnabled()))
            startRun()
            onView(control("Run", "Set 1 done")).perform(click())
            onView(control("Cycle", "Start set 1 hold")).perform(scrollTo())
            onView(control("Cycle", "Set 1 Active time in seconds"))
                .check(matches(allOf(isEnabled(), withText("0"))))
        }
    }

    @Test
    fun backgroundTimerStartsNextCardioAndRebindsWhenResumed() {
        seed(firstSeconds = 3, voice = true)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(isRoot())
                .perform(
                    waitUntil("voice service ready") {
                        WorkoutVoiceService.current != null
                    }
                )
            startRun()
            scenario.moveToState(Lifecycle.State.CREATED)
            val deadline = SystemClock.uptimeMillis() + 8000
            var started = false
            while (!started) {
                instrumentation.runOnMainSync {
                    val runtime = WorkoutVoiceService.current!!.runtime
                    assertFalse(runtime.foreground)
                    val next = runtime.repository.data.activeWorkout!!.exercises[1]
                    started = runtime.session.activeHoldWorkoutExerciseId == next.id
                }
                check(SystemClock.uptimeMillis() < deadline) { "Next cardio did not start" }
                if (!started) SystemClock.sleep(16)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            awaitCycleStarted()
            scenario.recreate()
            awaitCycleStarted()
            assertEquals(
                3,
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises[0]
                    .sets
                    .single()
                    .holdSeconds,
            )
        }
    }
}
