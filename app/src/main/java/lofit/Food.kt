package lofit

import android.graphics.Rect
import java.text.NumberFormat
import java.time.LocalDate

internal enum class FoodSection(val label: String) {
    INGREDIENTS("Ingredients"),
    RECIPES("Recipes"),
    MEALS("Meals"),
    STATISTICS("Statistics"),
}

internal enum class FoodSpan(override val label: String, override val description: String) :
    GraphSpan {
    SEVEN_DAYS("7D", "7 days"),
    TWO_WEEKS("2W", "2 weeks"),
    MONTH("1M", "1 month"),
    THREE_MONTHS("3M", "3 months"),
    ALL("All", "All time");

    fun firstDay(today: LocalDate): LocalDate? =
        when (this) {
            SEVEN_DAYS -> today.minusDays(6)
            TWO_WEEKS -> today.minusDays(13)
            MONTH -> today.minusMonths(1)
            THREE_MONTHS -> today.minusMonths(3)
            ALL -> null
        }
}

internal enum class FoodMetric(
    val label: String,
    val ingredientLabel: String,
    val kcalPerGram: Int = 0,
) {
    WEIGHT("Weight", "Product weight (g)"),
    PRICE("Price", "Product price"),
    PROTEIN("Protein", "Protein per 100 g", 4),
    FIBER("Fiber", "Fiber per 100 g", 2),
    CARBS("Carbs", "Carbs per 100 g", 4),
    SUGAR("Sugar", "Of which sugars per 100 g"),
    FAT("Fat", "Fat per 100 g", 9),
    SALT("Salt", "Salt per 100 g"),
    CALORIES("Calories", "Estimated calories per 100 g");

    companion object {
        val inputs = entries.filter { it != CALORIES }
        val energySources = inputs.filter { it.kcalPerGram > 0 }
    }

    fun valueIn(amounts: FoodAmounts): Double =
        if (this == CALORIES) energySources.sumOf { amounts.getValue(it) * it.kcalPerGram }
        else amounts.getValue(this)

    fun validInput(number: Double) =
        number.isFinite() &&
            when (this) {
                WEIGHT -> number > 0
                PRICE -> number >= 0
                else -> number in 0.0..100.0
            }

    fun format(value: Double): String {
        val number =
            NumberFormat.getNumberInstance()
                .apply {
                    maximumFractionDigits = if (this@FoodMetric == CALORIES) 0 else 2
                    if (this@FoodMetric == PRICE) minimumFractionDigits = 2
                }
                .format(value)
        return when (this) {
            CALORIES -> "$number kcal"
            PRICE -> number
            else -> "$number g"
        }
    }
}

internal typealias FoodAmounts = Map<FoodMetric, Double>

internal data class Ingredient(
    val id: String,
    val name: String,
    val image: String?,
    val product: FoodAmounts,
    val crop: Rect? = null,
) {
    fun portion(grams: Double): FoodAmounts =
        FoodMetric.inputs.associateWith { metric ->
            when (metric) {
                FoodMetric.WEIGHT -> grams
                FoodMetric.PRICE ->
                    product.getValue(metric) * (grams / product.getValue(FoodMetric.WEIGHT))
                else -> (grams / 100) * product.getValue(metric)
            }
        }
}

internal data class RecipeItem(val ingredientId: String, val grams: Double)

internal data class FoodRecipe(val id: String, val name: String, val items: List<RecipeItem>)

internal data class CookedItem(val name: String, val amounts: FoodAmounts)

internal data class CookedMeal(
    val id: String,
    val name: String,
    val day: LocalDate,
    val items: List<CookedItem>,
)

internal fun Iterable<FoodAmounts>.foodTotals(): FoodAmounts =
    FoodMetric.inputs.associateWith { metric -> sumOf { it.getValue(metric) } }

internal fun String.foodNumber(): Double? =
    trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
