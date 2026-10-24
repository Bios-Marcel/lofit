package lofit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.Filter
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.MultiAutoCompleteTextView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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
import java.util.UUID
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private companion object {
        const val WORKOUT_NOTIFICATION_CHANNEL = "active-workout"
        const val WORKOUT_NOTIFICATION_ID = 1
        const val ACTION_SUBTRACT_REST = "lofit.action.SUBTRACT_REST"
        const val ACTION_ADD_REST = "lofit.action.ADD_REST"
        const val ACTION_SKIP_REST = "lofit.action.SKIP_REST"
        const val SET_ROW_PADDING_DP = 4
        const val SET_HOLD_ACTION_WIDTH_DP = 48
        const val SET_DONE_ACTION_WIDTH_DP = 48
        const val SET_RECORD_ACTION_WIDTH_DP = 32
    }

    private enum class Screen { WORKOUT, ROUTINES, EXERCISES, RECORDS, HISTORY, SETTINGS }
    private enum class PickerTarget { WORKOUT, ROUTINE }
    private enum class ConfirmAction { FINISH_WORKOUT, DISCARD_WORKOUT, DELETE_EXERCISE, DELETE_ROUTINE, RESET_DATA }

    private inner class SwipeRevealRow(
        private val revealWidth: Int,
    ) : HorizontalScrollView(this@MainActivity) {
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
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                val swipeDistance = touchStartX - event.x
                val open = when {
                    swipeDistance > dp(24) -> true
                    swipeDistance < -dp(24) -> false
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

    private sealed class Route {
        data object Root : Route()
        data class ExercisePicker(val target: PickerTarget, val returnScrollY: Int? = null) : Route()
        data class RoutineEditor(val originalId: String?) : Route()
        data class ExerciseEditor(val exerciseId: String?) : Route()
        data class WorkoutHistory(val workoutId: String, val returnScrollY: Int) : Route()
        data class Confirm(val action: ConfirmAction, val targetId: String? = null) : Route()
    }

    private lateinit var repository: LofitRepository
    private lateinit var content: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var restBar: LinearLayout
    private lateinit var restTimeText: TextView
    private var currentScreen = Screen.WORKOUT
    private var route: Route = Route.Root
    private var calendarMonth = YearMonth.now()
    private var routineDraft: Routine? = null
    private var timerText: TextView? = null
    private var holdTimerText: TextView? = null
    private var holdTimerButton: MaterialButton? = null
    private var holdDoneCheckBox: CheckBox? = null
    private var activeHoldWorkoutExerciseId: String? = null
    private var activeHoldPlannedSetId: String? = null
    private var holdStartedAt = 0L
    private var holdCuePlayed = false
    private var updatingHoldTimer = false
    private var completingHoldAutomatically = false
    private var restEndsAt = 0L
    private var lastWorkoutNotification = ""
    private val drawableIds = mutableMapOf<String, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) updateWorkoutNotification(force = true)
    }
    private val notificationActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_SUBTRACT_REST -> subtractRest()
                ACTION_ADD_REST -> addRest()
                ACTION_SKIP_REST -> skipRest()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            updateWorkoutTimer()
            updateHoldTimer()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = LofitRepository(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (route != Route.Root) {
                    navigateBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        createWorkoutNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            notificationActionReceiver,
            IntentFilter().apply {
                addAction(ACTION_SUBTRACT_REST)
                addAction(ACTION_ADD_REST)
                addAction(ACTION_SKIP_REST)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        buildShell()
        render()
        if (repository.data.activeWorkout != null) ensureWorkoutNotification()
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        unregisterReceiver(notificationActionReceiver)
        super.onDestroy()
    }

    private fun buildShell() {
        drawerLayout = DrawerLayout(this).apply {
            setBackgroundColor(color(R.color.charcoal))
            setScrimColor(Color.argb(170, 0, 0, 0))
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.charcoal))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        restBar = createRestBar()
        root.addView(restBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        drawerLayout.addView(root, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        drawerLayout.addView(drawerMenu(), DrawerLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            gravity = GravityCompat.START
        })
        setContentView(drawerLayout)
    }

    private fun drawerMenu(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundColor(color(R.color.charcoal_light))
        ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(18), bars.top + dp(18), dp(18), bars.bottom + dp(18))
            insets
        }
        addView(titleText("Lofit").apply { textSize = 30f; setPadding(dp(8), 0, 0, dp(20)) })
        listOf(
            Screen.WORKOUT to getString(R.string.nav_workout),
            Screen.ROUTINES to getString(R.string.nav_routines),
            Screen.EXERCISES to getString(R.string.nav_exercises),
            Screen.RECORDS to getString(R.string.nav_records),
            Screen.HISTORY to getString(R.string.nav_history),
            Screen.SETTINGS to getString(R.string.nav_settings),
        ).forEach { (screen, label) ->
            addView(actionButton(label, secondary = true) {
                currentScreen = screen
                route = Route.Root
                routineDraft = null
                drawerLayout.closeDrawer(GravityCompat.START, false)
                render()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply { bottomMargin = dp(8) })
        }
    }

    private fun render(restoreScrollY: Int? = null) {
        if (repository.data.activeWorkout != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            restEndsAt = 0L
            if (::restBar.isInitialized) restBar.visibility = View.GONE
            clearActiveHold()
        }
        content.removeAllViews()
        timerText = null
        holdTimerText = null
        holdTimerButton = null
        holdDoneCheckBox = null
        val view = when (val activeRoute = route) {
            Route.Root -> rootScreen()
            is Route.ExercisePicker -> exercisePickerScreen(activeRoute)
            is Route.RoutineEditor -> routineEditorScreen(activeRoute.originalId)
            is Route.ExerciseEditor -> exerciseEditorScreen(activeRoute.exerciseId)
            is Route.WorkoutHistory -> repository.data.history.firstOrNull { it.id == activeRoute.workoutId }
                ?.let(::pastWorkoutScreen) ?: historyScreen()
            is Route.Confirm -> confirmationScreen(activeRoute)
        }
        content.addView(view)
        restoreScrollY?.let { scrollY ->
            (view as? ScrollView)?.post { view.scrollTo(0, scrollY) }
        }
        updateWorkoutNotification()
    }

    private fun rootScreen(): View = when (currentScreen) {
        Screen.WORKOUT -> repository.data.activeWorkout?.let(::activeWorkoutScreen) ?: workoutHome()
        Screen.ROUTINES -> routinesScreen()
        Screen.EXERCISES -> exercisesScreen()
        Screen.RECORDS -> recordsScreen()
        Screen.HISTORY -> historyScreen()
        Screen.SETTINGS -> settingsScreen()
    }

    private fun settingsScreen(): View = page("Settings", "Control how Lofit behaves.") { body ->
        body.addView(card {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(titleText("Sound cues"))
                    addView(metaText("Play rest and hold cues, even in silent or vibration mode."))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(12)
                })
                addView(Switch(this@MainActivity).apply {
                    isChecked = repository.data.soundCuesEnabled
                    showText = false
                    textOn = "On"
                    textOff = "Off"
                    isClickable = true
                    isFocusable = true
                    minWidth = dp(72)
                    textSize = 14f
                    setTextColor(color(R.color.cream))
                    thumbTintList = ColorStateList.valueOf(color(R.color.cream))
                    trackTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(),
                        ),
                        intArrayOf(color(R.color.coral_dark), color(R.color.sage)),
                    )
                    contentDescription = "Sound cues"
                    setOnCheckedChangeListener { _, enabled ->
                        repository.data.soundCuesEnabled = enabled
                        repository.save()
                    }
                })
            })
        })
        body.addView(sectionTitle("App data"))
        body.addView(metaText("Remove workout history, routines, custom exercises, and any active workout. Default exercises will be restored."))
        body.addView(actionButton("Reset all app data", secondary = true) {
            route = Route.Confirm(ConfirmAction.RESET_DATA)
            render()
        })
    }

    private fun workoutHome(): View = page("Ready to move?", "Local, focused, yours.") { body ->
        body.addView(workoutCalendar())
        body.addView(sectionTitle("Routines"))
        body.addView(actionButton(getString(R.string.start_workout)) {
            repository.data.activeWorkout = WorkoutSession(name = "Open workout")
            repository.save()
            ensureWorkoutNotification()
            render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })
        if (repository.data.routines.isEmpty()) {
            body.addView(emptyText("No routines yet. Build one in the Routines tab, or start empty and add exercises as you go."))
        }
        repository.data.routines.forEach { routine ->
            body.addView(card {
                addView(titleText(routine.name))
                addView(metaText("${routine.exercises.size} exercises · ${routine.defaultRestSeconds}s default rest"))
                addView(exerciseMuscleMaps(routine.exercises.map { it.exerciseId }))
                addView(actionButton("Start routine") { startRoutine(routine) })
            })
        }
    }

    private fun activeWorkoutScreen(workout: WorkoutSession): View {
        val workoutTimer = TextView(this).apply {
            textSize = 18f
            setTextColor(color(R.color.cream))
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            contentDescription = "Workout timer"
        }
        timerText = workoutTimer
        val view = page(workout.name, "Workout in progress", headerEnd = workoutTimer) { body ->
            updateWorkoutTimer()
            body.addView(restInput(workout.defaultRestSeconds) { seconds ->
                workout.defaultRestSeconds = seconds
                repository.save()
            })

            val exerciseCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val cardViews = mutableMapOf<String, View>()
            val updateActionStates = mutableMapOf<String, (Boolean, Boolean) -> Unit>()
            val recordIndicators = mutableMapOf<String, View>()
            fun updateRecordIndicators() {
                val recordSetIds = repository.data.recordSetIds(workout)
                recordIndicators.forEach { (setId, indicator) ->
                    indicator.visibility = if (setId in recordSetIds) View.VISIBLE else View.INVISIBLE
                }
            }
            fun updateExerciseActions() {
                workout.exercises.forEachIndexed { index, item ->
                    updateActionStates[item.id]?.invoke(index > 0, index < workout.exercises.lastIndex)
                }
            }
            fun moveWorkoutExercise(item: WorkoutExercise, offset: Int) {
                val fromIndex = workout.exercises.indexOf(item)
                val toIndex = fromIndex + offset
                if (fromIndex !in workout.exercises.indices || toIndex !in workout.exercises.indices) return
                workout.moveExercise(fromIndex, toIndex)
                cardViews[item.id]?.let { card ->
                    exerciseCards.removeView(card)
                    exerciseCards.addView(card, toIndex)
                }
                updateExerciseActions()
                repository.save()
            }
            fun removeWorkoutExercise(item: WorkoutExercise) {
                if (activeHoldWorkoutExerciseId == item.id) clearActiveHold()
                workout.exercises.remove(item)
                cardViews.remove(item.id)?.let(exerciseCards::removeView)
                updateActionStates.remove(item.id)
                if (workout.exercises.isEmpty()) {
                    exerciseCards.addView(emptyText("This workout is empty. Add any exercise when you’re ready."))
                }
                updateExerciseActions()
                repository.save()
                updateWorkoutNotification(force = true)
            }
            if (workout.exercises.isEmpty()) {
                exerciseCards.addView(emptyText("This workout is empty. Add any exercise when you’re ready."))
            }
            workout.exercises.forEachIndexed { index, item ->
                exercise(item.exerciseId)?.let { movement ->
                    val card = workoutExerciseCard(
                        workout = workout,
                        item = item,
                        exercise = movement,
                        index = index,
                        onMoveUp = { moveWorkoutExercise(item, -1) },
                        onMoveDown = { moveWorkoutExercise(item, 1) },
                        onRemove = { removeWorkoutExercise(item) },
                        onActionStateUpdaterReady = { updater -> updateActionStates[item.id] = updater },
                        onRecordIndicatorReady = { setId, indicator -> recordIndicators[setId] = indicator },
                        onSetsChanged = ::updateRecordIndicators,
                    )
                    cardViews[item.id] = card
                    exerciseCards.addView(card)
                }
            }
            updateRecordIndicators()
            body.addView(exerciseCards)
            body.addView(actionButton("Add exercise") {
                val scrollY = (content.getChildAt(0) as? ScrollView)?.scrollY
                route = Route.ExercisePicker(PickerTarget.WORKOUT, scrollY)
                render()
            })
            body.addView(divider())
            body.addView(actionButton("Finish workout") {
                endWorkout(ConfirmAction.FINISH_WORKOUT)
            })
            body.addView(actionButton("Discard workout", secondary = true) {
                endWorkout(ConfirmAction.DISCARD_WORKOUT)
            })
        }
        return view
    }

    private fun endWorkout(action: ConfirmAction) {
        if (repository.data.activeWorkout?.setCount() == 0) {
            discardActiveWorkout()
            repository.save()
            route = Route.Root
        } else {
            route = Route.Confirm(action)
        }
        render()
    }

    private fun discardActiveWorkout() {
        repository.data.activeWorkout = null
        restEndsAt = 0L
        clearActiveHold()
    }

    private fun workoutExerciseCard(
        workout: WorkoutSession,
        item: WorkoutExercise,
        exercise: Exercise,
        index: Int,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onRemove: () -> Unit,
        onActionStateUpdaterReady: ((Boolean, Boolean) -> Unit) -> Unit,
        onRecordIndicatorReady: (String, View) -> Unit,
        onSetsChanged: () -> Unit,
    ): View = card(horizontalPadding = 0) {
        addView(exerciseHeading(exercise, large = true, showMuscle = false, actions = exerciseActions(
            exercise = exercise,
            canMoveUp = index > 0,
            canMoveDown = index < workout.exercises.lastIndex,
            onMoveUp = onMoveUp,
            onMoveDown = onMoveDown,
            onRemove = onRemove,
            onStateUpdaterReady = onActionStateUpdaterReady,
        )), insetCardLayoutParams())
        val actionWidth = SET_DONE_ACTION_WIDTH_DP + SET_RECORD_ACTION_WIDTH_DP +
            if (exercise.type.tracksDuration) SET_HOLD_ACTION_WIDTH_DP else 0
        addView(plannedSetHeader(
            exercise,
            if (exercise.type.tracksDuration) "Start  Done" else "Done",
            endWidth = actionWidth,
            showActiveHold = exercise.type.tracksDuration,
        ))
        val setRows = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rowViews = mutableMapOf<String, View>()
        val updateRowIndices = mutableMapOf<String, (Int) -> Unit>()
        fun removeWorkoutSets(fromIndex: Int, allBelow: Boolean) {
            val endIndex = if (allBelow) item.plannedSets.size else fromIndex + 1
            val indices = fromIndex until endIndex
            val removedSets = item.plannedSets.subList(fromIndex, endIndex).toList()
            val loggedIds = indices.mapNotNull { setIndex ->
                item.plannedSets.getOrNull(setIndex)?.let { set -> loggedSetFor(item, set)?.id }
            }.toSet()
            if (activeHoldWorkoutExerciseId == item.id && removedSets.any { it.id == activeHoldPlannedSetId }) {
                clearActiveHold()
            }
            item.sets.removeAll { it.id in loggedIds }
            item.plannedSets.subList(fromIndex, endIndex).clear()
            removedSets.forEach { removedSet ->
                rowViews.remove(removedSet.id)?.let(setRows::removeView)
                updateRowIndices.remove(removedSet.id)
            }
            item.plannedSets.forEachIndexed { setIndex, set ->
                updateRowIndices[set.id]?.invoke(setIndex)
            }
            repository.save()
            onSetsChanged()
            updateWorkoutNotification(force = true)
        }
        fun addWorkoutSetRow(plannedSet: PlannedSet, setIndex: Int) {
            val loggedSet = loggedSetFor(item, plannedSet)
            val done = largeCheckBox().apply {
                isChecked = loggedSet != null
                contentDescription = "Set ${setIndex + 1} done"
            }
            val recordIndicator = ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_crown)
                contentDescription = "Set ${setIndex + 1} record"
                visibility = View.INVISIBLE
                setPadding(dp(5), dp(12), dp(5), dp(12))
            }
            loggedSet?.let { onRecordIndicatorReady(it.id, recordIndicator) }
            lateinit var activeHoldAmount: EditText
            val holdButton = if (exercise.type.tracksDuration) smallButton(
                if (isActiveHold(item, plannedSet)) "■" else "▶",
            ) {}.apply {
                textSize = 18f
                contentDescription = if (isActiveHold(item, plannedSet)) "Stop set ${setIndex + 1} hold" else "Start set ${setIndex + 1} hold"
                setOnClickListener {
                    if (isActiveHold(item, plannedSet)) {
                        stopActiveHold()
                    } else {
                        startInlineHold(item, plannedSet, activeHoldAmount, this, done)
                    }
                }
            } else null
            val rowActions = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                holdButton?.let {
                    addView(setGridControl(it), LinearLayout.LayoutParams(dp(SET_HOLD_ACTION_WIDTH_DP), dp(48)))
                }
                addView(done, LinearLayout.LayoutParams(dp(SET_DONE_ACTION_WIDTH_DP), dp(56)))
                addView(recordIndicator, LinearLayout.LayoutParams(dp(SET_RECORD_ACTION_WIDTH_DP), dp(56)))
            }
            val setRow = plannedSetEditor(
                exercise = exercise,
                plannedSet = plannedSet,
                setIndex = setIndex,
                endAction = rowActions,
                endActionWidth = actionWidth,
                endActionHeight = 56,
                completed = loggedSet != null,
                onRemove = {
                    item.plannedSets.indexOf(plannedSet).takeIf { it >= 0 }
                        ?.let { removeWorkoutSets(it, false) }
                },
                onRemoveAndBelow = {
                    item.plannedSets.indexOf(plannedSet).takeIf { it >= 0 }
                        ?.let { removeWorkoutSets(it, true) }
                },
                showActiveHold = exercise.type.tracksDuration,
                activeHoldRunning = isActiveHold(item, plannedSet),
                onActiveAmountReady = { amount ->
                    activeHoldAmount = amount
                    if (isActiveHold(item, plannedSet)) {
                        holdTimerText = amount
                        holdTimerButton = holdButton
                        holdDoneCheckBox = done
                    }
                },
                onIndexUpdaterReady = { updateEditorIndex ->
                    updateRowIndices[plannedSet.id] = { newIndex ->
                        updateEditorIndex(newIndex)
                        done.contentDescription = "Set ${newIndex + 1} done"
                        recordIndicator.contentDescription = "Set ${newIndex + 1} record"
                        holdButton?.contentDescription = if (isActiveHold(item, plannedSet)) {
                            "Stop set ${newIndex + 1} hold"
                        } else {
                            "Start set ${newIndex + 1} hold"
                        }
                    }
                },
                onChanged = {
                    loggedSetFor(item, plannedSet)?.let { syncLoggedSet(it, plannedSet, exercise) }
                    repository.save()
                    onSetsChanged()
                },
            )
            done.setOnCheckedChangeListener { _, checked ->
                val currentIndex = item.plannedSets.indexOf(plannedSet)
                if (currentIndex < 0) return@setOnCheckedChangeListener
                if (checked) {
                    if (isActiveHold(item, plannedSet) && !completingHoldAutomatically) stopActiveHold()
                    val loggedSet = loggedSetFor(item, plannedSet)
                    if (loggedSet == null) {
                        val newSet = loggedSetFrom(plannedSet, exercise)
                        item.sets.add(newSet)
                        onRecordIndicatorReady(newSet.id, recordIndicator)
                    } else {
                        syncLoggedSet(loggedSet, plannedSet, exercise)
                    }
                    if (!completingHoldAutomatically) startRest(item, workout)
                } else {
                    loggedSetFor(item, plannedSet)?.let { removedSet ->
                        item.sets.remove(removedSet)
                        onRecordIndicatorReady(removedSet.id, recordIndicator.apply { visibility = View.INVISIBLE })
                    }
                }
                setRow.setBackgroundColor(if (checked) color(R.color.done_green) else Color.TRANSPARENT)
                repository.save()
                onSetsChanged()
                updateWorkoutNotification(force = true)
            }
            rowViews[plannedSet.id] = setRow
            setRows.addView(setRow)
        }
        item.plannedSets.forEachIndexed { setIndex, plannedSet -> addWorkoutSetRow(plannedSet, setIndex) }
        addView(setRows)
        addView(actionButton("Add set", secondary = true) {
            val next = item.plannedSets.lastOrNull()?.copy(id = UUID.randomUUID().toString(), actualHoldSeconds = null)
                ?: PlannedSet(holdSeconds = exercise.minimumHoldSeconds)
            item.plannedSets.add(next)
            repository.save()
            addWorkoutSetRow(next, item.plannedSets.lastIndex)
            updateWorkoutNotification(force = true)
        })
        addView(restOverrideInput(item.restSecondsOverride) { seconds ->
            item.restSecondsOverride = seconds
            repository.save()
        }, insetCardLayoutParams())
    }

    private fun routinesScreen(): View = page("Routines", "Prescribe each movement before you train.") { body ->
        body.addView(actionButton("Create routine") { openRoutineEditor(null) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(16)
            })
        if (repository.data.routines.isEmpty()) body.addView(emptyText("No routines saved."))
        repository.data.routines.forEach { routine ->
            body.addView(card {
                addView(titleText(routine.name))
                addView(metaText("Default rest ${routine.defaultRestSeconds}s"))
                addView(exerciseMuscleMaps(routine.exercises.map { it.exerciseId }))
                addView(horizontalActions(
                    actionButton("Start") { startRoutine(routine) },
                    actionButton("Edit", secondary = true) { openRoutineEditor(routine) },
                    actionButton("Delete", secondary = true) {
                        route = Route.Confirm(ConfirmAction.DELETE_ROUTINE, routine.id)
                        render()
                    },
                ))
            })
        }
    }

    private fun routineEditorScreen(originalId: String?): View {
        val draft = routineDraft ?: return routinesScreen()
        return page(
            if (originalId == null) "New routine" else "Edit routine",
            if (originalId == null) null else "Set the exact work you intend to do.",
        ) { body ->
            val name = textField("Routine name").apply {
                setText(draft.name)
                doAfterTextChanged { draft.name = it.toString() }
            }
            body.addView(fieldLabel("Routine name"))
            body.addView(name)
            body.addView(restInput(draft.defaultRestSeconds) { seconds ->
                draft.defaultRestSeconds = seconds
            })
            body.addView(sectionTitle("Exercises"))
            val exerciseCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val cardViews = mutableMapOf<String, View>()
            val updateActionStates = mutableMapOf<String, (Boolean, Boolean) -> Unit>()
            fun updateExerciseActions() {
                draft.exercises.forEachIndexed { index, item ->
                    updateActionStates[item.id]?.invoke(index > 0, index < draft.exercises.lastIndex)
                }
            }
            fun moveExercise(item: RoutineExercise, offset: Int) {
                val fromIndex = draft.exercises.indexOf(item)
                val toIndex = fromIndex + offset
                if (fromIndex !in draft.exercises.indices || toIndex !in draft.exercises.indices) return
                draft.exercises.add(toIndex, draft.exercises.removeAt(fromIndex))
                cardViews[item.id]?.let { exerciseCard ->
                    exerciseCards.removeView(exerciseCard)
                    exerciseCards.addView(exerciseCard, toIndex)
                }
                updateExerciseActions()
            }
            fun removeExercise(item: RoutineExercise) {
                draft.exercises.remove(item)
                cardViews.remove(item.id)?.let(exerciseCards::removeView)
                updateActionStates.remove(item.id)
                if (draft.exercises.isEmpty()) {
                    exerciseCards.addView(emptyText("Add exercises, then specify sets and repetitions or hold time."))
                }
                updateExerciseActions()
            }
            if (draft.exercises.isEmpty()) {
                exerciseCards.addView(emptyText("Add exercises, then specify sets and repetitions or hold time."))
            }
            body.addView(exerciseCards)
            draft.exercises.forEachIndexed { index, item ->
                val movement = exercise(item.exerciseId) ?: return@forEachIndexed
                val exerciseCard = card(horizontalPadding = 0) {
                    addView(exerciseHeading(movement, large = true, actions = exerciseActions(
                        exercise = movement,
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.exercises.lastIndex,
                        onMoveUp = { moveExercise(item, -1) },
                        onMoveDown = { moveExercise(item, 1) },
                        onRemove = { removeExercise(item) },
                        onStateUpdaterReady = { updater -> updateActionStates[item.id] = updater },
                    )), insetCardLayoutParams())
                    val setRows = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    val rowViews = mutableMapOf<String, View>()
                    val updateRowIndices = mutableMapOf<String, (Int) -> Unit>()
                    fun removeRoutineSets(fromIndex: Int, allBelow: Boolean) {
                        val endIndex = if (allBelow) item.plannedSets.size else fromIndex + 1
                        val removedSets = item.plannedSets.subList(fromIndex, endIndex).toList()
                        item.plannedSets.subList(fromIndex, endIndex).clear()
                        removedSets.forEach { removedSet ->
                            rowViews.remove(removedSet.id)?.let(setRows::removeView)
                            updateRowIndices.remove(removedSet.id)
                        }
                        item.plannedSets.forEachIndexed { setIndex, set ->
                            updateRowIndices[set.id]?.invoke(setIndex)
                        }
                    }
                    fun addRoutineSetRow(plannedSet: PlannedSet, setIndex: Int) {
                        val row = plannedSetEditor(
                            movement,
                            plannedSet,
                            setIndex,
                            onRemove = {
                                item.plannedSets.indexOf(plannedSet).takeIf { it >= 0 }
                                    ?.let { removeRoutineSets(it, false) }
                            },
                            onRemoveAndBelow = {
                                item.plannedSets.indexOf(plannedSet).takeIf { it >= 0 }
                                    ?.let { removeRoutineSets(it, true) }
                            },
                            onIndexUpdaterReady = { updater -> updateRowIndices[plannedSet.id] = updater },
                        )
                        rowViews[plannedSet.id] = row
                        setRows.addView(row)
                    }
                    setRows.addView(plannedSetHeader(movement, null))
                    item.plannedSets.forEachIndexed { setIndex, plannedSet -> addRoutineSetRow(plannedSet, setIndex) }
                    addView(setRows)
                    addView(actionButton("Add set", secondary = true) {
                        val next = item.plannedSets.lastOrNull()?.copy(id = UUID.randomUUID().toString(), actualHoldSeconds = null)
                            ?: PlannedSet(holdSeconds = movement.minimumHoldSeconds)
                        item.plannedSets.add(next)
                        addRoutineSetRow(next, item.plannedSets.lastIndex)
                    })
                    addView(restOverrideInput(item.restSecondsOverride) { seconds ->
                        item.restSecondsOverride = seconds
                    }, insetCardLayoutParams())
                }
                cardViews[item.id] = exerciseCard
                exerciseCards.addView(exerciseCard)
            }
            body.addView(actionButton("Add Exercise") {
                route = Route.ExercisePicker(PickerTarget.ROUTINE)
                render()
            })
            body.addView(divider("Routine actions separator"))
            body.addView(actionButton("Save routine") {
                if (draft.name.isBlank()) {
                    Toast.makeText(this, "Give the routine a name", Toast.LENGTH_SHORT).show()
                } else {
                    saveRoutineDraft(originalId)
                }
            })
            body.addView(actionButton("Cancel", secondary = true) { cancelRoutineEditor() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(16)
                })
        }
    }

    private fun exercisePickerScreen(picker: Route.ExercisePicker): View = page("Add exercise") { body ->
        val target = picker.target
        val search = textField("Search name or muscle").apply {
            inputType = InputType.TYPE_CLASS_TEXT
            contentDescription = "Search exercises"
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun updateResults(query: String) {
            results.removeAllViews()
            val allMatches = repository.data.exercises.filter { exercise ->
                matchesSearchQuery(query, exercise.name, exercise.primaryMuscle, exercise.secondaryMuscles, exerciseSearchTerms(exercise))
            }
            val matches = allMatches.take(40)
            if (matches.isEmpty()) results.addView(emptyText("No matching exercises."))
            if (allMatches.size > matches.size) results.addView(metaText("Showing 40 of ${allMatches.size}. Search to narrow the list."))
            matches.forEach { movement ->
                results.addView(card {
                    addView(exerciseHeading(movement, large = true))
                    addView(metaText(listOf(exerciseTrackingLabel(movement), if (movement.asymmetric) "left / right" else null).filterNotNull().joinToString(" · ")))
                    addView(metaText("Primary: ${movement.primaryMuscle} · Secondary: ${movement.secondaryMuscles}"))
                    addView(actionButton("Add ${movement.name}") {
                        if (target == PickerTarget.WORKOUT) {
                            val lastKnownSets = repository.data.lastKnownSets(movement.id)
                            repository.data.activeWorkout?.exercises?.add(WorkoutExercise(
                                exerciseId = movement.id,
                                plannedSets = lastKnownSets ?: mutableListOf(),
                            ))
                            repository.save()
                            route = Route.Root
                        } else {
                            routineDraft?.exercises?.add(RoutineExercise(
                                exerciseId = movement.id,
                                plannedSets = MutableList(3) { PlannedSet(holdSeconds = movement.minimumHoldSeconds) },
                            ))
                            route = Route.RoutineEditor(repository.data.routines.firstOrNull { it.id == routineDraft?.id }?.id)
                        }
                        render(if (target == PickerTarget.WORKOUT) picker.returnScrollY else null)
                    })
                })
            }
        }
        search.doAfterTextChanged { updateResults(it.toString()) }
        body.addView(search)
        body.addView(results)
        updateResults("")
    }

    private fun exercisesScreen(): View = page("Exercise library", "Search built-in and custom movements.") { body ->
        body.addView(actionButton("Create custom exercise") {
            route = Route.ExerciseEditor(null)
            render()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })
        val search = textField("Search name or muscle")
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun updateResults(query: String) {
            results.removeAllViews()
            val allMatches = repository.data.exercises.filter { movement ->
                matchesSearchQuery(query, movement.name, movement.primaryMuscle, movement.secondaryMuscles, exerciseSearchTerms(movement))
            }
            val matches = allMatches.take(40)
            if (allMatches.size > matches.size) results.addView(metaText("Showing 40 of ${allMatches.size}. Search to narrow the list."))
            matches.forEach { movement ->
                results.addView(card {
                    addView(exerciseHeading(movement, large = true))
                    addView(metaText(listOf(exerciseTrackingLabel(movement), if (movement.asymmetric) "left / right" else null).filterNotNull().joinToString(" · ")))
                    addView(metaText("Primary: ${movement.primaryMuscle} · Secondary: ${movement.secondaryMuscles}"))
                    recordFor(movement)?.let { addView(accentText(it)) }
                    if (movement.custom) addView(horizontalActions(
                        actionButton("Edit", secondary = true) {
                            route = Route.ExerciseEditor(movement.id)
                            render()
                        },
                        actionButton("Delete", secondary = true) {
                            route = Route.Confirm(ConfirmAction.DELETE_EXERCISE, movement.id)
                            render()
                        },
                    ))
                })
            }
        }
        search.doAfterTextChanged { updateResults(it.toString()) }
        body.addView(search)
        body.addView(results)
        updateResults("")
    }

    private fun exerciseEditorScreen(exerciseId: String?): View {
        val existing = exerciseId?.let(::exercise)
        return page(if (existing == null) "New exercise" else "Edit exercise", "Define how this movement is tracked.") { body ->
            val name = textField("Exercise name").apply { setText(existing?.name.orEmpty()) }
            val primary = muscleGroupField("Search primary muscle", existing?.primaryMuscle.orEmpty())
            val secondary = muscleGroupField("Search secondary muscles", existing?.secondaryMuscles.orEmpty(), multiple = true)
            val typeGroup = choiceGroup(ExerciseType.entries.map { it.label }, existing?.type?.ordinal ?: 0)
            val weightGroup = choiceGroup(listOf("Bodyweight", "Weighted"), if (existing?.weighted == true) 1 else 0)
            val trackingGroup = choiceGroup(listOf("Track both sides together", "Track left and right"), if (existing?.asymmetric == true) 1 else 0)
            val hold = numberField("Minimum hold cue seconds").apply { setText(formatInteger(existing?.minimumHoldSeconds ?: 30)) }
            body.addView(fieldLabel("Exercise name")); body.addView(name)
            body.addView(fieldLabel("Exercise type")); body.addView(typeGroup)
            body.addView(fieldLabel("Load")); body.addView(weightGroup)
            body.addView(fieldLabel("Side tracking")); body.addView(trackingGroup)
            body.addView(fieldLabel("Primary muscle")); body.addView(primary)
            body.addView(fieldLabel("Secondary muscles")); body.addView(secondary)
            body.addView(fieldLabel("Hold cue")); body.addView(hold)
            body.addView(actionButton("Save exercise") {
                val primaryMuscle = MuscleGroups.canonical(primary.text.toString())
                val secondaryMuscles = MuscleGroups.canonicalList(secondary.text.toString())
                if (name.text.isBlank() || primaryMuscle == null) {
                    Toast.makeText(this, "Add a name and choose a primary muscle", Toast.LENGTH_SHORT).show()
                    return@actionButton
                }
                if (secondaryMuscles == null) {
                    Toast.makeText(this, "Choose secondary muscles from the list", Toast.LENGTH_SHORT).show()
                    return@actionButton
                }
                val movement = existing ?: Exercise(
                    id = UUID.randomUUID().toString(),
                    name = name.text.toString().trim(),
                    type = ExerciseType.REPETITIONS,
                    asymmetric = false,
                    primaryMuscle = primaryMuscle,
                    secondaryMuscles = secondaryMuscles,
                    custom = true,
                )
                movement.name = name.text.toString().trim()
                movement.type = ExerciseType.entries[selectedChoice(typeGroup)]
                movement.weighted = selectedChoice(weightGroup) == 1
                movement.asymmetric = selectedChoice(trackingGroup) == 1
                movement.primaryMuscle = primaryMuscle
                movement.secondaryMuscles = secondaryMuscles
                movement.minimumHoldSeconds = hold.text.toString().toIntOrNull()?.coerceIn(1, 3_600) ?: 30
                if (existing == null) repository.data.exercises.add(movement)
                repository.save()
                route = Route.Root
                currentScreen = Screen.EXERCISES
                render()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
            body.addView(actionButton("Cancel", secondary = true) { navigateBack() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(16)
                })
        }
    }

    private fun historyScreen(): View = page("History", "Your training stays on this device.") { body ->
        val history = repository.data.history.sortedByDescending { it.completedAt }
        if (history.isEmpty()) body.addView(emptyText("Complete a workout and it will appear here."))
        history.forEachIndexed { index, workout ->
            body.addView(card {
                addView(titleText(workout.name))
                addView(metaText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(workout.completedAt ?: workout.startedAt))))
                addView(metaText("${workout.setCount()} sets · ${formatWeight(workout.totalVolume())} kg volume · ${formatDuration((workout.completedAt ?: workout.startedAt) - workout.startedAt)}"))
                addView(exerciseMuscleMaps(workout.exercises.map { it.exerciseId }))
                if (index == 0 && workout.wasCompletedToday()) {
                    addView(actionButton("Continue workout") {
                        continueHistoryWorkout(workout)
                    }.apply {
                        contentDescription = "Continue ${workout.name}"
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(12)
                    })
                }
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(actionButton("View workout") {
                        openWorkoutHistory(workout)
                    }.apply {
                        contentDescription = "View ${workout.name}"
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(12)
                    })
                    addView(actionButton("Create routine", secondary = true) {
                        routineDraft = workout.toRoutine()
                        route = Route.RoutineEditor(null)
                        render()
                    }.apply {
                        contentDescription = "Create routine from ${workout.name}"
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(12)
                })
            })
        }
    }

    private fun openWorkoutHistory(workout: WorkoutSession) {
        route = Route.WorkoutHistory(workout.id, (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0)
        render()
    }

    private fun pastWorkoutScreen(workout: WorkoutSession): View = page(
        workout.name,
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(workout.completedAt ?: workout.startedAt)),
        headerEnd = titleText(formatDuration((workout.completedAt ?: workout.startedAt) - workout.startedAt)).apply {
            contentDescription = "Workout duration"
        },
    ) { body ->
        body.addView(metaText("${workout.setCount()} sets · ${formatWeight(workout.totalVolume())} kg volume"))
        body.addView(metaText("Set Rest: ${workout.defaultRestSeconds}s"))
        workout.exercises.forEach { item ->
            exercise(item.exerciseId)?.let { movement ->
                body.addView(card(horizontalPadding = 0) {
                    addView(exerciseHeading(movement, large = true, showMuscle = false), insetCardLayoutParams())
                    val actionWidth = SET_DONE_ACTION_WIDTH_DP + SET_RECORD_ACTION_WIDTH_DP
                    addView(plannedSetHeader(movement, "Done", endWidth = actionWidth,
                        showActiveHold = movement.type.tracksDuration, showHoldAction = false))
                    fun addSet(plannedSet: PlannedSet, loggedSet: LoggedSet?, index: Int) {
                        val displaySet = plannedSet.copy(
                            type = loggedSet?.type ?: plannedSet.type,
                            weightKg = loggedSet?.weightKg ?: plannedSet.weightKg,
                            reps = loggedSet?.reps ?: plannedSet.reps,
                            actualHoldSeconds = loggedSet?.holdSeconds ?: plannedSet.actualHoldSeconds,
                            side = loggedSet?.side ?: plannedSet.side,
                        )
                        addView(plannedSetEditor(
                            exercise = movement,
                            plannedSet = displaySet,
                            setIndex = index,
                            endAction = LinearLayout(this@MainActivity).apply {
                                addView(largeCheckBox().apply {
                                    isChecked = loggedSet != null
                                    isEnabled = false
                                    isClickable = false
                                    isFocusable = false
                                    contentDescription = "Set ${index + 1} done"
                                }, LinearLayout.LayoutParams(dp(SET_DONE_ACTION_WIDTH_DP), dp(56)))
                            },
                            endActionWidth = actionWidth,
                            endActionHeight = 56,
                            completed = loggedSet != null,
                            showActiveHold = movement.type.tracksDuration,
                            readOnly = true,
                        ))
                    }
                    item.plannedSets.forEachIndexed { index, set -> addSet(set, loggedSetFor(item, set), index) }
                    addView(metaText("Set Rest: ${item.restSeconds(workout.defaultRestSeconds)}s"), insetCardLayoutParams())
                })
            }
        }
    }

    private fun exerciseMuscleMaps(exerciseIds: List<String>): View {
        val muscleGroups = exerciseIds.mapNotNull(::exercise).flatMap { movement ->
            listOf(movement.primaryMuscle) + movement.secondaryMuscles.split(',')
        }.map(String::trim).filter(String::isNotBlank).toSet()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(10))
            listOf("Front" to MuscleMapSide.FRONT, "Back" to MuscleMapSide.BACK).forEach { (label, side) ->
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(metaText(label).apply { gravity = Gravity.CENTER })
                    addView(MuscleMapView(this@MainActivity, side, muscleGroups))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }
    }

    private fun recordsScreen(): View = page("Records", "Your best logged set for each exercise.") { body ->
        val records = repository.data.oneRepMaxes()
        if (records.isEmpty()) {
            body.addView(emptyText("Complete a set and finish the workout to earn your first record."))
        }
        records.forEach { record ->
            body.addView(card {
                addView(exerciseHeading(record.exercise, large = true))
                val value = when {
                    record.exercise.type.tracksDuration -> "${record.set.holdSeconds}s"
                    record.exercise.weighted -> "${formatWeight(record.set.weightKg)} kg"
                    else -> "${record.set.reps} reps"
                }
                val label = when {
                    record.exercise.type.tracksDuration -> "Longest hold"
                    record.exercise.weighted -> "Highest weight"
                    else -> "Most repetitions"
                }
                addView(accentText(value).apply { textSize = 22f })
                addView(metaText(label))
                record.volumeSet?.let { set ->
                    addView(accentText("${formatWeight(set.volume())} kg").apply { textSize = 22f })
                    addView(metaText("Highest set volume"))
                }
            })
        }
    }

    private fun workoutCalendar(): View {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val month = calendarMonth
        val trainingDays = repository.data.history.filter { it.completedAt != null }
            .sortedByDescending { it.completedAt }
            .groupBy { Instant.ofEpochMilli(it.completedAt!!).atZone(zone).toLocalDate() }
        val monthTitle = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))

        return card {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(metaText(monthTitle).apply {
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    textSize = 18f
                    setTextColor(color(R.color.white))
                    setTypeface(typeface, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(smallButton("‹") {
                    calendarMonth = calendarMonth.minusMonths(1)
                    render()
                }.apply {
                    contentDescription = "Previous month"
                }, LinearLayout.LayoutParams(dp(44), dp(42)).apply {
                    marginEnd = dp(6)
                })
                addView(smallButton("›") {
                    calendarMonth = calendarMonth.plusMonths(1)
                    render()
                }.apply {
                    contentDescription = "Next month"
                }, LinearLayout.LayoutParams(dp(44), dp(42)))
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                DayOfWeek.entries.forEach { weekday ->
                    addView(metaText(weekday.getDisplayName(TextStyle.NARROW, Locale.getDefault())).apply {
                        gravity = Gravity.CENTER
                        setTypeface(typeface, Typeface.BOLD)
                    }, LinearLayout.LayoutParams(0, dp(30), 1f))
                }
            })

            val firstOffset = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
            var date = month.atDay(1).minusDays(firstOffset.toLong())
            repeat(6) {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    repeat(7) {
                        val cellDate = date
                        val isInMonth = YearMonth.from(cellDate) == month
                        addView(TextView(this@MainActivity).apply {
                            text = cellDate.dayOfMonth.toString()
                            textSize = 14f
                            gravity = Gravity.CENTER
                            setTextColor(color(if (isInMonth) R.color.cream else R.color.sage))
                            val workouts = trainingDays[cellDate].orEmpty()
                            val workoutCount = workouts.size
                            val trained = workoutCount > 0
                            contentDescription = "$cellDate, ${when (workoutCount) {
                                0 -> "no workout"
                                1 -> "workout completed"
                                else -> "$workoutCount workouts completed"
                            }}"
                            if (trained || cellDate == today) {
                                textSize = 16f
                                setTypeface(typeface, Typeface.BOLD)
                            }
                            if (trained) {
                                setTextColor(color(R.color.charcoal))
                                elevation = 0f
                                isFocusable = true
                                setOnClickListener {
                                    if (workouts.size == 1) {
                                        openWorkoutHistory(workouts.single())
                                    } else {
                                        val timeFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
                                        AlertDialog.Builder(this@MainActivity)
                                            .setTitle(DateFormat.getDateInstance(DateFormat.MEDIUM)
                                                .format(Date(workouts.first().completedAt!!)))
                                            .setItems(workouts.map { workout ->
                                                "${workout.name} · ${timeFormat.format(Date(workout.completedAt!!))}"
                                            }.toTypedArray()) { _, index ->
                                                openWorkoutHistory(workouts[index])
                                            }
                                            .show()
                                    }
                                }
                            }
                            background = GradientDrawable().apply {
                                shape = GradientDrawable.RECTANGLE
                                cornerRadius = dp(2).toFloat()
                                setColor(if (trained) color(R.color.coral) else Color.TRANSPARENT)
                                if (cellDate == today) {
                                    setStroke(dp(1), color(R.color.sage))
                                }
                            }
                        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                            marginStart = dp(2)
                            marginEnd = dp(2)
                        })
                        date = date.plusDays(1)
                    }
                })
            }
        }.apply { contentDescription = "Workout calendar" }
    }

    private fun confirmationScreen(confirm: Route.Confirm): View {
        val (title, message, buttonLabel) = when (confirm.action) {
            ConfirmAction.FINISH_WORKOUT -> Triple("Finish workout?", "The workout and all logged sets will be saved to history.", "Finish and save")
            ConfirmAction.DISCARD_WORKOUT -> Triple("Discard workout?", "All sets logged in this workout will be removed.", "Discard workout")
            ConfirmAction.DELETE_EXERCISE -> Triple("Delete exercise?", "The custom exercise will be removed from routines and the library.", "Delete exercise")
            ConfirmAction.DELETE_ROUTINE -> Triple("Delete routine?", "The routine will be removed. Workout history is unaffected.", "Delete routine")
            ConfirmAction.RESET_DATA -> Triple("Reset all app data?", "This permanently removes workout history, routines, custom exercises, and the active workout.", "Reset everything")
        }
        return page(title, message) { body ->
            body.addView(actionButton(buttonLabel) {
                when (confirm.action) {
                    ConfirmAction.FINISH_WORKOUT -> repository.data.activeWorkout?.let { workout ->
                        if (workout.setCount() == 0) {
                            discardActiveWorkout()
                        } else {
                            workout.completedAt = System.currentTimeMillis()
                            repository.data.history.add(workout)
                            discardActiveWorkout()
                        }
                    }
                    ConfirmAction.DISCARD_WORKOUT -> discardActiveWorkout()
                    ConfirmAction.DELETE_EXERCISE -> confirm.targetId?.let { id ->
                        repository.data.exercises.removeAll { it.id == id && it.custom }
                        repository.data.routines.forEach { routine -> routine.exercises.removeAll { it.exerciseId == id } }
                    }
                    ConfirmAction.DELETE_ROUTINE -> repository.data.routines.removeAll { it.id == confirm.targetId }
                    ConfirmAction.RESET_DATA -> {
                        repository.reset()
                        routineDraft = null
                        restEndsAt = 0L
                        clearActiveHold()
                        currentScreen = Screen.WORKOUT
                    }
                }
                if (confirm.action != ConfirmAction.RESET_DATA) repository.save()
                route = Route.Root
                render()
            })
            body.addView(actionButton("Cancel", secondary = true) { navigateBack() })
        }
    }

    private fun openRoutineEditor(existing: Routine?) {
        routineDraft = existing?.copy(
            exercises = existing.exercises.map { item ->
                item.copy(plannedSets = item.plannedSets.map { it.copy(actualHoldSeconds = null) }.toMutableList())
            }.toMutableList(),
        ) ?: Routine(name = "")
        route = Route.RoutineEditor(existing?.id)
        render()
    }

    private fun saveRoutineDraft(originalId: String?) {
        val draft = routineDraft ?: return
        if (originalId == null) {
            repository.data.routines.add(draft)
        } else {
            val index = repository.data.routines.indexOfFirst { it.id == originalId }
            if (index >= 0) repository.data.routines[index] = draft
        }
        repository.save()
        routineDraft = null
        currentScreen = Screen.ROUTINES
        route = Route.Root
        render()
    }

    private fun cancelRoutineEditor() {
        routineDraft = null
        route = Route.Root
        render()
    }

    private fun startRoutine(routine: Routine) {
        if (repository.data.activeWorkout != null) {
            Toast.makeText(this, "Finish or discard the active workout first", Toast.LENGTH_SHORT).show()
            currentScreen = Screen.WORKOUT
            route = Route.Root
            render()
            return
        }
        repository.data.activeWorkout = WorkoutSession(
            name = routine.name,
            defaultRestSeconds = routine.defaultRestSeconds,
            exercises = routine.exercises.map { item ->
                val plannedSets = repository.data.lastKnownSets(item.exerciseId)
                    ?: item.plannedSets.map {
                        it.copy(id = UUID.randomUUID().toString(), actualHoldSeconds = null)
                    }.toMutableList()
                WorkoutExercise(
                    exerciseId = item.exerciseId,
                    restSecondsOverride = item.restSecondsOverride,
                    plannedSets = plannedSets,
                )
            }.toMutableList(),
        )
        repository.save()
        ensureWorkoutNotification()
        currentScreen = Screen.WORKOUT
        route = Route.Root
        render()
    }

    private fun continueHistoryWorkout(workout: WorkoutSession) {
        if (repository.data.activeWorkout != null) {
            Toast.makeText(this, "Finish or discard the active workout first", Toast.LENGTH_SHORT).show()
        } else {
            if (repository.data.resumeWorkout(workout.id) == null) {
                render()
                return
            }
            repository.save()
            ensureWorkoutNotification()
        }
        currentScreen = Screen.WORKOUT
        route = Route.Root
        render()
    }

    private fun navigateBack() {
        val previousRoute = route
        route = when (previousRoute) {
            is Route.ExercisePicker -> if (previousRoute.target == PickerTarget.ROUTINE) {
                Route.RoutineEditor(repository.data.routines.firstOrNull { it.id == routineDraft?.id }?.id)
            } else Route.Root
            is Route.Confirm -> Route.Root
            is Route.RoutineEditor -> {
                routineDraft = null
                Route.Root
            }
            else -> Route.Root
        }
        render(when (previousRoute) {
            is Route.ExercisePicker -> previousRoute.takeIf { it.target == PickerTarget.WORKOUT }?.returnScrollY
            is Route.WorkoutHistory -> previousRoute.returnScrollY
            else -> null
        })
    }

    private fun startRest(item: WorkoutExercise, workout: WorkoutSession) {
        val seconds = item.restSeconds(workout.defaultRestSeconds)
        restEndsAt = if (seconds > 0) SystemClock.elapsedRealtime() + seconds * 1_000L else 0L
        updateRestBar()
        updateWorkoutNotification(force = true)
    }

    private fun updateWorkoutTimer() {
        val workout = repository.data.activeWorkout
        if (workout == null) {
            restEndsAt = 0L
            updateRestBar()
            return
        }
        val elapsed = max(0, ((System.currentTimeMillis() - workout.startedAt) / 1_000).toInt())
        timerText?.text = formatWorkoutTime(elapsed)
        updateRestBar()
        updateWorkoutNotification()
    }

    private fun createRestBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(10), dp(10))
        setBackgroundColor(color(R.color.coral_dark))
        contentDescription = "Rest timer overlay"
        visibility = View.GONE
        restTimeText = TextView(this@MainActivity).apply {
            textSize = 18f
            setTextColor(color(R.color.white))
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(restTimeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(smallButton("−15", ::subtractRest), LinearLayout.LayoutParams(dp(64), dp(48)))
        addView(smallButton("+15", ::addRest), LinearLayout.LayoutParams(dp(64), dp(48)).apply { marginStart = dp(6) })
        addView(smallButton("Skip", ::skipRest), LinearLayout.LayoutParams(dp(72), dp(48)).apply { marginStart = dp(6) })
    }

    private fun subtractRest() {
        if (restEndsAt <= 0L) return
        restEndsAt = max(SystemClock.elapsedRealtime(), restEndsAt - 15_000L)
        updateRestBar()
        updateWorkoutNotification(force = true)
    }

    private fun addRest() {
        if (restEndsAt <= 0L) return
        restEndsAt += 15_000L
        updateRestBar()
        updateWorkoutNotification(force = true)
    }

    private fun skipRest() {
        if (restEndsAt <= 0L) return
        restEndsAt = 0L
        updateRestBar()
        updateWorkoutNotification(force = true)
    }

    private fun updateRestBar() {
        if (!::restBar.isInitialized) return
        if (repository.data.activeWorkout == null) restEndsAt = 0L
        if (restEndsAt <= 0L) {
            restBar.visibility = View.GONE
            return
        }
        val remaining = ((restEndsAt - SystemClock.elapsedRealtime() + 999L) / 1_000L).toInt()
        if (remaining <= 0) {
            restEndsAt = 0L
            restBar.visibility = View.GONE
            playCue(ToneGenerator.TONE_DTMF_D, durationMs = 750, volume = 100)
            updateWorkoutNotification(force = true)
            return
        }
        restTimeText.text = "Rest ${formatSeconds(remaining)}"
        restBar.visibility = View.VISIBLE
    }

    private fun isActiveHold(item: WorkoutExercise, set: PlannedSet): Boolean =
        activeHoldWorkoutExerciseId == item.id && activeHoldPlannedSetId == set.id

    private fun startInlineHold(
        item: WorkoutExercise,
        set: PlannedSet,
        amount: EditText,
        button: MaterialButton,
        done: CheckBox,
    ) {
        stopActiveHold()
        restEndsAt = 0L
        updateRestBar()
        if (done.isChecked) done.isChecked = false
        activeHoldWorkoutExerciseId = item.id
        activeHoldPlannedSetId = set.id
        holdStartedAt = SystemClock.elapsedRealtime()
        holdCuePlayed = false
        set.actualHoldSeconds = 0
        holdTimerText = amount
        holdTimerButton = button
        holdDoneCheckBox = done
        updatingHoldTimer = true
        amount.setText("0")
        updatingHoldTimer = false
        amount.isEnabled = false
        button.text = "■"
        button.contentDescription = button.contentDescription.toString().replace("Start", "Stop")
        repository.save()
        updateWorkoutNotification(force = true)
    }

    private fun stopActiveHold() {
        val itemId = activeHoldWorkoutExerciseId ?: return
        val setId = activeHoldPlannedSetId ?: return
        val elapsed = max(1, ((SystemClock.elapsedRealtime() - holdStartedAt) / 1_000L).toInt())
        val workout = repository.data.activeWorkout ?: run { clearActiveHold(); return }
        val item = workout.exercises.firstOrNull { it.id == itemId } ?: run { clearActiveHold(); return }
        val plannedSet = item.plannedSets.firstOrNull { it.id == setId } ?: run { clearActiveHold(); return }
        plannedSet.actualHoldSeconds = elapsed
        val loggedSet = item.sets.firstOrNull { it.plannedSetId == plannedSet.id }
        val movement = exercise(item.exerciseId)
        if (loggedSet != null && movement != null) {
            syncLoggedSet(loggedSet, plannedSet, movement)
        }
        updatingHoldTimer = true
        holdTimerText?.setText(elapsed.toString())
        updatingHoldTimer = false
        holdTimerText?.isEnabled = true
        holdTimerButton?.apply {
            text = "▶"
            contentDescription = contentDescription.toString().replace("Stop", "Start")
        }
        repository.save()
        clearActiveHold()
        if (loggedSet != null && movement != null) {
            startRest(item, workout)
        }
        updateWorkoutNotification(force = true)
    }

    private fun clearActiveHold() {
        activeHoldWorkoutExerciseId = null
        activeHoldPlannedSetId = null
        holdStartedAt = 0L
        holdCuePlayed = false
        holdTimerText = null
        holdTimerButton = null
        holdDoneCheckBox = null
    }

    private fun updateHoldTimer() {
        val itemId = activeHoldWorkoutExerciseId ?: return
        val setId = activeHoldPlannedSetId ?: return
        if (holdStartedAt == 0L) return
        val elapsed = max(0, ((SystemClock.elapsedRealtime() - holdStartedAt) / 1_000).toInt())
        val elapsedText = elapsed.toString()
        if (holdTimerText?.text?.toString() != elapsedText) {
            updatingHoldTimer = true
            holdTimerText?.setText(elapsedText)
            updatingHoldTimer = false
            updateWorkoutNotification()
        }
        val item = repository.data.activeWorkout?.exercises?.firstOrNull { it.id == itemId }
        if (item?.plannedSets?.none { it.id == setId } != false) {
            clearActiveHold()
            return
        }
        val plannedSet = item?.plannedSets?.firstOrNull { it.id == setId }
        val movement = item?.let { exercise(it.exerciseId) }
        if (!holdCuePlayed && plannedSet != null && movement != null && elapsed >= plannedSet.holdSeconds) {
            holdCuePlayed = true
            plannedSet.actualHoldSeconds = elapsed
            loggedSetFor(item, plannedSet)?.let { syncLoggedSet(it, plannedSet, movement) }
                ?: item.sets.add(loggedSetFrom(plannedSet, movement))
            repository.save()
            completingHoldAutomatically = true
            holdDoneCheckBox?.isChecked = true
            completingHoldAutomatically = false
            playCue(ToneGenerator.TONE_PROP_ACK, durationMs = 500, volume = 100)
        }
    }

    private fun playCue(tone: Int, durationMs: Int = 300, volume: Int = 80) {
        if (!repository.data.soundCuesEnabled) return
        val generator = ToneGenerator(AudioManager.STREAM_ALARM, volume)
        generator.startTone(tone, durationMs)
        handler.postDelayed({ generator.release() }, durationMs + 200L)
    }

    private fun createWorkoutNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            WORKOUT_NOTIFICATION_CHANNEL,
            "Active workout",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Current exercise, set, and rest timer"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
    }

    private fun ensureWorkoutNotification() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            updateWorkoutNotification(force = true)
        } else {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun updateWorkoutNotification(force: Boolean = false) {
        val manager = getSystemService(NotificationManager::class.java)
        val workout = repository.data.activeWorkout
        if (workout == null) {
            lastWorkoutNotification = ""
            manager.cancel(WORKOUT_NOTIFICATION_ID)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val current = workout.exercises.firstNotNullOfOrNull { item ->
            val nextSet = item.plannedSets.indexOfFirst { set ->
                loggedSetFor(item, set) == null
            }
            when {
                item.plannedSets.isEmpty() -> Triple(item, null, 0)
                nextSet >= 0 -> Triple(item, item.plannedSets[nextSet], nextSet)
                else -> null
            }
        }
        val fallback = workout.exercises.lastOrNull()?.let { Triple(it, null, it.plannedSets.size) }
        val (item, plannedSet, setIndex) = current ?: fallback ?: Triple(null, null, 0)
        val movement = item?.let { exercise(it.exerciseId) }
        val title = movement?.name ?: workout.name
        val setText = when {
            item == null -> "Add an exercise to continue"
            item.plannedSets.isEmpty() -> "Add a set to continue"
            plannedSet != null -> "Set ${setIndex + 1} of ${item.plannedSets.size}"
            else -> "All ${workout.setCount()} sets done"
        }
        val restSeconds = ((restEndsAt - SystemClock.elapsedRealtime() + 999L) / 1_000L).toInt().coerceAtLeast(0)
        val holdSeconds = if (holdStartedAt > 0L && activeHoldWorkoutExerciseId != null) {
            ((SystemClock.elapsedRealtime() - holdStartedAt) / 1_000L).toInt().coerceAtLeast(0)
        } else 0
        val detail = when {
            restSeconds > 0 -> "$setText · Rest ${formatSeconds(restSeconds)}"
            activeHoldWorkoutExerciseId != null -> "$setText · Hold ${formatSeconds(holdSeconds)}"
            else -> setText
        }
        val state = "$title|$detail"
        if (!force && state == lastWorkoutNotification) return
        lastWorkoutNotification = state

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, WORKOUT_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_workout_notification)
            .setContentTitle(title)
            .setContentText(detail)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setWhen(workout.startedAt)
            .setUsesChronometer(restSeconds == 0)
        if (restSeconds > 0) {
            notification
                .addAction(0, "−15s", restAction(ACTION_SUBTRACT_REST, 1))
                .addAction(0, "+15s", restAction(ACTION_ADD_REST, 2))
                .addAction(0, "Skip", restAction(ACTION_SKIP_REST, 3))
        }
        manager.notify(WORKOUT_NOTIFICATION_ID, notification.build())
    }

    private fun restAction(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        this,
        requestCode,
        Intent(action).setPackage(packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun recordFor(exercise: Exercise): String? {
        val sets = repository.data.history.flatMap { workout ->
            workout.exercises.filter { it.exerciseId == exercise.id }.flatMap { it.sets }
        }
        if (sets.isEmpty()) return null
        return when {
            exercise.type.tracksDuration && exercise.weighted ->
                "Best: ${sets.maxOf { it.holdSeconds }}s hold · ${formatWeight(sets.maxOf { it.weightKg })} kg"
            exercise.type.tracksDuration -> "Best: ${sets.maxOf { it.holdSeconds }}s hold"
            exercise.weighted -> "Best: ${formatWeight(sets.maxOf { it.weightKg })} kg · ${sets.maxOf { it.reps }} reps"
            else -> "Best: ${sets.maxOf { it.reps }} reps"
        }
    }

    private fun exerciseTrackingLabel(exercise: Exercise): String =
        "${exercise.type.label} · ${if (exercise.weighted) "Weighted" else "Bodyweight"}"

    private fun exerciseSearchTerms(exercise: Exercise): String =
        listOf(exercise.type.label, "Weighted".takeIf { exercise.weighted }).filterNotNull().joinToString(" ")

    private fun page(
        title: String,
        subtitle: String? = null,
        headerEnd: View? = null,
        build: (LinearLayout) -> Unit,
    ): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
        }
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleText(title).apply { textSize = 26f; setPadding(0, dp(5), 0, 0) })
                subtitle?.let { addView(metaText(it).apply { setPadding(0, 0, 0, dp(10)) }) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            headerEnd?.let {
                addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(12)
                    bottomMargin = dp(5)
                })
            }
        })
        build(body)
        scroll.addView(body)
        return scroll
    }

    private fun card(horizontalPadding: Int = 10, build: LinearLayout.() -> Unit): View = MaterialCardView(this).apply {
        radius = dp(3).toFloat()
        cardElevation = 0f
        strokeWidth = 0
        setCardBackgroundColor(color(R.color.charcoal_light))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(horizontalPadding), dp(10), dp(horizontalPadding), dp(10))
            build()
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun insetCardLayoutParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        marginStart = dp(10)
        marginEnd = dp(10)
    }

    private fun exerciseHeading(
        exercise: Exercise,
        large: Boolean,
        showMuscle: Boolean = true,
        actions: View? = null,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val width = if (large) dp(84) else dp(68)
        val height = if (large) dp(60) else dp(48)
        addView(exerciseThumbnail(exercise, width, height), LinearLayout.LayoutParams(width, height).apply { marginEnd = dp(12) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText(exercise.name))
            if (showMuscle) addView(metaText(exercise.primaryMuscle))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions?.let(::addView)
    }

    private fun exerciseActions(
        exercise: Exercise,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onRemove: () -> Unit,
        onStateUpdaterReady: ((Boolean, Boolean) -> Unit) -> Unit = {},
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val moveUp = smallButton("↑", onMoveUp).apply {
            isEnabled = canMoveUp
            contentDescription = "Move ${exercise.name} up"
            textSize = 20f
            setPadding(dp(2), 0, dp(2), 0)
        }
        val moveDown = smallButton("↓", onMoveDown).apply {
            isEnabled = canMoveDown
            contentDescription = "Move ${exercise.name} down"
            textSize = 20f
            setPadding(dp(2), 0, dp(2), 0)
        }
        addView(moveUp, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(moveDown, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(4) })
        addView(smallButton("×", onRemove).apply {
            contentDescription = "Remove ${exercise.name}"
            textSize = 20f
            setPadding(dp(2), 0, dp(2), 0)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(4) })
        onStateUpdaterReady { moveUpEnabled, moveDownEnabled ->
            moveUp.isEnabled = moveUpEnabled
            moveDown.isEnabled = moveDownEnabled
        }
    }

    private fun exerciseThumbnail(exercise: Exercise, width: Int, height: Int): View {
        val resource = exercise.imageResource ?: exercise.imageName?.let { name ->
            drawableIds.getOrPut(name) { resources.getIdentifier(name, "drawable", packageName) }
        }?.takeIf { it != 0 }
        return resource?.let {
            ImageView(this).apply {
                setImageResource(it)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "${exercise.name} illustration"
            }
        } ?: TextView(this).apply {
            text = exercise.name.take(1).uppercase(Locale.getDefault())
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(color(R.color.cream))
            setBackgroundColor(color(R.color.coral_dark))
            contentDescription = "${exercise.name} placeholder image"
        }
    }

    private fun stepperRow(label: String, value: Int, unit: String, onChange: (Int) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(9), 0, dp(9))
        addView(TextView(this@MainActivity).apply {
            text = "$label\n$value $unit"
            textSize = 15f
            setTextColor(color(R.color.cream))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(smallButton("−") { onChange(-1) }, LinearLayout.LayoutParams(dp(52), dp(46)))
        addView(smallButton("+") { onChange(1) }, LinearLayout.LayoutParams(dp(52), dp(46)).apply { marginStart = dp(6) })
    }

    private fun restInput(value: Int, onChange: (Int) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(3), 0, dp(3))
        addView(TextView(this@MainActivity).apply {
            text = "Set Rest"
            textSize = 16f
            setTextColor(color(R.color.cream))
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(numberField("Seconds").apply {
            setText(formatInteger(value.coerceIn(0, 3_600)))
            contentDescription = "Rest duration in seconds"
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            doAfterTextChanged { text -> text.toString().toIntOrNull()?.let { onChange(it.coerceIn(0, 3_600)) } }
        }, LinearLayout.LayoutParams(dp(82), dp(42)))
        addView(metaText("seconds").apply { setPadding(dp(8), 0, 0, 0) })
    }

    private fun restOverrideInput(value: Int?, onChange: (Int?) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(3), 0, dp(3))
        addView(TextView(this@MainActivity).apply {
            text = "Rest override"
            textSize = 16f
            setTextColor(color(R.color.cream))
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(numberField("Seconds").apply {
            setText(value?.let(::formatInteger).orEmpty())
            contentDescription = "Rest override in seconds"
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            doAfterTextChanged { text ->
                onChange(text.toString().toIntOrNull()?.coerceIn(0, 3_600))
            }
        }, LinearLayout.LayoutParams(dp(82), dp(42)))
    }

    private fun choiceGroup(values: List<String>, selected: Int): RadioGroup = RadioGroup(this).apply {
        orientation = if (values.size <= 3) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
        values.forEachIndexed { index, value ->
            addView(RadioButton(this@MainActivity).apply {
                id = index + 1
                text = value
                textSize = 15f
                setTextColor(color(R.color.cream))
                buttonTintList = toggleTint()
                buttonDrawable = sizedButtonDrawable(buttonDrawable, 30)
                isChecked = index == selected
                minHeight = dp(48)
                setPadding(dp(4), dp(4), dp(10), dp(4))
            }, if (orientation == RadioGroup.HORIZONTAL) RadioGroup.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) else RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun selectedChoice(group: RadioGroup): Int = (group.checkedRadioButtonId - 1).coerceAtLeast(0)

    private fun horizontalActions(vararg views: View): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(4), 0, dp(4))
        views.forEach { view -> addView(view, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) }) }
    }

    private fun actionButton(label: String, secondary: Boolean = false, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
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
        setOnClickListener { action() }
    }

    private fun smallButton(label: String, action: () -> Unit): MaterialButton = actionButton(label, secondary = true, action = action).apply {
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(5), 0, dp(5), 0)
    }

    private fun titleText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 18f
        setTextColor(color(R.color.cream))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun sectionTitle(value: String): TextView = titleText(value).apply { setPadding(0, dp(14), 0, dp(6)) }
    private fun divider(description: String = "Workout actions separator"): View = View(this).apply {
        setBackgroundColor(color(R.color.sage))
        alpha = 0.45f
        contentDescription = description
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(14)
            bottomMargin = dp(14)
        }
    }
    private fun fieldLabel(value: String): TextView = metaText(value).apply { setPadding(0, dp(8), 0, 0) }

    private fun metaText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(color(R.color.sage))
        setPadding(0, dp(3), 0, dp(3))
    }

    private fun accentText(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 15f
        setTextColor(color(R.color.coral))
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun emptyText(value: String): TextView = metaText(value).apply {
        textSize = 16f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(36), dp(18), dp(36))
    }

    private fun textField(hint: String): EditText = EditText(this).apply {
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

    private fun muscleGroupField(hint: String, value: String, multiple: Boolean = false): AutoCompleteTextView {
        val field = if (multiple) MultiAutoCompleteTextView(this) else AutoCompleteTextView(this)
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
            if (this is MultiAutoCompleteTextView) setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
            setText(value, false)
            contentDescription = hint
            setOnClickListener { showDropDown() }
        }
    }

    private fun searchAdapter(sourceValues: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, sourceValues.toMutableList()) {
            private val wordFilter = object : Filter() {
                override fun performFiltering(constraint: CharSequence?): FilterResults {
                    val matches = sourceValues.filter { matchesSearchQuery(constraint?.toString().orEmpty(), it) }
                    return FilterResults().apply {
                        values = matches
                        count = matches.size
                    }
                }

                override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
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

    private fun numberField(hint: String): EditText = textField(hint).apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setSelectAllOnFocus(true)
        setOnClickListener { selectAll() }
    }

    private fun decimalField(hint: String): EditText = textField(hint).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        setSelectAllOnFocus(true)
        setOnClickListener { selectAll() }
    }

    private fun fieldBackground(focused: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(3).toFloat()
        setColor(color(R.color.charcoal_input))
        if (focused) setStroke(dp(1), color(R.color.coral))
    }

    private fun toggleTint() = ColorStateList(
        arrayOf(
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(),
        ),
        intArrayOf(color(R.color.coral), color(R.color.sage)),
    )

    private fun sizedButtonDrawable(drawable: android.graphics.drawable.Drawable?, sizeDp: Int) = drawable?.let {
        LayerDrawable(arrayOf(it)).apply {
            setLayerSize(0, dp(sizeDp), dp(sizeDp))
            setLayerGravity(0, Gravity.CENTER)
        }
    }

    private fun largeCheckBox(): CheckBox = CheckBox(this).apply {
        buttonDrawable = sizedButtonDrawable(buttonDrawable, 40)
        buttonTintList = toggleTint()
        gravity = Gravity.CENTER
        minWidth = dp(56)
        minHeight = dp(56)
        setPadding(0, 0, 0, 0)
    }
    private fun exercise(id: String): Exercise? = repository.data.exercises.firstOrNull { it.id == id }

    private fun plannedSetEditor(
        exercise: Exercise,
        plannedSet: PlannedSet,
        setIndex: Int,
        endAction: View? = null,
        endActionWidth: Int = 48,
        endActionHeight: Int = 42,
        completed: Boolean = false,
        onRemove: (() -> Unit)? = null,
        onRemoveAndBelow: (() -> Unit)? = null,
        showActiveHold: Boolean = false,
        activeHoldRunning: Boolean = false,
        readOnly: Boolean = false,
        onAmountReady: (EditText) -> Unit = {},
        onActiveAmountReady: (EditText) -> Unit = {},
        onIndexUpdaterReady: ((Int) -> Unit) -> Unit = {},
        onChanged: () -> Unit = {},
    ): View {
        var currentSetIndex = setIndex
        return LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (completed) setBackgroundColor(color(R.color.done_green))
        val setNumber = if (readOnly) readOnlySetField(
            setKindMarker(setIndex, plannedSet.type), "Set ${setIndex + 1} type: ${plannedSet.type.label}",
        ).apply { gravity = Gravity.CENTER } else smallButton(setKindMarker(setIndex, plannedSet.type)) {}.apply {
            fun updateType(type: SetType) {
                plannedSet.type = type
                text = setKindMarker(currentSetIndex, type)
                contentDescription = "Set ${currentSetIndex + 1} type: ${type.label}"
                onChanged()
            }
            contentDescription = "Set ${currentSetIndex + 1} type: ${plannedSet.type.label}"
            setOnClickListener {
                updateType(nextSetType(plannedSet.type))
            }
        }
        val weight = if (exercise.weighted) {
            if (readOnly) readOnlySetField(formatWeight(plannedSet.weightKg), "Set ${setIndex + 1} weight")
            else decimalField("Weight in kg").apply {
                setText(formatWeight(plannedSet.weightKg))
                contentDescription = "Set ${currentSetIndex + 1} weight"
                setPadding(dp(6), 0, dp(6), 0)
                doAfterTextChanged { value ->
                    value.toString().toDoubleOrNull()?.takeIf { it >= 0 }?.let {
                        plannedSet.weightKg = it
                        onChanged()
                    }
                }
            }
        } else null
        val amountLabel = when {
            !exercise.type.tracksDuration -> "Repetitions"
            showActiveHold -> "Target time in seconds"
            else -> "Hold duration in seconds"
        }
        val amountValue = if (!exercise.type.tracksDuration) plannedSet.reps else plannedSet.holdSeconds
        val amount = if (readOnly) readOnlySetField(formatInteger(amountValue), "Set ${setIndex + 1} $amountLabel")
        else numberField(amountLabel).apply {
            setText(formatInteger(amountValue))
            contentDescription = "Set ${currentSetIndex + 1} $amountLabel"
            setPadding(dp(6), 0, dp(6), 0)
            doAfterTextChanged { value ->
                if (updatingHoldTimer) return@doAfterTextChanged
                value.toString().toIntOrNull()?.takeIf { it > 0 }?.let {
                    if (!exercise.type.tracksDuration) plannedSet.reps = it else plannedSet.holdSeconds = it
                    onChanged()
                }
            }
        }
        if (amount is EditText) onAmountReady(amount)
        val activeAmount = if (exercise.type.tracksDuration && showActiveHold) {
            if (readOnly) readOnlySetField(formatInteger(plannedSet.actualHoldSeconds ?: 0), "Set ${setIndex + 1} Active time in seconds")
            else numberField("Active time in seconds").apply {
                setText(formatInteger(plannedSet.actualHoldSeconds ?: 0))
                contentDescription = "Set ${currentSetIndex + 1} Active time in seconds"
                setPadding(dp(6), 0, dp(6), 0)
                isEnabled = !activeHoldRunning
                doAfterTextChanged { value ->
                    if (updatingHoldTimer) return@doAfterTextChanged
                    value.toString().toIntOrNull()?.takeIf { it >= 0 }?.let {
                        plannedSet.actualHoldSeconds = it
                        onChanged()
                    }
                }
            }.also(onActiveAmountReady)
        } else null
        val fields = GridLayout(this@MainActivity).apply {
            val activeGrid = activeAmount != null
            val columns = setGridColumnWidths(exercise.weighted, activeGrid, endAction?.let { endActionWidth } ?: 0)
            columnCount = columns.size
            rowCount = 1
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
            layoutParams = LinearLayout.LayoutParams(setTableWidth(), dp(64))
            setPadding(
                dp(SET_ROW_PADDING_DP),
                dp(SET_ROW_PADDING_DP),
                dp(SET_ROW_PADDING_DP),
                dp(SET_ROW_PADDING_DP),
            )
            addView(setGridControl(setNumber), setGridCell(columns[0], heightDp = 48))
            when {
                activeGrid && exercise.weighted -> {
                    addView(setGridControl(weight!!), setGridCell(columns[1], heightDp = 48))
                    addView(setGridControl(amount), setGridCell(columns[2], heightDp = 48))
                    addView(setGridControl(activeAmount!!), setGridCell(columns[3], heightDp = 48))
                    endAction?.let { addView(it, setGridCell(columns[4], heightDp = endActionHeight)) }
                }
                activeGrid -> {
                    addView(setGridControl(amount), setGridCell(columns[1], heightDp = 48))
                    addView(setGridControl(activeAmount!!), setGridCell(columns[2], heightDp = 48))
                    endAction?.let { addView(it, setGridCell(columns[3], heightDp = endActionHeight)) }
                }
                exercise.weighted -> {
                    addView(setGridControl(weight!!), setGridCell(columns[1], heightDp = 48))
                    addView(metaText("kg ×").apply { gravity = Gravity.CENTER }, setGridCell(columns[2], heightDp = 48))
                    addView(setGridControl(amount), setGridCell(columns[3], heightDp = 48))
                    addView(metaText(if (!exercise.type.tracksDuration) "reps" else "sec").apply { gravity = Gravity.CENTER }, setGridCell(columns[4], heightDp = 48))
                    endAction?.let { addView(it, setGridCell(columns[5], heightDp = endActionHeight)) }
                }
                else -> {
                    addView(setGridControl(amount), setGridCell(columns[1], heightDp = 48))
                    addView(metaText(if (!exercise.type.tracksDuration) "reps" else "sec").apply { gravity = Gravity.CENTER }, setGridCell(columns[2], heightDp = 48))
                    endAction?.let { addView(it, setGridCell(columns[3], heightDp = endActionHeight)) }
                }
            }
        }
        var swipeRow: HorizontalScrollView? = null
        var deleteButton: MaterialButton? = null
        var deleteBelowButton: MaterialButton? = null
        if (onRemove != null || onRemoveAndBelow != null) {
            val revealWidth = (if (onRemove != null) dp(92) else 0) +
                (if (onRemoveAndBelow != null) dp(128) else 0)
            val row = SwipeRevealRow(revealWidth).apply {
                contentDescription = "Set ${currentSetIndex + 1} row"
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(fields)
                    onRemove?.let { remove ->
                        val button = swipeDeleteButton("Delete", "Delete set ${currentSetIndex + 1}", remove)
                        deleteButton = button
                        addView(button, LinearLayout.LayoutParams(dp(92), dp(64)))
                    }
                    onRemoveAndBelow?.let { remove ->
                        val button = swipeDeleteButton("Delete below", "Delete set ${currentSetIndex + 1} and all below", remove)
                        deleteBelowButton = button
                        addView(button, LinearLayout.LayoutParams(dp(124), dp(64)).apply {
                            marginStart = dp(4)
                        })
                    }
                })
            }
            swipeRow = row
            addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        } else {
            addView(fields)
        }
        val sideChoice = if (exercise.asymmetric && readOnly) {
            readOnlySetField(plannedSet.side.label, "Set ${currentSetIndex + 1} side")
        } else if (exercise.asymmetric) choiceGroup(Side.entries.map { it.label }, plannedSet.side.ordinal).apply {
            contentDescription = "Set ${currentSetIndex + 1} side"
            setOnCheckedChangeListener { _, checkedId ->
                plannedSet.side = Side.entries[(checkedId - 1).coerceAtLeast(0)]
                onChanged()
            }
        } else null
        sideChoice?.let(::addView)
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
            sideChoice?.contentDescription = "Set ${newIndex + 1} side"
        }
        }
    }

    private fun readOnlySetField(value: String, description: String): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(color(R.color.cream))
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(6), 0, dp(6), 0)
        contentDescription = description
    }

    private fun swipeDeleteButton(label: String, description: String, action: () -> Unit): MaterialButton =
        smallButton(label, action).apply {
            contentDescription = description
            setBackgroundColor(color(R.color.coral_dark))
            strokeWidth = 0
        }

    private fun plannedSetHeader(
        exercise: Exercise,
        endLabel: String?,
        endWidth: Int = 48,
        showActiveHold: Boolean = false,
        showHoldAction: Boolean = true,
    ): View = GridLayout(this).apply {
        val activeGrid = exercise.type.tracksDuration && showActiveHold
        val columns = setGridColumnWidths(exercise.weighted, activeGrid, endLabel?.let { endWidth } ?: 0)
        columnCount = columns.size
        rowCount = 1
        alignmentMode = GridLayout.ALIGN_BOUNDS
        useDefaultMargins = false
        setPadding(
            dp(SET_ROW_PADDING_DP),
            dp(SET_ROW_PADDING_DP),
            dp(SET_ROW_PADDING_DP),
            dp(SET_ROW_PADDING_DP),
        )
        layoutParams = LinearLayout.LayoutParams(setTableWidth(), dp(38)).apply {
            topMargin = dp(8)
        }
        addView(setGridHeader("Set"), setGridCell(columns[0]))
        when {
            activeGrid && exercise.weighted -> {
                addView(setGridHeader("Weight"), setGridCell(columns[1]))
                addView(setGridHeader("Target"), setGridCell(columns[2]))
                addView(setGridHeader("Active"), setGridCell(columns[3]))
                endLabel?.let { addView(if (showHoldAction) timedSetActionHeader(endWidth) else doneSetActionHeader(), setGridCell(columns[4])) }
            }
            activeGrid -> {
                addView(setGridHeader("Target"), setGridCell(columns[1]))
                addView(setGridHeader("Active"), setGridCell(columns[2]))
                endLabel?.let { addView(if (showHoldAction) timedSetActionHeader(endWidth) else doneSetActionHeader(), setGridCell(columns[3])) }
            }
            exercise.weighted -> {
                addView(setGridHeader("Weight"), setGridCell(columns[1]))
                addView(View(this@MainActivity), setGridCell(columns[2]))
                addView(setGridHeader(if (!exercise.type.tracksDuration) "Reps" else "Hold"), setGridCell(columns[3]))
                addView(View(this@MainActivity), setGridCell(columns[4]))
                endLabel?.let { addView(doneSetActionHeader(), setGridCell(columns[5])) }
            }
            else -> {
                addView(setGridHeader(if (!exercise.type.tracksDuration) "Reps" else "Hold"), setGridCell(columns[1]))
                addView(View(this@MainActivity), setGridCell(columns[2]))
                endLabel?.let { addView(doneSetActionHeader(), setGridCell(columns[3])) }
            }
        }
    }

    private fun doneSetActionHeader(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        addView(setGridHeader("Done").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(SET_DONE_ACTION_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(SET_RECORD_ACTION_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun timedSetActionHeader(endWidth: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END
        addView(setGridHeader("Start").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(SET_HOLD_ACTION_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(setGridHeader("Done").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(SET_DONE_ACTION_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp((endWidth - SET_HOLD_ACTION_WIDTH_DP - SET_DONE_ACTION_WIDTH_DP).coerceAtLeast(0)), ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun setGridHeader(value: String) = metaText(value).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(6), dp(3), 0, dp(3))
    }

    private fun setGridColumnWidths(weighted: Boolean, activeHold: Boolean, endWidth: Int): IntArray {
        val totalWidth = (resources.configuration.screenWidthDp - 28 - SET_ROW_PADDING_DP * 2).coerceAtLeast(280)
        return when {
            weighted && activeHold -> {
                val fieldWidth = ((totalWidth - 48 - endWidth) / 3).coerceAtLeast(40)
                intArrayOf(48, fieldWidth, fieldWidth, totalWidth - 48 - endWidth - fieldWidth * 2, endWidth)
            }
            activeHold -> {
                val fieldWidth = ((totalWidth - 48 - endWidth) / 2).coerceAtLeast(64)
                intArrayOf(48, fieldWidth, totalWidth - 48 - endWidth - fieldWidth, endWidth)
            }
            weighted -> {
                val fieldWidth = ((totalWidth - 48 - 44 - 40 - endWidth) / 2).coerceAtLeast(48)
                intArrayOf(48, fieldWidth, 44, totalWidth - 48 - 44 - 40 - endWidth - fieldWidth, 40, endWidth)
            }
            else -> intArrayOf(48, totalWidth - 48 - 40 - endWidth, 40, endWidth)
        }
    }

    private fun setTableWidth(): Int = resources.displayMetrics.widthPixels - dp(14) * 2

    private fun setGridCell(widthDp: Int, heightDp: Int? = null) = GridLayout.LayoutParams().apply {
        width = dp(widthDp)
        height = heightDp?.let(::dp) ?: ViewGroup.LayoutParams.WRAP_CONTENT
        setGravity(Gravity.CENTER_VERTICAL)
        setMargins(0, 0, 0, 0)
    }

    private fun setGridControl(control: View): View = FrameLayout(this).apply {
        addView(control, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    private fun loggedSetFor(item: WorkoutExercise, plannedSet: PlannedSet): LoggedSet? =
        item.sets.firstOrNull { it.plannedSetId == plannedSet.id }

    private fun loggedSetFrom(plannedSet: PlannedSet, exercise: Exercise) = LoggedSet(
        type = plannedSet.type,
        weightKg = plannedSet.weightKg.takeIf { exercise.weighted } ?: 0.0,
        reps = if (!exercise.type.tracksDuration) plannedSet.reps else 0,
        holdSeconds = if (exercise.type.tracksDuration) plannedSet.actualHoldSeconds ?: 0 else 0,
        side = plannedSet.side,
        plannedSetId = plannedSet.id,
    )

    private fun syncLoggedSet(loggedSet: LoggedSet, plannedSet: PlannedSet, exercise: Exercise) {
        loggedSet.type = plannedSet.type
        loggedSet.weightKg = plannedSet.weightKg.takeIf { exercise.weighted } ?: 0.0
        loggedSet.reps = if (!exercise.type.tracksDuration) plannedSet.reps else 0
        loggedSet.holdSeconds = if (exercise.type.tracksDuration) plannedSet.actualHoldSeconds ?: 0 else 0
        loggedSet.side = plannedSet.side
    }

    private fun setKindMarker(setIndex: Int, type: SetType): String = when (type) {
        SetType.WARMUP -> "W"
        SetType.NORMAL -> "${setIndex + 1}"
        SetType.FAILURE -> "F"
    }

    private fun nextSetType(type: SetType): SetType = when (type) {
        SetType.NORMAL -> SetType.WARMUP
        SetType.WARMUP -> SetType.FAILURE
        SetType.FAILURE -> SetType.NORMAL
    }

    private fun formatWeight(value: Double): String = String.format(Locale.getDefault(), if (value % 1.0 == 0.0) "%.0f" else "%.1f", value)
    private fun formatInteger(value: Int): String = String.format(Locale.getDefault(), "%d", value)
    private fun formatSeconds(total: Int): String = String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
    private fun formatDuration(milliseconds: Long): String = formatWorkoutTime((milliseconds / 1_000).toInt())
    private fun color(resource: Int): Int = ContextCompat.getColor(this, resource)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
