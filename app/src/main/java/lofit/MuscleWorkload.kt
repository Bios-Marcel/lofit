package lofit

/** Set-based coverage shared by individual muscles and anatomy regions. */
class MuscleWorkload(exerciseSets: List<Pair<Exercise, Int>>) {
    private data class Work(val primary: String?, val secondary: Set<String>, val sets: Int)

    private val work = exerciseSets.filter { it.second > 0 }.map { (exercise, sets) ->
        Work(
            MuscleGroups.canonical(exercise.primaryMuscle),
            exercise.secondaryMuscles.split(',').mapNotNull(MuscleGroups::canonical).toSet(),
            sets,
        )
    }

    fun saturationFor(groups: Set<String>): Float {
        val canonicalGroups = groups.mapNotNull(MuscleGroups::canonical).toSet()
        var primarySets = 0
        var secondarySets = 0
        work.forEach { exercise ->
            // Count each set once even when several muscle names share a region.
            if (exercise.primary in canonicalGroups) primarySets += exercise.sets
            else if (exercise.secondary.any(canonicalGroups::contains)) secondarySets += exercise.sets
        }
        return (primarySets / 8f + (secondarySets / 8f * 0.25f).coerceAtMost(0.25f))
            .coerceAtMost(1f)
    }
}
