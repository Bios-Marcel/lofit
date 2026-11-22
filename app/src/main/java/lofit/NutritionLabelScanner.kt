package lofit

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.roundToInt

internal object NutritionLabelScanner {
    // ML Kit and image preparation run on the caller's worker thread.
    fun scan(bitmap: Bitmap): NutritionLabel {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            fun read(image: Bitmap): NutritionLabel =
                parse(Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0))))

            val original = read(bitmap)
            if (!needsSecondPass(original)) return discardImpossibleValues(original)

            fun retry(label: NutritionLabel, threshold: Boolean): NutritionLabel {
                val image = enhance(bitmap, threshold)
                return try {
                    val tentative = runCatching { read(image) }.getOrNull() ?: return label
                    merge(label, tentative)
                } finally {
                    image.recycle()
                }
            }

            val enhanced = retry(original, threshold = false)
            val best =
                if (
                    needsSecondPass(enhanced) &&
                        (enhanced.energyGap() > 12.0 || enhanced.sugarExceedsCarbs())
                )
                    retry(enhanced, threshold = true)
                else enhanced
            return discardImpossibleValues(best)
        } finally {
            recognizer.close()
        }
    }

    private fun needsSecondPass(label: NutritionLabel): Boolean =
        label.basisUnconfirmed ||
            !label.amounts.keys.containsAll(commonFields) ||
            label.weakReadings.isNotEmpty() ||
            label.lowConfidenceReadings.isNotEmpty() ||
            label.sugarExceedsCarbs() ||
            label.amounts[FoodMetric.SALT]?.let { it > 0.0 && it % 1.0 == 0.0 } == true ||
            label.energyGap() > 1.0

    internal fun merge(original: NutritionLabel, retry: NutritionLabel): NutritionLabel {
        if (original.amounts.isEmpty()) return retry
        if (original.basisUnconfirmed != retry.basisUnconfirmed) {
            return if (retry.amounts.isNotEmpty() && !retry.basisUnconfirmed) retry else original
        }
        if (retry.amounts.isEmpty()) return original

        val (primary, secondary) =
            if (retry.energyGap() <= 1.0 && original.energyGap() > 1.0) retry to original
            else original to retry
        val missingOrWeaker =
            secondary.amounts.filter { (metric, value) ->
                val first = primary.amounts[metric] ?: return@filter true
                when {
                    first == value &&
                        metric in secondary.valuesWithoutDecimal &&
                        metric in primary.lowConfidenceReadings &&
                        metric !in secondary.lowConfidenceReadings -> true
                    retainsDecimal(primary, secondary, metric) -> false
                    retainsDecimal(secondary, primary, metric) -> true
                    else -> metric in primary.weakReadings && metric !in secondary.weakReadings
                }
            }
        return primary.copy(
            amounts = primary.amounts + missingOrWeaker,
            weakReadings =
                (primary.weakReadings - missingOrWeaker.keys) +
                    secondary.weakReadings.intersect(missingOrWeaker.keys),
            lowConfidenceReadings =
                (primary.lowConfidenceReadings - missingOrWeaker.keys) +
                    secondary.lowConfidenceReadings.intersect(missingOrWeaker.keys),
            valuesWithoutDecimal =
                (primary.valuesWithoutDecimal - missingOrWeaker.keys) +
                    secondary.valuesWithoutDecimal.filterKeys { it in missingOrWeaker },
        )
    }

    private fun retainsDecimal(
        decimal: NutritionLabel,
        whole: NutritionLabel,
        metric: FoodMetric,
    ): Boolean {
        val withoutDecimal = decimal.valuesWithoutDecimal[metric] ?: return false
        return metric !in whole.valuesWithoutDecimal && withoutDecimal == whole.amounts[metric]
    }

    private fun discardImpossibleValues(label: NutritionLabel): NutritionLabel {
        val amounts = label.amounts
        val invalid = mutableSetOf<FoodMetric>()
        if (label.sugarExceedsCarbs()) invalid.add(FoodMetric.SUGAR)

        val kcal = amounts[FoodMetric.CALORIES]
        if (kcal != null) {
            // A lost comma can make one nutrient exceed the entire label's energy on its own.
            val limit = kcal + maxOf(12.0, kcal * 0.2)
            for (metric in listOf(FoodMetric.FAT, FoodMetric.PROTEIN)) {
                if ((amounts[metric] ?: 0.0) * metric.kcalPerGram > limit) invalid.add(metric)
            }
        }
        return label.copy(
            amounts = amounts - invalid,
            weakReadings = label.weakReadings - invalid,
            lowConfidenceReadings = label.lowConfidenceReadings - invalid,
            valuesWithoutDecimal = label.valuesWithoutDecimal - invalid,
        )
    }

    private fun NutritionLabel.sugarExceedsCarbs(): Boolean {
        val sugar = amounts[FoodMetric.SUGAR] ?: return false
        val carbs = amounts[FoodMetric.CARBS] ?: return false
        return sugar > carbs
    }

    private fun NutritionLabel.energyGap(): Double {
        val energy = FoodMetric.CALORIES
        val scanned = amounts[energy] ?: return Double.POSITIVE_INFINITY
        val macros =
            FoodMetric.energySources.associateWith { metric ->
                amounts[metric]
                    ?: if (metric == FoodMetric.FIBER) 0.0 else return Double.POSITIVE_INFINITY
            }
        return abs(scanned - energy.valueIn(macros))
    }

    private val commonFields =
        setOf(
            FoodMetric.CALORIES,
            FoodMetric.FAT,
            FoodMetric.CARBS,
            FoodMetric.SUGAR,
            FoodMetric.PROTEIN,
            FoodMetric.SALT,
        )

    private fun parse(text: Text): NutritionLabel {
        val lines = text.textBlocks.flatMap { it.lines }
        val slopes =
            lines
                .mapNotNull { line ->
                    val corners = line.cornerPoints ?: return@mapNotNull null
                    if (corners.size < 2 || line.text.length < 4) return@mapNotNull null
                    val dx = corners[1].x - corners[0].x
                    val dy = corners[1].y - corners[0].y
                    if (dx <= 0 || abs(dy) > dx * 0.5) null else dy.toDouble() / dx
                }
                .sorted()
        val slope = slopes.getOrNull(slopes.size / 2) ?: 0.0
        val words = lines.flatMap { line ->
            line.elements.mapNotNull { element ->
                element.boundingBox?.let {
                    LabelWord(
                        element.text,
                        it.left,
                        it.top,
                        it.right,
                        it.bottom,
                        element.confidence.takeIf { confidence -> confidence in 0f..1f },
                    )
                }
            }
        }
        return NutritionLabelParser.parse(words, slope)
    }

    private fun enhance(source: Bitmap, threshold: Boolean): Bitmap {
        val longSide = maxOf(source.width, source.height)
        val minimum = if (threshold) 950 else 1300
        val factor = (longSide.coerceIn(minimum, 1800).toDouble() / longSide).coerceAtMost(3.0)
        val stretch = if (threshold) 1.6 else 1.0
        val width = (source.width * factor * stretch).roundToInt().coerceIn(1, 2050)
        val height = (source.height * factor).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        if (scaled !== source) scaled.recycle()
        val histogram = IntArray(256)
        pixels.indices.forEach { index ->
            val rgb = pixels[index]
            val red = (rgb ushr 16) and 255
            val green = (rgb ushr 8) and 255
            val blue = rgb and 255
            val luminance = (77 * red + 150 * green + 29 * blue) shr 8
            histogram[luminance]++
            pixels[index] = luminance
        }

        fun percentile(count: Int): Int {
            var total = 0
            return histogram.indices.first {
                total += histogram[it]
                total > count
            }
        }

        val low = percentile(pixels.size / 100)
        val high = percentile(pixels.size - 1 - pixels.size / 100)
        val spread = (high - low).coerceAtLeast(1)
        val midpoint = if (threshold) splitContrast(histogram, pixels.size) else (low + high) / 2
        val invert = percentile(pixels.size / 2) < midpoint
        pixels.indices.forEach { index ->
            val normalized =
                if (threshold) {
                    ((pixels[index] - midpoint) * 8 + 128).coerceIn(0, 255)
                } else ((pixels[index] - low) * 255 / spread).coerceIn(0, 255)
            val gray = if (invert) 255 - normalized else normalized
            pixels[index] = 0xff000000.toInt() or (gray * 0x010101)
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun splitContrast(histogram: IntArray, size: Int): Int {
        val sum = histogram.indices.sumOf { it.toDouble() * histogram[it] }
        var darkerCount = 0
        var darkerSum = 0.0
        var bestSplit = 128
        var bestVariance = 0.0
        histogram.indices.forEach { gray ->
            darkerCount += histogram[gray]
            darkerSum += gray.toDouble() * histogram[gray]
            val lighterCount = size - darkerCount
            if (darkerCount > 0 && lighterCount > 0) {
                val difference = darkerSum / darkerCount - (sum - darkerSum) / lighterCount
                val variance = darkerCount.toDouble() * lighterCount * difference * difference
                if (variance > bestVariance) {
                    bestVariance = variance
                    bestSplit = gray
                }
            }
        }
        return bestSplit
    }
}
