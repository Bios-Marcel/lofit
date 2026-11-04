package lofit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.health.connect.HealthPermissions
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private companion object {
        const val WORKOUT_NOTIFICATION_CHANNEL = "active-workout"
        const val WORKOUT_NOTIFICATION_ID = 1
        const val ACTION_SUBTRACT_REST = "lofit.action.SUBTRACT_REST"
        const val ACTION_ADD_REST = "lofit.action.ADD_REST"
        const val ACTION_SKIP_REST = "lofit.action.SKIP_REST"
        const val ACTION_OPEN_WORKOUT = "lofit.action.OPEN_WORKOUT"
        const val HISTORY_PAGE_SIZE = 10
    }

    private enum class Screen {
        HOME,
        WORKOUT,
        ROUTINES,
        EXERCISES,
        RECORDS,
        BODY_MEASUREMENTS,
        HISTORY,
        LIVE_MEASUREMENT,
        SETTINGS,
    }

    private enum class PickerTarget {
        WORKOUT,
        HISTORY,
        ROUTINE,
    }

    private enum class ConfirmAction {
        FINISH_WORKOUT,
        DELETE_EXERCISE,
        DELETE_ROUTINE,
    }

    private sealed class Route {
        data object Root : Route()

        data class ExercisePicker(
            val target: PickerTarget,
            val returnScrollY: Int? = null,
            val selectedIds: MutableSet<String> = linkedSetOf(),
            val historyWorkoutId: String? = null,
            val historyReturnScrollY: Int = 0,
            val anchorId: String? = null,
            val anchorName: String? = null,
            val anchorExerciseId: String? = null,
            val placement: ExercisePlacement? = null,
        ) : Route()

        data class RoutineEditor(val originalId: String?) : Route()

        data class ExerciseEditor(val exerciseId: String?) : Route()

        data class WorkoutHistory(
            val workoutId: String,
            val returnScrollY: Int,
            val editing: Boolean = false,
        ) : Route()

        data class ExerciseTrend(
            val exerciseId: String,
            val returnRoute: Route,
            val returnScrollY: Int,
            val span: TrendSpan = TrendSpan.THREE_MONTHS,
        ) : Route()

        data class BodyMeasurementDetail(val bodyPart: BodyPart) : Route()

        data class Confirm(val action: ConfirmAction, val targetId: String? = null) : Route()
    }

    private lateinit var repository: LofitRepository
    private val ui by lazy {
        LofitViews(this, exercisePictures) { repository.data.biologicalGender }
    }
    private val setViews by lazy { PlannedSetViews(this, ui) { updatingHoldTimer } }
    private val trainingStats by lazy {
        TrainingStatsView(
            activityContext = this,
            ui = ui,
            data = { repository.data },
            onMonthChanged = { render() },
            onOpenWorkout = { openWorkoutHistory(it) },
        )
    }
    private val exerciseScreens by lazy {
        ExerciseScreens(
            activityContext = this,
            ui = ui,
            data = { repository.data },
            save = repository::save,
            onEdit = { id ->
                route = Route.ExerciseEditor(id)
                render()
            },
            onDelete = { id ->
                route = Route.Confirm(ConfirmAction.DELETE_EXERCISE, id)
                render()
            },
            onChoosePicture = { openExercisePicture.launch(arrayOf("image/*")) },
            onDeletePicture = ::deleteUnsavedPicture,
            onSaved = {
                route = Route.Root
                currentScreen = Screen.EXERCISES
                render()
            },
            onCancel = ::navigateBack,
        )
    }
    private val records by lazy {
        RecordScreens(this, ui, { repository.data }, ::openExerciseTrend)
    }
    private val bodyMeasurements by lazy {
        BodyMeasurementScreens(this, ui, { repository.data }, repository::save, { render() }) { part
            ->
            route = Route.BodyMeasurementDetail(part)
            render()
        }
    }
    private val routines by lazy {
        RoutineScreens(
            activityContext = this,
            ui = ui,
            sets = setViews,
            data = { repository.data },
            muscleMaps = ::exerciseMuscleMaps,
            onEdit = ::openRoutineEditor,
            onStart = ::startRoutine,
            onDelete = { id ->
                route = Route.Confirm(ConfirmAction.DELETE_ROUTINE, id)
                render()
            },
            onAddExercise = {
                openExercisePicker(PickerTarget.ROUTINE)
            },
            onSelectExercise = { id, placement ->
                openExercisePicker(PickerTarget.ROUTINE, id, placement)
            },
            onSave = ::saveRoutineDraft,
            onCancel = ::cancelRoutineEditor,
        )
    }
    private val settings by lazy {
        SettingsScreen(
            activityContext = this,
            ui = ui,
            data = { repository.data },
            save = repository::save,
            hevySettings = hevySettings,
            onHealthChanged = ::setBodyMetricsEnabled,
            onMcpChanged = ::updateMcpServer,
            onMcpPortChange = ::changeMcpPort,
            craneScaleSettings = { craneScaleScreens.settingsCard() },
            onRefresh = { render() },
        )
    }

    private val hevySettings by lazy { HevySettings(this) }
    private val hevyWorker = Executors.newSingleThreadExecutor()
    private var mcpServer: McpHttpServer? = null

    private data class PreviousScreen(
        val screen: Screen,
        val route: Route,
        val scrollY: Int,
        val routineDraft: Routine?,
        val historyPage: Int,
    )

    private val previousScreens = mutableListOf<PreviousScreen>()
    private val healthWeightReader by lazy { HealthConnectWeightReader(this) }
    private var healthPermissionPending = false
    private var onboardingGender = BiologicalGender.MALE
    private var onboardingHealthEnabled = false
    private var bodyMetricsReadGeneration = 0
    private lateinit var content: FrameLayout
    private lateinit var root: LinearLayout
    private lateinit var drawerLayout: DrawerLayout
    private val drawerMenuItems = mutableMapOf<Screen, MaterialButton>()
    private lateinit var restBar: LinearLayout
    private lateinit var historyPager: LinearLayout
    private lateinit var restTimeText: TextView
    private var currentScreen = Screen.HOME
    private var historyPage = 0
    private var route: Route = Route.Root
    private var routineDraft: Routine? = null
    private var historyEditDraft: WorkoutSession? = null
    private var exerciseEditUndo: Snackbar? = null
    private var exerciseDraft: Bundle? = null
    private val exercisePictures by lazy { ExercisePictures(this) }
    private val pictureWorker = Executors.newSingleThreadExecutor()
    private var pictureGeneration = 0
    private val openExercisePicture =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null && route is Route.ExerciseEditor) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                importExercisePicture(uri)
            }
        }
    private var timerText: TextView? = null
    private var holdTimerText: TextView? = null
    private var holdTimerButton: TextView? = null
    private var holdDoneCheckBox: CheckBox? = null
    private var activeHoldWorkoutExerciseId: String? = null
    private var activeHoldPlannedSetId: String? = null
    private var holdStartedAt = 0L
    private var holdCuePlayed = false
    private lateinit var cueSounds: SoundPool
    private lateinit var cueAudioManager: AudioManager
    private lateinit var cueAudioFocusRequest: AudioFocusRequest
    private val releaseCueAudioFocus = Runnable {
        cueAudioManager.abandonAudioFocusRequest(cueAudioFocusRequest)
    }
    private var completionCueId = 0
    private var updatingHoldTimer = false
    private var completingHoldAutomatically = false
    private var restEndsAt = 0L
    private var lastWorkoutNotification = ""
    private val handler = Handler(Looper.getMainLooper())
    private val workoutFileWorker = Executors.newSingleThreadExecutor()
    private var workoutFileGeneration = 0
    private var pendingImportedPlan: SharedWorkoutPlan? = null
    private val openWorkoutFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importWorkoutFile)
        }
    private val workoutBluetooth by lazy { WorkoutBluetooth(this) }
    private val craneScaleScreens by lazy {
        CraneScaleScreens(
            context = this,
            ui = ui,
            data = { repository.data },
            save = repository::save,
            withBluetooth = ::withBluetooth,
            onRefresh = { render() },
        )
    }
    private val liveWorkoutMeasurement by lazy { LiveWorkoutMeasurement { repository.data } }
    // Readings, completion, timer updates and graph rendering share one monotonic clock.
    internal var liveMeasurementNow: () -> Long = SystemClock::elapsedRealtime
    private val liveGraphs = mutableListOf<LiveMeasurementGraphView>()
    private val liveStatusViews = mutableListOf<TextView>()
    private val liveWeightViews = mutableMapOf<String, EditText>()
    private val liveTimeViews = mutableMapOf<String, EditText>()
    private var workoutForeground = false
    private var workoutScaleStatus: String? = "Waiting for scale…"
    private val workoutScaleReader by lazy {
        WorkoutScaleReader(
            scanner = BluetoothCraneScaleScanner(this),
            withBluetooth = ::withBluetooth,
            onReading = { onWorkoutScaleReading(liveMeasurementNow(), it) },
            onInterrupted = {
                liveWorkoutMeasurement.interrupt()?.let {
                    refreshLiveSetValues(it.pull.set)
                    repository.save()
                    updateWorkoutNotification(force = true)
                }
            },
            onStatus = { message ->
                workoutScaleStatus = message
                liveStatusViews.forEach { view ->
                    view.text = message.orEmpty()
                    view.visibility = if (message == null) View.GONE else View.VISIBLE
                }
            },
        )
    }
    private var workoutTogetherDialog: AlertDialog? = null
    private var pendingBluetoothAction: (() -> Unit)? = null
    private val bluetoothPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val action = pendingBluetoothAction
            pendingBluetoothAction = null
            if (result.isNotEmpty() && result.values.all { it }) action?.let(::withBluetooth)
            else
                Toast.makeText(this, "Allow Nearby devices to use Bluetooth", Toast.LENGTH_SHORT)
                    .show()
        }
    private val enableBluetooth =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val action = pendingBluetoothAction
            pendingBluetoothAction = null
            if (it.resultCode == RESULT_OK) action?.invoke()
            else Toast.makeText(this, "Bluetooth is off", Toast.LENGTH_SHORT).show()
        }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) updateWorkoutNotification(force = true)
        }
    private val healthPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            healthPermissionPending = false
            if (repository.data.bodyMetricsEnabled == true && !healthWeightReader.canReadWeight) {
                repository.data.bodyMetricsEnabled = false
                repository.save()
            }
            repository.data.activeWorkout
                ?.takeIf { it.bodyWeightKg == null }
                ?.let(::queryWorkoutWeight)
            if (currentScreen == Screen.SETTINGS) render()
            ensureWorkoutNotification()
        }
    private val notificationActionReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_SUBTRACT_REST -> subtractRest()
                    ACTION_ADD_REST -> addRest()
                    ACTION_SKIP_REST -> skipRest()
                }
            }
        }

    private val tick =
        object : Runnable {
            override fun run() {
                updateWorkoutTimer()
                updateHoldTimer()
                handler.postDelayed(this, 250)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hevySettings
        repository = LofitRepository(this)
        val cueAudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        cueAudioManager = getSystemService(AudioManager::class.java)
        cueAudioFocusRequest =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(cueAudioAttributes)
                .build()
        cueSounds =
            SoundPool.Builder().setMaxStreams(1).setAudioAttributes(cueAudioAttributes).build()
        completionCueId = cueSounds.load(this, R.raw.completion_chime, 1)
        if (
            repository.data.activeWorkout != null &&
                (savedInstanceState == null ||
                    savedInstanceState.getBoolean("showActiveWorkout") ||
                    intent.action == ACTION_OPEN_WORKOUT)
        ) {
            currentScreen = Screen.WORKOUT
        }
        healthPermissionPending = savedInstanceState?.getBoolean("healthPermissionPending") ?: false
        onboardingGender =
            savedInstanceState?.getString("onboardingGender")?.let(BiologicalGender::valueOf)
                ?: repository.data.biologicalGender
        onboardingHealthEnabled =
            savedInstanceState?.getBoolean("onboardingHealthEnabled")
                ?: (repository.data.bodyMetricsEnabled == true)
        exerciseDraft = savedInstanceState?.getBundle("exerciseDraft")
        exerciseDraft?.let {
            currentScreen = Screen.EXERCISES
            route = Route.ExerciseEditor(it.getString("exerciseId"))
        }
        if (savedInstanceState?.getBoolean("showLiveMeasurement") == true) {
            currentScreen = Screen.LIVE_MEASUREMENT
            route = Route.Root
        }
        if (savedInstanceState?.getBoolean("showBodyMeasurements") == true) {
            currentScreen = Screen.BODY_MEASUREMENTS
            bodyMeasurements.restoreDraft(savedInstanceState.getBundle("bodyMeasurementDraft"))
            route =
                savedInstanceState
                    .getString("measurementBodyPart")
                    ?.let(BodyPart::valueOf)
                    ?.takeIf { it.selectable }
                    ?.let {
                        Route.BodyMeasurementDetail(it)
                    } ?: Route.Root
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (route != Route.Root || currentScreen != Screen.HOME) {
                        navigateBack()
                    } else {
                        isEnabled = false
                        try {
                            onBackPressedDispatcher.onBackPressed()
                        } finally {
                            isEnabled = true
                        }
                    }
                }
            },
        )
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
        updateMcpServer()
        exerciseDraft?.getString("pendingPicture")?.let { importExercisePicture(Uri.parse(it)) }
        if (repository.data.onboardingComplete && !healthPermissionPending) {
            repository.data.activeWorkout?.let { workout ->
                if (workout.bodyWeightKg == null) queryWorkoutWeight(workout)
                ensureWorkoutNotification()
            }
        }
        handler.post(tick)
        handleWorkoutFileIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        craneScaleScreens.onStart()
        workoutForeground = true
        updateWorkoutScaleScan()
    }

    override fun onPostResume() {
        super.onPostResume()
        showPendingImportedPlan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_WORKOUT) openActiveWorkout()
        handleWorkoutFileIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("showActiveWorkout", currentScreen == Screen.WORKOUT)
        outState.putBoolean("showLiveMeasurement", currentScreen == Screen.LIVE_MEASUREMENT)
        outState.putBoolean("showBodyMeasurements", currentScreen == Screen.BODY_MEASUREMENTS)
        if (currentScreen == Screen.BODY_MEASUREMENTS) {
            outState.putBundle("bodyMeasurementDraft", bodyMeasurements.saveDraft())
        }
        (route as? Route.BodyMeasurementDetail)?.let {
            outState.putString("measurementBodyPart", it.bodyPart.name)
        }
        outState.putBoolean("healthPermissionPending", healthPermissionPending)
        outState.putString("onboardingGender", onboardingGender.name)
        outState.putBoolean("onboardingHealthEnabled", onboardingHealthEnabled)
        outState.putBundle("exerciseDraft", exerciseDraft)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        craneScaleScreens.onStop()
        workoutForeground = false
        updateWorkoutScaleScan()
        workoutTogetherDialog?.dismiss()
        workoutBluetooth.stop()
        super.onStop()
    }

    override fun onDestroy() {
        craneScaleScreens.clear()
        mcpServer?.close()
        mcpServer = null
        workoutFileGeneration++
        workoutFileWorker.shutdownNow()
        hevyWorker.shutdown()
        pictureGeneration++
        pictureWorker.shutdown()
        if (isFinishing) {
            exerciseDraft?.getString("imageFile")?.let(::deleteUnsavedPicture)
            exerciseDraft?.getString("pendingPicture")?.let {
                releasePicturePermission(Uri.parse(it))
            }
        }
        workoutTogetherDialog?.dismiss()
        workoutBluetooth.stop()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(releaseCueAudioFocus)
        cueAudioManager.abandonAudioFocusRequest(cueAudioFocusRequest)
        cueSounds.release()
        unregisterReceiver(notificationActionReceiver)
        super.onDestroy()
    }

    private fun buildShell() {
        drawerLayout =
            DrawerLayout(this).apply {
                setBackgroundColor(ui.color(R.color.charcoal))
                setScrimColor(Color.argb(170, 0, 0, 0))
            }
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(ui.color(R.color.charcoal))
            }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            view.setPadding(0, bars.top, 0, max(bars.bottom, keyboard))
            insets
        }
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        historyPager =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                setBackgroundColor(ui.color(R.color.charcoal_light))
                contentDescription = "History pages"
                visibility = View.GONE
            }
        root.addView(
            historyPager,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        restBar = createRestBar()
        root.addView(
            restBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        drawerLayout.addView(
            root,
            DrawerLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        drawerLayout.addView(
            drawerMenu(),
            DrawerLayout.LayoutParams(ui.dp(300), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = GravityCompat.START
            },
        )
        setContentView(drawerLayout)
    }

    private fun drawerMenu(): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))
            setBackgroundColor(ui.color(R.color.charcoal_light))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(ui.dp(18), bars.top + ui.dp(18), ui.dp(18), bars.bottom + ui.dp(18))
                insets
            }
            addView(
                ui.titleText("Lofit").apply {
                    textSize = 30f
                    setPadding(ui.dp(12), 0, 0, ui.dp(20))
                }
            )
            val itemStates = arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf())
            listOf(
                    Screen.HOME to getString(R.string.nav_workout),
                    Screen.ROUTINES to getString(R.string.nav_routines),
                    Screen.EXERCISES to getString(R.string.nav_exercises),
                    Screen.RECORDS to getString(R.string.nav_records),
                    Screen.HISTORY to getString(R.string.nav_history),
                    Screen.BODY_MEASUREMENTS to getString(R.string.nav_body_measurements),
                    Screen.LIVE_MEASUREMENT to getString(R.string.nav_live_measurement),
                    Screen.SETTINGS to getString(R.string.nav_settings),
                )
                .forEach { (screen, label) ->
                    addView(
                        ui.actionButton(label, secondary = true) {
                                drawerLayout.closeDrawer(GravityCompat.START, false)
                                openScreen(screen)
                            }
                            .apply {
                                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                                textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                                setPadding(ui.dp(12), 0, ui.dp(12), 0)
                                backgroundTintList =
                                    ColorStateList(
                                        itemStates,
                                        intArrayOf(
                                            ui.color(R.color.charcoal_surface_high),
                                            Color.TRANSPARENT,
                                        ),
                                    )
                                setTextColor(
                                    ColorStateList(
                                        itemStates,
                                        intArrayOf(
                                            ui.color(R.color.coral),
                                            ui.color(R.color.cream),
                                        ),
                                    )
                                )
                                drawerMenuItems[screen] = this
                            },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(52))
                            .apply { bottomMargin = ui.dp(6) },
                    )
                }
        }

    private fun render(
        restoreScrollY: Int? = null,
        scrollToBottom: Boolean = false,
        focusExerciseId: String? = null,
    ) {
        craneScaleScreens.clear()
        liveWorkoutMeasurement.reconcile()
        liveGraphs.clear()
        liveStatusViews.clear()
        liveWeightViews.clear()
        liveTimeViews.clear()
        val selectedScreen = if (currentScreen == Screen.WORKOUT) Screen.HOME else currentScreen
        drawerMenuItems.forEach { (screen, item) ->
            item.isSelected = screen == selectedScreen
            item.setTypeface(null, if (item.isSelected) Typeface.BOLD else Typeface.NORMAL)
        }
        drawerMenuItems[Screen.LIVE_MEASUREMENT]?.visibility =
            if (repository.data.craneScale != null) View.VISIBLE else View.GONE
        exerciseEditUndo?.dismiss()
        exerciseEditUndo = null
        content.setPadding(0, 0, 0, 0)
        exerciseScreens.clearPicturePreview()
        if (route !is Route.ExerciseEditor) {
            pictureGeneration++
            exerciseDraft?.getString("imageFile")?.let(::deleteUnsavedPicture)
            exerciseDraft?.getString("pendingPicture")?.let {
                releasePicturePermission(Uri.parse(it))
            }
            exerciseDraft = null
        }
        if (
            repository.data.activeWorkout != null ||
                (currentScreen == Screen.LIVE_MEASUREMENT && route == Route.Root)
        ) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        if (repository.data.activeWorkout == null) {
            restEndsAt = 0L
            if (::restBar.isInitialized) restBar.visibility = View.GONE
            clearActiveHold()
        }
        WindowCompat.getInsetsController(window, root).hide(WindowInsetsCompat.Type.ime())
        currentFocus?.clearFocus()
        content.removeAllViews()
        historyPager.visibility = View.GONE
        timerText = null
        holdTimerText = null
        holdTimerButton = null
        holdDoneCheckBox = null
        drawerLayout.setDrawerLockMode(
            if (repository.data.onboardingComplete) {
                DrawerLayout.LOCK_MODE_UNLOCKED
            } else {
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED
            }
        )
        if (!repository.data.onboardingComplete) {
            restBar.visibility = View.GONE
            content.addView(onboardingScreen())
            return
        }
        val view =
            when (val activeRoute = route) {
                Route.Root -> rootScreen()
                is Route.ExercisePicker -> exercisePickerScreen(activeRoute)
                is Route.RoutineEditor ->
                    routines.routineEditorScreen(routineDraft, activeRoute.originalId)
                is Route.ExerciseEditor ->
                    exerciseScreens.exerciseEditorScreen(
                        activeRoute.exerciseId,
                        exerciseDraft
                            ?: exerciseScreens.createDraft(activeRoute.exerciseId).also {
                                exerciseDraft = it
                            },
                    )
                is Route.WorkoutHistory -> {
                    val workout =
                        if (activeRoute.editing)
                            historyEditDraft?.takeIf { it.id == activeRoute.workoutId }
                        else repository.data.history.firstOrNull { it.id == activeRoute.workoutId }
                    workout?.let { pastWorkoutScreen(it, activeRoute.editing) } ?: historyScreen()
                }
                is Route.ExerciseTrend ->
                    records.exerciseTrendScreen(activeRoute.exerciseId, activeRoute.span) { span ->
                        route = activeRoute.copy(span = span)
                        render()
                    }
                is Route.BodyMeasurementDetail -> bodyMeasurements.detail(activeRoute.bodyPart)
                is Route.Confirm -> confirmationScreen(activeRoute)
            }
        content.addView(view)
        craneScaleScreens.startLive()
        updateWorkoutScaleScan()
        if (focusExerciseId != null && view is ScrollView) {
            view.doOnPreDraw {
                val card = view.findViewWithTag<View>(focusExerciseId) ?: return@doOnPreDraw
                val bounds = Rect()
                card.getDrawingRect(bounds)
                view.offsetDescendantRectToMyCoords(card, bounds)
                view.scrollTo(0, (bounds.top - ui.dp(12)).coerceAtLeast(0))
                card.alpha = 0.65f
                card.animate().alpha(1f).setDuration(450).start()
            }
        } else if (scrollToBottom) {
            (view as? ScrollView)?.doOnPreDraw { view.scrollTo(0, view.getChildAt(0).height) }
        } else
            restoreScrollY?.let { scrollY ->
                (view as? ScrollView)?.doOnPreDraw { view.scrollTo(0, scrollY) }
            }
        updateWorkoutNotification()
    }

    private fun rootScreen(): View =
        when (currentScreen) {
            Screen.HOME -> workoutHome()
            Screen.WORKOUT ->
                repository.data.activeWorkout?.let(::activeWorkoutScreen) ?: workoutHome()
            Screen.ROUTINES -> routines.routinesScreen()
            Screen.EXERCISES -> exerciseScreens.exercisesScreen()
            Screen.RECORDS -> records.recordsScreen()
            Screen.BODY_MEASUREMENTS -> bodyMeasurements.overview()
            Screen.HISTORY -> historyScreen()
            Screen.LIVE_MEASUREMENT -> craneScaleScreens.liveScreen()
            Screen.SETTINGS -> settings.settingsScreen()
        }

    private fun onboardingScreen(): View =
        ui.page("Welcome to Lofit") { body ->
            body.addView(settings.genderChoice(onboardingGender) { onboardingGender = it })
            body.addView(ui.sectionTitle("Android health integration"))
            body.addView(
                settings.healthIntegrationChoice(onboardingHealthEnabled) {
                    onboardingHealthEnabled = it
                }
            )
            body.addView(
                ui.actionButton("Get started") {
                    repository.data.biologicalGender = onboardingGender
                    repository.data.onboardingComplete = true
                    repository.save()
                    render()
                    setBodyMetricsEnabled(onboardingHealthEnabled)
                    showPendingImportedPlan()
                }
            )
        }

    private fun changeMcpPort(port: Int): Boolean {
        if (!repository.data.mcpEnabled) return true
        return try {
            val replacement = createMcpServer(port)
            val previous = mcpServer
            mcpServer = replacement
            previous?.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun updateMcpServer() {
        if (!repository.data.mcpEnabled) {
            mcpServer?.close()
            mcpServer = null
            return
        }
        if (mcpServer != null) return
        try {
            mcpServer = createMcpServer(repository.data.mcpPort)
        } catch (_: Exception) {
            repository.data.mcpEnabled = false
            repository.save()
            Toast.makeText(this, "MCP server could not start", Toast.LENGTH_LONG).show()
        }
    }

    private fun createMcpServer(port: Int): McpHttpServer {
        val protocol =
            McpProtocol(repository, exercisePictures) {
                if (
                    route == Route.Root &&
                        currentScreen in listOf(Screen.HOME, Screen.ROUTINES, Screen.EXERCISES)
                )
                    render()
            }
        lateinit var server: McpHttpServer
        server =
            McpHttpServer(port) { body ->
                val task = FutureTask {
                    check(mcpServer === server && repository.data.mcpEnabled && !isDestroyed)
                    protocol.handle(body)?.toString()
                }
                handler.post(task)
                try {
                    task.get(10, TimeUnit.SECONDS)
                } finally {
                    task.cancel(false)
                }
            }
        return server
    }

    private fun workoutHome(): View =
        ui.page("Ready to move?") { body ->
            repository.data.activeWorkout?.let { workout ->
                body.addView(
                    ui.card {
                        addView(ui.titleText("Active workout"))
                        addView(ui.metaText(workout.name))
                        addView(
                            ui.actionButton("Resume workout") { openActiveWorkout() }
                                .apply {
                                    contentDescription = "Open active workout"
                                },
                            LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                )
                                .apply {
                                    topMargin = ui.dp(12)
                                },
                        )
                    }
                )
            }
            body.addView(
                trainingStats.workoutCalendar().apply {
                    (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(12)
                }
            )
            body.addView(ui.sectionTitle("Routines"))
            if (repository.data.activeWorkout == null) {
                body.addView(
                    ui.actionButton(getString(R.string.start_workout)) {
                        startWorkout(WorkoutSession(name = "Open workout"))
                    },
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply {
                            bottomMargin = ui.dp(16)
                        },
                )
            }
            body.addView(
                togetherMenuButton(
                    "Workout Together",
                    ::scanTogetherWorkouts,
                    "Open .lofit file",
                ) {
                    openWorkoutFile.launch(arrayOf("*/*"))
                },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                        bottomMargin = ui.dp(16)
                    },
            )
            if (repository.data.routines.isEmpty()) {
                body.addView(
                    ui.emptyText(
                        "No routines yet. Build one in the Routines tab, or start empty and add exercises as you go."
                    )
                )
            }
            repository.data.routines.forEach { routine ->
                body.addView(
                    ui.card {
                        addView(ui.titleText(routine.name))
                        addView(
                            ui.metaText(
                                "${routine.exercises.size} exercises · ${routine.defaultRestSeconds}s default rest"
                            )
                        )
                        addView(
                            exerciseMuscleMaps(
                                routine.exercises.map { it.exerciseId to it.plannedSets.size }
                            )
                        )
                        addView(ui.actionButton("Start routine") { startRoutine(routine) })
                    }
                )
            }
        }

    private fun activeWorkoutScreen(workout: WorkoutSession): View {
        val finishButton =
            ui.actionButton("Finish workout") {
                if (workout.setCount() > 0) {
                    route = Route.Confirm(ConfirmAction.FINISH_WORKOUT)
                    render()
                }
            }
        fun updateFinishButton() {
            finishButton.isEnabled = workout.setCount() > 0
            finishButton.backgroundTintList =
                ColorStateList.valueOf(
                    ui.color(
                        if (finishButton.isEnabled) R.color.coral else R.color.charcoal_surface_high
                    )
                )
            finishButton.setTextColor(
                ui.color(if (finishButton.isEnabled) R.color.white else R.color.sage)
            )
        }
        updateFinishButton()
        val workoutTimer =
            TextView(this).apply {
                textSize = 18f
                setTextColor(ui.color(R.color.cream))
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                contentDescription = "Workout timer"
            }
        timerText = workoutTimer
        val view =
            ui.page(
                workout.name,
                headerEnd = workoutTimer,
                onTitleChange = { name ->
                    workout.name = name
                    repository.save()
                    updateWorkoutNotification(force = true)
                },
            ) { body ->
                updateWorkoutTimer()
                body.addView(
                    togetherMenuButton(
                        "Share workout",
                        { shareTogetherWorkout(workout) },
                        "Share .lofit file",
                    ) {
                        shareWorkoutFile(workout)
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
                body.addView(
                    ui.restInput(workout.defaultRestSeconds) { seconds ->
                        workout.defaultRestSeconds = seconds
                        repository.save()
                    }
                )

                val exerciseCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                val cardViews = mutableMapOf<String, View>()
                val reorder =
                    ExerciseReorderController(exerciseCards) { from, to ->
                        workout.moveExercise(from, to)
                        repository.save()
                        updateWorkoutNotification(force = true)
                    }
                val doneStateUpdaters = mutableMapOf<String, (Set<String>) -> Unit>()
                fun updateDoneStates() {
                    val recordSetIds = repository.data.recordSetIds(workout)
                    doneStateUpdaters.values.forEach { it(recordSetIds) }
                }
                fun removeWorkoutExercise(item: WorkoutExercise) {
                    if (activeHoldWorkoutExerciseId == item.id) clearActiveHold()
                    workout.exercises.remove(item)
                    cardViews.remove(item.id)?.let(reorder::remove)
                    if (workout.exercises.isEmpty()) {
                        exerciseCards.addView(
                            ui.emptyText(
                                "This workout is empty. Add any exercise when you’re ready."
                            )
                        )
                    }
                    repository.save()
                    updateFinishButton()
                    updateWorkoutNotification(force = true)
                }
                if (workout.exercises.isEmpty()) {
                    exerciseCards.addView(
                        ui.emptyText("This workout is empty. Add any exercise when you’re ready.")
                    )
                }
                workout.exercises.forEach { item ->
                    exercise(item.exerciseId)?.let { movement ->
                        lateinit var handle: View
                        val card =
                            workoutExerciseCard(
                                workout = workout,
                                item = item,
                                exercise = movement,
                                onRemove = { removeWorkoutExercise(item) },
                                onReorderHandleReady = { handle = it },
                                onDoneStateUpdaterReady = { setId, update ->
                                    doneStateUpdaters[setId] = update
                                },
                                onSetsChanged = {
                                    updateDoneStates()
                                    updateFinishButton()
                                },
                            )
                        cardViews[item.id] = card
                        card.tag = item.id
                        exerciseCards.addView(card)
                        reorder.bind(handle, card)
                    }
                }
                updateDoneStates()
                body.addView(exerciseCards)
                body.addView(
                    ui.actionButton("Add exercise") {
                        val scrollY = (content.getChildAt(0) as? ScrollView)?.scrollY
                        route = Route.ExercisePicker(PickerTarget.WORKOUT, scrollY)
                        render()
                    }
                )
                body.addView(ui.divider())
                body.addView(
                    finishButton,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { bottomMargin = ui.dp(8) },
                )
                body.addView(
                    ui.actionButton("Discard workout", secondary = true) {
                        confirmDiscardWorkout()
                    }
                )
            }
        return view
    }

    private fun confirmDiscardWorkout() {
        val workout = repository.data.activeWorkout ?: return
        fun discard() {
            discardActiveWorkout()
            repository.save()
            route = Route.Root
            render()
        }
        if (workout.exercises.size > 1 || workout.setCount() > 0) {
            AlertDialog.Builder(this)
                .setTitle("Discard workout?")
                .setMessage("All exercises and sets in this workout will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Discard workout") { _, _ -> discard() }
                .show()
        } else {
            discard()
        }
    }

    private fun discardActiveWorkout() {
        repository.data.activeWorkout = null
        currentScreen = Screen.HOME
        previousScreens.clear()
        restEndsAt = 0L
        clearActiveHold()
    }

    private fun workoutExerciseCard(
        workout: WorkoutSession,
        item: WorkoutExercise,
        exercise: Exercise,
        onRemove: () -> Unit,
        onReorderHandleReady: (View) -> Unit,
        onDoneStateUpdaterReady: (String, (Set<String>) -> Unit) -> Unit,
        onSetsChanged: () -> Unit,
        editingHistory: Boolean = false,
    ): View =
        ui.card(horizontalPadding = 0) {
            val saveChanges: () -> Unit = {
                exerciseEditUndo?.dismiss()
                if (!editingHistory) repository.save()
            }
            addView(
                ui.exerciseHeading(
                    exercise,
                    large = true,
                    showMuscle = false,
                    actions =
                        ui.exerciseActions(
                            exercise = exercise,
                            onRemove = onRemove,
                            onReorderHandleReady = onReorderHandleReady,
                            onSelectExercise = { placement ->
                                openExercisePicker(
                                    if (editingHistory) PickerTarget.HISTORY
                                    else PickerTarget.WORKOUT,
                                    item.id,
                                    placement,
                                )
                            },
                            replaceRemaining = item.sets.isNotEmpty(),
                            canReplace =
                                item.sets.isEmpty() ||
                                    item.plannedSets.any { loggedSetFor(item, it) == null },
                            pauseEverySecondSet = { item.pauseEverySecondSet },
                            onPauseEverySecondSetChange = {
                                item.pauseEverySecondSet = it
                                saveChanges()
                            },
                            useScale =
                                if (!editingHistory && exercise.supportsLiveMeasurement)
                                    ({ !item.manualMeasurement })
                                else null,
                            onUseScaleChange = { useScale ->
                                if (activeHoldWorkoutExerciseId == item.id) stopActiveHold()
                                if (liveWorkoutMeasurement.active?.item?.id == item.id) {
                                    liveWorkoutMeasurement.interrupt()?.let {
                                        refreshLiveSetValues(it.pull.set)
                                    }
                                }
                                item.manualMeasurement = !useScale
                                repository.save()
                                render(
                                    restoreScrollY = (content.getChildAt(0) as? ScrollView)?.scrollY
                                )
                            },
                        ),
                ),
                ui.insetCardLayoutParams(),
            )
            addView(
                ui.exerciseNote(
                    item.note,
                    exercise,
                    previousNote = item.previousNote,
                    onChange = { item.note = it },
                    onCommit = saveChanges,
                ),
                ui.insetCardLayoutParams().apply {
                    topMargin = ui.dp(8)
                    bottomMargin = ui.dp(8)
                },
            )
            val liveMeasurement = !editingHistory && liveWorkoutMeasurement.isLive(item)
            if (liveMeasurement) {
                addView(
                    LiveMeasurementGraphView(
                            this@MainActivity,
                            now = { liveMeasurementNow() },
                            series = liveWorkoutMeasurement.seriesFor(item.id),
                            targetWeightKg = {
                                item.plannedSets
                                    .firstOrNull { loggedSetFor(item, it) == null }
                                    ?.weightKg
                            },
                        )
                        .also {
                            it.contentDescription = "${exercise.name} live weight graph"
                            liveGraphs.add(it)
                        },
                    ui.insetCardLayoutParams(),
                )
                addView(
                    ui.metaText(workoutScaleStatus.orEmpty()).apply {
                        contentDescription = "${exercise.name} scale status"
                        visibility = if (workoutScaleStatus == null) View.GONE else View.VISIBLE
                        liveStatusViews.add(this)
                    },
                    ui.insetCardLayoutParams(),
                )
                addView(
                    ui.actionButton("Retry scale", secondary = true) { workoutScaleReader.retry() },
                    ui.insetCardLayoutParams().apply {
                        topMargin = ui.dp(8)
                        bottomMargin = ui.dp(8)
                    },
                )
            }
            addView(
                setViews.plannedSetHeader(
                    exercise,
                    showDone = true,
                    showActiveHold = exercise.type.tracksDuration,
                    showActiveHoldAction = !editingHistory && !liveMeasurement,
                )
            )
            val setRows =
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                }
            val rowViews = mutableMapOf<String, View>()
            val updateRowIndices = mutableMapOf<String, (Int) -> Unit>()
            fun removeWorkoutSets(fromIndex: Int, allBelow: Boolean) {
                val endIndex = if (allBelow) item.plannedSets.size else fromIndex + 1
                val indices = fromIndex until endIndex
                val removedSets = item.plannedSets.subList(fromIndex, endIndex).toList()
                val loggedIds =
                    indices
                        .mapNotNull { setIndex ->
                            item.plannedSets.getOrNull(setIndex)?.let { set ->
                                loggedSetFor(item, set)?.id
                            }
                        }
                        .toSet()
                if (
                    activeHoldWorkoutExerciseId == item.id &&
                        removedSets.any { it.id == activeHoldPlannedSetId }
                ) {
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
                saveChanges()
                onSetsChanged()
                if (!editingHistory) updateWorkoutNotification(force = true)
            }
            fun addWorkoutSetRow(plannedSet: PlannedSet, setIndex: Int) {
                val loggedSet = loggedSetFor(item, plannedSet)
                val done =
                    ui.largeCheckBox().apply {
                        isChecked = loggedSet != null
                        contentDescription = "Set ${setIndex + 1} done"
                        stateDescription = if (isChecked) "Done" else "Not done"
                    }
                val doneDrawable = done.buttonDrawable
                val recordDrawable =
                    ui.sizedButtonDrawable(
                        InsetDrawable(
                            ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_crown),
                            ui.dp(8),
                        ),
                        PlannedSetViews.CHECKBOX_WIDTH_DP,
                    )
                onDoneStateUpdaterReady(plannedSet.id) { recordSetIds ->
                    val isRecord = loggedSetFor(item, plannedSet)?.id in recordSetIds
                    done.buttonTintList = if (isRecord) null else ui.toggleTint()
                    done.buttonDrawable = if (isRecord) recordDrawable else doneDrawable
                    done.stateDescription =
                        when {
                            isRecord -> "Done, record"
                            done.isChecked -> "Done"
                            else -> "Not done"
                        }
                }
                lateinit var activeHoldAmount: EditText
                val holdButton =
                    if (exercise.type.tracksDuration && !editingHistory && !liveMeasurement)
                        TextView(this@MainActivity).apply {
                            text = if (isActiveHold(item, plannedSet)) "■" else "▶"
                            textSize = 18f
                            includeFontPadding = false
                            gravity = Gravity.CENTER
                            setPadding(0, 0, 0, 0)
                            setTextColor(ui.color(R.color.cream))
                            contentDescription =
                                if (isActiveHold(item, plannedSet)) "Stop set ${setIndex + 1} hold"
                                else "Start set ${setIndex + 1} hold"
                            setOnClickListener {
                                if (isActiveHold(item, plannedSet)) {
                                    stopActiveHold()
                                } else {
                                    startInlineHold(
                                        item,
                                        plannedSet,
                                        activeHoldAmount,
                                        this,
                                        done,
                                    )
                                }
                            }
                        }
                    else null
                val rowActions =
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            done,
                            LinearLayout.LayoutParams(
                                ui.dp(PlannedSetViews.CHECKBOX_WIDTH_DP),
                                ui.dp(56),
                            ),
                        )
                    }
                val setRow =
                    setViews.plannedSetEditor(
                        exercise = exercise,
                        plannedSet = plannedSet,
                        setIndex = setIndex,
                        endAction = rowActions,
                        endActionHeight = 56,
                        completed = loggedSet != null,
                        onRemove = {
                            item.plannedSets
                                .indexOf(plannedSet)
                                .takeIf { it >= 0 }
                                ?.let { removeWorkoutSets(it, false) }
                        },
                        onRemoveAndBelow = {
                            item.plannedSets
                                .indexOf(plannedSet)
                                .takeIf { it >= 0 }
                                ?.let { removeWorkoutSets(it, true) }
                        },
                        showActiveHold = exercise.type.tracksDuration,
                        activeHoldAction = holdButton,
                        activeHoldRunning = isActiveHold(item, plannedSet),
                        measured = liveMeasurement,
                        onWeightReady = {
                            if (liveMeasurement) liveWeightViews[plannedSet.id] = it
                        },
                        onActiveAmountReady = { amount ->
                            activeHoldAmount = amount
                            if (liveMeasurement) liveTimeViews[plannedSet.id] = amount
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
                                holdButton?.contentDescription =
                                    if (isActiveHold(item, plannedSet)) {
                                        "Stop set ${newIndex + 1} hold"
                                    } else {
                                        "Start set ${newIndex + 1} hold"
                                    }
                            }
                        },
                        onChanged = {
                            loggedSetFor(item, plannedSet)?.let {
                                syncLoggedSet(it, plannedSet, exercise)
                            }
                            saveChanges()
                            onSetsChanged()
                        },
                    )
                done.setOnCheckedChangeListener { _, checked ->
                    val currentIndex = item.plannedSets.indexOf(plannedSet)
                    if (currentIndex < 0) return@setOnCheckedChangeListener
                    if (checked) {
                        WindowCompat.getInsetsController(window, done)
                            .hide(WindowInsetsCompat.Type.ime())
                        currentFocus?.clearFocus()
                        if (liveMeasurement) {
                            liveWorkoutMeasurement
                                .stopForManualCompletion(plannedSet, liveMeasurementNow())
                                ?.let { refreshLiveSetValues(it.pull.set) }
                        }
                        if (isActiveHold(item, plannedSet) && !completingHoldAutomatically)
                            stopActiveHold()
                        val loggedSet = loggedSetFor(item, plannedSet)
                        if (loggedSet == null) {
                            val newSet = loggedSetFrom(plannedSet, exercise, workout.completedAt)
                            item.sets.add(newSet)
                        } else {
                            syncLoggedSet(loggedSet, plannedSet, exercise)
                        }
                        if (!editingHistory && !completingHoldAutomatically)
                            startRest(item, workout)
                    } else {
                        loggedSetFor(item, plannedSet)?.let { removedSet ->
                            item.sets.remove(removedSet)
                        }
                    }
                    setRow.setBackgroundColor(
                        if (checked) ui.color(R.color.done_green) else Color.TRANSPARENT
                    )
                    saveChanges()
                    onSetsChanged()
                    if (!editingHistory) updateWorkoutNotification(force = true)
                }
                rowViews[plannedSet.id] = setRow
                setRows.addView(setRow)
            }
            item.plannedSets.forEachIndexed { setIndex, plannedSet ->
                addWorkoutSetRow(plannedSet, setIndex)
            }
            addView(setRows)
            addView(
                ui.actionButton("Add set", secondary = true, keepVisibleAfterClick = true) {
                    val nextSets = nextPlannedSets(item.plannedSets, exercise)
                    val firstIndex = item.plannedSets.size
                    item.plannedSets.addAll(nextSets)
                    saveChanges()
                    nextSets.forEachIndexed { index, next ->
                        addWorkoutSetRow(next, firstIndex + index)
                    }
                    if (!editingHistory) updateWorkoutNotification(force = true)
                }
            )
            addView(
                ui.restOverrideInput(item.restSecondsOverride) { seconds ->
                    item.restSecondsOverride = seconds
                    saveChanges()
                },
                ui.insetCardLayoutParams(),
            )
        }

    private fun openExercisePicker(
        target: PickerTarget,
        anchorId: String? = null,
        placement: ExercisePlacement? = null,
    ) {
        val history = route as? Route.WorkoutHistory
        val exerciseId =
            when (target) {
                PickerTarget.ROUTINE ->
                    routineDraft?.exercises?.firstOrNull { it.id == anchorId }?.exerciseId
                PickerTarget.WORKOUT ->
                    repository.data.activeWorkout
                        ?.exercises
                        ?.firstOrNull { it.id == anchorId }
                        ?.exerciseId
                PickerTarget.HISTORY ->
                    historyEditDraft?.exercises?.firstOrNull { it.id == anchorId }?.exerciseId
            }
        route =
            Route.ExercisePicker(
                target = target,
                returnScrollY = (content.getChildAt(0) as? ScrollView)?.scrollY,
                historyWorkoutId = history?.workoutId,
                historyReturnScrollY = history?.returnScrollY ?: 0,
                anchorId = anchorId,
                anchorName = exerciseId?.let(::exercise)?.name,
                anchorExerciseId = exerciseId,
                placement = placement,
            )
        render()
    }

    private fun exercisePickerScreen(picker: Route.ExercisePicker): View {
        val footer =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(20))
                setBackgroundColor(ui.color(R.color.charcoal))
            }
        val target = picker.target
        val replacing = picker.placement == ExercisePlacement.REPLACE
        val anchorMovement = picker.anchorExerciseId?.let(::exercise)
        val exercises =
            repository.data
                .exercisesByRecency()
                .filter { !replacing || it.id != anchorMovement?.id }
                .let { movements ->
                    if (replacing)
                        movements.sortedByDescending {
                            it.primaryMuscle == anchorMovement?.primaryMuscle
                        }
                    else movements
                }
        val search =
            ui.textField("Search name or muscle").apply {
                inputType = InputType.TYPE_CLASS_TEXT
                contentDescription = "Search exercises"
            }
        fun addExercises(movements: List<Exercise>) {
            if (movements.isEmpty()) return
            if (replacing && movements.size != 1) return
            WindowCompat.getInsetsController(window, search).hide(WindowInsetsCompat.Type.ime())
            search.clearFocus()
            val undo: () -> Unit
            val focusId: String?
            when (target) {
                PickerTarget.WORKOUT,
                PickerTarget.HISTORY -> {
                    val workout =
                        if (target == PickerTarget.HISTORY)
                            historyEditDraft?.takeIf { it.id == picker.historyWorkoutId }
                        else repository.data.activeWorkout
                    if (workout == null) return
                    val anchorIndex = workout.exercises.indexOfFirst { it.id == picker.anchorId }
                    if (picker.placement != null && anchorIndex < 0) return
                    val anchor = workout.exercises.getOrNull(anchorIndex)
                    val originalPlans = anchor?.plannedSets?.toList().orEmpty()
                    val added = movements.map { movement ->
                        val previous =
                            repository.data.lastKnownExercise(
                                movement.id,
                                preferredRoutineId = workout.sourceRoutine?.id,
                            )
                        if (replacing && anchor != null) {
                            val remaining =
                                anchor.plannedSets.filter { loggedSetFor(anchor, it) == null }
                            WorkoutExercise(
                                exerciseId = movement.id,
                                restSecondsOverride = anchor.restSecondsOverride,
                                pauseEverySecondSet =
                                    movement.asymmetric && anchor.pauseEverySecondSet,
                                previousNote = previous?.effectiveNote.orEmpty(),
                                plannedSets =
                                    replacementSets(
                                        remaining,
                                        exercise(anchor.exerciseId) ?: return,
                                        movement,
                                        previous?.plannedSets.orEmpty(),
                                    ),
                            )
                        } else previous ?: WorkoutExercise(exerciseId = movement.id)
                    }
                    val insertionIndex =
                        when (picker.placement) {
                            ExercisePlacement.ABOVE -> anchorIndex
                            ExercisePlacement.BELOW -> anchorIndex + 1
                            ExercisePlacement.REPLACE -> {
                                if (anchor == null) return
                                if (
                                    anchor.sets.isNotEmpty() && added.single().plannedSets.isEmpty()
                                )
                                    return
                                if (activeHoldWorkoutExerciseId == anchor.id) clearActiveHold()
                                if (anchor.sets.isEmpty()) {
                                    workout.exercises.removeAt(anchorIndex)
                                    anchorIndex
                                } else {
                                    anchor.plannedSets.removeAll {
                                        loggedSetFor(anchor, it) == null
                                    }
                                    anchorIndex + 1
                                }
                            }
                            null -> workout.exercises.size
                        }
                    workout.exercises.addAll(insertionIndex, added)
                    focusId = added.first().id
                    undo = {
                        anchor
                            ?.plannedSets
                            ?.takeIf { replacing }
                            ?.apply {
                                clear()
                                addAll(originalPlans)
                            }
                        workout.exercises.removeAll { item -> added.any { it.id == item.id } }
                        if (replacing && anchor != null && anchor !in workout.exercises) {
                            workout.exercises.add(
                                anchorIndex.coerceAtMost(workout.exercises.size),
                                anchor,
                            )
                        }
                    }
                }
                PickerTarget.ROUTINE -> {
                    val draft = routineDraft ?: return
                    val anchorIndex = draft.exercises.indexOfFirst { it.id == picker.anchorId }
                    if (picker.placement != null && anchorIndex < 0) return
                    val anchor = draft.exercises.getOrNull(anchorIndex)
                    val added = movements.map { movement ->
                        if (replacing && anchor != null)
                            anchor.copy(
                                exerciseId = movement.id,
                                note = "",
                                manualMeasurement = false,
                                pauseEverySecondSet =
                                    movement.asymmetric && anchor.pauseEverySecondSet,
                                plannedSets =
                                    replacementSets(
                                        anchor.plannedSets,
                                        exercise(anchor.exerciseId) ?: return,
                                        movement,
                                        repository.data.lastKnownSets(movement.id).orEmpty(),
                                    ),
                            )
                        else
                            RoutineExercise(
                                exerciseId = movement.id,
                                plannedSets =
                                    MutableList(3) {
                                        PlannedSet(holdSeconds = movement.minimumHoldSeconds)
                                    },
                            )
                    }
                    val insertionIndex =
                        when (picker.placement) {
                            ExercisePlacement.ABOVE -> anchorIndex
                            ExercisePlacement.BELOW -> anchorIndex + 1
                            ExercisePlacement.REPLACE -> {
                                draft.exercises.removeAt(anchorIndex)
                                anchorIndex
                            }
                            null -> draft.exercises.size
                        }
                    draft.exercises.addAll(insertionIndex, added)
                    focusId = added.first().id
                    undo = {
                        draft.exercises.removeAll { item -> added.any { it.id == item.id } }
                        if (replacing && anchor != null) {
                            draft.exercises.add(
                                anchorIndex.coerceAtMost(draft.exercises.size),
                                anchor,
                            )
                        }
                    }
                }
            }
            route =
                when (target) {
                    PickerTarget.WORKOUT -> Route.Root
                    PickerTarget.HISTORY ->
                        Route.WorkoutHistory(
                            picker.historyWorkoutId ?: return,
                            picker.historyReturnScrollY,
                            editing = true,
                        )
                    PickerTarget.ROUTINE ->
                        Route.RoutineEditor(
                            repository.data.routines.firstOrNull { it.id == routineDraft?.id }?.id
                        )
                }
            if (target != PickerTarget.HISTORY) repository.save()
            render(
                scrollToBottom = picker.placement == null,
                focusExerciseId = focusId.takeIf { picker.placement != null },
            )
            if (picker.placement != null) {
                exerciseEditUndo =
                    Snackbar.make(
                            content,
                            if (replacing) "Exercise replaced" else "Exercises added",
                            Snackbar.LENGTH_LONG,
                        )
                        .setAction("Undo") {
                            undo()
                            if (target != PickerTarget.HISTORY) repository.save()
                            render(picker.returnScrollY)
                        }
                        .addCallback(
                            object : Snackbar.Callback() {
                                override fun onShown(snackbar: Snackbar) {
                                    if (exerciseEditUndo === snackbar) {
                                        content.setPadding(
                                            0,
                                            0,
                                            0,
                                            snackbar.view.height + ui.dp(12),
                                        )
                                    }
                                }

                                override fun onDismissed(snackbar: Snackbar, event: Int) {
                                    if (exerciseEditUndo === snackbar) {
                                        content.setPadding(0, 0, 0, 0)
                                        exerciseEditUndo = null
                                    }
                                }
                            }
                        )
                        .also { it.show() }
            }
        }
        val addSelected =
            ui.actionButton("Add selected") {
                    addExercises(picker.selectedIds.mapNotNull(::exercise))
                }
                .apply {
                    contentDescription = "Add selected exercises"
                    val states = arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf())
                    backgroundTintList =
                        ColorStateList(
                            states,
                            intArrayOf(
                                ui.color(R.color.charcoal_surface_high),
                                ui.color(R.color.coral),
                            ),
                        )
                    setTextColor(
                        ColorStateList(
                            states,
                            intArrayOf(ui.color(R.color.sage), ui.color(R.color.white)),
                        )
                    )
                }
        fun updateSelection() {
            addSelected.isEnabled = picker.selectedIds.isNotEmpty()
            addSelected.text =
                if (picker.selectedIds.isEmpty()) "Add selected"
                else "Add selected (${picker.selectedIds.size})"
        }
        updateSelection()
        val resultLabel = ui.metaText("").apply { contentDescription = "Exercise results" }
        val header =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(8))
                addView(
                    ui.titleText(
                            when (picker.placement) {
                                ExercisePlacement.REPLACE -> "Replace ${picker.anchorName}"
                                ExercisePlacement.ABOVE -> "Add above ${picker.anchorName}"
                                ExercisePlacement.BELOW -> "Add below ${picker.anchorName}"
                                null -> "Add exercise"
                            }
                        )
                        .apply {
                            textSize = 26f
                            setPadding(0, ui.dp(5), 0, 0)
                        }
                )
                addView(search)
                addView(resultLabel)
            }
        val results =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(12))
            }
        val resultsScroll =
            ScrollView(this).apply {
                isFillViewport = true
                addView(results)
            }
        fun updateResults(query: String) {
            results.removeAllViews()
            val allMatches = exercises.filter { exercise ->
                matchesSearchQuery(
                    query,
                    exercise.name,
                    exercise.primaryMuscle,
                    exercise.secondaryMuscles,
                    exerciseSearchTerms(exercise),
                )
            }
            val matches = allMatches.take(40)
            if (matches.isEmpty()) results.addView(ui.emptyText("No matching exercises."))
            resultLabel.text = "Showing 40 of ${allMatches.size}. Search to narrow the list."
            resultLabel.visibility = if (allMatches.size > matches.size) View.VISIBLE else View.GONE
            matches.forEach { movement ->
                results.addView(
                    ui.card {
                        addView(ui.exerciseHeading(movement, large = true))
                        addView(
                            ui.metaText(
                                listOf(
                                        exerciseTrackingLabel(movement),
                                        if (movement.asymmetric) "left / right" else null,
                                    )
                                    .filterNotNull()
                                    .joinToString(" · ")
                            )
                        )
                        addView(
                            ui.metaText(
                                "Primary: ${movement.primaryMuscle} · Secondary: ${movement.secondaryMuscles}"
                            )
                        )
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(0, ui.dp(8), 0, 0)
                                if (!replacing)
                                    addView(
                                        ui.largeCheckBox().apply {
                                            contentDescription = "Select ${movement.name}"
                                            isChecked = movement.id in picker.selectedIds
                                            setOnCheckedChangeListener { _, checked ->
                                                if (checked) picker.selectedIds.add(movement.id)
                                                else picker.selectedIds.remove(movement.id)
                                                updateSelection()
                                            }
                                        },
                                        LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply {
                                            marginEnd = ui.dp(12)
                                        },
                                    )
                                addView(
                                    ui.actionButton(if (replacing) "Replace" else "Add") {
                                            addExercises(listOf(movement))
                                        }
                                        .apply {
                                            contentDescription =
                                                "${if (replacing) "Replace with" else "Add"} ${movement.name}"
                                        },
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1f,
                                    ),
                                )
                            }
                        )
                    }
                )
            }
        }
        search.doAfterTextChanged {
            updateResults(it.toString())
            resultsScroll.scrollTo(0, 0)
        }
        footer.addView(
            addSelected,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        updateResults("")
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            addView(
                header,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                resultsScroll,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
            if (!replacing)
                addView(
                    footer,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
        }
    }

    private fun deleteUnsavedPicture(name: String) {
        if (repository.data.exercises.none { it.imageFile == name }) exercisePictures.delete(name)
    }

    private fun releasePicturePermission(uri: Uri) {
        runCatching {
            contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun importExercisePicture(uri: Uri) {
        val draft = exerciseDraft ?: return
        draft.putString("pendingPicture", uri.toString())
        val generation = ++pictureGeneration
        exerciseScreens.refreshPicturePreview()
        pictureWorker.execute {
            val result = runCatching { exercisePictures.importPicture(uri) }
            runOnUiThread {
                if (generation != pictureGeneration || exerciseDraft !== draft || isDestroyed) {
                    result.getOrNull()?.let(exercisePictures::delete)
                    return@runOnUiThread
                }
                draft.remove("pendingPicture")
                result
                    .onSuccess { name ->
                        draft.getString("imageFile")?.let(::deleteUnsavedPicture)
                        draft.putString("imageFile", name)
                    }
                    .onFailure {
                        Toast.makeText(this, "Could not open picture", Toast.LENGTH_SHORT).show()
                    }
                releasePicturePermission(uri)
                exerciseScreens.refreshPicturePreview()
            }
        }
    }

    private fun historyScreen(): View {
        val history = repository.data.history.sortedByDescending { it.completedAt }
        val pageCount =
            ((history.size + HISTORY_PAGE_SIZE - 1) / HISTORY_PAGE_SIZE).coerceAtLeast(1)
        historyPage = historyPage.coerceIn(0, pageCount - 1)
        val pageStart = historyPage * HISTORY_PAGE_SIZE
        val workouts =
            history.subList(pageStart, minOf(pageStart + HISTORY_PAGE_SIZE, history.size))
        updateHistoryPager(pageCount, history.isNotEmpty())
        val muscleMaps = LazyMuscleMaps(this, repository.data.biologicalGender)
        return ui.page("History", "Your training stays on this device.") { body ->
                if (history.isEmpty())
                    body.addView(ui.emptyText("Complete a workout and it will appear here."))
                val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                workouts.forEachIndexed { index, workout ->
                    body.addView(
                        ui.card {
                            addView(
                                LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    addView(
                                        ui.titleText(workout.name),
                                        LinearLayout.LayoutParams(
                                            0,
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            1f,
                                        ),
                                    )
                                    addView(
                                        historyWorkoutMenuButton(workout),
                                        LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply {
                                            marginStart = ui.dp(12)
                                        },
                                    )
                                }
                            )
                            addView(ui.metaText(dateFormat.format(Date(workout.startedAt))))
                            addView(
                                ui.metaText(
                                    "${workout.setCount()} sets · ${formatWeight(workout.totalVolume(repository.data.exercises))} kg volume · ${formatDuration(workout.durationMillis())}"
                                )
                            )
                            workout.bodyWeightKg?.let { addView(bodyWeightText(it)) }
                            addView(
                                muscleMaps.slot {
                                    exerciseMuscleWorkload(
                                        workout.exercises.map { it.exerciseId to it.sets.size }
                                    )
                                }
                            )
                            if (historyPage == 0 && index == 0 && workout.wasCompletedToday()) {
                                addView(
                                    ui.actionButton("Continue workout") {
                                            continueHistoryWorkout(workout)
                                        }
                                        .apply {
                                            contentDescription = "Continue ${workout.name}"
                                        },
                                    LinearLayout.LayoutParams(
                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                        )
                                        .apply {
                                            topMargin = ui.dp(12)
                                        },
                                )
                            }
                            addView(
                                LinearLayout(this@MainActivity).apply {
                                    orientation = LinearLayout.HORIZONTAL
                                    gravity = Gravity.CENTER_VERTICAL
                                    addView(
                                        ui.actionButton("View") {
                                                openWorkoutHistory(workout)
                                            }
                                            .apply {
                                                contentDescription = "View ${workout.name}"
                                            },
                                        LinearLayout.LayoutParams(
                                                0,
                                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                                1f,
                                            )
                                            .apply {
                                                marginEnd = ui.dp(8)
                                            },
                                    )
                                    addView(
                                        ui.actionButton("Edit", secondary = true) {
                                                openWorkoutHistory(workout, editing = true)
                                            }
                                            .apply {
                                                contentDescription = "Edit ${workout.name}"
                                            },
                                        LinearLayout.LayoutParams(
                                            0,
                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                            1f,
                                        ),
                                    )
                                },
                                LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    )
                                    .apply {
                                        topMargin = ui.dp(12)
                                    },
                            )
                        }
                    )
                }
            }
            .also { muscleMaps.attach(it as ScrollView) }
    }

    private fun historyWorkoutMenuButton(workout: WorkoutSession): MaterialButton =
        ui.smallButton("⋮") {}
            .apply {
                textSize = 20f
                contentDescription = "Options for ${workout.name}"
                setOnClickListener {
                    PopupMenu(this@MainActivity, this, Gravity.END).apply {
                        menu.add("Derive Routine").setOnMenuItemClickListener {
                            routineDraft = workout.toRoutine()
                            route = Route.RoutineEditor(null)
                            render()
                            true
                        }
                        val hevyWorkoutId = hevySettings.remoteWorkoutId(workout.id)
                        if (
                            workout.setCount() > 0 &&
                                (hevyWorkoutId == null || hevySettings.apiKey.isNotBlank())
                        ) {
                            menu
                                .add(
                                    if (hevyWorkoutId == null) "Export to Hevy"
                                    else "Update on Hevy"
                                )
                                .setOnMenuItemClickListener {
                                    exportWorkoutToHevy(workout, repository.data.exercises.toList())
                                    true
                                }
                        }
                        show()
                    }
                }
            }

    private fun updateHistoryPager(pageCount: Int, hasWorkouts: Boolean) {
        historyPager.removeAllViews()
        historyPager.visibility = if (hasWorkouts) View.VISIBLE else View.GONE
        if (!hasWorkouts) return
        historyPager.addView(
            ui.actionButton("Newer", secondary = true) {
                    historyPage--
                    render()
                }
                .apply {
                    contentDescription = "Newer workouts"
                    isEnabled = historyPage > 0
                },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = ui.dp(12)
            },
        )
        historyPager.addView(
            ui.metaText("${historyPage + 1} / $pageCount").apply {
                gravity = Gravity.CENTER
                contentDescription = "History page ${historyPage + 1} of $pageCount"
            }
        )
        historyPager.addView(
            ui.actionButton("Older", secondary = true) {
                    historyPage++
                    render()
                }
                .apply {
                    contentDescription = "Older workouts"
                    isEnabled = historyPage < pageCount - 1
                },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = ui.dp(12)
            },
        )
    }

    private fun openWorkoutHistory(workout: WorkoutSession, editing: Boolean = false) {
        historyEditDraft = if (editing) workout.editableCopy() else null
        route =
            Route.WorkoutHistory(
                workout.id,
                (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0,
                editing,
            )
        render()
    }

    private fun WorkoutSession.editableCopy(): WorkoutSession =
        copy(
            intervals = intervals.toMutableList(),
            exercises =
                exercises
                    .map { item ->
                        item.copy(
                            sets = item.sets.map { it.copy() }.toMutableList(),
                            plannedSets = item.plannedSets.map { it.copy() }.toMutableList(),
                        )
                    }
                    .toMutableList(),
        )

    private fun finishHistoryEdit(save: Boolean, activeRoute: Route.WorkoutHistory) {
        val draft = historyEditDraft?.takeIf { it.id == activeRoute.workoutId }
        if (save && draft != null) {
            val index = repository.data.history.indexOfFirst { it.id == draft.id }
            if (index >= 0) {
                val original = repository.data.history[index]
                repository.data.history[index] =
                    draft.copy(intervals = original.intervals.toMutableList())
                repository.saveHistory()
            }
        }
        historyEditDraft = null
        route = Route.Root
        render(activeRoute.returnScrollY)
    }

    private fun pastWorkoutScreen(workout: WorkoutSession, editing: Boolean): View =
        ui.page(
            workout.name,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(Date(workout.startedAt)),
            headerEnd =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        ui.titleText(formatDuration(workout.durationMillis())).apply {
                            contentDescription = "Workout duration"
                        }
                    )
                    if (!editing) {
                        addView(
                            historyWorkoutMenuButton(workout),
                            LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply {
                                marginStart = ui.dp(12)
                            },
                        )
                    }
                },
        ) { body ->
            body.addView(
                ui.metaText(
                    "${workout.setCount()} sets · ${formatWeight(workout.totalVolume(repository.data.exercises))} kg volume"
                )
            )
            workout.bodyWeightKg?.let { body.addView(bodyWeightText(it)) }
            if (editing) {
                body.addView(
                    ui.restInput(workout.defaultRestSeconds) { seconds ->
                        workout.defaultRestSeconds = seconds
                    }
                )
            } else body.addView(ui.metaText("Set Rest: ${workout.defaultRestSeconds}s"))
            val exerciseCards = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val reorder =
                ExerciseReorderController(exerciseCards) { from, to ->
                    workout.moveExercise(from, to)
                }
            val doneStateUpdaters = mutableMapOf<String, (Set<String>) -> Unit>()
            fun updateDoneStates() {
                val recordSetIds = repository.data.recordSetIds(workout)
                doneStateUpdaters.values.forEach { it(recordSetIds) }
            }
            workout.exercises.forEach { item ->
                exercise(item.exerciseId)?.let { movement ->
                    if (editing) {
                        lateinit var handle: View
                        val card =
                            workoutExerciseCard(
                                workout = workout,
                                item = item,
                                exercise = movement,
                                onRemove = {
                                    workout.exercises.remove(item)
                                    render((content.getChildAt(0) as? ScrollView)?.scrollY)
                                },
                                onReorderHandleReady = { handle = it },
                                onDoneStateUpdaterReady = { setId, update ->
                                    doneStateUpdaters[setId] = update
                                },
                                onSetsChanged = ::updateDoneStates,
                                editingHistory = true,
                            )
                        exerciseCards.addView(card)
                        card.tag = item.id
                        reorder.bind(handle, card)
                    } else {
                        exerciseCards.addView(
                            ui.card(horizontalPadding = 0) {
                                addView(
                                    ui.exerciseHeading(movement, large = true, showMuscle = false)
                                        .apply {
                                            contentDescription = "View ${movement.name} trend"
                                            setOnClickListener { openExerciseTrend(movement.id) }
                                        },
                                    ui.insetCardLayoutParams(),
                                )
                                if (item.note.isNotBlank()) {
                                    addView(
                                        ui.exerciseNote(item.note, movement, readOnly = true),
                                        ui.insetCardLayoutParams().apply {
                                            topMargin = ui.dp(8)
                                            bottomMargin = ui.dp(8)
                                        },
                                    )
                                }
                                addView(
                                    setViews.plannedSetHeader(
                                        movement,
                                        showActiveHold = movement.type.tracksDuration,
                                    )
                                )
                                fun addSet(
                                    plannedSet: PlannedSet,
                                    loggedSet: LoggedSet?,
                                    index: Int,
                                ) {
                                    val displaySet =
                                        plannedSet.copy(
                                            type = loggedSet?.type ?: plannedSet.type,
                                            weightKg = loggedSet?.weightKg ?: plannedSet.weightKg,
                                            reps = loggedSet?.reps ?: plannedSet.reps,
                                            actualHoldSeconds =
                                                loggedSet?.holdSeconds
                                                    ?: plannedSet.actualHoldSeconds,
                                            side = loggedSet?.side ?: plannedSet.side,
                                        )
                                    addView(
                                        setViews.plannedSetEditor(
                                            exercise = movement,
                                            plannedSet = displaySet,
                                            setIndex = index,
                                            completed = loggedSet != null,
                                            showActiveHold = movement.type.tracksDuration,
                                            readOnly = true,
                                        )
                                    )
                                }
                                item.plannedSets.forEachIndexed { index, set ->
                                    addSet(set, loggedSetFor(item, set), index)
                                }
                                addView(
                                    ui.metaText(
                                        "Set Rest: ${item.restSeconds(workout.defaultRestSeconds)}s"
                                    ),
                                    ui.insetCardLayoutParams(),
                                )
                            }
                        )
                    }
                }
            }
            if (editing) {
                updateDoneStates()
                body.addView(exerciseCards)
                body.addView(
                    ui.actionButton("Add exercise") {
                        route =
                            Route.ExercisePicker(
                                target = PickerTarget.HISTORY,
                                returnScrollY = (content.getChildAt(0) as? ScrollView)?.scrollY,
                                historyWorkoutId = workout.id,
                                historyReturnScrollY =
                                    (route as? Route.WorkoutHistory)?.returnScrollY ?: 0,
                            )
                        render()
                    }
                )
                body.addView(ui.divider())
            } else body.addView(exerciseCards)
            if (editing) {
                val activeRoute = route as? Route.WorkoutHistory ?: return@page
                body.addView(
                    ui.actionButton("Save changes") { finishHistoryEdit(true, activeRoute) }
                )
                body.addView(
                    ui.actionButton("Discard changes", secondary = true) {
                        finishHistoryEdit(false, activeRoute)
                    },
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = ui.dp(8) },
                )
            }
        }

    private fun exerciseMuscleWorkload(exerciseSets: List<Pair<String, Int>>): MuscleWorkload =
        MuscleWorkload(
            exerciseSets.mapNotNull { (exerciseId, sets) ->
                exercise(exerciseId)?.let { it to sets }
            }
        )

    // LEGACY: Routine and active-workout screens still construct their single pager eagerly.
    private fun exerciseMuscleMaps(exerciseSets: List<Pair<String, Int>>): View =
        MuscleTargetPager(
            this,
            exerciseMuscleWorkload(exerciseSets),
            repository.data.biologicalGender,
        )

    private fun openExerciseTrend(exerciseId: String) {
        route =
            Route.ExerciseTrend(
                exerciseId,
                route,
                (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0,
            )
        render()
    }

    private fun confirmationScreen(confirm: Route.Confirm): View {
        val (title, message, buttonLabel) =
            when (confirm.action) {
                ConfirmAction.FINISH_WORKOUT ->
                    Triple(
                        "Finish workout?",
                        "The workout and all logged sets will be saved to history.",
                        "Finish and save",
                    )
                ConfirmAction.DELETE_EXERCISE ->
                    Triple(
                        "Delete exercise?",
                        "The custom exercise will be removed from routines and the library.",
                        "Delete exercise",
                    )
                ConfirmAction.DELETE_ROUTINE ->
                    Triple(
                        "Delete routine?",
                        "The routine will be removed. Workout history is unaffected.",
                        "Delete routine",
                    )
            }
        return ui.page(title, message) { body ->
            val routineChanges =
                if (confirm.action == ConfirmAction.FINISH_WORKOUT)
                    repository.data.activeWorkout?.routineChanges(repository.data.routines)
                else null
            fun updateChoice(label: String): CheckBox =
                CheckBox(this).apply {
                    text = label
                    textSize = 14f
                    setTextColor(ui.color(R.color.cream))
                    minHeight = ui.dp(48)
                    body.addView(
                        this,
                        LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            .apply { bottomMargin = ui.dp(12) },
                    )
                }
            val updateSets =
                routineChanges
                    ?.takeIf { it.changed.isNotEmpty() }
                    ?.let {
                        updateChoice("Update sets, weights and notes in ${it.routine.name}")
                    }
            val includeAdded =
                routineChanges
                    ?.takeIf { it.added.isNotEmpty() }
                    ?.let {
                        updateChoice("Add new exercises to ${it.routine.name}")
                    }
            val exportToHevy =
                if (confirm.action == ConfirmAction.FINISH_WORKOUT) updateChoice("Export to Hevy")
                else null
            body.addView(
                ui.actionButton(buttonLabel) {
                    when (confirm.action) {
                        ConfirmAction.FINISH_WORKOUT ->
                            repository.data.activeWorkout?.let { workout ->
                                routineChanges?.apply(
                                    updateSets?.isChecked == true,
                                    includeAdded?.isChecked == true,
                                )
                                if (workout.setCount() == 0) {
                                    discardActiveWorkout()
                                } else {
                                    workout.finish()
                                    repository.data.recordExerciseRecency(workout)
                                    repository.data.recordWorkout(workout)
                                    repository.data.history.add(workout)
                                    repository.saveHistory()
                                    if (exportToHevy?.isChecked == true) {
                                        exportWorkoutToHevy(
                                            workout,
                                            repository.data.exercises.toList(),
                                        )
                                    }
                                    discardActiveWorkout()
                                }
                            }
                        ConfirmAction.DELETE_EXERCISE ->
                            confirm.targetId?.let { id ->
                                val picture =
                                    repository.data.exercises
                                        .firstOrNull { it.id == id && it.custom }
                                        ?.imageFile
                                repository.data.exercises.removeAll { it.id == id && it.custom }
                                repository.data.exerciseRecords.remove(id)
                                repository.data.exerciseLastSelectedAt.remove(id)
                                picture?.let(::deleteUnsavedPicture)
                                repository.data.routines.forEach { routine ->
                                    routine.exercises.removeAll { it.exerciseId == id }
                                }
                            }
                        ConfirmAction.DELETE_ROUTINE ->
                            repository.data.routines.removeAll { it.id == confirm.targetId }
                    }
                    repository.save()
                    route = Route.Root
                    render()
                },
                LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                    .apply {
                        bottomMargin = ui.dp(8)
                    },
            )
            body.addView(ui.actionButton("Cancel", secondary = true) { navigateBack() })
        }
    }

    private fun exportWorkoutToHevy(workout: WorkoutSession, catalog: List<Exercise>) {
        hevyWorker.execute {
            val result = runCatching { HevyExport(this, hevySettings).export(workout, catalog) }
            runOnUiThread {
                if (!isDestroyed) {
                    val message =
                        result.fold(
                            onSuccess = { it },
                            onFailure = { it.message ?: "Hevy export failed" },
                        )
                    AlertDialog.Builder(this)
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
    }

    private fun Routine.editableCopy(): Routine =
        copy(
            exercises =
                exercises
                    .map { item ->
                        item.copy(
                            plannedSets =
                                item.plannedSets
                                    .map { it.copy(actualHoldSeconds = null) }
                                    .toMutableList()
                        )
                    }
                    .toMutableList()
        )

    private fun openRoutineEditor(existing: Routine?) {
        routineDraft = existing?.editableCopy() ?: Routine(name = "")
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

    private fun cancelRoutineEditor(originalId: String?) {
        fun discard() {
            routineDraft = null
            route = Route.Root
            render()
        }
        val editing = originalId != null
        val original = repository.data.routines.firstOrNull { it.id == originalId }
        val needsConfirmation =
            if (editing) routineDraft != original?.editableCopy()
            else (routineDraft?.exercises?.size ?: 0) > 1
        if (needsConfirmation) {
            AlertDialog.Builder(this)
                .setTitle(if (editing) "Discard changes?" else "Discard routine?")
                .setMessage("Unsaved changes to this routine will be removed.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton(if (editing) "Discard changes" else "Discard routine") { _, _ ->
                    discard()
                }
                .show()
        } else {
            discard()
        }
    }

    private fun togetherMenuButton(
        label: String,
        bluetooth: () -> Unit,
        fileLabel: String,
        file: () -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background =
                GradientDrawable().apply {
                    setColor(ui.color(R.color.charcoal_surface_high))
                    cornerRadius = ui.dp(3).toFloat()
                }
            clipToOutline = true
            addView(
                ui.actionButton(label, secondary = true, action = bluetooth).apply {
                    cornerRadius = 0
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                View(this@MainActivity).apply {
                    setBackgroundColor(ui.color(R.color.cream))
                    alpha = 0.35f
                },
                LinearLayout.LayoutParams(ui.dp(1), ui.dp(30)),
            )
            addView(
                ui.actionButton("", secondary = true) {}
                    .apply {
                        contentDescription = "$label options"
                        cornerRadius = 0
                        minWidth = 0
                        minimumWidth = 0
                        minHeight = ui.dp(48)
                        setPadding(ui.dp(12), 0, ui.dp(12), 0)
                        setIconResource(R.drawable.ic_arrow_drop_down)
                        iconSize = ui.dp(24)
                        iconPadding = 0
                        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_END
                        iconTint = ColorStateList.valueOf(ui.color(R.color.cream))
                        setOnClickListener {
                            PopupMenu(this@MainActivity, this, Gravity.END).apply {
                                menu.add("Bluetooth").setOnMenuItemClickListener {
                                    bluetooth()
                                    true
                                }
                                menu.add(fileLabel).setOnMenuItemClickListener {
                                    file()
                                    true
                                }
                                show()
                            }
                        }
                    },
                LinearLayout.LayoutParams(ui.dp(52), ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

    private fun shareWorkoutFile(workout: WorkoutSession) {
        try {
            startActivity(
                Intent.createChooser(
                    WorkoutPlanFile.shareIntent(this, workout, repository.data.exercises),
                    "Share workout",
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, "Could not share workout file", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleWorkoutFileIntent(incoming: Intent) {
        if (incoming.action == Intent.ACTION_VIEW) incoming.data?.let(::importWorkoutFile)
    }

    private fun importWorkoutFile(uri: Uri) {
        if (uri.scheme !in listOf("content", "file")) {
            Toast.makeText(this, "Could not open workout file", Toast.LENGTH_LONG).show()
            return
        }
        val generation = ++workoutFileGeneration
        workoutFileWorker.execute {
            val plan = runCatching {
                requireNotNull(contentResolver.openInputStream(uri)).use(WorkoutPlanFile::decode)
            }
                .getOrNull()
            handler.post {
                if (isFinishing || isDestroyed || generation != workoutFileGeneration) return@post
                if (intent.action == Intent.ACTION_VIEW && intent.data == uri) {
                    setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                }
                if (plan == null) {
                    Toast.makeText(this, "Invalid or damaged .lofit file", Toast.LENGTH_LONG).show()
                } else {
                    pendingImportedPlan = plan
                    showPendingImportedPlan()
                }
            }
        }
    }

    private fun showPendingImportedPlan() {
        if (
            !repository.data.onboardingComplete ||
                !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        )
            return
        pendingImportedPlan?.let { plan ->
            pendingImportedPlan = null
            previewTogetherWorkout(plan)
        }
    }

    private fun withBluetooth(action: () -> Unit) {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this, "Bluetooth LE is not supported on this device", Toast.LENGTH_LONG)
                .show()
            return
        }
        val permissions =
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
            )
        if (
            permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        ) {
            pendingBluetoothAction = action
            bluetoothPermissions.launch(permissions)
            return
        }
        try {
            if (getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled != true) {
                pendingBluetoothAction = action
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else action()
        } catch (_: RuntimeException) {
            pendingBluetoothAction = null
            Toast.makeText(this, "Bluetooth is unavailable", Toast.LENGTH_LONG).show()
        }
    }

    private fun togetherDialog(title: String, body: View): AlertDialog {
        workoutTogetherDialog?.dismiss()
        return AlertDialog.Builder(this)
            .setTitle(title)
            .setView(body)
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                workoutTogetherDialog = dialog
                dialog.setOnDismissListener {
                    workoutBluetooth.stop()
                    if (workoutTogetherDialog === dialog) workoutTogetherDialog = null
                }
                dialog.show()
            }
    }

    private fun togetherBody(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8))
        }

    private fun shareTogetherWorkout(workout: WorkoutSession) = withBluetooth {
        val payload = runCatching {
            SharedWorkoutPlan.encode(workout, repository.data.exercises)
        }
            .getOrElse {
                Toast.makeText(this, it.message ?: "Could not share workout", Toast.LENGTH_LONG)
                    .show()
                return@withBluetooth
            }
        val status = ui.metaText("Starting Bluetooth broadcast…")
        val body =
            togetherBody().apply {
                addView(ui.titleText(workout.name))
                addView(status)
            }
        val dialog = togetherDialog("Share workout", body)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = "Stop sharing"
        workoutBluetooth.share(
            payload,
            onReady = { status.text = "Sharing nearby · Open Workout Together on the other phone" },
            error = {
                status.text = it
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text = "Close"
            },
        )
    }

    private fun scanTogetherWorkouts() {
        if (repository.data.activeWorkout != null) {
            Toast.makeText(this, "Finish or discard the active workout first", Toast.LENGTH_SHORT)
                .show()
            return
        }
        withBluetooth {
            val status = ui.metaText("Scanning for nearby workouts…")
            val hosts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val body =
                togetherBody().apply {
                    addView(status)
                    addView(
                        ScrollView(this@MainActivity).apply { addView(hosts) },
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(240)),
                    )
                }
            val dialog = togetherDialog("Workout Together", body)
            fun showError(message: String) {
                status.text = message
                hosts.removeAllViews()
                hosts.addView(
                    ui.actionButton("Try again") {
                        dialog.dismiss()
                        scanTogetherWorkouts()
                    }
                )
            }
            workoutBluetooth.scan(
                found = { device ->
                    val name =
                        try {
                            device.name
                        } catch (_: SecurityException) {
                            null
                        }
                    val label = name?.takeIf { it.isNotBlank() } ?: "Nearby workout"
                    hosts.addView(
                        ui.actionButton(
                            "$label · ${device.address.takeLast(5)}",
                            secondary = true,
                        ) {
                            hosts.removeAllViews()
                            status.text = "Receiving workout…"
                            workoutBluetooth.receive(
                                device,
                                received = { plan ->
                                    dialog.dismiss()
                                    previewTogetherWorkout(plan)
                                },
                                error = ::showError,
                            )
                        },
                        LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                            .apply {
                                topMargin = ui.dp(12)
                            },
                    )
                },
                error = ::showError,
            )
        }
    }

    private fun previewTogetherWorkout(plan: SharedWorkoutPlan) {
        val body =
            togetherBody().apply {
                addView(ui.titleText(plan.routine.name))
                addView(
                    ui.metaText(
                        "${plan.routine.exercises.size} exercises · ${plan.routine.defaultRestSeconds}s default rest"
                    )
                )
                addView(
                    ScrollView(this@MainActivity).apply {
                        addView(
                            ui.metaText(
                                plan.routine.exercises.joinToString("\n") { item ->
                                    "${plan.exercises.first { it.id == item.exerciseId }.name} · ${item.plannedSets.size} sets"
                                }
                            )
                        )
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(180)),
                )
            }
        val dialog = togetherDialog("Workout Together", body)
        body.addView(
            ui.actionButton("Start workout") {
                if (repository.data.activeWorkout == null) {
                    dialog.dismiss()
                    startWorkout(plan.startIn(repository.data))
                } else
                    Toast.makeText(
                            this,
                            "Finish or discard the active workout first",
                            Toast.LENGTH_SHORT,
                        )
                        .show()
            },
            LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                .apply {
                    topMargin = ui.dp(16)
                },
        )
    }

    private fun startRoutine(routine: Routine) {
        if (repository.data.activeWorkout != null) {
            Toast.makeText(this, "Finish or discard the active workout first", Toast.LENGTH_SHORT)
                .show()
            openActiveWorkout()
            return
        }
        startWorkout(repository.data.workoutFromRoutine(routine))
    }

    private fun startWorkout(workout: WorkoutSession) {
        if (repository.data.activeWorkout != null) {
            openActiveWorkout()
            return
        }
        repository.data.activeWorkout = workout
        repository.save()
        openActiveWorkout()
        queryWorkoutWeight(workout)
        ensureWorkoutNotification()
    }

    private fun setBodyMetricsEnabled(enabled: Boolean) {
        bodyMetricsReadGeneration++
        repository.data.bodyMetricsEnabled = enabled
        repository.save()
        val needsHeartRatePermission =
            ContextCompat.checkSelfPermission(this, HealthPermissions.READ_HEART_RATE) !=
                PackageManager.PERMISSION_GRANTED
        if (enabled && (!healthWeightReader.canReadWeight || needsHeartRatePermission)) {
            if (healthWeightReader.available) {
                healthPermissionPending = true
                healthPermission.launch(
                    arrayOf(
                        HealthPermissions.READ_WEIGHT,
                        HealthPermissions.READ_HEART_RATE,
                        HealthPermissions.READ_HEALTH_DATA_HISTORY,
                    )
                )
                return
            }
            repository.data.bodyMetricsEnabled = false
            repository.save()
            Toast.makeText(this, "Health Connect unavailable", Toast.LENGTH_SHORT).show()
        }
        repository.data.activeWorkout?.takeIf { it.bodyWeightKg == null }?.let(::queryWorkoutWeight)
        if (currentScreen == Screen.SETTINGS) render()
        ensureWorkoutNotification()
    }

    private fun queryWorkoutWeight(workout: WorkoutSession) {
        if (repository.data.bodyMetricsEnabled != true || !healthWeightReader.canReadWeight) return
        val generation = bodyMetricsReadGeneration
        healthWeightReader.readAt(workout.startedAt) { result ->
            if (
                isDestroyed ||
                    repository.data.bodyMetricsEnabled != true ||
                    generation != bodyMetricsReadGeneration
            )
                return@readAt
            val savedWorkout =
                repository.data.activeWorkout?.takeIf { it.id == workout.id }
                    ?: repository.data.history.firstOrNull { it.id == workout.id }
                    ?: return@readAt
            result
                .onSuccess { weight ->
                    if (weight != null && savedWorkout.bodyWeightKg == null) {
                        savedWorkout.bodyWeightKg = weight
                        if (savedWorkout === repository.data.activeWorkout) repository.save()
                        else repository.saveHistory()
                        if (currentScreen == Screen.HISTORY || route is Route.WorkoutHistory)
                            render()
                    }
                }
                .onFailure {
                    Toast.makeText(this, "Body weight unavailable", Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun bodyWeightText(weight: Double): TextView =
        ui.metaText(getString(R.string.body_weight, formatBodyWeight(weight))).apply {
            contentDescription = "Workout body weight"
        }

    private fun continueHistoryWorkout(workout: WorkoutSession) {
        if (repository.data.activeWorkout != null) {
            Toast.makeText(this, "Finish or discard the active workout first", Toast.LENGTH_SHORT)
                .show()
        } else {
            if (repository.data.resumeWorkout(workout.id) == null) {
                render()
                return
            }
            repository.save()
            repository.saveHistory()
            ensureWorkoutNotification()
        }
        openActiveWorkout()
    }

    private fun openActiveWorkout() {
        if (repository.data.activeWorkout == null) return
        openScreen(Screen.WORKOUT)
    }

    private fun openScreen(screen: Screen) {
        if (screen == Screen.HOME) {
            showHome()
            return
        }
        if (screen != currentScreen || route != Route.Root) {
            previousScreens.add(
                PreviousScreen(
                    currentScreen,
                    route,
                    (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0,
                    routineDraft,
                    historyPage,
                )
            )
        }
        currentScreen = screen
        if (screen == Screen.HISTORY) historyPage = 0
        route = Route.Root
        routineDraft = null
        render()
    }

    private fun showHome() {
        previousScreens.clear()
        currentScreen = Screen.HOME
        route = Route.Root
        routineDraft = null
        render()
    }

    private fun navigateBack() {
        if (route == Route.Root) {
            if (
                currentScreen == Screen.WORKOUT &&
                    repository.data.activeWorkout?.exercises?.isEmpty() == true
            ) {
                confirmDiscardWorkout()
                return
            }
            val previous = previousScreens.removeLastOrNull()
            if (previous == null) {
                showHome()
            } else {
                currentScreen = previous.screen
                route = previous.route
                routineDraft = previous.routineDraft
                historyPage = previous.historyPage
                render(previous.scrollY)
            }
            return
        }
        val previousRoute = route
        route =
            when (previousRoute) {
                is Route.ExerciseTrend -> previousRoute.returnRoute
                is Route.ExercisePicker ->
                    when (previousRoute.target) {
                        PickerTarget.ROUTINE ->
                            Route.RoutineEditor(
                                repository.data.routines
                                    .firstOrNull { it.id == routineDraft?.id }
                                    ?.id
                            )
                        PickerTarget.HISTORY ->
                            Route.WorkoutHistory(
                                previousRoute.historyWorkoutId ?: return,
                                previousRoute.historyReturnScrollY,
                                editing = true,
                            )
                        PickerTarget.WORKOUT -> Route.Root
                    }
                is Route.Confirm -> Route.Root
                is Route.RoutineEditor -> {
                    routineDraft = null
                    Route.Root
                }
                is Route.WorkoutHistory -> {
                    if (previousRoute.editing) historyEditDraft = null
                    Route.Root
                }
                else -> Route.Root
            }
        render(
            restoreScrollY =
                when (previousRoute) {
                    is Route.ExercisePicker -> previousRoute.returnScrollY
                    is Route.WorkoutHistory -> previousRoute.returnScrollY
                    is Route.ExerciseTrend -> previousRoute.returnScrollY
                    else -> null
                }
        )
    }

    private fun startRest(item: WorkoutExercise, workout: WorkoutSession) {
        val movement = exercise(item.exerciseId) ?: return
        val seconds = item.restSecondsAfterSet(workout.defaultRestSeconds, movement)
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
        val elapsed = (workout.durationMillis() / 1_000).toInt()
        timerText?.text = formatWorkoutTime(elapsed)
        updateRestBar()
        updateWorkoutNotification()
    }

    private fun createRestBar(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), ui.dp(10), ui.dp(10), ui.dp(10))
            setBackgroundColor(ui.color(R.color.coral_dark))
            contentDescription = "Rest timer overlay"
            visibility = View.GONE
            restTimeText =
                TextView(this@MainActivity).apply {
                    textSize = 18f
                    setTextColor(ui.color(R.color.white))
                    setTypeface(typeface, Typeface.BOLD)
                }
            addView(
                restTimeText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                ui.smallButton("−15", ::subtractRest),
                LinearLayout.LayoutParams(ui.dp(64), ui.dp(48)),
            )
            addView(
                ui.smallButton("+15", ::addRest),
                LinearLayout.LayoutParams(ui.dp(64), ui.dp(48)).apply { marginStart = ui.dp(6) },
            )
            addView(
                ui.smallButton("Skip", ::skipRest),
                LinearLayout.LayoutParams(ui.dp(72), ui.dp(48)).apply { marginStart = ui.dp(6) },
            )
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
            playCue()
            updateWorkoutNotification(force = true)
            return
        }
        restTimeText.text = "Rest ${formatSeconds(remaining)}"
        restBar.visibility = View.VISIBLE
    }

    private fun updateWorkoutScaleScan() {
        val workout = repository.data.activeWorkout
        workoutScaleReader.update(
            repository.data.craneScale,
            workoutForeground &&
                currentScreen == Screen.WORKOUT &&
                route == Route.Root &&
                workout?.exercises?.any(liveWorkoutMeasurement::isLive) == true,
        )
    }

    private fun onWorkoutScaleReading(atMillis: Long, kilograms: Double) {
        val change =
            liveWorkoutMeasurement.read(
                atMillis,
                kilograms,
                allowStart = activeHoldWorkoutExerciseId == null,
            )
        liveGraphs.forEach { it.showReading(kilograms) }
        if (change == null) return
        refreshLiveSetValues(change.pull.set)
        if (change.started) {
            restEndsAt = 0L
            updateRestBar()
        } else {
            if (change.completed) {
                repository.data.activeWorkout?.let { startRest(change.pull.item, it) }
            }
            render(restoreScrollY = (content.getChildAt(0) as? ScrollView)?.scrollY)
        }
        repository.save()
        updateWorkoutNotification(force = true)
    }

    private fun refreshLiveSetValues(set: PlannedSet) {
        updatingHoldTimer = true
        liveWeightViews[set.id]?.let { view ->
            val value = formatWeight(set.weightKg)
            if (view.text.toString() != value) view.setText(value)
        }
        liveTimeViews[set.id]?.let { view ->
            val value = (set.actualHoldSeconds ?: 0).toString()
            if (view.text.toString() != value) view.setText(value)
        }
        updatingHoldTimer = false
    }

    private fun isActiveHold(item: WorkoutExercise, set: PlannedSet): Boolean =
        activeHoldWorkoutExerciseId == item.id && activeHoldPlannedSetId == set.id

    private fun startInlineHold(
        item: WorkoutExercise,
        set: PlannedSet,
        amount: EditText,
        button: TextView,
        done: CheckBox,
    ) {
        liveWorkoutMeasurement.interrupt()?.let { refreshLiveSetValues(it.pull.set) }
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
        val workout =
            repository.data.activeWorkout
                ?: run {
                    clearActiveHold()
                    return
                }
        val item =
            workout.exercises.firstOrNull { it.id == itemId }
                ?: run {
                    clearActiveHold()
                    return
                }
        val plannedSet =
            item.plannedSets.firstOrNull { it.id == setId }
                ?: run {
                    clearActiveHold()
                    return
                }
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
        liveWorkoutMeasurement.reconcile()
        if (liveWorkoutMeasurement.tick(liveMeasurementNow())) playCue()
        liveWorkoutMeasurement.active?.let { pull ->
            refreshLiveSetValues(pull.set)
            updateWorkoutNotification()
        }
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
        if (
            !holdCuePlayed &&
                plannedSet != null &&
                movement != null &&
                elapsed >= plannedSet.holdSeconds
        ) {
            holdCuePlayed = true
            plannedSet.actualHoldSeconds = elapsed
            loggedSetFor(item, plannedSet)?.let { syncLoggedSet(it, plannedSet, movement) }
                // LEGACY: Failure sets still mark done at the goal while continuing to run.
                ?: item.sets.add(loggedSetFrom(plannedSet, movement))
            repository.save()
            completingHoldAutomatically = true
            holdDoneCheckBox?.isChecked = true
            completingHoldAutomatically = false
            playCue()
        }
        if (
            plannedSet != null &&
                elapsed >= plannedSet.holdSeconds &&
                plannedSet.type != SetType.FAILURE
        ) {
            stopActiveHold()
        }
    }

    private fun playCue() {
        getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.takeIf { it.hasVibrator() }
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180), -1))
        if (!repository.data.soundCuesEnabled) return
        handler.removeCallbacks(releaseCueAudioFocus)
        cueAudioManager.requestAudioFocus(cueAudioFocusRequest)
        cueSounds.play(completionCueId, 1f, 1f, 1, 0, 1f)
        handler.postDelayed(releaseCueAudioFocus, 1_200L)
    }

    private fun createWorkoutNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                    WORKOUT_NOTIFICATION_CHANNEL,
                    "Active workout",
                    NotificationManager.IMPORTANCE_LOW,
                )
                .apply {
                    description = "Current exercise, set, and rest timer"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
        )
    }

    private fun ensureWorkoutNotification() {
        if (repository.data.activeWorkout == null) return
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        ) {
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
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        )
            return

        val current =
            workout.exercises.firstNotNullOfOrNull { item ->
                val nextSet =
                    item.plannedSets.indexOfFirst { set ->
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
        val setText =
            when {
                item == null -> "Add an exercise to continue"
                item.plannedSets.isEmpty() -> "Add a set to continue"
                plannedSet != null -> "Set ${setIndex + 1} of ${item.plannedSets.size}"
                else -> "All ${workout.setCount()} sets done"
            }
        val restSeconds =
            ((restEndsAt - SystemClock.elapsedRealtime() + 999L) / 1_000L).toInt().coerceAtLeast(0)
        val livePull = liveWorkoutMeasurement.active
        val holdSeconds =
            if (livePull != null) {
                livePull.set.actualHoldSeconds ?: 0
            } else if (holdStartedAt > 0L && activeHoldWorkoutExerciseId != null) {
                ((SystemClock.elapsedRealtime() - holdStartedAt) / 1_000L).toInt().coerceAtLeast(0)
            } else 0
        val detail =
            when {
                restSeconds > 0 -> "$setText · Rest ${formatSeconds(restSeconds)}"
                activeHoldWorkoutExerciseId != null || livePull != null ->
                    "$setText · Hold ${formatSeconds(holdSeconds)}"
                else -> setText
            }
        val state = "$title|$detail"
        if (!force && state == lastWorkoutNotification) return
        lastWorkoutNotification = state

        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .setAction(ACTION_OPEN_WORKOUT)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(this, WORKOUT_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_workout_notification)
                .setContentTitle(title)
                .setContentText(detail)
                .setContentIntent(openApp)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setWhen(System.currentTimeMillis() - workout.durationMillis())
                .setUsesChronometer(restSeconds == 0)
        if (restSeconds > 0) {
            notification
                .addAction(0, "−15s", restAction(ACTION_SUBTRACT_REST, 1))
                .addAction(0, "+15s", restAction(ACTION_ADD_REST, 2))
                .addAction(0, "Skip", restAction(ACTION_SKIP_REST, 3))
        }
        manager.notify(WORKOUT_NOTIFICATION_ID, notification.build())
    }

    private fun restAction(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(action).setPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun exercise(id: String): Exercise? =
        repository.data.exercises.firstOrNull { it.id == id }

    private fun loggedSetFor(item: WorkoutExercise, plannedSet: PlannedSet): LoggedSet? =
        item.sets.firstOrNull { it.plannedSetId == plannedSet.id }

    private fun loggedSetFrom(
        plannedSet: PlannedSet,
        exercise: Exercise,
        completedAt: Long? = null,
    ) =
        LoggedSet(
            type = plannedSet.type,
            weightKg = plannedSet.weightKg.takeIf { exercise.weighted } ?: 0.0,
            reps = if (!exercise.type.tracksDuration) plannedSet.reps else 0,
            holdSeconds =
                if (exercise.type.tracksDuration) plannedSet.actualHoldSeconds ?: 0 else 0,
            side = plannedSet.side,
            completedAt = completedAt ?: System.currentTimeMillis(),
            plannedSetId = plannedSet.id,
        )

    private fun syncLoggedSet(loggedSet: LoggedSet, plannedSet: PlannedSet, exercise: Exercise) {
        loggedSet.type = plannedSet.type
        loggedSet.weightKg = plannedSet.weightKg.takeIf { exercise.weighted } ?: 0.0
        loggedSet.reps = if (!exercise.type.tracksDuration) plannedSet.reps else 0
        loggedSet.holdSeconds =
            if (exercise.type.tracksDuration) plannedSet.actualHoldSeconds ?: 0 else 0
        loggedSet.side = plannedSet.side
    }

    private fun formatSeconds(total: Int): String =
        String.format(Locale.getDefault(), "%d:%02d", total / 60, total % 60)

    private fun formatDuration(milliseconds: Long): String =
        formatWorkoutTime((milliseconds / 1_000).toInt())
}
