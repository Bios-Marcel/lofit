package lofit

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSessionTest {
    @Test fun finishingWorkoutSavesUntouchedNoteHintsAndNewNotes() {
        val workout = WorkoutSession(name = "Notes", exercises = mutableListOf(
            WorkoutExercise(exerciseId = "bench", previousNote = "Keep elbows tucked"),
            WorkoutExercise(exerciseId = "squat", previousNote = "Old cue", note = "New cue"),
            WorkoutExercise(exerciseId = "row", previousNote = "Slow descent", note = " \n"),
            WorkoutExercise(exerciseId = "plank"),
        ))
        workout.finish()
        assertEquals(listOf("Keep elbows tucked", "New cue", "Slow descent", ""), workout.exercises.map { it.note })
        assertTrue(workout.exercises.all { it.previousNote.isEmpty() })
    }

    @Test
    fun workoutTime_usesHoursAndMinutesFromOneHour() {
        assertEquals("59:59", formatWorkoutTime(3_599))
        assertEquals("1h 0m", formatWorkoutTime(3_600))
        assertEquals("1h 5m", formatWorkoutTime(3_901))
        assertEquals("2h 17m", formatWorkoutTime(8_279))
    }

    @Test
    fun search_matchesEachWordAcrossFieldsIgnoringCase() {
        assert(matchesSearchQuery("CURL dumb", "Dumbbell curl", "Biceps"))
        assert(matchesSearchQuery("dumb bic", "Dumbbell curl", "Biceps"))
        assert(matchesSearchQuery("  dumb   BIC  ", "Dumbbell curl", "Biceps"))
        assert(!matchesSearchQuery("dumb tri", "Dumbbell curl", "Biceps"))
        assert(matchesSearchQuery("", "Dumbbell curl", "Biceps"))
    }

    @Test
    fun muscleGroups_coverCatalogAndNormalizeSelections() {
        val catalogGroups = DefaultData.exercises().flatMap {
            listOf(it.primaryMuscle) + it.secondaryMuscles.split(',')
        }.map(String::trim).filter { it.isNotBlank() && it != "—" }

        assert(catalogGroups.all { MuscleGroups.canonical(it) != null })
        assertEquals(MuscleGroups.all.size, MuscleGroups.all.distinctBy(String::lowercase).size)
        assertEquals("Glutes, Hamstrings", MuscleGroups.canonicalList("glutes, hamstrings"))
        assertEquals("Glutes, Hamstrings", MuscleGroups.canonicalList("Glutes, Hamstrings, "))
        assertNull(MuscleGroups.canonicalList("Not a muscle"))
    }

    @Test
    fun moveExercise_reordersItemsAndIgnoresInvalidMoves() {
        val workout = WorkoutSession(
            name = "Test",
            exercises = mutableListOf(
                WorkoutExercise(exerciseId = "squat"),
                WorkoutExercise(exerciseId = "press"),
                WorkoutExercise(exerciseId = "row"),
            ),
        )

        workout.moveExercise(2, 0)
        workout.moveExercise(0, -1)

        assertEquals(listOf("row", "squat", "press"), workout.exercises.map { it.exerciseId })
    }

    @Test
    fun historyWorkout_convertsCompletedWorkToRoutine() {
        val firstPlannedSet = PlannedSet(weightKg = 60.0, reps = 8)
        val secondPlannedSet = PlannedSet(type = SetType.FAILURE, weightKg = 70.0, reps = 4, side = Side.RIGHT)
        val unloggedPlankSet = PlannedSet(weightKg = 10.0, holdSeconds = 45)
        val workout = WorkoutSession(
            name = "Push day",
            defaultRestSeconds = 120,
            exercises = mutableListOf(
                WorkoutExercise(
                    exerciseId = "bench-press",
                    restSecondsOverride = 75,
                    plannedSets = mutableListOf(firstPlannedSet, secondPlannedSet),
                    sets = mutableListOf(
                        LoggedSet(type = secondPlannedSet.type, weightKg = 72.5, reps = 3, side = secondPlannedSet.side, plannedSetId = secondPlannedSet.id),
                        LoggedSet(type = firstPlannedSet.type, weightKg = 62.5, reps = 7, plannedSetId = firstPlannedSet.id),
                    ),
                ),
                WorkoutExercise(
                    exerciseId = "plank",
                    restSecondsOverride = 30,
                    plannedSets = mutableListOf(unloggedPlankSet),
                ),
            ),
        )

        val routine = workout.toRoutine()

        assertEquals("Push day", routine.name)
        assertEquals(120, routine.defaultRestSeconds)
        assertEquals(listOf("bench-press", "plank"), routine.exercises.map { it.exerciseId })
        val bench = routine.exercises[0]
        assertEquals(75, bench.restSecondsOverride)
        assertEquals(listOf(62.5, 72.5), bench.plannedSets.map { it.weightKg })
        assertEquals(listOf(7, 3), bench.plannedSets.map { it.reps })
        assertEquals(listOf(SetType.NORMAL, SetType.FAILURE), bench.plannedSets.map { it.type })
        assertEquals(listOf(Side.BOTH, Side.RIGHT), bench.plannedSets.map { it.side })
        val plank = routine.exercises[1]
        assertEquals(30, plank.restSecondsOverride)
        assertEquals(listOf(45), plank.plannedSets.map { it.holdSeconds })
        assertEquals(listOf(10.0), plank.plannedSets.map { it.weightKg })
        assertTrue(plank.plannedSets.single().id != unloggedPlankSet.id)
    }

    @Test
    fun finishedWorkout_resumesAtItsPreviousElapsedTime() {
        val dayStart = LocalDate.of(2026, 10, 24).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val finished = WorkoutSession(
            name = "Paused",
            startedAt = dayStart + 1_000,
            completedAt = dayStart + 6_000,
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "bench-press",
                sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 8)),
            )),
        )
        val data = AppData(history = mutableListOf(finished))

        val resumed = data.resumeWorkout(finished.id, resumedAt = dayStart + 20_000)

        assertEquals(finished.id, resumed?.id)
        assertEquals(dayStart + 1_000L, resumed?.startedAt)
        assertEquals(listOf(
            WorkoutInterval(dayStart + 1_000, dayStart + 6_000),
            WorkoutInterval(dayStart + 20_000),
        ), resumed?.intervals)
        assertEquals(5_000L, resumed?.durationMillis(dayStart + 20_000))
        assertEquals(8_000L, resumed?.durationMillis(dayStart + 23_000))
        assertEquals(null, resumed?.completedAt)
        assertEquals(1, resumed?.setCount())
        assertEquals(resumed, data.activeWorkout)
        assertEquals(0, data.history.size)
    }

    @Test
    fun resumedWorkout_excludesEveryBreakAndKeepsFirstStart() {
        val dayStart = LocalDate.of(2026, 10, 24).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val workout = WorkoutSession(name = "Intervals", startedAt = dayStart + 1_000)
        val data = AppData()
        workout.finish(dayStart + 6_000)
        data.history.add(workout)

        val firstResume = data.resumeWorkout(workout.id, dayStart + 20_000)!!
        firstResume.finish(dayStart + 23_000)
        data.activeWorkout = null
        data.history.add(firstResume)
        val secondResume = data.resumeWorkout(workout.id, dayStart + 50_000)!!
        secondResume.finish(dayStart + 54_000)
        secondResume.finish(dayStart + 60_000)

        assertEquals(dayStart + 1_000, secondResume.startedAt)
        assertEquals(dayStart + 54_000, secondResume.completedAt)
        assertEquals(listOf(
            WorkoutInterval(dayStart + 1_000, dayStart + 6_000),
            WorkoutInterval(dayStart + 20_000, dayStart + 23_000),
            WorkoutInterval(dayStart + 50_000, dayStart + 54_000),
        ), secondResume.intervals)
        assertEquals(12_000L, secondResume.durationMillis(dayStart + 90_000))
        assertEquals(listOf(WorkoutInterval(dayStart + 1_000, dayStart + 6_000)), workout.intervals)
    }

    @Test
    fun workoutDuration_neverCountsNegativeIntervals() {
        val workout = WorkoutSession(name = "Clock changed", startedAt = 5_000)

        assertEquals(0L, workout.durationMillis(3_000))
        workout.finish(4_000)
        assertEquals(0L, workout.durationMillis(9_000))
    }

    @Test
    fun finishedWorkout_cannotResumeAcrossLocalMidnight() {
        val midnight = LocalDate.of(2026, 10, 24).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val finished = WorkoutSession(name = "Yesterday", startedAt = midnight - 60_000, completedAt = midnight - 1)
        val data = AppData(history = mutableListOf(finished))

        assertNull(data.resumeWorkout(finished.id, resumedAt = midnight))

        assertNull(data.activeWorkout)
        assertEquals(listOf(finished), data.history)
    }

    @Test
    fun finishedWorkout_usesCompletionDayForOvernightWorkout() {
        val midnight = LocalDate.of(2026, 10, 24).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val finished = WorkoutSession(name = "Overnight", startedAt = midnight - 60_000, completedAt = midnight + 60_000)
        val data = AppData(history = mutableListOf(finished))

        assertEquals(finished.id, data.resumeWorkout(finished.id, resumedAt = midnight + 120_000)?.id)
    }

    @Test
    fun unfinishedHistoryWorkout_cannotResume() {
        val unfinished = WorkoutSession(name = "Unfinished")
        val data = AppData(history = mutableListOf(unfinished))

        assertNull(data.resumeWorkout(unfinished.id))

        assertNull(data.activeWorkout)
        assertEquals(listOf(unfinished), data.history)
    }

    @Test
    fun totals_includeAllLoggedSets() {
        val workout = WorkoutSession(
            name = "Volume",
            exercises = mutableListOf(
                WorkoutExercise(
                    exerciseId = "bench",
                    sets = mutableListOf(
                        LoggedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10),
                        LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 5),
                    ),
                ),
                WorkoutExercise(
                    exerciseId = "plank",
                    sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 45)),
                ),
            ),
        )

        assertEquals(3, workout.setCount())
        assertEquals(500.0, workout.totalVolume(), 0.001)
    }

    @Test
    fun loggedSet_rejectsNegativeValues() {
        assertThrows(IllegalArgumentException::class.java) {
            LoggedSet(type = SetType.NORMAL, weightKg = -1.0, reps = 5)
        }
    }

    @Test
    fun defaultLibrary_hasFullBalancedCatalog() {
        val exercises = DefaultData.exercises()

        assertEquals(listOf("Repetitions", "Timed"), ExerciseType.entries.map { it.label })
        assertEquals(466, exercises.size)
        assert(exercises.map { it.id }.containsAll(listOf(
            "push-up",
            "close-grip-push-up",
            "wide-grip-push-up",
            "diamond-push-up",
            "knee-push-up",
            "decline-push-up",
            "wall-push-up",
            "pike-push-up",
            "archer-push-up",
            "one-arm-push-up",
            "clap-push-up",
            "spiderman-push-up",
        )))
        assert(exercises.any { it.type == ExerciseType.REPETITIONS })
        assert(exercises.count { it.type == ExerciseType.TIMED } >= 80)
        assertFalse(exercises.single { it.id == "push-up" }.weighted)
        assertTrue(exercises.single { it.id == "bench-press" }.weighted)
        assertFalse(exercises.single { it.id == "plank" }.weighted)
        assertTrue(exercises.single { it.id == "farmer-carry" }.weighted)
        assertFalse(exercises.single { it.id == "fedb-bodyweight-flyes" }.weighted)
        val weightedCopies = exercises.filter { it.id.startsWith("weighted-") }
        assertEquals(38, weightedCopies.size)
        weightedCopies.forEach { copy ->
            val original = exercises.single { it.id == copy.id.removePrefix("weighted-") }
            assertFalse(original.weighted)
            assertTrue(copy.weighted)
            assertEquals("Weighted ${original.name}", copy.name)
            assertEquals(original.type, copy.type)
            assertEquals(original.asymmetric, copy.asymmetric)
            assertEquals(original.primaryMuscle, copy.primaryMuscle)
            assertEquals(original.secondaryMuscles, copy.secondaryMuscles)
            assertEquals(original.imageResource, copy.imageResource)
            assertEquals(original.imageName, copy.imageName)
        }
        assert(exercises.count { it.asymmetric } >= 50)
        val shoulderRotations = exercises.filter { it.name.contains("Rotation", ignoreCase = true) && it.primaryMuscle == "Rotator cuff" }
        assert(shoulderRotations.size >= 8)
        assert(exercises.all { it.primaryMuscle.isNotBlank() })
        assert(exercises.all { it.imageResource != null || it.imageName != null })
        assertEquals(exercises.size, exercises.map { it.id }.distinct().size)
        val pullUpImage = exercises.single { it.id == "pull-up" }.imageResource
        assertEquals(pullUpImage, exercises.single { it.id == "machine-assisted-pull-up" }.imageResource)
        assertEquals(pullUpImage, exercises.single { it.id == "band-assisted-pull-up" }.imageResource)
    }

    @Test
    fun routineExercise_preservesPrescribedWork() {
        val prescription = RoutineExercise(
            exerciseId = "plank",
            plannedSets = MutableList(4) { PlannedSet(reps = 0, holdSeconds = 45) },
            restSecondsOverride = 60,
        )

        assertEquals(4, prescription.plannedSets.size)
        assertTrue(prescription.plannedSets.all { it.reps == 0 && it.holdSeconds == 45 })
        assertEquals(60, prescription.restSecondsOverride)
    }

    @Test
    fun workoutExercise_usesWorkoutRestWhenOverrideIsEmpty() {
        val exercise = WorkoutExercise(exerciseId = "bench")

        assertEquals(75, exercise.restSeconds(75))

        exercise.restSecondsOverride = 45
        assertEquals(45, exercise.restSeconds(75))
    }

    @Test
    fun routineExercise_allowsDifferentWeightAndRepsForEachSet() {
        val prescription = RoutineExercise(
            exerciseId = "bench",
            plannedSets = mutableListOf(
                PlannedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 10),
                PlannedSet(weightKg = 60.0, reps = 5),
                PlannedSet(type = SetType.FAILURE, weightKg = 65.0, reps = 3),
            ),
        )

        assertEquals(listOf(20.0, 60.0, 65.0), prescription.plannedSets.map { it.weightKg })
        assertEquals(listOf(10, 5, 3), prescription.plannedSets.map { it.reps })
        assertEquals(listOf(SetType.WARMUP, SetType.NORMAL, SetType.FAILURE), prescription.plannedSets.map { it.type })
    }

    @Test
    fun lastKnownSets_usesNewestLoggedSetsAndCopiesAllValues() {
        val older = WorkoutSession(
            name = "Older",
            startedAt = 100,
            completedAt = 200,
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "bench",
                sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 40.0, reps = 10)),
            )),
        )
        val newer = WorkoutSession(
            name = "Newer",
            startedAt = 300,
            completedAt = 400,
            exercises = mutableListOf(WorkoutExercise(
                exerciseId = "bench",
                sets = mutableListOf(
                    LoggedSet(type = SetType.WARMUP, weightKg = 20.0, reps = 12, side = Side.LEFT),
                    LoggedSet(type = SetType.FAILURE, weightKg = 62.5, reps = 4, side = Side.RIGHT),
                ),
            )),
        )

        val sets = AppData(history = mutableListOf(newer, older)).lastKnownSets("bench")!!

        assertEquals(2, sets.size)
        assertEquals(listOf(SetType.WARMUP, SetType.FAILURE), sets.map { it.type })
        assertEquals(listOf(20.0, 62.5), sets.map { it.weightKg })
        assertEquals(listOf(12, 4), sets.map { it.reps })
        assertEquals(listOf(Side.LEFT, Side.RIGHT), sets.map { it.side })
    }

    @Test
    fun lastKnownSets_skipsNewerWorkoutWithoutLoggedSetsForExercise() {
        val data = AppData(history = mutableListOf(
            WorkoutSession(
                name = "Known",
                startedAt = 100,
                exercises = mutableListOf(WorkoutExercise(
                    exerciseId = "plank",
                    sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 5.0, holdSeconds = 45)),
                )),
            ),
            WorkoutSession(
                name = "No plank sets",
                startedAt = 200,
                exercises = mutableListOf(WorkoutExercise(exerciseId = "plank")),
            ),
        ))

        val sets = data.lastKnownSets("plank")!!

        assertEquals(1, sets.size)
        assertEquals(5.0, sets.single().weightKg, 0.001)
        assertEquals(45, sets.single().holdSeconds)
    }

    @Test
    fun exerciseRecords_resumedWorkoutAppendsImprovementsWithoutChangingPastSnapshots() {
        val bench = Exercise("bench", "Bench", ExerciseType.REPETITIONS, false, "Chest", "")
        val set = LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 5)
        val workout = WorkoutSession(name = "Resume", exercises = mutableListOf(
            WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(set)),
        ))
        val data = AppData(exercises = mutableListOf(bench))
        workout.finish()
        data.recordWorkout(workout)
        data.history.add(workout)

        val resumed = data.resumeWorkout(workout.id)!!
        assertEquals(60.0, data.exerciseRecords.getValue(bench.id).single().set.weightKg, 0.0)
        resumed.exercises.single().sets.single().weightKg = 70.0
        resumed.finish()
        data.recordWorkout(resumed)
        data.recordWorkout(resumed)

        assertEquals(listOf(60.0, 70.0), data.exerciseRecords.getValue(bench.id).map { it.set.weightKg })
        assertEquals(listOf(70.0), data.exerciseTrend(bench.id, TrendSpan.ALL).series.first().points.map { it.value })
    }

    @Test
    fun exerciseRecords_createEntriesOnlyForEligibleSets() {
        val bench = Exercise("bench", "Bench", ExerciseType.REPETITIONS, false, "Chest", "")
        val data = AppData(exercises = mutableListOf(bench))
        val workout = WorkoutSession(name = "Invalid", exercises = mutableListOf(
            WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(
                LoggedSet(type = SetType.NORMAL, weightKg = 100.0, reps = 0),
                LoggedSet(type = SetType.NORMAL, weightKg = 0.0, reps = 10),
            )),
            WorkoutExercise(exerciseId = "missing", sets = mutableListOf(
                LoggedSet(type = SetType.NORMAL, weightKg = 100.0, reps = 10),
            )),
        ))

        data.recordWorkout(workout)

        assertTrue(data.exerciseRecords.isEmpty())
        assertTrue(data.oneRepMaxes().isEmpty())
    }

    @Test
    fun exerciseRecords_appendProgressionAndKeepSnapshotsIndependentOfHistory() {
        val bench = Exercise("bench", "Bench", ExerciseType.REPETITIONS, false, "Chest", "")
        val unused = Exercise("unused", "Unused", ExerciseType.REPETITIONS, false, "Chest", "")
        val first = LoggedSet(id = "first", type = SetType.NORMAL, weightKg = 60.0, reps = 5, completedAt = 10)
        val weight = LoggedSet(id = "weight", type = SetType.NORMAL, weightKg = 80.0, reps = 2, completedAt = 20)
        val volume = LoggedSet(id = "volume", type = SetType.NORMAL, weightKg = 50.0, reps = 10, completedAt = 30)
        val lower = LoggedSet(id = "lower", type = SetType.NORMAL, weightKg = 40.0, reps = 3, completedAt = 40)
        val data = AppData(
            exercises = mutableListOf(unused, bench),
            history = mutableListOf(
                WorkoutSession(name = "Newer", exercises = mutableListOf(
                    WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(lower, volume)),
                )),
                WorkoutSession(name = "Older", exercises = mutableListOf(
                    WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(weight)),
                    WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(first)),
                )),
            ),
            activeWorkout = WorkoutSession(name = "Active", exercises = mutableListOf(
                WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(
                    LoggedSet(type = SetType.NORMAL, weightKg = 100.0, reps = 10, completedAt = 50),
                )),
            )),
        )

        assertTrue(data.exerciseRecords.isEmpty())
        assertTrue(data.oneRepMaxes().isEmpty())
        data.history.asReversed().forEach(data::recordWorkout)
        val records = data.exerciseRecords

        assertEquals(setOf(bench.id), records.keys)
        assertEquals(listOf(first, weight, weight), records.getValue(bench.id).map { it.set })
        assertEquals(listOf(first, first, volume), records.getValue(bench.id).map { it.volumeSet })
        assertEquals(listOf(records.getValue(bench.id).last()), data.oneRepMaxes())

        data.history.removeAt(0)
        volume.weightKg = 1.0

        assertEquals(500.0, data.oneRepMaxes().single().volumeSet!!.volume(), 0.0)
        assertEquals(80.0, data.oneRepMaxes().single().set.weightKg, 0.0)
        assertEquals(3, records.getValue(bench.id).size)
    }

    @Test
    fun exerciseRecords_trackHoldsAndBodyweightRepsWithoutVolume() {
        val plank = Exercise("plank-test", "Plank", ExerciseType.TIMED, false, "Core", "")
        val pushUp = Exercise("push-up-test", "Push-up", ExerciseType.REPETITIONS, false, "Chest", "", weighted = false)
        val data = AppData(
            exercises = mutableListOf(pushUp, plank),
            history = mutableListOf(WorkoutSession(name = "Records", exercises = mutableListOf(
                WorkoutExercise(exerciseId = plank.id, sets = mutableListOf(
                    LoggedSet(type = SetType.NORMAL, weightKg = 10.0, holdSeconds = 30, completedAt = 10),
                    LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 45, completedAt = 20),
                    LoggedSet(type = SetType.NORMAL, weightKg = 20.0, holdSeconds = 20, completedAt = 30),
                )),
                WorkoutExercise(exerciseId = pushUp.id, sets = mutableListOf(
                    LoggedSet(type = SetType.NORMAL, weightKg = 0.0, reps = 8, completedAt = 10),
                    LoggedSet(type = SetType.NORMAL, weightKg = 0.0, reps = 12, completedAt = 20),
                    LoggedSet(type = SetType.NORMAL, weightKg = 50.0, reps = 10, completedAt = 30),
                )),
            ))),
        )

        data.recordWorkout(data.history.single())
        val records = data.exerciseRecords

        assertEquals(setOf(plank.id, pushUp.id), records.keys)
        assertEquals(listOf(30, 45), records.getValue(plank.id).map { it.set.holdSeconds })
        assertEquals(listOf(8, 12), records.getValue(pushUp.id).map { it.set.reps })
        assertTrue(records.values.flatten().all { it.volumeSet == null })
    }

    @Test
    fun exerciseRecords_keepNewestTiedRecordsAndDoNotDuplicateOnResave() {
        val bench = Exercise("bench", "Bench", ExerciseType.REPETITIONS, false, "Chest", "")
        val older = LoggedSet(id = "older", type = SetType.NORMAL, weightKg = 60.0, reps = 5, completedAt = 10)
        val newer = older.copy(id = "newer", completedAt = 20)
        val sameTime = newer.copy(id = "same-time")
        val data = AppData(
            exercises = mutableListOf(bench),
            history = mutableListOf(WorkoutSession(name = "Ties", exercises = mutableListOf(
                WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(newer, older, sameTime)),
            ))),
        )

        data.recordWorkout(data.history.single())
        data.recordWorkout(data.history.single())
        val records = data.exerciseRecords.getValue(bench.id)

        assertEquals(listOf(older, newer), records.map { it.set })
        assertEquals(newer, data.oneRepMaxes().single().set)
        assertEquals(newer, data.oneRepMaxes().single().volumeSet)
    }

    @Test
    fun oneRepMaxes_useStoredHighestWeightLongestHoldAndSetVolume() {
        val bench = Exercise("bench", "Bench", ExerciseType.REPETITIONS, false, "Chest", "")
        val plank = Exercise("plank-test", "Plank", ExerciseType.TIMED, false, "Core", "")
        val data = AppData(
            exercises = mutableListOf(bench, plank),
            history = mutableListOf(WorkoutSession(
                name = "Records",
                exercises = mutableListOf(
                    WorkoutExercise(
                        exerciseId = bench.id,
                        sets = mutableListOf(
                            LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 8),
                            LoggedSet(type = SetType.NORMAL, weightKg = 75.0, reps = 2),
                        ),
                    ),
                    WorkoutExercise(
                        exerciseId = plank.id,
                        sets = mutableListOf(
                            LoggedSet(type = SetType.NORMAL, weightKg = 10.0, holdSeconds = 20),
                            LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 55),
                        ),
                    ),
                ),
            )),
            activeWorkout = WorkoutSession(
                name = "Not finished",
                exercises = mutableListOf(WorkoutExercise(
                    exerciseId = bench.id,
                    sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 100.0, reps = 1)),
                )),
            ),
        )

        data.recordWorkout(data.history.single())
        val records = data.oneRepMaxes().associateBy { it.exercise.id }

        assertEquals(75.0, records.getValue(bench.id).set.weightKg, 0.001)
        assertEquals(480.0, records.getValue(bench.id).volumeSet!!.volume(), 0.001)
        assertEquals(55, records.getValue(plank.id).set.holdSeconds)
        assertNull(records.getValue(plank.id).volumeSet)
    }

    @Test
    fun oneRepMaxes_useRepetitionsForBodyweightExercise() {
        val pushUp = Exercise("push-up-test", "Push-up", ExerciseType.REPETITIONS, false, "Chest", "", weighted = false)
        val data = AppData(
            exercises = mutableListOf(pushUp),
            history = mutableListOf(WorkoutSession(
                name = "Bodyweight records",
                exercises = mutableListOf(WorkoutExercise(
                    exerciseId = pushUp.id,
                    sets = mutableListOf(
                        LoggedSet(type = SetType.NORMAL, weightKg = 50.0, reps = 5),
                        LoggedSet(type = SetType.NORMAL, weightKg = 0.0, reps = 20),
                    ),
                )),
            )),
        )

        data.recordWorkout(data.history.single())
        val record = data.oneRepMaxes().single()

        assertEquals(20, record.set.reps)
        assertNull(record.volumeSet)
    }

    @Test
    fun recordSetIds_markRepetitionRecordsForBodyweightExercise() {
        val pushUp = Exercise("push-up-test", "Push-up", ExerciseType.REPETITIONS, false, "Chest", "", weighted = false)
        val data = AppData(
            exercises = mutableListOf(pushUp),
            history = mutableListOf(WorkoutSession(
                name = "Old record",
                exercises = mutableListOf(WorkoutExercise(
                    exerciseId = pushUp.id,
                    sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 0.0, reps = 10)),
                )),
            )),
        )
        val record = LoggedSet(id = "rep-record", type = SetType.NORMAL, weightKg = 0.0, reps = 12, completedAt = 10)
        val noRecord = LoggedSet(id = "no-record", type = SetType.NORMAL, weightKg = 0.0, reps = 11, completedAt = 20)
        val workout = WorkoutSession(
            name = "New record",
            exercises = mutableListOf(WorkoutExercise(exerciseId = pushUp.id, sets = mutableListOf(record, noRecord))),
        )

        data.recordWorkout(data.history.single())
        assertEquals(setOf("rep-record"), data.recordSetIds(workout))
    }

    @Test
    fun recordSetIds_markRecordsInCompletionOrder() {
        val bench = Exercise("bench", "Bench", ExerciseType.REPETITIONS, false, "Chest", "")
        val plank = Exercise("plank-test", "Plank", ExerciseType.TIMED, false, "Core", "")
        val weightRecord = LoggedSet(id = "weight-record", type = SetType.NORMAL, weightKg = 65.0, reps = 4, completedAt = 10)
        val volumeRecord = LoggedSet(id = "volume-record", type = SetType.NORMAL, weightKg = 60.0, reps = 6, completedAt = 20)
        val noRecord = LoggedSet(id = "no-record", type = SetType.NORMAL, weightKg = 62.0, reps = 5, completedAt = 30)
        val holdRecord = LoggedSet(id = "hold-record", type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 50, completedAt = 40)
        val data = AppData(
            exercises = mutableListOf(bench, plank),
            history = mutableListOf(WorkoutSession(
                name = "Old records",
                exercises = mutableListOf(
                    WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 5))),
                    WorkoutExercise(exerciseId = plank.id, sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 0.0, holdSeconds = 45))),
                ),
            )),
        )
        val workout = WorkoutSession(
            name = "New records",
            exercises = mutableListOf(
                WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(weightRecord, volumeRecord, noRecord)),
                WorkoutExercise(exerciseId = plank.id, sets = mutableListOf(holdRecord)),
            ),
        )

        data.recordWorkout(data.history.single())
        assertEquals(setOf("weight-record", "volume-record", "hold-record"), data.recordSetIds(workout))
    }

    @Test
    fun recordSetIds_keepOnlyLatestBestSetForSameRecordType() {
        val squat = Exercise("squat", "Squat", ExerciseType.REPETITIONS, false, "Quadriceps", "")
        val data = AppData(
            exercises = mutableListOf(squat),
            history = mutableListOf(WorkoutSession(
                name = "Old record",
                exercises = mutableListOf(WorkoutExercise(
                    exerciseId = squat.id,
                    sets = mutableListOf(LoggedSet(type = SetType.NORMAL, weightKg = 0.5, reps = 5)),
                )),
            )),
        )
        val firstRecord = LoggedSet(id = "first-record", type = SetType.NORMAL, weightKg = 1.0, reps = 5, completedAt = 10)
        val secondRecord = LoggedSet(id = "second-record", type = SetType.NORMAL, weightKg = 2.0, reps = 5, completedAt = 20)
        val workout = WorkoutSession(
            name = "New records",
            exercises = mutableListOf(WorkoutExercise(exerciseId = squat.id, sets = mutableListOf(firstRecord, secondRecord))),
        )

        data.recordWorkout(data.history.single())
        assertEquals(setOf("second-record"), data.recordSetIds(workout))
    }

    @Test
    fun recordSetIds_requireEarlierWorkoutWithExercise() {
        val bench = Exercise("bench", "Bench", ExerciseType.REPETITIONS, false, "Chest", "")
        val first = LoggedSet(id = "first", type = SetType.NORMAL, weightKg = 50.0, reps = 5, completedAt = 10)
        val second = LoggedSet(id = "second", type = SetType.NORMAL, weightKg = 55.0, reps = 5, completedAt = 20)
        val data = AppData(exercises = mutableListOf(bench))
        val workout = WorkoutSession(
            name = "First workout",
            exercises = mutableListOf(WorkoutExercise(exerciseId = bench.id, sets = mutableListOf(first, second))),
        )

        assertEquals(emptySet<String>(), data.recordSetIds(workout))
    }
}
