package lofit

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

enum class ExerciseType(val label: String) {
    REPETITIONS("Repetitions"),
    TIMED("Timed");

    val tracksDuration: Boolean
        get() = this == TIMED
}

enum class SetType(val label: String) {
    WARMUP("Warm-up"),
    NORMAL("Normal"),
    FAILURE("Failure"),
}

enum class Side(val label: String, val marker: String) {
    BOTH("Both", "B"),
    LEFT("Left", "L"),
    RIGHT("Right", "R"),
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
    var imageFile: String? = null,
    var liveMeasurement: Boolean = false,
    internal val retired: Boolean = false,
    var cardio: Boolean = false,
) {
    init {
        require(!liveMeasurement || (type.tracksDuration && weighted)) {
            "Live measurement requires a timed weighted exercise"
        }
    }

    val supportsLiveMeasurement: Boolean
        get() = liveMeasurement && type.tracksDuration && weighted

    internal fun restSetInterval(pauseEverySecondSet: Boolean): Int =
        if (asymmetric && pauseEverySecondSet) 2 else 1
}

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

    fun volume(asymmetric: Boolean = false, timed: Boolean = false): Double =
        (weightKg.toBigDecimal() *
                // LEGACY: Repetition sets keep using weight multiplied by their rep count.
                (if (timed) 1 else reps).toBigDecimal() *
                (if (asymmetric && side == Side.BOTH) 2 else 1).toBigDecimal())
            .toDouble()
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
        require(actualHoldSeconds == null || actualHoldSeconds!! >= 0) {
            "Active hold duration cannot be negative"
        }
    }
}

data class WorkoutExercise(
    val id: String = UUID.randomUUID().toString(),
    val exerciseId: String,
    var restSecondsOverride: Int? = null,
    val sets: MutableList<LoggedSet> = mutableListOf(),
    val plannedSets: MutableList<PlannedSet> = mutableListOf(),
    var sourceRoutineExerciseId: String? = null,
    var note: String = "",
    var previousNote: String = "",
    var manualMeasurement: Boolean = false,
    var pauseEverySecondSet: Boolean = false,
) {
    fun restSeconds(defaultRestSeconds: Int): Int = restSecondsOverride ?: defaultRestSeconds

    fun restSecondsAfterSet(defaultRestSeconds: Int, exercise: Exercise): Int {
        if (sets.size % exercise.restSetInterval(pauseEverySecondSet) != 0) return 0
        // LEGACY: Exercises without paired rest keep resting after each completed set.
        return restSeconds(defaultRestSeconds)
    }

    val effectiveNote: String
        get() = note.ifBlank { previousNote }
}

data class WorkoutInterval(
    val startedAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
) {
    fun durationMillis(now: Long): Long = ((endedAt ?: now) - startedAt).coerceAtLeast(0)
}

