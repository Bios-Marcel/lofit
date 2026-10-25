package lofit

fun AppData.exercisesByRecency(): List<Exercise> {
    val lastUsedAt = exerciseLastSelectedAt.toMutableMap()
    (history.asSequence() + listOfNotNull(activeWorkout).asSequence()).forEach { workout ->
        workout.exercises.forEach { item ->
            item.sets.maxOfOrNull { it.completedAt }?.let { completedAt ->
                lastUsedAt[item.exerciseId] = maxOf(lastUsedAt[item.exerciseId] ?: Long.MIN_VALUE, completedAt)
            }
        }
    }
    // Stable sort keeps the catalog order for unused exercises and equal timestamps.
    return exercises.sortedByDescending { lastUsedAt[it.id] ?: Long.MIN_VALUE }
}
