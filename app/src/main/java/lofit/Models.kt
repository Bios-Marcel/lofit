package lofit

import java.util.UUID

enum class ExerciseType(val label: String) {
    WEIGHTED_REPS("Repetitions"),
    WEIGHTED_HOLD("Timed hold"),
    ISOMETRIC("Isometric hold"),
    STRETCH("Stretch"),
    ;

    val tracksDuration: Boolean
        get() = this != WEIGHTED_REPS
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
    var equipment: String = "",
    val imageName: String? = null,
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
    var actualHoldSeconds: Int? = null,
    var side: Side = Side.BOTH,
) {
    init {
        require(weightKg >= 0) { "Weight cannot be negative" }
        require(reps >= 0) { "Repetitions cannot be negative" }
        require(holdSeconds >= 0) { "Hold duration cannot be negative" }
        require(actualHoldSeconds == null || actualHoldSeconds!! >= 0) { "Active hold duration cannot be negative" }
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
) {
    fun lastKnownSets(exerciseId: String): MutableList<PlannedSet>? = history
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<WorkoutSession>> { it.value.completedAt ?: it.value.startedAt }
                .thenByDescending { it.index },
        )
        .firstNotNullOfOrNull { (_, workout) ->
            workout.exercises.lastOrNull { it.exerciseId == exerciseId && it.sets.isNotEmpty() }
        }
        ?.sets
        ?.map { set ->
            PlannedSet(
                type = set.type,
                weightKg = set.weightKg,
                reps = set.reps,
                holdSeconds = set.holdSeconds,
                side = set.side,
            )
        }
        ?.toMutableList()
}

object DefaultData {
    fun exercises(): List<Exercise> = ExerciseCatalog.exercises()
}
