package lofit

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/** Credentials stay in private app preferences, outside workout files and shared plans. */
class HevySettings(context: Context, preferenceName: String = "hevy-export") {
    private val preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)

    val accessToken: String
        get() = preferences.getString("access-token", "").orEmpty()

    val refreshToken: String
        get() = preferences.getString("refresh-token", "").orEmpty()

    fun saveTokenPair(access: String, refresh: String) {
        // LEGACY: Saving only the new access token loses the rotating refresh-token chain.
        val saved =
            preferences
                .edit()
                .putString(
                    "access-token",
                    access.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), ""),
                )
                .putString("refresh-token", refresh.trim())
                .commit()
        check(saved) { "Could not save Hevy tokens" }
    }

    var apiKey: String
        get() = preferences.getString("api-key", "").orEmpty()
        set(value) = preferences.edit().putString("api-key", value.trim()).apply()

    fun remoteWorkoutId(workoutId: String) = preferences.getString("workout-$workoutId", null)

    fun markExported(workoutId: String, remoteId: String) =
        preferences.edit().putString("workout-$workoutId", remoteId).apply()

    fun clear() = preferences.edit().clear().apply()
}

internal data class HevyResponse(val status: Int, val body: String) {
    fun json() = JSONObject(body)

    fun requireSuccess(): HevyResponse {
        if (status !in 200..299) {
            val detail = runCatching { json().optString("error") }.getOrNull().orEmpty()
            val message =
                when (status) {
                    401 ->
                        "Hevy rejected the token (401). Save a fresh access and refresh token pair."
                    403 -> "Hevy denied export (403)."
                    else ->
                        "Hevy returned HTTP $status${if (detail.isBlank()) "" else ": $detail"}."
                }
            error(message)
        }
        return this
    }
}

internal class HevySession(
    private val settings: HevySettings,
    private val send: (String, String, JSONObject?, String?, String?, Boolean) -> HevyResponse,
) {
    fun request(
        method: String,
        path: String,
        body: JSONObject?,
        key: String?,
        bearer: Boolean,
    ): HevyResponse {
        val first = send(method, path, body, key, if (bearer) settings.accessToken else null, false)
        if (!bearer || first.status != 401 || settings.refreshToken.isBlank()) return first
        refreshAccessToken()
        return send(method, path, body, key, settings.accessToken, false)
    }

    private fun refreshAccessToken() {
        val response =
            send(
                "POST",
                "/auth/refresh_token",
                JSONObject().put("refresh_token", settings.refreshToken),
                null,
                settings.accessToken,
                true,
            )
        if (response.status !in 200..299) {
            error("Hevy token refresh failed (HTTP ${response.status}). Save a fresh token pair.")
        }
        val pair = runCatching { response.json() }.getOrNull()
        val access = pair?.optString("access_token").orEmpty()
        val refresh = pair?.optString("refresh_token").orEmpty()
        check(
            access.isNotBlank() && refresh.isNotBlank() && access != "null" && refresh != "null"
        ) {
            "Hevy returned an incomplete token pair. Save fresh tokens from the same session."
        }
        settings.saveTokenPair(access, refresh)
    }
}

class HevyExport(private val context: Context, private val settings: HevySettings) {
    private val session = HevySession(settings, ::requestRaw)

