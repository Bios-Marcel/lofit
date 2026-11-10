package lofit

import org.junit.Assert.*
import org.junit.Test

class WorkoutVoiceSessionTest {
    private val reps = Exercise("reps", "Squat", ExerciseType.REPETITIONS, false, "Legs", "")
    private val timed = Exercise("timed", "Plank", ExerciseType.TIMED, false, "Core", "")
    private val first =
        WorkoutExercise(
            exerciseId = reps.id,
            plannedSets = mutableListOf(PlannedSet(), PlannedSet()),
        )
    private val hold =
        WorkoutExercise(
            exerciseId = timed.id,
            plannedSets = mutableListOf(PlannedSet(holdSeconds = 10)),
        )
    private val data =
        AppData().apply {
            exercises.addAll(listOf(reps, timed))
            activeWorkout =
                WorkoutSession(
                    name = "Test",
                    voiceCommandsEnabled = true,
                    defaultRestSeconds = 30,
                    exercises = mutableListOf(first, hold),
                )
        }
    private var now = 1000L
    private var saves = 0
    private val session = WorkoutVoiceSession({ data }, { saves++ }, { now })

    @Test
    fun completesAndAdvancesSetsWithoutViewsAndRejectsStaleTargets() {
        first.plannedSets[0].apply {
            weightKg = 52.5
            this.reps = 12
        }
        val target = session.backgroundTarget()!!
        assertNull(target.timer)
        assertTrue(first.sets.isEmpty())
        assertEquals(WorkoutVoiceSession.Cue.DONE, session.apply(VoiceCommand.DONE, target))
        assertEquals(12, first.sets.single().reps)
        assertEquals(52.5, first.sets.single().weightKg, 0.0)
        assertNull(session.apply(VoiceCommand.DONE, target))
        val next = session.backgroundTarget()!!
        assertEquals(first.plannedSets[1].id, next.done!!.setId)
        assertEquals(31_000L, next.restEndsAt)
        session.apply(VoiceCommand.SKIP, next)
        assertEquals(0L, session.restEndsAt)
        session.apply(VoiceCommand.DONE, session.backgroundTarget()!!)
        assertNull(session.apply(VoiceCommand.SKIP, next.copy(restEndsAt = 999)))
        assertEquals(hold.id, session.backgroundTarget()!!.done!!.exerciseId)
        assertEquals(2, saves)
    }

    @Test
    fun keepsChosenExerciseWhenScreenLocksAndAdvancesAfterItsLastSet() {
        session.preferredExerciseId = hold.id
        val target = session.backgroundTarget()!!
        assertEquals(hold.id, target.timer!!.exerciseId)
        session.apply(VoiceCommand.DONE, target)
        assertEquals(first.id, session.backgroundTarget()!!.done!!.exerciseId)
    }

    @Test
    fun timedSetRunsToGoalAndRestExpiresWithoutAnActivity() {
        session.preferredExerciseId = hold.id
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 10_000
        assertTrue(session.tick())
        assertEquals(10, hold.sets.single().holdSeconds)
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(now + 30_000, session.restEndsAt)
        assertFalse(session.tick())
        now += 30_000
        assertTrue(session.tick())
        assertEquals(0L, session.restEndsAt)
        assertFalse(session.tick())
    }

    private fun addFollowingCardio(): WorkoutExercise {
        timed.cardio = true
        hold.restSecondsOverride = 0
        val nextExercise = timed.copy(id = "next-cardio", name = "Next cardio")
        data.exercises.add(nextExercise)
        val next =
            WorkoutExercise(
                exerciseId = nextExercise.id,
                plannedSets = mutableListOf(PlannedSet(holdSeconds = 20)),
            )
        data.activeWorkout!!.exercises.add(next)
        session.preferredExerciseId = hold.id
        return next
    }

    private fun runHoldToGoal() {
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 10_000
        assertTrue(session.tick())
    }

    private fun finishCardioWithRest(): WorkoutExercise {
        val next = addFollowingCardio()
        hold.restSecondsOverride = 5
        runHoldToGoal()
        return next
    }

