package lofit

import org.junit.Assert.*
import org.junit.Test

class VoiceTimerTargetTest {
    private val timed = Exercise("timed", "Hold", ExerciseType.TIMED, false, "Core", "")
    private val reps = Exercise("reps", "Reps", ExerciseType.REPETITIONS, false, "Core", "")
    private val scale = timed.copy(id = "scale", weighted = true, liveMeasurement = true)
    private val exercises = listOf(timed, reps, scale)

    private fun item(exercise: Exercise = timed) =
        WorkoutExercise(
            exerciseId = exercise.id,
            plannedSets = mutableListOf(PlannedSet(), PlannedSet()),
        )

    private fun target(
        items: List<WorkoutExercise>,
        visible: Set<String> = items.map { it.id }.toSet(),
        active: VoiceTimerTarget? = null,
        scaleAvailable: Boolean = true,
    ) =
        voiceTimerTarget(
            WorkoutSession(name = "Test", exercises = items.toMutableList()),
            exercises,
            visible,
            scaleAvailable,
            active,
        )

    @Test
    fun doneIncludesRepetitionAndScaleSetsAndPrioritizesActiveSet() {
        val repetition = item(reps)
        val live = item(scale)
        val workout = WorkoutSession(name = "Test", exercises = mutableListOf(repetition, live))
        val visible = setOf(repetition.id, live.id)
        assertEquals(
            VoiceTimerTarget(repetition.id, repetition.plannedSets[0].id),
            voiceTimerTarget(workout, exercises, visible, true, timersOnly = false),
        )
        val active = VoiceTimerTarget(live.id, live.plannedSets[1].id)
        assertEquals(
            active,
            voiceTimerTarget(workout, exercises, visible, true, active, timersOnly = false),
        )
        assertNull(
            voiceTimerTarget(
                workout,
                exercises,
                setOf(repetition.id),
                true,
                active,
                timersOnly = false,
            )
        )
    }

    @Test
    fun choosesFirstUnfinishedSetOfFirstVisibleEligibleExercise() {
        val first = item()
        val second = item()
        first.sets.add(
            LoggedSet(type = SetType.NORMAL, weightKg = 0.0, plannedSetId = first.plannedSets[0].id)
        )
        assertEquals(
            VoiceTimerTarget(first.id, first.plannedSets[1].id),
            target(listOf(first, second)),
        )
    }

    @Test
    fun ignoresHiddenExercises() {
        val hidden = item()
        val visible = item()
        assertEquals(
            VoiceTimerTarget(visible.id, visible.plannedSets[0].id),
            target(listOf(hidden, visible), setOf(visible.id)),
        )
        assertNull(target(listOf(hidden), emptySet()))
    }

    @Test
    fun scaleAndRepetitionExercisesCannotStartByVoice() {
        assertNull(target(listOf(item(scale), item(reps))))
    }

    @Test
    fun scaleExerciseBecomesEligibleWhenManualModeIsSelected() {
        val manual = item(scale).apply { manualMeasurement = true }
        assertEquals(VoiceTimerTarget(manual.id, manual.plannedSets[0].id), target(listOf(manual)))
    }

    @Test
    fun scaleExerciseUsesManualTimerWhenNoScaleIsConfigured() {
        val live = item(scale)
        assertEquals(
            VoiceTimerTarget(live.id, live.plannedSets[0].id),
            target(listOf(live), scaleAvailable = false),
        )
        assertFalse(live.manualMeasurement)
    }

    @Test
    fun completedAndEmptyExercisesAreSkipped() {
        val done = item()
        done.plannedSets.forEach {
            done.sets.add(LoggedSet(type = SetType.NORMAL, weightKg = 0.0, plannedSetId = it.id))
        }
        val empty = item().apply { plannedSets.clear() }
        assertNull(target(listOf(done, empty)))
    }

    @Test
    fun selectionTracksWorkoutOrderAndSetRemoval() {
        val first = item()
        val second = item()
        second.plannedSets.removeAt(0)
        assertEquals(
            VoiceTimerTarget(second.id, second.plannedSets.single().id),
            target(listOf(second, first)),
        )
    }

    @Test
    fun missingWorkoutOrExerciseIsIneligible() {
        assertNull(voiceTimerTarget(null, exercises, emptySet(), true))
        assertNull(target(listOf(item().copy(exerciseId = "removed"))))
    }

    @Test
    fun runningTimerTakesPriorityOverEarlierUnfinishedSets() {
        val first = item()
        val running = item()
        val active = VoiceTimerTarget(running.id, running.plannedSets[1].id)
        assertEquals(active, target(listOf(first, running), active = active))
    }

    @Test
    fun completedFailureSetCanStillBeStoppedWhileRunning() {
        val running = item()
        val set = running.plannedSets[0].apply { type = SetType.FAILURE }
        running.sets.add(LoggedSet(type = SetType.FAILURE, weightKg = 0.0, plannedSetId = set.id))
        val active = VoiceTimerTarget(running.id, set.id)
        assertEquals(active, target(listOf(running), active = active))
    }

    @Test
    fun hiddenRunningTimerDoesNotAllowAnotherVisibleSetToStart() {
        val hidden = item()
        val visible = item()
        assertNull(
            target(
                listOf(hidden, visible),
                setOf(visible.id),
                VoiceTimerTarget(hidden.id, hidden.plannedSets[0].id),
            )
        )
    }

    @Test
    fun missingOrScaleControlledRunningTimerIsNotVoiceEligible() {
        val running = item(scale)
        assertNull(
            target(
                listOf(running),
                active = VoiceTimerTarget(running.id, running.plannedSets[0].id),
            )
        )
        assertNull(target(listOf(item()), active = VoiceTimerTarget("removed", "removed")))
    }
}
