package lofit

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import kotlin.math.abs
import kotlin.math.roundToInt

private const val DRAG_HOLD_MILLIS = 250L

/** Keeps the existing cards and their editors alive while changing exercise order. */
internal class ExerciseReorderController(
    private val cards: LinearLayout,
    private val onMove: (Int, Int) -> Unit,
) {
    private val handles = mutableMapOf<View, View>()
    private val density = cards.resources.displayMetrics.density

    init {
        cards.clipChildren = false
        cards.clipToPadding = false
    }

    @SuppressLint("ClickableViewAccessibility")
    fun bind(handle: View, card: View) {
        handles[card] = handle
        handle.setOnTouchListener(DragGesture(handle, card))
        updateActions()
    }

    fun remove(card: View) {
        handles.remove(card)
        cards.removeView(card)
        updateActions()
    }

    private fun move(card: View, to: Int) {
        val from = cards.indexOfChild(card)
        if (from < 0 || to !in 0 until cards.childCount || from == to) return
        onMove(from, to)
        cards.removeView(card)
        cards.addView(card, to)
        updateActions()
    }

    private fun updateActions() {
        handles.forEach { (card, handle) ->
            val index = cards.indexOfChild(card)
            handle.isEnabled = cards.childCount > 1
            handle.alpha = if (handle.isEnabled) 1f else 0.4f
            ViewCompat.removeAccessibilityAction(
                handle,
                AccessibilityActionCompat.ACTION_SCROLL_BACKWARD.id,
            )
            ViewCompat.removeAccessibilityAction(
                handle,
                AccessibilityActionCompat.ACTION_SCROLL_FORWARD.id,
            )
            if (index > 0) {
                ViewCompat.replaceAccessibilityAction(
                    handle,
                    AccessibilityActionCompat.ACTION_SCROLL_BACKWARD,
                    "Move up",
                ) { _, _ ->
                    move(card, cards.indexOfChild(card) - 1)
                    true
                }
            }
            if (index < cards.childCount - 1) {
                ViewCompat.replaceAccessibilityAction(
                    handle,
                    AccessibilityActionCompat.ACTION_SCROLL_FORWARD,
                    "Move down",
                ) { _, _ ->
                    move(card, cards.indexOfChild(card) + 1)
                    true
                }
            }
        }
    }

    private inner class DragGesture(private val handle: View, private val card: View) :
        View.OnTouchListener {
        private val touchSlop = ViewConfiguration.get(cards.context).scaledTouchSlop
        private var startY = 0f
        private var pointerY = 0f
        private var startScrollY = 0
        private var scroll: ScrollView? = null
        private var dragging = false
        private var autoScrollStarted = false
        private var tracking = false
        private var target = -1
        private var elevation = 0f

        private val startDrag = Runnable {
            if (!tracking || !handle.isAttachedToWindow) return@Runnable
            dragging = true
            card.elevation = elevation + 8 * density
            card.alpha = 0.9f
            cards.parent.requestDisallowInterceptTouchEvent(true)
            updatePreview()
        }

        private val autoScroll =
            object : Runnable {
                override fun run() {
                    if (!dragging || !handle.isAttachedToWindow) return
                    scroll?.let { viewport ->
                        val location = IntArray(2)
                        viewport.getLocationOnScreen(location)
                        val edge = 64 * density
                        val top = location[1] + viewport.paddingTop
                        val bottom = location[1] + viewport.height - viewport.paddingBottom
                        val speed =
                            when {
                                pointerY < top + edge ->
                                    -((top + edge - pointerY) / edge).coerceIn(0f, 1f)
                                pointerY > bottom - edge ->
                                    ((pointerY - bottom + edge) / edge).coerceIn(0f, 1f)
                                else -> 0f
                            }
                        viewport.scrollBy(
                            0,
                            (speed * (16f + 12f * abs(speed)) * density).roundToInt(),
                        )
                    }
                    updatePreview()
                    handle.postOnAnimation(this)
                }
            }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!handle.isEnabled) return false
                    tracking = true
                    startY = event.rawY
                    pointerY = startY
                    scroll =
                        generateSequence(cards.parent) { it.parent }
                            .filterIsInstance<ScrollView>()
                            .firstOrNull()
                    startScrollY = scroll?.scrollY ?: 0
                    target = cards.indexOfChild(card)
                    elevation = card.elevation
                    handle.isPressed = true
                    handle.postDelayed(startDrag, DRAG_HOLD_MILLIS)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!tracking) return true
                    pointerY = event.rawY
                    if (!dragging && abs(pointerY - startY) > touchSlop) {
                        finish(commit = false)
                    }
                    if (dragging) {
                        updatePreview()
                        if (!autoScrollStarted) {
                            autoScrollStarted = true
                            handle.postOnAnimation(autoScroll)
                        }
                    }
                }
                MotionEvent.ACTION_UP -> finish(commit = true)
                MotionEvent.ACTION_CANCEL,
                MotionEvent.ACTION_POINTER_DOWN -> finish(commit = false)
            }
            return true
        }

        private fun updatePreview() {
            val from = cards.indexOfChild(card)
            if (from < 0) return
            val offset = pointerY - startY + (scroll?.scrollY ?: 0) - startScrollY
            card.translationY = offset
            val center = card.top + card.height / 2f + card.translationY
            target = from
            for (index in 0 until cards.childCount) {
                val other = cards.getChildAt(index)
                val otherCenter = other.top + other.height / 2f
                if (index < from && center < otherCenter) {
                    target = index
                    break
                }
                if (index > from && center > otherCenter) target = index
            }
            val margins = card.layoutParams as ViewGroup.MarginLayoutParams
            val stride = (card.height + margins.topMargin + margins.bottomMargin).toFloat()
            for (index in 0 until cards.childCount) {
                if (index == from) continue
                cards.getChildAt(index).translationY =
                    when {
                        index in target until from -> stride
                        index in (from + 1)..target -> -stride
                        else -> 0f
                    }
            }
        }

        private fun finish(commit: Boolean) {
            if (!tracking) return
            tracking = false
            val shouldMove = dragging && commit
            dragging = false
            autoScrollStarted = false
            handle.removeCallbacks(startDrag)
            handle.removeCallbacks(autoScroll)
            handle.isPressed = false
            card.elevation = elevation
            card.alpha = 1f
            for (index in 0 until cards.childCount) cards.getChildAt(index).translationY = 0f
            cards.parent.requestDisallowInterceptTouchEvent(false)
            // Reparent only after release, so Android does not cancel the handle's gesture.
            if (shouldMove) move(card, target)
        }
    }
}
