package lofit

import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import java.time.LocalDate

internal class FoodScreens(
    private val context: Context,
    private val ui: LofitViews,
    private val store: FoodStore,
    private val edit: (FoodSection, String?) -> Unit,
    private val refresh: () -> Unit,
) {
    private val pictures = ingredientPictures(context)
    private var query = ""
    private var span = FoodSpan.THREE_MONTHS
    private var selected = mutableSetOf(FoodMetric.CARBS)

    fun saveState() =
        Bundle().apply {
            putString("query", query)
            putString("span", span.name)
            putStringArrayList("metrics", ArrayList(selected.map { it.name }))
        }

    fun restoreState(state: Bundle) {
        query = state.getString("query").orEmpty()
        // LEGACY: Restored screens can contain a timeframe from the exercise graph.
        span = FoodSpan.entries.find { it.name == state.getString("span") } ?: FoodSpan.THREE_MONTHS
        selected = state.getStringArrayList("metrics")!!.map(FoodMetric::valueOf).toMutableSet()
    }

    fun page(section: FoodSection): View =
        when (section) {
            FoodSection.INGREDIENTS -> ingredients()
            FoodSection.RECIPES -> recipes()
            FoodSection.MEALS -> meals()
            FoodSection.STATISTICS -> statistics()
        }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun ingredients() =
        ui.page("Ingredients") { body ->
            val items = store.ingredients()
            body.addView(
                ui.actionButton("Add ingredient") { edit(FoodSection.INGREDIENTS, null) },
                ui.foodSpacing(),
            )
            val search = ui.textField("Search ingredients").apply { setText(query) }
            body.addView(search, ui.foodSpacing())
            val results = column()
            body.addView(results, ui.foodSpacing())
            fun filter() {
                results.removeAllViews()
                val searchQuery = SearchQuery(query)
                val matches = items.filter { searchQuery.matches(it.name) }
                if (matches.isEmpty())
                    results.addView(
                        ui.emptyText(
                            if (query.isBlank()) "No ingredients yet."
                            else "No matching ingredients."
                        )
                    )
                matches.forEach { item ->
                    results.addView(
                        ui.card {
                                addView(
                                    LinearLayout(context).apply {
                                        gravity = Gravity.CENTER_VERTICAL
                                        addView(
                                            ui.foodImage(
                                                context,
                                                pictures,
                                                item.name,
                                                item.image,
                                                54,
                                                item.crop,
                                            )
                                        )
                                        addView(
                                            column().apply {
                                                addView(ui.titleText(item.name))
                                                addView(
                                                    ui.metaText(
                                                        foodDetails(item.product, per100g = true)
                                                    )
                                                )
                                            }
                                        )
                                    }
                                )
                            }
                            .apply { setOnClickListener { edit(FoodSection.INGREDIENTS, item.id) } }
                    )
                }
            }
            search.doAfterTextChanged {
                query = it.toString()
                filter()
            }
            filter()
        }

    private fun foodDetails(amounts: FoodAmounts, per100g: Boolean = false): String {
        val energy = FoodMetric.CALORIES.format(FoodMetric.CALORIES.valueIn(amounts))
        val calories = if (per100g) "$energy / 100 g" else energy
        return "${FoodMetric.WEIGHT.format(amounts.getValue(FoodMetric.WEIGHT))}  ·  $calories  ·  Price ${FoodMetric.PRICE.format(amounts.getValue(FoodMetric.PRICE))}"
    }

    private fun recipes() =
        ui.page("Recipes") { body ->
            val ingredients = store.ingredients().associateBy { it.id }
            val recipes = store.recipes()
            body.addView(
                ui.actionButton("Add recipe") { edit(FoodSection.RECIPES, null) },
                ui.foodSpacing(),
            )
            if (recipes.isEmpty()) body.addView(ui.emptyText("No recipes yet."))
            recipes.forEach { recipe ->
                val totals =
                    recipe.items
                        .map { ingredients.getValue(it.ingredientId).portion(it.grams) }
                        .foodTotals()
                body.addView(
                    ui.card {
                            addView(ui.titleText(recipe.name))
                            addView(ui.metaText(foodDetails(totals)))
                        }
                        .apply { setOnClickListener { edit(FoodSection.RECIPES, recipe.id) } }
                )
            }
        }

    private fun meals() =
        ui.page("Meals") { body ->
            val meals = store.meals()
            body.addView(
                ui.actionButton("Log meal") {
                    val choices = store.recipes()
                    val dialog = AlertDialog.Builder(context).setTitle("Choose recipe")
                    if (choices.isEmpty())
                        dialog.setMessage("Add a recipe first.").setPositiveButton("OK", null)
                    else
                        dialog
                            .setItems(choices.map { it.name }.toTypedArray()) { _, index ->
                                edit(FoodSection.MEALS, choices[index].id)
                            }
                            .setNegativeButton("Cancel", null)
                    dialog.show()
                },
                ui.foodSpacing(),
            )
            if (meals.isEmpty()) body.addView(ui.emptyText("No meals logged."))
            meals
                .groupBy { it.day }
                .forEach { (day, entries) ->
                    body.addView(ui.sectionTitle(day.toString()))
                    entries.forEach { meal ->
                        val totals = meal.items.map { it.amounts }.foodTotals()
                        body.addView(
                            ui.card {
                                    addView(ui.titleText(meal.name))
                                    addView(ui.metaText(foodDetails(totals)))
                                }
                                .apply { setOnClickListener { showMeal(meal, totals) } }
                        )
                    }
                }
        }

    private fun showMeal(meal: CookedMeal, totals: FoodAmounts) {
        val details =
            column().apply {
                setPadding(ui.dp(20), ui.dp(10), ui.dp(20), 0)
                addView(ui.metaText(meal.day.toString()))
                meal.items.forEach {
                    addView(
                        ui.metaText(
                            "${it.name}  ·  ${FoodMetric.WEIGHT.format(it.amounts.getValue(FoodMetric.WEIGHT))}"
                        )
                    )
                }
                addView(FoodSummary(context, ui).apply { update(totals) }.view, ui.foodSpacing())
            }
        AlertDialog.Builder(context)
            .setTitle(meal.name)
            .setView(ScrollView(context).apply { addView(details) })
            .setPositiveButton("Done", null)
            .setNeutralButton("Delete") { _, _ ->
                AlertDialog.Builder(context)
                    .setTitle("Delete this meal?")
                    .setMessage(meal.name)
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        store.deleteMeal(meal.id)
                        refresh()
                    }
                    .show()
            }
            .show()
    }

    private fun statistics() =
        ui.page("Statistics") { body ->
            val today = LocalDate.now()
            val totals = store.dailyTotals(span, today)
            val start = span.firstDay(today) ?: totals.keys.firstOrNull() ?: today
            body.addView(
                ui.trendSpanSelector(FoodSpan.entries, span) {
                    span = it
                    refresh()
                }
            )
            val plot = column()
            body.addView(plot)
            fun draw() {
                plot.removeAllViews()
                plot.addView(
                    when {
                        selected.isEmpty() -> ui.emptyText("Select a value to graph.")
                        totals.isEmpty() -> ui.emptyText("No meals in this span.")
                        else -> FoodGraphView(context, totals, start, today, selected.toSet())
                    }
                )
            }
            FoodMetric.entries.chunked(2).forEach { pair ->
                body.addView(
                    LinearLayout(context).apply {
                        for (metric in pair) {
                            addView(
                                CheckBox(context).apply {
                                    text = metric.label
                                    setTextColor(ui.color(R.color.cream))
                                    buttonTintList = ColorStateList.valueOf(metric.graphColor)
                                    minHeight = ui.dp(48)
                                    isChecked = metric in selected
                                    setOnCheckedChangeListener { _, checked ->
                                        if (checked) selected.add(metric)
                                        else selected.remove(metric)
                                        draw()
                                    }
                                },
                                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                            )
                        }
                    }
                )
            }
            draw()
        }
}
