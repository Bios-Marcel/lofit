package lofit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
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
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private companion object {
        const val WORKOUT_NOTIFICATION_CHANNEL = "active-workout"
        const val WORKOUT_NOTIFICATION_ID = 1
    }

    private enum class Screen { WORKOUT, ROUTINES, EXERCISES, HISTORY }
    private enum class PickerTarget { WORKOUT, ROUTINE }
    private enum class ConfirmAction { FINISH_WORKOUT, DISCARD_WORKOUT, DELETE_EXERCISE, DELETE_ROUTINE, RESET_DATA }

    private sealed class Route {
        data object Root : Route()
        data class ExercisePicker(val target: PickerTarget) : Route()
        data class RoutineEditor(val originalId: String?) : Route()
        data class ExerciseEditor(val exerciseId: String?) : Route()
        data class HoldTimer(val workoutExerciseId: String, val plannedSetId: String) : Route()
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
    private var routineDraft: Routine? = null
    private var timerText: TextView? = null
    private var holdTimerText: TextView? = null
    private var holdStartedAt = 0L
    private var holdCuePlayed = false
    private var restEndsAt = 0L
    private var lastWorkoutNotification = ""
    private val handler = Handler(Looper.getMainLooper())
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) updateWorkoutNotification(force = true)
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
        buildShell()
        render()
        if (repository.data.activeWorkout != null) ensureWorkoutNotification()
        handler.post(tick)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
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
            Screen.HISTORY to getString(R.string.nav_history),
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

    private fun render() {
        if (repository.data.activeWorkout != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        content.removeAllViews()
        timerText = null
        holdTimerText = null
        val view = when (val activeRoute = route) {
            Route.Root -> rootScreen()
            is Route.ExercisePicker -> exercisePickerScreen(activeRoute.target)
            is Route.RoutineEditor -> routineEditorScreen(activeRoute.originalId)
            is Route.ExerciseEditor -> exerciseEditorScreen(activeRoute.exerciseId)
            is Route.HoldTimer -> holdTimerScreen(activeRoute.workoutExerciseId, activeRoute.plannedSetId)
            is Route.Confirm -> confirmationScreen(activeRoute)
        }
        content.addView(view)
        updateWorkoutNotification()
    }

    private fun rootScreen(): View = when (currentScreen) {
        Screen.WORKOUT -> repository.data.activeWorkout?.let(::activeWorkoutScreen) ?: workoutHome()
        Screen.ROUTINES -> routinesScreen()
        Screen.EXERCISES -> exercisesScreen()
        Screen.HISTORY -> historyScreen()
    }

    private fun workoutHome(): View = page("Ready to move?", "Local, focused, yours.") { body ->
        body.addView(actionButton(getString(R.string.start_workout)) {
            repository.data.activeWorkout = WorkoutSession(name = "Open workout")
            repository.save()
            ensureWorkoutNotification()
            render()
        })
        body.addView(sectionTitle("Saved routines"))
        if (repository.data.routines.isEmpty()) {
            body.addView(emptyText("No routines yet. Build one in the Routines tab, or start empty and add exercises as you go."))
        }
        repository.data.routines.forEach { routine ->
            body.addView(card {
                addView(titleText(routine.name))
                addView(metaText("${routine.exercises.size} exercises · ${routine.defaultRestSeconds}s default rest"))
                addView(prescriptionSummary(routine))
                addView(actionButton("Start routine") { startRoutine(routine) })
            })
        }
    }

    private fun activeWorkoutScreen(workout: WorkoutSession): View = page(workout.name, "Workout in progress") { body ->
        timerText = TextView(this).apply {
            textSize = 18f
            setTextColor(color(R.color.cream))
            setPadding(0, 0, 0, dp(8))
        }
        body.addView(timerText)
        updateWorkoutTimer()
        body.addView(restInput(workout.defaultRestSeconds) { seconds ->
            workout.defaultRestSeconds = seconds
            repository.save()
        })

        if (workout.exercises.isEmpty()) {
            body.addView(emptyText("This workout is empty. Add any exercise when you’re ready."))
        }
        workout.exercises.forEachIndexed { index, item ->
            exercise(item.exerciseId)?.let { movement ->
                body.addView(workoutExerciseCard(workout, item, movement, index))
            }
        }
        body.addView(actionButton("Add exercise") {
            route = Route.ExercisePicker(PickerTarget.WORKOUT)
            render()
        })
        body.addView(divider())
        body.addView(actionButton("Finish workout") {
            route = Route.Confirm(ConfirmAction.FINISH_WORKOUT)
            render()
        })
        body.addView(actionButton("Discard workout", secondary = true) {
            route = Route.Confirm(ConfirmAction.DISCARD_WORKOUT)
            render()
        })
    }

    private fun workoutExerciseCard(
        workout: WorkoutSession,
        item: WorkoutExercise,
        exercise: Exercise,
        index: Int,
    ): View = card {
        addView(exerciseHeading(exercise, large = true, actions = exerciseActions(
            exercise = exercise,
            canMoveUp = index > 0,
            canMoveDown = index < workout.exercises.lastIndex,
            onMoveUp = { workout.moveExercise(index, index - 1); persistAndRender() },
            onMoveDown = { workout.moveExercise(index, index + 1); persistAndRender() },
            onRemove = { workout.exercises.remove(item); persistAndRender() },
        )))
        val rest = item.restSecondsOverride ?: exercise.defaultRestSeconds.takeIf { it > 0 } ?: workout.defaultRestSeconds
        addView(plannedSetHeader(exercise, "Done", endWidth = 52))
        item.plannedSets.forEachIndexed { setIndex, plannedSet ->
            val loggedSet = loggedSetFor(item, plannedSet, setIndex)
            val done = CheckBox(this@MainActivity).apply {
                isChecked = loggedSet != null
                buttonDrawable?.let { drawable ->
                    buttonDrawable = LayerDrawable(arrayOf(drawable)).apply {
                        setLayerSize(0, dp(32), dp(32))
                        setLayerGravity(0, Gravity.CENTER)
                    }
                }
                contentDescription = "Set ${setIndex + 1} done"
            }
            val setRow = plannedSetEditor(
                exercise = exercise,
                plannedSet = plannedSet,
                setIndex = setIndex,
                endAction = done,
                endActionWidth = 52,
                endActionHeight = 48,
                completed = loggedSet != null,
                onChanged = {
                    loggedSetFor(item, plannedSet, setIndex)?.let { syncLoggedSet(it, plannedSet, exercise) }
                    repository.save()
                },
            )
            done.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    item.sets.add(loggedSetFrom(plannedSet, exercise))
                    startRest(item, exercise, workout)
                } else {
                    loggedSetFor(item, plannedSet, setIndex)?.let(item.sets::remove)
                }
                setRow.setBackgroundColor(if (checked) color(R.color.done_green) else Color.TRANSPARENT)
                repository.save()
                updateWorkoutNotification(force = true)
            }
            addView(setRow)
        }
        addView(actionButton("Add set", secondary = true) {
            val next = item.plannedSets.lastOrNull()?.copy(id = UUID.randomUUID().toString())
                ?: PlannedSet(holdSeconds = exercise.minimumHoldSeconds)
            item.plannedSets.add(next)
            item.targetSets = item.plannedSets.size
            repository.save()
            render()
        })
        if (exercise.type == ExerciseType.WEIGHTED_HOLD) addView(actionButton("Start next hold") {
            val next = item.plannedSets.withIndex().firstOrNull { (setIndex, set) ->
                loggedSetFor(item, set, setIndex) == null
            }?.value ?: item.plannedSets.lastOrNull()?.copy(id = UUID.randomUUID().toString())
                ?.also { item.plannedSets.add(it) }
                ?: PlannedSet(holdSeconds = exercise.minimumHoldSeconds).also { item.plannedSets.add(it) }
            item.targetSets = item.plannedSets.size
            repository.save()
            holdStartedAt = SystemClock.elapsedRealtime()
            holdCuePlayed = false
            route = Route.HoldTimer(item.id, next.id)
            render()
        })
        addView(restInput(rest) { seconds ->
            item.restSecondsOverride = seconds
            repository.save()
        })
    }

    private fun routinesScreen(): View = page("Routines", "Prescribe each movement before you train.") { body ->
        body.addView(actionButton("Create routine") { openRoutineEditor(null) })
        if (repository.data.routines.isEmpty()) body.addView(emptyText("No routines saved."))
        repository.data.routines.forEach { routine ->
            body.addView(card {
                addView(titleText(routine.name))
                addView(metaText("Default rest ${routine.defaultRestSeconds}s"))
                addView(prescriptionSummary(routine))
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
        return page(if (originalId == null) "New routine" else "Edit routine", "Set the exact work you intend to do.", back = true) { body ->
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
            body.addView(actionButton("Search and add exercise") {
                route = Route.ExercisePicker(PickerTarget.ROUTINE)
                render()
            })
            if (draft.exercises.isEmpty()) body.addView(emptyText("Add exercises, then specify sets and repetitions or hold time."))
            draft.exercises.forEachIndexed { index, item ->
                val movement = exercise(item.exerciseId) ?: return@forEachIndexed
                body.addView(card {
                    addView(exerciseHeading(movement, large = true, actions = exerciseActions(
                        exercise = movement,
                        canMoveUp = index > 0,
                        canMoveDown = index < draft.exercises.lastIndex,
                        onMoveUp = { moveRoutineExercise(draft, index, index - 1) },
                        onMoveDown = { moveRoutineExercise(draft, index, index + 1) },
                        onRemove = { draft.exercises.remove(item); render() },
                    )))
                    addView(plannedSetHeader(movement, if (item.plannedSets.size > 1) "" else null))
                    item.plannedSets.forEachIndexed { setIndex, plannedSet ->
                        val remove = if (item.plannedSets.size > 1) smallButton("×") {
                            item.plannedSets.removeAt(setIndex)
                            item.targetSets = item.plannedSets.size
                            render()
                        } else null
                        addView(plannedSetEditor(movement, plannedSet, setIndex, remove) {
                            if (setIndex == 0) {
                                item.targetReps = plannedSet.reps
                                item.targetHoldSeconds = plannedSet.holdSeconds
                            }
                        })
                    }
                    addView(actionButton("Add set", secondary = true) {
                        val next = item.plannedSets.lastOrNull()?.copy(id = UUID.randomUUID().toString())
                            ?: PlannedSet(reps = item.targetReps, holdSeconds = item.targetHoldSeconds)
                        item.plannedSets.add(next)
                        item.targetSets = item.plannedSets.size
                        render()
                    })
                    val defaultRest = item.restSecondsOverride ?: movement.defaultRestSeconds
                    addView(restInput(defaultRest) { seconds ->
                        item.restSecondsOverride = seconds
                    })
                })
            }
            body.addView(actionButton("Save routine") {
                if (draft.name.isBlank()) {
                    Toast.makeText(this, "Give the routine a name", Toast.LENGTH_SHORT).show()
                } else {
                    saveRoutineDraft(originalId)
                }
            })
            body.addView(actionButton("Cancel", secondary = true) { cancelRoutineEditor() })
        }
    }

    private fun exercisePickerScreen(target: PickerTarget): View = page(
        "Add exercise",
        if (target == PickerTarget.ROUTINE) "Add a movement to this routine." else "Add a movement to this workout.",
        back = true,
    ) { body ->
        val search = textField("Search name or muscle").apply {
            inputType = InputType.TYPE_CLASS_TEXT
            contentDescription = "Search exercises"
        }
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun updateResults(query: String) {
            results.removeAllViews()
            val normalized = query.trim().lowercase(Locale.getDefault())
            val matches = repository.data.exercises.filter { exercise ->
                normalized.isBlank() || listOf(exercise.name, exercise.primaryMuscle, exercise.secondaryMuscles)
                    .any { it.lowercase(Locale.getDefault()).contains(normalized) }
            }
            if (matches.isEmpty()) results.addView(emptyText("No matching exercises."))
            matches.forEach { movement ->
                results.addView(card {
                    addView(exerciseHeading(movement, large = true))
                    addView(metaText("${movement.type.label}${if (movement.asymmetric) " · left / right" else ""}"))
                    addView(metaText("Primary: ${movement.primaryMuscle} · Secondary: ${movement.secondaryMuscles}"))
                    addView(actionButton("Add ${movement.name}") {
                        if (target == PickerTarget.WORKOUT) {
                            repository.data.activeWorkout?.exercises?.add(WorkoutExercise(exerciseId = movement.id))
                            repository.save()
                            route = Route.Root
                        } else {
                            routineDraft?.exercises?.add(RoutineExercise(
                                exerciseId = movement.id,
                                targetHoldSeconds = movement.minimumHoldSeconds,
                            ))
                            route = Route.RoutineEditor(repository.data.routines.firstOrNull { it.id == routineDraft?.id }?.id)
                        }
                        render()
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
        })
        val search = textField("Search name or muscle")
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun updateResults(query: String) {
            results.removeAllViews()
            val normalized = query.trim().lowercase(Locale.getDefault())
            repository.data.exercises.filter { movement ->
                normalized.isBlank() || listOf(movement.name, movement.primaryMuscle, movement.secondaryMuscles)
                    .any { it.lowercase(Locale.getDefault()).contains(normalized) }
            }.forEach { movement ->
                results.addView(card {
                    addView(exerciseHeading(movement, large = true))
                    addView(metaText("${movement.type.label}${if (movement.asymmetric) " · left / right" else ""}"))
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
        return page(if (existing == null) "New exercise" else "Edit exercise", "Define how this movement is tracked.", back = true) { body ->
            val name = textField("Exercise name").apply { setText(existing?.name.orEmpty()) }
            val primary = textField("Primary muscle").apply { setText(existing?.primaryMuscle.orEmpty()) }
            val secondary = textField("Secondary muscles").apply { setText(existing?.secondaryMuscles.orEmpty()) }
            val typeGroup = choiceGroup(ExerciseType.entries.map { it.label }, existing?.type?.ordinal ?: 0)
            val trackingGroup = choiceGroup(listOf("Track both sides together", "Track left and right"), if (existing?.asymmetric == true) 1 else 0)
            var restSeconds = existing?.defaultRestSeconds ?: 90
            val hold = numberField("Minimum hold cue seconds").apply { setText(formatInteger(existing?.minimumHoldSeconds ?: 30)) }
            body.addView(fieldLabel("Exercise name")); body.addView(name)
            body.addView(fieldLabel("Exercise type")); body.addView(typeGroup)
            body.addView(fieldLabel("Side tracking")); body.addView(trackingGroup)
            body.addView(fieldLabel("Primary muscle")); body.addView(primary)
            body.addView(fieldLabel("Secondary muscles")); body.addView(secondary)
            body.addView(restInput(restSeconds) { restSeconds = it })
            body.addView(fieldLabel("Hold cue")); body.addView(hold)
            body.addView(actionButton("Save exercise") {
                if (name.text.isBlank() || primary.text.isBlank()) {
                    Toast.makeText(this, "Name and primary muscle are required", Toast.LENGTH_SHORT).show()
                    return@actionButton
                }
                val movement = existing ?: Exercise(
                    id = UUID.randomUUID().toString(),
                    name = name.text.toString().trim(),
                    type = ExerciseType.WEIGHTED_REPS,
                    asymmetric = false,
                    primaryMuscle = primary.text.toString().trim(),
                    secondaryMuscles = secondary.text.toString().trim(),
                    custom = true,
                )
                movement.name = name.text.toString().trim()
                movement.type = ExerciseType.entries[selectedChoice(typeGroup)]
                movement.asymmetric = selectedChoice(trackingGroup) == 1
                movement.primaryMuscle = primary.text.toString().trim()
                movement.secondaryMuscles = secondary.text.toString().trim()
                movement.defaultRestSeconds = restSeconds
                movement.minimumHoldSeconds = hold.text.toString().toIntOrNull()?.coerceIn(1, 3_600) ?: 30
                if (existing == null) repository.data.exercises.add(movement)
                repository.save()
                route = Route.Root
                currentScreen = Screen.EXERCISES
                render()
            })
            body.addView(actionButton("Cancel", secondary = true) { navigateBack() })
        }
    }

    private fun holdTimerScreen(workoutExerciseId: String, plannedSetId: String): View {
        val workout = repository.data.activeWorkout ?: return workoutHome()
        val item = workout.exercises.firstOrNull { it.id == workoutExerciseId } ?: return activeWorkoutScreen(workout)
        val movement = exercise(item.exerciseId) ?: return activeWorkoutScreen(workout)
        val plannedSet = item.plannedSets.firstOrNull { it.id == plannedSetId } ?: return activeWorkoutScreen(workout)
        return page(movement.name, "The cue sounds at ${movement.minimumHoldSeconds} seconds.", back = true) { body ->
            body.addView(exerciseHeading(movement, large = true))
            holdTimerText = titleText("0:00").apply {
                textSize = 54f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
                contentDescription = "Hold timer"
            }
            body.addView(holdTimerText)
            val typeGroup = choiceGroup(SetType.entries.map { it.label }, plannedSet.type.ordinal)
            val sideGroup = choiceGroup(Side.entries.map { it.label }, plannedSet.side.ordinal)
            val weight = decimalField("Weight in kg (0 for bodyweight)")
            weight.setText(formatWeight(plannedSet.weightKg))
            body.addView(fieldLabel("Set type")); body.addView(typeGroup)
            if (movement.asymmetric) { body.addView(fieldLabel("Side")); body.addView(sideGroup) }
            body.addView(fieldLabel("Weight")); body.addView(weight)
            body.addView(actionButton("Stop and save hold") {
                val elapsed = max(1, ((SystemClock.elapsedRealtime() - holdStartedAt) / 1_000).toInt())
                plannedSet.type = SetType.entries[selectedChoice(typeGroup)]
                plannedSet.weightKg = max(0.0, weight.text.toString().toDoubleOrNull() ?: 0.0)
                plannedSet.holdSeconds = elapsed
                plannedSet.side = if (movement.asymmetric) Side.entries[selectedChoice(sideGroup)] else Side.BOTH
                loggedSetFor(item, plannedSet, item.plannedSets.indexOf(plannedSet))?.let(item.sets::remove)
                item.sets.add(LoggedSet(
                    type = plannedSet.type,
                    weightKg = plannedSet.weightKg,
                    holdSeconds = elapsed,
                    side = plannedSet.side,
                    plannedSetId = plannedSet.id,
                ))
                repository.save()
                startRest(item, movement, workout)
                route = Route.Root
                render()
            })
            body.addView(actionButton("Cancel hold", secondary = true) {
                route = Route.Root
                render()
            })
        }
    }

    private fun historyScreen(): View = page("History", "Your training stays on this device.") { body ->
        val history = repository.data.history.sortedByDescending { it.completedAt }
        if (history.isEmpty()) body.addView(emptyText("Complete a workout and it will appear here."))
        history.forEach { workout ->
            body.addView(card {
                addView(titleText(workout.name))
                addView(metaText(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(workout.completedAt ?: workout.startedAt))))
                addView(metaText("${workout.setCount()} sets · ${formatWeight(workout.totalVolume())} kg volume · ${formatDuration((workout.completedAt ?: workout.startedAt) - workout.startedAt)}"))
                workout.exercises.forEach { item ->
                    exercise(item.exerciseId)?.let { movement ->
                        addView(horizontalActions(exerciseThumbnail(movement, dp(64), dp(48)), metaText("${movement.name}: ${item.sets.size} sets")))
                    }
                }
            })
        }
        body.addView(sectionTitle("App data"))
        body.addView(metaText("Remove workout history, routines, custom exercises, and any active workout. Default exercises will be restored."))
        body.addView(actionButton("Reset all app data", secondary = true) {
            route = Route.Confirm(ConfirmAction.RESET_DATA)
            render()
        })
    }

    private fun confirmationScreen(confirm: Route.Confirm): View {
        val (title, message, buttonLabel) = when (confirm.action) {
            ConfirmAction.FINISH_WORKOUT -> Triple("Finish workout?", "The workout and all logged sets will be saved to history.", "Finish and save")
            ConfirmAction.DISCARD_WORKOUT -> Triple("Discard workout?", "All sets logged in this workout will be removed.", "Discard workout")
            ConfirmAction.DELETE_EXERCISE -> Triple("Delete exercise?", "The custom exercise will be removed from routines and the library.", "Delete exercise")
            ConfirmAction.DELETE_ROUTINE -> Triple("Delete routine?", "The routine will be removed. Workout history is unaffected.", "Delete routine")
            ConfirmAction.RESET_DATA -> Triple("Reset all app data?", "This permanently removes workout history, routines, custom exercises, and the active workout.", "Reset everything")
        }
        return page(title, message, back = true) { body ->
            body.addView(actionButton(buttonLabel) {
                when (confirm.action) {
                    ConfirmAction.FINISH_WORKOUT -> repository.data.activeWorkout?.let { workout ->
                        workout.completedAt = System.currentTimeMillis()
                        repository.data.history.add(workout)
                        repository.data.activeWorkout = null
                        restEndsAt = 0L
                    }
                    ConfirmAction.DISCARD_WORKOUT -> {
                        repository.data.activeWorkout = null
                        restEndsAt = 0L
                    }
                    ConfirmAction.DELETE_EXERCISE -> confirm.targetId?.let { id ->
                        repository.data.exercises.removeAll { it.id == id && it.custom }
                        repository.data.routines.forEach { routine -> routine.exercises.removeAll { it.exerciseId == id } }
                    }
                    ConfirmAction.DELETE_ROUTINE -> repository.data.routines.removeAll { it.id == confirm.targetId }
                    ConfirmAction.RESET_DATA -> {
                        repository.reset()
                        routineDraft = null
                        restEndsAt = 0L
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
                item.copy(plannedSets = item.plannedSets.map { it.copy() }.toMutableList())
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
        currentScreen = Screen.ROUTINES
        route = Route.Root
        render()
    }

    private fun moveRoutineExercise(routine: Routine, from: Int, to: Int) {
        if (from in routine.exercises.indices && to in routine.exercises.indices && from != to) {
            routine.exercises.add(to, routine.exercises.removeAt(from))
            render()
        }
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
                WorkoutExercise(
                    exerciseId = item.exerciseId,
                    restSecondsOverride = item.restSecondsOverride,
                    targetSets = item.plannedSets.size,
                    targetReps = item.plannedSets.firstOrNull()?.reps,
                    targetHoldSeconds = item.plannedSets.firstOrNull()?.holdSeconds,
                    plannedSets = item.plannedSets.map { it.copy() }.toMutableList(),
                )
            }.toMutableList(),
        )
        repository.save()
        ensureWorkoutNotification()
        currentScreen = Screen.WORKOUT
        route = Route.Root
        render()
    }

    private fun navigateBack() {
        route = when (route) {
            is Route.ExercisePicker -> if ((route as Route.ExercisePicker).target == PickerTarget.ROUTINE) {
                Route.RoutineEditor(repository.data.routines.firstOrNull { it.id == routineDraft?.id }?.id)
            } else Route.Root
            is Route.Confirm -> Route.Root
            is Route.RoutineEditor -> {
                routineDraft = null
                Route.Root
            }
            else -> Route.Root
        }
        render()
    }

    private fun startRest(item: WorkoutExercise, movement: Exercise, workout: WorkoutSession) {
        val seconds = item.restSecondsOverride ?: movement.defaultRestSeconds.takeIf { it > 0 } ?: workout.defaultRestSeconds
        restEndsAt = if (seconds > 0) SystemClock.elapsedRealtime() + seconds * 1_000L else 0L
        updateRestBar()
        updateWorkoutNotification(force = true)
    }

    private fun updateWorkoutTimer() {
        val workout = repository.data.activeWorkout ?: return
        val elapsed = max(0, ((System.currentTimeMillis() - workout.startedAt) / 1_000).toInt())
        timerText?.text = "Workout ${formatSeconds(elapsed)}"
        updateRestBar()
        updateWorkoutNotification()
    }

    private fun createRestBar(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(10), dp(10), dp(10))
        setBackgroundColor(color(R.color.coral_dark))
        visibility = View.GONE
        restTimeText = TextView(this@MainActivity).apply {
            textSize = 18f
            setTextColor(color(R.color.white))
            setTypeface(typeface, Typeface.BOLD)
        }
        addView(restTimeText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(smallButton("−15") {
            restEndsAt = max(SystemClock.elapsedRealtime(), restEndsAt - 15_000L)
            updateRestBar()
        }, LinearLayout.LayoutParams(dp(64), dp(48)))
        addView(smallButton("+15") {
            if (restEndsAt > 0L) restEndsAt += 15_000L
            updateRestBar()
        }, LinearLayout.LayoutParams(dp(64), dp(48)).apply { marginStart = dp(6) })
        addView(smallButton("Skip") {
            restEndsAt = 0L
            updateRestBar()
        }, LinearLayout.LayoutParams(dp(72), dp(48)).apply { marginStart = dp(6) })
    }

    private fun updateRestBar() {
        if (!::restBar.isInitialized) return
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

    private fun updateHoldTimer() {
        if (route !is Route.HoldTimer || holdStartedAt == 0L) return
        val elapsed = max(0, ((SystemClock.elapsedRealtime() - holdStartedAt) / 1_000).toInt())
        holdTimerText?.text = formatSeconds(elapsed)
        val itemId = (route as Route.HoldTimer).workoutExerciseId
        val item = repository.data.activeWorkout?.exercises?.firstOrNull { it.id == itemId }
        val movement = item?.let { exercise(it.exerciseId) }
        if (!holdCuePlayed && movement != null && elapsed >= movement.minimumHoldSeconds) {
            holdCuePlayed = true
            playCue(ToneGenerator.TONE_PROP_BEEP2)
        }
    }

    private fun playCue(tone: Int, durationMs: Int = 300, volume: Int = 80) {
        val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, volume)
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
                loggedSetFor(item, set, item.plannedSets.indexOf(set)) == null
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
        val detail = if (restSeconds > 0) "$setText · Rest ${formatSeconds(restSeconds)}" else setText
        val state = "$title|$detail"
        if (!force && state == lastWorkoutNotification) return
        lastWorkoutNotification = state

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(WORKOUT_NOTIFICATION_ID, NotificationCompat.Builder(this, WORKOUT_NOTIFICATION_CHANNEL)
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
            .build())
    }

    private fun prescriptionSummary(routine: Routine): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        routine.exercises.forEach { item ->
            exercise(item.exerciseId)?.let { movement ->
                val sets = item.plannedSets.mapIndexed { index, set ->
                    "${index + 1}. ${set.type.label} · ${describePlannedSet(movement, set)}"
                }.joinToString(" · ")
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(exerciseThumbnail(movement, dp(72), dp(52)))
                    addView(metaText("${movement.name}\n$sets"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(10) })
                }.apply { setPadding(0, dp(5), 0, dp(5)) })
            }
        }
    }

    private fun recordFor(exercise: Exercise): String? {
        val sets = repository.data.history.flatMap { workout ->
            workout.exercises.filter { it.exerciseId == exercise.id }.flatMap { it.sets }
        }
        if (sets.isEmpty()) return null
        return if (exercise.type == ExerciseType.WEIGHTED_HOLD) {
            "Best: ${sets.maxOf { it.holdSeconds }}s hold · ${formatWeight(sets.maxOf { it.weightKg })} kg"
        } else {
            "Best: ${formatWeight(sets.maxOf { it.weightKg })} kg · ${sets.maxOf { it.reps }} reps"
        }
    }

    private fun page(title: String, subtitle: String, back: Boolean = false, build: (LinearLayout) -> Unit): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(20))
        }
        if (back) {
            body.addView(actionButton("← Back", secondary = true) { navigateBack() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        }
        body.addView(titleText(title).apply { textSize = 26f; setPadding(0, dp(5), 0, 0) })
        body.addView(metaText(subtitle).apply { setPadding(0, 0, 0, dp(10)) })
        build(body)
        scroll.addView(body)
        return scroll
    }

    private fun card(build: LinearLayout.() -> Unit): View = MaterialCardView(this).apply {
        radius = dp(14).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = Color.rgb(59, 65, 64)
        setCardBackgroundColor(color(R.color.charcoal_light))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            build()
        })
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    }

    private fun exerciseHeading(exercise: Exercise, large: Boolean, actions: View? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val width = if (large) dp(84) else dp(68)
        val height = if (large) dp(60) else dp(48)
        addView(exerciseThumbnail(exercise, width, height), LinearLayout.LayoutParams(width, height).apply { marginEnd = dp(12) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText(exercise.name))
            addView(metaText(exercise.primaryMuscle))
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
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(smallButton("↑", onMoveUp).apply {
            isEnabled = canMoveUp
            contentDescription = "Move ${exercise.name} up"
            textSize = 20f
            setPadding(dp(2), 0, dp(2), 0)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        addView(smallButton("↓", onMoveDown).apply {
            isEnabled = canMoveDown
            contentDescription = "Move ${exercise.name} down"
            textSize = 20f
            setPadding(dp(2), 0, dp(2), 0)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(4) })
        addView(smallButton("×", onRemove).apply {
            contentDescription = "Remove ${exercise.name}"
            textSize = 20f
            setPadding(dp(2), 0, dp(2), 0)
        }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(4) })
    }

    private fun exerciseThumbnail(exercise: Exercise, width: Int, height: Int): View {
        return exercise.imageResource?.let { resource ->
            ImageView(this).apply {
                setImageResource(resource)
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

    private fun choiceGroup(values: List<String>, selected: Int): RadioGroup = RadioGroup(this).apply {
        orientation = if (values.size <= 3) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
        values.forEachIndexed { index, value ->
            addView(RadioButton(this@MainActivity).apply {
                id = index + 1
                text = value
                setTextColor(color(R.color.cream))
                isChecked = index == selected
                setPadding(dp(2), dp(4), dp(8), dp(4))
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
        cornerRadius = dp(12)
        minHeight = dp(42)
        if (secondary) {
            setBackgroundColor(color(R.color.charcoal_light))
            setTextColor(color(R.color.cream))
            strokeColor = ContextCompat.getColorStateList(this@MainActivity, R.color.sage)
            strokeWidth = dp(1)
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
    private fun divider(): View = View(this).apply {
        setBackgroundColor(color(R.color.sage))
        alpha = 0.45f
        contentDescription = "Workout actions separator"
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
        setTextColor(color(R.color.cream))
        setHintTextColor(color(R.color.sage))
    }

    private fun numberField(hint: String): EditText = textField(hint).apply { inputType = InputType.TYPE_CLASS_NUMBER }
    private fun decimalField(hint: String): EditText = textField(hint).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
    private fun exercise(id: String): Exercise? = repository.data.exercises.firstOrNull { it.id == id }

    private fun persistAndRender() {
        repository.save()
        render()
    }

    private fun plannedSetEditor(
        exercise: Exercise,
        plannedSet: PlannedSet,
        setIndex: Int,
        endAction: View? = null,
        endActionWidth: Int = 48,
        endActionHeight: Int = 42,
        completed: Boolean = false,
        onChanged: () -> Unit = {},
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (completed) setBackgroundColor(color(R.color.done_green))
        val setNumber = smallButton(setKindMarker(setIndex, plannedSet.type)) {}.apply {
            fun updateType(type: SetType) {
                plannedSet.type = type
                text = setKindMarker(setIndex, type)
                contentDescription = "Set ${setIndex + 1} type: ${type.label}"
                onChanged()
            }
            contentDescription = "Set ${setIndex + 1} type: ${plannedSet.type.label}"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Set ${setIndex + 1} type")
                    .setItems(SetType.entries.map { it.label }.toTypedArray()) { dialog, choice ->
                        updateType(SetType.entries[choice])
                        dialog.dismiss()
                    }
                    .create()
                    .apply {
                        setOnShowListener { window?.setWindowAnimations(0) }
                        show()
                    }
            }
        }
        val weight = decimalField("Weight in kg (0 for bodyweight)").apply {
            setText(formatWeight(plannedSet.weightKg))
            contentDescription = "Set ${setIndex + 1} weight"
            setPadding(dp(6), 0, dp(6), 0)
            doAfterTextChanged { value ->
                value.toString().toDoubleOrNull()?.takeIf { it >= 0 }?.let {
                    plannedSet.weightKg = it
                    onChanged()
                }
            }
        }
        val amountLabel = if (exercise.type == ExerciseType.WEIGHTED_REPS) "Repetitions" else "Hold duration in seconds"
        val amountValue = if (exercise.type == ExerciseType.WEIGHTED_REPS) plannedSet.reps else plannedSet.holdSeconds
        val amount = numberField(amountLabel).apply {
            setText(formatInteger(amountValue))
            contentDescription = "Set ${setIndex + 1} $amountLabel"
            setPadding(dp(6), 0, dp(6), 0)
            doAfterTextChanged { value ->
                value.toString().toIntOrNull()?.takeIf { it > 0 }?.let {
                    if (exercise.type == ExerciseType.WEIGHTED_REPS) plannedSet.reps = it else plannedSet.holdSeconds = it
                    onChanged()
                }
            }
        }
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
            addView(setNumber, LinearLayout.LayoutParams(dp(48), dp(42)))
            addView(weight, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(metaText("kg ×").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(44), dp(42)))
            addView(amount, LinearLayout.LayoutParams(0, dp(42), 1f))
            addView(metaText(if (exercise.type == ExerciseType.WEIGHTED_REPS) "reps" else "sec").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(40), dp(42)))
            endAction?.let { addView(it, LinearLayout.LayoutParams(dp(endActionWidth), dp(endActionHeight))) }
        })
        if (exercise.asymmetric) addView(choiceGroup(Side.entries.map { it.label }, plannedSet.side.ordinal).apply {
            contentDescription = "Set ${setIndex + 1} side"
            setOnCheckedChangeListener { _, checkedId ->
                plannedSet.side = Side.entries[(checkedId - 1).coerceAtLeast(0)]
                onChanged()
            }
        })
    }

    private fun plannedSetHeader(exercise: Exercise, endLabel: String?, endWidth: Int = 48): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(metaText("Set").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(metaText("Weight").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(44), 1))
        addView(metaText(if (exercise.type == ExerciseType.WEIGHTED_REPS) "Reps" else "Hold").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(40), 1))
        endLabel?.let {
            addView(metaText(it).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(endWidth), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun loggedSetFor(item: WorkoutExercise, plannedSet: PlannedSet, setIndex: Int): LoggedSet? =
        item.sets.firstOrNull { it.plannedSetId == plannedSet.id }
            ?: item.sets.getOrNull(setIndex)?.takeIf { it.plannedSetId == null }

    private fun loggedSetFrom(plannedSet: PlannedSet, exercise: Exercise) = LoggedSet(
        type = plannedSet.type,
        weightKg = plannedSet.weightKg,
        reps = if (exercise.type == ExerciseType.WEIGHTED_REPS) plannedSet.reps else 0,
        holdSeconds = if (exercise.type == ExerciseType.WEIGHTED_HOLD) plannedSet.holdSeconds else 0,
        side = plannedSet.side,
        plannedSetId = plannedSet.id,
    )

    private fun syncLoggedSet(loggedSet: LoggedSet, plannedSet: PlannedSet, exercise: Exercise) {
        loggedSet.type = plannedSet.type
        loggedSet.weightKg = plannedSet.weightKg
        loggedSet.reps = if (exercise.type == ExerciseType.WEIGHTED_REPS) plannedSet.reps else 0
        loggedSet.holdSeconds = if (exercise.type == ExerciseType.WEIGHTED_HOLD) plannedSet.holdSeconds else 0
        loggedSet.side = plannedSet.side
    }

    private fun setKindMarker(setIndex: Int, type: SetType): String = when (type) {
        SetType.WARMUP -> "W"
        SetType.NORMAL -> "${setIndex + 1}"
        SetType.FAILURE -> "F"
    }

    private fun describePlannedSet(exercise: Exercise, set: PlannedSet): String = if (exercise.type == ExerciseType.WEIGHTED_HOLD) {
        "${formatWeight(set.weightKg)} kg × ${set.holdSeconds}s"
    } else {
        "${formatWeight(set.weightKg)} kg × ${set.reps} reps"
    }

    private fun formatWeight(value: Double): String = String.format(Locale.getDefault(), if (value % 1.0 == 0.0) "%.0f" else "%.1f", value)
    private fun formatInteger(value: Int): String = String.format(Locale.getDefault(), "%d", value)
    private fun formatSeconds(total: Int): String = String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)
    private fun formatDuration(milliseconds: Long): String = formatSeconds((milliseconds / 1_000).toInt())
    private fun color(resource: Int): Int = ContextCompat.getColor(this, resource)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
