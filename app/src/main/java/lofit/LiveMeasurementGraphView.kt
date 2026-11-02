package lofit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import kotlin.math.ceil
import kotlin.math.max

internal class LiveMeasurementGraphView(
    context: Context,
    private val now: () -> Long = SystemClock::elapsedRealtime,
    val series: LiveMeasurementSeries = LiveMeasurementSeries(),
    private val targetWeightKg: () -> Double? = { null },
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1 }
    private val sage = ContextCompat.getColor(context, R.color.sage)
    private val coral = ContextCompat.getColor(context, R.color.coral)
    private val targetGreen = ContextCompat.getColor(context, R.color.target_green)
    private val redraw =
        object : Runnable {
            override fun run() {
                invalidate()
                postDelayed(this, 250)
            }
        }

    init {
        contentDescription = "Live weight graph"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun addReading(kilograms: Double) {
        series.add(now(), kilograms)
        showReading(kilograms)
    }

    fun showReading(kilograms: Double) {
        stateDescription = "${formatBodyWeight(kilograms)} kg · last 60 seconds"
        postInvalidateOnAnimation()
    }

    fun endSegment() {
        series.endSegment()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateRedraw()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(redraw)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRedraw()
    }

    private fun updateRedraw() {
        removeCallbacks(redraw)
        if (isAttachedToWindow && windowVisibility == VISIBLE) post(redraw)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(320).toInt(), widthMeasureSpec),
            resolveSize(dp(240).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val time = now()
        series.trim(time)
        val samples = series.samples
        val target = targetWeightKg()?.takeIf { it.isFinite() && it >= 0 }
        val peak = max(samples.maxOfOrNull { it.kilograms } ?: 0.0, target ?: 0.0)
        val maximum = max(5.0, ceil(peak * 1.1 / 5) * 5)
        paint.style = Paint.Style.FILL
        paint.color = sage
        paint.alpha = 255
        paint.textSize = dp(11)
        val ticks = (0..4).map { maximum * it / 4 }
        val left = ticks.maxOf { paint.measureText(number.format(it)) } + dp(12)
        val right = width - dp(12)
        val top = dp(28)
        val bottom = height - dp(32)
        if (right <= left || bottom <= top) return
        fun x(atMillis: Long): Float =
            left +
                ((atMillis - (time - LiveMeasurementSeries.WINDOW_MILLIS)).toDouble() /
                        LiveMeasurementSeries.WINDOW_MILLIS)
                    .toFloat() * (right - left)
        fun y(kilograms: Double): Float = bottom - (kilograms / maximum).toFloat() * (bottom - top)

        paint.textAlign = Paint.Align.LEFT
        canvas.drawText("kg", left, dp(14), paint)
        ticks.forEach { kilograms ->
            paint.alpha = 50
            paint.strokeWidth = dp(1)
            canvas.drawLine(left, y(kilograms), right, y(kilograms), paint)
            paint.alpha = 255
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(number.format(kilograms), left - dp(8), y(kilograms) + dp(4), paint)
        }
        (0..2).forEach { index ->
            paint.textAlign =
                when (index) {
                    0 -> Paint.Align.LEFT
                    2 -> Paint.Align.RIGHT
                    else -> Paint.Align.CENTER
                }
            val label = if (index == 2) "0 s" else "−${60 - index * 30} s"
            canvas.drawText(label, left + (right - left) * index / 2, bottom + dp(24), paint)
        }

        target?.let { kilograms ->
            paint.color = targetGreen
            paint.strokeWidth = dp(2)
            canvas.drawLine(left, y(kilograms), right, y(kilograms), paint)
        }

        path.reset()
        samples.forEachIndexed { index, sample ->
            if (index == 0 || sample.startsSegment)
                path.moveTo(x(sample.atMillis), y(sample.kilograms))
            else path.lineTo(x(sample.atMillis), y(sample.kilograms))
        }
        paint.color = coral
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2.5f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        samples.forEachIndexed { index, sample ->
            if (index == 0 || sample.startsSegment || index == samples.lastIndex)
                canvas.drawCircle(x(sample.atMillis), y(sample.kilograms), dp(3), paint)
        }
    }

    private fun dp(value: Number): Float = value.toFloat() * resources.displayMetrics.density
}