    @Test
    fun consecutiveCardioStartsNextTimerWithoutRestAndStopsAtWorkoutEnd() {
        val next = addFollowingCardio()
        assertFalse(session.tick())
        assertNull(session.activeHoldPlannedSetId)
        assertNull(hold.plannedSets.single().actualHoldSeconds)
        runHoldToGoal()
        assertEquals(10, hold.sets.single().holdSeconds)
        assertEquals(next.id, session.activeHoldWorkoutExerciseId)
        assertEquals(next.plannedSets.single().id, session.activeHoldPlannedSetId)
        assertEquals(now, session.holdStartedAt)
        assertEquals(0, next.plannedSets.single().actualHoldSeconds)
        assertTrue(next.sets.isEmpty())
        assertEquals(0L, session.restEndsAt)
        assertEquals(next.id, session.backgroundTarget()!!.timer!!.exerciseId)
        now += 5000
        assertFalse(session.tick())
        assertEquals(5, next.plannedSets.single().actualHoldSeconds)
        now += 15_000
        assertTrue(session.tick())
        assertEquals(20, next.sets.single().holdSeconds)
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(now + 30_000, session.restEndsAt)
    }

    @Test
    fun followingCardioSetUsesZeroDefaultRestAndWorksWithoutVoiceCommands() {
        val next = addFollowingCardio()
        hold.plannedSets.add(PlannedSet(holdSeconds = 20))
        hold.restSecondsOverride = null
        data.activeWorkout!!.defaultRestSeconds = 0
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        data.activeWorkout!!.voiceCommandsEnabled = false
        now += 10_000
        assertTrue(session.tick())
        assertEquals(hold.id, session.activeHoldWorkoutExerciseId)
        assertEquals(hold.plannedSets[1].id, session.activeHoldPlannedSetId)
        now += 20_000
        assertTrue(session.tick())
        assertEquals(next.id, session.activeHoldWorkoutExerciseId)
        assertEquals(next.plannedSets.single().id, session.activeHoldPlannedSetId)
        assertEquals(0, next.plannedSets.single().actualHoldSeconds)
    }

    @Test
    fun cardioRestDelaysRemainingSetThenNextExerciseUntilEachRestExpires() {
        val next = addFollowingCardio()
        val nextSet = PlannedSet(holdSeconds = 20)
        hold.plannedSets.add(nextSet)
        hold.restSecondsOverride = 5
        runHoldToGoal()
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(now + 5000, session.restEndsAt)
        now += 4999
        assertFalse(session.tick())
        assertNull(session.activeHoldPlannedSetId)
        now++
        assertTrue(session.tick())
        assertEquals(hold.id, session.activeHoldWorkoutExerciseId)
        assertEquals(nextSet.id, session.activeHoldPlannedSetId)
        assertEquals(0, nextSet.actualHoldSeconds)
        assertEquals(0L, session.restEndsAt)
        now += 20_000
        assertTrue(session.tick())
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(now + 5000, session.restEndsAt)
        now += 5000
        assertTrue(session.tick())
        assertEquals(next.id, session.activeHoldWorkoutExerciseId)
        assertEquals(next.plannedSets.single().id, session.activeHoldPlannedSetId)
        assertEquals(0, next.plannedSets.single().actualHoldSeconds)
    }

    @Test
    fun nextCardioStartsOnlyAfterExtendedRestExpires() {
        val next = finishCardioWithRest()
        session.restEndsAt += 15_000
        now += 5000
        assertFalse(session.tick())
        assertNull(session.activeHoldPlannedSetId)
        assertNull(next.plannedSets.single().actualHoldSeconds)
        now += 15_000
        assertTrue(session.tick())
        assertEquals(next.id, session.activeHoldWorkoutExerciseId)
        assertEquals(now, session.holdStartedAt)
        assertEquals(0, next.plannedSets.single().actualHoldSeconds)
    }

