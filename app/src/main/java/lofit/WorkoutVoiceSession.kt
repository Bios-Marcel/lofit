package lofit

/** Workout state shared by the screen and service, accessed only on the main thread. */
internal class WorkoutVoiceSession(
    private val data: () -> AppData,
    private val save: () -> Unit,
    private val now: () -> Long,
) {
    enum class Cue {
        CHANGE,
        DONE,
    }

    private data class CardioContinuation(val workoutId: String, val completed: VoiceTimerTarget)

    private var pendingCardio: CardioContinuation? = null
    var activeHoldWorkoutExerciseId: String? = null
    var activeHoldPlannedSetId: String? = null
    var holdStartedAt = 0L
    var holdCuePlayed = false
    var restEndsAt = 0L
        set(value) {
            field = value
            if (value == 0L) pendingCardio = null
        }

    var preferredExerciseId: String? = null
    val liveMeasurement = LiveWorkoutMeasurement(data)

    val enabled: Boolean
        get() =
            data().activeWorkout?.let { it.voiceCommandsEnabled && it.completedAt == null } == true

    private fun resolve(target: VoiceTimerTarget?): Pair<WorkoutExercise, PlannedSet>? {
        target ?: return null
        val item =
            data().activeWorkout?.exercises?.firstOrNull { it.id == target.exerciseId }
                ?: return null
        val set = item.plannedSets.firstOrNull { it.id == target.setId } ?: return null
        return item to set
    }

    private fun activeTarget(): VoiceTimerTarget? = activeHoldWorkoutExerciseId?.let { item ->
        activeHoldPlannedSetId?.let { VoiceTimerTarget(item, it) }
    }

    fun backgroundTarget(): WorkoutVoiceTarget? {
        val workout = data().activeWorkout ?: return null
        if (!enabled) return null
        val active =
            liveMeasurement.active?.let { VoiceTimerTarget(it.item.id, it.set.id) }
                ?: activeTarget()
        val ordered = workout.exercises.sortedBy { if (it.id == preferredExerciseId) 0 else 1 }
        val next =
            active?.takeIf { resolve(it) != null }
                ?: ordered.firstNotNullOfOrNull { item ->
                    item.plannedSets
                        .firstOrNull { set -> item.sets.none { it.plannedSetId == set.id } }
                        ?.let { VoiceTimerTarget(item.id, it.id) }
                }
        val timer = next?.takeIf {
            val item = resolve(it)?.first ?: return@takeIf false
            val exercise = data().exercises.firstOrNull { e -> e.id == item.exerciseId }
            exercise?.type?.tracksDuration == true &&
                !liveMeasurement.isLive(item) &&
                liveMeasurement.active == null
        }
        val rest = restEndsAt.takeIf { it > now() }
        return WorkoutVoiceTarget(timer, next, rest).takeIf { next != null || rest != null }
    }

    fun apply(command: VoiceCommand, expected: WorkoutVoiceTarget): Cue? {
        val current = backgroundTarget() ?: return null
        if (command == VoiceCommand.SKIP) {
            if (expected.restEndsAt == null || expected.restEndsAt != current.restEndsAt)
                return null
            finishRest()
            return Cue.CHANGE
        }
        val target = if (command == VoiceCommand.TIME) expected.timer else expected.done
        if (
            target == null ||
                target != if (command == VoiceCommand.TIME) current.timer else current.done
        )
            return null
        val (item, set) = resolve(target) ?: return null
        val exercise = data().exercises.firstOrNull { it.id == item.exerciseId } ?: return null
        when (command) {
            is VoiceCommand.SetValues -> {
                if (item.sets.any { it.plannedSetId == set.id }) return null
                if (!applyVoiceSetValues(command, exercise, set)) return null
            }
            VoiceCommand.TIME -> {
                if (activeTarget() == target) stopHold(item, set, exercise)
                else startHold(item, set)
            }
            VoiceCommand.DONE -> {
                if (activeTarget() == target) stopHold(item, set, exercise)
                liveMeasurement.stopForManualCompletion(set, now())
                record(item, set, exercise)
                startRest(item, exercise)
            }
            VoiceCommand.SKIP -> return null
        }
        save()
        return if (command == VoiceCommand.DONE) Cue.DONE else Cue.CHANGE
    }

    private fun record(item: WorkoutExercise, set: PlannedSet, exercise: Exercise) {
        item.sets
            .firstOrNull { it.plannedSetId == set.id }
            ?.let { syncLoggedSet(it, set, exercise) }
            ?: item.sets.add(loggedSetFrom(set, exercise))
    }

    fun startRest(item: WorkoutExercise, exercise: Exercise) {
        pendingCardio = null
        val workout = data().activeWorkout ?: return
        val seconds = item.restSecondsAfterSet(workout.defaultRestSeconds, exercise)
        restEndsAt = if (seconds > 0) now() + seconds * 1000L else 0L
    }

    /** Ends a rest and resumes an eligible cardio chain, including when rest is skipped. */
    fun finishRest(): Boolean {
        val pending = pendingCardio
        restEndsAt = 0
        if (pending == null || data().activeWorkout?.id != pending.workoutId) return false
        val (item, set) = resolve(pending.completed) ?: return false
        if (item.sets.none { it.plannedSetId == set.id }) return false
        return startFollowingCardio(item, set)
    }

    private fun startHold(item: WorkoutExercise, set: PlannedSet) {
        activeHoldWorkoutExerciseId = item.id
        activeHoldPlannedSetId = set.id
        holdStartedAt = now()
        holdCuePlayed = false
        restEndsAt = 0
        set.actualHoldSeconds = 0
    }

    /** Starts the next timed cardio set in this exercise or the immediately following one. */
    fun startFollowingCardio(item: WorkoutExercise, completedSet: PlannedSet): Boolean {
        val workout = data().activeWorkout ?: return false
        if (!workout.autoStartCardio) return false
        if (workout.completedAt != null || activeTarget() != null) return false
        if (completedSet.type == SetType.FAILURE || liveMeasurement.active != null) return false
        val exercise = data().exercises.firstOrNull { it.id == item.exerciseId } ?: return false
        if (!exercise.cardio || !exercise.type.tracksDuration) return false
        if (liveMeasurement.isLive(item)) return false
        val index = workout.exercises.indexOfFirst { it.id == item.id }
        if (index < 0) return false
        val remainingSet =
            item.plannedSets.firstOrNull { set ->
                item.sets.none { it.plannedSetId == set.id }
            }
        val nextItem =
            if (remainingSet != null) {
                if (
                    item.plannedSets.indexOf(remainingSet) <= item.plannedSets.indexOf(completedSet)
                )
                    return false
                item
            } else workout.exercises.getOrNull(index + 1) ?: return false
        val nextExercise =
            data().exercises.firstOrNull { it.id == nextItem.exerciseId } ?: return false
        // LEGACY: Non-cardio timers and automatic scale measurements still require a manual start.
        if (!nextExercise.cardio || !nextExercise.type.tracksDuration) return false
        if (liveMeasurement.isLive(nextItem)) return false
        val nextSet =
            remainingSet
                ?: nextItem.plannedSets.firstOrNull { set ->
                    nextItem.sets.none { it.plannedSetId == set.id }
                }
                ?: return false
        if (restEndsAt > now()) {
            pendingCardio =
                CardioContinuation(workout.id, VoiceTimerTarget(item.id, completedSet.id))
            return false
        }
        startHold(nextItem, nextSet)
        preferredExerciseId = nextItem.id
        save()
        return true
    }

    private fun stopHold(item: WorkoutExercise, set: PlannedSet, exercise: Exercise) {
        set.actualHoldSeconds = ((now() - holdStartedAt) / 1000).toInt().coerceAtLeast(1)
        item.sets
            .firstOrNull { it.plannedSetId == set.id }
            ?.let {
                syncLoggedSet(it, set, exercise)
                startRest(item, exercise)
            }
        clearHold()
    }

    fun clearHold() {
        activeHoldWorkoutExerciseId = null
        activeHoldPlannedSetId = null
        holdStartedAt = 0
        holdCuePlayed = false
    }

    /** Continues manual timers and rest cues while no activity is resumed. */
    fun tick(): Boolean {
        var cue = false
        if (restEndsAt > 0 && restEndsAt <= now()) {
            finishRest()
            cue = true
        }
        val target = activeTarget() ?: return cue
        val pair = resolve(target)
        if (pair == null) {
            clearHold()
            return cue
        }
        val (item, set) = pair
        val exercise = data().exercises.firstOrNull { it.id == item.exerciseId } ?: return cue
        val elapsed = ((now() - holdStartedAt) / 1000).toInt().coerceAtLeast(0)
        set.actualHoldSeconds = elapsed
        if (elapsed >= set.holdSeconds && !holdCuePlayed) {
            holdCuePlayed = true
            record(item, set, exercise)
            save()
            cue = true
        }
        // LEGACY: Failure sets stay running after the goal until explicitly stopped.
        if (elapsed >= set.holdSeconds && set.type != SetType.FAILURE) {
            stopHold(item, set, exercise)
            // LEGACY: Other timed exercises still wait for a manual start after completion.
            startFollowingCardio(item, set)
            save()
        }
        return cue
    }
}
