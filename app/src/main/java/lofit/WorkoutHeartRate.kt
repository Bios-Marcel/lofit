package lofit

fun workoutsMissingHeartRate(history: List<WorkoutSession>): List<WorkoutSession> =
    history
        .filter { it.completedAt != null }
        .sortedByDescending { it.completedAt }
        .takeWhile { it.averageHeartRateBpm == null }
