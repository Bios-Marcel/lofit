package lofit

import kotlin.math.abs

internal data class LabelWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val centerX
        get() = (left + right) / 2.0

    val centerY
        get() = (top + bottom) / 2.0

    val height
        get() = bottom - top
}

internal data class NutritionLabel(
    val amounts: FoodAmounts,
    val volumeOnly: Boolean = false,
    val basisUnconfirmed: Boolean = false,
    val weakReadings: Set<FoodMetric> = emptySet(),
) {
    fun caloriesMatch(estimated: Double): Boolean? {
        val scanned = amounts[FoodMetric.CALORIES] ?: return null
        if (FoodMetric.energySources.any { it != FoodMetric.FIBER && it !in amounts }) return null
        return abs(scanned - estimated) <= 1.0 + 1e-9
    }
}

internal object NutritionLabelParser {
    private val ignoreCase = RegexOption.IGNORE_CASE
    private val hundred = Regex("""(?<!\d)[1Il][0O]{2}\s*(m[lI1]|g(?:r)?|9)(?!\p{L})""", ignoreCase)
    private val serving = Regex("""\b(?:portion|serving|stück)\b""", ignoreCase)
    private val amount =
        Regex(
            """(?<![\d.,])([<≤−-]\s*)?(\d+(?:\s*[,.]\s*\d+)?)(?:\s*(kca[lI1]|kj|mg|g|q|%|(?<=\s)9)(?![\d\p{L}]))?""",
            ignoreCase,
        )
    private val marker = Regex("""^[*·•−–—-]+\s*""")
    private val saturated = Regex("""(?:gesättigt|saturat|saturé)""", ignoreCase)
    private val pairedEnergyUnits = Regex("""\bkj\s*/\s*kca[lI1]\b""", ignoreCase)
    private val pairedEnergyValues = Regex("""\d[\d .]*\s*/\s*(\d+(?:[,.]\d+)?)(?![\d.,])""")
    private val nutrients =
        listOf(
                FoodMetric.CALORIES to """[eé]nergie(?:gehalt)?|brennwert|energy|calories""",
                FoodMetric.FAT to """fett|fat|matières?\s+grasses?|grassi""",
                FoodMetric.CARBS to """kohlenhydrate[n]?|carbohydrates?|glucides?|carboidrati""",
                FoodMetric.SUGAR to
                    """(?:(?:davon|of which|dont|di cui):?\s+)?(?:zucker|sugars?|sucres?|zuccheri)""",
                FoodMetric.FIBER to """ballast\s*stoffe|fib(?:res?|er)""",
                FoodMetric.PROTEIN to """ei\s*wei(?:l?[ßẞ]|ss?)|prot[eé]ines?""",
                FoodMetric.SALT to """salz(?:gehalt)?|salt|sel|sale""",
            )
            .map { (metric, name) ->
                metric to Regex("(?:^|[/|]\\s*)(?:$name)(?!\\p{L})", ignoreCase)
            }

    fun parse(words: List<LabelWord>, slope: Double = 0.0): NutritionLabel {
        val rows = groupRows(words, slope)
        val candidates = rows.mapNotNull(::readRow)
        val firstValue = candidates.firstOrNull()?.source
        val headers = rows.takeWhile { it !== firstValue }
        val bases = headers.flatMap { row ->
            hundred
                .findAll(row.text)
                .map { match ->
                    match.groupValues[1].startsWith("m", ignoreCase = true) to
                        row.rightAt(match.range.last)
                }
                .toList()
        }
        val grams = bases.firstOrNull { !it.first }?.second
        val volumes = bases.filter { it.first }.map { it.second }
        if (grams == null && volumes.isNotEmpty())
            return NutritionLabel(emptyMap(), volumeOnly = true)

        val portions = headers.flatMap { row ->
            serving.findAll(row.text).map { row.rightAt(it.range.last) }.toList()
        }
        if (grams == null && portions.isNotEmpty()) return NutritionLabel(emptyMap())

        // Real values can be right-aligned well past the text in a wide column heading.
        val columns = valueColumns(candidates)
        val target =
            when {
                columns.isEmpty() -> grams
                grams == null -> columns.first()
                else -> columns.minByOrNull { abs(it - grams) }
            }
        val otherColumns = columns.filter { it != target } + volumes + portions
        val values = candidates.mapNotNull { row ->
            val cell =
                if (target == null) {
                    row.cells.singleOrNull()
                } else {
                    row.cells
                        .minByOrNull { abs(it.right - target) }
                        ?.takeIf { selected ->
                            val distance = abs(selected.right - target)
                            otherColumns.none { abs(selected.right - it) < distance }
                        }
                }
            cell?.takeIf { it.value != null }?.let { row.metric to it }
        }
        val readings =
            values
                .groupBy({ it.first }, { it.second })
                .mapNotNull { (metric, cells) ->
                    val reliable = cells.filter { it.hasUnit }.ifEmpty { cells }
                    reliable.distinctBy { it.value }.singleOrNull()?.let { metric to it }
                }
                .toMap()
        return NutritionLabel(
            readings.mapValues { (_, cell) -> requireNotNull(cell.value) },
            basisUnconfirmed = grams == null,
            weakReadings = readings.filterValues { !it.hasUnit }.keys,
        )
    }

