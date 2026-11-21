package lofit

import java.util.UUID

/** Fit the active sets to the requested time. Rest between sets is separate. */
internal fun WorkoutSession.setCardioDuration(
    minutes: Int,
    catalog: Map<String, Exercise>,
): String? {
    if (minutes <= 0) return "Enter a positive number of minutes."
    if (exercises.isEmpty() || exercises.any { catalog[it.exerciseId]?.cardio != true })
        return "Choose a workout with only cardio exercises."

    val target = minutes * 60L
    val cycle = exercises.sumOf { item ->
        item.plannedSets.sumOf { it.activeSeconds(catalog.getValue(item.exerciseId)) }
    }
    if (cycle == 0L) return "Add a cardio set with a positive duration or rep count first."
    if (target == cycle) return null
    if (target < cycle) return trimCardioDuration(target, catalog)

    val additions = mutableListOf<WorkoutExercise>()
    var remaining = target - cycle
    var addedSets = 0
    while (remaining > 0) {
        for (item in exercises) {
            val exercise = catalog.getValue(item.exerciseId)
            val sets = mutableListOf<PlannedSet>()
            for (set in item.plannedSets) {
                // The workout screen creates a view for every set.
                if (++addedSets > 500) return "Choose a shorter duration or use longer sets."
                val next = set.freshCopy()
                next.limitTo(remaining, exercise)
                sets.add(next)
                remaining -= next.activeSeconds(exercise)
                if (remaining <= 0) break
            }
            if (sets.isNotEmpty()) {
                additions.add(
                    item.copy(
                        id = UUID.randomUUID().toString(),
                        plannedSets = sets,
                        sets = mutableListOf(),
                        sourceRoutineExerciseId = null,
                    )
                )
            }
            if (remaining <= 0) break
        }
    }
    exercises.addAll(additions)
    return null
}

private fun WorkoutSession.trimCardioDuration(
    target: Long,
    catalog: Map<String, Exercise>,
): String? {
    var remaining = target
    for ((itemIndex, item) in exercises.withIndex()) {
        val exercise = catalog.getValue(item.exerciseId)
        for ((setIndex, set) in item.plannedSets.withIndex()) {
            val seconds = set.activeSeconds(exercise)
            if (seconds < remaining) {
                remaining -= seconds
                continue
            }
            val last = set.copy().apply { limitTo(remaining, exercise) }
            val affectedSets = item.plannedSets.drop(if (last == set) setIndex + 1 else setIndex)
            val later = exercises.subList(itemIndex + 1, exercises.size)
            if (
                affectedSets.any { s ->
                    s.actualHoldSeconds != null || item.sets.any { it.plannedSetId == s.id }
                } ||
                    later.any { row ->
                        row.sets.isNotEmpty() ||
                            row.plannedSets.any { it.actualHoldSeconds != null }
                    }
            ) {
                return "Choose a longer duration to keep sets you’ve already started."
            }
            set.reps = last.reps
            set.holdSeconds = last.holdSeconds
            item.plannedSets.subList(setIndex + 1, item.plannedSets.size).clear()
            later.clear()
            return null
        }
    }
    error("Target exceeds the programmed cardio duration")
}

private fun PlannedSet.limitTo(seconds: Long, exercise: Exercise) {
    if (activeSeconds(exercise) <= seconds) return
    if (exercise.type.tracksDuration) holdSeconds = seconds.toInt()
    else reps = ((seconds + ESTIMATED_SECONDS_PER_REP - 1) / ESTIMATED_SECONDS_PER_REP).toInt()
}
