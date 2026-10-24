package lofit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

class TrendGraphView(
    context: Context,
    val series: TrendSeries,
    val startedAt: Long,
    val endedAt: Long,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    private val sage = ContextCompat.getColor(context, R.color.sage)
    private val coral = ContextCompat.getColor(context, R.color.coral)
    private val dateFormat = DateTimeFormatter.ofPattern(
        if (endedAt - startedAt > 180L * 24 * 60 * 60 * 1000) "MMM yy" else "d MMM",
    ).withZone(ZoneId.systemDefault())

    init {
        contentDescription = "${series.metric.label} trend"
        stateDescription = series.points.joinToString("; ") {
            "${dateFormat.format(Instant.ofEpochMilli(it.completedAt))}: ${number.format(it.value)} ${series.metric.unit}"
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(320).toInt(), widthMeasureSpec), resolveSize(dp(240).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (series.points.isEmpty()) return
        val low = series.points.minOf { it.value }
        val high = series.points.maxOf { it.value }
        val padding = max((high - low) * 0.15, max(high * 0.05, 1.0))
        val minimum = max(0.0, low - padding)
        val maximum = high + padding
        paint.textSize = dp(11)
        paint.typeface = android.graphics.Typeface.DEFAULT
        val ticks = (0..4).map { minimum + (maximum - minimum) * it / 4 }
        val left = ticks.maxOf { paint.measureText(number.format(it)) } + dp(12)
        val right = width - dp(12)
        val top = dp(16)
        val bottom = height - dp(34)
        if (right <= left || bottom <= top) return
        fun x(time: Long): Float = left +
            ((time - startedAt).toDouble() / (endedAt - startedAt).coerceAtLeast(1)).toFloat() * (right - left)
        fun y(value: Double): Float = bottom - ((value - minimum) / (maximum - minimum)).toFloat() * (bottom - top)

        ticks.forEach { value ->
            paint.color = sage
            paint.alpha = 50
            paint.strokeWidth = dp(1)
            canvas.drawLine(left, y(value), right, y(value), paint)
            paint.alpha = 255
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(number.format(value), left - dp(8), y(value) + dp(4), paint)
        }
        (0..2).forEach { index ->
            val time = startedAt + (endedAt - startedAt) * index / 2
            paint.textAlign = when (index) {
                0 -> Paint.Align.LEFT
                2 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            canvas.drawText(dateFormat.format(Instant.ofEpochMilli(time)), x(time), bottom + dp(24), paint)
        }
        val path = Path()
        series.points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(x(point.completedAt), y(point.value)) else {
                path.lineTo(x(point.completedAt), y(series.points[index - 1].value))
                path.lineTo(x(point.completedAt), y(point.value))
            }
        }
        path.lineTo(right, y(series.points.last().value))
        paint.color = coral
        paint.strokeWidth = dp(2.5f)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        series.points.forEach { canvas.drawCircle(x(it.completedAt), y(it.value), dp(3.5f), paint) }
    }

    private fun dp(value: Number): Float = value.toFloat() * resources.displayMetrics.density
}
