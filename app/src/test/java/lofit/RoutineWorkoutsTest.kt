package lofit

import org.junit.Assert.*
import org.junit.Test

class RoutineWorkoutsTest {
    private fun routine() = Routine(name = "Strength", exercises = mutableListOf(
        RoutineExercise(exerciseId = "bench", plannedSets = MutableList(3) {
            PlannedSet(weightKg = 60.0, reps = 5, type = SetType.NORMAL, side = Side.RIGHT)
        }),
    ))
    private fun data(routine: Routine) = AppData(routines = mutableListOf(routine), history = mutableListOf(
        WorkoutSession(name = "Previous", completedAt = 200, sourceRoutine = routine, exercises = mutableListOf(
            WorkoutExercise(exerciseId = "bench", restSecondsOverride = 45, sets = mutableListOf(
                LoggedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 12, side = Side.LEFT),
                LoggedSet(type = SetType.FAILURE, weightKg = 80.0, reps = 6, side = Side.LEFT),
            )),
        )),
    ))

    @Test fun routineKeepsCountAndKindsButUsesLatestLoadsAndLastRowForExtraSets() {
        val saved = routine()
        val data = data(saved)
        val workout = data.workoutFromRoutine(saved)
        val sets = workout.exercises.single().plannedSets
        assertEquals(3, sets.size)
        assertEquals(listOf(20.0, 80.0, 80.0), sets.map { it.weightKg })
        assertEquals(listOf(12, 6, 6), sets.map { it.reps })
        assertTrue(sets.all { it.type == SetType.NORMAL && it.side == Side.RIGHT })
        assertTrue(sets.all { it.actualHoldSeconds == null })
        assertEquals(List(3) { 60.0 }, saved.exercises.single().plannedSets.map { it.weightKg })
        assertNull(workout.routineChanges(data.routines))
        sets.first().actualHoldSeconds = 15
        workout.exercises.single().sets.add(LoggedSet(type = SetType.NORMAL, weightKg = 20.0, reps = 12))
        assertNull(workout.routineChanges(data.routines))
    }

    @Test fun sameRoutineHistoryWinsOverNewerOpenAndOtherRoutineWorkouts() {
        val saved = routine()
        val other = Routine(name = saved.name)
        val data = AppData(routines = mutableListOf(saved))
        fun session(at: Long, source: Routine?, weight: Double, count: Int) = WorkoutSession(
            name = saved.name, completedAt = at, sourceRoutine = source,
            exercises = mutableListOf(WorkoutExercise(exerciseId = "bench", sets = MutableList(count) {
                LoggedSet(type = SetType.NORMAL, weightKg = weight, reps = 7)
            })))
        data.history.addAll(listOf(
            session(500, other, 150.0, 4), session(100, saved, 50.0, 1),
            session(600, null, 120.0, 5), session(300, saved, 70.0, 2),
            session(700, saved, 0.0, 0),
        ))
        val sets = data.workoutFromRoutine(saved).exercises.single().plannedSets
        assertEquals(List(3) { 70.0 }, sets.map { it.weightKg })
        assertEquals(List(3) { 7 }, sets.map { it.reps })
        assertEquals(5, data.lastKnownExercise("bench")!!.plannedSets.size)
        assertEquals(List(5) { 120.0 }, data.lastKnownSets("bench")!!.map { it.weightKg })
    }

    @Test fun missingRoutineExerciseHistoryUsesSavedPrescription() {
        val saved = routine()
        saved.exercises.add(RoutineExercise(exerciseId = "plank", plannedSets = mutableListOf(PlannedSet(holdSeconds = 30))))
        val data = AppData(routines = mutableListOf(saved), history = mutableListOf(
            WorkoutSession(name = "Own", completedAt = 100, sourceRoutine = saved,
                exercises = mutableListOf(
                    WorkoutExercise(exerciseId = "bench", sets = mutableListOf(
                        LoggedSet(type = SetType.NORMAL, weightKg = 70.0, reps = 8))),
                    WorkoutExercise(exerciseId = "plank"),
                )),
            WorkoutSession(name = "Open", completedAt = 300, exercises = mutableListOf(
                WorkoutExercise(exerciseId = "bench", sets = mutableListOf(
                    LoggedSet(type = SetType.NORMAL, weightKg = 120.0, reps = 2))),
                WorkoutExercise(exerciseId = "plank", sets = mutableListOf(
                    LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 90))),
            )),
        ))
        val workout = data.workoutFromRoutine(saved)
        assertEquals(List(3) { 70.0 }, workout.exercises.first().plannedSets.map { it.weightKg })
        assertEquals(30, workout.exercises.last().plannedSets.single().holdSeconds)
    }

    @Test fun routineWithoutOwnHistoryUsesExactSavedPrescriptionDespiteOtherHistory() {
        val saved = routine()
        saved.exercises.single().plannedSets.apply {
            clear()
            add(PlannedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 12, holdSeconds = 15, side = Side.LEFT))
            add(PlannedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 5, holdSeconds = 30, side = Side.RIGHT))
            add(PlannedSet(type = SetType.FAILURE, weightKg = 65.0, reps = 3, holdSeconds = 45))
        }
        val data = data(saved)
        data.history[0] = data.history[0].copy(sourceRoutine = null)
        data.history.add(data.history[0].copy(sourceRoutine = Routine(name = saved.name)))
        val sets = data.workoutFromRoutine(saved).exercises.single().plannedSets
        assertEquals(saved.exercises.single().plannedSets.map { it.copy(id = "") }, sets.map { it.copy(id = "") })
        assertNotEquals(saved.exercises.single().plannedSets.first().id, sets.first().id)
        assertNull(data.workoutFromRoutine(saved).routineChanges(data.routines))
    }

    @Test fun matchingWorkingSetsDoNotUseWarmupLoads() {
        val saved = routine()
        val data = data(saved)
        data.history.single().exercises.single().sets.last().type = SetType.NORMAL
        val sets = data.workoutFromRoutine(saved).exercises.single().plannedSets
        assertEquals(List(3) { 80.0 }, sets.map { it.weightKg })
        assertEquals(List(3) { 6 }, sets.map { it.reps })
    }

    @Test fun routineWithFewerRowsTruncatesHistoryAndEmptyPrescriptionStaysEmpty() {
        val saved = routine()
        saved.exercises.single().plannedSets.removeAt(2)
        saved.exercises.single().plannedSets.removeAt(1)
        val data = data(saved)
        assertEquals(1, data.workoutFromRoutine(saved).exercises.single().plannedSets.size)
        saved.exercises.single().plannedSets.clear()
        assertTrue(data.workoutFromRoutine(saved).exercises.single().plannedSets.isEmpty())
    }

    @Test fun noHistoryUsesSavedPrescriptionAndCopiesWithoutMutatingRoutine() {
        val saved = routine()
        val data = AppData(routines = mutableListOf(saved))
        val workout = data.workoutFromRoutine(saved)
        assertEquals(List(3) { 60.0 }, workout.exercises.single().plannedSets.map { it.weightKg })
        workout.exercises.single().plannedSets.first().weightKg = 90.0
        assertEquals(60.0, saved.exercises.single().plannedSets.first().weightKg, 0.0)
    }

    @Test fun emptyWorkoutCopiesExactLoggedExerciseIncludingRestAndSetCount() {
        val data = data(routine())
        val item = data.lastKnownExercise("bench")!!
        assertEquals(45, item.restSecondsOverride)
        assertEquals(2, item.plannedSets.size)
        assertEquals(listOf(SetType.WARMUP, SetType.FAILURE), item.plannedSets.map { it.type })
        assertEquals(listOf(20.0, 80.0), item.plannedSets.map { it.weightKg })
        assertEquals(listOf(12, 6), item.plannedSets.map { it.reps })
        assertTrue(item.plannedSets.all { it.side == Side.LEFT })
        assertTrue(item.sets.isEmpty())
        assertNull(item.sourceRoutineExerciseId)
        item.plannedSets.first().weightKg = 100.0
        assertEquals(20.0, data.history.single().exercises.single().sets.first().weightKg, 0.0)
    }

    @Test fun emptyWorkoutKeepsPreviousEffectiveRestWhenExerciseInheritedIt() {
        val data = data(routine())
        data.history.single().defaultRestSeconds = 75
        data.history.single().exercises.single().restSecondsOverride = null
        assertEquals(75, data.lastKnownExercise("bench")!!.restSecondsOverride)
    }

    @Test fun changesAndAdditionsAreIndependentOptInsAndKeepUnchangedRows() {
        val saved = routine()
        saved.exercises.add(RoutineExercise(exerciseId = "bench", plannedSets = mutableListOf(PlannedSet(weightKg = 30.0))))
        val data = data(saved)
        val workout = data.workoutFromRoutine(saved)
        workout.exercises.first().plannedSets.add(PlannedSet(weightKg = 85.0, reps = 7))
        val added = WorkoutExercise(exerciseId = "plank", restSecondsOverride = 15,
            plannedSets = mutableListOf(PlannedSet(holdSeconds = 45, actualHoldSeconds = 12)))
        workout.exercises.add(added)
        val changes = workout.routineChanges(data.routines)!!
        assertEquals(1, changes.changed.size)
        assertEquals(listOf(added), changes.added)
        changes.apply(updateSets = false, includeAdded = true)
        assertEquals(3, saved.exercises.first().plannedSets.size)
        assertEquals(30.0, saved.exercises[1].plannedSets.single().weightKg, 0.0)
        assertEquals("plank", saved.exercises.last().exerciseId)
        assertEquals(45, saved.exercises.last().plannedSets.single().holdSeconds)
        assertNull(saved.exercises.last().plannedSets.single().actualHoldSeconds)
        assertEquals(saved.exercises.last().id, added.sourceRoutineExerciseId)
        changes.apply(updateSets = true, includeAdded = false)
        assertEquals(4, saved.exercises.first().plannedSets.size)
        assertEquals(85.0, saved.exercises.first().plannedSets.last().weightKg, 0.0)
        assertEquals(30.0, saved.exercises[1].plannedSets.single().weightKg, 0.0)
    }

    @Test fun declinedChangesAndDeletedSourceNeverAlterRoutine() {
        val saved = routine()
        val data = data(saved)
        val workout = data.workoutFromRoutine(saved)
        workout.exercises.single().plannedSets.clear()
        val changes = workout.routineChanges(data.routines)!!
        changes.apply(updateSets = false, includeAdded = false)
        assertEquals(3, saved.exercises.single().plannedSets.size)
        data.routines.clear()
        assertNull(workout.routineChanges(data.routines))
    }

    @Test fun resumedWorkoutUsesFreshBaselineAndDoesNotAskAgainAboutOldEdits() {
        val saved = routine()
        val data = data(saved)
        val workout = data.workoutFromRoutine(saved)
        workout.exercises.single().plannedSets.first().weightKg = 85.0
        workout.finish()
        data.history.add(workout)
        val resumed = data.resumeWorkout(workout.id)!!
        assertNull(resumed.routineChanges(data.routines))
        resumed.exercises.single().plannedSets.first().weightKg = 90.0
        assertNotNull(resumed.routineChanges(data.routines))
    }
}