    @Test
    fun skippingCardioRestStartsNextTimerAndRejectsStaleSkip() {
        val next = finishCardioWithRest()
        val target = session.backgroundTarget()!!
        assertNull(
            session.apply(VoiceCommand.SKIP, target.copy(restEndsAt = session.restEndsAt + 1))
        )
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(WorkoutVoiceSession.Cue.CHANGE, session.apply(VoiceCommand.SKIP, target))
        assertEquals(next.id, session.activeHoldWorkoutExerciseId)
        assertEquals(0L, session.restEndsAt)
        assertNull(session.apply(VoiceCommand.SKIP, target))
        assertEquals(next.id, session.activeHoldWorkoutExerciseId)
    }

    @Test
    fun disablingAutoStartDuringRestCancelsQueuedStart() {
        val next = finishCardioWithRest()
        data.activeWorkout!!.autoStartCardio = false
        now += 5000
        assertTrue(session.tick())
        assertNull(session.activeHoldPlannedSetId)
        assertNull(next.plannedSets.single().actualHoldSeconds)
    }

    @Test
    fun replacingWorkoutDuringRestCancelsQueuedStart() {
        val next = finishCardioWithRest()
        data.activeWorkout = data.activeWorkout!!.copy(id = "replacement-workout")
        now += 5000
        assertTrue(session.tick())
        assertNull(session.activeHoldPlannedSetId)
        assertNull(next.plannedSets.single().actualHoldSeconds)
    }

    @Test
    fun manuallyStartingAndStoppingNextCardioCancelsQueuedStart() {
        val next = finishCardioWithRest()
        session.preferredExerciseId = next.id
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 1000
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 5000
        assertFalse(session.tick())
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(1, next.plannedSets.single().actualHoldSeconds)
        assertTrue(next.sets.isEmpty())
    }

    @Test
    fun manualCardioCompletionWithRestWaitsForManualStart() {
        val next = addFollowingCardio()
        hold.restSecondsOverride = 5
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 3000
        session.apply(VoiceCommand.DONE, session.backgroundTarget()!!)
        assertEquals(3, hold.sets.single().holdSeconds)
        now += 5000
        assertTrue(session.tick())
        assertNull(session.activeHoldPlannedSetId)
        assertNull(next.plannedSets.single().actualHoldSeconds)
    }

    @Test
    fun nonCardioTimerDoesNotStartFollowingCardio() {
        addFollowingCardio()
        hold.plannedSets.add(PlannedSet(holdSeconds = 20))
        timed.cardio = false
        runHoldToGoal()
        assertNull(session.activeHoldPlannedSetId)
    }

    @Test
    fun cardioDoesNotStartFollowingNonCardioTimer() {
        val next = addFollowingCardio()
        data.exercises.single { it.id == next.exerciseId }.cardio = false
        runHoldToGoal()
        assertNull(session.activeHoldPlannedSetId)
    }

    @Test
    fun cardioDoesNotSkipInterveningRepetitionExercise() {
        addFollowingCardio()
        data.activeWorkout!!
            .exercises
            .add(
                2,
                WorkoutExercise(exerciseId = reps.id, plannedSets = mutableListOf(PlannedSet())),
            )
        runHoldToGoal()
        assertNull(session.activeHoldPlannedSetId)
    }

    @Test
    fun cardioStartsRemainingSetBeforeMovingToNextExercise() {
        val next = addFollowingCardio()
        hold.plannedSets.add(PlannedSet(holdSeconds = 10))
        runHoldToGoal()
        assertEquals(hold.id, session.activeHoldWorkoutExerciseId)
        assertEquals(hold.plannedSets[1].id, session.activeHoldPlannedSetId)
        assertEquals(now, session.holdStartedAt)
        assertEquals(0, hold.plannedSets[1].actualHoldSeconds)
        now += 10_000
        assertTrue(session.tick())
        assertEquals(2, hold.sets.size)
        assertEquals(next.id, session.activeHoldWorkoutExerciseId)
        assertEquals(next.plannedSets.single().id, session.activeHoldPlannedSetId)
        assertEquals(0, next.plannedSets.single().actualHoldSeconds)
    }

    @Test
    fun disablingAutoStartDuringHoldStopsTheChainAtCompletion() {
        val next = addFollowingCardio()
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        data.activeWorkout!!.autoStartCardio = false
        now += 10_000
        assertTrue(session.tick())
        assertEquals(10, hold.sets.single().holdSeconds)
        assertNull(session.activeHoldPlannedSetId)
        assertNull(next.plannedSets.single().actualHoldSeconds)
        assertEquals(0L, session.restEndsAt)
    }

