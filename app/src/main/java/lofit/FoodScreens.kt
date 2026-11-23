package lofit

import android.app.DatePickerDialog
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

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
    private var statisticsPage = 0
    private var statisticsDay: LocalDate? = null

    fun saveState() =
        Bundle().apply {
            putString("query", query)
            putString("span", span.name)
            putStringArrayList("metrics", ArrayList(selected.map { it.name }))
            putInt("statisticsPage", statisticsPage)
            putString("statisticsDay", statisticsDay?.toString())
        }

    fun restoreState(state: Bundle) {
        query = state.getString("query").orEmpty()
        // LEGACY: Restored screens can contain a timeframe from the exercise graph.
        span = FoodSpan.entries.find { it.name == state.getString("span") } ?: FoodSpan.THREE_MONTHS
        selected = state.getStringArrayList("metrics")!!.map(FoodMetric::valueOf).toMutableSet()
        statisticsPage = state.getInt("statisticsPage")
        statisticsDay = state.getString("statisticsDay")?.let(LocalDate::parse)
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
                ui.actionButton("Log meal", action = ::pickMeal),
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

    private fun pickMeal() {
        val choices = store.mealChoices()
        val builder =
            AlertDialog.Builder(context).setTitle("Choose recipe").setNegativeButton("Cancel", null)
        if (choices.isEmpty()) {
            builder
                .setMessage("No recipes yet.")
                .setPositiveButton("Add recipe") { _, _ -> edit(FoodSection.RECIPES, null) }
                .show()
            return
        }

        val search = ui.textField("Search recipes")
        val empty =
            TextView(context).apply {
                text = "No matching recipes."
                textSize = 16f
                gravity = Gravity.CENTER
                setPadding(ui.dp(16), ui.dp(32), ui.dp(16), ui.dp(32))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
        val list = ListView(context)
        val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        var matches = choices
        val adapter =
            object : BaseAdapter() {
                override fun getCount() = matches.size

                override fun getItem(position: Int) = matches[position]

                override fun getItemId(position: Int) = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val choice = getItem(position)
                    val row =
                        convertView
                            ?: LayoutInflater.from(context)
                                .inflate(android.R.layout.simple_list_item_2, parent, false)
                    row.findViewById<TextView>(android.R.id.text1).text = choice.name
                    row.findViewById<TextView>(android.R.id.text2).apply {
                        visibility = if (choice.lastLogged == null) View.GONE else View.VISIBLE
                        text = choice.lastLogged?.let { "Last logged ${it.format(dateFormat)}" }
                    }
                    return row
                }
            }
        list.adapter = adapter
        list.emptyView = empty
        search.doAfterTextChanged {
            val query = SearchQuery(it.toString())
            matches = choices.filter { query.matches(it.name) }
            adapter.notifyDataSetChanged()
            list.setSelection(0)
        }
        val content =
            column().apply {
                isFocusableInTouchMode = true
                addView(
                    search,
                    ui.foodSpacing().apply {
                        marginStart = ui.dp(24)
                        marginEnd = ui.dp(24)
                    },
                )
                addView(
                    FrameLayout(context).apply {
                        addView(list, MATCH_PARENT, WRAP_CONTENT)
                        addView(empty, MATCH_PARENT, WRAP_CONTENT)
                    }
                )
                requestFocus()
            }
        val dialog = builder.setView(content).create()
        list.setOnItemClickListener { _, _, position, _ ->
            val recipeId = adapter.getItem(position).recipeId
            dialog.dismiss()
            edit(FoodSection.MEALS, recipeId)
        }
        dialog.show()
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
                addView(FoodSummary(context, ui).apply { update(totals) }.card(), ui.foodSpacing())
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
        ui.page("Statistics", scrollable = false) { body ->
            body.addView(
                ui.pagedCard(
                    "Nutrition statistics",
                    listOf("Daily totals" to ::dailyPage, "Trends" to ::trendsPage),
                    statisticsPage,
                    LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f),
                ) {
                    statisticsPage = it
                },
                LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = ui.dp(8) },
            )
        }

    private fun dailyPage() =
        ScrollView(context).apply {
            val today = LocalDate.now()
            val day = statisticsDay?.coerceAtMost(today) ?: today
            val title =
                if (day == today) "Today"
                else day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
            fun selectDay(date: LocalDate) {
                statisticsDay = date.takeUnless { it == today }
                refresh()
            }
            fun dayArrow(symbol: String, label: String, date: LocalDate) =
                ui.smallButton(symbol) { selectDay(date) }
                    .apply {
                        contentDescription = label
                        isEnabled = date <= today
                        textSize = 20f
                        setPadding(0, 0, 0, 0)
                    }
            addView(
                column().apply {
                    addView(ui.titleText("Daily totals"))
                    addView(
                        LinearLayout(context).apply {
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                ui.smallButton(title) {
                                    DatePickerDialog(
                                            context,
                                            { _, year, month, dayOfMonth ->
                                                selectDay(LocalDate.of(year, month + 1, dayOfMonth))
                                            },
                                            day.year,
                                            day.monthValue - 1,
                                            day.dayOfMonth,
                                        )
                                        .apply { datePicker.maxDate = System.currentTimeMillis() }
                                        .show()
                                },
                                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                            )
                            addView(
                                dayArrow("‹", "Previous day", day.minusDays(1)),
                                LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply {
                                    marginStart = ui.dp(8)
                                },
                            )
                            addView(
                                dayArrow("›", "Next day", day.plusDays(1)),
                                LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply {
                                    marginStart = ui.dp(8)
                                },
                            )
                        },
                        ui.foodSpacing(),
                    )
                    val totals = store.dailyTotals(day)
                    addView(
                        if (totals == null) ui.emptyText("No meals logged for this day.")
                        else FoodSummary(context, ui).apply { update(totals) }.view,
                        ui.foodSpacing(),
                    )
                }
            )
        }

    private fun trendsPage() =
        ScrollView(context).apply {
            addView(
                column().apply {
                    val today = LocalDate.now()
                    val totals = store.dailyTotals(span, today)
                    val start = span.firstDay(today) ?: totals.keys.firstOrNull() ?: today
                    addView(ui.titleText("Trends"))
                    addView(
                        ui.trendSpanSelector(FoodSpan.entries, span) {
                            span = it
                            refresh()
                        }
                    )
                    val plot = column()
                    addView(plot)
                    fun draw() {
                        plot.removeAllViews()
                        plot.addView(
                            when {
                                selected.isEmpty() -> ui.emptyText("Select a value to graph.")
                                totals.isEmpty() -> ui.emptyText("No meals in this span.")
                                else ->
                                    FoodGraphView(context, totals, start, today, selected.toSet())
                            }
                        )
                    }
                    FoodMetric.entries.chunked(2).forEach { pair ->
                        addView(
                            LinearLayout(context).apply {
                                for (metric in pair) {
                                    addView(
                                        CheckBox(context).apply {
                                            text = metric.label
                                            setTextColor(ui.color(R.color.cream))
                                            buttonTintList =
                                                ColorStateList.valueOf(metric.graphColor)
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
            )
        }
}
