package lofit

/** Include action identity as well as visible text when deciding whether to publish again. */
internal data class WorkoutVoiceNotificationState(
    val title: String,
    val detail: String,
    val workoutId: String?,
    val setId: String?,
    val restEndsAt: Long?,
    val canComplete: Boolean,
    val voiceEnabled: Boolean,
)

internal fun WorkoutVoiceSession.notificationState(
    data: AppData,
    target: WorkoutVoiceTarget?,
    now: Long,
    voiceEnabled: Boolean = enabled,
): WorkoutVoiceNotificationState {
    val workout = data.activeWorkout
    val item =
        workout?.exercises?.firstOrNull { it.id == target?.done?.exerciseId }
            ?: workout?.exercises?.firstOrNull { it.plannedSets.isEmpty() }
            ?: workout?.exercises?.lastOrNull()
    val set = item?.plannedSets?.firstOrNull { it.id == target?.done?.setId }
    val exercise = data.exercises.firstOrNull { it.id == item?.exerciseId }
    val detail =
        if (!voiceEnabled) {
            val setText =
                when {
                    item == null -> "Add an exercise to continue"
                    item.plannedSets.isEmpty() -> "Add a set to continue"
                    set == null || exercise == null -> "All ${workout?.setCount() ?: 0} sets done"
                    else -> {
                        val values = buildList {
                            if (exercise.weighted) add("${formatWeight(set.weightKg)} kg")
                            add(
                                if (exercise.type.tracksDuration) "${set.holdSeconds}s"
                                else "${set.reps} reps"
                            )
                        }
                            .joinToString(" × ")
                        "Set ${item.plannedSets.indexOf(set) + 1} of ${item.plannedSets.size} · $values"
                    }
                }
            when {
                target?.restEndsAt != null ->
                    "$setText · Rest ${formatSeconds(((target.restEndsAt - now + 999) / 1000).toInt().coerceAtLeast(0))}"
                activeHoldPlannedSetId != null ->
                    "$setText · Hold ${formatSeconds(((now - holdStartedAt) / 1000).toInt().coerceAtLeast(0))}"
                else -> setText
            }
        } else
            buildList {
                    add("Voice on")
                    if (set != null && exercise != null) {
                        add("Set ${item!!.plannedSets.indexOf(set) + 1}")
                        if (exercise.weighted) add("${formatWeight(set.weightKg)} kg")
                        add(
                            if (exercise.type.tracksDuration) "${set.holdSeconds}s"
                            else "${set.reps} reps"
                        )
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
        voiceEnabled = voiceEnabled,
    )
}
