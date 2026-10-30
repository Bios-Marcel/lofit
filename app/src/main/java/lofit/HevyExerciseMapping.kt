package lofit

internal data class HevyTemplate(
    val id: String,
    val title: String,
    val type: String,
    val custom: Boolean = false,
)

/** Match only known names and compatible metrics; an uncertain match uses a placeholder. */
internal object HevyExerciseMapping {
    private val aliases =
        mapOf(
            "back-squat" to "Squat (Barbell)",
            "bench-press" to "Bench Press (Barbell)",
            "incline-dumbbell-bench-press" to "Incline Bench Press (Dumbbell)",
            "cable-tricep-extension" to "Triceps Extension (Cable)",
            "deadlift" to "Deadlift (Barbell)",
            "overhead-press" to "Shoulder Press (Barbell)",
            "dumbbell-curl" to "Bicep Curl (Dumbbell)",
            "pull-up" to "Pull Up",
            "push-up" to "Push Up",
        )

    fun type(exercise: Exercise): String =
        when {
            exercise.type.tracksDuration && exercise.weighted -> "weight_duration"
            exercise.type.tracksDuration -> "duration"
            exercise.weighted -> "weight_reps"
            else -> "reps_only"
        }

    fun placeholder(type: String): String =
        when (type) {
            "duration" -> "Placeholder Timed"
            "weight_duration" -> "Placeholder Weighted Timed"
            "weight_reps" -> "Placeholder Weighted"
            else -> "Placeholder Reps"
        }

    fun find(
        exercise: Exercise,
        sets: List<LoggedSet>,
        templates: List<HevyTemplate>,
    ): HevyTemplate? {
        val expectedType = type(exercise)
        val name = aliases[exercise.id] ?: exercise.name
        val names = buildList {
            add(name)
            val equipment =
                listOf("Dumbbell", "Barbell", "Kettlebell", "Cable", "Machine").firstOrNull {
                    name.startsWith("$it ", ignoreCase = true)
                }
            if (equipment != null) add("${name.drop(equipment.length + 1)} ($equipment)")
        }
        return templates
            .sortedBy { it.custom }
            .firstOrNull { template ->
                names.any { normalize(template.title) == normalize(it) } &&
                    (template.type == expectedType ||
                        (expectedType == "weight_reps" &&
                            template.type in setOf("bodyweight_reps", "reps_only") &&
                            sets.all { it.weightKg == 0.0 }) ||
                        (expectedType == "reps_only" && template.type == "bodyweight_reps") ||
                        (expectedType == "weight_duration" &&
                            template.type == "duration" &&
                            sets.all { it.weightKg == 0.0 }))
            }
    }

    private fun normalize(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }
}