data class WorkoutSession(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    val intervals: MutableList<WorkoutInterval>,
    var defaultRestSeconds: Int = 120,
    val exercises: MutableList<WorkoutExercise> = mutableListOf(),
    var bodyWeightKg: Double? = null,
    val sourceRoutine: Routine? = null,
    var averageHeartRateBpm: Double? = null,
    var autoStartCardio: Boolean = true,
    var voiceCommandsEnabled: Boolean = false,
    var note: String = "",
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
        averageHeartRateBpm: Double? = null,
        autoStartCardio: Boolean = true,
        voiceCommandsEnabled: Boolean = false,
        note: String = "",
    ) : this(
        id,
        name,
        mutableListOf(WorkoutInterval(startedAt, completedAt)),
        defaultRestSeconds,
        exercises,
        bodyWeightKg,
        sourceRoutine,
        averageHeartRateBpm,
        autoStartCardio,
        voiceCommandsEnabled,
        note,
    )

    init {
        require(intervals.isNotEmpty()) { "Workout needs a start time" }
        require(intervals.dropLast(1).all { it.endedAt != null }) {
            "Only the last interval can be active"
        }
    }

    val startedAt: Long
        get() = intervals.first().startedAt

    val completedAt: Long?
        get() = intervals.last().endedAt

    fun durationMillis(now: Long = System.currentTimeMillis()): Long = intervals.sumOf {
        it.durationMillis(now)
    }

    fun finish(completedAt: Long = System.currentTimeMillis()) {
        if (this.completedAt != null) return
        exercises.forEach { item ->
            item.note = item.effectiveNote
            item.previousNote = ""
        }
        intervals[intervals.lastIndex] = intervals.last().copy(endedAt = completedAt)
    }

    fun moveExercise(from: Int, to: Int) {
        if (from !in exercises.indices || to !in exercises.indices || from == to) return
        exercises.add(to, exercises.removeAt(from))
    }

    fun totalVolume(exerciseCatalog: List<Exercise>): Double {
        val exercisesById = exerciseCatalog.associateBy { it.id }
        return exercises
            .sumOf { item ->
                val exercise = exercisesById[item.exerciseId]
                item.sets.sumOf {
                    it.volume(
                            asymmetric = exercise?.asymmetric == true,
                            timed = exercise?.type?.tracksDuration == true,
                        )
                        .toBigDecimal()
                }
            }
            .toDouble()
    }

    fun setCount(): Int = exercises.sumOf { it.sets.size }

    fun wasCompletedToday(now: Long = System.currentTimeMillis()): Boolean {
        val finishedAt = completedAt ?: return false
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochMilli(finishedAt).atZone(zone).toLocalDate() ==
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }

    fun toRoutine(): Routine =
        Routine(
            name = name,
            defaultRestSeconds = defaultRestSeconds,
            autoStartCardio = autoStartCardio,
            exercises =
                exercises
                    .map { item ->
                        val plannedSetOrder =
                            item.plannedSets.mapIndexed { index, set -> set.id to index }.toMap()
                        val sets =
                            item.sets.sortedBy { plannedSetOrder[it.plannedSetId] ?: Int.MAX_VALUE }
                        val plannedSets =
                            if (sets.isNotEmpty()) {
                                sets
                                    .map { set ->
                                        PlannedSet(
                                            type = set.type,
                                            weightKg = set.weightKg,
                                            reps = set.reps,
                                            holdSeconds = set.holdSeconds,
                                            side = set.side,
                                        )
                                    }
                                    .toMutableList()
                            } else {
                                item.plannedSets
                                    .map { set ->
                                        set.copy(
                                            id = UUID.randomUUID().toString(),
                                            actualHoldSeconds = null,
                                        )
                                    }
                                    .toMutableList()
                            }
                        RoutineExercise(
                            exerciseId = item.exerciseId,
                            restSecondsOverride = item.restSecondsOverride,
                            plannedSets = plannedSets,
                            note = item.effectiveNote,
                            manualMeasurement = item.manualMeasurement,
                            pauseEverySecondSet = item.pauseEverySecondSet,
                        )
                    }
                    .toMutableList(),
        )
}

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var defaultRestSeconds: Int = 120,
    val exercises: MutableList<RoutineExercise> = mutableListOf(),
    var autoStartCardio: Boolean = true,
)

data class RoutineExercise(
    val id: String = UUID.randomUUID().toString(),
    val exerciseId: String,
    var restSecondsOverride: Int? = null,
    val plannedSets: MutableList<PlannedSet> = MutableList(3) { PlannedSet() },
    var note: String = "",
    var manualMeasurement: Boolean = false,
    var pauseEverySecondSet: Boolean = false,
)

data class ExerciseRecord(
    val exercise: Exercise,
    val set: LoggedSet,
    val volumeSet: LoggedSet?,
)

enum class BiologicalGender(val label: String) {
    MALE("Male"),
    FEMALE("Female"),
}

