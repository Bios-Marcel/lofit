package lofit

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A plan only: no history, completed sets, body metrics, or running timers. */
data class SharedWorkoutPlan(val routine: Routine, val exercises: List<Exercise>) {
    fun startIn(data: AppData): WorkoutSession {
        check(data.activeWorkout == null) { "Finish or discard the active workout first" }
        val mapped = exercises.associate { remote ->
            val local = data.exercises.firstOrNull { sameMovement(it, remote) }
                ?: remote.copy(id = UUID.randomUUID().toString(), custom = true).also(data.exercises::add)
            remote.id to local.id
        }
        return WorkoutSession(
            name = routine.name,
            defaultRestSeconds = routine.defaultRestSeconds,
            exercises = routine.exercises.map { item ->
                WorkoutExercise(
                    exerciseId = mapped.getValue(item.exerciseId),
                    note = item.note,
                    restSecondsOverride = item.restSecondsOverride,
                    plannedSets = item.plannedSets.map {
                        it.copy(id = UUID.randomUUID().toString(), actualHoldSeconds = null)
                    }.toMutableList(),
                )
            }.toMutableList(),
        )
    }

    companion object {
        const val MAX_BYTES = 256 * 1024

        private fun sameMovement(a: Exercise, b: Exercise): Boolean =
            a.name == b.name && a.type == b.type && a.asymmetric == b.asymmetric &&
                a.primaryMuscle == b.primaryMuscle && a.secondaryMuscles == b.secondaryMuscles &&
                a.minimumHoldSeconds == b.minimumHoldSeconds && a.weighted == b.weighted

        fun encode(workout: WorkoutSession, catalog: List<Exercise>): ByteArray {
            val movements = workout.exercises.map { item ->
                requireNotNull(catalog.firstOrNull { it.id == item.exerciseId })
            }.distinctBy { it.id }
            val root = JSONObject().put("version", 1).put("name", workout.name)
                .put("rest", workout.defaultRestSeconds)
                .put("movements", JSONArray().apply {
                    movements.forEach { e -> put(JSONObject().put("id", e.id).put("name", e.name)
                        .put("type", e.type.name).put("asymmetric", e.asymmetric)
                        .put("primary", e.primaryMuscle).put("secondary", e.secondaryMuscles)
                        .put("hold", e.minimumHoldSeconds).put("weighted", e.weighted)) }
                })
                .put("exercises", JSONArray().apply {
                    workout.exercises.forEach { item ->
                        // Unlike history-to-routine conversion, keep unfinished prescribed sets too.
                        val sets = item.plannedSets.ifEmpty {
                            item.sets.map { PlannedSet(type = it.type, weightKg = it.weightKg,
                                reps = it.reps, holdSeconds = it.holdSeconds, side = it.side) }
                        }
                        put(JSONObject().put("movement", item.exerciseId)
                            .put("note", item.effectiveNote)
                            .put("rest", item.restSecondsOverride ?: JSONObject.NULL)
                            .put("sets", JSONArray().apply {
                                sets.forEach { s -> put(JSONObject().put("type", s.type.name)
                                    .put("weight", s.weightKg).put("reps", s.reps)
                                    .put("hold", s.holdSeconds).put("side", s.side.name)) }
                            }))
                    }
                })
            return root.toString().toByteArray(Charsets.UTF_8).also {
                require(it.size <= MAX_BYTES) { "Workout plan is too large to share" }
                decode(it) // Apply the same bounds on both ends.
            }
        }

        fun decode(bytes: ByteArray): SharedWorkoutPlan {
            require(bytes.size in 1..MAX_BYTES)
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("version") == 1) { "Unsupported workout plan version" }
            val movements = root.getJSONArray("movements").objects(200).map { e ->
                Exercise(id = e.text("id"), name = e.text("name"),
                    type = ExerciseType.valueOf(e.getString("type")),
                    asymmetric = e.getBoolean("asymmetric"), primaryMuscle = e.text("primary"),
                    secondaryMuscles = e.getString("secondary").also { require(it.length <= 500) },
                    minimumHoldSeconds = e.number("hold", 1, 3_600), weighted = e.getBoolean("weighted"),
                    custom = true)
            }
            require(movements.map { it.id }.distinct().size == movements.size)
            val routine = Routine(name = root.text("name"), defaultRestSeconds = root.number("rest", 0, 3_600),
                exercises = root.getJSONArray("exercises").objects(200).map { e ->
                    val movement = e.text("movement")
                    require(movements.any { it.id == movement })
                    RoutineExercise(exerciseId = movement,
                        note = e.optString("note", ""),
                        restSecondsOverride = if (e.isNull("rest")) null else e.number("rest", 0, 3_600),
                        plannedSets = e.getJSONArray("sets").objects(200).map { s ->
                            PlannedSet(type = SetType.valueOf(s.getString("type")),
                                weightKg = s.getDouble("weight").also { require(it.isFinite() && it in 0.0..100_000.0) },
                                reps = s.number("reps", 0, 100_000), holdSeconds = s.number("hold", 0, 86_400),
                                side = Side.valueOf(s.getString("side")))
                        }.toMutableList())
                }.toMutableList())
            return SharedWorkoutPlan(routine, movements)
        }

        private fun JSONArray.objects(max: Int): List<JSONObject> {
            require(length() <= max)
            return List(length()) { getJSONObject(it) }
        }
        private fun JSONObject.text(key: String): String = getString(key).also {
            require(it.isNotBlank() && it.length <= 200)
        }
        private fun JSONObject.number(key: String, min: Int, max: Int): Int = getInt(key).also {
            require(it in min..max)
        }
    }
}
