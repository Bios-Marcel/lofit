package lofit

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

class LofitRepository(context: Context) {
    private companion object {
        const val SCHEMA_VERSION = 4
    }

    private val stateFile = AtomicFile(File(context.filesDir, "lofit-data.json"))
    private val historyFile = AtomicFile(File(context.filesDir, "lofit-history.json"))
    private val preferences by lazy {
        context.getSharedPreferences("lofit-data", Context.MODE_PRIVATE)
    }
    var data: AppData = load()
        private set

    // Save exercises, routines, the active workout, and settings only. Call saveHistory()
    // separately whenever history changes so frequent saves do not encode or write it.
    fun save() {
        write(encode(data).toString())
    }

    fun saveHistory() {
        write(encodeHistory(data.history).toString(), historyFile)
    }

    fun reset() {
        val fresh = AppData()
        val history = encodeHistory(fresh.history).toString()
        write(encode(fresh).toString())
        write(history, historyFile)
        data = fresh
    }

    private fun load(): AppData {
        val hasJson = exists(stateFile)
        val raw =
            if (hasJson) stateFile.readFully().toString(Charsets.UTF_8)
            else
                preferences.getString("state", null)
                    ?: return AppData().also {
                        write("[]", historyFile)
                    }
        val root = JSONObject(raw)
        return when (val version = root.optInt("schemaVersion", 0)) {
            // LEGACY: Schema 2 embeds history in the state file; move it to its own file.
            2 -> {
                migrateLegacyExerciseIds(root, root.getJSONArray("history"))
                persistMigration(raw, decode(root), "state-before-migration-v3")
            }
            // LEGACY: Schema 3 uses the old exercise IDs in state and separate history.
            3 -> {
                val historyRaw = historyFile.readFully().toString(Charsets.UTF_8)
                val history = JSONArray(historyRaw)
                migrateLegacyExerciseIds(root, history)
                persistMigration(
                    raw,
                    decode(root, history.mapObjects(::decodeWorkout).toMutableList()),
                    "state-before-migration-v4",
                    historyRaw,
                )
            }
            SCHEMA_VERSION -> {
                val history = historyFile.readFully().toString(Charsets.UTF_8)
                decode(root, JSONArray(history).mapObjects(::decodeWorkout).toMutableList()).also {
                    // Copy only after validation. Keep the original XML and its backups untouched.
                    if (!hasJson) write(raw)
                }
            }
            else -> error("Unsupported data schema: $version")
        }
    }

    private fun persistMigration(
        raw: String,
        data: AppData,
        backupKey: String,
        historyBackupRaw: String? = null,
    ): AppData {
        val migrated = encode(data)
        val history = encodeHistory(data.history).toString()
        // Validate before changing stored data, and keep the original for recovery.
        val loaded =
            decode(migrated, JSONArray(history).mapObjects(::decodeWorkout).toMutableList())
        val backup = AtomicFile(File(stateFile.baseFile.parentFile, "$backupKey.json"))
        if (!exists(backup)) write(raw, backup)
        if (historyBackupRaw != null) {
            val historyBackup =
                AtomicFile(File(stateFile.baseFile.parentFile, "history-before-migration-v4.json"))
            if (!exists(historyBackup)) write(historyBackupRaw, historyBackup)
        }
        write(history, historyFile)
        write(migrated.toString())
        return loaded
    }

