package lofit

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

internal class RecordScreens(
    private val activityContext: Context,
    private val ui: LofitViews,
    private val data: () -> AppData,
    private val onOpenTrend: (String) -> Unit,
) {
    fun recordsScreen(): View =
        ui.page("Records", "Your best logged set for each exercise.") { body ->
            val records = data().oneRepMaxes()
            if (records.isEmpty()) {
                body.addView(
                    ui.emptyText("Complete a set and finish the workout to earn your first record.")
                )
            }
            records.forEach { record ->
                body.addView(
                    ui.card {
                            addView(ui.exerciseHeading(record.exercise, large = true))
                            val value =
                                when {
                                    record.exercise.type.tracksDuration ->
                                        "${record.set.holdSeconds}s"
                                    record.exercise.weighted ->
                                        "${formatWeight(record.set.weightKg)} kg"
                                    else -> "${record.set.reps} reps"
                                }
                            val label =
                                when {
                                    record.exercise.type.tracksDuration -> "Longest hold"
                                    record.exercise.weighted -> "Highest weight"
                                    else -> "Most repetitions"
                                }
                            addView(ui.accentText(value).apply { textSize = 22f })
                            addView(ui.metaText(label))
                            record.volumeSet?.let { set ->
                                addView(
                                    ui.accentText(
                                            "${formatWeight(set.volume(record.exercise.asymmetric))} kg"
                                        )
                                        .apply {
                                            textSize = 22f
                                        }
                                )
                                addView(ui.metaText("Highest set volume"))
                            }
                        }
                        .apply {
                            contentDescription = "View ${record.exercise.name} trend"
                            setOnClickListener { onOpenTrend(record.exercise.id) }
                        }
                )
            }
        }

    fun exerciseTrendScreen(
        exerciseId: String,
        span: TrendSpan,
        onSpanChange: (TrendSpan) -> Unit,
    ): View {
        val movement =
            data().exercises.firstOrNull { it.id == exerciseId } ?: return recordsScreen()
        val trend = data().exerciseTrend(movement.id, span)
        return ui.page(movement.name) { body ->
            body.addView(
                LinearLayout(activityContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    TrendSpan.entries.forEachIndexed { index, choice ->
                        addView(
                            ui.smallButton(choice.label) {
                                    onSpanChange(choice)
                                }
                                .apply {
                                    isSelected = choice == span
                                    contentDescription =
                                        when (choice) {
                                            TrendSpan.MONTH -> "1 month"
                                            TrendSpan.THREE_MONTHS -> "3 months"
                                            TrendSpan.SIX_MONTHS -> "6 months"
                                            TrendSpan.YEAR -> "1 year"
                                            TrendSpan.ALL -> "All time"
                                        }
                                    if (isSelected) {
                                        backgroundTintList =
                                            ColorStateList.valueOf(ui.color(R.color.coral))
                                        setTextColor(ui.color(R.color.white))
                                    }
                                },
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                                .apply {
                                    if (index > 0) marginStart = ui.dp(8)
                                },
                        )
                    }
                },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                        topMargin = ui.dp(12)
                        bottomMargin = ui.dp(16)
                    },
            )
            if (trend.series.all { it.points.isEmpty() }) {
                body.addView(ui.emptyText("No records in this span."))
            } else
                trend.series
                    .filter { it.points.isNotEmpty() }
                    .forEach { series ->
                        body.addView(
                            ui.card {
                                addView(ui.titleText(series.metric.label))
                                addView(
                                    ui.accentText(
                                        "${formatWeight(series.points.last().value)} ${series.metric.unit}"
                                    )
                                )
                                addView(
                                    TrendGraphView(
                                        activityContext,
                                        series,
                                        trend.startedAt,
                                        trend.endedAt,
                                    )
                                )
                            }
                        )
                    }
        }
    }
}
