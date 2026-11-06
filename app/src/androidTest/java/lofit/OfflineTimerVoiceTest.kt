package lofit

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineTimerVoiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class Detector : VoiceKeywordDetector {
        val closed = CountDownLatch(1)
        val audio = CountDownLatch(1)
        val closes = AtomicInteger()

        override fun reset() = Unit

        override fun accept(samples: FloatArray): Boolean {
            audio.countDown()
            return false
        }

        override fun close() {
            closes.incrementAndGet()
            closed.countDown()
        }
    }

    private fun await(latch: CountDownLatch) = assertTrue(latch.await(8, TimeUnit.SECONDS))

    @Test
    fun unloadClosesOnceAndNextWorkoutCreatesFreshModel() {
        val models = LinkedBlockingQueue<Detector>()
        val voice = OfflineTimerVoice(context, {}, {}, { Detector().also(models::add) })
        try {
            instrumentation.runOnMainSync { voice.prepare() }
            val first = models.poll(8, TimeUnit.SECONDS)!!
            instrumentation.runOnMainSync {
                voice.unload()
                voice.unload()
            }
            await(first.closed)
            assertEquals(1, first.closes.get())
            instrumentation.runOnMainSync { voice.prepare() }
            val next = models.poll(8, TimeUnit.SECONDS)!!
            assertNotSame(first, next)
            instrumentation.runOnMainSync { voice.close() }
            await(next.closed)
            assertEquals(1, next.closes.get())
        } finally {
            instrumentation.runOnMainSync { voice.close() }
        }
    }

    @Test
    fun endingWorkoutDuringLoadReleasesOldModelBeforeLoadingNext() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val creates = AtomicInteger()
        val models = LinkedBlockingQueue<Detector>()
        val voice =
            OfflineTimerVoice(
                context,
                {},
                {},
                {
                    if (creates.incrementAndGet() == 1) {
                        entered.countDown()
                        check(release.await(8, TimeUnit.SECONDS))
                    }
                    Detector().also(models::add)
                },
            )
        try {
            instrumentation.runOnMainSync { voice.prepare() }
            await(entered)
            instrumentation.runOnMainSync {
                voice.unload()
                voice.prepare()
            }
            release.countDown()
            val first = models.poll(8, TimeUnit.SECONDS)!!
            val next = models.poll(8, TimeUnit.SECONDS)!!
            await(first.closed)
            assertEquals(0, next.closes.get())
            instrumentation.runOnMainSync { voice.close() }
            await(next.closed)
        } finally {
            release.countDown()
            instrumentation.runOnMainSync { voice.close() }
        }
    }

    @Test
    fun unloadStopsActiveMicrophoneBeforeClosingModel() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.RECORD_AUDIO,
        )
        val model = Detector()
        val errors = AtomicInteger()
        val voice = OfflineTimerVoice(context, {}, { errors.incrementAndGet() }, { model })
        try {
            instrumentation.runOnMainSync { voice.update("set") }
            await(model.audio)
            instrumentation.runOnMainSync { voice.unload() }
            await(model.closed)
            assertEquals(1, model.closes.get())
            assertEquals(0, errors.get())
        } finally {
            instrumentation.runOnMainSync { voice.close() }
        }
    }
}
