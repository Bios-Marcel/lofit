package lofit

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import org.json.JSONObject
import kotlin.math.min

enum class MuscleMapSide { FRONT, BACK }

/** Native, scalable anatomy. Artwork attribution: assets/MUSCLE_MAP_NOTICE.txt. */
class MuscleMapView(
    context: Context,
    private val side: MuscleMapSide,
    muscleGroups: Set<String>,
) : View(context) {
    private val activeGroups = muscleGroups.mapNotNull(MuscleGroups::canonical).toSet()
    private val anatomy = anatomy(context, side)
    private val surface = ContextCompat.getColor(context, R.color.charcoal_light)
    private val sage = ContextCompat.getColor(context, R.color.sage)
    private val coral = ContextCompat.getColor(context, R.color.coral)
    private val cream = ContextCompat.getColor(context, R.color.cream)
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.blendARGB(surface, sage, 0.10f)
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.blendARGB(surface, sage, 0.45f)
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    private val separationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = surface
        strokeWidth = 1.4f
        strokeJoin = Paint.Join.ROUND
        style = Paint.Style.STROKE
    }
    // Parse geometry once per side; prepare shading once per card, never while scrolling.
    private val muscles = anatomy.regions.flatMap { region ->
        val active = groupsFor(region.name, side).any(activeGroups::contains)
        val base = if (active) coral else ColorUtils.blendARGB(surface, sage, 0.48f)
        val light = ColorUtils.blendARGB(base, if (active) cream else sage, 0.20f)
        val shadow = ColorUtils.blendARGB(base, surface, 0.28f)
        region.paths.map { path ->
            val bounds = RectF().also { path.computeBounds(it, true) }
            ShadedPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    bounds.left, bounds.top, bounds.right, bounds.bottom,
                    intArrayOf(shadow, light, base, shadow),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP,
                )
            })
        }
    }

    init {
        contentDescription = if (side == MuscleMapSide.FRONT) "Front muscle map" else "Back muscle map"
        val visibleGroups = anatomy.regions.flatMap { groupsFor(it.name, side) }.toSet()
        val highlighted = activeGroups.filter(visibleGroups::contains).sorted()
        stateDescription = highlighted.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "No highlighted muscles"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(148), widthMeasureSpec),
            resolveSize(dp(264), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewport = anatomy.viewport
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).toFloat()
        val scale = min(availableWidth / viewport.width(), availableHeight / viewport.height())
        if (scale <= 0f) return
        canvas.save()
        canvas.translate(
            paddingLeft + (availableWidth - viewport.width() * scale) / 2f,
            paddingTop + (availableHeight - viewport.height() * scale) / 2f,
        )
        canvas.scale(scale, scale)
        canvas.translate(-viewport.left, -viewport.top)
        canvas.drawPath(anatomy.outline, bodyPaint)
        canvas.drawPath(anatomy.outline, outlinePaint)
        muscles.forEach { muscle ->
            canvas.drawPath(muscle.path, muscle.paint)
            canvas.drawPath(muscle.path, separationPaint)
        }
        canvas.restore()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class Region(val name: String, val paths: List<Path>)
    private data class Anatomy(val viewport: RectF, val outline: Path, val regions: List<Region>)
    private data class ShadedPath(val path: Path, val paint: Paint)

    private companion object {
        private val cache = mutableMapOf<MuscleMapSide, Anatomy>()

        @Synchronized
        fun anatomy(context: Context, side: MuscleMapSide): Anatomy = cache.getOrPut(side) {
            val resource = if (side == MuscleMapSide.FRONT) R.raw.muscle_map_front else R.raw.muscle_map_back
            val json = context.resources.openRawResource(resource).bufferedReader().use { JSONObject(it.readText()) }
            val viewport = json.getJSONArray("viewport")
            val x = viewport.getDouble(0).toFloat()
            val y = viewport.getDouble(1).toFloat()
            val regions = json.getJSONArray("regions")
            Anatomy(
                RectF(x, y, x + viewport.getDouble(2).toFloat(), y + viewport.getDouble(3).toFloat()),
                requireNotNull(PathParser.createPathFromPathData(json.getString("outline"))),
                List(regions.length()) { index ->
                    val region = regions.getJSONObject(index)
                    val paths = region.getJSONArray("paths")
                    Region(region.getString("name"), List(paths.length()) { pathIndex ->
                        requireNotNull(PathParser.createPathFromPathData(paths.getString(pathIndex)))
                    })
                },
            )
        }

        fun groupsFor(region: String, side: MuscleMapSide): Set<String> = when (region) {
            "chest" -> setOf("Chest", "Upper chest")
            "abs" -> setOf("Abdominals", "Core")
            "obliques" -> setOf("Obliques", "Core", "Serratus anterior")
            "biceps" -> setOf("Biceps", "Brachialis")
            "triceps" -> setOf("Triceps")
            "neck" -> setOf("Neck")
            "trapezius" -> setOf("Traps", "Upper back", "Back")
            "deltoids" -> setOf(
                "Shoulders", "Lateral deltoids", "Rotator cuff",
                if (side == MuscleMapSide.FRONT) "Front deltoids" else "Rear deltoids",
            )
            "upper-back" -> setOf("Back", "Upper back", "Middle back", "Lats", "Rhomboids")
            "lower-back" -> setOf("Back", "Lower back", "Erector spinae", "Core", "Posterior chain")
            "forearm" -> setOf("Forearms", "Brachioradialis", "Grip")
            "hands" -> setOf("Grip")
            "gluteal" -> setOf("Glutes", "Abductors", "Posterior chain")
            "adductors" -> if (side == MuscleMapSide.FRONT) setOf("Adductors", "Hip flexors") else setOf("Adductors")
            "quadriceps" -> setOf("Quadriceps")
            "hamstring" -> setOf("Hamstrings", "Posterior chain")
            "tibialis" -> setOf("Tibialis anterior")
            "calves" -> setOf("Calves", "Gastrocnemius", "Soleus", "Posterior chain")
            "feet" -> setOf("Feet")
            else -> emptySet()
        }
    }
}
