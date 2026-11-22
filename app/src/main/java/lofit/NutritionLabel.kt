package lofit

import kotlin.math.abs

internal data class LabelWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float? = null,
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
    val lowConfidenceReadings: Set<FoodMetric> = emptySet(),
    val valuesWithoutDecimal: FoodAmounts = emptyMap(),
) {
    fun caloriesMatch(estimated: Double): Boolean? {
        val scanned = amounts[FoodMetric.CALORIES] ?: return null
        if (FoodMetric.energySources.any { it != FoodMetric.FIBER && it !in amounts }) return null
        return abs(scanned - estimated) <= 1.0 + 1e-9
    }
}

internal object NutritionLabelParser {
    private const val rowTolerance = 0.65
    private const val minimumConfidence = 0.5f
    private val ignoreCase = RegexOption.IGNORE_CASE
    private val hundred = Regex("""(?<!\d)[1Il][0O]{2}\s*(m[lI1]|g(?:r)?|9)(?!\p{L})""", ignoreCase)
    private val serving = Regex("""\b(?:portion|serving|stück)\b""", ignoreCase)
    private val amount =
        Regex(
            """(?<![\d.,])([<≤−-]\s*)?(\d+(?:\s*[,.]\s*\d+)?|(?<!\p{L})(?:o\s*)?[,.]\s*\d+)(?:\s*(kca[lI1]|kj|mg|g|q|%|(?<=\s)9)(?![\d\p{L}]))?""",
            ignoreCase,
        )
    private val splitDecimal =
        Regex("""(?<![\d.,])(\d{1,2})\s+(\d{1,3})\s*(?:mg|g)(?![\d\p{L}])""", ignoreCase)
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
        // LEGACY: Keep the OCR angle when it yields nutrients or identifies a volume-only label.
        val label = parseRows(rows)
        if (label.volumeOnly || label.amounts.keys.any { it != FoodMetric.CALORIES }) return label

