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
    fun savedIngredientCanBeReframedAcrossReloads() {
        val rice = Ingredient("rice", "Rice", "rice.webp", FoodMetric.inputs.associateWith { 1.0 })
        val region = Rect(7, 9, 39, 41)
        FoodStore(isolated).use { store -> store.save(rice) }
        FoodStore(isolated).use { store ->
            val saved = store.ingredients().single()
            assertEquals(rice, saved)
            store.save(saved.copy(crop = region))
        }
        FoodStore(isolated).use { store ->
            assertEquals(rice.copy(crop = region), store.ingredients().single())
        }
    }

    @Test
    fun olderSchemaIsRejectedWithoutChangingStoredMeals() {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE meals (id TEXT PRIMARY KEY, name TEXT NOT NULL, day TEXT NOT NULL)"
            )
            db.execSQL("INSERT INTO meals VALUES ('old-soup', 'Soup', '2026-03-30')")
            db.version = 3
        }
        FoodStore(isolated).use { store ->
            val failure = assertThrows(IllegalStateException::class.java) { store.meals() }
            assertEquals("Unsupported food database schema: 3", failure.message)
        }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(3, db.version)
            db.rawQuery("SELECT id, name, day FROM meals", null).use { meals ->
                assertTrue(meals.moveToFirst())
                assertEquals("old-soup", meals.getString(0))
                assertEquals("Soup", meals.getString(1))
                assertEquals("2026-03-30", meals.getString(2))
                assertFalse(meals.moveToNext())
            }
        }
    }

    @Test
    fun versionFiveMealsKeepSingleItemModeWhenSourceIsDeleted() {
        val day = LocalDate.of(2026, 3, 31)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE ingredients (id TEXT PRIMARY KEY)")
            db.execSQL(
                "CREATE TABLE meals (id TEXT PRIMARY KEY, name TEXT NOT NULL, day TEXT NOT NULL, recipe_id TEXT, item_id TEXT REFERENCES ingredients(id) ON DELETE SET NULL)"
            )
            db.execSQL("CREATE TABLE meal_items (meal TEXT, name TEXT)")
            db.execSQL("INSERT INTO ingredients VALUES ('rice')")
            db.execSQL("INSERT INTO meals VALUES ('lunch', 'Rice', '$day', NULL, 'rice')")
            db.execSQL("INSERT INTO meals VALUES ('dinner', 'Bowl', '$day', 'bowl', NULL)")
            db.version = 5
        }
        FoodStore(isolated).use { store ->
            store.writableDatabase.delete("ingredients", "id = ?", arrayOf("rice"))
            assertEquals(
                CookedMeal("lunch", "Rice", day, emptyList(), singleItem = true),
                store.meals("lunch").single(),
            )
            assertEquals(
                CookedMeal("dinner", "Bowl", day, emptyList(), recipeId = "bowl"),
                store.meals("dinner").single(),
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
                    MealChoice(bowl.id, bowl.name, today, isItem = false),
                    MealChoice(soup.id, "Tomato soup", today, isItem = false),
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
