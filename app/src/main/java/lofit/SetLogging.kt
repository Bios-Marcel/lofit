package lofit

internal fun loggedSetFrom(
    plannedSet: PlannedSet,
    exercise: Exercise,
    completedAt: Long? = null,
) =
    LoggedSet(
        type = plannedSet.type,
        weightKg = plannedSet.weightKg.takeIf { exercise.weighted } ?: 0.0,
        reps = if (!exercise.type.tracksDuration) plannedSet.reps else 0,
        holdSeconds = if (exercise.type.tracksDuration) plannedSet.actualHoldSeconds ?: 0 else 0,
        side = plannedSet.side,
        completedAt = completedAt ?: System.currentTimeMillis(),
        plannedSetId = plannedSet.id,
    )

internal fun syncLoggedSet(loggedSet: LoggedSet, plannedSet: PlannedSet, exercise: Exercise) {
    loggedSet.type = plannedSet.type
    loggedSet.weightKg = plannedSet.weightKg.takeIf { exercise.weighted } ?: 0.0
    loggedSet.reps = if (!exercise.type.tracksDuration) plannedSet.reps else 0
    loggedSet.holdSeconds =
        if (exercise.type.tracksDuration) plannedSet.actualHoldSeconds ?: 0 else 0
    loggedSet.side = plannedSet.side
}
