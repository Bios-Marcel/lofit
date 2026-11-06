package lofit

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimeKeywordDetectorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun samples(name: String): FloatArray {
        val bytes =
            instrumentation.context.assets.open("voice-time/$name.pcm").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 2) { buffer.short / 32768f }
    }

    private fun detect(model: TimeKeywordDetector, samples: FloatArray): List<Int> {
        val detections = mutableListOf<Int>()
        for (offset in samples.indices step 320) {
            val end = minOf(offset + 320, samples.size)
            if (model.accept(samples.copyOfRange(offset, end))) detections.add(end)
        }
        return detections
    }

    @Test
    fun bundledModelRecognizesTimeFromStreamingAudioWithoutAnExternalSpeechService() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            for (name in
                listOf(
                    "time-us",
                    "time-uk",
                    "time-natural",
                    "time-female",
                    "time-robotic-female",
                )) {
                model.reset()
                val audio = samples(name)
                val started = SystemClock.elapsedRealtime()
                val detections = detect(model, audio)
                assertEquals("$name detections", 1, detections.size)
                val lastSpeech = audio.indexOfLast { kotlin.math.abs(it) > 0.005f }
                val latencyMs = (detections.single() - lastSpeech) / 16
                assertTrue("$name detection lag: ${latencyMs}ms", latencyMs <= 640)
                Log.i(
                    "TimerVoiceTest",
                    "$name: detection lag=${latencyMs}ms, processing=${SystemClock.elapsedRealtime() - started}ms",
                )
            }
        }
    }

    @Test
    fun silenceAndOtherWordsDoNotStartTimer() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            assertTrue(detect(model, FloatArray(16_000 * 10)).isEmpty())
            assertTrue(detect(model, samples("other-words")).isEmpty())
            for (voice in listOf("lessac", "amy")) {
                for (word in
                    listOf("timer", "timed", "times", "tiny", "dime", "climb", "nine", "tie")) {
                    model.reset()
                    assertTrue(
                        "False start for $voice/$word",
                        detect(model, samples("$voice-$word")).isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun continuousListeningRecognizesLaterCommandsAndResetDropsOldAudio() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            val command = samples("time-us")
            assertEquals(2, detect(model, command + FloatArray(16_000) + command).size)
            model.reset()
            model.accept(command.copyOfRange(0, 4_000))
            model.reset()
            assertTrue(detect(model, FloatArray(16_000)).isEmpty())
            assertEquals(1, detect(model, command).size)
        }
    }

    @Test
    fun quieterCommandsAndCommandsWithBackgroundHissStillWork() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            for (name in listOf("time-natural", "time-female")) {
                val original = samples(name)
                val random = Random(1)
                val quiet = FloatArray(original.size) { original[it] * 0.2f }
                val hiss =
                    FloatArray(original.size) {
                        original[it] + random.nextGaussian().toFloat() * 0.005f
                    }
                for (audio in listOf(quiet, hiss)) {
                    model.reset()
                    assertEquals(name, 1, detect(model, audio).size)
                }
            }
        }
    }

    @Test
    fun commandsAreRecognizedAfterDifferentLengthsOfSilence() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            for (name in
                listOf(
                    "time-us",
                    "time-uk",
                    "time-natural",
                    "time-female",
                    "time-robotic-female",
                )) {
                for (silence in listOf(4_000, 8_000, 16_000, 32_000)) {
                    model.reset()
                    assertEquals(
                        "$name after $silence samples",
                        1,
                        detect(model, FloatArray(silence) + samples(name)).size,
                    )
                }
            }
        }
    }
}
