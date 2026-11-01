package lofit

import android.content.Context
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton

/** Set rows and headers used by routines, active workouts and history. */
internal class PlannedSetViews(
    private val activityContext: Context,
    private val ui: LofitViews,
    private val isUpdatingHoldTimer: () -> Boolean,
) {
    companion object {
        const val CHECKBOX_WIDTH_DP = 40
        private const val SET_ROW_PADDING_DP = 4
        private const val SET_HOLD_BUTTON_WIDTH_DP = 30
        private const val SET_ACTIVE_FIELD_EXTRA_WIDTH_DP = 24
    }

    fun plannedSetEditor(
        exercise: Exercise,
        plannedSet: PlannedSet,
        setIndex: Int,
        endAction: View? = null,
        endActionHeight: Int = 42,
        completed: Boolean = false,
        onRemove: (() -> Unit)? = null,
        onRemoveAndBelow: (() -> Unit)? = null,
        showActiveHold: Boolean = false,
        activeHoldAction: View? = null,
        activeHoldRunning: Boolean = false,
        readOnly: Boolean = false,
        onAmountReady: (EditText) -> Unit = {},
        onActiveAmountReady: (EditText) -> Unit = {},
        onIndexUpdaterReady: ((Int) -> Unit) -> Unit = {},
        onChanged: () -> Unit = {},
    ): View {
        var currentSetIndex = setIndex
        return LinearLayout(activityContext).apply {
            orientation = LinearLayout.VERTICAL
            if (completed) setBackgroundColor(ui.color(R.color.done_green))
            val setNumber =
                if (readOnly)
                    readOnlySetField(
                            setKindMarker(setIndex, plannedSet.type),
                            "Set ${setIndex + 1} type: ${plannedSet.type.label}",
                        )
                        .apply { gravity = Gravity.CENTER }
                else
                    ui.smallButton(setKindMarker(setIndex, plannedSet.type)) {}
                        .apply {
                            fun updateType(type: SetType) {
                                plannedSet.type = type
                                text = setKindMarker(currentSetIndex, type)
                                contentDescription =
                                    "Set ${currentSetIndex + 1} type: ${type.label}"
                                onChanged()
                            }
                            contentDescription =
                                "Set ${currentSetIndex + 1} type: ${plannedSet.type.label}"
                            setOnClickListener {
                                updateType(nextSetType(plannedSet.type))
                            }
                        }
            val sideControl: TextView? =
                if (!exercise.asymmetric) null
                else if (readOnly)
                    readOnlySetField(
                            plannedSet.side.marker,
                            "Set ${currentSetIndex + 1} side: ${plannedSet.side.label}",
                        )
                        .apply { gravity = Gravity.CENTER }
                else
                    ui.smallButton(plannedSet.side.marker) {}
                        .apply {
                            contentDescription =
                                "Set ${currentSetIndex + 1} side: ${plannedSet.side.label}"
                            setOnClickListener {
                                plannedSet.side =
                                    Side.entries[(plannedSet.side.ordinal + 1) % Side.entries.size]
                                text = plannedSet.side.marker
                                contentDescription =
                                    "Set ${currentSetIndex + 1} side: ${plannedSet.side.label}"
                                onChanged()
                            }
                        }
            val weight =
                if (exercise.weighted) {
                    if (readOnly)
                        readOnlySetField(
                            formatWeight(plannedSet.weightKg),
                            "Set ${setIndex + 1} weight",
                        )
                    else
                        ui.decimalField("Weight in kg").apply {
                            setText(formatWeight(plannedSet.weightKg))
                            contentDescription = "Set ${currentSetIndex + 1} weight"
                            setPadding(ui.dp(6), 0, ui.dp(6), 0)
                            doAfterTextChanged { value ->
                                parseWeight(value.toString())
                                    ?.takeIf { it >= 0 }
                                    ?.let {
                                        plannedSet.weightKg = it
                                        onChanged()
                                    }
                            }
                        }
                } else null
            val amountLabel =
                when {
                    !exercise.type.tracksDuration -> "Repetitions"
                    showActiveHold -> "Goal time in seconds"
                    else -> "Hold duration in seconds"
                }
            val amountValue =
                if (!exercise.type.tracksDuration) plannedSet.reps else plannedSet.holdSeconds
            val amount =
                if (readOnly)
                    readOnlySetField(
                        ui.formatInteger(amountValue),
                        "Set ${setIndex + 1} $amountLabel",
                    )
                else
                    ui.numberField(amountLabel).apply {
                        setText(ui.formatInteger(amountValue))
                        contentDescription = "Set ${currentSetIndex + 1} $amountLabel"
                        setPadding(ui.dp(6), 0, ui.dp(6), 0)
                        doAfterTextChanged { value ->
                            if (isUpdatingHoldTimer()) return@doAfterTextChanged
                            value
                                .toString()
                                .toIntOrNull()
                                ?.takeIf { it > 0 }
                                ?.let {
                                    if (!exercise.type.tracksDuration) plannedSet.reps = it
                                    else plannedSet.holdSeconds = it
                                    onChanged()
                                }
                        }
                    }
            if (amount is EditText) onAmountReady(amount)
            val activeAmount =
                if (exercise.type.tracksDuration && showActiveHold) {
                    if (readOnly)
                        readOnlySetField(
                            ui.formatInteger(plannedSet.actualHoldSeconds ?: 0),
                            "Set ${setIndex + 1} Active time in seconds",
                        )
                    else
                        ui.numberField("Active time in seconds")
                            .apply {
                                setText(ui.formatInteger(plannedSet.actualHoldSeconds ?: 0))
                                contentDescription =
                                    "Set ${currentSetIndex + 1} Active time in seconds"
                                setPadding(ui.dp(6), 0, ui.dp(6), 0)
                                isEnabled = !activeHoldRunning
                                doAfterTextChanged { value ->
                                    if (isUpdatingHoldTimer()) return@doAfterTextChanged
                                    value
                                        .toString()
                                        .toIntOrNull()
                                        ?.takeIf { it >= 0 }
                                        ?.let {
                                            plannedSet.actualHoldSeconds = it
                                            onChanged()
                                        }
                                }
                            }
                            .also(onActiveAmountReady)
                } else null
            val fields =
                GridLayout(activityContext).apply {
                    val activeGrid = activeAmount != null
                    val columns =
                        setGridColumnWidths(
                            exercise.weighted,
                            activeGrid,
                            endAction?.let { CHECKBOX_WIDTH_DP } ?: 0,
                            exercise.asymmetric,
                        )
                    columnCount = columns.size
                    rowCount = 1
                    alignmentMode = GridLayout.ALIGN_BOUNDS
                    useDefaultMargins = false
                    layoutParams = LinearLayout.LayoutParams(setTableWidth(), ui.dp(64))
                    setPadding(
                        ui.dp(SET_ROW_PADDING_DP),
                        ui.dp(SET_ROW_PADDING_DP),
                        ui.dp(SET_ROW_PADDING_DP),
                        ui.dp(SET_ROW_PADDING_DP),
                    )
                    addView(setGridControl(setNumber), setGridCell(columns[0], heightDp = 48))
                    val sideOffset = if (sideControl != null) 1 else 0
                    sideControl?.let {
                        addView(setGridControl(it), setGridCell(columns[1], heightDp = 48))
                    }
                    val activeControl = activeAmount?.let { active ->
                        FrameLayout(activityContext).apply {
                            addView(
                                active,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                            activeHoldAction?.let { action ->
                                (active as? EditText)?.setPadding(
                                    ui.dp(6),
                                    0,
                                    ui.dp(SET_HOLD_BUTTON_WIDTH_DP + 2),
                                    0,
                                )
                                addView(
                                    action,
                                    FrameLayout.LayoutParams(
                                        ui.dp(SET_HOLD_BUTTON_WIDTH_DP),
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        Gravity.END,
                                    ),
                                )
                            }
                        }
                    }
                    when {
                        activeGrid && exercise.weighted -> {
                            addView(
                                setGridControl(weight!!),
                                setGridCell(columns[1 + sideOffset], heightDp = 48),
                            )
                            addView(
                                setGridControl(amount),
                                setGridCell(columns[2 + sideOffset], heightDp = 48),
                            )
                            addView(
                                setGridControl(activeControl!!),
                                setGridCell(columns[3 + sideOffset], heightDp = 48),
                            )
                            endAction?.let {
                                addView(
                                    it,
                                    setGridCell(
                                        columns[4 + sideOffset],
                                        heightDp = endActionHeight,
                                    ),
                                )
                            }
                        }
                        activeGrid -> {
                            addView(
                                setGridControl(amount),
                                setGridCell(columns[1 + sideOffset], heightDp = 48),
                            )
                            addView(
                                setGridControl(activeControl!!),
                                setGridCell(columns[2 + sideOffset], heightDp = 48),
                            )
                            endAction?.let {
                                addView(
                                    it,
                                    setGridCell(
                                        columns[3 + sideOffset],
                                        heightDp = endActionHeight,
                                    ),
                                )
                            }
                        }
                        exercise.weighted -> {
                            addView(
                                setGridControl(weight!!),
                                setGridCell(columns[1 + sideOffset], heightDp = 48),
                            )
                            addView(
                                ui.metaText("kg ×").apply { gravity = Gravity.CENTER },
                                setGridCell(columns[2 + sideOffset], heightDp = 48),
                            )
                            addView(
                                setGridControl(amount),
                                setGridCell(columns[3 + sideOffset], heightDp = 48),
                            )
                            addView(
                                ui.metaText(if (!exercise.type.tracksDuration) "reps" else "sec")
                                    .apply { gravity = Gravity.CENTER },
                                setGridCell(columns[4 + sideOffset], heightDp = 48),
                            )
                            endAction?.let {
                                addView(
                                    it,
                                    setGridCell(
                                        columns[5 + sideOffset],
                                        heightDp = endActionHeight,
                                    ),
                                )
                            }
                        }
                        else -> {
                            addView(
                                setGridControl(amount),
                                setGridCell(columns[1 + sideOffset], heightDp = 48),
                            )
                            addView(
                                ui.metaText(if (!exercise.type.tracksDuration) "reps" else "sec")
                                    .apply { gravity = Gravity.CENTER },
                                setGridCell(columns[2 + sideOffset], heightDp = 48),
                            )
                            endAction?.let {
                                addView(
                                    it,
                                    setGridCell(
                                        columns[3 + sideOffset],
                                        heightDp = endActionHeight,
                                    ),
                                )
                            }
                        }
                    }
                }
            var swipeRow: HorizontalScrollView? = null
            var deleteButton: MaterialButton? = null
            var deleteBelowButton: MaterialButton? = null
            if (onRemove != null || onRemoveAndBelow != null) {
                val revealWidth =
                    (if (onRemove != null) ui.dp(92) else 0) +
                        (if (onRemoveAndBelow != null) ui.dp(128) else 0)
                val row =
                    SwipeRevealRow(revealWidth).apply {
                        contentDescription = "Set ${currentSetIndex + 1} row"
                        addView(
                            LinearLayout(activityContext).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(fields)
                                onRemove?.let { remove ->
                                    val button =
                                        swipeDeleteButton(
                                            "Delete",
                                            "Delete set ${currentSetIndex + 1}",
                                            remove,
                                        )
                                    deleteButton = button
                                    addView(button, LinearLayout.LayoutParams(ui.dp(92), ui.dp(64)))
                                }
                                onRemoveAndBelow?.let { remove ->
                                    val button =
                                        swipeDeleteButton(
                                            "Delete below",
                                            "Delete set ${currentSetIndex + 1} and all below",
                                            remove,
                                        )
                                    deleteBelowButton = button
                                    addView(
                                        button,
                                        LinearLayout.LayoutParams(ui.dp(124), ui.dp(64)).apply {
                                            marginStart = ui.dp(4)
                                        },
                                    )
                                }
                            }
                        )
                    }
                swipeRow = row
                addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)),
                )
            } else {
                addView(fields)
            }
            onIndexUpdaterReady { newIndex ->
                currentSetIndex = newIndex
                setNumber.text = setKindMarker(newIndex, plannedSet.type)
                setNumber.contentDescription = "Set ${newIndex + 1} type: ${plannedSet.type.label}"
                weight?.contentDescription = "Set ${newIndex + 1} weight"
                amount.contentDescription = "Set ${newIndex + 1} $amountLabel"
                activeAmount?.contentDescription = "Set ${newIndex + 1} Active time in seconds"
                swipeRow?.contentDescription = "Set ${newIndex + 1} row"
                deleteButton?.contentDescription = "Delete set ${newIndex + 1}"
                deleteBelowButton?.contentDescription = "Delete set ${newIndex + 1} and all below"
                sideControl?.contentDescription =
                    "Set ${newIndex + 1} side: ${plannedSet.side.label}"
            }
        }
    }

    private fun readOnlySetField(value: String, description: String): TextView =
        TextView(activityContext).apply {
            text = value
            textSize = 16f
            setTextColor(ui.color(R.color.cream))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(ui.dp(6), 0, ui.dp(6), 0)
            contentDescription = description
        }

    private fun swipeDeleteButton(
        label: String,
        description: String,
        action: () -> Unit,
    ): MaterialButton =
        ui.smallButton(label, action).apply {
            contentDescription = description
            setBackgroundColor(ui.color(R.color.coral_dark))
            strokeWidth = 0
        }

    fun plannedSetHeader(
        exercise: Exercise,
        showDone: Boolean = false,
        showActiveHold: Boolean = false,
    ): View =
        GridLayout(activityContext).apply {
            val activeGrid = exercise.type.tracksDuration && showActiveHold
            val columns =
                setGridColumnWidths(
                    exercise.weighted,
                    activeGrid,
                    if (showDone) CHECKBOX_WIDTH_DP else 0,
                    exercise.asymmetric,
                )
            val sideOffset = if (exercise.asymmetric) 1 else 0
            columnCount = columns.size
            rowCount = 1
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            setPadding(
                ui.dp(SET_ROW_PADDING_DP),
                ui.dp(SET_ROW_PADDING_DP),
                ui.dp(SET_ROW_PADDING_DP),
                ui.dp(SET_ROW_PADDING_DP),
            )
            layoutParams =
                LinearLayout.LayoutParams(setTableWidth(), ui.dp(38)).apply {
                    topMargin = ui.dp(8)
                }
            addView(setGridHeader("Set"), setGridCell(columns[0]))
            if (exercise.asymmetric) {
                addView(
                    setGridHeader("Side"),
                    setGridCell(columns[1]),
                )
            }
            when {
                activeGrid && exercise.weighted -> {
                    addView(setGridHeader("Weight"), setGridCell(columns[1 + sideOffset]))
                    addView(setGridHeader("Goal"), setGridCell(columns[2 + sideOffset]))
                    addView(setGridHeader("Active"), setGridCell(columns[3 + sideOffset]))
                }
                activeGrid -> {
                    addView(setGridHeader("Goal"), setGridCell(columns[1 + sideOffset]))
                    addView(setGridHeader("Active"), setGridCell(columns[2 + sideOffset]))
                }
                exercise.weighted -> {
                    addView(setGridHeader("Weight"), setGridCell(columns[1 + sideOffset]))
                    addView(View(activityContext), setGridCell(columns[2 + sideOffset]))
                    addView(
                        setGridHeader(if (!exercise.type.tracksDuration) "Reps" else "Hold"),
                        setGridCell(columns[3 + sideOffset]),
                    )
                    addView(View(activityContext), setGridCell(columns[4 + sideOffset]))
                }
                else -> {
                    addView(
                        setGridHeader(if (!exercise.type.tracksDuration) "Reps" else "Hold"),
                        setGridCell(columns[1 + sideOffset]),
                    )
                    addView(View(activityContext), setGridCell(columns[2 + sideOffset]))
                }
            }
            if (showDone) addView(View(activityContext), setGridCell(columns.last()))
        }

    private fun setGridHeader(value: String) =
        ui.metaText(value).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(ui.dp(6), ui.dp(3), 0, ui.dp(3))
        }

    private fun setGridColumnWidths(
        weighted: Boolean,
        activeHold: Boolean,
        endWidth: Int,
        asymmetric: Boolean,
    ): IntArray {
        val sideWidth = if (asymmetric) 48 else 0
        val totalWidth =
            (activityContext.resources.configuration.screenWidthDp - 28 - SET_ROW_PADDING_DP * 2)
                .coerceAtLeast(280) - sideWidth
        val widths =
            when {
                weighted && activeHold -> {
                    val fieldWidth =
                        ((totalWidth - 48 - endWidth - SET_ACTIVE_FIELD_EXTRA_WIDTH_DP) / 3)
                            .coerceAtLeast(40)
                    intArrayOf(
                        48,
                        fieldWidth,
                        fieldWidth,
                        totalWidth - 48 - endWidth - fieldWidth * 2,
                        endWidth,
                    )
                }
                activeHold -> {
                    val fieldWidth = ((totalWidth - 48 - endWidth) / 2).coerceAtLeast(64)
                    intArrayOf(48, fieldWidth, totalWidth - 48 - endWidth - fieldWidth, endWidth)
                }
                weighted -> {
                    val fieldWidth = ((totalWidth - 48 - 44 - 40 - endWidth) / 2).coerceAtLeast(48)
                    intArrayOf(
                        48,
                        fieldWidth,
                        44,
                        totalWidth - 48 - 44 - 40 - endWidth - fieldWidth,
                        40,
                        endWidth,
                    )
                }
                else -> intArrayOf(48, totalWidth - 48 - 40 - endWidth, 40, endWidth)
            }
        return if (asymmetric) intArrayOf(widths[0], sideWidth) + widths.copyOfRange(1, widths.size)
        else widths
    }

    private fun setTableWidth(): Int =
        activityContext.resources.displayMetrics.widthPixels - ui.dp(14) * 2

    private fun setGridCell(widthDp: Int, heightDp: Int? = null) =
        GridLayout.LayoutParams().apply {
            width = ui.dp(widthDp)
            height = heightDp?.let(ui::dp) ?: ViewGroup.LayoutParams.WRAP_CONTENT
            setGravity(Gravity.CENTER_VERTICAL)
            setMargins(0, 0, 0, 0)
        }

    private fun setGridControl(control: View): View =
        FrameLayout(activityContext).apply {
            addView(
                control,
                FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    .apply {
                        marginStart = ui.dp(3)
                        marginEnd = ui.dp(3)
                    },
            )
        }

    private fun setKindMarker(setIndex: Int, type: SetType): String =
        when (type) {
            SetType.WARMUP -> "W"
            SetType.NORMAL -> "${setIndex + 1}"
            SetType.FAILURE -> "F"
        }

    private fun nextSetType(type: SetType): SetType =
        when (type) {
            SetType.NORMAL -> SetType.WARMUP
            SetType.WARMUP -> SetType.FAILURE
            SetType.FAILURE -> SetType.NORMAL
        }

    private inner class SwipeRevealRow(private val revealWidth: Int) :
        HorizontalScrollView(activityContext) {
        private var touchStartX = 0f

        init {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchStartX = event.x
            return super.onInterceptTouchEvent(event)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchStartX = event.x
            val handled = super.onTouchEvent(event)
            if (
                event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                val swipeDistance = touchStartX - event.x
                val open =
                    when {
                        swipeDistance > ui.dp(24) -> true
                        swipeDistance < -ui.dp(24) -> false
                        else -> scrollX >= revealWidth / 2
                    }
                post { smoothScrollTo(if (open) revealWidth else 0, 0) }
            }
            return handled
        }

        override fun fling(velocityX: Int) {
            smoothScrollTo(if (velocityX > 0) revealWidth else 0, 0)
        }
    }
}
