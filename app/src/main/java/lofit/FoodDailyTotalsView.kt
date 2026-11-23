package lofit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.math.RoundingMode
import java.text.NumberFormat
import kotlin.math.roundToInt

internal class FoodDailyTotalsView(
    context: Context,
    private val ui: LofitViews,
    totals: FoodAmounts?,
    goals: FoodGoals,
) : LinearLayout(context) {
    private val surface = ui.color(R.color.charcoal_light)
    private val percent =
        NumberFormat.getPercentInstance().apply { roundingMode = RoundingMode.DOWN }

    init {
        orientation = VERTICAL
        val calories = FoodMetric.CALORIES
        val protein = FoodMetric.PROTEIN
        val salt = FoodMetric.SALT
        val amounts = totals ?: emptyList<FoodAmounts>().foodTotals()
        if (totals == null) {
            addView(ui.metaText("No meals logged for this day."))
        }
        addTileRow(
            listOf(
                gauge(calories, calories.valueIn(amounts), goals.calories),
                gauge(protein, protein.valueIn(amounts), goals.protein),
            )
        )
        addTileRow(listOf(saltGauge(salt.valueIn(amounts), goals.saltLimit)))
        addView(ui.sectionTitle("Other totals"))
        FoodMetric.entries
            .filter { it != calories && it != protein && it != salt }
            .chunked(2)
            .forEach { row ->
                addTileRow(
                    row.map { metric ->
                        tile(metric).apply {
                            addView(metricLabel(metric))
                            addView(ui.titleText(metric.format(metric.valueIn(amounts))))
                        }
                    }
                )
            }
    }

    private fun addTileRow(tiles: List<View>) {
        addView(
            LinearLayout(context).apply {
                isBaselineAligned = false
                tiles.forEachIndexed { index, tile ->
                    addView(
                        tile,
                        LayoutParams(0, MATCH_PARENT, 1f).apply {
                            if (index > 0) marginStart = ui.dp(8)
                        },
                    )
                }
            },
            LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = ui.dp(8) },
        )
    }

    private fun tile(metric: FoodMetric) =
        LinearLayout(context).apply {
            orientation = VERTICAL
            background =
                GradientDrawable().apply {
                    cornerRadius = ui.dp(12).toFloat()
                    setColor(ColorUtils.blendARGB(surface, metric.graphColor, 0.10f))
                }
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
        }

    private fun metricLabel(metric: FoodMetric) =
        ui.metaText(metric.label).apply { setTextColor(metric.graphColor) }

    private fun trackColor(metric: FoodMetric) =
        ColorUtils.blendARGB(surface, metric.graphColor, 0.28f)

    private fun gauge(metric: FoodMetric, amount: Double, goal: Int?) =
        tile(metric).apply {
            val progress = goal?.let { amount / it }
            gravity = Gravity.CENTER_HORIZONTAL
            addView(metricLabel(metric).apply { gravity = Gravity.CENTER })
            addView(
                FrameLayout(context).apply {
                    addView(
                        FoodGoalArcView(
                            context,
                            metric.graphColor,
                            trackColor(metric),
                            ui.dp(8).toFloat(),
                            (progress ?: 0.0).coerceIn(0.0, 1.0).toFloat(),
                        ),
                        MATCH_PARENT,
                        MATCH_PARENT,
                    )
                    if (progress != null) {
                        val status = if (progress > 1) "Over goal" else percent.format(progress)
                        addView(
                            ui.metaText(status).apply {
                                gravity = Gravity.CENTER
                                setTextColor(metric.graphColor)
                            },
                            FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM),
                        )
                    }
                },
                LayoutParams(MATCH_PARENT, ui.dp(66)).apply { topMargin = ui.dp(4) },
            )
            addView(ui.titleText(metric.format(amount)).apply { gravity = Gravity.CENTER })
            addView(
                ui.metaText(goal?.let { "Goal ${metric.format(it.toDouble())}" } ?: "No goal")
                    .apply {
                        gravity = Gravity.CENTER
                    }
            )
        }

    private fun saltGauge(amount: Double, limit: Double): View {
        val metric = FoodMetric.SALT
        val fraction = amount / limit
        val over = fraction > 1
        val accent = if (over) ui.color(R.color.coral) else metric.graphColor
        fun line(start: View, end: View) =
            LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                isBaselineAligned = false
                addView(start, LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(8) })
                addView(end)
            }

        return tile(metric).apply {
            addView(line(metricLabel(metric), ui.titleText(metric.format(amount))))
            addView(
                LinearProgressIndicator(context).apply {
                    isIndeterminate = false
                    trackThickness = ui.dp(8)
                    trackCornerRadius = ui.dp(4)
                    trackColor = trackColor(metric)
                    setIndicatorColor(accent)
                    max = 1000
                    progress = (fraction.coerceIn(0.0, 1.0) * max).roundToInt()
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                },
                LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = ui.dp(6)
                    bottomMargin = ui.dp(4)
                },
            )
            addView(
                line(
                    ui.metaText("Limit ${formatWeight(limit)} g"),
                    ui.metaText(if (over) "Over limit" else percent.format(fraction)).apply {
                        setTextColor(accent)
                    },
                )
            )
        }
    }
}

private class FoodGoalArcView(
    context: Context,
    private val accent: Int,
    private val track: Int,
    stroke: Float,
    private val progress: Float,
) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val y = height - paint.strokeWidth / 2f
        val radius = minOf(x - paint.strokeWidth / 2f, height - paint.strokeWidth)
        if (radius <= 0) return
        paint.color = track
        canvas.drawArc(x - radius, y - radius, x + radius, y + radius, 180f, 180f, false, paint)
        if (progress > 0) {
            paint.color = accent
            canvas.drawArc(
                x - radius,
                y - radius,
                x + radius,
                y + radius,
                180f,
                180f * progress,
                false,
                paint,
            )
        }
    }
}
