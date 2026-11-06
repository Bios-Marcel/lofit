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

    private fun speak(
        scenario: ActivityScenario<MainActivity>,
        word: String,
        expected: VoiceCommand,
    ) {
        lateinit var target: WorkoutVoiceTarget
        scenario.onActivity { target = it.visibleWorkoutVoiceTarget()!! }
        val bytes =
            instrumentation.context.assets.open("voice-time/lessac-$word.pcm").use {
                it.readBytes()
            }
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val audio = FloatArray(bytes.size / 2) { buffer.short / 32768f }
        val commands = mutableListOf<VoiceCommand>()
        TimeKeywordDetector(context).use { detector ->
            for (offset in audio.indices step 320) {
                detector.accept(audio.copyOfRange(offset, minOf(offset + 320, audio.size)))?.let {
                    command ->
                    commands.add(command)
                    scenario.onActivity { it.onWorkoutVoiceCommand(command, target) }
                }
            }
        }
        assertEquals(listOf(expected), commands)
    }

    @Test
    fun spokenDoneCompletesRepetitionSetAndSpokenSkipEndsRest() {
        LofitRepository(context).apply {
            item = item.copy(exerciseId = "back-squat")
            data.activeWorkout!!.exercises[0] = item
            data.activeWorkout!!.defaultRestSeconds = 90
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), waitForStableLayout())
            awaitMicrophone(true)
            lateinit var oldTarget: WorkoutVoiceTarget
            scenario.onActivity { oldTarget = it.visibleWorkoutVoiceTarget()!! }
            speak(scenario, "done", VoiceCommand.DONE)
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 2 done")).check(matches(isNotChecked()))
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            scenario.onActivity { it.onWorkoutVoiceCommand(VoiceCommand.DONE, oldTarget) }
            assertEquals(1, LofitRepository(context).data.activeWorkout!!.setCount())
            lateinit var oldRest: WorkoutVoiceTarget
            scenario.onActivity { oldRest = it.visibleWorkoutVoiceTarget()!! }
            speak(scenario, "skip", VoiceCommand.SKIP)
            onView(withContentDescription("Rest timer overlay"))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
            speak(scenario, "done", VoiceCommand.DONE)
            scenario.onActivity { it.onWorkoutVoiceCommand(VoiceCommand.SKIP, oldRest) }
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            awaitMicrophone(true)
            speak(scenario, "skip", VoiceCommand.SKIP)
            awaitMicrophone(false)
            assertEquals(2, LofitRepository(context).data.activeWorkout!!.setCount())
        }
    }

    @Test
    fun spokenDoneStopsRunningHoldAndStoresItsElapsedTime() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            speak(scenario, "done", VoiceCommand.DONE)
            onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 2 done")).check(matches(isNotChecked()))
            val saved = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertTrue(saved.plannedSets[0].actualHoldSeconds!! >= 1)
            assertEquals(1, saved.sets.size)
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun doneAndSkipAreIgnoredInBackground() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            onView(withContentDescription("Set 1 done")).perform(click())
            lateinit var target: WorkoutVoiceTarget
            scenario.onActivity { target = it.visibleWorkoutVoiceTarget()!! }
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitMicrophone(false)
            scenario.onActivity {
                it.onWorkoutVoiceCommand(VoiceCommand.DONE, target)
                it.onWorkoutVoiceCommand(VoiceCommand.SKIP, target)
            }
            scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withContentDescription("Set 2 done")).check(matches(isNotChecked()))
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            assertEquals(1, LofitRepository(context).data.activeWorkout!!.setCount())
        }
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
                            ) == VoiceCommand.TIME
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
            awaitMicrophone(true)
            scenario.onActivity {
                it.onWorkoutVoiceCommand(VoiceCommand.SKIP, it.visibleWorkoutVoiceTarget()!!)
            }
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
    fun scrollingRejectsHiddenTimerWhileVisibleRepetitionSetRemainsEligible() {
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
            awaitMicrophone(true)
            scenario.onActivity {
                assertNull(it.visibleWorkoutVoiceTarget()!!.timer)
                assertNotNull(it.visibleWorkoutVoiceTarget()!!.done)
                it.onTimerVoiceCommand(item.plannedSets[0].id)
            }
            ready()
        }
    }

    @Test
    fun scaleModeListensForDoneAndManualModeCanStartTimer() {
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
            awaitMicrophone(true)
            scenario.onActivity {
                assertNull(it.visibleWorkoutVoiceTarget()!!.timer)
                assertEquals(item.plannedSets[0].id, it.visibleWorkoutVoiceTarget()!!.done!!.setId)
                it.onTimerVoiceCommand(item.plannedSets[0].id)
            }
            onView(withContentDescription("Options for Voice Scale")).perform(scrollTo(), click())
            onView(withText("Use scale")).perform(click())
            ready()
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
        }
    }
}
