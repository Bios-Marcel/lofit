package lofit

import java.util.UUID

internal enum class ExercisePlacement {
    REPLACE,
    ABOVE,
    BELOW,
}

internal fun Exercise.canKeepLoggedSetsFrom(original: Exercise): Boolean =
    type == original.type && asymmetric == original.asymmetric && weighted == original.weighted

internal fun nextPlannedSets(sets: List<PlannedSet>, exercise: Exercise): List<PlannedSet> {
    val paired =
        sets.size >= 2 &&
            sets.size % 2 == 0 &&
            sets.all { it.side != Side.BOTH } &&
            sets.zipWithNext().all { (first, second) -> first.side != second.side }
    val previous =
        if (paired) sets.takeLast(2)
        else {
            // LEGACY: Unpaired sets keep adding a single copy of the last set.
            sets.takeLast(1)
        }
    return previous
        .map { it.copy(id = UUID.randomUUID().toString(), actualHoldSeconds = null) }
        .ifEmpty { listOf(PlannedSet(holdSeconds = exercise.minimumHoldSeconds)) }
}

internal fun replacementSets(
    sets: List<PlannedSet>,
    original: Exercise,
    replacement: Exercise,
    previousSets: List<PlannedSet> = emptyList(),
): MutableList<PlannedSet> =
    sets
        .mapIndexed { index, set ->
            val previous = previousSets.getOrNull(index) ?: previousSets.lastOrNull()
            PlannedSet(
                type = set.type,
                weightKg = if (replacement.weighted) previous?.weightKg ?: 0.0 else 0.0,
                reps = if (original.type == replacement.type) set.reps else 8,
                holdSeconds =
                    if (original.type.tracksDuration && replacement.type.tracksDuration)
                        set.holdSeconds.coerceAtLeast(replacement.minimumHoldSeconds)
                    else replacement.minimumHoldSeconds,
                side = if (original.asymmetric && replacement.asymmetric) set.side else Side.BOTH,
            )
        }
        .toMutableList()
