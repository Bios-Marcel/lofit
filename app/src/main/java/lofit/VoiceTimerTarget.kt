package lofit

internal data class VoiceTimerTarget(val exerciseId: String, val setId: String)

/** A running manual timer takes priority; otherwise choose the first visible unfinished set. */
internal fun voiceTimerTarget(
    workout: WorkoutSession?,
    exercises: List<Exercise>,
    visibleExerciseIds: Set<String>,
    active: VoiceTimerTarget? = null,
): VoiceTimerTarget? {
    for (item in workout?.exercises.orEmpty()) {
        if (active != null && item.id != active.exerciseId) continue
        if (item.id !in visibleExerciseIds) continue
        val exercise = exercises.firstOrNull { it.id == item.exerciseId } ?: continue
        if (!exercise.type.tracksDuration) continue
        if (exercise.supportsLiveMeasurement && !item.manualMeasurement) continue
        val set =
            item.plannedSets.firstOrNull { planned ->
                if (active != null) planned.id == active.setId
                else item.sets.none { it.plannedSetId == planned.id }
            } ?: continue
        return VoiceTimerTarget(item.id, set.id)
    }
    return null
}
