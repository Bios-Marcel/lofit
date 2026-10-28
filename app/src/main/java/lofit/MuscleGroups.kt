package lofit

object MuscleGroups {
    private val aliases =
        mapOf(
            "front delts" to "Front deltoids",
            "rear delts" to "Rear deltoids",
        )

    val all =
        listOf(
            "Abdominals",
            "Abductors",
            "Adductors",
            "Back",
            "Biceps",
            "Brachialis",
            "Brachioradialis",
            "Calves",
            "Chest",
            "Core",
            "Erector spinae",
            "Feet",
            "Forearms",
            "Front deltoids",
            "Gastrocnemius",
            "Glutes",
            "Grip",
            "Hamstrings",
            "Hip flexors",
            "Lateral deltoids",
            "Lats",
            "Lower back",
            "Middle back",
            "Neck",
            "Obliques",
            "Posterior chain",
            "Quadriceps",
            "Rear deltoids",
            "Rhomboids",
            "Rotator cuff",
            "Serratus anterior",
            "Shoulders",
            "Soleus",
            "Tibialis anterior",
            "Traps",
            "Triceps",
            "Upper back",
            "Upper chest",
        )

    fun canonical(value: String): String? {
        val normalized = value.trim()
        return all.firstOrNull { it.equals(normalized, ignoreCase = true) }
            ?: aliases[normalized.lowercase()]
    }

    fun canonicalList(value: String): String? {
        if (value.isBlank() || value.trim() == "—") return ""
        val groups =
            value
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .map { canonical(it) ?: return null }
                .distinct()
        return groups.joinToString(", ")
    }
}
