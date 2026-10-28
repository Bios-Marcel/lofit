package lofit

fun AppData.exercisesByRecency(): List<Exercise> {
    val lastUsedAt = exerciseLastSelectedAt.toMutableMap()
    history.forEach { workout ->
        workout.exercises.forEach { item ->
            item.sets.maxOfOrNull { it.completedAt }?.let { completedAt ->
                lastUsedAt[item.exerciseId] = maxOf(lastUsedAt[item.exerciseId] ?: Long.MIN_VALUE, completedAt)
            }
        }
    }
    // Stable sort keeps the catalog order for unused exercises and equal timestamps.
    return exercises.sortedByDescending { lastUsedAt[it.id] ?: Long.MIN_VALUE }
}

fun AppData.recordExerciseRecency(workout: WorkoutSession) {
    val completedAt = workout.completedAt ?: return
    workout.exercises.forEach { item ->
        exerciseLastSelectedAt[item.exerciseId] = maxOf(
            exerciseLastSelectedAt[item.exerciseId] ?: Long.MIN_VALUE, completedAt,
        )
    }
}
