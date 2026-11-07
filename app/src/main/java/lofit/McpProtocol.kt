package lofit

import android.graphics.ImageDecoder
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Invoked on the UI thread, so reads and writes share the UI's repository and ordering. */
internal class McpProtocol(
    private val repository: LofitRepository,
    private val pictures: ExercisePictures,
    private val onDataChanged: () -> Unit = {},
) {
    private companion object {
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_IMAGE_BASE64_LENGTH = 4 * ((MAX_IMAGE_BYTES + 2) / 3)
    }

    fun handle(body: String): JSONObject? {
        val request =
            try {
                val tokens = JSONTokener(body)
                val value = tokens.nextValue()
                if (tokens.nextClean() != '\u0000') throw JSONException("Trailing data")
                value as? JSONObject
                    ?: return error(JSONObject.NULL, -32600, "Expected a JSON-RPC object")
            } catch (_: JSONException) {
                return error(JSONObject.NULL, -32700, "Invalid JSON")
            }
        val id = request.opt("id")
        if (
            request.opt("jsonrpc") != "2.0" ||
                request.opt("method") !is String ||
                (request.has("id") && id !is String && id !is Number)
        ) {
            return error(JSONObject.NULL, -32600, "Invalid JSON-RPC request")
        }
        // Notifications never have a response and never invoke a tool.
        if (!request.has("id")) return null
        return try {
            val params =
                if (request.has("params"))
                    request.opt("params") as? JSONObject
                        ?: throw RpcFailure(-32602, "Expected object params")
                else JSONObject()
            val result =
                when (request.getString("method")) {
                    "initialize" -> {
                        val version =
                            params.opt("protocolVersion") as? String
                                ?: throw RpcFailure(-32602, "protocolVersion required")
                        if (
                            params.opt("capabilities") !is JSONObject ||
                                params.opt("clientInfo") !is JSONObject
                        ) {
                            throw RpcFailure(-32602, "capabilities and clientInfo required")
                        }
                        JSONObject().apply {
                            put(
                                "protocolVersion",
                                version.takeIf { it in McpHttpServer.PROTOCOL_VERSIONS }
                                    ?: McpHttpServer.PROTOCOL_VERSIONS.last(),
                            )
                            put(
                                "capabilities",
                                JSONObject().put("tools", JSONObject().put("listChanged", false)),
                            )
                            put(
                                "serverInfo",
                                JSONObject().put("name", "lofit").put("version", "1.0"),
                            )
                        }
                    }
                    "ping" -> JSONObject()
                    "tools/list" -> JSONObject().put("tools", toolDefinitions())
                    "tools/call" -> callTool(params)
                    else -> throw RpcFailure(-32601, "Method not found")
                }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        } catch (failure: RpcFailure) {
            error(id, failure.code, failure.message.orEmpty())
        } catch (_: Exception) {
            error(id, -32603, "Internal error")
        }
    }

    private fun callTool(params: JSONObject): JSONObject {
        val name = params.opt("name") as? String ?: throw RpcFailure(-32602, "Tool name required")
        if (
            name !in
                listOf(
                    "get_routines",
                    "get_exercises",
                    "get_history",
                    "create_routine",
                    "create_exercise",
                    "edit_exercise",
                )
        ) {
            throw RpcFailure(-32602, "Unknown tool: $name")
        }
        val arguments =
            if (params.has("arguments"))
                params.opt("arguments") as? JSONObject
                    ?: throw RpcFailure(-32602, "Expected object arguments")
            else JSONObject()
        return try {
            val result =
                when (name) {
                    "get_routines" ->
                        page(
                            arguments,
                            "routines",
                            repository.data.routines,
                            repository::encodeRoutine,
                        )
                    "get_exercises" ->
                        page(
                            arguments,
                            "exercises",
                            repository.data.exercises.filter { !it.retired },
                            repository::encodeExercise,
                        )
                    "get_history" ->
                        page(
                            arguments,
                            "history",
                            repository.data.history.sortedByDescending {
                                it.completedAt ?: it.startedAt
                            },
                        ) {
                            repository
                                .encodeWorkout(it)
                                .put("startedAt", it.startedAt)
                                .put("completedAt", it.completedAt ?: JSONObject.NULL)
                        }
                    "create_routine" -> createRoutine(arguments)
                    "create_exercise" -> createExercise(arguments)
                    else -> editExercise(arguments)
                }
            JSONObject()
                .put("content", textContent(result.toString()))
                .put("structuredContent", result)
                .put("isError", false)
        } catch (failure: IllegalArgumentException) {
            JSONObject()
                .put("content", textContent(failure.message ?: "Invalid arguments"))
                .put("isError", true)
        }
    }

    private fun <T> page(
        args: JSONObject,
        key: String,
        items: List<T>,
        encode: (T) -> JSONObject,
    ): JSONObject {
        args.only("offset", "limit")
        val offset = args.integer("offset", 0)
        val limit = args.integer("limit", 100, 1, 200)
        val slice = items.drop(offset).take(limit)
        return JSONObject()
            .put(key, JSONArray().apply { slice.forEach { put(encode(it)) } })
            .put("total", items.size)
            .put("nextOffset", (offset + slice.size).takeIf { it < items.size } ?: JSONObject.NULL)
    }

    private fun createRoutine(args: JSONObject): JSONObject {
        args.only("name", "defaultRestSeconds", "exercises")
        val name = args.string("name", 200).trim()
        require(name.isNotEmpty()) { "name must not be blank" }
        val routine =
            Routine(name = name, defaultRestSeconds = args.integer("defaultRestSeconds", 120))
        val items = args.array("exercises", 1, 200)
        for (index in 0 until items.length()) {
            val item =
                items.opt(index) as? JSONObject
                    ?: throw IllegalArgumentException("exercises must contain objects")
            item.only("exerciseId", "restSecondsOverride", "note", "plannedSets")
            val exerciseId = item.string("exerciseId", 200)
            val exercise = repository.data.exercises.firstOrNull { it.id == exerciseId }
            require(exercise != null) {
                "Unknown exerciseId: $exerciseId. Read get_exercises first."
            }
            val sets =
                if (item.has("plannedSets")) {
                    val values = item.array("plannedSets", 1, 100)
                    (0 until values.length())
                        .map { setIndex ->
                            val set =
                                values.opt(setIndex) as? JSONObject
                                    ?: throw IllegalArgumentException(
                                        "plannedSets must contain objects"
                                    )
                            set.only("type", "weightKg", "reps", "holdSeconds", "side")
                            val weight =
                                if (set.has("weightKg")) {
                                    (set.opt("weightKg") as? Number)?.toDouble()
                                        ?: throw IllegalArgumentException(
                                            "weightKg must be a number"
                                        )
                                } else 0.0
                            require(weight.isFinite() && weight >= 0) {
                                "weightKg must be finite and nonnegative"
                            }
                            PlannedSet(
                                type = set.enum("type", SetType.NORMAL),
                                weightKg = weight,
                                reps = set.integer("reps", 8),
                                holdSeconds =
                                    set.integer("holdSeconds", exercise.minimumHoldSeconds),
                                side = set.enum("side", Side.BOTH),
                            )
                        }
                        .toMutableList()
                } else MutableList(3) { PlannedSet(holdSeconds = exercise.minimumHoldSeconds) }
            routine.exercises.add(
                RoutineExercise(
                    exerciseId = exerciseId,
                    restSecondsOverride =
                        if (!item.has("restSecondsOverride") || item.isNull("restSecondsOverride"))
                            null
                        else item.integer("restSecondsOverride", 0),
                    note = if (item.has("note")) item.string("note", 10_000) else "",
                    plannedSets = sets,
                )
            )
        }
        // Validate the whole routine before touching app data. Roll back failed storage writes.
        repository.data.routines.add(routine)
        try {
            repository.save()
        } catch (failure: Exception) {
            repository.data.routines.remove(routine)
            throw failure
        }
        onDataChanged()
        return JSONObject().put("routine", repository.encodeRoutine(routine))
    }

    private fun exerciseFields(args: JSONObject, existing: Exercise? = null): Exercise {
        val name = if (args.has("name")) args.string("name", 200).trim() else existing?.name
        require(!name.isNullOrBlank()) { "name must not be blank" }
        val cardio = args.boolean("cardio", existing?.cardio ?: false)
        val primary =
            if (args.has("primaryMuscle"))
                MuscleGroups.canonicalPrimary(args.string("primaryMuscle", 200), cardio)
            else existing?.primaryMuscle ?: MuscleGroups.canonicalPrimary("", cardio)
        require(primary != null && (cardio || primary.isNotBlank())) {
            "primaryMuscle is required unless cardio is true; when provided, use one of: ${MuscleGroups.all.joinToString()}"
        }
        val secondary =
            if (args.has("secondaryMuscles"))
                MuscleGroups.canonicalList(args.string("secondaryMuscles", 2000))
            else existing?.secondaryMuscles ?: ""
        require(secondary != null) {
            "secondaryMuscles must be a comma-separated list of muscle groups: ${MuscleGroups.all.joinToString()}"
        }
        val type = args.enum("type", existing?.type ?: ExerciseType.REPETITIONS)
        return Exercise(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            type = type,
            asymmetric = args.boolean("asymmetric", existing?.asymmetric ?: false),
            primaryMuscle = primary,
            secondaryMuscles = secondary,
            minimumHoldSeconds =
                args.integer("minimumHoldSeconds", existing?.minimumHoldSeconds ?: 30),
            custom = true,
            imageResource = existing?.imageResource,
            imageName = existing?.imageName,
            imageFile = existing?.imageFile,
            weighted =
                args.boolean("weighted", existing?.weighted ?: (type == ExerciseType.REPETITIONS)),
            liveMeasurement = args.boolean("liveMeasurement", existing?.liveMeasurement ?: false),
            cardio = cardio,
        )
    }

    private fun createExercise(args: JSONObject): JSONObject {
        args.only(
            "name",
            "type",
            "asymmetric",
            "primaryMuscle",
            "secondaryMuscles",
            "minimumHoldSeconds",
            "weighted",
            "liveMeasurement",
            "cardio",
        )
        val exercise = exerciseFields(args)
        repository.data.exercises.add(exercise)
        try {
            repository.save()
        } catch (failure: Exception) {
            repository.data.exercises.remove(exercise)
            throw failure
        }
        onDataChanged()
        return JSONObject().put("exercise", repository.encodeExercise(exercise))
    }

    private fun editExercise(args: JSONObject): JSONObject {
        args.only(
            "exerciseId",
            "name",
            "type",
            "asymmetric",
            "primaryMuscle",
            "secondaryMuscles",
            "minimumHoldSeconds",
            "weighted",
            "liveMeasurement",
            "cardio",
            "imageBase64",
        )
        val id = args.string("exerciseId", 200)
        val index = repository.data.exercises.indexOfFirst { it.id == id }
        require(index >= 0) { "Unknown exerciseId: $id. Read get_exercises first." }
        val existing = repository.data.exercises[index]
        require(existing.custom) {
            "Only custom exercises can be edited. Create a custom exercise instead."
        }
        val exercise = exerciseFields(args, existing)
        if (args.has("imageBase64")) {
            exercise.imageFile =
                if (args.isNull("imageBase64")) null
                else {
                    val bytes =
                        Base64.getDecoder()
                            .decode(args.string("imageBase64", MAX_IMAGE_BASE64_LENGTH))
                    require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES) {
                        "imageBase64 must encode an image of 1 byte to 5 MiB"
                    }
                    try {
                        pictures.importPicture(bytes)
                    } catch (_: ImageDecoder.DecodeException) {
                        throw IllegalArgumentException(
                            "imageBase64 must encode a valid image, such as PNG, JPEG or WebP"
                        )
                    }
                }
        }
        repository.data.exercises[index] = exercise
        try {
            repository.save()
        } catch (failure: Exception) {
            repository.data.exercises[index] = existing
            exercise.imageFile?.takeIf { it != existing.imageFile }?.let(pictures::delete)
            throw failure
        }
        existing.imageFile
            ?.takeIf { old ->
                old != exercise.imageFile && repository.data.exercises.none { it.imageFile == old }
            }
            ?.let(pictures::delete)
        onDataChanged()
        return JSONObject().put("exercise", repository.encodeExercise(exercise))
    }

    private fun exerciseSchema(edit: Boolean): JSONObject {
        val properties =
            JSONObject(
                """{
                "name":{"type":"string","minLength":1,"maxLength":200},
                "type":{"type":"string","enum":["REPETITIONS","TIMED"]},
                "asymmetric":{"type":"boolean"},
                "primaryMuscle":{"type":"string","maxLength":200},
                "secondaryMuscles":{"type":"string","maxLength":2000,"description":"Comma-separated muscle groups; empty string clears the list."},
                "minimumHoldSeconds":{"type":"integer","minimum":0,"maximum":2147483647},
                "weighted":{"type":"boolean"},
                "cardio":{"type":"boolean","description":"Metadata only; does not change exercise tracking. Defaults to false."},
                "liveMeasurement":{"type":"boolean","description":"Requires type TIMED and weighted true."}
            }"""
            )
        properties
            .getJSONObject("primaryMuscle")
            .put(
                "description",
                "Required unless cardio is true; cardio exercises may omit it or use an empty string. Omitted fields on edits keep their current values. Muscle group: ${MuscleGroups.all.joinToString()}. Case-insensitive; front delts and rear delts are accepted aliases.",
            )
        if (edit) {
            properties.put(
                "exerciseId",
                JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200),
            )
            properties.put(
                "imageBase64",
                JSONObject()
                    .put("type", JSONArray(listOf("string", "null")))
                    .put("minLength", 1)
                    .put("maxLength", MAX_IMAGE_BASE64_LENGTH)
                    .put(
                        "description",
                        "Attach or replace a picture using standard base64 image bytes (PNG, JPEG, WebP or another Android-supported format), at most 5 MiB decoded. No data URL prefix or whitespace. Omit to keep the current picture; null removes it. Stored as WebP, at most 1024 pixels on the longest edge. Returns imageFile, not the image bytes.",
                    ),
            )
        }
        val schema =
            JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put(
                    "required",
                    JSONArray(if (edit) listOf("exerciseId") else listOf("name")),
                )
                .put("properties", properties)
        if (!edit)
            schema.put(
                "anyOf",
                JSONArray(
                    """[
            {"required":["cardio"],"properties":{"cardio":{"const":true}}},
            {"required":["primaryMuscle"],"properties":{"primaryMuscle":{"minLength":1}}}
        ]"""
                ),
            )
        return schema
    }

    private fun toolDefinitions(): JSONArray {
        val pagingSchema =
            JSONObject(
                """{
            "type":"object", "additionalProperties":false,
            "properties":{
                "offset":{"type":"integer","minimum":0,"default":0},
                "limit":{"type":"integer","minimum":1,"maximum":200,"default":100}
            }
        }"""
            )
        fun tool(
            name: String,
            description: String,
            schema: JSONObject,
            readOnly: Boolean,
            destructive: Boolean = false,
            idempotent: Boolean = readOnly,
        ) =
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("inputSchema", schema)
                .put(
                    "annotations",
                    JSONObject()
                        .put("readOnlyHint", readOnly)
                        .put("destructiveHint", destructive)
                        .put("idempotentHint", idempotent)
                        .put("openWorldHint", false),
                )
        return JSONArray().apply {
            put(
                tool(
                    "get_routines",
                    "Read saved routines with exercises, planned sets and notes. Paginated by offset; follow nextOffset until null.",
                    pagingSchema,
                    true,
                )
            )
            put(
                tool(
                    "get_exercises",
                    "Read the exercise catalog, including custom exercises and IDs used to create routines. Paginated by offset; follow nextOffset until null.",
                    pagingSchema,
                    true,
                )
            )
            put(
                tool(
                    "get_history",
                    "Read saved workout history, newest first, including logged sets and notes. Timestamps are Unix milliseconds; weights are kilograms. Paginated by offset; follow nextOffset until null.",
                    pagingSchema,
                    true,
                )
            )
            put(
                tool(
                    "create_routine",
                    "Save a new routine using exercise IDs from get_exercises. Returns the saved routine with generated IDs. Omitted plannedSets creates three normal sets (8 reps, zero kg, exercise minimum hold). Never changes existing routines.",
                    JSONObject(
                        """{
                    "type":"object", "additionalProperties":false, "required":["name","exercises"],
                    "properties":{
                        "name":{"type":"string","minLength":1,"maxLength":200},
                        "defaultRestSeconds":{"type":"integer","minimum":0,"maximum":2147483647,"default":120},
                        "exercises":{"type":"array","minItems":1,"maxItems":200,"items":{
                            "type":"object","additionalProperties":false,"required":["exerciseId"],
                            "properties":{
                                "exerciseId":{"type":"string","minLength":1,"maxLength":200},
                                "restSecondsOverride":{"type":["integer","null"],"minimum":0,"maximum":2147483647},
                                "note":{"type":"string","maxLength":10000},
                                "plannedSets":{"type":"array","minItems":1,"maxItems":100,"items":{
                                    "type":"object","additionalProperties":false,"properties":{
                                        "type":{"type":"string","enum":["WARMUP","NORMAL","FAILURE"],"default":"NORMAL"},
                                        "weightKg":{"type":"number","minimum":0,"default":0},
                                        "reps":{"type":"integer","minimum":0,"maximum":2147483647,"default":8},
                                        "holdSeconds":{"type":"integer","minimum":0,"maximum":2147483647},
                                        "side":{"type":"string","enum":["BOTH","LEFT","RIGHT"],"default":"BOTH"}
                                    }
                                }}
                            }
                        }}
                    }
                }"""
                    ),
                    false,
                )
            )
            put(
                tool(
                    "create_exercise",
                    "Save a new custom exercise with a generated ID usable in create_routine. Defaults: REPETITIONS, weighted true for repetitions and false for timed, asymmetric false, cardio false, liveMeasurement false, minimumHoldSeconds 30, no secondary muscles. Returns the saved exercise.",
                    exerciseSchema(false),
                    false,
                )
            )
            put(
                tool(
                    "edit_exercise",
                    "Edit a custom exercise by exerciseId from get_exercises. Omitted fields keep their current values, including weighted and liveMeasurement when type changes. Use imageBase64 to attach, replace or remove a picture. Built-in exercises cannot be edited. Keeps the ID; existing routines and workouts reference the updated exercise. Returns the saved exercise.",
                    exerciseSchema(true),
                    false,
                    destructive = true,
                    idempotent = false,
                )
            )
        }
    }

    private fun JSONObject.only(vararg allowed: String) {
        keys().forEach { require(it in allowed) { "Unknown field: $it" } }
    }

    private fun JSONObject.string(key: String, maxLength: Int): String {
        val value = opt(key) as? String ?: throw IllegalArgumentException("$key must be a string")
        require(value.length <= maxLength) { "$key is too long" }
        return value
    }

    private fun JSONObject.boolean(key: String, default: Boolean): Boolean {
        if (!has(key)) return default
        return opt(key) as? Boolean ?: throw IllegalArgumentException("$key must be a boolean")
    }

    private fun JSONObject.integer(
        key: String,
        default: Int,
        min: Int = 0,
        max: Int = Int.MAX_VALUE,
    ): Int {
        if (!has(key)) return default
        val value =
            (opt(key) as? Number)?.toDouble()
                ?: throw IllegalArgumentException("$key must be an integer")
        require(
            value.isFinite() && value >= min && value <= max && value == value.toInt().toDouble()
        ) {
            "$key must be an integer between $min and $max"
        }
        return value.toInt()
    }

    private fun JSONObject.array(key: String, min: Int, max: Int): JSONArray {
        val value =
            opt(key) as? JSONArray ?: throw IllegalArgumentException("$key must be an array")
        require(value.length() in min..max) { "$key must contain $min to $max items" }
        return value
    }

    private inline fun <reified T : Enum<T>> JSONObject.enum(key: String, default: T): T {
        if (!has(key)) return default
        return enumValues<T>().firstOrNull { it.name == opt(key) }
            ?: throw IllegalArgumentException(
                "Invalid $key; use ${enumValues<T>().joinToString { it.name }}"
            )
    }

    private fun textContent(text: String) =
        JSONArray().put(JSONObject().put("type", "text").put("text", text))

    private fun error(id: Any?, code: Int, message: String) =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("error", JSONObject().put("code", code).put("message", message))

    private class RpcFailure(val code: Int, message: String) : Exception(message)
}
