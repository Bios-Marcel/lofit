package lofit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Decode only attached thumbnails, and discard callbacks from an earlier attachment. */
internal class ExerciseThumbnailView(
    context: Context,
    private val pictures: ExercisePictures,
    private val exerciseName: String,
    private val imageFile: String?,
    private val resource: Int?,
    private val targetWidth: Int,
    private val targetHeight: Int,
) : AppCompatImageView(context) {
    private companion object {
        val worker = Executors.newFixedThreadPool(2)
        val main = Handler(Looper.getMainLooper())
    }

    private var load: Future<*>? = null
    private var generation = 0
    private val initial = exerciseName.take(1).uppercase(Locale.getDefault())
    private val placeholder =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.cream)
            textSize =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 30f, resources.displayMetrics)
            textAlign = Paint.Align.CENTER
        }

    init {
        contentDescription = "$exerciseName illustration"
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(ContextCompat.getColor(context, R.color.coral_dark))
        pictures.cachedThumbnail(imageFile, resource, targetWidth, targetHeight)?.let {
            setImageBitmap(it)
            background = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (drawable != null) return
        val request = ++generation
        load = worker.submit {
            val bitmap = pictures.thumbnail(imageFile, resource, targetWidth, targetHeight)
            main.post {
                if (generation == request && isAttachedToWindow) {
                    if (bitmap != null) {
                        setImageBitmap(bitmap)
                        background = null
                    } else contentDescription = "$exerciseName placeholder image"
                    load = null
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        generation++
        load?.cancel(false)
        load = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drawable == null) {
            canvas.drawText(
                initial,
                width / 2f,
                (height - placeholder.ascent() - placeholder.descent()) / 2,
                placeholder,
            )
        }
    }
}
