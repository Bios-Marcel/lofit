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
    val rest =
        target?.restEndsAt?.let {
            val remaining = ((it - now + 999) / 1000).toInt().coerceAtLeast(0)
            formatRestTime(remaining, showSecondsUnit = true)
        }
    val hold = activeHoldPlannedSetId?.let {
        val elapsed = ((now - holdStartedAt) / 1000).toInt().coerceAtLeast(0)
        formatExerciseTime(elapsed, showSecondsUnit = true)
    }
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
                                if (exercise.type.tracksDuration)
                                    formatExerciseTime(set.holdSeconds, showSecondsUnit = true)
                                else "${set.reps} reps"
                            )
                        }
                            .joinToString(" × ")
                        "Set ${item.plannedSets.indexOf(set) + 1} of ${item.plannedSets.size} · $values"
                    }
                }
            when {
                rest != null -> "$setText · Rest $rest"
                hold != null -> "$setText · Hold $hold"
                else -> setText
            }
        } else
            buildList {
                    add("Voice on")
                    if (set != null && exercise != null) {
                        add("Set ${item!!.plannedSets.indexOf(set) + 1}")
                        if (exercise.weighted) add("${formatWeight(set.weightKg)} kg")
                        add(
                            if (exercise.type.tracksDuration)
                                formatExerciseTime(set.holdSeconds, showSecondsUnit = true)
                            else "${set.reps} reps"
                        )
                    } else add("All sets done")
                    if (rest != null) add("Rest $rest")
                    if (hold != null) add("Hold $hold")
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
