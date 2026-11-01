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
    private val maps = MuscleMapPair(context, workload, gender)
    private val chart: LinearLayout
    private val percentages = mutableListOf<TextView>()
    private val bars = mutableListOf<ProgressBar>()
    private val pager: ViewPager

    var currentPage: Int
        get() = pager.currentItem
        set(value) {
            pager.setCurrentItem(value, false)
        }

    init {
        orientation = VERTICAL
        setPadding(0, dp(10), 0, 0)
        chart =
            LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                contentDescription = "Muscle targeting graph"
                setPadding(dp(8), 0, dp(8), 0)
                workload.targetPercentages().forEach { (name, _) ->
                    addView(
                        LinearLayout(context).apply {
                            orientation = VERTICAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(
                                LinearLayout(context).apply {
                                    gravity = Gravity.CENTER_VERTICAL
                                    addView(
                                        label(name, cream),
                                        LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
                                    )
                                    addView(label("", coral).also { percentages.add(it) })
                                }
                            )
                            addView(
                                ProgressBar(
                                        context,
                                        null,
                                        android.R.attr.progressBarStyleHorizontal,
                                    )
                                    .apply {
                                        max = 100
                                        progressTintList = ColorStateList.valueOf(coral)
                                        progressBackgroundTintList = ColorStateList.valueOf(track)
                                        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                                        bars.add(this)
                                    },
                                LayoutParams(LayoutParams.MATCH_PARENT, dp(8)).apply {
                                    topMargin = dp(6)
                                },
                            )
                        },
                        LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
                    )
                }
            }
        val pages = listOf(maps, chart)
        pager =
            ViewPager(context).apply {
                contentDescription = "Muscle visualization pages"
                adapter =
                    object : PagerAdapter() {
                        override fun getCount() = pages.size

                        override fun isViewFromObject(view: View, item: Any) = view === item

                        override fun instantiateItem(container: ViewGroup, position: Int): Any =
                            pages[position].also {
                                container.addView(it)
                            }

                        override fun destroyItem(container: ViewGroup, position: Int, item: Any) {
                            container.removeView(item as View)
                        }

                        override fun getPageTitle(position: Int) =
                            if (position == 0) "Muscle maps" else "Muscle targeting"
                    }
            }
        // Leave enough room for the anatomy and for larger system text.
        addView(
            pager,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                pageHeight(context),
            ),
        )
        val dots =
            listOf("Muscle maps", "Muscle targeting").mapIndexed { index, name ->
                FrameLayout(context).apply {
                    contentDescription = "$name, page ${index + 1} of 2"
                    isFocusable = true
                    setOnClickListener { pager.setCurrentItem(index, true) }
                    addView(
                        View(context).apply {
                            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                        },
                        FrameLayout.LayoutParams(dp(8), dp(8), Gravity.CENTER),
                    )
                }
            }
        fun selectPage(position: Int) {
            pager.stateDescription = "Page ${position + 1} of 2"
            dots.forEachIndexed { index, dot ->
                dot.isSelected = index == position
                (dot.getChildAt(0).background as GradientDrawable).setColor(
                    if (dot.isSelected) coral else sage
                )
                dot.getChildAt(0).alpha = if (dot.isSelected) 1f else 0.4f
            }
        }
        addView(
            LinearLayout(context).apply {
                gravity = Gravity.CENTER
                dots.forEach { addView(it, LayoutParams(dp(48), dp(48))) }
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT),
        )
        pager.addOnPageChangeListener(
            object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) = selectPage(position)
            }
        )
        selectPage(0)
        bindChart(workload)
    }

    fun bind(workload: MuscleWorkload) {
        maps.bind(workload)
        bindChart(workload)
    }

    private fun bindChart(workload: MuscleWorkload) {
        val values = workload.targetPercentages()
        chart.stateDescription = values.entries.joinToString(", ") { "${it.key} ${it.value}%" }
        values.values.forEachIndexed { index, value ->
            percentages[index].text = "$value%"
            bars[index].progress = value
            bars[index].jumpDrawablesToCurrentState()
        }
    }

    internal companion object {
        fun pageHeight(context: Context): Int =
            ((264 + (28 * context.resources.configuration.fontScale).toInt()) *
                    context.resources.displayMetrics.density)
                .toInt()

        fun height(context: Context): Int =
            pageHeight(context) +
                (10 * context.resources.displayMetrics.density).toInt() +
                (48 * context.resources.displayMetrics.density).toInt()
    }

    private fun label(value: String, color: Int) =
        TextView(context).apply {
            text = value
            textSize = 13f
            setTextColor(color)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

/** Also supplies the neutral, shared history placeholder without creating a pager or chart. */
internal class MuscleMapPair(
    context: Context,
    workload: MuscleWorkload,
    gender: BiologicalGender,
) : LinearLayout(context) {
    private val maps = mutableListOf<MuscleMapView>()

    init {
        gravity = Gravity.CENTER
        listOf("Front" to MuscleMapSide.FRONT, "Back" to MuscleMapSide.BACK).forEach { (label, side)
            ->
            addView(
                LinearLayout(context).apply {
                    orientation = VERTICAL
                    gravity = Gravity.CENTER
                    addView(
                        TextView(context).apply {
                            text = label
                            textSize = 13f
                            setTextColor(ContextCompat.getColor(context, R.color.sage))
                            gravity = Gravity.CENTER
                        }
                    )
                    addView(
                        MuscleMapView(context, side, workload, gender).also { maps.add(it) },
                        LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f),
                    )
                },
                LayoutParams(0, LayoutParams.MATCH_PARENT, 1f),
            )
        }
    }

    fun bind(workload: MuscleWorkload) = maps.forEach { it.bind(workload) }
}
