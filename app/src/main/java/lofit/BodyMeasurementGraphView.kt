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

class BodyMeasurementGraphView(
    context: Context,
    val bodyPart: BodyPart,
    measurements: List<BodyMeasurement>,
) : View(context) {
    val measurements = measurements.forBodyPart(bodyPart)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val date = DateTimeFormatter.ofPattern("d MMM yy").withZone(ZoneId.systemDefault())
    private val sage = ContextCompat.getColor(context, R.color.sage)
    private val coral = ContextCompat.getColor(context, R.color.coral)
    private val tickFormat = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }

    init {
        contentDescription = "${bodyPart.label} measurements graph in centimeters"
        stateDescription =
            this.measurements.joinToString("; ") {
                "${date.format(Instant.ofEpochMilli(it.measuredAt))}, ${if (bodyPart.paired) "${it.side.label}: " else ""}${formatWeight(it.centimeters)} cm"
            }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(320).toInt(), widthMeasureSpec),
            resolveSize(dp(250).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (measurements.isEmpty()) return
        val first = measurements.first().measuredAt
        val last = measurements.last().measuredAt
        val timePadding = max((last - first) / 20, 12 * 60 * 60 * 1000L)
        val start = first - timePadding
        val end = last + timePadding
        val low = measurements.minOf { it.centimeters }
        val high = measurements.maxOf { it.centimeters }
        val valuePadding = max((high - low) * .15, 1.0)
        val minimum = max(0.0, low - valuePadding)
        val maximum = high + valuePadding
        paint.textSize = dp(11)
        val ticks = (0..4).map { minimum + (maximum - minimum) * it / 4 }
        val left = ticks.maxOf { paint.measureText(tickFormat.format(it)) } + dp(12)
        val right = width - dp(12)
        val top = dp(16)
        val bottom = height - dp(if (bodyPart.paired) 60 else 34)
        if (right <= left || bottom <= top) return
        fun x(time: Long) =
            left + ((time - start).toDouble() / (end - start)).toFloat() * (right - left)
        fun y(value: Double) =
            bottom - ((value - minimum) / (maximum - minimum)).toFloat() * (bottom - top)
        paint.style = Paint.Style.FILL
        ticks.forEach { value ->
            paint.color = sage
            paint.alpha = 45
            paint.strokeWidth = dp(1)
            canvas.drawLine(left, y(value), right, y(value), paint)
            paint.alpha = 255
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(tickFormat.format(value), left - dp(8), y(value) + dp(4), paint)
        }
        listOf(first, last).distinct().forEachIndexed { index, time ->
            paint.textAlign = if (index == 0) Paint.Align.LEFT else Paint.Align.RIGHT
            canvas.drawText(
                date.format(Instant.ofEpochMilli(time)),
                if (index == 0) left else right,
                bottom + dp(24),
                paint,
            )
        }
        bodyPart.sides.forEach { side ->
            val points = measurements.filter { it.side == side }
            if (points.isEmpty()) return@forEach
            paint.color = if (side == Side.RIGHT) sage else coral
            paint.strokeWidth = dp(2.5f)
            paint.style = Paint.Style.STROKE
            val line = Path()
            points.forEachIndexed { index, point ->
                if (index == 0) line.moveTo(x(point.measuredAt), y(point.centimeters))
                else line.lineTo(x(point.measuredAt), y(point.centimeters))
            }
            canvas.drawPath(line, paint)
            paint.style = Paint.Style.FILL
            points.forEach { canvas.drawCircle(x(it.measuredAt), y(it.centimeters), dp(4), paint) }
        }
        if (bodyPart.paired) {
            listOf(Side.LEFT, Side.RIGHT).forEachIndexed { index, side ->
                val position = left + index * (right - left) / 2
                paint.color = if (side == Side.RIGHT) sage else coral
                canvas.drawCircle(position + dp(4), height - dp(10), dp(4), paint)
                paint.textAlign = Paint.Align.LEFT
                canvas.drawText(side.label, position + dp(15), height - dp(6), paint)
            }
        }
    }

    private fun dp(value: Number) = value.toFloat() * resources.displayMetrics.density
}
