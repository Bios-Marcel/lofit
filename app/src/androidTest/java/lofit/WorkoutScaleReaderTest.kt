package lofit

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutScaleReaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scale = CraneScaleDevice("AA:BB:CC:DD:EE:01", "Scale")

    private class Scanner : CraneScaleScanner {
        var running = false
        lateinit var reading: (CraneScaleDevice, Double) -> Unit
        lateinit var error: (String) -> Unit

        override fun start(
            address: String?,
            onReading: (CraneScaleDevice, Double) -> Unit,
            onError: (String) -> Unit,
        ) {
            reading = onReading
            error = onError
            running = true
        }

        override fun stop() {
            running = false
        }
    }

    @Test
    fun filtersSelectedScaleCanRetryAndIgnoresCallbacksAfterErrorOrStop() {
        instrumentation.runOnMainSync {
            val scanner = Scanner()
            val readings = mutableListOf<Double>()
            var interruptions = 0
            var status: String? = null
            val reader =
                WorkoutScaleReader(
                    scanner,
                    { it() },
                    readings::add,
                    { interruptions++ },
                    { status = it },
                )
            reader.update(scale, true)
            val oldReading = scanner.reading
            scanner.reading(CraneScaleDevice("AA:BB:CC:DD:EE:02", "Other"), 99.0)
            scanner.reading(scale, Double.NaN)
            scanner.reading(scale, 10.0)
            assertEquals(listOf(10.0), readings)
            assertNull(status)
            scanner.error("Bluetooth off")
            assertFalse(scanner.running)
            assertEquals("Bluetooth off", status)
            oldReading(scale, 100.0)
            reader.retry()
            assertTrue(scanner.running)
            scanner.reading(scale, 20.0)
            oldReading(scale, 100.0)
            assertEquals(listOf(10.0, 20.0), readings)
            val lastReading = scanner.reading
            reader.update(scale, false)
            lastReading(scale, 100.0)
            assertEquals(listOf(10.0, 20.0), readings)
            assertFalse(scanner.running)
            assertTrue(interruptions >= 3)
        }
    }

    @Test
    fun signalLossInterruptsWithoutInventingAReleaseAndNextReadingRestoresStatus() {
        val expired = CountDownLatch(1)
        val scanner = Scanner()
        lateinit var reader: WorkoutScaleReader
        var status: String? = null
        instrumentation.runOnMainSync {
            reader =
                WorkoutScaleReader(
                    scanner,
                    { it() },
                    {},
                    {},
                    {
                        status = it
                        if (it?.startsWith("No signal") == true) expired.countDown()
                    },
                    staleMillis = 30,
                )
            reader.update(scale, true)
            scanner.reading(scale, 10.0)
        }
        assertTrue(expired.await(3, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            assertTrue(status!!.startsWith("No signal"))
            assertTrue(scanner.running)
            scanner.reading(scale, 11.0)
            assertNull(status)
            reader.update(null, false)
        }
    }
}