    private fun migrateLegacyExerciseIds(root: JSONObject, history: JSONArray) {
        val defaultIds = DefaultData.exercises().mapTo(mutableSetOf()) { it.id }

        // LEGACY: Previous built-in IDs used this prefix, including weighted copies.
        fun candidateId(id: String): String =
            when {
                id.startsWith("weighted-fedb-") -> "weighted-" + id.removePrefix("weighted-fedb-")
                id.startsWith("fedb-") -> id.removePrefix("fedb-")
                else -> id
            }

        val exercises = root.getJSONArray("exercises")
        val legacyDefaultIds =
            (0 until exercises.length())
                .map { exercises.getJSONObject(it) }
                .filter { !it.getBoolean("custom") }
                .map { it.getString("id") }
                .filter { candidateId(it) != it && candidateId(it) in defaultIds }
                .toSet()
        fun currentId(id: String): String = if (id in legacyDefaultIds) candidateId(id) else id

        fun migrateRoutine(routine: JSONObject) {
            val exercises = routine.getJSONArray("exercises")
            for (index in 0 until exercises.length()) {
                val item = exercises.getJSONObject(index)
                item.put("exerciseId", currentId(item.getString("exerciseId")))
            }
        }

        fun migrateWorkout(workout: JSONObject) {
            if (!workout.isNull("sourceRoutine"))
                migrateRoutine(workout.getJSONObject("sourceRoutine"))
            val exercises = workout.getJSONArray("exercises")
            for (index in 0 until exercises.length()) {
                val item = exercises.getJSONObject(index)
                item.put("exerciseId", currentId(item.getString("exerciseId")))
            }
        }

        for (index in 0 until exercises.length()) {
            val item = exercises.getJSONObject(index)
            if (!item.getBoolean("custom")) item.put("id", currentId(item.getString("id")))
        }
        val routines = root.getJSONArray("routines")
        for (index in 0 until routines.length()) migrateRoutine(routines.getJSONObject(index))
        if (!root.isNull("activeWorkout")) migrateWorkout(root.getJSONObject("activeWorkout"))
        for (index in 0 until history.length()) migrateWorkout(history.getJSONObject(index))

        root.optJSONObject("exerciseRecords")?.let { records ->
            records.keys().asSequence().toList().forEach { oldId ->
                val newId = currentId(oldId)
                if (newId != oldId) {
                    val oldEntries = records.getJSONArray(oldId)
                    val newEntries = records.optJSONArray(newId)
                    if (newEntries == null) records.put(newId, oldEntries)
                    else
                        for (index in 0 until oldEntries.length()) newEntries.put(
                            oldEntries.get(index)
                        )
                    records.remove(oldId)
                }
            }
        }
        root.optJSONObject("exerciseLastSelectedAt")?.let { timestamps ->
            timestamps.keys().asSequence().toList().forEach { oldId ->
                val newId = currentId(oldId)
                if (newId != oldId) {
                    val timestamp = timestamps.getLong(oldId)
                    timestamps.put(
                        newId,
                        if (timestamps.has(newId)) maxOf(timestamp, timestamps.getLong(newId))
                        else timestamp,
                    )
                    timestamps.remove(oldId)
                }
            }
        }
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

    private fun encode(data: AppData) =
        JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put(
                "exercises",
                JSONArray().apply { data.exercises.forEach { put(encodeExercise(it)) } },
            )
            put("routines", JSONArray().apply { data.routines.forEach { put(encodeRoutine(it)) } })
            put(
                "exerciseRecords",
                JSONObject().apply {
                    data.exerciseRecords.forEach { (exerciseId, records) ->
                        put(
                            exerciseId,
                            JSONArray().apply {
                                records.forEach { record ->
                                    put(
                                        JSONObject().apply {
                                            put("set", encodeLoggedSet(record.set))
                                            put(
                                                "volumeSet",
                                                record.volumeSet?.let(::encodeLoggedSet)
                                                    ?: JSONObject.NULL,
                                            )
                                        }
                                    )
                                }
                            },
                        )
                    }
                },
            )
            put("activeWorkout", data.activeWorkout?.let(::encodeWorkout) ?: JSONObject.NULL)
            put("soundCuesEnabled", data.soundCuesEnabled)
            put("mcpEnabled", data.mcpEnabled)
            put("mcpPort", data.mcpPort)
            put("bodyMetricsEnabled", data.bodyMetricsEnabled ?: JSONObject.NULL)
            put("biologicalGender", data.biologicalGender.name)
            put("onboardingComplete", data.onboardingComplete)
            put("exerciseLastSelectedAt", JSONObject(data.exerciseLastSelectedAt))
        }

    private fun encodeHistory(history: List<WorkoutSession>) =
        JSONArray().apply {
            history.forEach { put(encodeWorkout(it)) }
        }

