package lofit

import java.util.UUID

enum class ExerciseType(val label: String) {
    WEIGHTED_REPS("Weighted reps"),
    WEIGHTED_HOLD("Weighted hold"),
}

enum class SetType(val label: String) {
    WARMUP("Warm-up"),
    NORMAL("Normal"),
    FAILURE("Failure"),
}

enum class Side(val label: String) {
    BOTH("Both"),
    LEFT("Left"),
    RIGHT("Right"),
}

data class Exercise(
    val id: String,
    var name: String,
    var type: ExerciseType,
    var asymmetric: Boolean,
    var primaryMuscle: String,
    var secondaryMuscles: String,
    var defaultRestSeconds: Int = 90,
    var minimumHoldSeconds: Int = 30,
    val custom: Boolean = false,
    val imageResource: Int? = null,
)

data class LoggedSet(
    val id: String = UUID.randomUUID().toString(),
    var type: SetType,
    var weightKg: Double,
    var reps: Int = 0,
    var holdSeconds: Int = 0,
    var side: Side = Side.BOTH,
    val completedAt: Long = System.currentTimeMillis(),
    var plannedSetId: String? = null,
) {
    init {
        require(weightKg >= 0) { "Weight cannot be negative" }
        require(reps >= 0) { "Repetitions cannot be negative" }
        require(holdSeconds >= 0) { "Hold duration cannot be negative" }
    }

    fun volume(): Double = weightKg * reps
}

data class PlannedSet(
    val id: String = UUID.randomUUID().toString(),
    var type: SetType = SetType.NORMAL,
    var weightKg: Double = 0.0,
    var reps: Int = 8,
    var holdSeconds: Int = 30,
    var side: Side = Side.BOTH,
) {
    init {
        require(weightKg >= 0) { "Weight cannot be negative" }
        require(reps >= 0) { "Repetitions cannot be negative" }
        require(holdSeconds >= 0) { "Hold duration cannot be negative" }
    }
}

data class WorkoutExercise(
    val id: String = UUID.randomUUID().toString(),
    val exerciseId: String,
    var restSecondsOverride: Int? = null,
    var targetSets: Int? = null,
    var targetReps: Int? = null,
    var targetHoldSeconds: Int? = null,
    val sets: MutableList<LoggedSet> = mutableListOf(),
    val plannedSets: MutableList<PlannedSet> = targetSets?.let { count ->
        MutableList(count) {
            PlannedSet(reps = targetReps ?: 8, holdSeconds = targetHoldSeconds ?: 30)
        }
    } ?: mutableListOf(),
)

data class WorkoutSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startedAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,
    var defaultRestSeconds: Int = 90,
    val exercises: MutableList<WorkoutExercise> = mutableListOf(),
) {
    fun moveExercise(from: Int, to: Int) {
        if (from !in exercises.indices || to !in exercises.indices || from == to) return
        exercises.add(to, exercises.removeAt(from))
    }

    fun totalVolume(): Double = exercises.sumOf { item -> item.sets.sumOf(LoggedSet::volume) }
    fun setCount(): Int = exercises.sumOf { it.sets.size }
}

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var defaultRestSeconds: Int = 90,
    val exercises: MutableList<RoutineExercise> = mutableListOf(),
)

data class RoutineExercise(
    val id: String = UUID.randomUUID().toString(),
    val exerciseId: String,
    var targetSets: Int = 3,
    var targetReps: Int = 8,
    var targetHoldSeconds: Int = 30,
    var restSecondsOverride: Int? = null,
    val plannedSets: MutableList<PlannedSet> = MutableList(targetSets) {
        PlannedSet(reps = targetReps, holdSeconds = targetHoldSeconds)
    },
)

data class AppData(
    val exercises: MutableList<Exercise> = DefaultData.exercises().toMutableList(),
    val routines: MutableList<Routine> = mutableListOf(),
    val history: MutableList<WorkoutSession> = mutableListOf(),
    var activeWorkout: WorkoutSession? = null,
)

object DefaultData {
    fun exercises(): List<Exercise> = listOf(
        Exercise("back-squat", "Back squat", ExerciseType.WEIGHTED_REPS, false, "Quadriceps", "Glutes, hamstrings", 120, imageResource = R.drawable.exercise_squat),
        Exercise("bench-press", "Bench press", ExerciseType.WEIGHTED_REPS, false, "Chest", "Triceps, front delts", 120, imageResource = R.drawable.exercise_bench),
        Exercise("deadlift", "Deadlift", ExerciseType.WEIGHTED_REPS, false, "Posterior chain", "Back, grip", 150, imageResource = R.drawable.exercise_deadlift),
        Exercise("pull-up", "Pull-up", ExerciseType.WEIGHTED_REPS, false, "Lats", "Biceps, upper back", 90, imageResource = R.drawable.exercise_pullup),
        Exercise("overhead-press", "Overhead press", ExerciseType.WEIGHTED_REPS, false, "Shoulders", "Triceps, upper chest", 120, imageResource = R.drawable.exercise_press),
        Exercise("plank", "Plank", ExerciseType.WEIGHTED_HOLD, false, "Core", "Glutes, shoulders", 60, 45, imageResource = R.drawable.exercise_plank),
        Exercise("farmer-carry", "Farmer carry", ExerciseType.WEIGHTED_HOLD, true, "Grip", "Traps, core", 90, 30, imageResource = R.drawable.exercise_carry),
        Exercise("dumbbell-curl", "Dumbbell curl", ExerciseType.WEIGHTED_REPS, true, "Biceps", "Forearms", 60, imageResource = R.drawable.exercise_curl),
    )
}
