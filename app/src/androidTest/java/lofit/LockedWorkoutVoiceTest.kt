package lofit

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.PowerManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockedWorkoutVoiceTest {
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
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.activeWorkout =
                WorkoutSession(
                    voiceCommandsEnabled = true,
                    name = "Locked workout",
                    defaultRestSeconds = 60,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "back-squat",
                                plannedSets =
                                    mutableListOf(
                                        PlannedSet(weightKg = 50.0, reps = 8),
                                        PlannedSet(),
                                    ),
                            )
                        ),
                )
            save()
        }
        wake()
    }

    @After
    fun cleanup() {
        wake()
        instrumentation.runOnMainSync { WorkoutVoiceService.stop() }
        await { !microphoneRunning() }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
    }

    private fun wake() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        await { context.getSystemService(PowerManager::class.java).isInteractive }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Voice service condition timed out" }
            SystemClock.sleep(16)
        }
    }

    private fun microphoneRunning() =
        context.getSystemService(AudioManager::class.java).activeRecordingConfigurations.any {
            it.clientAudioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION &&
                !it.isClientSilenced
        }

    private fun background(): Boolean {
        var background = false
        instrumentation.runOnMainSync {
            background = WorkoutVoiceService.current?.runtime?.foreground == false
        }
        return background
    }

    private fun ready() {
        onView(withContentDescription("Set 1 done")).perform(scrollTo(), waitForStableLayout())
        await { microphoneRunning() }
    }

    private fun lock() {
        shell("input keyevent KEYCODE_SLEEP")
        await { !context.getSystemService(PowerManager::class.java).isInteractive && background() }
        await { microphoneRunning() }
        val notification =
            context.getSystemService(NotificationManager::class.java).activeNotifications.single {
                it.id == 1
            }
        assertTrue(notification.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
    }

    private fun command(command: VoiceCommand) {
        instrumentation.runOnMainSync {
            val service = WorkoutVoiceService.current!!
            service.onVoiceCommand(command, service.runtime.session.backgroundTarget()!!)
        }
    }

    /** Exercise the real recognizer while the service owns the locked workout. */
    private fun speak(phrase: String, expected: VoiceCommand? = null) {
        lateinit var service: WorkoutVoiceService
        lateinit var target: WorkoutVoiceTarget
        instrumentation.runOnMainSync {
            service = WorkoutVoiceService.current!!
            target = service.runtime.session.backgroundTarget()!!
        }
        val bytes =
            instrumentation.context.assets.open("voice-time/$phrase.pcm").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val audio = ShortArray(bytes.size / 2) { buffer.short }
        val commands = mutableListOf<VoiceCommand>()
        TimeKeywordDetector(context).use { detector ->
            for (offset in audio.indices step 320) {
                detector.accept(audio.copyOfRange(offset, minOf(offset + 320, audio.size)))?.let {
                    command ->
                    commands.add(command)
                    instrumentation.runOnMainSync { service.onVoiceCommand(command, target) }
                }
            }
        }
        assertEquals(listOfNotNull(expected), commands)
    }

    @Test
    fun spokenSetValuesLeaveWeightedTimedGoalUnchangedWithScreenLocked() {
        LofitRepository(context).apply {
            val exercise =
                data.exercises
                    .first { it.id == "plank" }
                    .copy(id = "locked-weighted-hold", custom = true, weighted = true)
            data.exercises.add(exercise)
            data.activeWorkout!!.exercises.apply {
                clear()
                add(
                    WorkoutExercise(
                        exerciseId = exercise.id,
                        plannedSets =
                            mutableListOf(
                                PlannedSet(weightKg = 20.0, holdSeconds = 60, actualHoldSeconds = 7)
                            ),
                    )
                )
            }
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            ready()
            lock()
            speak("lessac-set-timed")
            val item = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertEquals(20.0, item.plannedSets.single().weightKg, 0.0)
            assertEquals(60, item.plannedSets.single().holdSeconds)
            assertEquals(7, item.plannedSets.single().actualHoldSeconds)
            assertTrue(item.sets.isEmpty())
            wake()
            onView(withContentDescription("Set 1 Goal time in seconds"))
                .check(matches(withText("60")))
            onView(withContentDescription("Set 1 weight")).check(matches(withText("20")))
        }
    }

    @Test
    fun spokenSetValuesLeaveUnweightedTimedGoalUnchangedWithScreenLocked() {
        LofitRepository(context).apply {
            data.activeWorkout!!.exercises.apply {
                clear()
                add(
                    WorkoutExercise(
                        exerciseId = "plank",
                        plannedSets = mutableListOf(PlannedSet(holdSeconds = 60)),
                    )
                )
            }
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            ready()
            lock()
            speak("lessac-set-seconds")
            assertEquals(
                60,
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises
                    .single()
                    .plannedSets
                    .single()
                    .holdSeconds,
            )
            assertEquals(0, LofitRepository(context).data.activeWorkout!!.setCount())
            wake()
            onView(withContentDescription("Set 1 Goal time in seconds"))
                .check(matches(withText("60")))
        }
    }

    @Test
    fun lockedScreenIgnoresSetValuesAndStillAcceptsDoneAndSkip() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready()
            lock()
            speak("lessac-set-decimal")
            speak("lessac-done", VoiceCommand.DONE)
            speak("lessac-skip", VoiceCommand.SKIP)
            val item = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertEquals(50.0, item.sets.single().weightKg, 0.0)
            assertEquals(8, item.sets.single().reps)
            assertEquals(0.0, item.plannedSets[1].weightKg, 0.0)
            assertTrue(microphoneRunning())
            wake()
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 1 weight")).check(matches(withText("50")))
            onView(withContentDescription("Rest timer overlay"))
                .check(matches(withEffectiveVisibility(Visibility.GONE)))
        }
    }

    @Test
    fun serviceKeepsListeningAfterActivityDestructionAndNewScreenUsesSameWorkout() {
        ActivityScenario.launch(MainActivity::class.java).use { ready() }
        await { background() && microphoneRunning() }
        command(VoiceCommand.DONE)
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Set 1 done"))
                .perform(scrollTo(), waitForStableLayout())
                .check(matches(isChecked()))
            onView(withContentDescription("Set 1 weight")).check(matches(withText("50")))
            onView(withContentDescription("Set 2 done")).check(matches(isNotChecked()))
            assertEquals(1, LofitRepository(context).data.activeWorkout!!.setCount())
        }
    }

    @Test
    fun lockedTimerReachesGoalAndRestCanBeSkipped() {
        LofitRepository(context).apply {
            data.activeWorkout!!.exercises.apply {
                clear()
                add(
                    WorkoutExercise(
                        exerciseId = "plank",
                        plannedSets = mutableListOf(PlannedSet(holdSeconds = 1)),
                    )
                )
            }
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            ready()
            lock()
            speak("time-natural", VoiceCommand.TIME)
            await { LofitRepository(context).data.activeWorkout!!.setCount() == 1 }
            val set = LofitRepository(context).data.activeWorkout!!.exercises.single().sets.single()
            assertTrue(set.holdSeconds >= 1)
            command(VoiceCommand.SKIP)
            await { !microphoneRunning() }
            wake()
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun notificationActionsWorkWithoutActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { ready() }
        await { background() && microphoneRunning() }
        val manager = context.getSystemService(NotificationManager::class.java)
        fun action(label: String): android.app.PendingIntent? =
            manager.activeNotifications
                .firstOrNull { it.id == 1 }
                ?.notification
                ?.actions
                ?.firstOrNull { it.title.toString() == label }
                ?.actionIntent
        await { action("Complete Set") != null }
        action("Complete Set")!!.send()
        await { LofitRepository(context).data.activeWorkout!!.setCount() == 1 }
        await { action("Skip") != null }
        action("Skip")!!.send()
        await {
            var skipped = false
            instrumentation.runOnMainSync {
                skipped = WorkoutVoiceService.current!!.runtime.session.restEndsAt == 0L
            }
            skipped
        }
    }
}
