package lofit

internal data class VoiceTimerTarget(val exerciseId: String, val setId: String)

/** A running set takes priority; otherwise choose the first visible unfinished eligible set. */
internal fun voiceTimerTarget(
    workout: WorkoutSession?,
    exercises: List<Exercise>,
    visibleExerciseIds: Set<String>,
    scaleAvailable: Boolean,
    active: VoiceTimerTarget? = null,
    timersOnly: Boolean = true,
): VoiceTimerTarget? {
    for (item in workout?.exercises.orEmpty()) {
        if (active != null && item.id != active.exerciseId) continue
        if (item.id !in visibleExerciseIds) continue
        val exercise = exercises.firstOrNull { it.id == item.exerciseId } ?: continue
        // LEGACY: “time” requires a manual timer; “done” also accepts repetition and live sets.
        if (timersOnly && !exercise.type.tracksDuration) continue
        if (
            timersOnly &&
                scaleAvailable &&
                exercise.supportsLiveMeasurement &&
                !item.manualMeasurement
        )
            continue
        val set =
            item.plannedSets.firstOrNull { planned ->
                if (active != null) planned.id == active.setId
                else item.sets.none { it.plannedSetId == planned.id }
            } ?: continue
        return VoiceTimerTarget(item.id, set.id)
    }
    return null
}
