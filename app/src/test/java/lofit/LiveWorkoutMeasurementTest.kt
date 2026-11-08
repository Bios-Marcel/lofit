package lofit

import org.junit.Assert.*
import org.junit.Test

class LiveWorkoutMeasurementTest {
    private val movement =
        Exercise(
            "live",
            "Live pull",
            ExerciseType.TIMED,
            false,
            "Forearms",
            "—",
            weighted = true,
            liveMeasurement = true,
            custom = true,
        )
    private val first = PlannedSet(holdSeconds = 2, weightKg = 50.0)
    private val second = PlannedSet(holdSeconds = 3)
    private val item =
        WorkoutExercise(exerciseId = movement.id, plannedSets = mutableListOf(first, second))
    private val workout = WorkoutSession(name = "Live", exercises = mutableListOf(item))
    private val data =
        AppData(
            exercises = mutableListOf(movement),
            activeWorkout = workout,
            craneScale = CraneScaleDevice("AA:BB:CC:DD:EE:01", "Scale"),
        )
    private val measurement = LiveWorkoutMeasurement { data }

    @Test
    fun missingScalePreventsReadingsAndForgettingScaleDropsActivePull() {
        val selected = data.craneScale
        data.craneScale = null
        assertFalse(measurement.isLive(item))
        assertNull(measurement.read(0, 20.0))
        assertTrue(measurement.seriesFor(item.id).samples.isEmpty())
        data.craneScale = selected
        assertTrue(measurement.isLive(item))
        assertTrue(measurement.read(100, 20.0)!!.started)
        data.craneScale = null
        measurement.reconcile()
        assertFalse(measurement.isLive(item))
        assertNull(measurement.active)
        assertNull(measurement.read(2_100, 0.0))
        assertTrue(item.sets.isEmpty())
        assertFalse(item.manualMeasurement)
    }

    @Test
    fun requiresTimedWeightedExercise() {
        assertThrows(IllegalArgumentException::class.java) {
            movement.copy(type = ExerciseType.REPETITIONS)
        }
        assertThrows(IllegalArgumentException::class.java) { movement.copy(weighted = false) }
        movement.type = ExerciseType.REPETITIONS
        assertFalse(movement.supportsLiveMeasurement)
        assertNull(measurement.read(0, 20.0))
    }

    @Test
    fun startsAboveOneKiloAndStopsAtOneKiloWithoutRestartingBelowIt() {
        assertNull(measurement.read(0, 0.0))
        assertNull(measurement.read(100, 1.0))
        assertTrue(measurement.read(200, 1.01)!!.started)
        assertNull(measurement.read(300, 1.01))
        assertNotNull(measurement.active)
        assertFalse(measurement.read(400, 1.0)!!.completed)
        assertNull(measurement.active)
        assertTrue(item.sets.isEmpty())
        assertNull(measurement.read(500, 0.75))
        assertNull(measurement.read(600, 1.0))
        assertNull(measurement.active)
    }

    @Test
    fun oneKiloReleaseCompletesOnlyAfterTheDesiredTime() {
        measurement.read(0, 10.0)
        assertNull(measurement.read(2_000, 1.01))
        assertTrue(item.sets.isEmpty())
        assertTrue(measurement.read(2_100, 1.0)!!.completed)
        assertNull(measurement.active)
        assertEquals(2, first.actualHoldSeconds)
        assertEquals(10.0, item.sets.single().weightKg, 0.0)
        assertEquals(first.id, item.sets.single().plannedSetId)
    }

    @Test
    fun releaseLogsFullDurationAndMeanAfterTrimmingEdges() {
        measurement.read(1_000, 1.1)
        measurement.read(1_200, 10.0)
        measurement.read(1_400, 20.0)
        measurement.read(2_000, 20.0)
        measurement.read(3_000, 20.0)
        measurement.read(4_500, 5.0)
        val result = measurement.read(4_900, 0.0)!!
        assertTrue(result.completed)
        assertEquals(20.0, first.weightKg, 0.00001)
        assertEquals(3, first.actualHoldSeconds)
        val logged = item.sets.single()
        assertEquals(20.0, logged.weightKg, 0.00001)
        assertEquals(3, logged.holdSeconds)
        assertEquals(first.id, logged.plannedSetId)
        assertEquals(0, logged.reps)
        assertEquals(2, first.holdSeconds)
    }

    @Test
    fun reachingGoalOnlyCuesAndReleaseCompletes() {
        measurement.read(0, 10.0)
        measurement.read(100, 10.0)
        assertFalse(measurement.tick(1_999))
        assertTrue(measurement.tick(2_000))
        assertFalse(measurement.tick(3_000))
        assertTrue(item.sets.isEmpty())
        assertNotNull(measurement.active)
        assertTrue(measurement.read(3_000, 0.0)!!.completed)
    }

