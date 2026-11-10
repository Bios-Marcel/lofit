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

    private fun samples(name: String): ShortArray {
        val bytes =
            instrumentation.context.assets.open("voice-time/$name.pcm").use { it.readBytes() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return ShortArray(bytes.size / 2) { buffer.short }
    }

    private fun detect(model: TimeKeywordDetector, samples: ShortArray): List<Int> {
        val detections = mutableListOf<Int>()
        for (offset in samples.indices step 320) {
            val end = minOf(offset + 320, samples.size)
            if (model.accept(samples.copyOfRange(offset, end)) != null) detections.add(end)
        }
        return detections
    }

    @Test
    fun partialPcmBuffersIgnoreUnusedTail() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            val audio = samples("time-natural")
            val buffer = ShortArray(640) { Short.MAX_VALUE }
            var commands = 0
            for (offset in audio.indices step 193) {
                val count = minOf(193, audio.size - offset)
                audio.copyInto(buffer, 0, offset, offset + count)
                if (model.accept(buffer, count) != null) commands++
            }
            assertEquals(1, commands)
        }
    }

    @Test
    fun removedSetCommandsDoNotTriggerWorkoutControls() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            for (voice in listOf("lessac", "amy")) {
                for (phrase in
                    listOf(
                        "set-values",
                        "set-decimal",
                        "set-hundred",
                        "set-zero",
                        "set-single",
                        "set-seconds",
                        "set-four",
                        "set-four-pair",
                        "set-timed",
                        "set-for",
                        "set-incomplete",
                        "set-reversed",
                        "set-negative",
                    )) {
                    model.reset()
                    val audio = samples("$voice-$phrase")
                    val commands = mutableListOf<VoiceCommand>()
                    for (offset in audio.indices step 320) {
                        model
                            .accept(audio.copyOfRange(offset, minOf(offset + 320, audio.size)))
                            ?.let(commands::add)
                    }
                    assertTrue(
                        "Unexpected control for $voice-$phrase: $commands",
                        commands.isEmpty(),
                    )
                }
            }
        }
    }

    @Test
    fun recognizesSkipAndDoneOncePerUtteranceAlongsideTime() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            for (voice in listOf("lessac", "amy")) {
                model.reset()
                val audio =
                    samples("$voice-skip") + samples("$voice-done") + samples("time-natural")
                val commands = mutableListOf<VoiceCommand>()
                for (offset in audio.indices step 320) {
                    model
                        .accept(audio.copyOfRange(offset, minOf(offset + 320, audio.size)))
                        ?.let(commands::add)
                }
                assertEquals(
                    voice,
                    listOf(VoiceCommand.SKIP, VoiceCommand.DONE, VoiceCommand.TIME),
                    commands,
                )
            }
        }
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
                val lastSpeech = audio.indexOfLast { kotlin.math.abs(it.toInt()) > 163 }
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
            assertTrue(detect(model, ShortArray(16_000 * 10)).isEmpty())
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
    fun captureCanRestartAfterOnlyAFewAudioFrames() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            val audio = samples("time-natural")
            for (frames in listOf(1, 2, 4, 8, 16, 24)) {
                model.reset()
                repeat(frames) { model.accept(audio.copyOfRange(it * 320, (it + 1) * 320)) }
                model.reset()
                assertTrue(detect(model, ShortArray(16000)).isEmpty())
                assertEquals(1, detect(model, audio).size)
            }
        }
    }

    @Test
    fun continuousListeningRecognizesLaterCommandsAndResetDropsOldAudio() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            val command = samples("time-us")
            assertEquals(2, detect(model, command + ShortArray(16_000) + command).size)
            model.reset()
            model.accept(command.copyOfRange(0, 4_000))
            model.reset()
            assertTrue(detect(model, ShortArray(16_000)).isEmpty())
            assertEquals(1, detect(model, command).size)
        }
    }

    @Test
    fun quieterCommandsAndCommandsWithBackgroundHissStillWork() {
        TimeKeywordDetector(instrumentation.targetContext).use { model ->
            for (name in listOf("time-natural", "time-female")) {
                val original = samples(name)
                val random = Random(1)
                val quiet = ShortArray(original.size) { (original[it] * 0.2f).toInt().toShort() }
                val hiss =
                    ShortArray(original.size) {
                        ((original[it] / 32768f + random.nextGaussian().toFloat() * 0.005f) *
                                32768f)
                            .toInt()
                            .coerceIn(-32768, 32767)
                            .toShort()
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
                        detect(model, ShortArray(silence) + samples(name)).size,
                    )
                }
            }
        }
    }
}
