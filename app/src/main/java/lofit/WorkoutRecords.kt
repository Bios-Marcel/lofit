package lofit

internal data class WorkoutRecord(
    val exerciseId: String,
    val exerciseName: String,
    val setId: String,
    val metric: TrendMetric,
    val value: Double,
) {
    val key: Pair<String, TrendMetric>
        get() = exerciseId to metric

    fun message(): String {
        val amount = formatWeight(value)
        val detail =
            when (metric) {
                TrendMetric.WEIGHT -> "$amount kg weight"
                TrendMetric.VOLUME -> "$amount kg set volume"
                TrendMetric.HOLD -> "$amount s hold time"
                TrendMetric.REPS -> "$amount repetitions"
            }
        return "$exerciseName $detail"
    }
}

internal fun AppData.workoutRecords(workout: WorkoutSession): List<WorkoutRecord> {
    val exercisesById = exercises.associateBy { it.id }
    return workout.exercises
        .flatMap { item -> item.sets.map { item.exerciseId to it } }
        .groupBy({ it.first }, { it.second })
        .flatMap { (exerciseId, loggedSets) ->
            val exercise = exercisesById[exerciseId] ?: return@flatMap emptyList()
            val previous = exerciseRecords[exerciseId]?.lastOrNull() ?: return@flatMap emptyList()
            val sets = loggedSets.filter { it.isRecordEligible(exercise) }
            val metrics =
                when {
                    exercise.type.tracksDuration -> listOf(TrendMetric.HOLD)
                    exercise.weighted -> listOf(TrendMetric.WEIGHT, TrendMetric.VOLUME)
                    else -> listOf(TrendMetric.REPS)
                }
            metrics.mapNotNull { metric ->
                val baseline =
                    if (metric == TrendMetric.VOLUME) previous.volumeSet ?: return@mapNotNull null
                    else previous.set
                val best =
                    sets.maxWithOrNull(
                        compareBy<LoggedSet> { metric.value(it, exercise.asymmetric) }
                            .thenByDescending { it.completedAt }
                    ) ?: return@mapNotNull null
                val value = metric.value(best, exercise.asymmetric)
                if (value <= metric.value(baseline, exercise.asymmetric)) return@mapNotNull null
                WorkoutRecord(exerciseId, exercise.name, best.id, metric, value)
            }
        }
}

/** Remember announced values so redraws, ties and undo do not repeat celebrations. */
internal class WorkoutRecordNotifications {
    private var workoutId: String? = null
    private val bestValues = mutableMapOf<Pair<String, TrendMetric>, Double>()

    fun update(workoutId: String, records: List<WorkoutRecord>): List<WorkoutRecord> {
        if (this.workoutId != workoutId) {
            this.workoutId = workoutId
            bestValues.clear()
            records.forEach { bestValues[it.key] = it.value }
            return emptyList()
        }
        return records.filter { record ->
            val previous = bestValues[record.key]
            (previous == null || record.value > previous).also { improved ->
                if (improved) bestValues[record.key] = record.value
            }
        }
    }
}
