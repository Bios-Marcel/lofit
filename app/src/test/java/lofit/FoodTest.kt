package lofit

import org.junit.Assert.assertEquals
import org.junit.Test

class FoodTest {
    @Test
    fun portionsScalePriceByPackAndEachNutrientByProductWeight() {
        val label =
            mapOf(
                FoodMetric.WEIGHT to 500.0,
                FoodMetric.PRICE to 4.0,
                FoodMetric.PROTEIN to 20.0,
                FoodMetric.CARBS to 50.0,
                FoodMetric.SUGAR to 10.0,
                FoodMetric.FAT to 2.0,
                FoodMetric.SALT to 0.25,
                FoodMetric.FIBER to 8.0,
            )
        val ingredient = Ingredient("oats", "Oats", null, label)
        val amounts = listOf(ingredient.portion(25.0), ingredient.portion(100.0)).foodTotals()
        assertEquals(314.0, FoodMetric.CALORIES.valueIn(label), 0.0)
        assertEquals(392.5, FoodMetric.CALORIES.valueIn(amounts), 0.0)
        assertEquals(
            mapOf(
                FoodMetric.WEIGHT to 125.0,
                FoodMetric.PRICE to 1.0,
                FoodMetric.PROTEIN to 25.0,
                FoodMetric.CARBS to 62.5,
                FoodMetric.SUGAR to 12.5,
                FoodMetric.FAT to 2.5,
                FoodMetric.SALT to 0.3125,
                FoodMetric.FIBER to 10.0,
            ),
            amounts,
        )
    }
}
