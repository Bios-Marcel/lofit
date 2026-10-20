package lofit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkoutSessionTest {
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

        assertEquals(410, exercises.size)
        assert(exercises.any { it.type == ExerciseType.WEIGHTED_HOLD })
        assert(exercises.any { it.type == ExerciseType.WEIGHTED_REPS })
        assert(exercises.count { it.type == ExerciseType.ISOMETRIC } >= 10)
        assert(exercises.count { it.type == ExerciseType.STRETCH } >= 60)
        assert(exercises.count { it.asymmetric } >= 50)
        assert(exercises.count { it.equipment in setOf("Barbell", "Dumbbell", "Dumbbells", "Kettlebells", "EZ Curl Bar") } >= 150)
        assert(exercises.count { it.equipment in setOf("Machine", "Cable") } >= 90)
        val shoulderRotations = exercises.filter { it.name.contains("Rotation", ignoreCase = true) && it.primaryMuscle == "Rotator cuff" }
        assert(shoulderRotations.size >= 8)
        assert(shoulderRotations.count { it.equipment == "Bands" } >= 6)
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
            targetSets = 4,
            targetReps = 0,
            targetHoldSeconds = 45,
            restSecondsOverride = 60,
        )

        assertEquals(4, prescription.targetSets)
        assertEquals(45, prescription.targetHoldSeconds)
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
    fun oneRepMaxes_useHighestWeightOrLongestHoldFromHistory() {
        val bench = Exercise("bench", "Bench", ExerciseType.WEIGHTED_REPS, false, "Chest", "")
        val plank = Exercise("plank-test", "Plank", ExerciseType.ISOMETRIC, false, "Core", "")
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

        val records = data.oneRepMaxes().associateBy { it.exercise.id }

        assertEquals(75.0, records.getValue(bench.id).set.weightKg, 0.001)
        assertEquals(55, records.getValue(plank.id).set.holdSeconds)
    }
}
