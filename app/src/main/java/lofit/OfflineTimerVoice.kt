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
    private val onTime: (String) -> Unit,
    private val onError: () -> Unit,
    private val createDetector: () -> VoiceKeywordDetector = { TimeKeywordDetector(context) },
) : AutoCloseable {
    private class Session(val target: String, val retries: Int = 0) {
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
    fun update(target: String?) {
        if (closed || target == session?.target) return
        // LEGACY: Hiding an ongoing workout stops capture but keeps its model warm.
        session?.stop()
        session = target?.let { Session(it) }
        session?.let { next ->
            modelRequested = true
            worker.execute { listen(next) }
        }
    }

    @SuppressLint("MissingPermission") // The activity checks and requests RECORD_AUDIO first.
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
            try {
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                if (!current.start(recorder)) return
                val pcm = ShortArray(320) // 20 ms reads; no utterance-end or network wait.
                val samples = FloatArray(pcm.size)
                while (current.active) {
                    val count = recorder.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                    if (!current.active) break
                    check(count > 0) { "Microphone read failed: $count" }
                    for (i in 0 until count) samples[i] = pcm[i] / 32768f
                    if (
                        model.accept(if (count == samples.size) samples else samples.copyOf(count))
                    ) {
                        main.post {
                            if (session === current && current.active) onTime(current.target)
                        }
                    }
                }
            } finally {
                current.stop()
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