    fun export(workout: WorkoutSession, catalog: List<Exercise>): String {
        check(workout.completedAt != null) { "Finish the workout first" }
        val key = settings.apiKey.takeIf { it.isNotBlank() }
        val bearer = settings.accessToken.takeIf { it.isNotBlank() }
        check(key != null || bearer != null) { "Set a Hevy bearer token or API key in Settings" }
        val remoteId = settings.remoteWorkoutId(workout.id)
        if (key == null && remoteId != null) return "Already exported to Hevy"
        val templates =
            if (key != null) loadPublicTemplates(key) else loadPrivateTemplates(bearer!!)
        val exercises = catalog.associateBy { it.id }
        val hevyExercises = JSONArray()
        workout.exercises
            .filter { it.sets.isNotEmpty() }
            .forEach { item ->
                val exercise = exercises[item.exerciseId] ?: error("Exercise missing from library")
                val template = resolveTemplate(exercise, item.sets, templates, key, bearer)
                val note = exerciseNote(exercise, item, template)
                val sets = JSONArray()
                item.sets.forEachIndexed { index, set ->
                    sets.put(
                        JSONObject().apply {
                            put("type", set.type.name.lowercase())
                            if (key == null) {
                                put("index", index)
                                put(
                                    "weight_kg",
                                    if (
                                        exercise.weighted &&
                                            template.type in setOf("weight_reps", "weight_duration")
                                    )
                                        set.weightKg
                                    else JSONObject.NULL,
                                )
                                put(
                                    "reps",
                                    if (exercise.type.tracksDuration) JSONObject.NULL else set.reps,
                                )
                                put(
                                    "duration_seconds",
                                    if (exercise.type.tracksDuration) set.holdSeconds
                                    else JSONObject.NULL,
                                )
                                put("distance_meters", JSONObject.NULL)
                                put("rpe", JSONObject.NULL)
                                put("custom_metric", JSONObject.NULL)
                                put(
                                    "completed_at",
                                    Instant.ofEpochMilli(set.completedAt).toString(),
                                )
                            } else {
                                if (exercise.type.tracksDuration)
                                    put("duration_seconds", set.holdSeconds)
                                else put("reps", set.reps)
                                if (
                                    exercise.weighted &&
                                        template.type in setOf("weight_reps", "weight_duration")
                                )
                                    put("weight_kg", set.weightKg)
                            }
                        }
                    )
                }
                hevyExercises.put(
                    JSONObject().apply {
                        put("exercise_template_id", template.id)
                        put("notes", note)
                        put("sets", sets)
                        if (key == null) {
                            put("title", template.title)
                            put("rest_timer_seconds", item.restSeconds(workout.defaultRestSeconds))
                            put("volume_doubling_enabled", false)
                            put("superset_id", JSONObject.NULL)
                        }
                    }
                )
            }
        if (key == null) {
            val body =
                JSONObject().apply {
                    put("share_to_strava", false)
                    put(
                        "workout",
                        JSONObject().apply {
                            put("workout_id", workout.id)
                            put("title", workout.name)
                            put("description", "")
                            put("start_time", workout.startedAt / 1000)
                            put("end_time", workout.completedAt!! / 1000)
                            put("exercises", hevyExercises)
                            put("apple_watch", false)
                            put("wearos_watch", false)
                            put(
                                "biometrics",
                                JSONObject()
                                    .put("heart_rate_samples", JSONArray())
                                    .put("total_calories", 0),
                            )
                            put("gym", JSONObject.NULL)
                            put("is_biometrics_public", false)
                            put("is_private", false)
                            put("media", JSONArray())
                            put("routine_id", JSONObject.NULL)
                            put("trainer_program_id", JSONObject.NULL)
                        },
                    )
                }
            request("POST", "/v2/workout", body, bearer = bearer).requireSuccess()
            settings.markExported(workout.id, workout.id)
            return "Exported to Hevy"
        }
        val body =
            JSONObject()
                .put(
                    "workout",
                    JSONObject().apply {
                        put("title", workout.name)
                        put("start_time", Instant.ofEpochMilli(workout.startedAt).toString())
                        put("end_time", Instant.ofEpochMilli(workout.completedAt!!).toString())
                        put("exercises", hevyExercises)
                    },
                )
        val response =
            request(
                    if (remoteId == null) "POST" else "PUT",
                    if (remoteId == null) "/v1/workouts" else "/v1/workouts/$remoteId",
                    body,
                    key = key,
                )
                .requireSuccess()
        if (remoteId == null) {
            val result = response.json()
            val created =
                result.optJSONArray("workout")?.optJSONObject(0)
                    ?: result.optJSONObject("workout")
                    ?: result
            settings.markExported(workout.id, created.getString("id"))
        }
        return if (remoteId == null) "Exported to Hevy" else "Updated on Hevy"
    }

    private fun exerciseNote(
        exercise: Exercise,
        item: WorkoutExercise,
        template: HevyTemplate,
    ): String {
        if (template.title.startsWith("Placeholder ")) return exercise.name
        return buildString {
            if (exercise.asymmetric && item.sets.any { it.side != Side.BOTH }) {
                if (isNotEmpty()) append("\n")
                append("Set sides: ")
                append(
                    item.sets
                        .mapIndexed { index, set -> "${index + 1} ${set.side.label}" }
                        .joinToString(", ")
                )
            }
            if (item.effectiveNote.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(item.effectiveNote)
            }
        }
    }