    @Test
    fun disabledAutoStartAlsoWaitsBetweenSetsOfSameCardioExercise() {
        addFollowingCardio()
        hold.plannedSets.add(PlannedSet(holdSeconds = 10))
        data.activeWorkout!!.autoStartCardio = false
        runHoldToGoal()
        assertEquals(1, hold.sets.size)
        assertNull(session.activeHoldPlannedSetId)
        assertNull(hold.plannedSets[1].actualHoldSeconds)
    }

    @Test
    fun cardioDoesNotRestartCompletedNextExercise() {
        val next = addFollowingCardio()
        next.sets.add(loggedSetFrom(next.plannedSets.single(), timed))
        runHoldToGoal()
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(1, next.sets.size)
    }

    @Test
    fun cardioDoesNotStartScaleMeasurementButCanStartManualMode() {
        data.craneScale = CraneScaleDevice("AA:BB:CC:DD:EE:01", "Scale")
        addFollowingCardio()
        hold.plannedSets.add(PlannedSet(holdSeconds = 20))
        timed.weighted = true
        timed.liveMeasurement = true
        val completedSet = hold.plannedSets.first()
        hold.sets.add(loggedSetFrom(completedSet, timed))
        assertFalse(session.startFollowingCardio(hold, completedSet))
        assertNull(session.activeHoldPlannedSetId)
        hold.manualMeasurement = true
        assertTrue(session.startFollowingCardio(hold, completedSet))
        assertEquals(hold.id, session.activeHoldWorkoutExerciseId)
        assertEquals(hold.plannedSets[1].id, session.activeHoldPlannedSetId)
    }

    @Test
    fun nextCardioDoesNotStartScaleMeasurementButCanStartManualMode() {
        data.craneScale = CraneScaleDevice("AA:BB:CC:DD:EE:01", "Scale")
        val next = addFollowingCardio()
        data.exercises
            .single { it.id == next.exerciseId }
            .apply {
                weighted = true
                liveMeasurement = true
            }
        runHoldToGoal()
        assertNull(session.activeHoldPlannedSetId)
        assertNull(next.plannedSets.single().actualHoldSeconds)
        assertTrue(next.sets.isEmpty())
        next.manualMeasurement = true
        assertTrue(session.startFollowingCardio(hold, hold.plannedSets.single()))
        assertEquals(next.id, session.activeHoldWorkoutExerciseId)
        assertEquals(next.plannedSets.single().id, session.activeHoldPlannedSetId)
    }

    @Test
    fun scaleExerciseUsesManualTimerUntilAScaleIsConfigured() {
        timed.weighted = true
        timed.liveMeasurement = true
        session.preferredExerciseId = hold.id
        val target = session.backgroundTarget()!!
        assertEquals(VoiceTimerTarget(hold.id, hold.plannedSets[0].id), target.timer)
        data.craneScale = CraneScaleDevice("AA:BB:CC:DD:EE:01", "Scale")
        assertNull(session.backgroundTarget()!!.timer)
        data.craneScale = null
        assertNotNull(session.apply(VoiceCommand.TIME, target))
        assertEquals(hold.plannedSets[0].id, session.activeHoldPlannedSetId)
        assertFalse(hold.manualMeasurement)
    }

    @Test
    fun cardioFailureSetAndManualCompletionNeverStartNextTimer() {
        addFollowingCardio()
        hold.plannedSets.add(PlannedSet(holdSeconds = 20))
        hold.plannedSets.first().type = SetType.FAILURE
        runHoldToGoal()
        assertEquals(hold.id, session.activeHoldWorkoutExerciseId)
        session.apply(VoiceCommand.DONE, session.backgroundTarget()!!)
        assertNull(session.activeHoldPlannedSetId)
        hold.plannedSets.first().type = SetType.NORMAL
        hold.sets.clear()
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 3000
        session.apply(VoiceCommand.DONE, session.backgroundTarget()!!)
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(3, hold.sets.single().holdSeconds)
    }

