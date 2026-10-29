package lofit

/** Set-based coverage shared by individual muscles and anatomy regions. */
class MuscleWorkload(exerciseSets: List<Pair<Exercise, Int>>) {
    companion object {
        private val targetGroups =
            linkedMapOf(
                "Legs" to
                    setOf(
                        "Abductors",
                        "Adductors",
                        "Calves",
                        "Feet",
                        "Gastrocnemius",
                        "Glutes",
                        "Hamstrings",
                        "Hip flexors",
                        "Posterior chain",
                        "Quadriceps",
                        "Soleus",
                        "Tibialis anterior",
                    ),
                "Arms" to
                    setOf("Biceps", "Brachialis", "Brachioradialis", "Forearms", "Grip", "Triceps"),
                "Shoulders" to
                    setOf(
                        "Front deltoids",
                        "Lateral deltoids",
                        "Rear deltoids",
                        "Rotator cuff",
                        "Shoulders",
                    ),
                "Back" to
                    setOf(
                        "Back",
                        "Erector spinae",
                        "Lats",
                        "Lower back",
                        "Middle back",
                        "Rhomboids",
                        "Traps",
                        "Upper back",
                    ),
                "Chest" to setOf("Chest", "Upper chest", "Serratus anterior"),
            )
        private val coreGroups = setOf("Abdominals", "Core", "Obliques")
    }

    private data class Work(val primary: String?, val secondary: Set<String>, val sets: Int)

    private val work =
        exerciseSets
            .filter { it.second > 0 }
            .map { (exercise, sets) ->
                Work(
                    MuscleGroups.canonical(exercise.primaryMuscle),
                    exercise.secondaryMuscles
                        .split(',')
                        .mapNotNull(MuscleGroups::canonical)
                        .toSet(),
                    sets,
                )
            }

    /** Primary sets count once; secondary sets count a quarter, including for core. */
    fun regionSetCredits(): Map<String, Double> =
        (targetGroups + ("Core" to coreGroups)).mapValues { (_, groups) ->
            work.sumOf { exercise ->
                exercise.sets.toDouble() *
                    when {
                        exercise.primary in groups -> 1.0
                        exercise.secondary.any(groups::contains) -> 0.25
                        else -> 0.0
                    }
            }
        }

    fun regionPercentages(): Map<String, Int> {
        val credits = regionSetCredits()
        val total = credits.values.sum()
        if (total == 0.0) return credits.mapValues { 0 }
        val exact = credits.mapValues { it.value / total * 100 }
        val rounded = exact.mapValues { it.value.toInt() }.toMutableMap()
        exact.keys
            .sortedByDescending { exact.getValue(it) - rounded.getValue(it) }
            .take(100 - rounded.values.sum())
            .forEach { rounded[it] = rounded.getValue(it) + 1 }
        return rounded
    }

    /**
     * Relative share within these five regions; core and neck work is outside this breakdown. Each
     * region gets one primary credit or a quarter secondary credit per set, without heat-map caps.
     */
    fun targetPercentages(): Map<String, Int> {
        val credits = targetGroups.mapValues { (_, groups) ->
            work.sumOf { exercise ->
                exercise.sets.toDouble() *
                    when {
                        exercise.primary in groups -> 1.0
                        exercise.secondary.any(groups::contains) -> 0.25
                        else -> 0.0
                    }
            }
        }
        val total = credits.values.sum()
        if (total == 0.0) return credits.mapValues { 0 }
        val exact = credits.mapValues { it.value / total * 100 }
        val rounded = exact.mapValues { it.value.toInt() }.toMutableMap()
        // Largest remainders keep displayed shares at exactly 100%.
        exact.keys
            .sortedByDescending { exact.getValue(it) - rounded.getValue(it) }
            .take(100 - rounded.values.sum())
            .forEach { rounded[it] = rounded.getValue(it) + 1 }
        return rounded
    }

    fun saturationFor(groups: Set<String>): Float {
        val canonicalGroups = groups.mapNotNull(MuscleGroups::canonical).toSet()
        var primarySets = 0
        var secondarySets = 0
        work.forEach { exercise ->
            // Count each set once even when several muscle names share a region.
            if (exercise.primary in canonicalGroups) primarySets += exercise.sets
            else if (exercise.secondary.any(canonicalGroups::contains))
                secondarySets += exercise.sets
        }
        return (primarySets / 8f + (secondarySets / 8f * 0.25f).coerceAtMost(0.25f)).coerceAtMost(
            1f
        )
    }
}