data class AppData(
    val exercises: MutableList<Exercise> = DefaultData.exercises().toMutableList(),
    val routines: MutableList<Routine> = mutableListOf(),
    val history: MutableList<WorkoutSession> = mutableListOf(),
    var activeWorkout: WorkoutSession? = null,
    var soundCuesEnabled: Boolean = true,
    var bodyMetricsEnabled: Boolean? = null,
    var biologicalGender: BiologicalGender = BiologicalGender.MALE,
    var onboardingComplete: Boolean = false,
    val exerciseRecords: MutableMap<String, MutableList<ExerciseRecord>> = mutableMapOf(),
    val exerciseLastSelectedAt: MutableMap<String, Long> = mutableMapOf(),
    var mcpEnabled: Boolean = false,
    var mcpPort: Int = BuildConfig.MCP_PORT,
    var craneScale: CraneScaleDevice? = null,
    val bodyMeasurements: MutableList<BodyMeasurement> = mutableListOf(),
    var recordCelebrationsEnabled: Boolean = false,
) {
    fun resumeWorkout(
        workoutId: String,
        resumedAt: Long = System.currentTimeMillis(),
    ): WorkoutSession? {
        if (activeWorkout != null) return null
        val historyIndex = history.indexOfFirst { it.id == workoutId }
        if (historyIndex < 0) return null
        if (!history[historyIndex].wasCompletedToday(resumedAt)) return null
        val finishedWorkout = history.removeAt(historyIndex)
        return finishedWorkout
            .copy(
                intervals =
                    (finishedWorkout.intervals + WorkoutInterval(startedAt = resumedAt))
                        .toMutableList(),
                sourceRoutine = finishedWorkout.captureRoutineBaseline(),
                averageHeartRateBpm = null,
            )
            .also { activeWorkout = it }
    }

    fun lastKnownSets(
        exerciseId: String,
        preferredRoutineId: String? = null,
        sameRoutineOnly: Boolean = false,
    ): MutableList<PlannedSet>? =
        lastKnownExercise(exerciseId, preferredRoutineId, sameRoutineOnly)?.plannedSets

    // Saved prescriptions restrict history to their routine; newly added exercises can use other
    // history.
    fun lastKnownExercise(
        exerciseId: String,
        preferredRoutineId: String? = null,
        sameRoutineOnly: Boolean = false,
    ): WorkoutExercise? =
        history
            .withIndex()
            .filter { !sameRoutineOnly || it.value.sourceRoutine?.id == preferredRoutineId }
            .sortedWith(
                compareByDescending<IndexedValue<WorkoutSession>> {
                        preferredRoutineId != null &&
                            it.value.sourceRoutine?.id == preferredRoutineId
                    }
                    .thenByDescending { it.value.completedAt ?: it.value.startedAt }
                    .thenByDescending { it.index }
            )
            .firstNotNullOfOrNull { (_, workout) ->
                workout.exercises
                    .lastOrNull { it.exerciseId == exerciseId && it.sets.isNotEmpty() }
                    ?.let { workout to it }
            }
            ?.let { (workout, item) ->
                WorkoutExercise(
                    exerciseId = item.exerciseId,
                    previousNote = item.effectiveNote,
                    pauseEverySecondSet = item.pauseEverySecondSet,
                    restSecondsOverride =
                        if (preferredRoutineId == null) null
                        // LEGACY: Routine workouts retain the previous exercise's effective rest.
                        else item.restSecondsOverride ?: workout.defaultRestSeconds,
                    plannedSets =
                        item.sets
                            .map { set ->
                                PlannedSet(
                                    type = set.type,
                                    weightKg = set.weightKg,
                                    reps = set.reps,
                                    holdSeconds = set.holdSeconds,
                                    side = set.side,
                                )
                            }
                            .toMutableList(),
                )
            }

    // Append snapshots only when a workout is saved; never rebuild records from history.
    fun recordWorkout(workout: WorkoutSession) {
        val exercisesById = exercises.associateBy { it.id }
        workout.exercises
            .groupBy { it.exerciseId }
            .forEach { (exerciseId, items) ->
                val exercise = exercisesById[exerciseId] ?: return@forEach
                val sets =
                    items
                        .flatMap { it.sets }
                        .filter { it.isRecordEligible(exercise) }
                        .sortedBy { it.completedAt }
                val bestOrder =
                    compareBy<LoggedSet> {
                            when {
                                exercise.type.tracksDuration -> it.holdSeconds.toDouble()
                                exercise.weighted -> it.weightKg
                                else -> it.reps.toDouble()
                            }
                        }
                        .thenBy { it.completedAt }
                val volumeOrder =
                    compareBy<LoggedSet> { it.volume(exercise.asymmetric) }
                        .thenBy { it.completedAt }
                val tracksVolume = exercise.weighted && !exercise.type.tracksDuration
                sets.forEach { set ->
                    val previous = exerciseRecords[exerciseId]?.lastOrNull()
                    val best = previous?.set?.takeUnless { bestOrder.compare(set, it) > 0 } ?: set
                    val volumeSet =
                        if (tracksVolume) {
                            previous?.volumeSet?.takeUnless { volumeOrder.compare(set, it) > 0 }
                                ?: set
                        } else null
                    if (
                        previous == null || best != previous.set || volumeSet != previous.volumeSet
                    ) {
                        exerciseRecords
                            .getOrPut(exerciseId) { mutableListOf() }
                            .add(ExerciseRecord(exercise, best.copy(), volumeSet?.copy()))
                    }
                }
            }
    }

    fun oneRepMaxes(): List<ExerciseRecord> =
        exerciseRecords.values
            .mapNotNull { it.lastOrNull() }
            .sortedBy { it.exercise.name.lowercase() }

    fun recordSetIds(workout: WorkoutSession): Set<String> {
        // LEGACY: Set crowns stay deduplicated when one set earns multiple record types.
        return workoutRecords(workout).mapTo(mutableSetOf()) { it.setId }
    }
}

object DefaultData {
    fun exercises(): List<Exercise> = ExerciseCatalog.exercises()
}
