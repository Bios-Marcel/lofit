package lofit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat

/** Fixed-height slots share a neutral image and borrow a live pager only while visible. */
internal class LazyMuscleMaps(
    private val context: Context,
    private val gender: BiologicalGender,
) : View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private val placeholder = MuscleMapPlaceholder(context, gender)
    private val slots = mutableListOf<Slot>()
    private val pool = ArrayDeque<MuscleTargetPager>()
    private val visibleRect = Rect()
    private lateinit var scroll: ScrollView
    private var observer: ViewTreeObserver? = null
    private var updatePending = false
    private val update = Runnable {
        updatePending = false
        if (scroll.isAttachedToWindow) updateVisibleSlots()
    }

    fun slot(workload: () -> MuscleWorkload): View = Slot(workload).also { slots.add(it) }

    fun attach(scroll: ScrollView) {
        this.scroll = scroll
        scroll.addOnAttachStateChangeListener(this)
        if (scroll.isAttachedToWindow) onViewAttachedToWindow(scroll)
    }

    override fun onViewAttachedToWindow(view: View) {
        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }

    override fun onViewDetachedFromWindow(view: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        view.removeCallbacks(update)
        updatePending = false
        slots.forEach(::release)
        pool.clear()
        placeholder.clear()
    }

    override fun onPreDraw(): Boolean {
        // Defer construction until after this frame, so the shared placeholder can draw first.
        if (!updatePending) {
            updatePending = true
            scroll.post(update)
        }
        return true
    }

    private fun updateVisibleSlots() {
        val visible = slots.filter { it.isShown && it.getLocalVisibleRect(visibleRect) }
        slots.filter { it.pager != null && it !in visible }.forEach(::release)
        visible.forEach { slot ->
            if (slot.pager == null) {
                val pager =
                    if (pool.isEmpty()) MuscleTargetPager(context, slot.workload, gender)
                    else pool.removeFirst().apply { bind(slot.workload) }
                pager.currentPage = slot.page
                slot.pager = pager
                slot.addView(
                    pager,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        }
    }

    private fun release(slot: Slot) {
        val pager = slot.pager ?: return
        slot.page = pager.currentPage
        slot.removeView(pager)
        slot.pager = null
        pool.addLast(pager)
        slot.invalidate()
    }

    private inner class Slot(workload: () -> MuscleWorkload) : FrameLayout(context) {
        val workload by lazy(workload)
        var pager: MuscleTargetPager? = null
        var page = 0

        init {
            setWillNotDraw(false)
            layoutParams =
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    MuscleTargetPager.height(context),
                )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (pager == null && width > 0) {
                canvas.drawBitmap(placeholder.bitmap(width), 0f, 0f, null)
            }
        }
    }
}

/** One bitmap per width, shared by every unloaded slot in this history screen. */
internal class MuscleMapPlaceholder(
    private val context: Context,
    private val gender: BiologicalGender,
) {
    private val bitmaps = mutableMapOf<Int, Bitmap>()

    fun bitmap(width: Int): Bitmap =
        bitmaps.getOrPut(width) {
            val height = MuscleTargetPager.height(context)
            val pageHeight = MuscleTargetPager.pageHeight(context)
            val density = context.resources.displayMetrics.density
            val maps = MuscleMapPair(context, MuscleWorkload(emptyList()), gender)
            maps.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pageHeight, View.MeasureSpec.EXACTLY),
            )
            maps.layout(0, 0, width, pageHeight)
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                bitmap.density = context.resources.displayMetrics.densityDpi
                val canvas = Canvas(bitmap)
                canvas.translate(0f, (10 * density).toInt().toFloat())
                maps.draw(canvas)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                listOf(R.color.coral, R.color.sage).forEachIndexed { index, color ->
                    paint.color = ContextCompat.getColor(context, color)
                    paint.alpha = if (index == 0) 255 else (255 * 0.4f).toInt()
                    canvas.drawCircle(
                        width / 2f + (if (index == 0) -24 else 24) * density,
                        pageHeight + 24 * density,
                        4 * density,
                        paint,
                    )
                }
            }
        }

    fun clear() = bitmaps.clear()
}
