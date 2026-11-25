package lofit

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Rect
import androidx.core.content.edit
import androidx.core.database.getDoubleOrNull
import androidx.core.database.sqlite.transaction
import java.time.LocalDate

/** Food history is independent of the frequently saved workout state. */
internal class FoodStore(context: Context) : SQLiteOpenHelper(context, "food.db", null, 5) {
    private val metrics = FoodMetric.inputs
    private val amountColumns = metrics.joinToString { "${it.name} REAL NOT NULL" }
    private val mealRecipeColumn = "recipe_id TEXT REFERENCES recipes(id) ON DELETE SET NULL"
    private val mealRecipeIndex = "CREATE INDEX meal_recipe_day ON meals(recipe_id, day)"
    private val mealItemColumn = "item_id TEXT REFERENCES ingredients(id) ON DELETE SET NULL"
    private val mealItemIndex = "CREATE INDEX meal_item_day ON meals(item_id, day)"
    private val goalPreferences by lazy {
        context.getSharedPreferences("food_goals", Context.MODE_PRIVATE)
    }

    fun goals() =
        FoodGoals(
            calories = goalPreferences.getInt("calories", 0).takeIf { it > 0 },
            protein = goalPreferences.getInt("protein", 0).takeIf { it > 0 },
            saltLimit = readLimit("salt_limit", FoodGoals.DEFAULT_SALT_LIMIT),
            sugarLimit = readLimit("sugar_limit", FoodGoals.DEFAULT_SUGAR_LIMIT),
        )

    private fun readLimit(key: String, default: Double): Double =
        goalPreferences.getString(key, null)?.foodNumber()?.takeIf { it > 0 } ?: default

    fun saveGoals(goals: FoodGoals) {
        goalPreferences.edit {
            putInt("calories", goals.calories ?: 0)
            putInt("protein", goals.protein ?: 0)
            putString("salt_limit", goals.saltLimit.toString())
            putString("sugar_limit", goals.sugarLimit.toString())
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ingredients (id TEXT PRIMARY KEY, name TEXT NOT NULL, image TEXT, $amountColumns, crop TEXT, default_portion_grams REAL)"
        )
        db.execSQL("CREATE TABLE recipes (id TEXT PRIMARY KEY, name TEXT NOT NULL)")
        db.execSQL(
            "CREATE TABLE recipe_items (recipe TEXT NOT NULL REFERENCES recipes(id) ON DELETE CASCADE, ingredient TEXT NOT NULL REFERENCES ingredients(id), grams REAL NOT NULL)"
        )
        db.execSQL("CREATE INDEX recipe_item_parent ON recipe_items(recipe)")
        db.execSQL(
            "CREATE TABLE meals (id TEXT PRIMARY KEY, name TEXT NOT NULL, day TEXT NOT NULL, $mealRecipeColumn, $mealItemColumn)"
        )
        // Store the actual quantities and cost at cooking time, including substitutions.
        db.execSQL(
            "CREATE TABLE meal_items (meal TEXT NOT NULL REFERENCES meals(id) ON DELETE CASCADE, name TEXT NOT NULL, $amountColumns)"
        )
        db.execSQL("CREATE INDEX meal_item_parent ON meal_items(meal)")
        db.execSQL("CREATE INDEX meal_day ON meals(day)")
        db.execSQL(mealRecipeIndex)
        db.execSQL(mealItemIndex)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // LEGACY: Version 1 ingredients need a crop column before the portion upgrade.
        if (oldVersion < 2) db.execSQL("ALTER TABLE ingredients ADD COLUMN crop TEXT")
        if (oldVersion < 3)
            db.execSQL("ALTER TABLE ingredients ADD COLUMN default_portion_grams REAL")
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE meals ADD COLUMN $mealRecipeColumn")
            // LEGACY: Meal snapshots stored only the name. Link exact, unambiguous matches.
            db.execSQL(
                """
                UPDATE meals SET recipe_id = (
                    SELECT MIN(id) FROM recipes WHERE recipes.name = meals.name HAVING COUNT(*) = 1
                )
                """
                    .trimIndent()
            )
            db.execSQL(mealRecipeIndex)
        }
        if (oldVersion < 5) {
            // LEGACY: Earlier meals were logged from recipes and have no single-item source.
            db.execSQL("ALTER TABLE meals ADD COLUMN $mealItemColumn")
            db.execSQL(mealItemIndex)
        }
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
                getDoubleOrNull(getColumnIndexOrThrow("default_portion_grams")),
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
                put("default_portion_grams", ingredient.defaultPortionGrams)
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

