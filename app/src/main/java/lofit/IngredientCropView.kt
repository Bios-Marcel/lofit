package lofit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** Selection is in source pixels. The entire photo stays fitted in the view. */
internal class IngredientCropView(
    context: Context,
    private val changed: (Rect, Int) -> Unit,
) : View(context) {
    private var image: Bitmap? = null
    private val photo = RectF()
    private val region = Rect()
    private val frame = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val density = resources.displayMetrics.density
    private var scale = 1f

    private val dragStart = Rect()
    private var downX = 0f
    private var downY = 0f
    // The signs identify the grabbed corner. Zero means move the whole frame.
    private var cornerX = 0
    private var cornerY = 0

    private val pinch =
        ScaleGestureDetector(
                context,
                object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    private var size = 0f

                    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                        size = region.width().toFloat()
                        return true
                    }

                    override fun onScale(detector: ScaleGestureDetector): Boolean {
                        size = (size * detector.scaleFactor).coerceIn(1f, maxSide.toFloat())
                        setSize(size.roundToInt())
                        return true
                    }
                },
            )
            .apply { isQuickScaleEnabled = false }

    private val maxSide
        get() = image?.let { minOf(it.width, it.height) } ?: 1

    init {
        contentDescription = "Crop preview"
        isFocusable = true
    }

    fun setImage(bitmap: Bitmap, saved: Rect?) {
        image = bitmap
        fitPhoto()
        val size = saved?.width()?.coerceIn(1, maxSide) ?: maxSide
        select(
            saved?.left ?: (bitmap.width - size) / 2,
            saved?.top ?: (bitmap.height - size) / 2,
            size,
        )
    }

    fun setSize(size: Int) {
        val side = size.coerceIn(1, maxSide)
        select(region.centerX() - side / 2, region.centerY() - side / 2, side)
    }

    private fun select(x: Int, y: Int, side: Int) {
        val bitmap = image ?: return
        val left = x.coerceIn(0, bitmap.width - side)
        val top = y.coerceIn(0, bitmap.height - side)
        region.set(left, top, left + side, top + side)
        updateFrame()
        changed(Rect(region), maxSide)
        invalidate()
    }

    private fun fitPhoto() {
        val bitmap = image ?: return
        if (width == 0 || height == 0) return
        val inset = minOf(12 * density, minOf(width, height) / 4f)
        scale = minOf((width - 2 * inset) / bitmap.width, (height - 2 * inset) / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        photo.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        updateFrame()
    }

    private fun updateFrame() {
        frame.set(
            photo.left + region.left * scale,
            photo.top + region.top * scale,
            photo.left + region.right * scale,
            photo.top + region.bottom * scale,
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = fitPhoto()

    private fun startDrag(x: Float, y: Float, allowCorner: Boolean): Boolean {
        val sx = if (x < frame.centerX()) -1 else 1
        val sy = if (y < frame.centerY()) -1 else 1
        val dx = x - if (sx < 0) frame.left else frame.right
        val dy = y - if (sy < 0) frame.top else frame.bottom
        val hit = 24 * density
        val corner =
            allowCorner &&
                abs(dx) <= hit &&
                abs(dy) <= hit &&
                -sx * dx <= frame.width() / 3 &&
                -sy * dy <= frame.height() / 3
        cornerX = if (corner) sx else 0
        cornerY = if (corner) sy else 0
        if (allowCorner && !corner && !frame.contains(x, y)) {
            if (!photo.contains(x, y)) return false
            val side = region.width()
            select(
                ((x - photo.left) / scale - side / 2f).roundToInt(),
                ((y - photo.top) / scale - side / 2f).roundToInt(),
                side,
            )
        }
        downX = x
        downY = y
        dragStart.set(region)
        return true
    }

    private fun dragTo(x: Float, y: Float) {
        val bitmap = image ?: return
        val dx = (x - downX) / scale
        val dy = (y - downY) / scale
        if (cornerX == 0) {
            select(
                dragStart.left + dx.roundToInt(),
                dragStart.top + dy.roundToInt(),
                dragStart.width(),
            )
            return
        }
        val anchorX = if (cornerX < 0) dragStart.right else dragStart.left
        val anchorY = if (cornerY < 0) dragStart.bottom else dragStart.top
        val limit =
            minOf(
                if (cornerX < 0) anchorX else bitmap.width - anchorX,
                if (cornerY < 0) anchorY else bitmap.height - anchorY,
            )
        // Project movement onto the square's diagonal, keeping the opposite corner fixed.
        val side =
            (dragStart.width() + (dx * cornerX + dy * cornerY) / 2).roundToInt().coerceIn(1, limit)
        select(
            if (cornerX < 0) anchorX - side else anchorX,
            if (cornerY < 0) anchorY - side else anchorY,
            side,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (photo.isEmpty || region.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN && !startDrag(event.x, event.y, true))
            return false
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> if (event.pointerCount == 1) dragTo(event.x, event.y)
            MotionEvent.ACTION_POINTER_UP ->
                if (event.pointerCount == 2) {
                    val remaining = 1 - event.actionIndex
                    startDrag(event.getX(remaining), event.getY(remaining), false)
                }
        }
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val x = region.left
        val y = region.top
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> select(x - 1, y, region.width())
            KeyEvent.KEYCODE_DPAD_RIGHT -> select(x + 1, y, region.width())
            KeyEvent.KEYCODE_DPAD_UP -> select(x, y - 1, region.width())
            KeyEvent.KEYCODE_DPAD_DOWN -> select(x, y + 1, region.width())
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = image ?: return
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawBitmap(bitmap, null, photo, paint)
        canvas.save()
        canvas.clipRect(photo)
        canvas.clipOutRect(frame)
        canvas.drawColor(0x88000000.toInt())
        canvas.restore()

        paint.color = 0x99ffffff.toInt()
        paint.strokeWidth = density
        for (i in 1..2) {
            val offset = frame.width() * i / 3
            canvas.drawLine(
                frame.left + offset,
                frame.top,
                frame.left + offset,
                frame.bottom,
                paint,
            )
            canvas.drawLine(frame.left, frame.top + offset, frame.right, frame.top + offset, paint)
        }
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * density
        canvas.drawRect(frame, paint)
        paint.style = Paint.Style.FILL
        val radius = minOf(6 * density, frame.width() / 4)
        for (i in 0..3) {
            val x = if (i % 2 == 0) frame.left else frame.right
            val y = if (i < 2) frame.top else frame.bottom
            paint.color = Color.DKGRAY
            canvas.drawCircle(x, y, radius + density, paint)
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, radius, paint)
        }
    }
}
