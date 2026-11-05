package lofit

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.core.content.ContextCompat

/** Keeps a graph in the viewport until the bottom of its exercise pushes it out. */
internal class StickyExerciseGraph(private val graph: View, private val exercise: ViewGroup) :
    View.OnAttachStateChangeListener, ViewTreeObserver.OnPreDrawListener {
    private var scroll: ScrollView? = null
    private var observer: ViewTreeObserver? = null
    private val exerciseLocation = IntArray(2)
    private val scrollLocation = IntArray(2)

    init {
        graph.setBackgroundColor(ContextCompat.getColor(graph.context, R.color.charcoal_light))
        graph.elevation = graph.resources.displayMetrics.density
        // Consume taps over covered set controls; the parent still handles scrolling.
        graph.isClickable = true
        graph.addOnAttachStateChangeListener(this)
        if (graph.isAttachedToWindow) onViewAttachedToWindow(graph)
    }

    override fun onViewAttachedToWindow(view: View) {
        scroll =
            generateSequence(graph.parent) { it.parent }
                .filterIsInstance<ScrollView>()
                .firstOrNull()
        observer = view.viewTreeObserver.also { it.addOnPreDrawListener(this) }
    }

    override fun onViewDetachedFromWindow(view: View) {
        observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
        observer = null
        scroll = null
        graph.translationY = 0f
    }

    override fun onPreDraw(): Boolean {
        val viewport = scroll ?: return true
        viewport.getLocationInWindow(scrollLocation)
        exercise.getLocationInWindow(exerciseLocation)
        val offset = scrollLocation[1] + viewport.paddingTop - exerciseLocation[1] - graph.top
        val maximum = (exercise.height - exercise.paddingBottom - graph.bottom).coerceAtLeast(0)
        graph.translationY = offset.coerceIn(0, maximum).toFloat()
        return true
    }
}