    private fun loadPrivateTemplates(token: String): MutableList<HevyTemplate> {
        val templates = mutableListOf<HevyTemplate>()
        val bundled =
            JSONArray(
                context.assets.open("hevy_exercise_templates.json").bufferedReader().use {
                    it.readText()
                }
            )
        for (index in 0 until bundled.length()) {
            val item = bundled.getJSONObject(index)
            templates.add(
                HevyTemplate(item.getString("id"), item.getString("title"), item.getString("type"))
            )
        }
        val custom =
            JSONArray(
                request("GET", "/custom_exercise_templates", bearer = token).requireSuccess().body
            )
        for (index in 0 until custom.length()) {
            val item = custom.getJSONObject(index)
            if (!item.optBoolean("is_archived")) {
                templates.add(
                    HevyTemplate(
                        item.getString("id"),
                        item.getString("title"),
                        item.getString("exercise_type"),
                        true,
                    )
                )
            }
        }
        return templates
    }

    private fun loadPublicTemplates(key: String): MutableList<HevyTemplate> {
        val templates = mutableListOf<HevyTemplate>()
        var page = 1
        var pageCount: Int
        do {
            val result =
                request("GET", "/v1/exercise_templates?page=$page&pageSize=100", key = key)
                    .requireSuccess()
                    .json()
            val entries = result.getJSONArray("exercise_templates")
            for (index in 0 until entries.length()) {
                val item = entries.getJSONObject(index)
                templates.add(
                    HevyTemplate(
                        item.getString("id"),
                        item.getString("title"),
                        item.getString("type"),
                        item.optBoolean("is_custom"),
                    )
                )
            }
            pageCount = result.getInt("page_count")
            page++
        } while (page <= pageCount)
        return templates
    }

    private fun resolveTemplate(
        exercise: Exercise,
        sets: List<LoggedSet>,
        templates: MutableList<HevyTemplate>,
        key: String?,
        bearer: String?,
    ): HevyTemplate {
        val type = HevyExerciseMapping.type(exercise)
        HevyExerciseMapping.find(exercise, sets, templates)?.let {
            return it
        }
        val name = HevyExerciseMapping.placeholder(type)
        templates
            .firstOrNull { it.title == name && it.type == type }
            ?.let {
                return it
            }
        val exerciseBody =
            JSONObject().apply {
                put("title", name)
                put("exercise_type", type)
                put("muscle_group", "other")
                put("equipment_category", "other")
                put("other_muscles", JSONArray())
            }
        val body = if (key == null) JSONObject().put("exercise", exerciseBody) else exerciseBody
        val response =
            request(
                    "POST",
                    if (key == null) "/custom_exercise_template" else "/v1/exercise_templates",
                    body,
                    key = key,
                    bearer = bearer,
                )
                .requireSuccess()
        val id =
            runCatching {
                val result = response.json()
                result.optJSONObject("exercise")?.optString("id")
                    ?: result.optJSONObject("exercise_template")?.optString("id")
                    ?: result.optString("id")
            }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: Regex(
                        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
                    )
                    .find(response.body)
                    ?.value
                ?: error("Could not read new Hevy exercise ID")
        return HevyTemplate(id, name, type, true).also { templates.add(it) }
    }

    private fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        key: String? = null,
        bearer: String? = null,
    ): HevyResponse {
        return session.request(method, path, body, key, bearer != null)
    }

    private fun requestRaw(
        method: String,
        path: String,
        body: JSONObject? = null,
        key: String? = null,
        bearer: String? = null,
        refresh: Boolean = false,
    ): HevyResponse {
        val connection = URL("https://api.hevyapp.com$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            if (key != null) connection.setRequestProperty("api-key", key)
            if (bearer != null) {
                connection.setRequestProperty("Authorization", "Bearer $bearer")
                connection.setRequestProperty(
                    "X-Api-Key",
                    if (refresh) "with_great_power" else "klean_kanteen_insulated",
                )
                if (!refresh) {
                    connection.setRequestProperty("Hevy-App-Version", "2.5.6")
                    connection.setRequestProperty("Hevy-App-Build", "1819922")
                    connection.setRequestProperty("Hevy-Platform", "android 36")
                }
            }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use {
                    it.write(body.toString().toByteArray(Charsets.UTF_8))
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return HevyResponse(status, stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }
}
