package lofit

private val searchWhitespace = Regex("\\s+")

fun matchesSearchQuery(query: String, vararg values: String): Boolean {
    val words = query.trim().split(searchWhitespace).filter(String::isNotEmpty)
    return words.all { word -> values.any { it.contains(word, ignoreCase = true) } }
}

internal fun exerciseSearchTerms(exercise: Exercise): String =
    listOf(
            exercise.type.label,
            "Weighted".takeIf { exercise.weighted },
            "Live measurement".takeIf { exercise.supportsLiveMeasurement },
        )
        .filterNotNull()
        .joinToString(" ")

internal fun exerciseTrackingLabel(exercise: Exercise): String =
    "${exercise.type.label} · ${if (exercise.weighted) "Weighted" else "Bodyweight"}" +
        if (exercise.supportsLiveMeasurement) " · Live measurement" else ""