    internal fun encodeExercise(exercise: Exercise) =
        JSONObject().apply {
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

    internal fun encodeRoutine(routine: Routine) =
        JSONObject().apply {
            put("id", routine.id)
            put("name", routine.name)
            put("defaultRestSeconds", routine.defaultRestSeconds)
            put(
                "exercises",
                JSONArray().apply {
                    routine.exercises.forEach { item ->
                        put(
                            JSONObject().apply {
                                put("id", item.id)
                                put("exerciseId", item.exerciseId)
                                put(
                                    "restSecondsOverride",
                                    item.restSecondsOverride ?: JSONObject.NULL,
                                )
                                put("note", item.note)
                                put("plannedSets", encodePlannedSets(item.plannedSets))
                            }
                        )
                    }
                },
            )
        }

    internal fun encodeWorkout(workout: WorkoutSession) =
        JSONObject().apply {
            put("id", workout.id)
            put("name", workout.name)
            put("bodyWeightKg", workout.bodyWeightKg?.let(::roundBodyWeight) ?: JSONObject.NULL)
            put("sourceRoutine", workout.sourceRoutine?.let(::encodeRoutine) ?: JSONObject.NULL)
            put(
                "intervals",
                JSONArray().apply {
                    workout.intervals.forEach { interval ->
                        put(
                            JSONObject().apply {
                                put("startedAt", interval.startedAt)
                                put("endedAt", interval.endedAt ?: JSONObject.NULL)
                            }
                        )
                    }
                },
            )
            put("defaultRestSeconds", workout.defaultRestSeconds)
            put(
                "exercises",
                JSONArray().apply {
                    workout.exercises.forEach { item ->
                        put(
                            JSONObject().apply {
                                put("id", item.id)
                                put("exerciseId", item.exerciseId)
                                put(
                                    "restSecondsOverride",
                                    item.restSecondsOverride ?: JSONObject.NULL,
                                )
                                put(
                                    "sourceRoutineExerciseId",
                                    item.sourceRoutineExerciseId ?: JSONObject.NULL,
                                )
                                put("note", item.note)
                                put("previousNote", item.previousNote)
                                put("plannedSets", encodePlannedSets(item.plannedSets))
                                put(
                                    "sets",
                                    JSONArray().apply {
                                        item.sets.forEach { set ->
                                            put(encodeLoggedSet(set))
                                        }
                                    },
                                )
                            }
                        )
                    }
                },
            )
        }

    private fun encodeLoggedSet(set: LoggedSet) =
        JSONObject().apply {
            put("id", set.id)
            put("type", set.type.name)
            put("weightKg", set.weightKg)
            put("reps", set.reps)
            put("holdSeconds", set.holdSeconds)
            put("side", set.side.name)
            put("completedAt", set.completedAt)
            put("plannedSetId", set.plannedSetId ?: JSONObject.NULL)
        }

    private fun decode(
        root: JSONObject,
        history: MutableList<WorkoutSession> =
            root.getJSONArray("history").mapObjects(::decodeWorkout).toMutableList(),
    ): AppData {
        val savedExercises = root.getJSONArray("exercises").mapObjects(::decodeExercise)
        val defaults = DefaultData.exercises()
        val defaultIds = defaults.mapTo(mutableSetOf()) { it.id }
        val exercises = (defaults + savedExercises.filter { it.id !in defaultIds }).toMutableList()
        return AppData(
            exercises = exercises,
            routines = root.getJSONArray("routines").mapObjects(::decodeRoutine).toMutableList(),
            history = history,
            activeWorkout =
                if (root.isNull("activeWorkout")) null
                else decodeWorkout(root.getJSONObject("activeWorkout")),
            soundCuesEnabled = root.getBoolean("soundCuesEnabled"),
            mcpEnabled = root.optBoolean("mcpEnabled", false),
            mcpPort =
                root.optInt("mcpPort", BuildConfig.MCP_PORT).takeIf { it in 1..65535 }
                    ?: BuildConfig.MCP_PORT,
            bodyMetricsEnabled =
                if (root.isNull("bodyMetricsEnabled")) null
                else root.getBoolean("bodyMetricsEnabled"),
            biologicalGender =
                BiologicalGender.valueOf(
                    root.optString("biologicalGender", BiologicalGender.MALE.name)
                ),
            onboardingComplete = root.optBoolean("onboardingComplete", false),
            exerciseRecords = decodeRecords(root.optJSONObject("exerciseRecords"), exercises),
            exerciseLastSelectedAt =
                mutableMapOf<String, Long>().apply {
                    root.optJSONObject("exerciseLastSelectedAt")?.let { timestamps ->
                        timestamps.keys().forEach { id ->
                            if (exercises.any { it.id == id }) put(id, timestamps.getLong(id))
                        }
                    }
                },
        )
    }

    private fun decodeRecords(
        value: JSONObject?,
        exercises: List<Exercise>,
    ): MutableMap<String, MutableList<ExerciseRecord>> {
        val records = mutableMapOf<String, MutableList<ExerciseRecord>>()
        if (value == null) return records
        val exercisesById = exercises.associateBy { it.id }
        value.keys().forEach { exerciseId ->
            val exercise = exercisesById[exerciseId] ?: return@forEach
            val entries =
                value.getJSONArray(exerciseId).mapObjects { entry ->
                    ExerciseRecord(
                        exercise = exercise,
                        set = decodeLoggedSet(entry.getJSONObject("set")),
                        volumeSet =
                            if (entry.isNull("volumeSet")) null
                            else decodeLoggedSet(entry.getJSONObject("volumeSet")),
                    )
                }
            if (entries.isNotEmpty()) records[exerciseId] = entries.toMutableList()
        }
        return records
    }

    private fun decodeExercise(value: JSONObject) =
        Exercise(
            id = value.getString("id"),
            name = value.getString("name"),
            type = ExerciseType.valueOf(value.getString("type")),
            asymmetric = value.getBoolean("asymmetric"),
            primaryMuscle = value.getString("primaryMuscle"),
            secondaryMuscles = value.getString("secondaryMuscles"),
            minimumHoldSeconds = value.getInt("minimumHoldSeconds"),
            custom = value.getBoolean("custom"),
            imageResource =
                if (value.isNull("imageResource")) null else value.getInt("imageResource"),
            imageName = if (value.isNull("imageName")) null else value.getString("imageName"),
            weighted = value.getBoolean("weighted"),
            imageFile = if (value.isNull("imageFile")) null else value.getString("imageFile"),
        )

    private fun decodeRoutine(value: JSONObject) =
        Routine(
            id = value.getString("id"),
            name = value.getString("name"),
            defaultRestSeconds = value.getInt("defaultRestSeconds"),
            exercises =
                value
                    .getJSONArray("exercises")
                    .mapObjects { item ->
                        RoutineExercise(
                            id = item.getString("id"),
                            note = item.optString("note", ""),
                            exerciseId = item.getString("exerciseId"),
                            restSecondsOverride =
                                if (item.isNull("restSecondsOverride")) null
                                else item.getInt("restSecondsOverride"),
                            plannedSets = decodePlannedSets(item.getJSONArray("plannedSets")),
                        )
                    }
                    .toMutableList(),
        )

    private fun decodeWorkout(value: JSONObject) =
        WorkoutSession(
            id = value.getString("id"),
            name = value.getString("name"),
            bodyWeightKg =
                if (value.isNull("bodyWeightKg")) null
                else roundBodyWeight(value.getDouble("bodyWeightKg")),
            sourceRoutine =
                if (value.isNull("sourceRoutine")) null
                else decodeRoutine(value.getJSONObject("sourceRoutine")),
            intervals =
                value
                    .getJSONArray("intervals")
                    .mapObjects { interval ->
                        WorkoutInterval(
                            startedAt = interval.getLong("startedAt"),
                            endedAt =
                                if (interval.isNull("endedAt")) null
                                else interval.getLong("endedAt"),
                        )
                    }
                    .toMutableList(),
            defaultRestSeconds = value.getInt("defaultRestSeconds"),
            exercises =
                value
                    .getJSONArray("exercises")
                    .mapObjects { item ->
                        WorkoutExercise(
                            id = item.getString("id"),
                            note = item.optString("note", ""),
                            previousNote = item.optString("previousNote", ""),
                            exerciseId = item.getString("exerciseId"),
                            sourceRoutineExerciseId =
                                if (item.isNull("sourceRoutineExerciseId")) null
                                else item.getString("sourceRoutineExerciseId"),
                            restSecondsOverride =
                                if (item.isNull("restSecondsOverride")) null
                                else item.getInt("restSecondsOverride"),
                            plannedSets = decodePlannedSets(item.getJSONArray("plannedSets")),
                            sets =
                                item
                                    .getJSONArray("sets")
                                    .mapObjects(::decodeLoggedSet)
                                    .toMutableList(),
                        )
                    }
                    .toMutableList(),
        )

    private fun decodeLoggedSet(set: JSONObject) =
        LoggedSet(
            id = set.getString("id"),
            type = SetType.valueOf(set.getString("type")),
            weightKg = set.getDouble("weightKg"),
            reps = set.getInt("reps"),
            holdSeconds = set.getInt("holdSeconds"),
            side = Side.valueOf(set.getString("side")),
            completedAt = set.getLong("completedAt"),
            plannedSetId = if (set.isNull("plannedSetId")) null else set.getString("plannedSetId"),
        )

    private fun encodePlannedSets(sets: List<PlannedSet>) =
        JSONArray().apply {
            sets.forEach { set ->
                put(
                    JSONObject().apply {
                        put("id", set.id)
                        put("type", set.type.name)
                        put("weightKg", set.weightKg)
                        put("reps", set.reps)
                        put("holdSeconds", set.holdSeconds)
                        put("actualHoldSeconds", set.actualHoldSeconds ?: JSONObject.NULL)
                        put("side", set.side.name)
                    }
                )
            }
        }

    private fun decodePlannedSets(value: JSONArray): MutableList<PlannedSet> =
        value
            .mapObjects { set ->
                PlannedSet(
                    id = set.getString("id"),
                    type = SetType.valueOf(set.getString("type")),
                    weightKg = set.getDouble("weightKg"),
                    reps = set.getInt("reps"),
                    holdSeconds = set.getInt("holdSeconds"),
                    actualHoldSeconds =
                        if (set.isNull("actualHoldSeconds")) null
                        else set.getInt("actualHoldSeconds"),
                    side = Side.valueOf(set.getString("side")),
                )
            }
            .toMutableList()
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> =
    (0 until length()).map { transform(getJSONObject(it)) }
