package lofit

/** Include action identity as well as visible text when deciding whether to publish again. */
internal data class WorkoutVoiceNotificationState(
    val title: String,
    val detail: String,
    val workoutId: String?,
    val setId: String?,
    val restEndsAt: Long?,
    val canComplete: Boolean,
)

internal fun WorkoutVoiceSession.notificationState(
    data: AppData,
    target: WorkoutVoiceTarget?,
    now: Long,
): WorkoutVoiceNotificationState {
    val workout = data.activeWorkout
    val item = workout?.exercises?.firstOrNull { it.id == target?.done?.exerciseId }
    val set = item?.plannedSets?.firstOrNull { it.id == target?.done?.setId }
    val exercise = data.exercises.firstOrNull { it.id == item?.exerciseId }
    val detail = buildList {
        add("Voice on")
        if (set != null && exercise != null) {
            add("Set ${item!!.plannedSets.indexOf(set) + 1}")
            if (exercise.weighted) add("${formatWeight(set.weightKg)} kg")
            add(if (exercise.type.tracksDuration) "${set.holdSeconds}s" else "${set.reps} reps")
        } else add("All sets done")
        target?.restEndsAt?.let {
            add("Rest ${((it - now + 999) / 1000).coerceAtLeast(0)}s")
        }
        if (activeHoldPlannedSetId != null)
            add("Hold ${((now - holdStartedAt) / 1000).coerceAtLeast(0)}s")
    }
        .joinToString(" · ")
    return WorkoutVoiceNotificationState(
        title = exercise?.name ?: workout?.name ?: "Workout",
        detail = detail,
        workoutId = workout?.id,
        setId = set?.id,
        restEndsAt = target?.restEndsAt,
        canComplete = set != null && item.sets.none { it.plannedSetId == set.id },
    )
}
