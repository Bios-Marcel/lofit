package lofit

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

internal class WorkoutVoiceRuntime(val repository: LofitRepository) {
    val session =
        WorkoutVoiceSession({ repository.data }, repository::save, SystemClock::elapsedRealtime)
    var foreground = false
    var foregroundTarget: WorkoutVoiceTarget? = null
    var onCommand: ((VoiceCommand, WorkoutVoiceTarget) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var revision = 0
    var failed = false
}

/** Owns microphone capture and workout commands independently of the activity lifetime. */
class WorkoutVoiceService : Service() {
    companion object {
        private const val CHANNEL = "active-workout"
        private const val NOTIFICATION_ID = 1
        private const val COMPLETE = "lofit.voice.COMPLETE"
        private const val SKIP = "lofit.voice.SKIP"
        private const val ADD = "lofit.voice.ADD"
        private const val SUBTRACT = "lofit.voice.SUBTRACT"
        private var pending: WorkoutVoiceRuntime? = null
        private var stopRequested = false
        internal var current: WorkoutVoiceService? = null
            private set

        internal val currentRuntime: WorkoutVoiceRuntime?
            get() = current?.takeUnless { it.closed }?.runtime ?: pending

        internal fun start(context: Context, runtime: WorkoutVoiceRuntime) {
            if (current != null || pending != null || runtime.failed) return
            stopRequested = false
            pending = runtime
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, WorkoutVoiceService::class.java),
                )
            } catch (error: RuntimeException) {
                pending = null
                runtime.failed = true
                Log.w("WorkoutVoice", "Cannot start microphone service", error)
                Toast.makeText(
                        context,
                        "Open the workout to resume voice commands.",
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }

        internal fun stop() {
            // A queued start must first enter foreground, even if the workout was just ended.
            // Stopping it before Android delivers onStartCommand can crash the whole process.
            stopRequested = true
            current
                ?.takeIf { it.foregroundStarted }
                ?.let {
                    it.shutdown()
                    it.stopSelf()
                }
        }
    }

    internal lateinit var runtime: WorkoutVoiceRuntime
        private set

    private lateinit var voice: OfflineTimerVoice
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var sounds: SoundPool
    private lateinit var audioManager: AudioManager
    private lateinit var audioFocus: AudioFocusRequest
    private var completionSound = 0
    private var doneSound = 0
    private val handler = Handler(Looper.getMainLooper())
    private var closed = false
    private var foregroundStarted = false
    private var wakeRenewedAt = 0L
    private var notificationAt = 0L
    private var lastNotification: WorkoutVoiceNotificationState? = null
    private val releaseFocus = Runnable { audioManager.abandonAudioFocusRequest(audioFocus) }
    private val tick =
        object : Runnable {
            override fun run() {
                if (closed) return
                if (!runtime.foreground && runtime.session.tick()) {
                    runtime.revision++
                    cue(WorkoutVoiceSession.Cue.CHANGE)
                }
                refresh()
                if (!closed) handler.postDelayed(this, 250)
            }
        }

