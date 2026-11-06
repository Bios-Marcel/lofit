package lofit

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/** One streaming, local keyword model. All instance calls belong to the audio worker. */
internal interface VoiceKeywordDetector : AutoCloseable {
    fun reset()

    fun accept(samples: FloatArray): Boolean
}

internal class TimeKeywordDetector(context: Context) : VoiceKeywordDetector {
    private val model = Model(prepareModel(context).absolutePath)
    private val recognizer =
        try {
            Recognizer(
                model,
                16_000f,
                context.assets.open("voice-time/grammar.json").bufferedReader().use {
                    it.readText()
                },
            )
        } catch (error: Exception) {
            model.close()
            throw error
        }
    private var triggered = false

    override fun reset() {
        recognizer.reset()
        triggered = false
    }

    override fun accept(samples: FloatArray): Boolean {
        val pcm =
            ShortArray(samples.size) {
                (samples[it] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            }
        val ended = recognizer.acceptWaveForm(pcm, pcm.size)
        val result = JSONObject(if (ended) recognizer.result else recognizer.partialResult)
        val text = result.optString(if (ended) "text" else "partial")
        // Partial results start promptly. Competitors and [unk] prevent forcing every sound to
        // "time".
        val detected = !triggered && text == "time"
        if (detected) triggered = true
        // A final result for the same utterance must not trigger a second toggle.
        if (ended) triggered = false
        return detected
    }

    override fun close() {
        recognizer.close()
        model.close()
    }

    private companion object {
        const val MODEL_NAME = "vosk-model-small-en-us-0.15"

        /** Extract once from the APK, never the network. A failed copy cannot become a model. */
        @Synchronized
        fun prepareModel(context: Context): File {
            val directory = File(context.noBackupFilesDir, MODEL_NAME)
            if (File(directory, ".ready").isFile) return directory
            val staging = File(context.noBackupFilesDir, "$MODEL_NAME.tmp")
            staging.deleteRecursively()
            check(staging.mkdirs())
            try {
                ZipInputStream(context.assets.open("voice-time/model.zip").buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        require(entry.name.startsWith("$MODEL_NAME/"))
                        val output = File(staging, entry.name.removePrefix("$MODEL_NAME/"))
                        require(
                            output.canonicalPath == staging.canonicalPath ||
                                output.canonicalPath.startsWith(
                                    staging.canonicalPath + File.separator
                                )
                        )
                        if (entry.isDirectory) output.mkdirs()
                        else {
                            output.parentFile!!.mkdirs()
                            output.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                    }
                }
                check(File(staging, "am/final.mdl").isFile)
                File(staging, ".ready").writeText(MODEL_NAME)
                directory.deleteRecursively()
                check(staging.renameTo(directory))
                return directory
            } finally {
                staging.deleteRecursively()
            }
        }
    }
}
