package lofit

import android.Manifest
import android.content.Intent
import android.app.Activity
import android.view.ViewGroup
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.InsertRecordsResponse
import android.health.connect.RecordIdFilter
import android.health.connect.datatypes.Metadata
import android.health.connect.datatypes.WeightRecord
import android.health.connect.datatypes.units.Mass
import android.os.OutcomeReceiver
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthConnectWeightReaderTest {
    @Test
    fun healthConnect_readsNearestWeightsAndWorkoutSavesSnapshot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("lofit-data", 0).edit().clear().commit()
        LofitRepository(context).apply { data.bodyMetricsEnabled = true; save() }
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, HealthPermissions.READ_WEIGHT)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, HealthPermissions.READ_HEART_RATE)
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, HealthPermissions.READ_HEALTH_DATA_HISTORY)
        val activity = instrumentation.startActivitySync(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, HealthPermissions.WRITE_WEIGHT)
        val manager = context.getSystemService(HealthConnectManager::class.java)
        val now = Instant.now().minusSeconds(60)
        val records = listOf(
            now.minusSeconds(240) to 70.0,
            now.minusSeconds(120) to 74.0,
            now to 80.0,
        ).map { (time, weight) ->
            WeightRecord.Builder(Metadata.Builder().build(), time, Mass.fromGrams(weight * 1000)).build()
        }
        // Only debug builds declare write access for instrumentation fixtures.
        val inserted = awaitHealthResult<InsertRecordsResponse> {
            manager.insertRecords(records, context.mainExecutor, it)
        }.records
        try {
            val reader = HealthConnectWeightReader(context)
            assertEquals(77.0, read(reader, now.minusSeconds(90).toEpochMilli())!!, 0.0)
            assertEquals(74.0, read(reader, now.minusSeconds(120).toEpochMilli())!!, 0.0)
            assertEquals(80.0, read(reader, System.currentTimeMillis())!!, 0.0)

            onView(withText(R.string.start_workout)).perform(click())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (LofitRepository(context).data.activeWorkout?.bodyWeightKg == null && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
            assertEquals(80.0, LofitRepository(context).data.activeWorkout!!.bodyWeightKg!!, 0.0)
            onView(withContentDescription("Workout body weight")).check(doesNotExist())

            openScreen(activity, R.string.nav_settings)
            onView(withContentDescription("Body metrics")).perform(click())
            assertEquals(false, LofitRepository(context).data.bodyMetricsEnabled)
            openScreen(activity, R.string.nav_workout)
            onView(withText("Finish workout")).perform(scrollTo(), click())
            onView(withText(R.string.start_workout)).perform(click())
            // Real weight data and OS permission remain available while app consent is off.
            assertEquals(80.0, read(reader, System.currentTimeMillis())!!, 0.0)
            instrumentation.waitForIdleSync()
            assertNull(LofitRepository(context).data.activeWorkout!!.bodyWeightKg)

            openScreen(activity, R.string.nav_settings)
            onView(withContentDescription("Body metrics")).perform(click())
            val enabledDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (LofitRepository(context).data.activeWorkout?.bodyWeightKg == null && System.nanoTime() < enabledDeadline) {
                Thread.sleep(50)
            }
            assertEquals(true, LofitRepository(context).data.bodyMetricsEnabled)
            assertEquals(80.0, LofitRepository(context).data.activeWorkout!!.bodyWeightKg!!, 0.0)
        } finally {
            try {
                awaitHealthResult<Void> { callback ->
                    manager.deleteRecords(inserted.map {
                        RecordIdFilter.fromId(WeightRecord::class.java, it.metadata.id)
                    }, context.mainExecutor, callback)
                }
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    private fun openScreen(activity: Activity, label: Int) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as DrawerLayout)
                .openDrawer(GravityCompat.START, false)
        }
        onView(withText(label)).perform(click())
    }

    private fun read(reader: HealthConnectWeightReader, time: Long): Double? {
        val latch = CountDownLatch(1)
        var result: Result<Double?>? = null
        reader.readAt(time) { result = it; latch.countDown() }
        assertTrue("Weight read timed out", latch.await(10, TimeUnit.SECONDS))
        return result!!.getOrThrow()
    }

    private fun <T> awaitHealthResult(
        action: (OutcomeReceiver<T, HealthConnectException>) -> Unit,
    ): T {
        val latch = CountDownLatch(1)
        var value: T? = null
        var error: Exception? = null
        action(object : OutcomeReceiver<T, HealthConnectException> {
            override fun onResult(result: T) { value = result; latch.countDown() }
            override fun onError(failure: HealthConnectException) { error = failure; latch.countDown() }
        })
        assertTrue("Health Connect timed out", latch.await(10, TimeUnit.SECONDS))
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
