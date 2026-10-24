package lofit

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class ExerciseType(val label: String) {
    REPETITIONS("Repetitions"),
    TIMED("Timed"),
    ;

    val tracksDuration: Boolean
        get() = this == TIMED
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
    var minimumHoldSeconds: Int = 30,
    val custom: Boolean = false,
    val imageResource: Int? = null,
    val imageName: String? = null,
    var weighted: Boolean = type == ExerciseType.REPETITIONS,
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
    val sets: MutableList<LoggedSet> = mutableListOf(),
    val plannedSets: MutableList<PlannedSet> = mutableListOf(),
    var sourceRoutineExerciseId: String? = null,
) {
    fun restSeconds(defaultRestSeconds: Int): Int = restSecondsOverride ?: defaultRestSeconds
}

data class WorkoutInterval(
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
) {
    fun durationMillis(now: Long): Long = ((endedAt ?: now) - startedAt).coerceAtLeast(0)
}

data class WorkoutSession(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val intervals: MutableList<WorkoutInterval>,
    var defaultRestSeconds: Int = 120,
    val exercises: MutableList<WorkoutExercise> = mutableListOf(),
    var bodyWeightKg: Double? = null,
    val sourceRoutine: Routine? = null,
) {
    constructor(
        id: String = UUID.randomUUID().toString(),
        name: String,
        startedAt: Long = System.currentTimeMillis(),
        completedAt: Long? = null,
        defaultRestSeconds: Int = 120,
        exercises: MutableList<WorkoutExercise> = mutableListOf(),
        bodyWeightKg: Double? = null,
        sourceRoutine: Routine? = null,
    ) : this(id, name, mutableListOf(WorkoutInterval(startedAt, completedAt)), defaultRestSeconds, exercises, bodyWeightKg, sourceRoutine)

    init {
        require(intervals.isNotEmpty()) { "Workout needs a start time" }
        require(intervals.dropLast(1).all { it.endedAt != null }) { "Only the last interval can be active" }
    }

    val startedAt: Long get() = intervals.first().startedAt
    val completedAt: Long? get() = intervals.last().endedAt

    fun durationMillis(now: Long = System.currentTimeMillis()): Long = intervals.sumOf { it.durationMillis(now) }

    fun finish(completedAt: Long = System.currentTimeMillis()) {
        if (this.completedAt != null) return
        intervals[intervals.lastIndex] = intervals.last().copy(endedAt = completedAt)
    }

    fun moveExercise(from: Int, to: Int) {
        if (from !in exercises.indices || to !in exercises.indices || from == to) return
        exercises.add(to, exercises.removeAt(from))
    }

    fun totalVolume(): Double = exercises.sumOf { item -> item.sets.sumOf(LoggedSet::volume) }
    fun setCount(): Int = exercises.sumOf { it.sets.size }

    fun wasCompletedToday(now: Long = System.currentTimeMillis()): Boolean {
        val finishedAt = completedAt ?: return false
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(finishedAt).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }

    fun toRoutine(): Routine = Routine(
        name = name,
        defaultRestSeconds = defaultRestSeconds,
        exercises = exercises.map { item ->
            val plannedSetOrder = item.plannedSets.mapIndexed { index, set -> set.id to index }.toMap()
            val sets = item.sets.sortedBy { plannedSetOrder[it.plannedSetId] ?: Int.MAX_VALUE }
            val plannedSets = if (sets.isNotEmpty()) {
                sets.map { set ->
                    PlannedSet(
                        type = set.type,
                        weightKg = set.weightKg,
                        reps = set.reps,
                        holdSeconds = set.holdSeconds,
                        side = set.side,
                    )
                }.toMutableList()
            } else {
                item.plannedSets.map { set ->
                    set.copy(id = UUID.randomUUID().toString(), actualHoldSeconds = null)
                }.toMutableList()
            }
            RoutineExercise(
                exerciseId = item.exerciseId,
                restSecondsOverride = item.restSecondsOverride,
                plannedSets = plannedSets,
            )
        }.toMutableList(),
    )
}

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var defaultRestSeconds: Int = 120,
    val exercises: MutableList<RoutineExercise> = mutableListOf(),
)

data class RoutineExercise(
    val id: String = UUID.randomUUID().toString(),
    val exerciseId: String,
    var restSecondsOverride: Int? = null,
    val plannedSets: MutableList<PlannedSet> = MutableList(3) { PlannedSet() },
)

data class ExerciseRecord(
    val exercise: Exercise,
    val set: LoggedSet,
    val volumeSet: LoggedSet?,
)

enum class BiologicalGender(val label: String) { MALE("Male"), FEMALE("Female") }

