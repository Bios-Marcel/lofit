package lofit

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class FoodStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.cacheDir, "food-test-${UUID.randomUUID()}.db")
    private val isolated =
        object : ContextWrapper(context) {
            override fun getDatabasePath(name: String): File = file
        }

    @After
    fun cleanUp() {
        SQLiteDatabase.deleteDatabase(file)
    }

    @Test
    fun savedIngredientCanBeReframedAfterUpgrade() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE ingredients (id TEXT PRIMARY KEY, name TEXT NOT NULL, image TEXT, WEIGHT REAL NOT NULL, PRICE REAL NOT NULL, PROTEIN REAL NOT NULL, FIBER REAL NOT NULL, CARBS REAL NOT NULL, SUGAR REAL NOT NULL, FAT REAL NOT NULL, SALT REAL NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO ingredients VALUES ('rice', 'Rice', 'rice.webp', 500, 2, 7, 1, 78, 1, 2, 0)"
            )
            db.version = 1
        }
        val region = Rect(7, 9, 39, 41)
        FoodStore(isolated).use { store ->
            val rice = store.ingredients().single()
            assertNull(rice.crop)
            assertEquals("rice.webp", rice.image)
            assertEquals(78.0, rice.product.getValue(FoodMetric.CARBS), 0.0)
            store.save(rice.copy(crop = region))
        }
        FoodStore(isolated).use { store -> assertEquals(region, store.ingredients().single().crop) }
    }

    @Test
    fun sumsCookedQuantitiesByDayAndFiltersSpanAcrossReloads() {
        val today = LocalDate.of(2026, 3, 31)
        val start = today.minusMonths(1)
        fun meal(id: String, date: LocalDate, quantity: Double) =
            CookedMeal(
                id,
                "Bowl",
                date,
                listOf(CookedItem("Rice", FoodMetric.inputs.associateWith { quantity })),
            )
        FoodStore(isolated).use {
            it.log(meal("old", start.minusDays(1), 50.0))
            it.log(meal("boundary", start, 10.0))
            for (daysAgo in listOf(6L, 7L, 13L, 14L)) {
                it.log(meal("day-$daysAgo", today.minusDays(daysAgo), 10.0))
            }
            repeat(2) { _ -> it.log(meal("lunch", today, 30.0)) }
            it.log(meal("dinner", today, 20.0))
        }
        FoodStore(isolated).use {
            val month = it.dailyTotals(FoodSpan.MONTH, today)
            assertEquals(
                listOf(start) + listOf(14L, 13L, 7L, 6L, 0L).map(today::minusDays),
                month.keys.toList(),
            )
            assertEquals(
                listOf(today.minusDays(6), today),
                it.dailyTotals(FoodSpan.SEVEN_DAYS, today).keys.toList(),
            )
            assertEquals(
                listOf(13L, 7L, 6L, 0L).map(today::minusDays),
                it.dailyTotals(FoodSpan.TWO_WEEKS, today).keys.toList(),
            )
            assertTrue(month.getValue(today).values.all { amount -> amount == 50.0 })
            assertEquals(7, it.dailyTotals(FoodSpan.ALL, today).size)
            it.deleteMeal("dinner")
            assertEquals(
                30.0,
                it.dailyTotals(FoodSpan.MONTH, today).getValue(today).getValue(FoodMetric.CARBS),
                0.0,
            )
        }
    }
}