    override fun onCreate() {
        super.onCreate()
        runtime = pending ?: WorkoutVoiceRuntime(LofitRepository(applicationContext))
        pending = null
        current = this
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "lofit:workout-voice")
                .apply { setReferenceCounted(false) }
        val attributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        audioManager = getSystemService(AudioManager::class.java)
        audioFocus =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
        sounds = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attributes).build()
        completionSound = sounds.load(this, R.raw.completion_chime, 1)
        doneSound = sounds.load(this, R.raw.checkmark_chime, 1)
        voice =
            OfflineTimerVoice(
                applicationContext,
                ::onVoiceCommand,
                {
                    runtime.failed = true
                    Toast.makeText(
                            this,
                            "Voice commands unavailable. Reopen the workout to retry.",
                            Toast.LENGTH_LONG,
                        )
                        .show()
                    shutdown()
                    stopSelf()
                },
            )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Active workout", NotificationManager.IMPORTANCE_LOW)
                    .apply {
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    }
            )
    }

    private fun eligible(): Boolean =
        runtime.session.enabled &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (closed) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundStarted) {
            try {
                val state =
                    runtime.session.notificationState(
                        runtime.repository.data,
                        runtime.session.backgroundTarget(),
                        SystemClock.elapsedRealtime(),
                    )
                startForeground(
                    NOTIFICATION_ID,
                    notification(state),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
                foregroundStarted = true
                lastNotification = state
                notificationAt = SystemClock.elapsedRealtime()
            } catch (error: RuntimeException) {
                runtime.failed = true
                Log.w("WorkoutVoice", "Microphone foreground service unavailable", error)
                shutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            voice.prepare()
            handler.post(tick)
        }
        if (stopRequested || !eligible()) {
            shutdown()
            stopSelf()
            return START_NOT_STICKY
        }
        val target = runtime.session.backgroundTarget()
        when (intent?.action) {
            COMPLETE ->
                if (
                    target != null &&
                        target.done != null &&
                        target.done.setId == intent.getStringExtra("setId") &&
                        runtime.repository.data.activeWorkout?.id ==
                            intent.getStringExtra("workoutId")
                ) {
                    applyBackground(VoiceCommand.DONE, target)
                }
            SKIP,
            ADD,
            SUBTRACT ->
                if (target?.restEndsAt == intent.getLongExtra("restEndsAt", -1)) {
                    when (intent.action) {
                        SKIP -> applyBackground(VoiceCommand.SKIP, target!!)
                        ADD -> runtime.session.restEndsAt += 15_000
                        SUBTRACT ->
                            runtime.session.restEndsAt =
                                maxOf(
                                    SystemClock.elapsedRealtime(),
                                    runtime.session.restEndsAt - 15_000,
                                )
                    }
                    runtime.revision++
                    runtime.onChanged?.invoke()
                }
        }
        refresh()
        return START_NOT_STICKY
    }

    internal fun onVoiceCommand(command: VoiceCommand, target: WorkoutVoiceTarget) {
        if (closed || !eligible()) return
        if (runtime.foreground) runtime.onCommand?.invoke(command, target)
        else applyBackground(command, target)
        refresh()
    }

    private fun applyBackground(command: VoiceCommand, target: WorkoutVoiceTarget) {
        val result = runtime.session.apply(command, target) ?: return
        runtime.revision++
        cue(result)
        runtime.onChanged?.invoke()
    }

    internal fun refresh() {
        if (closed || !foregroundStarted) return
        if (!eligible()) {
            shutdown()
            stopSelf()
            return
        }
        val target =
            if (runtime.foreground) runtime.foregroundTarget else runtime.session.backgroundTarget()
        voice.update(target)
        val now = SystemClock.elapsedRealtime()
        if (target == null) {
            if (wakeLock.isHeld) wakeLock.release()
        } else if (!wakeLock.isHeld || now - wakeRenewedAt > 60_000) {
            // Renew a bounded CPU lock only while capturing; never keep the display awake.
            wakeLock.acquire(10 * 60_000L)
            wakeRenewedAt = now
        }
        if (now - notificationAt >= 500) {
            notificationAt = now
            val state =
                runtime.session.notificationState(
                    runtime.repository.data,
                    if (runtime.foreground) runtime.session.backgroundTarget() else target,
                    now,
                )
            if (state != lastNotification) {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(state))
                lastNotification = state
            }
        }
    }

    private fun notification(state: WorkoutVoiceNotificationState): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java)
                    .setAction("lofit.action.OPEN_WORKOUT")
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_workout_notification)
                .setContentTitle(state.title)
                .setContentText(state.detail)
                .setStyle(NotificationCompat.BigTextStyle().bigText(state.detail))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        fun action(name: String, label: String, code: Int) {
            val intent =
                Intent(this, WorkoutVoiceService::class.java)
                    .setAction(name)
                    .putExtra("workoutId", state.workoutId)
                    .putExtra("setId", state.setId)
                    .putExtra("restEndsAt", state.restEndsAt ?: -1)
            builder.addAction(
                0,
                label,
                PendingIntent.getService(
                    this,
                    code,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        if (state.restEndsAt != null) {
            action(SUBTRACT, "−15s", 11)
            action(ADD, "+15s", 12)
            action(SKIP, "Skip", 13)
        } else if (state.canComplete) {
            action(COMPLETE, "Complete Set", 14)
        }
        return builder.build()
    }

    private fun cue(kind: WorkoutVoiceSession.Cue) {
        getSystemService(VibratorManager::class.java)
            ?.defaultVibrator
            ?.takeIf { it.hasVibrator() }
            ?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180), -1))
        if (!runtime.repository.data.soundCuesEnabled) return
        handler.removeCallbacks(releaseFocus)
        audioManager.requestAudioFocus(audioFocus)
        sounds.play(
            if (kind == WorkoutVoiceSession.Cue.DONE) doneSound else completionSound,
            1f,
            1f,
            1,
            0,
            1f,
        )
        handler.postDelayed(releaseFocus, 1200)
    }

    private fun shutdown() {
        if (closed) return
        closed = true
        handler.removeCallbacksAndMessages(null)
        voice.close()
        if (wakeLock.isHeld) wakeLock.release()
        audioManager.abandonAudioFocusRequest(audioFocus)
        sounds.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        shutdown()
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
