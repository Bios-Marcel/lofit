package lofit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/** Shared anatomy with muscle details merged into measurement groups. */
class BodyMeasurementMapView(
    context: Context,
    private val side: MuscleMapSide,
    gender: BiologicalGender,
    private val onSelect: (BodyPart, Side) -> Unit,
) : View(context) {
    private val anatomy = MuscleMapView.anatomy(context, side, gender)
    private val viewport = anatomy.viewport
    private val silhouette =
        Path(anatomy.outline).apply {
            // The source artwork stores the crown of the head separately from the body outline.
            anatomy.regions
                .filter { it.name in setOf("head", "hair") }
                .flatMap { it.paths }
                .forEach {
                    op(it, Path.Op.UNION)
                }
        }
    private val center = RectF().also { anatomy.outline.computeBounds(it, true) }.centerX()
    private val surface = ContextCompat.getColor(context, R.color.charcoal_light)
    private val sage = ContextCompat.getColor(context, R.color.sage)
    private val coral = ContextCompat.getColor(context, R.color.coral)
    private val bodyPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.blendARGB(surface, sage, .12f)
        }
    private val outlinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.blendARGB(surface, sage, .25f)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
    private val musclePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.blendARGB(surface, sage, .7f)
        }
    private val selectedPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.blendARGB(surface, coral, .7f)
        }

    private data class Target(
        val part: BodyPart,
        val bodySide: Side,
        val path: Path,
        val region: Region,
    )

    private val targets = buildTargets()
    private var pressed: Target? = null

    init {
        contentDescription =
            "${if (side == MuscleMapSide.FRONT) "Front" else "Back"} body measurements"
        // Standard controls below the model expose every measurement to assistive technology.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = true
    }

    private fun buildTargets(): List<Target> {
        val groups =
            linkedMapOf(
                BodyPart.NECK to setOf("neck"),
                BodyPart.SHOULDERS to setOf("deltoids", "trapezius"),
                BodyPart.CHEST to setOf("chest", "upper-back"),
                BodyPart.WAIST to setOf("abs", "obliques", "lower-back"),
                BodyPart.HIPS to setOf("gluteal"),
                BodyPart.UPPER_ARM to setOf("biceps", "triceps"),
                BodyPart.FOREARM to setOf("forearm"),
                BodyPart.THIGH to setOf("quadriceps", "adductors", "hamstring"),
                BodyPart.CALF to setOf("calves", "tibialis"),
            )
        val clip =
            Region(
                viewport.left.toInt(),
                viewport.top.toInt(),
                viewport.right.toInt(),
                viewport.bottom.toInt(),
            )
        return groups.flatMap { (part, names) ->
            val source = anatomy.regions.filter { it.name in names }.flatMap { it.paths }
            part.sides.mapNotNull { bodySide ->
                val half = RectF(viewport)
                if (bodySide != Side.BOTH) {
                    val onLeft = (bodySide == Side.RIGHT) == (side == MuscleMapSide.FRONT)
                    if (onLeft) half.right = center else half.left = center
                }
                val halfPath = Path().apply { addRect(half, Path.Direction.CW) }
                val path = Path()
                // Close fine seams while keeping the artwork's curved anatomical boundaries.
                val mergePaint =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL_AND_STROKE
                        strokeWidth = 7f
                        strokeJoin = Paint.Join.ROUND
                        strokeCap = Paint.Cap.ROUND
                    }
                source.forEach { original ->
                    val softened = Path()
                    mergePaint.getFillPath(original, softened)
                    path.op(softened, Path.Op.UNION)
                }
                path.op(anatomy.outline, Path.Op.INTERSECT)
                // Preserve the artwork at the silhouette edge, including thin arm muscles.
                source.forEach { path.op(it, Path.Op.UNION) }
                path.op(halfPath, Path.Op.INTERSECT)
                val region = Region().apply { setPath(path, clip) }
                if (region.isEmpty) null else Target(part, bodySide, path, region)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(160).toInt(), widthMeasureSpec),
            resolveSize(dp(340).toInt(), heightMeasureSpec),
        )
    }

    private fun scale() = min(width / viewport.width(), height / viewport.height())

    override fun onDraw(canvas: Canvas) {
        val scale = scale()
        if (scale <= 0) return
        canvas.save()
        canvas.translate(
            (width - viewport.width() * scale) / 2,
            (height - viewport.height() * scale) / 2,
        )
        canvas.scale(scale, scale)
        canvas.translate(-viewport.left, -viewport.top)
        canvas.drawPath(silhouette, bodyPaint)
        canvas.drawPath(silhouette, outlinePaint)
        targets.forEach { target ->
            canvas.drawPath(
                target.path,
                if (pressed == target) selectedPaint else musclePaint,
            )
        }
        canvas.restore()
    }

    internal fun targetAt(x: Float, y: Float): Pair<BodyPart, Side>? {
        val scale = scale()
        if (scale <= 0) return null
        val modelX = (x - (width - viewport.width() * scale) / 2) / scale + viewport.left
        val modelY = (y - (height - viewport.height() * scale) / 2) / scale + viewport.top
        return targets
            .lastOrNull { it.region.contains(modelX.toInt(), modelY.toInt()) }
            ?.let { it.part to it.bodySide }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = targetAt(event.x, event.y) ?: return false
                pressed = targets.first { it.part == hit.first && it.bodySide == hit.second }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                val hit = targetAt(event.x, event.y)
                val selected = pressed
                pressed = null
                invalidate()
                if (selected != null && hit == (selected.part to selected.bodySide)) {
                    performClick()
                    onSelect(selected.part, selected.bodySide)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
                return true
            }
        }
        return pressed != null
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun dp(value: Int) = value * resources.displayMetrics.density
}
