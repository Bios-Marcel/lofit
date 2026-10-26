package lofit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager

/** Shared anatomy and target distribution pages for routines and logged workouts. */
class MuscleTargetPager(
    context: Context,
    workload: MuscleWorkload,
    gender: BiologicalGender,
) : LinearLayout(context) {
    private val cream = ContextCompat.getColor(context, R.color.cream)
    private val sage = ContextCompat.getColor(context, R.color.sage)
    private val coral = ContextCompat.getColor(context, R.color.coral)
    private val track = ContextCompat.getColor(context, R.color.charcoal_surface_high)

    init {
        orientation = VERTICAL
        setPadding(0, dp(10), 0, 0)
        val maps = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            listOf("Front" to MuscleMapSide.FRONT, "Back" to MuscleMapSide.BACK).forEach { (label, side) ->
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    addView(label(label, sage).apply { gravity = Gravity.CENTER })
                    addView(MuscleMapView(context, side, workload, gender),
                        LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
                }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
        }
        val percentages = workload.targetPercentages()
        val chart = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Muscle targeting graph"
            stateDescription = percentages.entries.joinToString(", ") { "${it.key} ${it.value}%" }
            setPadding(dp(8), 0, dp(8), 0)
            percentages.forEach { (name, percentage) ->
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(label(name, cream), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                        addView(label("$percentage%", coral))
                    })
                    addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                        max = 100
                        progress = percentage
                        progressTintList = ColorStateList.valueOf(coral)
                        progressBackgroundTintList = ColorStateList.valueOf(track)
                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LayoutParams(LayoutParams.MATCH_PARENT, dp(8)).apply { topMargin = dp(6) })
                }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            }
        }
        val pages = listOf(maps, chart)
        val pager = ViewPager(context).apply {
            contentDescription = "Muscle visualization pages"
            adapter = object : PagerAdapter() {
                override fun getCount() = pages.size
                override fun isViewFromObject(view: View, item: Any) = view === item
                override fun instantiateItem(container: ViewGroup, position: Int): Any = pages[position].also {
                    container.addView(it)
                }
                override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                    container.removeView(item as View)
                }
                override fun getPageTitle(position: Int) = if (position == 0) "Muscle maps" else "Muscle targeting"
            }
        }
        // Leave enough room for the anatomy and for larger system text.
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT,
            dp(264 + (28 * resources.configuration.fontScale).toInt())))
        val dots = listOf("Muscle maps", "Muscle targeting").mapIndexed { index, name ->
            FrameLayout(context).apply {
                contentDescription = "$name, page ${index + 1} of 2"
                isFocusable = true
                setOnClickListener { pager.setCurrentItem(index, true) }
                addView(View(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                }, FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER))
            }
        }
        fun selectPage(position: Int) {
            pager.stateDescription = "Page ${position + 1} of 2"
            dots.forEachIndexed { index, dot ->
                dot.isSelected = index == position
                (dot.getChildAt(0).background as GradientDrawable).setColor(if (dot.isSelected) coral else sage)
                dot.getChildAt(0).alpha = if (dot.isSelected) 1f else 0.4f
            }
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            dots.forEach { addView(it, LayoutParams(dp(48), dp(48))) }
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) = selectPage(position)
        })
        selectPage(0)
    }

    private fun label(value: String, color: Int) = TextView(context).apply {
        text = value
        textSize = 13f
        setTextColor(color)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