        val correctedSlope = estimateTableSlope(rows, slope) ?: return label
        val recovered = parseRows(groupRows(words, correctedSlope))
        val reliableNutrients =
            recovered.amounts.keys.count {
                it != FoodMetric.CALORIES && it !in recovered.weakReadings
            }
        return if (!recovered.basisUnconfirmed && reliableNutrients >= 2) recovered else label
    }

    private fun parseRows(rows: List<Row>): NutritionLabel {
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
            lowConfidenceReadings = readings.filterValues { it.lowConfidence }.keys,
            valuesWithoutDecimal =
                readings
                    .mapNotNull { (metric, cell) ->
                        cell.withoutDecimal?.let { metric to it }
                    }
                    .toMap(),
        )
    }

    private fun estimateTableSlope(rows: List<Row>, slope: Double): Double? {
        val names = rows.mapNotNull { row ->
            val (metric, end) =
                nutrientName(row.text.substring(row.nameStart)) ?: return@mapNotNull null
            if (
                metric == FoodMetric.CALORIES ||
                    (metric == FoodMetric.FAT && saturated.containsMatchIn(row.text))
            )
                return@mapNotNull null
            metric to row.wordAt(row.nameStart + end - 1)
        }
        if (names.size < 2) return null

        fun y(word: LabelWord) = word.centerY - word.centerX * slope

        // OCR can return each column as separate, nearly horizontal text fragments.
        val adjustments =
            rows
                .flatMap { row ->
                    amount
                        .findAll(row.text)
                        .mapNotNull { match ->
                            val unit = match.groupValues[3].lowercase()
                            if (match.groupValues[1].isNotEmpty() || (unit != "g" && unit != "mg"))
                                return@mapNotNull null
                            val value = row.wordAt(match.range.first)
                            val leftNames = names.filter { (_, name) -> name.right < value.left }
                            val nearest =
                                leftNames.minByOrNull { (_, name) -> abs(y(value) - y(name)) }
                                    ?: return@mapNotNull null
                            val (metric, name) = nearest
                            val offset = y(value) - y(name)
                            val distance = abs(offset)
                            val width = value.centerX - name.centerX
                            val height = maxOf(value.height, name.height)
                            if (
                                distance <= rowTolerance * height ||
                                    distance > 1.25 * height ||
                                    width <= 2 * height
                            )
                                return@mapNotNull null
                            // A neighboring row can be closer when the shift approaches half a row.
                            if (
                                leftNames.any {
                                    it !== nearest &&
                                        abs(y(value) - y(it.second)) <= distance + height / 4.0
                                }
                            )
                                return@mapNotNull null

                            metric to offset / width
                        }
                        .toList()
                }
                .sortedBy { it.second }
        val adjustment = adjustments.getOrNull(adjustments.size / 2)?.second ?: return null
        val agreeingMetrics =
            adjustments
                .filter { (_, candidate) -> abs(candidate - adjustment) <= abs(adjustment) * 0.3 }
                .map { it.first }
                .toSet()
        return (slope + adjustment).takeIf { agreeingMetrics.size >= 2 }
    }

    private fun nutrientName(text: String): Pair<FoodMetric, Int>? =
        nutrients
            .mapNotNull { (metric, name) ->
                name.find(text)?.let { metric to (it.range.last + 1) }
            }
            .minByOrNull { it.second }

    private fun readRow(row: Row): NutrientRow? {
        val start = row.nameStart
        val text = row.text.substring(start)
        val named = nutrientName(text)
        val metric =
            named?.first
                ?: FoodMetric.CALORIES.takeIf { text.firstOrNull()?.isDigit() == true }
                ?: return null
        val searchStart = start + (named?.second ?: 0)
        val originalMatches = amount.findAll(row.text, searchStart).toList()
        val separators =
            if (metric == FoodMetric.CALORIES) emptyList()
            else row.inferredDecimalSeparators(searchStart, originalMatches)
        val matches =
            if (separators.isEmpty()) originalMatches
            else {
                val corrected = StringBuilder(row.text)
                for (separator in separators) corrected.setCharAt(separator, ',')
                amount.findAll(corrected, searchStart).toList()
            }
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
            val digits =
                match.groupValues[2]
                    .filterNot(Char::isWhitespace)
                    .replace('o', '0', ignoreCase = true)
            // The OCR model often reads the printed g as a digit 9, including in compact cells.
            val numeric =
                if (unit.isEmpty() && digits.length > 1 && digits.endsWith('9')) {
                    digits.dropLast(1).trimEnd(',', '.')
                } else digits
            val divisor = if (unit == "mg") 1000.0 else 1.0
            val number = numeric.foodNumber()?.div(divisor) ?: return@mapNotNull null
            val valid = if (kcal) number in 0.0..900.0 else metric.validInput(number)
            if (!valid) return@mapNotNull null
            val right = row.rightAt(match.range.last)
            val inferred = separators.any { it in match.range }
            Cell(
                number.takeIf { match.groupValues[1].isEmpty() },
                right,
                hasUnit = unit.isNotEmpty(),
                lowConfidence = inferred || row.hasLowConfidence(match.range),
                withoutDecimal = numeric.withoutDecimal()?.div(divisor)?.takeUnless { inferred },
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
                        val kcal = requireNotNull(match.groups[1])
                        kcal.value
                            .foodNumber()
                            ?.takeIf { it in 0.0..900.0 }
                            ?.let {
                                Cell(
                                    it,
                                    row.rightAt(kcal.range.last),
                                    hasUnit = true,
                                    lowConfidence = row.hasLowConfidence(kcal.range),
                                    withoutDecimal = kcal.value.withoutDecimal(),
                                )
                            }
                    }
                    .toList()
            } else cells
        return NutrientRow(row, metric, values).takeIf { values.isNotEmpty() }
    }

    private fun valueColumns(rows: List<NutrientRow>): List<Int> {
        val multiColumnRows = rows.filter { it.cells.size > 1 }.groupBy { it.cells.size }
        val consistentRows = multiColumnRows.values.maxByOrNull { it.size } ?: return emptyList()
        // An OCR error in a single row should not create a second value column.
        if (consistentRows.size < 2) return emptyList()
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
                    abs(y(word) - y(anchor)) <= rowTolerance * maxOf(word.height, anchor.height)
            ) {
                row.add(word)
            } else {
                groups.add(mutableListOf(word))
            }
        }
        return groups.map { Row(it.sortedBy(LabelWord::left)) }
    }

    private fun String.withoutDecimal(): Double? = takeIf {
        '.' in it || ',' in it
    }
        ?.filter(Char::isDigit)
        ?.toDoubleOrNull()

    private data class Cell(
        val value: Double?,
        val right: Int,
        val hasUnit: Boolean,
        val lowConfidence: Boolean,
        val withoutDecimal: Double?,
    )

    private data class NutrientRow(val source: Row, val metric: FoodMetric, val cells: List<Cell>)

    private class Row(private val words: List<LabelWord>) {
        private val ends = mutableListOf<Int>()
        val text = buildString {
            words.forEach {
                if (isNotEmpty()) append(' ')
                append(it.text)
                ends.add(lastIndex)
            }
        }

        val nameStart
            get() = marker.find(text)?.range?.last?.plus(1) ?: 0

        fun wordAt(index: Int): LabelWord = words[ends.indexOfFirst { it >= index }]

        fun rightAt(index: Int): Int = wordAt(index).right

        fun inferredDecimalSeparators(start: Int, matches: List<MatchResult>): List<Int> =
            splitDecimal
                .findAll(text, start)
                .mapNotNull { candidate ->
                    val whole = requireNotNull(candidate.groups[1])
                    val fractional = requireNotNull(candidate.groups[2])
                    if (fractional.value.length == 3 && whole.value != "0") return@mapNotNull null
                    val original =
                        matches.firstOrNull { it.groups[2]?.range == whole.range }
                            ?: return@mapNotNull null
                    if (original.groupValues[3] !in listOf("", "9")) return@mapNotNull null

                    val before = wordAt(whole.range.last)
                    val after = wordAt(fractional.range.first)
                    val digitWidth = (before.right - before.left).toDouble() / before.text.length
                    val gap = (after.left - before.right).toDouble()
                    // A split within one print cell is at most about one digit wide.
                    (whole.range.last + 1).takeIf {
                        before === after || (gap > digitWidth / 4 && gap <= digitWidth)
                    }
                }
                .toList()

        fun hasLowConfidence(range: IntRange): Boolean =
            words.indices.any { index ->
                val word = words[index]
                val confidence = word.confidence ?: return@any false
                val end = ends[index]
                val start = end - word.text.length + 1
                confidence < minimumConfidence && end >= range.first && start <= range.last
            }
    }
}
