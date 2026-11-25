package lofit

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged

internal fun LofitViews.foodSpacing() =
    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(8)
        bottomMargin = dp(4)
    }

internal fun <T> AlertDialog.Builder.showFoodPicker(
    ui: LofitViews,
    choices: List<T>,
    nameOf: (T) -> String,
    searchHint: String,
    emptyMessage: String,
    rowView: (T, View?, ViewGroup) -> View,
    select: (T) -> Unit,
) {
    val search = ui.textField(searchHint)
    val empty =
        TextView(context).apply {
            text = emptyMessage
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(ui.dp(16), ui.dp(32), ui.dp(16), ui.dp(32))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    var matches = choices
    val adapter =
        object : BaseAdapter() {
            override fun getCount() = matches.size

            override fun getItem(position: Int) = matches[position]

            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                rowView(getItem(position), convertView, parent)
        }
    val list = ListView(context)
    list.adapter = adapter
    list.emptyView = empty
    search.doAfterTextChanged {
        val query = SearchQuery(it.toString())
        matches = choices.filter { query.matches(nameOf(it)) }
        adapter.notifyDataSetChanged()
        list.setSelection(0)
    }
    val content =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
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
    val dialog = setView(content).create()
    list.setOnItemClickListener { _, _, position, _ ->
        val choice = adapter.getItem(position)
        dialog.dismiss()
        select(choice)
    }
    dialog.show()
}

internal class FoodSummary(context: Context, private val ui: LofitViews) {
    private val values = mutableMapOf<FoodMetric, TextView>()
    val view =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            FoodMetric.entries.forEach { metric ->
                addView(
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            ui.metaText(metric.label),
                            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
                        )
                        val value = ui.titleText("").apply { textSize = 15f }
                        values[metric] = value
                        addView(value)
                    }
                )
            }
        }

    fun card() = ui.card {
        addView(ui.titleText("Totals"))
        addView(view)
    }

    fun update(amounts: FoodAmounts) {
        values.forEach { (metric, field) -> field.text = metric.format(metric.valueIn(amounts)) }
    }
}

internal fun ingredientPictures(context: Context) = ExercisePictures(context, "ingredient-pictures")

internal fun LofitViews.foodImage(
    context: Context,
    pictures: ExercisePictures,
    name: String,
    image: String?,
    size: Int,
    crop: Rect?,
) =
    ExerciseThumbnailView(context, pictures, name, image, null, dp(size), dp(size), crop).apply {
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(12) }
    }
