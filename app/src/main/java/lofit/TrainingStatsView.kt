package lofit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.viewpager.widget.PagerAdapter
import androidx.viewpager.widget.ViewPager
import java.text.DateFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

internal class TrainingStatsView(
    private val activityContext: Context,
    private val ui: LofitViews,
    private val data: () -> AppData,
    private val onMonthChanged: () -> Unit,
    private val onOpenWorkout: (WorkoutSession) -> Unit,
) {
    private var calendarMonth = YearMonth.now()
    private var calendarPage = 0
    private var muscleSpanDays = 28L

    fun workoutCalendar(): View = ui.card {
        val names = listOf("Calendar", "Weekly rhythm", "Muscle balance")
        val pages = listOf(calendarGrid(), weeklyRhythmPage(), muscleBalancePage())
        val pager =
            ViewPager(activityContext).apply {
                contentDescription = "Calendar and training stats"
                offscreenPageLimit = 2
                adapter =
                    object : PagerAdapter() {
                        override fun getCount() = pages.size

                        override fun isViewFromObject(view: View, item: Any) = view === item

                        override fun instantiateItem(container: ViewGroup, position: Int): Any =
                            pages[position].also { container.addView(it) }

                        override fun destroyItem(
                            container: ViewGroup,
                            position: Int,
                            item: Any,
                        ) {
                            container.removeView(item as View)
                        }

                        override fun getPageTitle(position: Int) = names[position]
                    }
                currentItem = calendarPage
            }
        addView(
            pager,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ui.dp(304 + (16 * activityContext.resources.configuration.fontScale).toInt()),
            ),
        )
        val dots = names.mapIndexed { index, name ->
            FrameLayout(activityContext).apply {
                contentDescription = "$name, page ${index + 1} of ${names.size}"
                isFocusable = true
                setOnClickListener { pager.setCurrentItem(index, true) }
                addView(
                    View(activityContext).apply {
                        background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
                    },
                    FrameLayout.LayoutParams(ui.dp(8), ui.dp(8), Gravity.CENTER),
                )
            }
        }
        fun selectPage(position: Int) {
            calendarPage = position
            pager.stateDescription = "Page ${position + 1} of ${names.size}"
            dots.forEachIndexed { index, dot ->
                dot.isSelected = index == position
                (dot.getChildAt(0).background as GradientDrawable).setColor(
                    ui.color(if (dot.isSelected) R.color.coral else R.color.sage)
                )
                dot.getChildAt(0).alpha = if (dot.isSelected) 1f else 0.4f
            }
        }
        addView(
            LinearLayout(activityContext).apply {
                gravity = Gravity.CENTER
                dots.forEach { addView(it, LinearLayout.LayoutParams(ui.dp(48), ui.dp(40))) }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        pager.addOnPageChangeListener(
            object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) = selectPage(position)
            }
        )
        selectPage(calendarPage)
        setPadding(paddingLeft, paddingTop, paddingRight, ui.dp(6))
    }

    private fun weeklyRhythmPage(): View {
        val rhythm = data().history.weeklyRhythm()
        val weekLabel = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
        return LinearLayout(activityContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                ui.titleText("Weekly rhythm").apply { gravity = Gravity.CENTER_VERTICAL },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(42)),
            )
            addView(
                LinearLayout(activityContext).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        LinearLayout(activityContext).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                ui.accentText("${rhythm.thisWeekDays}").apply { textSize = 27f }
                            )
                            addView(ui.metaText("This week"))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    addView(
                        LinearLayout(activityContext).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(
                                ui.accentText(
                                        String.format(
                                            Locale.getDefault(),
                                            "%.1f",
                                            rhythm.previousFourWeekAverage,
                                        )
                                    )
                                    .apply { textSize = 27f }
                            )
                            addView(ui.metaText("Past 4 weeks avg"))
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { topMargin = ui.dp(10) },
            )
            addView(
                LinearLayout(activityContext).apply {
                    gravity = Gravity.BOTTOM
                    rhythm.weeks.forEachIndexed { index, week ->
                        addView(
                            LinearLayout(activityContext).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER_HORIZONTAL
                                contentDescription =
                                    "Week of ${week.startsOn.format(weekLabel)}, ${week.days} training days"
                                addView(
                                    ui.accentText(week.days.toString()).apply {
                                        gravity = Gravity.CENTER
                                        setTextColor(ui.color(R.color.cream))
                                    }
                                )
                                addView(
                                    FrameLayout(activityContext).apply {
                                        addView(
                                            View(activityContext).apply {
                                                setBackgroundColor(
                                                    ui.color(
                                                        if (index == rhythm.weeks.lastIndex)
                                                            R.color.coral
                                                        else R.color.sage
                                                    )
                                                )
                                            },
                                            FrameLayout.LayoutParams(
                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                    ui.dp(4 + 15 * week.days),
                                                    Gravity.BOTTOM,
                                                )
                                                .apply {
                                                    marginStart = ui.dp(8)
                                                    marginEnd = ui.dp(8)
                                                },
                                        )
                                    },
                                    LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ui.dp(116),
                                    ),
                                )
                                addView(
                                    ui.metaText(week.startsOn.format(weekLabel)).apply {
                                        gravity = Gravity.CENTER
                                        textSize = 11f
                                    }
                                )
                            },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                        )
                    }
                },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { topMargin = ui.dp(14) },
            )
        }
    }

    private fun muscleBalancePage(): View {
        val graph = LinearLayout(activityContext).apply { orientation = LinearLayout.VERTICAL }
        lateinit var refresh: () -> Unit
        val buttons =
            listOf(7L, 28L).associateWith { span ->
                ui.smallButton("${span}d") {
                    muscleSpanDays = span
                    refresh()
                }
            }
        refresh = {
            buttons.forEach { (span, button) ->
                button.isSelected = muscleSpanDays == span
                button.backgroundTintList =
                    ColorStateList.valueOf(
                        ui.color(
                            if (button.isSelected) R.color.coral else R.color.charcoal_surface_high
                        )
                    )
                button.setTextColor(
                    ui.color(if (button.isSelected) R.color.white else R.color.cream)
                )
            }
            graph.removeAllViews()
            val percentages = data().recentMuscleWorkload(muscleSpanDays).regionPercentages()
            if (percentages.values.all { it == 0 }) {
                graph.addView(ui.emptyText("No sets in this period."))
            }
            if (percentages.values.any { it > 0 })
                percentages.forEach { (name, percentage) ->
                    graph.addView(
                        LinearLayout(activityContext).apply {
                            orientation = LinearLayout.VERTICAL
                            contentDescription = "$name, $percentage% of credited sets"
                            addView(
                                LinearLayout(activityContext).apply {
                                    addView(
                                        ui.metaText(name).apply {
                                            setTextColor(ui.color(R.color.cream))
                                        },
                                        LinearLayout.LayoutParams(
                                            0,
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            1f,
                                        ),
                                    )
                                    addView(ui.metaText("$percentage%"))
                                }
                            )
                            addView(
                                android.widget
                                    .ProgressBar(
                                        activityContext,
                                        null,
                                        android.R.attr.progressBarStyleHorizontal,
                                    )
                                    .apply {
                                        max = 100
                                        progress = percentage
                                        progressTintList =
                                            ColorStateList.valueOf(ui.color(R.color.coral))
                                        progressBackgroundTintList =
                                            ColorStateList.valueOf(
                                                ui.color(R.color.charcoal_surface_high)
                                            )
                                        importantForAccessibility =
                                            View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                    },
                                LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ui.dp(7),
                                    )
                                    .apply { topMargin = ui.dp(2) },
                            )
                        },
                        LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            .apply { bottomMargin = ui.dp(7) },
                    )
                }
        }
        return LinearLayout(activityContext).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(activityContext).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        ui.titleText("Muscle balance"),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                    )
                    buttons.values.forEach { button ->
                        addView(
                            button,
                            LinearLayout.LayoutParams(ui.dp(48), ui.dp(42)).apply {
                                marginStart = ui.dp(6)
                            },
                        )
                    }
                }
            )
            addView(
                graph,
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { topMargin = ui.dp(8) },
            )
            refresh()
        }
    }

    private fun calendarGrid(): View {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val month = calendarMonth
        val trainingDays =
            data()
                .history
                .filter { it.completedAt != null }
                .sortedByDescending { it.startedAt }
                .groupBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
        val monthTitle = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))

        return LinearLayout(activityContext)
            .apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    LinearLayout(activityContext).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            ui.metaText(monthTitle).apply {
                                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                                textSize = 18f
                                setTextColor(ui.color(R.color.white))
                                setTypeface(typeface, Typeface.BOLD)
                            },
                            LinearLayout.LayoutParams(0, ui.dp(42), 1f),
                        )
                        addView(
                            ui.smallButton("‹") {
                                    calendarMonth = calendarMonth.minusMonths(1)
                                    onMonthChanged()
                                }
                                .apply {
                                    contentDescription = "Previous month"
                                    textSize = 20f
                                    setPadding(ui.dp(2), 0, ui.dp(2), 0)
                                },
                            LinearLayout.LayoutParams(ui.dp(44), ui.dp(42)).apply {
                                marginEnd = ui.dp(6)
                            },
                        )
                        addView(
                            ui.smallButton("›") {
                                    calendarMonth = calendarMonth.plusMonths(1)
                                    onMonthChanged()
                                }
                                .apply {
                                    contentDescription = "Next month"
                                    textSize = 20f
                                    setPadding(ui.dp(2), 0, ui.dp(2), 0)
                                },
                            LinearLayout.LayoutParams(ui.dp(44), ui.dp(42)),
                        )
                    }
                )
                addView(
                    LinearLayout(activityContext).apply {
                        orientation = LinearLayout.HORIZONTAL
                        DayOfWeek.entries.forEach { weekday ->
                            addView(
                                ui.metaText(
                                        weekday.getDisplayName(
                                            TextStyle.NARROW,
                                            Locale.getDefault(),
                                        )
                                    )
                                    .apply {
                                        gravity = Gravity.CENTER
                                        setTypeface(typeface, Typeface.BOLD)
                                    },
                                LinearLayout.LayoutParams(0, ui.dp(30), 1f),
                            )
                        }
                    }
                )

                val firstOffset = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
                var date = month.atDay(1).minusDays(firstOffset.toLong())
                repeat(6) {
                    addView(
                        LinearLayout(activityContext).apply {
                            orientation = LinearLayout.HORIZONTAL
                            repeat(7) {
                                val cellDate = date
                                val isInMonth = YearMonth.from(cellDate) == month
                                addView(
                                    TextView(activityContext).apply {
                                        text = cellDate.dayOfMonth.toString()
                                        textSize = 14f
                                        gravity = Gravity.CENTER
                                        setTextColor(
                                            ui.color(if (isInMonth) R.color.cream else R.color.sage)
                                        )
                                        val workouts = trainingDays[cellDate].orEmpty()
                                        val workoutCount = workouts.size
                                        val trained = workoutCount > 0
                                        contentDescription =
                                            "$cellDate, ${when (workoutCount) {
                                0 -> "no workout"
                                1 -> "workout completed"
                                else -> "$workoutCount workouts completed"
                            }}"
                                        if (trained || cellDate == today) {
                                            textSize = 16f
                                            setTypeface(typeface, Typeface.BOLD)
                                        }
                                        if (trained) {
                                            setTextColor(ui.color(R.color.charcoal))
                                            elevation = 0f
                                            isFocusable = true
                                            setOnClickListener {
                                                if (workouts.size == 1) {
                                                    onOpenWorkout(workouts.single())
                                                } else {
                                                    val timeFormat =
                                                        DateFormat.getTimeInstance(DateFormat.SHORT)
                                                    AlertDialog.Builder(activityContext)
                                                        .setTitle(
                                                            DateFormat.getDateInstance(
                                                                    DateFormat.MEDIUM
                                                                )
                                                                .format(
                                                                    Date(workouts.first().startedAt)
                                                                )
                                                        )
                                                        .setItems(
                                                            workouts
                                                                .map { workout ->
                                                                    "${workout.name} · ${timeFormat.format(Date(workout.startedAt))}"
                                                                }
                                                                .toTypedArray()
                                                        ) { _, index ->
                                                            onOpenWorkout(workouts[index])
                                                        }
                                                        .show()
                                                }
                                            }
                                        }
                                        background =
                                            GradientDrawable().apply {
                                                shape = GradientDrawable.RECTANGLE
                                                cornerRadius = ui.dp(2).toFloat()
                                                setColor(
                                                    if (trained) ui.color(R.color.coral)
                                                    else Color.TRANSPARENT
                                                )
                                                if (cellDate == today) {
                                                    setStroke(ui.dp(1), ui.color(R.color.sage))
                                                }
                                            }
                                    },
                                    LinearLayout.LayoutParams(0, ui.dp(36), 1f).apply {
                                        marginStart = ui.dp(2)
                                        marginEnd = ui.dp(2)
                                        topMargin = ui.dp(2)
                                        bottomMargin = ui.dp(2)
                                    },
                                )
                                date = date.plusDays(1)
                            }
                        }
                    )
                }
            }
            .apply { contentDescription = "Workout calendar" }
    }
}
