package lofit

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutBluetoothTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun grantNearbyDevices() {
        assumeTrue(context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
        listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
            .forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
        assumeTrue(
            context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        )
    }

    private fun fixture(): Pair<WorkoutSession, Exercise> {
        val movement =
            Exercise(
                "shared-hold",
                "Partner hold",
                ExerciseType.TIMED,
                true,
                "Core",
                "Shoulders",
                weighted = true,
                custom = true,
            )
        val session =
            WorkoutSession(
                name = "Bluetooth transfer test",
                defaultRestSeconds = 75,
                exercises =
                    MutableList(12) { index ->
                        WorkoutExercise(
                            exerciseId = movement.id,
                            restSecondsOverride = 30 + index,
                            plannedSets =
                                MutableList(8) {
                                    PlannedSet(
                                        weightKg = 2.5 + index,
                                        holdSeconds = 40 + it,
                                        side = Side.LEFT,
                                    )
                                },
                        )
                    },
            )
        return session to movement
    }

    @Test
    fun broadcastStartsAndStops() {
        val transport = WorkoutBluetooth(context)
        val ready = CountDownLatch(1)
        val failure = AtomicReference<String>()
        val (session, movement) = fixture()
        try {
            instrumentation.runOnMainSync {
                transport.share(
                    SharedWorkoutPlan.encode(session, listOf(movement)),
                    onReady = { ready.countDown() },
                    error = {
                        failure.set(it)
                        ready.countDown()
                    },
                )
            }
            assertTrue("Broadcast did not start", ready.await(15, TimeUnit.SECONDS))
            assertNull(failure.get(), failure.get())
        } finally {
            instrumentation.runOnMainSync { transport.stop() }
        }
    }
}
