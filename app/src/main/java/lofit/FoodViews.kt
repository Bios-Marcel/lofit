package lofit

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

internal fun LofitViews.foodSpacing() =
    LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
        topMargin = dp(8)
        bottomMargin = dp(4)
    }

internal class FoodSummary(context: Context, ui: LofitViews) {
    private val values = mutableMapOf<FoodMetric, TextView>()
    val view = ui.card {
        addView(ui.titleText("Totals"))
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