    private fun readRow(row: Row): NutrientRow? {
        val start = marker.find(row.text)?.range?.last?.plus(1) ?: 0
        val text = row.text.substring(start)
        val named =
            nutrients
                .mapNotNull { (metric, name) ->
                    name.find(text)?.let { metric to (start + it.range.last + 1) }
                }
                .minByOrNull { it.second }
        val metric =
            named?.first
                ?: FoodMetric.CALORIES.takeIf { text.firstOrNull()?.isDigit() == true }
                ?: return null
        val searchStart = named?.second ?: start
        val matches = amount.findAll(row.text, searchStart).toList()
        if (
            metric == FoodMetric.FAT &&
                saturated.containsMatchIn(
                    row.text.substring(0, matches.firstOrNull()?.range?.first ?: row.text.length)
                )
        )
            return null
        val cells = matches.mapNotNull { match ->
            val unit = match.groupValues[3].lowercase()
            val kcal = unit.startsWith("kca")
            if (metric == FoodMetric.CALORIES && !kcal) return@mapNotNull null
            if (metric != FoodMetric.CALORIES && unit !in listOf("", "g", "mg", "q", "9"))
                return@mapNotNull null
            val digits = match.groupValues[2].filterNot(Char::isWhitespace)
            // The OCR model often reads the printed g as a digit 9, including in compact cells.
            val numeric =
                if (unit.isEmpty() && digits.length > 1 && digits.endsWith('9')) {
                    digits.dropLast(1).trimEnd(',', '.')
                } else digits
            val number =
                numeric.foodNumber()?.let { if (unit == "mg") it / 1000 else it }
                    ?: return@mapNotNull null
            val valid = if (kcal) number in 0.0..900.0 else metric.validInput(number)
            if (!valid) return@mapNotNull null
            val right = row.rightAt(match.range.last)
            Cell(
                number.takeIf { match.groupValues[1].isEmpty() },
                right,
                hasUnit = unit.isNotEmpty(),
            )
        }
        val values =
            if (
                cells.isEmpty() &&
                    metric == FoodMetric.CALORIES &&
                    pairedEnergyUnits.containsMatchIn(text)
            ) {
                pairedEnergyValues
                    .findAll(row.text, searchStart)
                    .mapNotNull { match ->
                        match.groupValues[1]
                            .foodNumber()
                            ?.takeIf { it in 0.0..900.0 }
                            ?.let { Cell(it, row.rightAt(match.range.last), hasUnit = true) }
                    }
                    .toList()
            } else cells
        return NutrientRow(row, metric, values).takeIf { values.isNotEmpty() }
    }

    private fun valueColumns(rows: List<NutrientRow>): List<Int> {
        val multiColumnRows = rows.filter { it.cells.size > 1 }.groupBy { it.cells.size }
        val consistentRows =
            multiColumnRows.maxByOrNull { it.value.size }?.value ?: return emptyList()
        return consistentRows.first().cells.indices.map { column ->
            val positions = consistentRows.map { it.cells[column].right }.sorted()
            positions[positions.size / 2]
        }
    }

    private fun groupRows(words: List<LabelWord>, slope: Double): List<Row> {
        val groups = mutableListOf<MutableList<LabelWord>>()
        fun y(word: LabelWord) = word.centerY - word.centerX * slope
        words.sortedBy(::y).forEach { word ->
            val row = groups.lastOrNull()
            val anchor = row?.get(row.size / 2)
            if (
                anchor != null &&
                    abs(y(word) - y(anchor)) <= 0.65 * maxOf(word.height, anchor.height)
            ) {
                row.add(word)
            } else {
                groups.add(mutableListOf(word))
            }
        }
        return groups.map { Row(it.sortedBy(LabelWord::left)) }
    }

    private data class Cell(val value: Double?, val right: Int, val hasUnit: Boolean)

    private data class NutrientRow(val source: Row, val metric: FoodMetric, val cells: List<Cell>)

    private class Row(words: List<LabelWord>) {
        private val ends = mutableListOf<Pair<Int, Int>>()
        val text = buildString {
            words.forEach {
                if (isNotEmpty()) append(' ')
                append(it.text)
                ends.add(lastIndex to it.right)
            }
        }

        fun rightAt(index: Int): Int = ends.first { (end, _) -> end >= index }.second
    }
}