    @Test
    fun shortPullWritesResultButRetryStartsFreshOnSameSet() {
        measurement.read(0, 10.0)
        measurement.read(100, 10.0)
        assertFalse(measurement.read(1_999, 0.0)!!.completed)
        assertEquals(1, first.actualHoldSeconds)
        assertEquals(10.0, first.weightKg, 0.0)
        assertTrue(item.sets.isEmpty())
        assertTrue(measurement.read(3_000, 30.0)!!.started)
        measurement.read(3_100, 30.0)
        assertEquals(0, first.actualHoldSeconds)
        assertTrue(measurement.read(5_000, 0.0)!!.completed)
        assertEquals(30.0, item.sets.single().weightKg, 0.0)
        assertEquals(2, item.sets.single().holdSeconds)
    }

    @Test
    fun manualCompletionKeepsMeasuredValuesAndWaitsForReleaseBeforeNextSet() {
        measurement.read(1_000, 20.0)
        measurement.read(1_100, 20.0)
        measurement.read(2_100, 20.0)
        assertNotNull(measurement.stopForManualCompletion(first, 2_500))
        assertNull(measurement.active)
        assertEquals(1, first.actualHoldSeconds)
        assertEquals(20.0, first.weightKg, 0.0)
        assertTrue(item.sets.isEmpty())
        item.sets.add(
            LoggedSet(
                type = first.type,
                weightKg = first.weightKg,
                holdSeconds = requireNotNull(first.actualHoldSeconds),
                plannedSetId = first.id,
            )
        )
        assertNull(measurement.read(2_600, 30.0))
        assertFalse(measurement.tick(5_000))
        assertNull(measurement.active)
        assertEquals(1, first.actualHoldSeconds)
        assertNull(measurement.read(5_000, 0.0))
        assertEquals(1, item.sets.size)
        assertEquals(second.id, measurement.read(6_000, 30.0)!!.pull.set.id)
        assertTrue(measurement.read(9_000, 0.0)!!.completed)
        assertEquals(2, item.sets.size)
        assertEquals(1, item.sets.first().holdSeconds)
        assertEquals(20.0, item.sets.first().weightKg, 0.0)
    }

    @Test
    fun manuallyCompletingAnotherSetLeavesCurrentPullRunning() {
        measurement.read(1_000, 20.0)
        assertNull(measurement.stopForManualCompletion(second, 1_500))
        assertEquals(first.id, measurement.active!!.set.id)
        assertTrue(measurement.read(3_000, 0.0)!!.completed)
    }

    @Test
    fun eachPullAdvancesExactlyOneSetThenNextExercise() {
        val next =
            item.copy(
                id = "next",
                plannedSets = mutableListOf(PlannedSet(holdSeconds = 1)),
                sets = mutableListOf(),
            )
        workout.exercises.add(next)
        measurement.read(0, 10.0)
        measurement.read(100, 10.0)
        measurement.read(2_000, 0.0)
        assertEquals(second.id, measurement.read(3_000, 20.0)!!.pull.set.id)
        measurement.read(3_100, 20.0)
        measurement.read(6_000, 0.0)
        assertEquals(next.id, measurement.read(7_000, 30.0)!!.pull.item.id)
        measurement.read(7_100, 30.0)
        measurement.read(8_000, 0.0)
        assertEquals(2, item.sets.size)
        assertEquals(1, next.sets.size)
        assertNull(measurement.read(9_000, 50.0))
    }

    @Test
    fun manualOverridePreventsMeasurementButLaterLiveExercisesCanStillMeasure() {
        item.manualMeasurement = true
        assertNull(measurement.read(0, 20.0))
        assertNull(measurement.active)
        assertTrue(measurement.seriesFor(item.id).samples.isEmpty())
        val later =
            item.copy(
                id = "later",
                manualMeasurement = false,
                plannedSets = mutableListOf(PlannedSet(holdSeconds = 1)),
                sets = mutableListOf(),
            )
        workout.exercises.add(later)
        assertEquals(later.id, measurement.read(100, 20.0)!!.pull.item.id)
        measurement.read(200, 20.0)
        assertTrue(measurement.read(1_100, 0.0)!!.completed)
        assertTrue(item.sets.isEmpty())
        assertEquals(1, later.sets.size)
        assertEquals(3, measurement.seriesFor(later.id).samples.size)
    }

    @Test
    fun manualTimerCanBlockStartingAPull() {
        assertNull(measurement.read(0, 10.0, allowStart = false))
        assertTrue(measurement.read(100, 10.0)!!.started)
    }

    @Test
    fun interruptionNeverCompletesAndUsesOnlyObservedTime() {
        measurement.read(1_000, 1.1)
        measurement.read(1_100, 15.0)
        measurement.read(1_500, 15.0)
        measurement.read(2_000, 15.0)
        measurement.tick(9_000)
        assertFalse(measurement.interrupt()!!.completed)
        assertEquals(1, first.actualHoldSeconds)
        assertEquals(15.0, first.weightKg, 0.0)
        assertTrue(item.sets.isEmpty())
        assertNull(measurement.active)
        measurement.read(10_000, 30.0)
        assertTrue(measurement.seriesFor(item.id).samples.last().startsSegment)
    }

