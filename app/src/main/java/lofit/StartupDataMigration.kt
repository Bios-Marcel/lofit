package lofit

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Unversioned data -> current schema. Called once, before the repository exposes data. After
 * installed data has migrated, delete this file and the schema-0 branch in load(). Keep
 * schemaVersion in the current codec. The original JSON stays in state-before-migration-v1.json.
 */
internal object StartupDataMigration {
    fun migrate(root: JSONObject): AppData {
        val savedExercises = root.getJSONArray("exercises").mapObjects(::decodeExercise)
        val routines =
            root.optJSONArray("routines")?.mapObjects(::decodeRoutine)?.toMutableList()
                ?: mutableListOf()
        val history =
            root.optJSONArray("history")?.mapObjects(::decodeWorkout)?.toMutableList()
                ?: mutableListOf()
        val active = root.optJSONObject("activeWorkout")?.let(::decodeWorkout)
        return AppData(
            exercises = savedExercises.toMutableList(),
            routines = routines,
            history = history,
            activeWorkout = active,
            soundCuesEnabled = root.optBoolean("soundCuesEnabled", true),
            bodyMetricsEnabled =
                if (root.isNull("bodyMetricsEnabled")) null
                else root.getBoolean("bodyMetricsEnabled"),
            biologicalGender =
                BiologicalGender.valueOf(
                    root.optString("biologicalGender", BiologicalGender.MALE.name)
                ),
            onboardingComplete = root.optBoolean("onboardingComplete", false),
        )
    }

    private fun decodeExercise(value: JSONObject) =
        Exercise(
            id = value.getString("id"),
            name = value.getString("name"),
            type = decodeExerciseType(value.getString("type")),
            asymmetric = value.optBoolean("asymmetric"),
            primaryMuscle = value.optString("primaryMuscle"),
            secondaryMuscles = value.optString("secondaryMuscles"),
            minimumHoldSeconds = value.optInt("minimumHoldSeconds", 30),
            custom = value.optBoolean("custom"),
            imageResource = value.optInt("imageResource").takeIf { !value.isNull("imageResource") },
            imageName = value.optString("imageName").takeIf { !value.isNull("imageName") },
            weighted = value.optBoolean("weighted", true),
        )

    private fun decodeRoutine(value: JSONObject) =
        Routine(
            value.getString("id"),
            value.getString("name"),
            value.optInt("defaultRestSeconds", 120),
            value
                .getJSONArray("exercises")
                .mapObjects { item ->
                    val targetSets = item.optInt("targetSets", 3)
                    val targetReps = item.optInt("targetReps", 8)
                    val targetHoldSeconds = item.optInt("targetHoldSeconds", 30)
                    RoutineExercise(
                        id = item.optString("id", UUID.randomUUID().toString()),
                        exerciseId = item.getString("exerciseId"),
                        restSecondsOverride =
                            item.optInt("restSecondsOverride").takeIf {
                                !item.isNull("restSecondsOverride")
                            },
                        plannedSets =
                            item.optJSONArray("plannedSets")?.let(::decodePlannedSets)
                                ?: MutableList(targetSets) {
                                    PlannedSet(reps = targetReps, holdSeconds = targetHoldSeconds)
                                },
                    )
                }
                .toMutableList(),
        )

    private fun decodeWorkout(value: JSONObject) =
        WorkoutSession(
            value.getString("id"),
            value.getString("name"),
            value.workoutIntervals(),
            value.optInt("defaultRestSeconds", 120),
            value
                .getJSONArray("exercises")
                .mapObjects { item ->
                    val loggedSets =
                        item
                            .optJSONArray("sets")
                            ?.mapObjects { set ->
                                LoggedSet(
                                    id = set.getString("id"),
                                    type = SetType.valueOf(set.getString("type")),
                                    weightKg = set.optDouble("weightKg", 0.0),
                                    reps = set.optInt("reps"),
                                    holdSeconds = set.optInt("holdSeconds"),
                                    side = Side.valueOf(set.optString("side", Side.BOTH.name)),
                                    completedAt = set.optLong("completedAt"),
                                    plannedSetId =
                                        set.optString("plannedSetId").takeIf {
                                            !set.isNull("plannedSetId")
                                        },
                                )
                            }
                            ?.toMutableList() ?: mutableListOf()
                    val plannedSets =
                        item.optJSONArray("plannedSets")?.let(::decodePlannedSets)
                            ?: MutableList(item.optInt("targetSets", 0)) { index ->
                                loggedSets.getOrNull(index)?.toPlannedSet()
                                    ?: PlannedSet(
                                        reps = item.optInt("targetReps", 8),
                                        holdSeconds = item.optInt("targetHoldSeconds", 30),
                                    )
                            }
                    // Keep existing links, including sets logged out of order. Only unlinked
                    // old sets use their former position; don't steal an already linked row.
                    val claimedIds = loggedSets.mapNotNullTo(mutableSetOf()) { it.plannedSetId }
                    loggedSets.forEachIndexed { index, set ->
                        val linked = plannedSets.firstOrNull { it.id == set.plannedSetId }
                        val positional =
                            if (set.plannedSetId == null) {
                                plannedSets.getOrNull(index)?.takeIf { it.id !in claimedIds }
                            } else null
                        val plannedSet =
                            linked ?: positional ?: set.toPlannedSet().also(plannedSets::add)
                        set.plannedSetId = plannedSet.id
                        claimedIds.add(plannedSet.id)
                        if (plannedSet.actualHoldSeconds == null && set.holdSeconds > 0) {
                            plannedSet.actualHoldSeconds = set.holdSeconds
                        }
                    }
                    WorkoutExercise(
                        id = item.getString("id"),
                        exerciseId = item.getString("exerciseId"),
                        restSecondsOverride =
                            item.optInt("restSecondsOverride").takeIf {
                                !item.isNull("restSecondsOverride")
                            },
                        sets = loggedSets,
                        plannedSets = plannedSets,
                    )
                }
                .toMutableList(),
        )

    private fun LoggedSet.toPlannedSet() =
        PlannedSet(
            id = plannedSetId ?: UUID.randomUUID().toString(),
            type = type,
            weightKg = weightKg,
            reps = reps,
            holdSeconds = holdSeconds,
            side = side,
        )

    private fun decodePlannedSets(value: JSONArray): MutableList<PlannedSet> =
        value
            .mapObjects { set ->
                PlannedSet(
                    id = set.optString("id", UUID.randomUUID().toString()),
                    type = SetType.valueOf(set.optString("type", SetType.NORMAL.name)),
                    weightKg = set.optDouble("weightKg", 0.0),
                    reps = set.optInt("reps", 8),
                    holdSeconds = set.optInt("holdSeconds", 30),
                    actualHoldSeconds =
                        set.optInt("actualHoldSeconds").takeIf { !set.isNull("actualHoldSeconds") },
                    side = Side.valueOf(set.optString("side", Side.BOTH.name)),
                )
            }
            .toMutableList()

    private fun decodeExerciseType(value: String): ExerciseType =
        when (value) {
            "WEIGHTED_REPS" -> ExerciseType.REPETITIONS
            "WEIGHTED_HOLD",
            "ISOMETRIC",
            "STRETCH" -> ExerciseType.TIMED
            else -> ExerciseType.valueOf(value)
        }
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }
