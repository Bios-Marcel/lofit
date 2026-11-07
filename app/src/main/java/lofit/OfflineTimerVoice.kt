package lofit

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.Executors

/** Captures only while a target is eligible. Audio is neither saved nor sent anywhere. */
internal class OfflineTimerVoice(
    private val context: Context,
    private val onCommand: (VoiceCommand, WorkoutVoiceTarget) -> Unit,
    private val onError: () -> Unit,
    private val createDetector: () -> VoiceKeywordDetector = { TimeKeywordDetector(context) },
) : AutoCloseable {
    private class Session(@Volatile var target: WorkoutVoiceTarget, val retries: Int = 0) {
        @Volatile
        var active = true
            private set

        private var recorder: AudioRecord? = null

        @Synchronized
        fun start(record: AudioRecord): Boolean {
            if (!active) return false
            recorder = record
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            return true
        }

        @Synchronized
        fun stop() {
            active = false
            runCatching { recorder?.stop() }
        }

        @Synchronized
        fun release(record: AudioRecord) {
            recorder = null
            record.release()
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var detector: VoiceKeywordDetector? = null
    private var session: Session? = null
    private var closed = false
    private var preparing = false
    private var modelRequested = false

    /** Warm the bundled model while the workout opens, without opening the microphone. */
    fun prepare() {
        if (closed || preparing) return
        preparing = true
        modelRequested = true
        worker.execute {
            try {
                if (detector == null) detector = createDetector()
            } catch (error: Exception) {
                Log.w("TimerVoice", "Voice model preparation failed", error)
            } catch (error: LinkageError) {
                Log.w("TimerVoice", "Voice model preparation failed", error)
            }
        }
    }

    /** Main thread only. Replacing the target also invalidates queued recognition callbacks. */
    fun update(target: WorkoutVoiceTarget?) {
        if (closed || target == session?.target) return
        if (target != null && session?.active == true) {
            // Keep utterance deduplication when a command advances to the next set or rest.
            session!!.target = target
            return
        }
        // No eligible set: stop capture but keep the workout model warm.
        session?.stop()
        session = target?.let { Session(it) }
        session?.let { next ->
            modelRequested = true
            worker.execute { listen(next) }
        }
    }

    @SuppressLint(
        "MissingPermission"
    ) // The foreground service verifies RECORD_AUDIO before capture.
    private fun listen(current: Session) {
        if (!current.active) return
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val model = detector ?: createDetector().also { detector = it }
            if (!current.active) return
            model.reset()
            val minimum =
                AudioRecord.getMinBufferSize(
                    16_000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
            check(minimum > 0)
            val recorder =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    16_000,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minimum, 3_200),
                )
            val audioRoute = VoiceAudioRoute(context, recorder)
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                audioRoute.start()
                if (!current.start(recorder)) return
                val pcm = ShortArray(320) // 20 ms reads; no utterance-end or network wait.
                while (current.active) {
                    val target = current.target
                    val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (!current.active) break
                    check(count > 0) { "Microphone read failed: $count" }
                    val command = model.accept(pcm, count)
                    if (command != null) {
                        main.post {
                            if (session === current && current.active && current.target == target)
                                onCommand(command, target)
                        }
                    }
                }
            } finally {
                current.stop()
                audioRoute.close()
                current.release(recorder)
            }
        } catch (error: Exception) {
            reportError(current, error)
        } catch (error: LinkageError) {
            reportError(current, error)
        }
    }

    private fun reportError(current: Session, error: Throwable) {
        Log.w("TimerVoice", "Offline voice timer unavailable", error)
        main.post {
            if (!closed && session === current) {
                if (current.retries < 3) {
                    val retry = Session(current.target, current.retries + 1)
                    session = retry
                    main.postDelayed(
                        {
                            if (!closed && session === retry) worker.execute { listen(retry) }
                        },
                        500L shl current.retries,
                    )
                } else onError()
            }
        }
    }

    /** Release native memory on the audio worker, after any pending load or microphone read. */
    fun unload() {
        if (closed) return
        update(null)
        if (!modelRequested) return
        modelRequested = false
        preparing = false
        worker.execute {
            detector?.close()
            detector = null
        }
    }

    override fun close() {
        if (closed) return
        unload()
        closed = true
        worker.shutdown()
    }
}
