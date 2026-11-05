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
        private const val SET_HOLD_BUTTON_WIDTH_DP = 36
        private const val SET_ACTIVE_FIELD_EXTRA_WIDTH_DP = 24
        private const val JOINED_SET_BUTTON_WIDTH_DP = 44
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
        onOverrideBelow: (() -> Unit)? = null,
        showActiveHold: Boolean = false,
        activeHoldAction: View? = null,
        activeHoldRunning: Boolean = false,
        readOnly: Boolean = false,
        measured: Boolean = false,
        onWeightReady: (EditText) -> Unit = {},
        onAmountReady: (EditText) -> Unit = {},
        onActiveAmountReady: (EditText) -> Unit = {},
        onIndexUpdaterReady: ((Int) -> Unit) -> Unit = {},
        onValuesUpdaterReady: (() -> Unit) -> Unit = {},
        onChanged: () -> Unit = {},
    ): View {
        var currentSetIndex = setIndex
        var updatingValues = false
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
                            onWeightReady(this)
                            doAfterTextChanged { value ->
                                if (updatingValues || isUpdatingHoldTimer())
                                    return@doAfterTextChanged
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
                            if (updatingValues || isUpdatingHoldTimer()) return@doAfterTextChanged
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
                                // LEGACY: Active time stays read-only while a timer or scale
                                // controls it.
                                isEnabled = !activeHoldRunning && !measured
                                doAfterTextChanged { value ->
                                    if (updatingValues || isUpdatingHoldTimer())
                                        return@doAfterTextChanged
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
                            activeHoldAction != null,
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
                    // LEGACY: Symmetric exercises keep their standalone set-type control.
                    val setControl =
                        if (sideControl == null) setNumber
                        else joinedSetControl(setNumber, sideControl)
                    addView(setGridControl(setControl), setGridCell(columns[0], heightDp = 48))
                    when {
                        activeGrid && exercise.weighted -> {
                            addView(
                                setGridControl(weight!!),
                                setGridCell(columns[1], heightDp = 48),
                            )
                            addView(
                                setGridControl(amount),
                                setGridCell(columns[2], heightDp = 48),
                            )
                            addView(
                                setGridControl(activeAmount!!),
                                setGridCell(columns[3], heightDp = 48),
                            )
                            activeHoldAction?.let {
                                addView(
                                    setGridControl(it, horizontalMarginDp = 0),
                                    setGridCell(columns[columns.lastIndex - 1], heightDp = 56),
                                )
                            }
                            endAction?.let {
                                addView(
                                    it,
                                    setGridCell(
                                        columns.last(),
                                        heightDp = endActionHeight,
                                    ),
                                )
                            }
                        }
                        activeGrid -> {
                            addView(
                                setGridControl(amount),
                                setGridCell(columns[1], heightDp = 48),
                            )
                            addView(
                                setGridControl(activeAmount!!),
                                setGridCell(columns[2], heightDp = 48),
                            )
                            activeHoldAction?.let {
                                addView(
                                    setGridControl(it, horizontalMarginDp = 0),
                                    setGridCell(columns[columns.lastIndex - 1], heightDp = 56),
                                )
                            }
                            endAction?.let {
                                addView(
                                    it,
                                    setGridCell(
                                        columns.last(),
                                        heightDp = endActionHeight,
                                    ),
                                )
                            }
                        }
                        exercise.weighted -> {
                            addView(
                                setGridControl(weight!!),
                                setGridCell(columns[1], heightDp = 48),
                            )
                            addView(
                                ui.metaText("kg ×").apply { gravity = Gravity.CENTER },
                                setGridCell(columns[2], heightDp = 48),
                            )
                            addView(
                                setGridControl(amount),
                                setGridCell(columns[3], heightDp = 48),
                            )
                            addView(
                                ui.metaText(if (!exercise.type.tracksDuration) "reps" else "sec")
                                    .apply { gravity = Gravity.CENTER },
                                setGridCell(columns[4], heightDp = 48),
                            )
                            endAction?.let {
                                addView(
                                    it,
                                    setGridCell(
                                        columns[5],
                                        heightDp = endActionHeight,
                                    ),
                                )
                            }
                        }
                        else -> {
                            addView(
                                setGridControl(amount),
                                setGridCell(columns[1], heightDp = 48),
                            )
                            addView(
                                ui.metaText(if (!exercise.type.tracksDuration) "reps" else "sec")
                                    .apply { gravity = Gravity.CENTER },
                                setGridCell(columns[2], heightDp = 48),
                            )
                            endAction?.let {
                                addView(
                                    it,
                                    setGridCell(
                                        columns[3],
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
            var overrideBelowButton: MaterialButton? = null
            if (onRemove != null || onRemoveAndBelow != null || onOverrideBelow != null) {
                val revealWidth =
                    (if (onRemove != null) ui.dp(84) else 0) +
                        (if (onOverrideBelow != null) ui.dp(116) else 0) +
                        (if (onRemoveAndBelow != null) ui.dp(108) else 0)
                val row =
                    SwipeRevealRow(revealWidth).apply {
                        contentDescription = "Set ${currentSetIndex + 1} row"
                        addView(
                            LinearLayout(activityContext).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                addView(fields)
                                onOverrideBelow?.let { override ->
                                    val button =
                                        ui.smallButton("Override down") {
                                                override()
                                                swipeRow?.smoothScrollTo(0, 0)
                                            }
                                            .apply {
                                                contentDescription =
                                                    "Override down set ${currentSetIndex + 1}"
                                            }
                                    overrideBelowButton = button
                                    addView(
                                        button,
                                        LinearLayout.LayoutParams(ui.dp(116), ui.dp(48)),
                                    )
                                }
                                onRemove?.let { remove ->
                                    val button =
                                        swipeDeleteButton(
                                            "Delete",
                                            "Delete set ${currentSetIndex + 1}",
                                            remove,
                                        )
                                    deleteButton = button
                                    addView(
                                        button,
                                        LinearLayout.LayoutParams(ui.dp(80), ui.dp(48)).apply {
                                            marginStart = ui.dp(4)
                                        },
                                    )
                                }
                                onRemoveAndBelow?.let { remove ->
                                    val button =
                                        swipeDeleteButton(
                                            "Delete down",
                                            "Delete set ${currentSetIndex + 1} and all below",
                                            remove,
                                        )
                                    deleteBelowButton = button
                                    addView(
                                        button,
                                        LinearLayout.LayoutParams(ui.dp(104), ui.dp(48)).apply {
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
                overrideBelowButton?.contentDescription = "Override down set ${newIndex + 1}"
                sideControl?.contentDescription =
                    "Set ${newIndex + 1} side: ${plannedSet.side.label}"
            }
            onValuesUpdaterReady {
                updatingValues = true
                try {
                    weight?.text = formatWeight(plannedSet.weightKg)
                    amount.text =
                        ui.formatInteger(
                            if (exercise.type.tracksDuration) plannedSet.holdSeconds
                            else plannedSet.reps
                        )
                } finally {
                    updatingValues = false
                }
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
        showActiveHoldAction: Boolean = false,
    ): View =
        GridLayout(activityContext).apply {
            val activeGrid = exercise.type.tracksDuration && showActiveHold
            val columns =
                setGridColumnWidths(
                    exercise.weighted,
                    activeGrid,
                    if (showDone) CHECKBOX_WIDTH_DP else 0,
                    exercise.asymmetric,
                    showActiveHoldAction,
                )
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
            when {
                activeGrid && exercise.weighted -> {
                    addView(setGridHeader("Weight"), setGridCell(columns[1]))
                    addView(setGridHeader("Goal"), setGridCell(columns[2]))
                    addView(setGridHeader("Active"), setGridCell(columns[3]))
                }
                activeGrid -> {
                    addView(setGridHeader("Goal"), setGridCell(columns[1]))
                    addView(setGridHeader("Active"), setGridCell(columns[2]))
                }
                exercise.weighted -> {
                    addView(setGridHeader("Weight"), setGridCell(columns[1]))
                    addView(View(activityContext), setGridCell(columns[2]))
                    addView(
                        setGridHeader(if (!exercise.type.tracksDuration) "Reps" else "Hold"),
                        setGridCell(columns[3]),
                    )
                    addView(View(activityContext), setGridCell(columns[4]))
                }
                else -> {
                    addView(
                        setGridHeader(if (!exercise.type.tracksDuration) "Reps" else "Hold"),
                        setGridCell(columns[1]),
                    )
                    addView(View(activityContext), setGridCell(columns[2]))
                }
            }
            if (activeGrid && showActiveHoldAction) {
                addView(View(activityContext), setGridCell(columns[columns.lastIndex - 1]))
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
        activeHoldAction: Boolean,
    ): IntArray {
        // LEGACY: Standalone set buttons retain their original width.
        val setWidth = if (asymmetric) JOINED_SET_BUTTON_WIDTH_DP else 48
        val sideWidth = if (asymmetric) JOINED_SET_BUTTON_WIDTH_DP else 0
        val holdButtonWidth = if (activeHold && activeHoldAction) SET_HOLD_BUTTON_WIDTH_DP else 0
        val totalWidth =
            (activityContext.resources.configuration.screenWidthDp - 28 - SET_ROW_PADDING_DP * 2)
                .coerceAtLeast(280) - sideWidth - holdButtonWidth
        val widths =
            when {
                weighted && activeHold -> {
                    // Give the space saved by compact timer controls to the weight input.
                    // LEGACY: Rows without manual timers keep their existing weight allowance.
                    val extraWeightWidth =
                        if (activeHoldAction) 12 else SET_ACTIVE_FIELD_EXTRA_WIDTH_DP
                    val fieldWidth =
                        ((totalWidth - setWidth - endWidth - extraWeightWidth) / 3).coerceAtLeast(
                            40
                        )
                    val weightWidth = fieldWidth + extraWeightWidth
                    intArrayOf(
                        setWidth,
                        weightWidth,
                        fieldWidth,
                        totalWidth - setWidth - endWidth - weightWidth - fieldWidth,
                        endWidth,
                    )
                }
                activeHold -> {
                    val fieldWidth = ((totalWidth - setWidth - endWidth) / 2).coerceAtLeast(64)
                    intArrayOf(
                        setWidth,
                        fieldWidth,
                        totalWidth - setWidth - endWidth - fieldWidth,
                        endWidth,
                    )
                }
                weighted -> {
                    val fieldWidth =
                        ((totalWidth - setWidth - 44 - 40 - endWidth) / 2).coerceAtLeast(48)
                    intArrayOf(
                        setWidth,
                        fieldWidth,
                        44,
                        totalWidth - setWidth - 44 - 40 - endWidth - fieldWidth,
                        40,
                        endWidth,
                    )
                }
                else -> intArrayOf(setWidth, totalWidth - setWidth - 40 - endWidth, 40, endWidth)
            }
        val columns =
            if (holdButtonWidth > 0)
                widths.copyOfRange(0, widths.lastIndex) + intArrayOf(holdButtonWidth, endWidth)
            else widths
        if (asymmetric) columns[0] += sideWidth
        return columns
    }

    private fun joinedSetControl(setNumber: TextView, side: TextView): View =
        LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            val rtl = resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
            listOf(setNumber, side).forEachIndexed { index, control ->
                (control as? MaterialButton)?.apply {
                    setPadding(ui.dp(3), paddingTop, ui.dp(3), paddingBottom)
                    insetLeft = 0
                    insetRight = 0
                    shapeAppearanceModel =
                        shapeAppearanceModel
                            .toBuilder()
                            .apply {
                                if ((index == 0) != rtl) {
                                    setTopRightCornerSize(0f)
                                    setBottomRightCornerSize(0f)
                                } else {
                                    setTopLeftCornerSize(0f)
                                    setBottomLeftCornerSize(0f)
                                }
                            }
                            .build()
                }
                addView(
                    control,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
                )
            }
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

    private fun setGridControl(control: View, horizontalMarginDp: Int = 3): View =
        FrameLayout(activityContext).apply {
            addView(
                control,
                FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    .apply {
                        marginStart = ui.dp(horizontalMarginDp)
                        marginEnd = ui.dp(horizontalMarginDp)
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
