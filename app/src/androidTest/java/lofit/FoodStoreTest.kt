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

    private fun createLegacyMealTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE recipes (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE meals (id TEXT PRIMARY KEY, name TEXT NOT NULL, day TEXT NOT NULL)"
        )
    }

    @Test
    fun savedIngredientCanBeReframedAfterUpgrade() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createLegacyMealTables(db)
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
            assertNull(rice.defaultPortionGrams)
            assertEquals("rice.webp", rice.image)
            assertEquals(78.0, rice.product.getValue(FoodMetric.CARBS), 0.0)
            store.save(rice.copy(crop = region))
        }
        FoodStore(isolated).use { store -> assertEquals(region, store.ingredients().single().crop) }
    }

    @Test
    fun upgradeLinksOnlyUnambiguousMealNames() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            createLegacyMealTables(db)
            db.execSQL(
                "INSERT INTO recipes VALUES ('soup', 'Soup'), ('bowl-1', 'Bowl'), ('bowl-2', 'Bowl')"
            )
            db.execSQL(
                "INSERT INTO meals VALUES ('old-soup', 'Soup', '2026-03-30'), ('old-bowl', 'Bowl', '2026-03-31')"
            )
            db.version = 3
        }
        FoodStore(isolated).use { store ->
            assertEquals(
                listOf(
                    MealChoice("soup", "Soup", LocalDate.of(2026, 3, 30)),
                    MealChoice("bowl-1", "Bowl", null),
                    MealChoice("bowl-2", "Bowl", null),
                ),
                store.mealChoices(),
            )
        }
    }

    @Test
    fun mealRecencyFollowsRecipeIdAndLatestMealDate() {
        val today = LocalDate.of(2026, 3, 31)
        val soup = FoodRecipe("soup", "Soup", emptyList())
        val bowl = FoodRecipe("bowl", "Bowl", emptyList())
        FoodStore(isolated).use { store ->
            store.save(soup)
            store.save(bowl)
            store.log(CookedMeal("lunch", soup.name, today, emptyList(), soup.id))
            store.log(CookedMeal("dinner", bowl.name, today, emptyList(), bowl.id))
            store.log(CookedMeal("earlier", soup.name, today.minusDays(1), emptyList(), soup.id))
            store.save(soup.copy(name = "Tomato soup"))
            assertEquals(
                listOf(
                    MealChoice(bowl.id, bowl.name, today),
                    MealChoice(soup.id, "Tomato soup", today),
                ),
                store.mealChoices(),
            )
            store.deleteMeal("lunch")
            assertEquals(today.minusDays(1), store.mealChoices().last().lastLogged)
        }
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
