package lofit

private val searchWhitespace = Regex("\\s+")

fun matchesSearchQuery(query: String, vararg values: String): Boolean {
    val words = query.trim().split(searchWhitespace).filter(String::isNotEmpty)
    return words.all { word -> values.any { it.contains(word, ignoreCase = true) } }
}