    @Test
    fun ignoresInvalidAndOutOfOrderReadings() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach {
            assertNull(measurement.read(0, it))
        }
        measurement.read(1_000, 10.0)
        measurement.read(1_100, 10.0)
        assertNull(measurement.read(500, 0.0))
        assertNull(measurement.read(500, 100.0))
        assertTrue(measurement.read(3_000, 0.0)!!.completed)
        assertEquals(10.0, first.weightKg, 0.0)
    }

    @Test
    fun trimsDetectedRampsAndRoundsOnlyTheFinalMean() {
        first.holdSeconds = 1
        measurement.read(1_000, 1.1)
        measurement.read(1_100, 8.0)
        measurement.read(1_200, 20.111)
        measurement.read(1_400, 20.122)
        measurement.read(1_900, 20.133)
        measurement.read(2_200, 7.0)
        measurement.read(2_400, 2.0)
        assertTrue(measurement.read(2_500, 0.0)!!.completed)
        assertEquals(20.12, first.weightKg, 0.0)
        assertEquals(20.12, item.sets.single().weightKg, 0.0)
        assertEquals(1, item.sets.single().holdSeconds)
    }

    @Test
    fun roundsHalfUpToTwoDecimalPlaces() {
        measurement.read(0, 1.1)
        measurement.read(100, 10.005)
        measurement.read(2_000, 10.005)
        assertTrue(measurement.read(2_100, 0.0)!!.completed)
        assertEquals(10.01, first.weightKg, 0.0)
        assertEquals(10.01, item.sets.single().weightKg, 0.0)
    }

    @Test
    fun instantPullKeepsItsFullLoadReadingInsteadOfApplyingATimeTrim() {
        measurement.read(0, 10.0)
        assertTrue(measurement.read(2_000, 0.0)!!.completed)
        assertEquals(10.0, first.weightKg, 0.0)
        assertEquals(2, first.actualHoldSeconds)
        assertEquals(10.0, item.sets.single().weightKg, 0.0)
    }

    @Test
    fun shortFlatPullKeepsAllReadingsButStaysUnfinished() {
        measurement.read(0, 10.0)
        measurement.read(100, 10.0)
        assertFalse(measurement.read(150, 0.0)!!.completed)
        assertEquals(10.0, first.weightKg, 0.0)
        assertTrue(item.sets.isEmpty())
    }

    @Test
    fun averageIncludesInteriorReadingsEvenAfterTheyAgeOutOfTheGraph() {
        measurement.read(0, 1.1)
        measurement.read(100, 15.0)
        measurement.read(30_000, 15.0)
        measurement.read(70_000, 15.0)
        assertTrue(measurement.read(70_050, 0.0)!!.completed)
        assertEquals(15.0, first.weightKg, 0.0)
        assertEquals(70, item.sets.single().holdSeconds)
        assertFalse(measurement.seriesFor(item.id).samples.any { it.atMillis == 100L })
    }

    @Test
    fun removingActiveSetOrWorkoutDropsRuntimeState() {
        measurement.read(0, 10.0)
        item.plannedSets.remove(first)
        measurement.reconcile()
        assertNull(measurement.active)
        assertTrue(item.sets.isEmpty())
        val series = measurement.seriesFor(item.id)
        assertEquals(1, series.samples.size)
        data.activeWorkout = null
        measurement.reconcile()
        assertNull(measurement.active)
        assertTrue(measurement.seriesFor(item.id).samples.isEmpty())
    }

    @Test
    fun routineDefaultsLiveAndPreservesExplicitManualChoiceAcrossConversionsAndUpdates() {
        val routine =
            Routine(
                name = "Pulls",
                exercises = mutableListOf(RoutineExercise(exerciseId = movement.id)),
            )
        data.routines.add(routine)
        val liveWorkout = data.workoutFromRoutine(routine)
        assertFalse(liveWorkout.exercises.single().manualMeasurement)
        routine.exercises.single().manualMeasurement = true
        val manualWorkout = data.workoutFromRoutine(routine)
        assertTrue(manualWorkout.exercises.single().manualMeasurement)
        assertTrue(manualWorkout.toRoutine().exercises.single().manualMeasurement)
        assertTrue(manualWorkout.captureRoutineBaseline()!!.exercises.single().manualMeasurement)
        manualWorkout.exercises.single().manualMeasurement = false
        val changes = manualWorkout.routineChanges(data.routines)!!
        assertEquals(1, changes.changed.size)
        changes.apply(updateSets = true, includeAdded = false)
        assertFalse(routine.exercises.single().manualMeasurement)
    }
}
