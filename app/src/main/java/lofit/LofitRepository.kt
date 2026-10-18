package lofit

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class LofitRepository(context: Context) {
    private val preferences = context.getSharedPreferences("lofit-data", Context.MODE_PRIVATE)
    var data: AppData = load()
        private set

    fun save() {
        preferences.edit { putString("state", encode(data).toString()) }
    }

    fun reset() {
        data = AppData()
        preferences.edit { clear() }
    }

    private fun load(): AppData {
        val raw = preferences.getString("state", null) ?: return AppData()
        return runCatching { decode(JSONObject(raw)) }.getOrElse { AppData() }
    }

    private fun encode(data: AppData) = JSONObject().apply {
        put("exercises", JSONArray().apply { data.exercises.forEach { put(encodeExercise(it)) } })
        put("routines", JSONArray().apply { data.routines.forEach { put(encodeRoutine(it)) } })
        put("history", JSONArray().apply { data.history.forEach { put(encodeWorkout(it)) } })
        put("activeWorkout", data.activeWorkout?.let(::encodeWorkout) ?: JSONObject.NULL)
    }

    private fun encodeExercise(exercise: Exercise) = JSONObject().apply {
        put("id", exercise.id)
        put("name", exercise.name)
        put("type", exercise.type.name)
        put("asymmetric", exercise.asymmetric)
        put("primaryMuscle", exercise.primaryMuscle)
        put("secondaryMuscles", exercise.secondaryMuscles)
        put("defaultRestSeconds", exercise.defaultRestSeconds)
        put("minimumHoldSeconds", exercise.minimumHoldSeconds)
        put("custom", exercise.custom)
        put("imageResource", exercise.imageResource ?: JSONObject.NULL)
        put("equipment", exercise.equipment)
        put("imageName", exercise.imageName ?: JSONObject.NULL)
    }

    private fun encodeRoutine(routine: Routine) = JSONObject().apply {
        put("id", routine.id)
        put("name", routine.name)
        put("defaultRestSeconds", routine.defaultRestSeconds)
        put("exercises", JSONArray().apply {
            routine.exercises.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("exerciseId", item.exerciseId)
                    put("targetSets", item.plannedSets.size)
                    put("targetReps", item.plannedSets.firstOrNull()?.reps ?: item.targetReps)
                    put("targetHoldSeconds", item.plannedSets.firstOrNull()?.holdSeconds ?: item.targetHoldSeconds)
                    put("restSecondsOverride", item.restSecondsOverride ?: JSONObject.NULL)
                    put("plannedSets", encodePlannedSets(item.plannedSets))
                })
            }
        })
    }

    private fun encodeWorkout(workout: WorkoutSession) = JSONObject().apply {
        put("id", workout.id)
        put("name", workout.name)
        put("startedAt", workout.startedAt)
        put("completedAt", workout.completedAt ?: JSONObject.NULL)
        put("defaultRestSeconds", workout.defaultRestSeconds)
        put("exercises", JSONArray().apply {
            workout.exercises.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("exerciseId", item.exerciseId)
                    put("restSecondsOverride", item.restSecondsOverride ?: JSONObject.NULL)
                    put("targetSets", item.plannedSets.size.takeIf { item.targetSets != null } ?: JSONObject.NULL)
                    put("targetReps", item.plannedSets.firstOrNull()?.reps ?: item.targetReps ?: JSONObject.NULL)
                    put("targetHoldSeconds", item.plannedSets.firstOrNull()?.holdSeconds ?: item.targetHoldSeconds ?: JSONObject.NULL)
                    put("plannedSets", encodePlannedSets(item.plannedSets))
                    put("sets", JSONArray().apply {
                        item.sets.forEach { set ->
                            put(JSONObject().apply {
                                put("id", set.id)
                                put("type", set.type.name)
                                put("weightKg", set.weightKg)
                                put("reps", set.reps)
                                put("holdSeconds", set.holdSeconds)
                                put("side", set.side.name)
                                put("completedAt", set.completedAt)
                                put("plannedSetId", set.plannedSetId ?: JSONObject.NULL)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun decode(root: JSONObject): AppData {
        val savedExercises = root.getJSONArray("exercises").mapObjects(::decodeExercise)
        val defaults = DefaultData.exercises()
        val defaultIds = defaults.mapTo(mutableSetOf()) { it.id }
        val exercises = (defaults + savedExercises.filter { it.custom && it.id !in defaultIds }).toMutableList()
        val routines = root.optJSONArray("routines")?.mapObjects(::decodeRoutine)?.toMutableList() ?: mutableListOf()
        val history = root.optJSONArray("history")?.mapObjects(::decodeWorkout)?.toMutableList() ?: mutableListOf()
        val active = root.optJSONObject("activeWorkout")?.let(::decodeWorkout)
        return AppData(exercises.ifEmpty { DefaultData.exercises().toMutableList() }, routines, history, active)
    }

    private fun decodeExercise(value: JSONObject) = Exercise(
        value.getString("id"),
        value.getString("name"),
        ExerciseType.valueOf(value.getString("type")),
        value.optBoolean("asymmetric"),
        value.optString("primaryMuscle"),
        value.optString("secondaryMuscles"),
        value.optInt("defaultRestSeconds", 90),
        value.optInt("minimumHoldSeconds", 30),
        value.optBoolean("custom"),
        value.optInt("imageResource").takeIf { !value.isNull("imageResource") },
        value.optString("equipment"),
        value.optString("imageName").takeIf { !value.isNull("imageName") },
    )

    private fun decodeRoutine(value: JSONObject) = Routine(
        value.getString("id"),
        value.getString("name"),
        value.optInt("defaultRestSeconds", 90),
        value.getJSONArray("exercises").mapObjects { item ->
            val targetSets = item.optInt("targetSets", 3)
            val targetReps = item.optInt("targetReps", 8)
            val targetHoldSeconds = item.optInt("targetHoldSeconds", 30)
            RoutineExercise(
                item.optString("id", java.util.UUID.randomUUID().toString()),
                item.getString("exerciseId"),
                targetSets,
                targetReps,
                targetHoldSeconds,
                item.optInt("restSecondsOverride").takeIf { !item.isNull("restSecondsOverride") },
                decodePlannedSets(item.optJSONArray("plannedSets")).ifEmpty {
                    MutableList(targetSets) { PlannedSet(reps = targetReps, holdSeconds = targetHoldSeconds) }
                },
            )
        }.toMutableList(),
    )

    private fun decodeWorkout(value: JSONObject) = WorkoutSession(
        value.getString("id"),
        value.getString("name"),
        value.getLong("startedAt"),
        value.optLong("completedAt").takeIf { !value.isNull("completedAt") },
        value.optInt("defaultRestSeconds", 90),
        value.getJSONArray("exercises").mapObjects { item ->
            val loggedSets = item.optJSONArray("sets")?.mapObjects { set ->
                LoggedSet(
                    id = set.getString("id"),
                    type = SetType.valueOf(set.getString("type")),
                    weightKg = set.optDouble("weightKg"),
                    reps = set.optInt("reps"),
                    holdSeconds = set.optInt("holdSeconds"),
                    side = Side.valueOf(set.optString("side", Side.BOTH.name)),
                    completedAt = set.optLong("completedAt"),
                    plannedSetId = set.optString("plannedSetId").takeIf { !set.isNull("plannedSetId") },
                )
            }?.toMutableList() ?: mutableListOf()
            val plannedSets = decodePlannedSets(item.optJSONArray("plannedSets")).ifEmpty {
                MutableList(item.optInt("targetSets", 0)) {
                    PlannedSet(
                        reps = item.optInt("targetReps", 8),
                        holdSeconds = item.optInt("targetHoldSeconds", 30),
                    )
                }
            }
            while (plannedSets.size < loggedSets.size) {
                val set = loggedSets[plannedSets.size]
                plannedSets.add(PlannedSet(
                    type = set.type,
                    weightKg = set.weightKg,
                    reps = set.reps,
                    holdSeconds = set.holdSeconds,
                    side = set.side,
                ))
            }
            loggedSets.forEachIndexed { index, set ->
                if (set.plannedSetId == null) set.plannedSetId = plannedSets.getOrNull(index)?.id
                plannedSets.getOrNull(index)?.let { plannedSet ->
                    if (plannedSet.actualHoldSeconds == null && set.holdSeconds > 0) {
                        plannedSet.actualHoldSeconds = set.holdSeconds
                    }
                }
            }
            WorkoutExercise(
                id = item.getString("id"),
                exerciseId = item.getString("exerciseId"),
                restSecondsOverride = item.optInt("restSecondsOverride").takeIf { !item.isNull("restSecondsOverride") },
                targetSets = item.optInt("targetSets").takeIf { !item.isNull("targetSets") },
                targetReps = item.optInt("targetReps").takeIf { !item.isNull("targetReps") },
                targetHoldSeconds = item.optInt("targetHoldSeconds").takeIf { !item.isNull("targetHoldSeconds") },
                sets = loggedSets,
                plannedSets = plannedSets,
            )
        }.toMutableList(),
    )

    private fun encodePlannedSets(sets: List<PlannedSet>) = JSONArray().apply {
        sets.forEach { set ->
            put(JSONObject().apply {
                put("id", set.id)
                put("type", set.type.name)
                put("weightKg", set.weightKg)
                put("reps", set.reps)
                put("holdSeconds", set.holdSeconds)
                put("actualHoldSeconds", set.actualHoldSeconds ?: JSONObject.NULL)
                put("side", set.side.name)
            })
        }
    }

    private fun decodePlannedSets(value: JSONArray?): MutableList<PlannedSet> =
        value?.mapObjects { set ->
            PlannedSet(
                id = set.optString("id", java.util.UUID.randomUUID().toString()),
                type = runCatching { SetType.valueOf(set.optString("type", SetType.NORMAL.name)) }
                    .getOrDefault(SetType.NORMAL),
                weightKg = set.optDouble("weightKg"),
                reps = set.optInt("reps", 8),
                holdSeconds = set.optInt("holdSeconds", 30),
                actualHoldSeconds = set.optInt("actualHoldSeconds").takeIf { !set.isNull("actualHoldSeconds") },
                side = runCatching { Side.valueOf(set.optString("side", Side.BOTH.name)) }
                    .getOrDefault(Side.BOTH),
            )
        }?.toMutableList() ?: mutableListOf()
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

