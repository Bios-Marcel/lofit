package lofit

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class LofitRepository(context: Context) {
    private companion object {
        const val SCHEMA_VERSION = 2
    }

    private val stateFile = AtomicFile(File(context.filesDir, "lofit-data.json"))
    private val preferences by lazy { context.getSharedPreferences("lofit-data", Context.MODE_PRIVATE) }
    var data: AppData = load()
        private set

    fun save() {
        write(encode(data).toString())
    }

    fun reset() {
        val fresh = AppData()
        write(encode(fresh).toString())
        data = fresh
    }

    private fun load(): AppData {
        val hasJson = exists(stateFile)
        val raw = if (hasJson) stateFile.readFully().toString(Charsets.UTF_8)
            else preferences.getString("state", null) ?: return AppData()
        val root = JSONObject(raw)
        return when (val version = if (root.has("schemaVersion")) root.getInt("schemaVersion") else 0) {
            0 -> persistMigration(raw, StartupDataMigration.migrate(root), "state-before-migration-v1")
            1 -> persistMigration(raw, decode(root), "state-before-migration-v2")
            SCHEMA_VERSION -> decode(root).also {
                // Copy only after validation. Keep the original XML and its backups untouched.
                if (!hasJson) write(raw)
            }
            else -> error("Unsupported data schema: $version")
        }
    }

    private fun persistMigration(raw: String, data: AppData, backupKey: String): AppData {
        val migrated = encode(data)
        // Validate before changing stored data, and keep the original for recovery.
        val loaded = decode(migrated)
        val backup = AtomicFile(File(stateFile.baseFile.parentFile, "$backupKey.json"))
        if (!exists(backup)) write(raw, backup)
        write(migrated.toString())
        return loaded
    }

    private fun exists(file: AtomicFile): Boolean =
        file.baseFile.exists() || File("${file.baseFile.path}.bak").exists()

    private fun write(raw: String, file: AtomicFile = stateFile) {
        val stream = file.startWrite()
        try {
            stream.write(raw.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Throwable) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun encode(data: AppData) = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("exercises", JSONArray().apply { data.exercises.forEach { put(encodeExercise(it)) } })
        put("routines", JSONArray().apply { data.routines.forEach { put(encodeRoutine(it)) } })
        put("history", JSONArray().apply { data.history.forEach { put(encodeWorkout(it)) } })
        put("exerciseRecords", JSONObject().apply {
            data.exerciseRecords.forEach { (exerciseId, records) ->
                put(exerciseId, JSONArray().apply {
                    records.forEach { record ->
                        put(JSONObject().apply {
                            put("set", encodeLoggedSet(record.set))
                            put("volumeSet", record.volumeSet?.let(::encodeLoggedSet) ?: JSONObject.NULL)
                        })
                    }
                })
            }
        })
        put("activeWorkout", data.activeWorkout?.let(::encodeWorkout) ?: JSONObject.NULL)
        put("soundCuesEnabled", data.soundCuesEnabled)
        put("bodyMetricsEnabled", data.bodyMetricsEnabled ?: JSONObject.NULL)
        put("biologicalGender", data.biologicalGender.name)
        put("onboardingComplete", data.onboardingComplete)
        put("exerciseLastSelectedAt", JSONObject(data.exerciseLastSelectedAt))
    }

    private fun encodeExercise(exercise: Exercise) = JSONObject().apply {
        put("id", exercise.id)
        put("name", exercise.name)
        put("type", exercise.type.name)
        put("asymmetric", exercise.asymmetric)
        put("primaryMuscle", exercise.primaryMuscle)
        put("secondaryMuscles", exercise.secondaryMuscles)
        put("minimumHoldSeconds", exercise.minimumHoldSeconds)
        put("weighted", exercise.weighted)
        put("custom", exercise.custom)
        put("imageResource", exercise.imageResource ?: JSONObject.NULL)
        put("imageName", exercise.imageName ?: JSONObject.NULL)
        put("imageFile", exercise.imageFile ?: JSONObject.NULL)
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
                    put("restSecondsOverride", item.restSecondsOverride ?: JSONObject.NULL)
                    put("note", item.note)
                    put("plannedSets", encodePlannedSets(item.plannedSets))
                })
            }
        })
    }

    private fun encodeWorkout(workout: WorkoutSession) = JSONObject().apply {
        put("id", workout.id)
        put("name", workout.name)
        put("bodyWeightKg", workout.bodyWeightKg?.let(::roundBodyWeight) ?: JSONObject.NULL)
        put("sourceRoutine", workout.sourceRoutine?.let(::encodeRoutine) ?: JSONObject.NULL)
        put("intervals", JSONArray().apply {
            workout.intervals.forEach { interval ->
                put(JSONObject().apply {
                    put("startedAt", interval.startedAt)
                    put("endedAt", interval.endedAt ?: JSONObject.NULL)
                })
            }
        })
        put("defaultRestSeconds", workout.defaultRestSeconds)
        put("exercises", JSONArray().apply {
            workout.exercises.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("exerciseId", item.exerciseId)
                    put("restSecondsOverride", item.restSecondsOverride ?: JSONObject.NULL)
                    put("sourceRoutineExerciseId", item.sourceRoutineExerciseId ?: JSONObject.NULL)
                    put("note", item.note)
                    put("previousNote", item.previousNote)
                    put("plannedSets", encodePlannedSets(item.plannedSets))
                    put("sets", JSONArray().apply {
                        item.sets.forEach { set ->
                            put(encodeLoggedSet(set))
                        }
                    })
                })
            }
        })
    }

    private fun encodeLoggedSet(set: LoggedSet) = JSONObject().apply {
        put("id", set.id)
        put("type", set.type.name)
        put("weightKg", set.weightKg)
        put("reps", set.reps)
        put("holdSeconds", set.holdSeconds)
        put("side", set.side.name)
        put("completedAt", set.completedAt)
        put("plannedSetId", set.plannedSetId ?: JSONObject.NULL)
    }

    private fun decode(root: JSONObject): AppData {
        val savedExercises = root.getJSONArray("exercises").mapObjects(::decodeExercise)
        val defaults = DefaultData.exercises()
        val defaultIds = defaults.mapTo(mutableSetOf()) { it.id }
        val exercises = (defaults + savedExercises.filter { it.id !in defaultIds }).toMutableList()
        return AppData(
            exercises = exercises,
            routines = root.getJSONArray("routines").mapObjects(::decodeRoutine).toMutableList(),
            history = root.getJSONArray("history").mapObjects(::decodeWorkout).toMutableList(),
            activeWorkout = if (root.isNull("activeWorkout")) null else decodeWorkout(root.getJSONObject("activeWorkout")),
            soundCuesEnabled = root.getBoolean("soundCuesEnabled"),
            bodyMetricsEnabled = if (root.isNull("bodyMetricsEnabled")) null else root.getBoolean("bodyMetricsEnabled"),
            biologicalGender = BiologicalGender.valueOf(root.optString("biologicalGender", BiologicalGender.MALE.name)),
            onboardingComplete = root.optBoolean("onboardingComplete", false),
            exerciseRecords = decodeRecords(root.optJSONObject("exerciseRecords"), exercises),
            exerciseLastSelectedAt = mutableMapOf<String, Long>().apply {
                root.optJSONObject("exerciseLastSelectedAt")?.let { timestamps ->
                    timestamps.keys().forEach { id ->
                        if (exercises.any { it.id == id }) put(id, timestamps.getLong(id))
                    }
                }
            },
        )
    }

    private fun decodeRecords(
        value: JSONObject?, exercises: List<Exercise>,
    ): MutableMap<String, MutableList<ExerciseRecord>> {
        val records = mutableMapOf<String, MutableList<ExerciseRecord>>()
        if (value == null) return records
        val exercisesById = exercises.associateBy { it.id }
        value.keys().forEach { exerciseId ->
            val exercise = exercisesById[exerciseId] ?: return@forEach
            val entries = value.getJSONArray(exerciseId).mapObjects { entry ->
                ExerciseRecord(
                    exercise = exercise,
                    set = decodeLoggedSet(entry.getJSONObject("set")),
                    volumeSet = if (entry.isNull("volumeSet")) null else decodeLoggedSet(entry.getJSONObject("volumeSet")),
                )
            }
            if (entries.isNotEmpty()) records[exerciseId] = entries.toMutableList()
        }
        return records
    }

    private fun decodeExercise(value: JSONObject) = Exercise(
        id = value.getString("id"),
        name = value.getString("name"),
        type = ExerciseType.valueOf(value.getString("type")),
        asymmetric = value.getBoolean("asymmetric"),
        primaryMuscle = value.getString("primaryMuscle"),
        secondaryMuscles = value.getString("secondaryMuscles"),
        minimumHoldSeconds = value.getInt("minimumHoldSeconds"),
        custom = value.getBoolean("custom"),
        imageResource = if (value.isNull("imageResource")) null else value.getInt("imageResource"),
        imageName = if (value.isNull("imageName")) null else value.getString("imageName"),
        weighted = value.getBoolean("weighted"),
        imageFile = if (value.isNull("imageFile")) null else value.getString("imageFile"),
    )

    private fun decodeRoutine(value: JSONObject) = Routine(
        id = value.getString("id"),
        name = value.getString("name"),
        defaultRestSeconds = value.getInt("defaultRestSeconds"),
        exercises = value.getJSONArray("exercises").mapObjects { item ->
            RoutineExercise(
                id = item.getString("id"),
                note = item.optString("note", ""),
                exerciseId = item.getString("exerciseId"),
                restSecondsOverride = if (item.isNull("restSecondsOverride")) null else item.getInt("restSecondsOverride"),
                plannedSets = decodePlannedSets(item.getJSONArray("plannedSets")),
            )
        }.toMutableList(),
    )

    private fun decodeWorkout(value: JSONObject) = WorkoutSession(
        id = value.getString("id"),
        name = value.getString("name"),
        bodyWeightKg = if (value.isNull("bodyWeightKg")) null else roundBodyWeight(value.getDouble("bodyWeightKg")),
        sourceRoutine = if (value.isNull("sourceRoutine")) null else decodeRoutine(value.getJSONObject("sourceRoutine")),
        intervals = value.workoutIntervals(),
        defaultRestSeconds = value.getInt("defaultRestSeconds"),
        exercises = value.getJSONArray("exercises").mapObjects { item ->
            WorkoutExercise(
                id = item.getString("id"),
                note = item.optString("note", ""),
                previousNote = item.optString("previousNote", ""),
                exerciseId = item.getString("exerciseId"),
                sourceRoutineExerciseId = if (item.isNull("sourceRoutineExerciseId")) null else item.getString("sourceRoutineExerciseId"),
                restSecondsOverride = if (item.isNull("restSecondsOverride")) null else item.getInt("restSecondsOverride"),
                plannedSets = decodePlannedSets(item.getJSONArray("plannedSets")),
                sets = item.getJSONArray("sets").mapObjects(::decodeLoggedSet).toMutableList(),
            )
        }.toMutableList(),
    )

    private fun decodeLoggedSet(set: JSONObject) = LoggedSet(
        id = set.getString("id"),
        type = SetType.valueOf(set.getString("type")),
        weightKg = set.getDouble("weightKg"),
        reps = set.getInt("reps"),
        holdSeconds = set.getInt("holdSeconds"),
        side = Side.valueOf(set.getString("side")),
        completedAt = set.getLong("completedAt"),
        plannedSetId = if (set.isNull("plannedSetId")) null else set.getString("plannedSetId"),
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

    private fun decodePlannedSets(value: JSONArray): MutableList<PlannedSet> =
        value.mapObjects { set ->
            PlannedSet(
                id = set.getString("id"),
                type = SetType.valueOf(set.getString("type")),
                weightKg = set.getDouble("weightKg"),
                reps = set.getInt("reps"),
                holdSeconds = set.getInt("holdSeconds"),
                actualHoldSeconds = if (set.isNull("actualHoldSeconds")) null else set.getInt("actualHoldSeconds"),
                side = Side.valueOf(set.getString("side")),
            )
        }.toMutableList()
}

internal fun JSONObject.workoutIntervals(): MutableList<WorkoutInterval> =
    if (has("intervals")) {
        getJSONArray("intervals").mapObjects { interval ->
            WorkoutInterval(
                startedAt = interval.getLong("startedAt"),
                endedAt = if (interval.isNull("endedAt")) null else interval.getLong("endedAt"),
            )
        }.toMutableList()
    } else {
        mutableListOf(WorkoutInterval(
            startedAt = getLong("startedAt"),
            endedAt = if (isNull("completedAt")) null else getLong("completedAt"),
        ))
    }

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }

