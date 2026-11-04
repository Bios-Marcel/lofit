package lofit

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Filter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale

/** Shared view styling and reusable controls. Does not own navigation or app data. */
internal class LofitViews(
    private val activityContext: Context,
    private val exercisePictures: ExercisePictures,
    private val biologicalGender: () -> BiologicalGender,
) {
    fun page(
        title: String,
        subtitle: String? = null,
        headerEnd: View? = null,
        build: (LinearLayout) -> Unit,
    ): View {
        val scroll = ScrollView(activityContext).apply { isFillViewport = true }
        val body =
            LinearLayout(activityContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(20))
            }
        body.addView(
            LinearLayout(activityContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (headerEnd is TextView) Gravity.TOP else Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(activityContext).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(
                            titleText(title).apply {
                                textSize = 26f
                                setPadding(0, dp(5), 0, 0)
                            }
                        )
                        baselineAlignedChildIndex = 0
                        subtitle?.let {
                            addView(metaText(it).apply { setPadding(0, 0, 0, dp(10)) })
                        }
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
                )
                headerEnd?.let {
                    addView(
                        it,
                        LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            .apply {
                                marginStart = dp(12)
                                bottomMargin = dp(5)
                            },
                    )
                }
            }
        )
        build(body)
        scroll.addView(body)
        return scroll
    }

    fun card(horizontalPadding: Int = 10, build: LinearLayout.() -> Unit): View =
        MaterialCardView(activityContext).apply {
            radius = dp(3).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color(R.color.charcoal_light))
            addView(
                LinearLayout(activityContext).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(horizontalPadding), dp(10), dp(horizontalPadding), dp(10))
                    build()
                }
            )
            layoutParams =
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply { bottomMargin = dp(8) }
        }

    fun insetCardLayoutParams() =
        LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            .apply {
                marginStart = dp(10)
                marginEnd = dp(10)
            }

    fun exerciseHeading(
        exercise: Exercise,
        large: Boolean,
        showMuscle: Boolean = true,
        actions: View? = null,
    ): View =
        LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val width = if (large) dp(84) else dp(68)
            val height = if (large) dp(60) else dp(48)
            addView(
                exerciseThumbnail(exercise, width, height),
                LinearLayout.LayoutParams(width, height).apply { marginEnd = dp(12) },
            )
            addView(
                LinearLayout(activityContext).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleText(exercise.name))
                    if (showMuscle) addView(metaText(exercise.primaryMuscle))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            actions?.let(::addView)
        }

    fun exerciseActions(
        exercise: Exercise,
        onRemove: () -> Unit,
        onReorderHandleReady: (View) -> Unit,
        onSelectExercise: (ExercisePlacement) -> Unit,
        replaceRemaining: Boolean = false,
        canReplace: Boolean = true,
        useScale: (() -> Boolean)? = null,
        onUseScaleChange: (Boolean) -> Unit = {},
        pauseEverySecondSet: () -> Boolean = { false },
        onPauseEverySecondSetChange: (Boolean) -> Unit = {},
    ): View =
        LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val handle =
                ImageView(activityContext).apply {
                    setImageResource(R.drawable.ic_drag_handle)
                    imageTintList = ColorStateList.valueOf(color(R.color.cream))
                    scaleType = ImageView.ScaleType.CENTER
                    contentDescription = "Reorder ${exercise.name}"
                    isFocusable = true
                }
            addView(handle, LinearLayout.LayoutParams(dp(42), dp(48)))
            onReorderHandleReady(handle)
            addView(
                smallButton("⋮") {}
                    .apply {
                        contentDescription = "Options for ${exercise.name}"
                        textSize = 20f
                        setPadding(dp(2), 0, dp(2), 0)
                        setOnClickListener {
                            PopupMenu(activityContext, this, Gravity.END).apply {
                                menu.add("Remove exercise").setOnMenuItemClickListener {
                                    onRemove()
                                    true
                                }
                                menu
                                    .add(
                                        if (replaceRemaining) "Replace remaining"
                                        else "Replace exercise"
                                    )
                                    .apply {
                                        isEnabled = canReplace
                                        setOnMenuItemClickListener {
                                            onSelectExercise(ExercisePlacement.REPLACE)
                                            true
                                        }
                                    }
                                menu.add("Add exercise above").setOnMenuItemClickListener {
                                    onSelectExercise(ExercisePlacement.ABOVE)
                                    true
                                }
                                menu.add("Add exercise below").setOnMenuItemClickListener {
                                    onSelectExercise(ExercisePlacement.BELOW)
                                    true
                                }
                                useScale?.let { checked ->
                                    menu.add("Use scale").apply {
                                        isCheckable = true
                                        isChecked = checked()
                                        setOnMenuItemClickListener {
                                            onUseScaleChange(!isChecked)
                                            true
                                        }
                                    }
                                }
                                if (exercise.asymmetric) {
                                    menu.add("Pause every second set").apply {
                                        isCheckable = true
                                        isChecked = pauseEverySecondSet()
                                        setOnMenuItemClickListener {
                                            onPauseEverySecondSetChange(!isChecked)
                                            true
                                        }
                                    }
                                }
                                show()
                            }
                        }
                    },
                LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = dp(8) },
            )
        }

    fun exerciseThumbnail(exercise: Exercise, width: Int, height: Int): View {
        exercise.imageFile
            ?.let { exercisePictures.thumbnail(it, width, height) }
            ?.let { bitmap ->
                return ImageView(activityContext).apply {
                    setImageBitmap(bitmap)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "${exercise.name} illustration"
                }
            }
        val resource = exercisePictures.bundledResource(exercise, biologicalGender())
        return resource?.let {
            ImageView(activityContext).apply {
                setImageResource(it)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "${exercise.name} illustration"
            }
        }
            ?: TextView(activityContext).apply {
                text = exercise.name.take(1).uppercase(Locale.getDefault())
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(color(R.color.cream))
                setBackgroundColor(color(R.color.coral_dark))
                contentDescription = "${exercise.name} placeholder image"
            }
    }

    fun stepperRow(label: String, value: Int, unit: String, onChange: (Int) -> Unit): View =
        LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            addView(
                TextView(activityContext).apply {
                    text = "$label\n$value $unit"
                    textSize = 15f
                    setTextColor(color(R.color.cream))
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(smallButton("−") { onChange(-1) }, LinearLayout.LayoutParams(dp(52), dp(46)))
            addView(
                smallButton("+") { onChange(1) },
                LinearLayout.LayoutParams(dp(52), dp(46)).apply { marginStart = dp(6) },
            )
        }

    fun restInput(value: Int, onChange: (Int) -> Unit): View =
        LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(
                TextView(activityContext).apply {
                    text = "Set Rest"
                    textSize = 16f
                    setTextColor(color(R.color.cream))
                    setTypeface(typeface, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                numberField("Seconds").apply {
                    setText(formatInteger(value.coerceIn(0, 3_600)))
                    contentDescription = "Rest duration in seconds"
                    gravity = Gravity.CENTER
                    setPadding(dp(6), 0, dp(6), 0)
                    doAfterTextChanged { text ->
                        text.toString().toIntOrNull()?.let { onChange(it.coerceIn(0, 3_600)) }
                    }
                },
                LinearLayout.LayoutParams(dp(82), dp(42)),
            )
        }

    fun restOverrideInput(value: Int?, onChange: (Int?) -> Unit): View =
        LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(
                TextView(activityContext).apply {
                    text = "Rest override"
                    textSize = 16f
                    setTextColor(color(R.color.cream))
                    setTypeface(typeface, Typeface.BOLD)
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                numberField("Seconds").apply {
                    setText(value?.let(::formatInteger).orEmpty())
                    contentDescription = "Rest override in seconds"
                    gravity = Gravity.CENTER
                    setPadding(dp(6), 0, dp(6), 0)
                    doAfterTextChanged { text ->
                        onChange(text.toString().toIntOrNull()?.coerceIn(0, 3_600))
                    }
                },
                LinearLayout.LayoutParams(dp(82), dp(42)),
            )
        }

    fun choiceGroup(values: List<String>, selected: Int): RadioGroup =
        RadioGroup(activityContext).apply {
            orientation = if (values.size <= 3) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
            values.forEachIndexed { index, value ->
                addView(
                    RadioButton(activityContext).apply {
                        id = index + 1
                        text = value
                        textSize = 15f
                        setTextColor(color(R.color.cream))
                        buttonTintList = toggleTint()
                        buttonDrawable = sizedButtonDrawable(buttonDrawable, 30)
                        isChecked = index == selected
                        minHeight = dp(48)
                        setPadding(dp(4), dp(4), dp(10), dp(4))
                    },
                    if (orientation == RadioGroup.HORIZONTAL)
                        RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    else
                        RadioGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                )
            }
        }

    fun selectedChoice(group: RadioGroup): Int = (group.checkedRadioButtonId - 1).coerceAtLeast(0)

    fun horizontalActions(vararg views: View): View =
        LinearLayout(activityContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            views.forEach { view ->
                addView(
                    view,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(5)
                    },
                )
            }
        }

    fun actionButton(
        label: String,
        secondary: Boolean = false,
        keepVisibleAfterClick: Boolean = false,
        action: () -> Unit,
    ): MaterialButton =
        MaterialButton(activityContext).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            letterSpacing = 0f
            setTypeface(typeface, Typeface.NORMAL)
            cornerRadius = dp(3)
            elevation = 0f
            stateListAnimator = null
            insetTop = 0
            insetBottom = 0
            strokeWidth = 0
            minHeight = dp(46)
            if (secondary) {
                backgroundTintList = ColorStateList.valueOf(color(R.color.charcoal_surface_high))
                setTextColor(color(R.color.cream))
            } else {
                backgroundTintList = ColorStateList.valueOf(color(R.color.coral))
                setTextColor(color(R.color.white))
            }
            setOnClickListener {
                action()
                if (keepVisibleAfterClick) {
                    doOnPreDraw { requestRectangleOnScreen(Rect(0, 0, width, height), true) }
                }
            }
        }

    fun smallButton(label: String, action: () -> Unit): MaterialButton =
        actionButton(label, secondary = true, action = action).apply {
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(5), 0, dp(5), 0)
        }

    fun titleText(value: String): TextView =
        TextView(activityContext).apply {
            text = value
            textSize = 18f
            setTextColor(color(R.color.cream))
            setTypeface(typeface, Typeface.BOLD)
        }

    fun sectionTitle(value: String): TextView =
        titleText(value).apply { setPadding(0, dp(14), 0, dp(6)) }

    fun divider(description: String = "Workout actions separator"): View =
        View(activityContext).apply {
            setBackgroundColor(color(R.color.sage))
            alpha = 0.45f
            contentDescription = description
            layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(14)
                    bottomMargin = dp(14)
                }
        }

    fun fieldLabel(value: String): TextView = metaText(value).apply { setPadding(0, dp(8), 0, 0) }

    fun metaText(value: String): TextView =
        TextView(activityContext).apply {
            text = value
            textSize = 14f
            setTextColor(color(R.color.sage))
            setPadding(0, dp(3), 0, dp(3))
        }

    fun accentText(value: String): TextView =
        TextView(activityContext).apply {
            text = value
            textSize = 15f
            setTextColor(color(R.color.coral))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }

    fun emptyText(value: String): TextView =
        metaText(value).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(36), dp(18), dp(36))
        }

    fun textField(hint: String): EditText =
        EditText(activityContext).apply {
            this.hint = hint
            isSingleLine = true
            textSize = 16f
            minHeight = dp(48)
            setTextColor(color(R.color.cream))
            setHintTextColor(color(R.color.sage))
            background = fieldBackground(focused = false)
            setPadding(dp(12), 0, dp(12), 0)
            setOnFocusChangeListener { view, focused -> view.background = fieldBackground(focused) }
        }

    fun exerciseNote(
        value: String,
        exercise: Exercise,
        previousNote: String = "",
        readOnly: Boolean = false,
        onCommit: () -> Unit = {},
        onChange: (String) -> Unit = {},
    ): TextView {
        val field = if (readOnly) TextView(activityContext) else textField("Type note here ...")
        var committedValue = value
        fun commitNote() {
            val currentValue = field.text.toString()
            if (currentValue == committedValue) return
            onChange(currentValue)
            onCommit()
            committedValue = currentValue
        }
        return field.apply {
            hint = previousNote.ifBlank { "Type note here ..." }
            textSize = 14f
            setTextColor(color(R.color.cream))
            setHintTextColor(color(R.color.sage))
            setPadding(dp(4), dp(4), dp(4), dp(4))
            minHeight = 0
            background = null
            contentDescription = "${exercise.name} note"
            if (this is EditText) {
                inputType =
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                        InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                isSingleLine = false
                gravity = Gravity.TOP or Gravity.START
                setText(value)
                doAfterTextChanged { onChange(it.toString()) }
                setOnFocusChangeListener { _, focused -> if (!focused) commitNote() }
                addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(view: View) = Unit

                        override fun onViewDetachedFromWindow(view: View) = commitNote()
                    }
                )
            } else {
                text = value
            }
        }
    }

    fun muscleGroupField(
        hint: String,
        value: String,
        multiple: Boolean = false,
    ): AutoCompleteTextView {
        val field =
            if (multiple) MultiAutoCompleteTextView(activityContext)
            else AutoCompleteTextView(activityContext)
        return field.apply {
            this.hint = hint
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            threshold = 0
            textSize = 16f
            minHeight = dp(48)
            setTextColor(color(R.color.cream))
            setHintTextColor(color(R.color.sage))
            background = fieldBackground(focused = false)
            setPadding(dp(12), 0, dp(12), 0)
            setOnFocusChangeListener { view, focused -> view.background = fieldBackground(focused) }
            setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0)
            setAdapter(searchAdapter(MuscleGroups.all))
            if (this is MultiAutoCompleteTextView)
                setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
            setText(value, false)
            contentDescription = hint
            setOnClickListener { showDropDown() }
        }
    }

    private fun searchAdapter(sourceValues: List<String>): ArrayAdapter<String> =
        object :
            ArrayAdapter<String>(
                activityContext,
                android.R.layout.simple_dropdown_item_1line,
                sourceValues.toMutableList(),
            ) {
            private val wordFilter =
                object : Filter() {
                    override fun performFiltering(constraint: CharSequence?): FilterResults {
                        val matches = sourceValues.filter {
                            matchesSearchQuery(constraint?.toString().orEmpty(), it)
                        }
                        return FilterResults().apply {
                            values = matches
                            count = matches.size
                        }
                    }

                    override fun publishResults(
                        constraint: CharSequence?,
                        results: FilterResults?,
                    ) {
                        @Suppress("UNCHECKED_CAST")
                        val matches = results?.values as? List<String> ?: emptyList()
                        setNotifyOnChange(false)
                        clear()
                        addAll(matches)
                        notifyDataSetChanged()
                    }
                }

            override fun getFilter(): Filter = wordFilter
        }

    fun numberField(hint: String): EditText =
        textField(hint).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSelectAllOnFocus(true)
            setOnClickListener { selectAll() }
        }

    fun decimalField(hint: String): EditText =
        textField(hint).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setSelectAllOnFocus(true)
            setOnClickListener { selectAll() }
        }

    private fun fieldBackground(focused: Boolean) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(3).toFloat()
            setColor(color(R.color.charcoal_input))
            if (focused) setStroke(dp(1), color(R.color.coral))
        }

    fun toggleTint() =
        ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(),
            ),
            intArrayOf(color(R.color.coral), color(R.color.sage)),
        )

    fun sizedButtonDrawable(drawable: android.graphics.drawable.Drawable?, sizeDp: Int) =
        drawable?.let {
            LayerDrawable(arrayOf(it)).apply {
                setLayerSize(0, dp(sizeDp), dp(sizeDp))
                setLayerGravity(0, Gravity.CENTER)
            }
        }

    fun largeCheckBox(): CheckBox =
        CheckBox(activityContext).apply {
            buttonDrawable = sizedButtonDrawable(buttonDrawable, PlannedSetViews.CHECKBOX_WIDTH_DP)
            buttonTintList = toggleTint()
            gravity = Gravity.CENTER
            minimumWidth = dp(PlannedSetViews.CHECKBOX_WIDTH_DP)
            minimumHeight = dp(56)
            setPadding(0, 0, 0, 0)
        }

    fun formatInteger(value: Int): String = String.format(Locale.getDefault(), "%d", value)

    fun color(resource: Int): Int = ContextCompat.getColor(activityContext, resource)

    fun dp(value: Int): Int = (value * activityContext.resources.displayMetrics.density).toInt()
}
