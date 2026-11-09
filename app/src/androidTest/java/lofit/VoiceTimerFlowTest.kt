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
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceTimerFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var item: WorkoutExercise

    @After
    fun stopVoiceService() {
        instrumentation.runOnMainSync { WorkoutVoiceService.stop() }
        awaitMicrophone(false)
    }

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
            data.bodyMetricsEnabled = false
            data.mcpEnabled = false
            data.activeWorkout =
                WorkoutSession(
                    name = "Voice test",
                    exercises = mutableListOf(item),
                    voiceCommandsEnabled = true,
                )
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

    private fun command(scenario: ActivityScenario<MainActivity>, command: VoiceCommand) {
        scenario.onActivity { it.onWorkoutVoiceCommand(command, it.visibleWorkoutVoiceTarget()!!) }
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
        val audio = ShortArray(bytes.size / 2) { buffer.short }
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
    fun spokenNumbersUpdateOnlyCurrentRepetitionSetAndPersistUntilDone() {
        LofitRepository(context).apply {
            item = item.copy(exerciseId = "back-squat")
            data.activeWorkout!!.exercises[0] = item
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), waitForStableLayout())
            awaitMicrophone(true)
            lateinit var firstTarget: WorkoutVoiceTarget
            scenario.onActivity { firstTarget = it.visibleWorkoutVoiceTarget()!! }
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity {
                it.onWorkoutVoiceCommand(VoiceCommand.SetValues(99.0, 99), firstTarget)
            }
            assertEquals(
                0.0,
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises
                    .single()
                    .plannedSets[0]
                    .weightKg,
                0.0,
            )
            scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), waitForStableLayout())
            awaitMicrophone(true)
            speak(scenario, "set-decimal", VoiceCommand.SetValues(12.5, 10))
            onView(withContentDescription("Set 1 weight")).check(matches(withText("12.5")))
            onView(withContentDescription("Set 1 Repetitions")).check(matches(withText("10")))
            onView(withContentDescription("Set 1 done")).check(matches(isNotChecked()))
            val saved = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertEquals(12.5, saved.plannedSets[0].weightKg, 0.0)
            assertEquals(10, saved.plannedSets[0].reps)
            assertEquals(0.0, saved.plannedSets[1].weightKg, 0.0)
            assertTrue(saved.sets.isEmpty())
            scenario.recreate()
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), waitForStableLayout())
            awaitMicrophone(true)
            onView(withContentDescription("Set 1 weight")).check(matches(withText("12.5")))
            command(scenario, VoiceCommand.DONE)
            scenario.onActivity {
                it.onWorkoutVoiceCommand(VoiceCommand.SetValues(99.0, 99), firstTarget)
            }
            command(scenario, VoiceCommand.SetValues(50.0, 8))
            val completed = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertEquals(12.5, completed.sets.single().weightKg, 0.0)
            assertEquals(10, completed.sets.single().reps)
            assertEquals(50.0, completed.plannedSets[1].weightKg, 0.0)
            assertEquals(8, completed.plannedSets[1].reps)
            assertEquals(1, completed.sets.size)
        }
    }

    @Test
    fun unweightedRepetitionSetsAcceptSingleValueOnly() {
        LofitRepository(context).apply {
            val exercise =
                data.exercises
                    .first { it.id == "back-squat" }
                    .copy(id = "voice-unweighted", custom = true, weighted = false)
            data.exercises.add(exercise)
            item = item.copy(exerciseId = exercise.id)
            data.activeWorkout!!.exercises[0] = item
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), waitForStableLayout())
            awaitMicrophone(true)
            scenario.onActivity {
                val target = it.visibleWorkoutVoiceTarget()!!
                it.onWorkoutVoiceCommand(VoiceCommand.SetValues(50.0, 12), target)
            }
            assertEquals(
                8,
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises
                    .single()
                    .plannedSets[0]
                    .reps,
            )
            command(scenario, VoiceCommand.SetValues(null, 15))
            onView(withContentDescription("Set 1 Repetitions")).check(matches(withText("15")))
            val saved = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertEquals(0.0, saved.plannedSets[0].weightKg, 0.0)
            assertEquals(15, saved.plannedSets[0].reps)
            assertTrue(saved.sets.isEmpty())
        }
    }

    @Test
    fun weightedTimedSetUpdatesWeightAndGoalWithoutChangingElapsedTime() {
        LofitRepository(context).apply {
            val exercise =
                data.exercises
                    .first { it.id == "plank" }
                    .copy(id = "voice-weighted-hold", custom = true, weighted = true)
            data.exercises.add(exercise)
            item = item.copy(exerciseId = exercise.id)
            item.plannedSets[0].actualHoldSeconds = 7
            data.activeWorkout!!.exercises[0] = item
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            command(scenario, VoiceCommand.SetValues(12.5, 30))
            onView(withContentDescription("Set 1 weight")).check(matches(withText("12.5")))
            onView(withContentDescription("Set 1 Goal time in seconds"))
                .check(matches(withText("30")))
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(withText("7")))
            onView(withContentDescription("Set 1 done")).check(matches(isNotChecked()))
            command(scenario, VoiceCommand.SetValues(null, 15))
            val saved = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertEquals(12.5, saved.plannedSets[0].weightKg, 0.0)
            assertEquals(30, saved.plannedSets[0].holdSeconds)
            assertEquals(7, saved.plannedSets[0].actualHoldSeconds)
            assertEquals(60, saved.plannedSets[1].holdSeconds)
            assertTrue(saved.sets.isEmpty())
            scenario.recreate()
            ready()
            onView(withContentDescription("Set 1 Goal time in seconds"))
                .check(matches(withText("30")))
            onView(withContentDescription("Set 1 weight")).check(matches(withText("12.5")))
        }
    }

    @Test
    fun unweightedTimedSetUsesSingleValueAsGoalSeconds() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            command(scenario, VoiceCommand.SetValues(50.0, 8))
            val saved = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertEquals(0.0, saved.plannedSets[0].weightKg, 0.0)
            assertEquals(60, saved.plannedSets[0].holdSeconds)
            assertTrue(saved.sets.isEmpty())
            command(scenario, VoiceCommand.SetValues(null, 30))
            onView(withContentDescription("Set 1 Goal time in seconds"))
                .check(matches(withText("30")))
            onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
            assertEquals(
                30,
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises
                    .single()
                    .plannedSets[0]
                    .holdSeconds,
            )
            assertTrue(
                LofitRepository(context).data.activeWorkout!!.exercises.single().sets.isEmpty()
            )
        }
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
            command(scenario, VoiceCommand.DONE)
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 2 done")).check(matches(isNotChecked()))
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            scenario.onActivity { it.onWorkoutVoiceCommand(VoiceCommand.DONE, oldTarget) }
            assertEquals(1, LofitRepository(context).data.activeWorkout!!.setCount())
            lateinit var oldRest: WorkoutVoiceTarget
            scenario.onActivity { oldRest = it.visibleWorkoutVoiceTarget()!! }
            command(scenario, VoiceCommand.SKIP)
            onView(withContentDescription("Rest timer overlay"))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
            command(scenario, VoiceCommand.DONE)
            scenario.onActivity { it.onWorkoutVoiceCommand(VoiceCommand.SKIP, oldRest) }
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            awaitMicrophone(true)
            command(scenario, VoiceCommand.SKIP)
            awaitMicrophone(false)
            assertEquals(2, LofitRepository(context).data.activeWorkout!!.setCount())
        }
    }

    @Test
    fun spokenDoneStopsRunningHoldAndStoresItsElapsedTime() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            command(scenario, VoiceCommand.DONE)
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
    fun queuedActivityCallbacksAreIgnoredWhileServiceOwnsBackgroundCommands() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            onView(withContentDescription("Set 1 done")).perform(click())
            lateinit var target: WorkoutVoiceTarget
            scenario.onActivity { target = it.visibleWorkoutVoiceTarget()!! }
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitMicrophone(true)
            scenario.onActivity {
                it.onWorkoutVoiceCommand(VoiceCommand.DONE, target)
                it.onWorkoutVoiceCommand(VoiceCommand.SKIP, target)
                it.onWorkoutVoiceCommand(VoiceCommand.SetValues(50.0, 10), target)
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
    fun onboardingHasNoVoiceChoiceAndWorkoutCanEnableVoiceAfterward() {
        LofitRepository(context).apply {
            data.onboardingComplete = false
            data.activeWorkout!!.voiceCommandsEnabled = false
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("Voice commands")).check(doesNotExist())
            onView(withContentDescription("Voice commands")).check(doesNotExist())
            scenario.recreate()
            onView(withText("Voice commands")).check(doesNotExist())
            onView(withText("Get started")).perform(scrollTo(), click())
            assertFalse(LofitRepository(context).data.activeWorkout!!.voiceCommandsEnabled)
            assertFalse(microphoneRunning())
            onView(withContentDescription("Workout options")).perform(scrollTo(), click())
            onView(withText("Voice commands")).perform(click())
            ready()
            scenario.recreate()
            ready()
        }
    }

    @Test
    fun defaultWorkoutKeepsMicrophoneOffAndManualTimersWorking() {
        LofitRepository(context).apply {
            data.activeWorkout =
                WorkoutSession(name = "Quiet workout", exercises = mutableListOf(item))
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.recreate()
            onView(withContentDescription("Start set 1 hold"))
                .perform(scrollTo(), waitForStableLayout())
            assertFalse(LofitRepository(context).data.activeWorkout!!.voiceCommandsEnabled)
            assertFalse(microphoneRunning())
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Start set 1 hold")).perform(click())
            onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
            assertFalse(microphoneRunning())
        }
    }

    @Test
    fun workoutVoiceOptionStopsListeningKeepsManualTimersAndSurvivesRestart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            lateinit var target: WorkoutVoiceTarget
            lateinit var service: WorkoutVoiceService
            scenario.onActivity {
                target = it.visibleWorkoutVoiceTarget()!!
                service = WorkoutVoiceService.current!!
            }
            onView(withContentDescription("Workout options")).perform(scrollTo(), click())
            onView(withText("Voice commands")).perform(click())
            awaitMicrophone(false)
            assertFalse(LofitRepository(context).data.activeWorkout!!.voiceCommandsEnabled)
            scenario.onActivity {
                assertNull(it.visibleWorkoutVoiceTarget())
                it.onWorkoutVoiceCommand(VoiceCommand.DONE, target)
                service.onVoiceCommand(VoiceCommand.DONE, target)
            }
            assertEquals(0, LofitRepository(context).data.activeWorkout!!.setCount())
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitMicrophone(false)
            scenario.moveToState(Lifecycle.State.RESUMED)
            onView(withContentDescription("Start set 1 hold")).perform(scrollTo(), click())
            onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
            assertFalse(microphoneRunning())
            onView(withContentDescription("Stop set 1 hold")).perform(click())

            onView(withContentDescription("Workout options")).perform(scrollTo(), click())
            onView(withText("Voice commands")).perform(click())
            ready()
            assertTrue(LofitRepository(context).data.activeWorkout!!.voiceCommandsEnabled)
            onView(withContentDescription("Workout options")).perform(scrollTo(), click())
            onView(withText("Voice commands")).perform(click())
            awaitMicrophone(false)
            scenario.recreate()
            onView(withContentDescription("Start set 1 hold"))
                .perform(scrollTo(), waitForStableLayout())
            assertFalse(microphoneRunning())
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Start set 1 hold"))
                .perform(scrollTo(), waitForStableLayout())
            assertFalse(microphoneRunning())
            scenario.onActivity { assertNull(it.visibleWorkoutVoiceTarget()) }
            assertFalse(LofitRepository(context).data.activeWorkout!!.voiceCommandsEnabled)
        }
    }

    @Test
    fun workoutVoiceOptionResetsWhenStartingNextWorkout() {
        LofitRepository(context).apply {
            data.routines.add(data.activeWorkout!!.toRoutine())
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            ready()
            val previousId = LofitRepository(context).data.activeWorkout!!.id
            onView(withText("Discard workout")).perform(scrollTo(), click())
            awaitMicrophone(false)
            assertNull(LofitRepository(context).data.activeWorkout)
            onView(withText("Start routine")).perform(scrollTo(), click())
            onView(withContentDescription("Start set 1 hold"))
                .perform(scrollTo(), waitForStableLayout())
            val next = LofitRepository(context).data.activeWorkout!!
            assertNotEquals(previousId, next.id)
            assertFalse(next.voiceCommandsEnabled)
            assertFalse(microphoneRunning())
            onView(withContentDescription("Workout options")).perform(scrollTo(), click())
            onView(withText("Voice commands")).perform(click())
            ready()
        }
    }

    @Test
    fun settingsHasNoVoiceChoiceAndWorkoutOptionRemainsAvailable() {
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
            onView(withText("Voice commands")).check(doesNotExist())
            onView(withContentDescription("Voice commands")).check(doesNotExist())
            scenario.recreate()
            navigate("Settings")
            onView(withText("Voice commands")).check(doesNotExist())
            navigate(context.getString(R.string.nav_workout))
            ready()
            onView(withContentDescription("Workout options")).perform(scrollTo(), click())
            onView(withText("Voice commands")).perform(click())
            awaitMicrophone(false)
            assertFalse(LofitRepository(context).data.activeWorkout!!.voiceCommandsEnabled)
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
        awaitMicrophone(true)
    }

    @Test
    fun drawerStopsListeningButBackgroundAndRecreationKeepServiceAlive() {
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
            awaitMicrophone(true)
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            ready()
            scenario.recreate()
            ready()
        }
        awaitMicrophone(true)
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
            val audio = ShortArray(bytes.size / 2) { buffer.short }
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
            data.craneScale = CraneScaleDevice("AA:BB:CC:DD:EE:01", "Scale")
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
            onView(withText("Use crane scale")).perform(click())
            ready()
            scenario.onActivity { it.onTimerVoiceCommand(item.plannedSets[0].id) }
            onView(withContentDescription("Stop set 1 hold")).check(matches(isDisplayed()))
        }
    }
}