    @Test
    fun stoppingEarlyDoesNotCompleteButDoneRecordsElapsedTime() {
        session.preferredExerciseId = hold.id
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 3000
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        assertTrue(hold.sets.isEmpty())
        assertEquals(3, hold.plannedSets[0].actualHoldSeconds)
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 4000
        session.apply(VoiceCommand.DONE, session.backgroundTarget()!!)
        assertEquals(4, hold.sets.single().holdSeconds)
        assertNull(session.activeHoldPlannedSetId)
    }

    @Test
    fun failureSetKeepsRunningAfterGoalAndStartsRestOnlyWhenStopped() {
        session.preferredExerciseId = hold.id
        hold.plannedSets[0].type = SetType.FAILURE
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 10_000
        assertTrue(session.tick())
        assertEquals(hold.plannedSets[0].id, session.backgroundTarget()!!.timer!!.setId)
        assertEquals(0L, session.restEndsAt)
        now += 5000
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        assertEquals(15, hold.sets.single().holdSeconds)
        assertEquals(now + 30_000, session.restEndsAt)
    }

    @Test
    fun workoutVoiceToggleRejectsQueuedCommandsAndResetsForNextWorkout() {
        session.preferredExerciseId = hold.id
        session.restEndsAt = now + 30_000
        val target = session.backgroundTarget()!!
        val workout = data.activeWorkout!!
        workout.voiceCommandsEnabled = false
        assertFalse(session.enabled)
        assertNull(session.backgroundTarget())
        listOf(
                VoiceCommand.TIME,
                VoiceCommand.DONE,
                VoiceCommand.SKIP,
            )
            .forEach { assertNull(session.apply(it, target)) }
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(now + 30_000, session.restEndsAt)
        assertEquals(10, hold.plannedSets[0].holdSeconds)
        assertTrue(hold.sets.isEmpty())
        assertEquals(0, saves)

        workout.voiceCommandsEnabled = true
        assertTrue(session.enabled)
        assertNotNull(session.backgroundTarget())
        data.activeWorkout = data.workoutFromRoutine(workout.toRoutine())
        assertFalse(data.activeWorkout!!.voiceCommandsEnabled)
        assertFalse(session.enabled)
        assertNull(session.backgroundTarget())
    }

    @Test
    fun disablingVoiceForWorkoutKeepsRunningTimer() {
        session.preferredExerciseId = hold.id
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        data.activeWorkout!!.voiceCommandsEnabled = false
        now += 10_000
        assertTrue(session.tick())
        assertEquals(10, hold.sets.single().holdSeconds)
        assertNull(session.activeHoldPlannedSetId)
        assertEquals(now + 30_000, session.restEndsAt)
    }

    @Test
    fun newWorkoutsRejectVoiceCommandsUntilEnabled() {
        val target = session.backgroundTarget()!!
        val original = data.activeWorkout!!
        val defaults =
            listOf(
                WorkoutSession(name = "Empty"),
                WorkoutSession(name = "Intervals", intervals = mutableListOf(WorkoutInterval())),
                data.workoutFromRoutine(original.toRoutine()),
            )
        defaults.forEach { workout ->
            data.activeWorkout = workout
            assertFalse(workout.voiceCommandsEnabled)
            assertFalse(session.enabled)
            assertNull(session.backgroundTarget())
            assertNull(session.apply(VoiceCommand.DONE, target))
        }
        assertEquals(0, saves)
    }

    @Test
    fun disablingVoiceOrEndingWorkoutRejectsCommands() {
        val target = session.backgroundTarget()!!
        val workout = data.activeWorkout!!
        workout.voiceCommandsEnabled = false
        assertNull(session.apply(VoiceCommand.DONE, target))
        assertNull(session.backgroundTarget())
        workout.voiceCommandsEnabled = true
        workout.finish()
        assertFalse(session.enabled)
        assertNull(session.apply(VoiceCommand.DONE, target))
        data.activeWorkout = null
        assertNull(session.apply(VoiceCommand.DONE, target))
        assertEquals(0, saves)
    }
}
