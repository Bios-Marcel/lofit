package lofit

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

internal data class WorkoutVoiceTarget(
    val timer: VoiceTimerTarget? = null,
    val done: VoiceTimerTarget? = null,
    val restEndsAt: Long? = null,
)

/** One streaming, local keyword model. All instance calls belong to the audio worker. */
internal interface VoiceKeywordDetector : AutoCloseable {
    fun reset()

    /** Consume only [count] PCM samples; the caller reuses the buffer after this returns. */
    fun accept(samples: ShortArray, count: Int = samples.size): VoiceCommand?
}

internal class TimeKeywordDetector(context: Context) : VoiceKeywordDetector {
    // LEGACY: Keep number words as ignored competitors to reduce false controls from set phrases.
    private val grammar =
        context.assets.open("voice-time/grammar.json").bufferedReader().use { it.readText() }
    private val model = Model(prepareModel(context).absolutePath)
    private var recognizer: Recognizer? = null
    private val commands = VoiceCommandGate()

    override fun reset() {
        // A capture may stop before even one decoder frame exists. Reusing Vosk's partial
        // pipeline in that state can abort natively; retain the model, replace the decoder.
        recognizer?.close()
        recognizer = null
        commands.reset()
    }

    override fun accept(samples: ShortArray, count: Int): VoiceCommand? {
        val recognizer = recognizer ?: Recognizer(model, 16_000f, grammar).also { recognizer = it }
        val ended = recognizer.acceptWaveForm(samples, count)
        val result = JSONObject(if (ended) recognizer.result else recognizer.partialResult)
        val text = result.optString(if (ended) "text" else "partial")
        return commands.accept(text, ended)
    }

    override fun close() {
        recognizer?.close()
        recognizer = null
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
