package lofit

import java.util.UUID

/** Keep the routine's count and set kinds; refresh loads only from this routine's history. */
fun AppData.workoutFromRoutine(routine: Routine): WorkoutSession {
    val items =
        routine.exercises
            .map { item ->
                val previousExercise =
                    lastKnownExercise(
                        item.exerciseId,
                        preferredRoutineId = routine.id,
                        sameRoutineOnly = true,
                    )
                val latest = previousExercise?.plannedSets
                WorkoutExercise(
                    exerciseId = item.exerciseId,
                    restSecondsOverride = item.restSecondsOverride,
                    sourceRoutineExerciseId = item.id,
                    note = if (previousExercise == null) item.note else "",
                    previousNote = previousExercise?.effectiveNote.orEmpty(),
                    plannedSets =
                        item.plannedSets
                            .mapIndexed { index, saved ->
                                // Match warm-ups, working sets, and sides before falling back to
                                // row position.
                                val matching =
                                    latest
                                        .orEmpty()
                                        .filter { it.type == saved.type && it.side == saved.side }
                                        .ifEmpty {
                                            latest.orEmpty().filter { it.type == saved.type }
                                        }
                                val occurrence =
                                    item.plannedSets.take(index).count {
                                        it.type == saved.type && it.side == saved.side
                                    }
                                val previous =
                                    if (matching.isNotEmpty())
                                        matching.getOrNull(occurrence) ?: matching.last()
                                    else latest?.getOrNull(index) ?: latest?.lastOrNull()
                                saved.freshCopy().apply {
                                    previous?.let {
                                        weightKg = it.weightKg
                                        reps = it.reps
                                        holdSeconds = it.holdSeconds
                                    }
                                }
                            }
                            .toMutableList(),
                )
            }
            .toMutableList()
    val workout =
        WorkoutSession(
            name = routine.name,
            defaultRestSeconds = routine.defaultRestSeconds,
            exercises = items,
            sourceRoutine = routine,
        )
    return workout.copy(sourceRoutine = workout.captureRoutineBaseline())
}

/** Baseline uses workout row IDs, so repeated movements and later additions stay distinct. */
fun WorkoutSession.captureRoutineBaseline(): Routine? =
    sourceRoutine?.copy(
        exercises =
            exercises
                .map { item ->
                    RoutineExercise(
                        id = item.id,
                        exerciseId = item.exerciseId,
                        note = item.effectiveNote,
                        restSecondsOverride = item.restSecondsOverride,
                        plannedSets = item.plannedSets.map { it.freshCopy() }.toMutableList(),
                    )
                }
                .toMutableList()
    )

private fun PlannedSet.freshCopy() =
    copy(id = UUID.randomUUID().toString(), actualHoldSeconds = null)

private fun List<PlannedSet>.prescription() = map {
    listOf(it.type, it.weightKg, it.reps, it.holdSeconds, it.side)
}

class RoutineWorkoutChanges(
    val routine: Routine,
    val changed: List<Pair<RoutineExercise, WorkoutExercise>>,
    val added: List<WorkoutExercise>,
) {
    fun apply(updateSets: Boolean, includeAdded: Boolean) {
        if (updateSets)
            changed.forEach { (saved, trained) ->
                saved.note = trained.effectiveNote
                saved.plannedSets.clear()
                saved.plannedSets.addAll(trained.plannedSets.map { it.freshCopy() })
            }
        if (includeAdded)
            added.forEach { item ->
                val saved =
                    RoutineExercise(
                        exerciseId = item.exerciseId,
                        note = item.effectiveNote,
                        restSecondsOverride = item.restSecondsOverride,
                        plannedSets = item.plannedSets.map { it.freshCopy() }.toMutableList(),
                    )
                routine.exercises.add(saved)
                item.sourceRoutineExerciseId = saved.id
            }
    }
}

fun WorkoutSession.routineChanges(routines: List<Routine>): RoutineWorkoutChanges? {
    val baseline = sourceRoutine ?: return null
    val routine = routines.firstOrNull { it.id == baseline.id } ?: return null
    val initialById = baseline.exercises.associateBy { it.id }
    val changed = exercises.mapNotNull { item ->
        val initial = initialById[item.id] ?: return@mapNotNull null
        val saved =
            routine.exercises.firstOrNull {
                it.id == item.sourceRoutineExerciseId && it.exerciseId == item.exerciseId
            } ?: return@mapNotNull null
        if (
            initial.plannedSets.prescription() == item.plannedSets.prescription() &&
                initial.note == item.effectiveNote
        )
            null
        else saved to item
    }
    val added = exercises.filter { it.id !in initialById && it.sourceRoutineExerciseId == null }
    return if (changed.isEmpty() && added.isEmpty()) null
    else RoutineWorkoutChanges(routine, changed, added)
}
