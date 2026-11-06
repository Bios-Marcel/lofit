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
            voiceCommandsEnabled = true
            exercises.addAll(listOf(reps, timed))
            activeWorkout =
                WorkoutSession(
                    name = "Test",
                    defaultRestSeconds = 30,
                    exercises = mutableListOf(first, hold),
                )
        }
    private var now = 1000L
    private var saves = 0
    private val session = WorkoutVoiceSession({ data }, { saves++ }, { now })

    @Test
    fun updatesGoalsForAllFourExerciseKindsWithoutChangingElapsedTime() {
        for (weighted in listOf(false, true)) {
            for (exercise in listOf(reps, timed)) {
                exercise.weighted = weighted
                val item = if (exercise === reps) first else hold
                val set = item.plannedSets[0]
                set.actualHoldSeconds = 7
                session.preferredExerciseId = item.id
                val target = session.backgroundTarget()!!
                val command = VoiceCommand.SetValues(if (weighted) 12.5 else null, 30)
                assertNotNull(session.apply(command, target))
                assertEquals(30, if (exercise.type.tracksDuration) set.holdSeconds else set.reps)
                assertEquals(if (weighted) 12.5 else 0.0, set.weightKg, 0.0)
                assertEquals(7, set.actualHoldSeconds)
                assertNull(session.activeHoldPlannedSetId)
                assertTrue(item.sets.isEmpty())
                val wrongShape = command.copy(weightKg = if (weighted) null else 50.0, amount = 99)
                assertNull(session.apply(wrongShape, target))
                assertEquals(30, if (exercise.type.tracksDuration) set.holdSeconds else set.reps)
            }
        }
    }

    @Test
    fun changingRunningHoldGoalKeepsElapsedTimeAndUsesNewGoal() {
        timed.weighted = true
        session.preferredExerciseId = hold.id
        session.apply(VoiceCommand.TIME, session.backgroundTarget()!!)
        now += 5000
        assertFalse(session.tick())
        session.apply(VoiceCommand.SetValues(12.5, 30), session.backgroundTarget()!!)
        assertEquals(5, hold.plannedSets[0].actualHoldSeconds)
        now += 5000
        assertFalse(session.tick())
        assertTrue(hold.sets.isEmpty())
        now += 20_000
        assertTrue(session.tick())
        assertEquals(30, hold.sets.single().holdSeconds)
        assertEquals(12.5, hold.sets.single().weightKg, 0.0)
    }

    @Test
    fun updatesCompletesAndAdvancesSetsWithoutViewsAndRejectsStaleTargets() {
        val target = session.backgroundTarget()!!
        assertNull(target.timer)
        assertNotNull(session.apply(VoiceCommand.SetValues(52.5, 12), target))
        assertTrue(first.sets.isEmpty())
        assertEquals(52.5, first.plannedSets[0].weightKg, 0.0)
        assertEquals(WorkoutVoiceSession.Cue.DONE, session.apply(VoiceCommand.DONE, target))
        assertEquals(12, first.sets.single().reps)
        assertEquals(52.5, first.sets.single().weightKg, 0.0)
        assertNull(session.apply(VoiceCommand.DONE, target))
        assertNull(session.apply(VoiceCommand.SetValues(99.0, 99), target))
        val next = session.backgroundTarget()!!
        assertEquals(first.plannedSets[1].id, next.done!!.setId)
        assertEquals(31_000L, next.restEndsAt)
        session.apply(VoiceCommand.SKIP, next)
        assertEquals(0L, session.restEndsAt)
        session.apply(VoiceCommand.DONE, session.backgroundTarget()!!)
        assertNull(session.apply(VoiceCommand.SKIP, next.copy(restEndsAt = 999)))
        assertEquals(hold.id, session.backgroundTarget()!!.done!!.exerciseId)
        assertEquals(3, saves)
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
    fun disablingVoiceOrEndingWorkoutRejectsCommands() {
        val target = session.backgroundTarget()!!
        data.voiceCommandsEnabled = false
        assertNull(session.apply(VoiceCommand.DONE, target))
        assertNull(session.backgroundTarget())
        data.voiceCommandsEnabled = true
        data.activeWorkout = null
        assertNull(session.apply(VoiceCommand.DONE, target))
        assertEquals(0, saves)
    }
}
