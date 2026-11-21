package lofit

internal class HistoryHealthUpdate(history: List<WorkoutSession>) {
    private val newestFirst =
        history.filter { it.completedAt != null }.sortedByDescending { it.completedAt }

    // LEGACY: Heart rate backfill stops at the first already-saved average.
    val heartRates = newestFirst.takeWhile { it.averageHeartRateBpm == null }
    val weights = newestFirst.takeWhile { it.bodyWeightKg == newestFirst.first().bodyWeightKg }
}
