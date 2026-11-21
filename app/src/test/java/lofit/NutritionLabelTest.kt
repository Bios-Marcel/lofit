package lofit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLabelTest {
    @Test
    fun parsesThePer100GramColumnEvenWhenItIsSecond() {
        val label =
            NutritionLabelParser.parse(
                row(0, 5 to "Nährwerte", 240 to "pro Portion", 405 to "pro 100 g") +
                    row(35, 5 to "Brennwert", 212 to "700 kJ", 385 to "1400 kJ") +
                    row(60, 204 to "168 kcal", 375 to "336 kcal") +
                    row(85, 5 to "Fett", 227 to "2,4 g", 395 to "4,8 g", 500 to "7 %") +
                    row(110, 5 to "davon gesättigte Fettsäuren", 230 to "1 g", 401 to "2 g") +
                    row(135, 5 to "Kohlenhydrate", 218 to "29 g", 395 to "58 g") +
                    row(160, 5 to "davon Zucker", 228 to "3 g", 399 to "6 g") +
                    row(185, 5 to "Ballaststoffe", 228 to "4 g", 399 to "8 g") +
                    row(210, 5 to "Eiweiß", 221 to "6 g", 395 to "12 g") +
                    row(235, 5 to "Salz", 215 to "0,13 g", 380 to "0,26 g")
            )
        assertEquals(
            mapOf(
                FoodMetric.CALORIES to 336.0,
                FoodMetric.FAT to 4.8,
                FoodMetric.CARBS to 58.0,
                FoodMetric.SUGAR to 6.0,
                FoodMetric.FIBER to 8.0,
                FoodMetric.PROTEIN to 12.0,
                FoodMetric.SALT to 0.26,
            ),
            label.amounts,
        )
    }

    @Test
    fun leavesLessThanValuesAndMissingGramCellsBlank() {
        val label =
            NutritionLabelParser.parse(
                row(0, 230 to "je 100g", 405 to "pro Portion") +
                    row(30, 5 to "Fett", 400 to "2 g") +
                    row(60, 5 to "Zucker", 217 to "< 0,1 g", 402 to "0 g") +
                    row(90, 5 to "Eiweiß", 235 to "9 g", 402 to "3 g")
            )
        assertEquals(mapOf(FoodMetric.PROTEIN to 9.0), label.amounts)
    }

    @Test
    fun declinesValuesOnlyGivenPer100Millilitres() {
        val label =
            NutritionLabelParser.parse(
                row(0, 230 to "100 ml") + row(30, 5 to "Zucker", 234 to "9,5 g")
            )
        assertTrue(label.volumeOnly)
        assertTrue(label.amounts.isEmpty())
    }

    @Test
    fun readsTiltedTableWithSeparatedColumnsAndMisreadGramUnits() {
        val words =
            row(0, 5 to "Nährwertinformation", 360 to "1 Portion") +
                row(24, 5 to "100g enthalten:") +
                row(54, 5 to "Brennwert kJ/kcal", 180 to "1390/332", 345 to "695/166") +
                row(80, 5 to "Fett", 200 to "12 q", 380 to "6 g") +
                row(106, 5 to "Kohlenhydrate", 205 to "42g", 375 to "21g") +
                row(132, 5 to "- davon Zucker", 185 to "12 9", 365 to "6g") +
                row(158, 5 to "Ballaststoffe", 192 to "7,39", 365 to "3,65g") +
                row(184, 5 to "Eiweiß", 198 to "18g", 375 to "9g")
        val tilted = words.map { word ->
            val shift = (word.centerX * 0.15).toInt()
            word.copy(top = word.top + shift, bottom = word.bottom + shift)
        }
        val label = NutritionLabelParser.parse(tilted, slope = 0.15)
        assertEquals(
            mapOf(
                FoodMetric.CALORIES to 332.0,
                FoodMetric.FAT to 12.0,
                FoodMetric.CARBS to 42.0,
                FoodMetric.SUGAR to 12.0,
                FoodMetric.FIBER to 7.3,
                FoodMetric.PROTEIN to 18.0,
            ),
            label.amounts,
        )
        assertEquals(setOf(FoodMetric.FIBER), label.weakReadings)
    }

    @Test
    fun checksCaloriesWithinOneAndRequiresTheMainMacros() {
        val amounts =
            mapOf(
                FoodMetric.CALORIES to 100.0,
                FoodMetric.PROTEIN to 10.0,
                FoodMetric.CARBS to 10.0,
                FoodMetric.FAT to 2.0,
            )
        val label = NutritionLabel(amounts)
        assertEquals(true, label.caloriesMatch(99.0))
        assertEquals(true, label.caloriesMatch(101.0))
        assertEquals(false, label.caloriesMatch(98.99))
        assertEquals(false, label.caloriesMatch(101.01))
        assertNull(NutritionLabel(amounts - FoodMetric.FAT).caloriesMatch(100.0))
        assertNull(NutritionLabel(amounts - FoodMetric.CALORIES).caloriesMatch(100.0))
    }

    private fun row(top: Int, vararg cells: Pair<Int, String>): List<LabelWord> =
        cells.flatMap { (left, text) ->
            var x = left
            text.split(' ').map { word ->
                val next = x + word.length * 7
                LabelWord(word, x, top, next, top + 16).also { x = next + 5 }
            }
        }
}
