package lofit

/** Pull state and graph samples live only in memory; only the resulting set is saved. */
internal class LiveWorkoutMeasurement(private val data: () -> AppData) {
    companion object {
        const val PULL_THRESHOLD_KG = 1.0
    }

    data class Pull(
        val item: WorkoutExercise,
        val set: PlannedSet,
        val startedAt: Long,
        var lastReadingAt: Long,
        var cuePlayed: Boolean = false,
    ) {
        private val readings = mutableListOf<PullWeightReading>()

        fun addReading(atMillis: Long, kilograms: Double) {
            readings.add(PullWeightReading(atMillis, kilograms))
        }

        fun average(release: PullWeightReading?): Double? = averagePullWeight(readings, release)
    }

    data class Change(val pull: Pull, val started: Boolean = false, val completed: Boolean = false)

    var active: Pull? = null
        private set

    private var workoutId: String? = null
    private val traces = mutableMapOf<String, LiveMeasurementSeries>()

    fun seriesFor(itemId: String): LiveMeasurementSeries =
        traces.getOrPut(itemId) { LiveMeasurementSeries() }

    fun reconcile() {
        val workout = data().activeWorkout
        if (workoutId != workout?.id) {
            active = null
            traces.clear()
            workoutId = workout?.id
        }
        traces.keys.retainAll(workout?.exercises.orEmpty().map { it.id }.toSet())
        active?.let { pull ->
            if (
                workout?.exercises?.contains(pull.item) != true ||
                    pull.set !in pull.item.plannedSets ||
                    !isLive(pull.item)
            ) {
                active = null
            }
        }
    }

    fun isLive(item: WorkoutExercise): Boolean =
        !item.manualMeasurement &&
            data().exercises.firstOrNull { it.id == item.exerciseId }?.supportsLiveMeasurement ==
                true

    fun read(atMillis: Long, kilograms: Double, allowStart: Boolean = true): Change? {
        if (!kilograms.isFinite() || kilograms < 0) return null
        reconcile()
        val workout = data().activeWorkout ?: return null
        workout.exercises.filter(::isLive).forEach { seriesFor(it.id).add(atMillis, kilograms) }
        active?.let { pull ->
            if (atMillis < pull.lastReadingAt) return null
            if (kilograms <= PULL_THRESHOLD_KG) {
                return stop(atMillis, release = PullWeightReading(atMillis, kilograms))
            }
            pull.lastReadingAt = atMillis
            pull.addReading(atMillis, kilograms)
            return null
        }
        if (!allowStart || kilograms <= PULL_THRESHOLD_KG) return null
        // One scale measures one live set at a time, in workout order.
        val item =
            workout.exercises.firstOrNull { item ->
                isLive(item) &&
                    item.plannedSets.any { set -> item.sets.none { it.plannedSetId == set.id } }
            } ?: return null
        val set = item.plannedSets.first { set -> item.sets.none { it.plannedSetId == set.id } }
        set.actualHoldSeconds = 0
        val pull = Pull(item, set, atMillis, atMillis)
        pull.addReading(atMillis, kilograms)
        active = pull
        return Change(pull, started = true)
    }

    /** Returns true once at the goal, without completing a set until the scale is released. */
    fun tick(nowMillis: Long): Boolean {
        val pull = active ?: return false
        pull.set.actualHoldSeconds = elapsed(pull, nowMillis)
        if (!pull.cuePlayed && pull.set.actualHoldSeconds!! >= pull.set.holdSeconds) {
            pull.cuePlayed = true
            return true
        }
        return false
    }

    fun interrupt(): Change? {
        traces.values.forEach { it.endSegment() }
        // Missing readings cannot prove that the pull continued or that the scale was released.
        return active?.let { stop(it.lastReadingAt) }
    }

    private fun stop(atMillis: Long, release: PullWeightReading? = null): Change {
        val pull = requireNotNull(active)
        active = null
        val seconds = elapsed(pull, atMillis)
        pull.set.actualHoldSeconds = seconds
        val averageKg = pull.average(release)
        if (averageKg != null) pull.set.weightKg = averageKg
        val completed = release != null && seconds >= pull.set.holdSeconds && averageKg != null
        if (completed) {
            pull.item.sets.add(
                LoggedSet(
                    type = pull.set.type,
                    weightKg = requireNotNull(averageKg),
                    holdSeconds = seconds,
                    side = pull.set.side,
                    plannedSetId = pull.set.id,
                )
            )
        }
        return Change(pull, completed = completed)
    }

    private fun elapsed(pull: Pull, nowMillis: Long): Int =
        ((nowMillis - pull.startedAt).coerceAtLeast(0) / 1_000).toInt()
}