data class AppData(
    val exercises: MutableList<Exercise> = DefaultData.exercises().toMutableList(),
    val routines: MutableList<Routine> = mutableListOf(),
    val history: MutableList<WorkoutSession> = mutableListOf(),
    var activeWorkout: WorkoutSession? = null,
    var soundCuesEnabled: Boolean = true,
    var bodyMetricsEnabled: Boolean? = null,
    var biologicalGender: BiologicalGender = BiologicalGender.MALE,
    var onboardingComplete: Boolean = false,
) {
    fun resumeWorkout(workoutId: String, resumedAt: Long = System.currentTimeMillis()): WorkoutSession? {
        if (activeWorkout != null) return null
        val historyIndex = history.indexOfFirst { it.id == workoutId }
        if (historyIndex < 0) return null
        if (!history[historyIndex].wasCompletedToday(resumedAt)) return null
        val finishedWorkout = history.removeAt(historyIndex)
        return finishedWorkout.copy(
            intervals = (finishedWorkout.intervals + WorkoutInterval(startedAt = resumedAt)).toMutableList(),
            sourceRoutine = finishedWorkout.captureRoutineBaseline(),
        ).also { activeWorkout = it }
    }

    fun lastKnownSets(
        exerciseId: String, preferredRoutineId: String? = null, sameRoutineOnly: Boolean = false,
    ): MutableList<PlannedSet>? = lastKnownExercise(exerciseId, preferredRoutineId, sameRoutineOnly)?.plannedSets

    // Saved prescriptions restrict history to their routine; newly added exercises can use other history.
    fun lastKnownExercise(
        exerciseId: String, preferredRoutineId: String? = null, sameRoutineOnly: Boolean = false,
    ): WorkoutExercise? = history
        .withIndex()
        .filter { !sameRoutineOnly || it.value.sourceRoutine?.id == preferredRoutineId }
        .sortedWith(
            compareByDescending<IndexedValue<WorkoutSession>> {
                preferredRoutineId != null && it.value.sourceRoutine?.id == preferredRoutineId
            }.thenByDescending { it.value.completedAt ?: it.value.startedAt }
                .thenByDescending { it.index },
        )
        .firstNotNullOfOrNull { (_, workout) ->
            workout.exercises.lastOrNull { it.exerciseId == exerciseId && it.sets.isNotEmpty() }
                ?.let { workout to it }
        }
        ?.let { (workout, item) ->
            WorkoutExercise(exerciseId = item.exerciseId,
                restSecondsOverride = item.restSecondsOverride ?: workout.defaultRestSeconds,
                plannedSets = item.sets.map { set ->
                    PlannedSet(type = set.type, weightKg = set.weightKg, reps = set.reps,
                        holdSeconds = set.holdSeconds, side = set.side)
                }.toMutableList())
        }

    // Build chronological record snapshots from saved sets so edits and resumed workouts stay in sync.
    fun exerciseRecordHistory(): Map<String, List<ExerciseRecord>> = exercises
        .sortedBy { it.name.lowercase() }
        .mapNotNull { exercise ->
            val sets = history.flatMap { workout ->
                workout.exercises
                    .filter { it.exerciseId == exercise.id }
                    .flatMap { it.sets }
            }.filter { it.isRecordEligible(exercise) }.sortedBy { it.completedAt }
            val bestOrder = compareBy<LoggedSet> {
                when {
                    exercise.type.tracksDuration -> it.holdSeconds.toDouble()
                    exercise.weighted -> it.weightKg
                    else -> it.reps.toDouble()
                }
            }.thenBy { it.completedAt }
            val volumeOrder = compareBy<LoggedSet> { it.volume() }.thenBy { it.completedAt }
            val tracksVolume = exercise.weighted && !exercise.type.tracksDuration
            val records = mutableListOf<ExerciseRecord>()
            sets.forEach { set ->
                val previous = records.lastOrNull()
                val best = previous?.set?.takeUnless { bestOrder.compare(set, it) > 0 } ?: set
                val volumeSet = if (tracksVolume) {
                    previous?.volumeSet?.takeUnless { volumeOrder.compare(set, it) > 0 } ?: set
                } else null
                if (previous == null || best.id != previous.set.id || volumeSet?.id != previous.volumeSet?.id) {
                    records.add(ExerciseRecord(exercise, best, volumeSet))
                }
            }
            records.takeIf { it.isNotEmpty() }?.let { exercise.id to it }
        }.toMap()

    fun oneRepMaxes(): List<ExerciseRecord> = exerciseRecordHistory().values.map { it.last() }

    fun recordSetIds(workout: WorkoutSession): Set<String> {
        val records = oneRepMaxes().associateBy { it.exercise.id }
        val exercisesById = exercises.associateBy { it.id }
        val recordIds = mutableSetOf<String>()

        workout.exercises
            .flatMap { item -> item.sets.map { item.exerciseId to it } }
            .groupBy({ it.first }, { it.second })
            .forEach { (exerciseId, loggedSets) ->
                val exercise = exercisesById[exerciseId] ?: return@forEach
                val sets = loggedSets.filter { it.isRecordEligible(exercise) }
                val previousRecord = records[exerciseId] ?: return@forEach
                if (exercise.type.tracksDuration) {
                    sets.maxWithOrNull(compareBy<LoggedSet> { it.holdSeconds }.thenBy { it.completedAt })
                        ?.takeIf { it.holdSeconds > previousRecord.set.holdSeconds }
                        ?.let { recordIds.add(it.id) }
                } else if (exercise.weighted) {
                    sets.maxWithOrNull(compareBy<LoggedSet> { it.weightKg }.thenBy { it.completedAt })
                        ?.takeIf { it.weightKg > previousRecord.set.weightKg }
                        ?.let { recordIds.add(it.id) }
                    val previousVolume = previousRecord.volumeSet?.volume()
                    if (previousVolume != null) {
                        sets.maxWithOrNull(compareBy<LoggedSet> { it.volume() }.thenBy { it.completedAt })
                            ?.takeIf { it.volume() > previousVolume }
                            ?.let { recordIds.add(it.id) }
                    }
                } else {
                    sets.maxWithOrNull(compareBy<LoggedSet> { it.reps }.thenBy { it.completedAt })
                        ?.takeIf { it.reps > previousRecord.set.reps }
                        ?.let { recordIds.add(it.id) }
                }
            }

        return recordIds
    }
}

object DefaultData {
    fun exercises(): List<Exercise> = ExerciseCatalog.exercises()
}
