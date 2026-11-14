package lofit

import org.junit.Assert.*
import org.junit.Test

class WorkoutVoiceNotificationTest {
    private val data = AppData()
    private val set = PlannedSet(weightKg = 20.0)
    private val item = WorkoutExercise(exerciseId = "bench-press", plannedSets = mutableListOf(set))
    private val workout = WorkoutSession(name = "Workout", exercises = mutableListOf(item))
    private val session = WorkoutVoiceSession({ data }, {}, { 1000L })
    private val target = WorkoutVoiceTarget(done = VoiceTimerTarget(item.id, set.id))

    init {
        data.activeWorkout = workout
    }

    @Test
    fun unchangedStateIsStableButEditsAndCompletionChangeIt() {
        val first = session.notificationState(data, target, 1000)
        assertEquals(first, session.notificationState(data, target, 3000))
        set.weightKg = 25.0
        assertNotEquals(first, session.notificationState(data, target, 3000))
        val edited = session.notificationState(data, target, 3000)
        item.sets.add(
            LoggedSet(type = SetType.NORMAL, weightKg = 25.0, reps = 8, plannedSetId = set.id)
        )
        val completed = session.notificationState(data, target, 3000)
        assertEquals(edited.detail, completed.detail)
        assertTrue(edited.canComplete)
        assertFalse(completed.canComplete)
        assertNotEquals(edited, completed)
    }

    @Test
    fun countdownChangesOnlyAtDisplayedSecondsAndIncludesActionDeadline() {
        val rest = target.copy(restEndsAt = 5000)
        val first = session.notificationState(data, rest, 1100)
        assertEquals(first, session.notificationState(data, rest, 1200))
        assertNotEquals(first, session.notificationState(data, rest, 2100))
        val adjusted = session.notificationState(data, rest.copy(restEndsAt = 5100), 1100)
        assertEquals(first.detail, adjusted.detail)
        assertNotEquals(first, adjusted)
    }

    @Test
    fun holdSecondsAndSetIdentityTriggerUpdates() {
        session.activeHoldPlannedSetId = set.id
        session.holdStartedAt = 1000
        val first = session.notificationState(data, target, 2100)
        assertEquals(first, session.notificationState(data, target, 2200))
        assertNotEquals(first, session.notificationState(data, target, 3100))
        val next = set.copy(id = "next")
        item.plannedSets[0] = next
        val changed =
            session.notificationState(
                data,
                target.copy(done = VoiceTimerTarget(item.id, next.id)),
                2100,
            )
        assertEquals(first.detail, changed.detail)
        assertNotEquals(first, changed)
    }

    @Test
    fun manualRestNotificationKeepsSetDetailsWithoutClaimingVoiceIsOn() {
        val rest = target.copy(restEndsAt = 121_000)
        val state = session.notificationState(data, rest, 1000)
        assertFalse(state.voiceEnabled)
        assertEquals("Set 1 of 1 · 20 kg × 8 reps · Rest 2:00", state.detail)
        assertEquals(121_000L, state.restEndsAt)
        assertTrue(state.canComplete)
        val voice = session.notificationState(data, rest, 1000, voiceEnabled = true)
        assertTrue(voice.detail.startsWith("Voice on"))
        assertNotEquals(state, voice)
    }
}
