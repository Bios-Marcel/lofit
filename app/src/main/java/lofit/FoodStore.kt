package lofit

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Rect
import androidx.core.database.sqlite.transaction
import java.time.LocalDate

/** Food history is independent of the frequently saved workout state. */
internal class FoodStore(context: Context) : SQLiteOpenHelper(context, "food.db", null, 2) {
    private val metrics = FoodMetric.inputs
    private val amountColumns = metrics.joinToString { "${it.name} REAL NOT NULL" }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ingredients (id TEXT PRIMARY KEY, name TEXT NOT NULL, image TEXT, $amountColumns, crop TEXT)"
        )
        db.execSQL("CREATE TABLE recipes (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE recipe_items (recipe TEXT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE, ingredient TEXT NOT NULL REFERENCES ingredients(id), grams REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX recipe_item_parent ON recipe_items(recipe)")
        db.execSQL(
            "CREATE TABLE meals (id TEXT PRIMARY KEY, name TEXT NOT NULL, day TEXT NOT NULL)"
        )
        // Store the actual quantities and cost at cooking time, including substitutions.
        db.execSQL(
            "CREATE TABLE meal_items (meal TEXT NOT NULL REFERENCES meals(id) ON DELETE CASCADE, name TEXT NOT NULL, $amountColumns)"
        )
        db.execSQL("CREATE INDEX meal_item_parent ON meal_items(meal)")
        db.execSQL("CREATE INDEX meal_day ON meals(day)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2)
            db.execSQL("ALTER TABLE ingredients ADD COLUMN crop TEXT")
        else error("Unsupported food database upgrade $oldVersion to $newVersion")
    }

    private fun <T> rows(sql: String, vararg args: String, read: Cursor.() -> T): List<T> =
        readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.read()) }
        }

    private fun Cursor.amounts(): FoodAmounts = metrics.associateWith {
        getDouble(getColumnIndexOrThrow(it.name))
    }

    fun ingredients(): List<Ingredient> =
        rows("SELECT * FROM ingredients ORDER BY name COLLATE NOCASE, id") {
            Ingredient(
                getString(0),
                getString(1),
                getString(2),
                amounts(),
                getString(getColumnIndexOrThrow("crop"))?.let(Rect::unflattenFromString),
            )
        }

    private fun FoodAmounts.values() =
        ContentValues().also { values ->
            metrics.forEach { values.put(it.name, getValue(it)) }
        }

    fun save(ingredient: Ingredient) {
        val values =
            ingredient.product.values().apply {
                put("id", ingredient.id)
                put("name", ingredient.name)
                put("image", ingredient.image)
                put("crop", ingredient.crop?.flattenToString())
            }
        writableDatabase.transaction {
            if (update("ingredients", values, "id = ?", arrayOf(ingredient.id)) == 0)
                insertOrThrow("ingredients", null, values)
        }
    }

    fun recipes(): List<FoodRecipe> {
        val items =
            rows("SELECT recipe, ingredient, grams FROM recipe_items ORDER BY rowid") {
                    getString(0) to RecipeItem(getString(1), getDouble(2))
                }
                .groupBy({ it.first }, { it.second })
        return rows("SELECT id, name FROM recipes ORDER BY name COLLATE NOCASE, id") {
            FoodRecipe(getString(0), getString(1), items[getString(0)].orEmpty())
        }
    }

    fun save(recipe: FoodRecipe) {
        writableDatabase.transaction {
            val values =
                ContentValues().apply {
                    put("id", recipe.id)
                    put("name", recipe.name)
                }
            if (update("recipes", values, "id = ?", arrayOf(recipe.id)) == 0)
                insertOrThrow("recipes", null, values)
            delete("recipe_items", "recipe = ?", arrayOf(recipe.id))
            recipe.items.forEach {
                execSQL(
                    "INSERT INTO recipe_items(recipe, ingredient, grams) VALUES (?, ?, ?)",
                    arrayOf<Any>(recipe.id, it.ingredientId, it.grams),
                )
            }
        }
    }

    fun log(meal: CookedMeal) {
        writableDatabase.transaction {
            val values =
                ContentValues().apply {
                    put("id", meal.id)
                    put("name", meal.name)
                    put("day", meal.day.toString())
                }
            // A restored editor can finish saving the same meal again.
            if (insertWithOnConflict("meals", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L)
                return@transaction
            meal.items.forEach { item ->
                insertOrThrow(
                    "meal_items",
                    null,
                    item.amounts.values().apply {
                        put("meal", meal.id)
                        put("name", item.name)
                    },
                )
            }
        }
    }

    fun meals(): List<CookedMeal> {
        val items =
            rows("SELECT * FROM meal_items ORDER BY rowid") {
                    getString(0) to CookedItem(getString(1), amounts())
                }
                .groupBy({ it.first }, { it.second })
        return rows("SELECT id, name, day FROM meals ORDER BY day DESC, rowid DESC") {
            CookedMeal(
                getString(0),
                getString(1),
                LocalDate.parse(getString(2)),
                items[getString(0)].orEmpty(),
            )
        }
    }

    fun deleteMeal(id: String) {
        writableDatabase.delete("meals", "id = ?", arrayOf(id))
    }

    fun dailyTotals(
        span: FoodSpan,
        today: LocalDate = LocalDate.now(),
    ): Map<LocalDate, FoodAmounts> {
        val sums = metrics.joinToString { "SUM(${it.name}) AS ${it.name}" }
        val start = span.firstDay(today)?.toString().orEmpty()
        return rows(
                "SELECT day, $sums FROM meals JOIN meal_items ON meal = id WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day",
                start,
                today.toString(),
            ) {
                LocalDate.parse(getString(0)) to amounts()
            }
            .toMap()
    }
}