    fun mealChoices(): List<MealChoice> =
        rows(
            """
            WITH choices(recipe_id, item_id, name) AS (
                SELECT id, NULL, name FROM recipes
                UNION ALL
                SELECT NULL, id, name FROM ingredients
            )
            SELECT COALESCE(choices.recipe_id, choices.item_id), choices.name, latest.day,
                   choices.item_id IS NOT NULL
            FROM choices
            LEFT JOIN meals AS latest ON latest.rowid = (
                SELECT rowid FROM meals
                WHERE meals.recipe_id = choices.recipe_id OR meals.item_id = choices.item_id
                ORDER BY day DESC, rowid DESC LIMIT 1
            )
            ORDER BY latest.day DESC, latest.rowid DESC, choices.name COLLATE NOCASE,
                     choices.recipe_id, choices.item_id
            """
                .trimIndent()
        ) {
            MealChoice(
                getString(0),
                getString(1),
                getString(2)?.let(LocalDate::parse),
                getInt(3) != 0,
            )
        }

    fun log(meal: CookedMeal) {
        writableDatabase.transaction {
            val values =
                ContentValues().apply {
                    put("id", meal.id)
                    put("name", meal.name)
                    put("day", meal.day.toString())
                    put("recipe_id", meal.recipeId)
                    put("item_id", meal.itemId)
                }
            // A restored editor can finish saving the same meal again.
            if (insertWithOnConflict("meals", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L)
                return@transaction
            insertMealItems(meal.id, meal.items)
        }
    }

    fun updateMeal(id: String, name: String, day: LocalDate, items: List<CookedItem>): Boolean =
        writableDatabase.transaction {
            val values =
                ContentValues().apply {
                    put("name", name)
                    put("day", day.toString())
                }
            if (update("meals", values, "id = ?", arrayOf(id)) == 0) return@transaction false
            delete("meal_items", "meal = ?", arrayOf(id))
            insertMealItems(id, items)
            true
        }

    private fun SQLiteDatabase.insertMealItems(id: String, items: List<CookedItem>) {
        items.forEach { item ->
            insertOrThrow(
                "meal_items",
                null,
                item.amounts.values().apply {
                    put("meal", id)
                    put("name", item.name)
                },
            )
        }
    }

    fun meals(id: String? = null): List<CookedMeal> {
        val args = listOfNotNull(id).toTypedArray()
        val itemFilter = if (id == null) "" else "WHERE meal = ?"
        val mealFilter = if (id == null) "" else "WHERE id = ?"
        val items =
            rows("SELECT * FROM meal_items $itemFilter ORDER BY rowid", *args) {
                    getString(0) to CookedItem(getString(1), amounts())
                }
                .groupBy({ it.first }, { it.second })
        return rows(
            "SELECT id, name, day, recipe_id, item_id FROM meals $mealFilter ORDER BY day DESC, rowid DESC",
            *args,
        ) {
            CookedMeal(
                getString(0),
                getString(1),
                LocalDate.parse(getString(2)),
                items[getString(0)].orEmpty(),
                getString(3),
                getString(4),
            )
        }
    }

    fun deleteMeal(id: String) {
        writableDatabase.delete("meals", "id = ?", arrayOf(id))
    }

    fun dailyTotals(
        span: FoodSpan,
        today: LocalDate = LocalDate.now(),
    ): Map<LocalDate, FoodAmounts> = dailyTotalsBetween(span.firstDay(today), today)

    fun dailyTotals(day: LocalDate): FoodAmounts? = dailyTotalsBetween(day, day)[day]

    private fun dailyTotalsBetween(start: LocalDate?, end: LocalDate): Map<LocalDate, FoodAmounts> {
        val sums = metrics.joinToString { "SUM(${it.name}) AS ${it.name}" }
        return rows(
                "SELECT day, $sums FROM meals JOIN meal_items ON meal = id WHERE day BETWEEN ? AND ? GROUP BY day ORDER BY day",
                start?.toString().orEmpty(),
                end.toString(),
            ) {
                LocalDate.parse(getString(0)) to amounts()
            }
            .toMap()
    }
}
