package lofit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal val FoodMetric.graphColor
    get() =
        Color.parseColor(
            when (this) {
                FoodMetric.CARBS -> "#E8755E"
                FoodMetric.WEIGHT -> "#E8DECE"
                FoodMetric.PRICE -> "#FFD54F"
                FoodMetric.PROTEIN -> "#8DC8FF"
                FoodMetric.FIBER -> "#8ED6A2"
                FoodMetric.FAT -> "#D9A1E3"
                FoodMetric.SUGAR -> "#4FD1CF"
                FoodMetric.SALT -> "#FDA9C4"
                FoodMetric.CALORIES -> "#FFAE63"
            }
        )

internal class FoodGraphView(
    context: Context,
    private val days: Map<LocalDate, FoodAmounts>,
    private val start: LocalDate,
    private val end: LocalDate,
    private val metrics: Set<FoodMetric>,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val muted = ContextCompat.getColor(context, R.color.sage)
    private val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
    private val date =
        DateTimeFormatter.ofPattern(
            if (end.toEpochDay() - start.toEpochDay() > 180) "MMM yy" else "d MMM"
        )

    init {
        contentDescription = "Daily food totals"
        stateDescription =
            days.entries.joinToString("; ") { (day, amounts) ->
                "$day · " + metrics.joinToString { "${it.label} ${it.format(it.valueIn(amounts))}" }
            }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(320).toInt(), widthMeasureSpec),
            resolveSize(dp(244).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val nutrients = metrics.filter { it != FoodMetric.PRICE }
        val showPrice = FoodMetric.PRICE in metrics
        val nutrientMax = maximum(nutrients)
        val priceMax = maximum(listOf(FoodMetric.PRICE))
        val leftMax = if (nutrients.isEmpty()) priceMax else nutrientMax
        val dualAxis = nutrients.isNotEmpty() && showPrice
        paint.textSize = dp(11)
        fun labelWidth(max: Double) =
            (0..4).maxOf { paint.measureText(number.format(max * (it / 4.0))) }
        val left = dp(10) + labelWidth(leftMax)
        val right = width - dp(10) - if (dualAxis) labelWidth(priceMax) else 0f
        val top = dp(29)
        val bottom = height - dp(27)
        if (right <= left || bottom <= top) return
        paint.color = muted
        paint.textAlign = Paint.Align.LEFT
        val unit =
            when {
                nutrients.isEmpty() -> "Price"
                FoodMetric.CALORIES !in nutrients -> "g"
                nutrients.size == 1 -> "kcal"
                else -> "g / kcal"
            }
        canvas.drawText(unit, left, dp(15), paint)
        if (dualAxis) {
            paint.color = FoodMetric.PRICE.graphColor
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Price", right, dp(15), paint)
        }
        for (index in 0..4) {
            val fraction = index / 4.0
            val y = bottom - (bottom - top) * fraction.toFloat()
            paint.color = muted
            paint.alpha = 60
            paint.strokeWidth = dp(1)
            canvas.drawLine(left, y, right, y, paint)
            paint.alpha = 255
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(number.format(leftMax * fraction), left - dp(5), y + dp(4), paint)
            if (dualAxis) {
                paint.color = FoodMetric.PRICE.graphColor
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText(number.format(priceMax * fraction), right + dp(5), y + dp(4), paint)
            }
        }
        paint.color = muted
        paint.textAlign = Paint.Align.LEFT
        canvas.drawText(date.format(start), left, bottom + dp(20), paint)
        if (start != end) {
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(date.format(end), right, bottom + dp(20), paint)
        }
        val span = end.toEpochDay() - start.toEpochDay()
        for (metric in metrics) {
            paint.color = metric.graphColor
            paint.strokeWidth = dp(2)
            var previousDay: LocalDate? = null
            var previousX = 0f
            var previousY = 0f
            for ((day, amounts) in days) {
                val x =
                    if (span == 0L) (left + right) / 2
                    else
                        left +
                            (right - left) *
                                ((day.toEpochDay() - start.toEpochDay()).toDouble() / span)
                                    .toFloat()
                val max = if (metric == FoodMetric.PRICE) priceMax else nutrientMax
                val y = bottom - (bottom - top) * (metric.valueIn(amounts) / max).toFloat()
                if (previousDay == day.minusDays(1))
                    canvas.drawLine(previousX, previousY, x, y, paint)
                canvas.drawCircle(x, y, dp(3.5f), paint)
                previousDay = day
                previousX = x
                previousY = y
            }
        }
    }

    private fun maximum(metrics: List<FoodMetric>) =
        days.values
            .maxOfOrNull { amounts -> metrics.maxOfOrNull { it.valueIn(amounts) } ?: 0.0 }
            ?.takeIf { it > 0 } ?: 1.0

    private fun dp(value: Number) = value.toFloat() * resources.displayMetrics.density
}
