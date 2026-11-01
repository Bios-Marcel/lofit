package lofit

internal enum class ExercisePlacement {
    REPLACE,
    ABOVE,
    BELOW,
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
