package lofit

import org.junit.Assert.*
import org.junit.Test

class PauseEverySecondSetTest {
    private val movement =
        Exercise("split", "Split squat", ExerciseType.REPETITIONS, true, "Quads", "Glutes")

    @Test
    fun enabledRestsAfterEachPairAndDisabledRestsAfterEachSet() {
        val item = WorkoutExercise(exerciseId = movement.id, pauseEverySecondSet = true)
        repeat(4) { index ->
            item.sets.add(LoggedSet(type = SetType.NORMAL, weightKg = 10.0))
            assertEquals(if (index % 2 == 0) 0 else 120, item.restSecondsAfterSet(120, movement))
        }
        item.sets.removeLast()
        assertEquals(0, item.restSecondsAfterSet(120, movement))
        item.pauseEverySecondSet = false
        assertEquals(120, item.restSecondsAfterSet(120, movement))
        item.pauseEverySecondSet = true
        assertEquals(120, item.restSecondsAfterSet(120, movement.copy(asymmetric = false)))
        item.sets.add(LoggedSet(type = SetType.NORMAL, weightKg = 10.0))
        item.restSecondsOverride = 45
        assertEquals(45, item.restSecondsAfterSet(120, movement))
        item.restSecondsOverride = 0
        assertEquals(0, item.restSecondsAfterSet(120, movement))
    }

    @Test
    fun settingSurvivesRoutineStartHistoryAndRoutineUpdates() {
        val routine =
            Routine(
                name = "Legs",
                exercises =
                    mutableListOf(
                        RoutineExercise(exerciseId = movement.id, pauseEverySecondSet = true)
                    ),
            )
        val data = AppData(routines = mutableListOf(routine))
        val workout = data.workoutFromRoutine(routine)
        val item = workout.exercises.single()
        assertTrue(item.pauseEverySecondSet)
        assertTrue(workout.toRoutine().exercises.single().pauseEverySecondSet)
        assertNull(workout.routineChanges(data.routines))
        item.pauseEverySecondSet = false
        workout.routineChanges(data.routines)!!.apply(updateSets = true, includeAdded = false)
        assertFalse(routine.exercises.single().pauseEverySecondSet)
        item.pauseEverySecondSet = true
        item.sets.add(LoggedSet(type = SetType.NORMAL, weightKg = 10.0))
        workout.finish()
        data.history.add(workout)
        assertTrue(data.lastKnownExercise(movement.id)!!.pauseEverySecondSet)
    }
}
