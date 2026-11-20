package lofit

import kotlin.math.*

private fun WorkoutLocation.within100Meters(other: WorkoutLocation): Boolean {
    val halfLat = Math.toRadians(other.latitude - latitude) / 2
    val halfLon = Math.toRadians(other.longitude - longitude) / 2
    val a =
        sin(halfLat).pow(2) +
            cos(Math.toRadians(latitude)) *
                cos(Math.toRadians(other.latitude)) *
                sin(halfLon).pow(2)
    return 2 * 6371000 * asin(sqrt(a.coerceIn(0.0, 1.0))) <= 100
}

internal fun AppData.suggestWorkout(
    point: WorkoutLocation,
    now: Long = System.currentTimeMillis(),
): WorkoutSession? =
    suggestWorkout(now) { it.startLocation?.within100Meters(point) == true }
        ?.apply { startLocation = point }

internal fun AppData.suggestWorkoutOnWifi(
    wifiName: String,
    now: Long = System.currentTimeMillis(),
): WorkoutSession? =
    suggestWorkout(now) { it.startWifiName == wifiName }?.apply { startWifiName = wifiName }

private fun AppData.suggestWorkout(
    now: Long,
    matchesPlace: (WorkoutSession) -> Boolean,
): WorkoutSession? {
    val catalog = exercises.associateBy { it.id }
    val windowMillis = 28L * 86400000
    val recent = (now - windowMillis)..now
    val recoveryWindow = (now - 2L * 86400000)..now
    val recovering = mutableSetOf<String>()
    val credits = mutableMapOf<String, Double>()
    val nearby = linkedMapOf<Exercise, WorkoutExercise>()
    fun muscles(exercise: Exercise) =
        (listOf(exercise.primaryMuscle) + exercise.secondaryMuscles.split(','))
            .mapNotNull(MuscleGroups::canonical)
            .toSet()
    for (session in history.sortedByDescending { it.startedAt }) {
        val local = matchesPlace(session)
        for (entry in session.exercises) {
            val exercise = catalog[entry.exerciseId] ?: continue
            if (entry.sets.isEmpty()) continue
            if (local && !exercise.retired && muscles(exercise).isNotEmpty())
                nearby.putIfAbsent(exercise, entry)
            val primary = MuscleGroups.canonical(exercise.primaryMuscle)
            if (primary != null && entry.sets.any { it.completedAt in recoveryWindow })
                recovering.add(primary)
            // Training credit fades linearly to zero over four weeks.
            val weightedSets =
                entry.sets.sumOf { set ->
                    if (set.completedAt in recent)
                        (set.completedAt - recent.first).toDouble() / windowMillis
                    else 0.0
                }
            for (group in muscles(exercise)) {
                val weight = if (group == primary) 1.0 else 0.25
                credits[group] = credits.getOrDefault(group, 0.0) + weight * weightedSets
            }
        }
    }
    val ranked =
        nearby.keys
            .filter { muscles(it).none(recovering::contains) }
            .sortedBy { credits[MuscleGroups.canonical(it.primaryMuscle)] ?: 0.0 }
            .toMutableList()
    val workout = WorkoutSession(name = "Suggested workout")
    var remaining = 30
    var previous = emptySet<String>()
    while (ranked.isNotEmpty() && remaining > 0) {
        val next = ranked.firstOrNull { muscles(it).none(previous::contains) } ?: break
        ranked.remove(next)
        val source = nearby.getValue(next)
        val sets = source.freshPlannedSets().take(remaining).toMutableList()
        workout.exercises.add(
            WorkoutExercise(
                exerciseId = next.id,
                plannedSets = sets,
                restSecondsOverride = source.restSecondsOverride,
                previousNote = source.effectiveNote,
                manualMeasurement = source.manualMeasurement,
                pauseEverySecondSet = source.pauseEverySecondSet,
            )
        )
        remaining -= sets.size
        previous = muscles(next)
    }
    return workout.takeIf { it.exercises.isNotEmpty() }
}
