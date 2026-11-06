package lofit

import android.Manifest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.SystemClock
import android.view.ViewGroup
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceTimerFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var item: WorkoutExercise

    @Before
    fun setup() {
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
        context.clearRepositoryTestData()
        item =
            WorkoutExercise(
                exerciseId = "plank",
                plannedSets =
                    mutableListOf(PlannedSet(holdSeconds = 60), PlannedSet(holdSeconds = 60)),
            )
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.voiceCommandsEnabled = true
            data.bodyMetricsEnabled = false
            data.mcpEnabled = false
            data.activeWorkout =
                WorkoutSession(name = "Voice test", exercises = mutableListOf(item))
            save()
        }
    }

    private fun microphoneRunning(): Boolean =
        context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.any {
            it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

    private fun awaitMicrophone(running: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8_000
        while (microphoneRunning() != running) {
            check(SystemClock.uptimeMillis() < deadline) {
                "Microphone running=$running not reached"
            }
            SystemClock.sleep(16)
        }
    }

    private fun ready() {
        onView(withContentDescription("Start set 1 hold"))
            .perform(scrollTo(), waitForStableLayout())
        awaitMicrophone(true)
    }

    @Test
    fun finishingWorkoutStopsVoiceAndRejectsOldCommands() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            onView(withContentDescription("Set 1 done")).perform(click())
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText("Finish and save")).perform(scrollTo(), click())
            awaitMicrophone(false)
            assertNull(LofitRepository(context).data.activeWorkout)
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[1].id) }
            assertNull(LofitRepository(context).data.activeWorkout)
        }
    }

    @Test
    fun discardingWorkoutStopsVoiceAndRejectsOldCommands() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            onView(withText("Discard workout")).perform(scrollTo(), click())
            awaitMicrophone(false)
            assertNull(LofitRepository(context).data.activeWorkout)
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            assertNull(LofitRepository(context).data.activeWorkout)
        }
    }

    @Test
    fun onboardingVoiceChoiceSurvivesRecreationAndEnablesListening() {
        LofitRepository(context).apply {
            data.onboardingComplete = false
            data.voiceCommandsEnabled = false
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Voice commands"))
                .perform(scrollTo())
                .check(matches(org.hamcrest.Matchers.not(isChecked())))
                .perform(click())
            assertFalse(LofitRepository(context).data.voiceCommandsEnabled)
            scenario.recreate()
            onView(withContentDescription("Voice commands"))
                .perform(scrollTo())
                .check(matches(isChecked()))
            onView(withText("Get started")).perform(scrollTo(), click())
            assertTrue(LofitRepository(context).data.voiceCommandsEnabled)
            ready()
            scenario.recreate()
            ready()
        }
    }

    @Test
    fun decliningVoiceKeepsMicrophoneOffAndManualTimersWorking() {
        LofitRepository(context).apply {
            data.onboardingComplete = false
            data.voiceCommandsEnabled = false
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Voice commands"))
                .perform(scrollTo())
                .check(matches(org.hamcrest.Matchers.not(isChecked())))
            onView(withText("Get started")).perform(scrollTo(), click())
            scenario.recreate()
            onView(withContentDescription("Start set 1 hold"))
                .perform(scrollTo(), waitForStableLayout())
            assertFalse(LofitRepository(context).data.voiceCommandsEnabled)
            assertFalse(microphoneRunning())
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Start set 1 hold")).perform(click())
            onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
            assertFalse(microphoneRunning())
        }
    }

    @Test
    fun settingsCanDisableAndReenableVoiceForExistingWorkout() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            fun navigate(label: String) {
                scenario.onActivity { activity ->
                    (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                            as DrawerLayout)
                        .openDrawer(GravityCompat.START, false)
                }
                onView(
                        org.hamcrest.Matchers.allOf(
                            withText(label),
                            isAssignableFrom(android.widget.Button::class.java),
                        )
                    )
                    .perform(click())
                if (label == context.getString(R.string.nav_workout)) {
                    onView(withText("Resume workout")).perform(scrollTo(), click())
                }
            }
            ready()
            navigate("Settings")
            onView(withContentDescription("Voice commands")).perform(scrollTo(), click())
            assertFalse(LofitRepository(context).data.voiceCommandsEnabled)
            scenario.recreate()
            navigate(context.getString(R.string.nav_workout))
            onView(withContentDescription("Start set 1 hold"))
                .perform(scrollTo(), waitForStableLayout())
            assertFalse(microphoneRunning())
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
            navigate("Settings")
            onView(withContentDescription("Voice commands")).perform(scrollTo(), click())
            navigate(context.getString(R.string.nav_workout))
            ready()
        }
    }

    @Test
    fun voiceStartsAndStopsRunningTimerBeforeStartingNextSet() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
            awaitMicrophone(true)
            onView(withContentDescription("Set 1 Active time in seconds"))
                .perform(
                    waitUntil("timer has advanced") {
                        (it as android.widget.TextView).text.toString().toInt() > 0
                    }
                )
            scenario.onActivity {
                it.onTimerVoiceCommand(item.plannedSets[1].id)
            }
            onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
            onView(withContentDescription("Set 1 Active time in seconds")).check { view, error ->
                if (error != null) throw error
                assertTrue((view as android.widget.TextView).text.toString().toInt() > 0)
            }
            onView(withContentDescription("Start set 2 hold")).check(matches(isDisplayed()))
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
            assertTrue(
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises
                    .single()
                    .plannedSets[0]
                    .actualHoldSeconds!! > 0
            )
            awaitMicrophone(true)
            onView(withContentDescription("Set 1 done")).perform(click())
            awaitMicrophone(true)
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[1].id) }
            onView(withContentDescription("Stop set 2 hold")).check(matches(isDisplayed()))
        }
        awaitMicrophone(false)
    }

    @Test
    fun drawerAndBackgroundStopListeningAndRejectQueuedCommands() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            scenario.onActivity { activity ->
                val drawer =
                    activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        as DrawerLayout
                drawer.openDrawer(GravityCompat.START, false)
            }
            awaitMicrophone(false)
            scenario.onActivity { activity ->
                activity.onTimerVoiceCommand(item.plannedSets[0].id)
                val drawer =
                    activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        as DrawerLayout
                drawer.closeDrawer(GravityCompat.START, false)
            }
            onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
            awaitMicrophone(true)
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitMicrophone(false)
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            ready()
            scenario.recreate()
            ready()
        }
        awaitMicrophone(false)
    }

    @Test
    fun oneSpokenTimeStartsAndSecondSpokenTimeStopsWithoutDuplicateToggles() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            val bytes =
                instrumentation.context.assets.open("voice-time/time-natural.pcm").use {
                    it.readBytes()
                }
            val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val audio = FloatArray(bytes.size / 2) { buffer.short / 32768f }
            TimeKeywordDetector(context).use { detector ->
                var commands = 0
                fun speak() {
                    for (offset in audio.indices step 320) {
                        if (
                            detector.accept(
                                audio.copyOfRange(offset, minOf(offset + 320, audio.size))
                            )
                        ) {
                            commands++
                            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
                        }
                    }
                }
                speak()
                assertEquals(1, commands)
                onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
                awaitMicrophone(true)
                speak()
                assertEquals(2, commands)
                onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
            }
        }
    }

    @Test
    fun voiceStopsFailureTimerEvenAfterItsOnlySetIsMarkedDone() {
        LofitRepository(context).apply {
            data.activeWorkout!!.exercises.single().plannedSets.apply {
                removeAt(1)
                single().type = SetType.FAILURE
                single().holdSeconds = 1
            }
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            onView(withContentDescription("Start set 1 hold")).perform(click())
            onView(withContentDescription("Set 1 done")).perform(waitUntilMatches(isChecked()))
            awaitMicrophone(true)
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
            awaitMicrophone(false)
            assertTrue(
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises
                    .single()
                    .plannedSets
                    .single()
                    .actualHoldSeconds!! >= 1
            )
        }
    }

    @Test
    fun scrollingExerciseOffScreenStopsListeningAndReturningResumes() {
        LofitRepository(context).apply {
            data.activeWorkout!!
                .exercises
                .add(
                    WorkoutExercise(
                        exerciseId = "back-squat",
                        plannedSets = MutableList(20) { PlannedSet() },
                    )
                )
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            onView(withText("Finish workout")).perform(scrollTo())
            awaitMicrophone(false)
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            ready()
        }
    }

    @Test
    fun scaleModeNeverListensAndManualModeCanStart() {
        LofitRepository(context).apply {
            val scale =
                Exercise(
                    "voice-scale",
                    "Voice Scale",
                    ExerciseType.TIMED,
                    false,
                    "Forearms",
                    "",
                    weighted = true,
                    liveMeasurement = true,
                )
            data.exercises.add(scale)
            item = item.copy(exerciseId = scale.id)
            data.activeWorkout!!.exercises[0] = item
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Voice Scale live weight graph"))
                .perform(scrollTo(), waitForStableLayout())
            assertFalse(microphoneRunning())
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Options for Voice Scale")).perform(scrollTo(), click())
            onView(withText("Use scale")).perform(click())
            ready()
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
        }
    }
}
