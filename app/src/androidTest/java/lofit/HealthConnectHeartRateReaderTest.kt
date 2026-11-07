package lofit

import android.Manifest
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.health.connect.HealthConnectException
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import android.health.connect.InsertRecordsResponse
import android.health.connect.RecordIdFilter
import android.health.connect.datatypes.HeartRateRecord
import android.health.connect.datatypes.Metadata
import android.os.OutcomeReceiver
import android.view.ViewGroup
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HealthConnectHeartRateReaderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(HealthConnectManager::class.java)

    @Before
    fun prepare() {
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = true
            save()
        }
        listOf(
                Manifest.permission.POST_NOTIFICATIONS,
                HealthPermissions.READ_WEIGHT,
                HealthPermissions.READ_HEART_RATE,
                HealthPermissions.READ_HEALTH_DATA_HISTORY,
                HealthPermissions.WRITE_HEART_RATE,
            )
            .forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
    }

    @Test
    fun finishingWorkoutSavesAverageAndShowsHeartInOverviewAndDetail() {
        val start = Instant.now().minusSeconds(120)
        seedActiveWorkout(start)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withRecords(listOf(record(start, listOf(0L to 100L, 30L to 140L)))) {
                finishWorkout()
                awaitAverage("Measured", 120.0)
                openHistory(scenario)
                onView(withText("♥ 120 bpm · average")).check(matches(isDisplayed()))
                onView(withContentDescription("View Measured")).perform(scrollTo(), click())
                onView(withText("♥ 120 bpm · average")).check(matches(isDisplayed()))
                scenario.recreate()
                assertEquals(
                    120.0,
                    LofitRepository(context).data.history.single().averageHeartRateBpm!!,
                    0.0,
                )
            }
        }
    }

    @Test
    fun hindsightSkipsEmptyWorkoutsFillsNewDataAndStopsAtSavedBoundary() {
        val start = Instant.now().minusSeconds(3_600)
        fun workout(name: String, offset: Long, average: Double? = null) =
            WorkoutSession(
                name = name,
                startedAt = start.plusSeconds(offset).toEpochMilli(),
                completedAt = start.plusSeconds(offset + 60).toEpochMilli(),
                averageHeartRateBpm = average,
            )
        LofitRepository(context).apply {
            data.history.addAll(
                listOf(
                    workout("Older", 0),
                    workout("Boundary", 120, 90.0),
                    workout("Delayed one", 240),
                    workout("Delayed two", 360),
                    workout("Still missing", 900),
                )
            )
            saveHistory()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openHistory(scenario)
            requestHistoryHeartRates()
            awaitRequestFinished()
            assertEquals(
                1,
                LofitRepository(context).data.history.count { it.averageHeartRateBpm != null },
            )
            withRecords(
                listOf(0L, 120L, 240L, 360L).map { offset ->
                    record(start.plusSeconds(offset), listOf(10L to 110L, 20L to 130L))
                }
            ) {
                requestHistoryHeartRates()
                awaitRequestFinished()
                val history = LofitRepository(context).data.history
                assertNull(history[0].averageHeartRateBpm)
                assertEquals(90.0, history[1].averageHeartRateBpm!!, 0.0)
                assertEquals(120.0, history[2].averageHeartRateBpm!!, 0.0)
                assertEquals(120.0, history[3].averageHeartRateBpm!!, 0.0)
                assertNull(history[4].averageHeartRateBpm)
            }
        }
    }

    @Test
    fun readerWeightsIntervalsBySampleCountExcludingBreaksAndBoundaries() {
        val start = Instant.now().minusSeconds(10_000)
        val intervals =
            listOf(
                WorkoutInterval(start.toEpochMilli(), start.plusSeconds(20).toEpochMilli()),
                WorkoutInterval(
                    start.plusSeconds(30).toEpochMilli(),
                    start.plusSeconds(40).toEpochMilli(),
                ),
            )
        ActivityScenario.launch(MainActivity::class.java).use {
            withRecords(
                listOf(
                    record(
                        start.minusSeconds(1),
                        listOf(
                            0L to 200L,
                            1L to 100L,
                            11L to 120L,
                            21L to 200L,
                            26L to 200L,
                            31L to 140L,
                            41L to 200L,
                        ),
                    )
                )
            ) {
                assertEquals(120.0, read(intervals).getOrThrow()!!, 0.0)
                assertNull(
                    read(
                            listOf(
                                WorkoutInterval(
                                    start.minusSeconds(600).toEpochMilli(),
                                    start.minusSeconds(300).toEpochMilli(),
                                )
                            )
                        )
                        .getOrThrow()
                )
                assertNull(
                    read(listOf(WorkoutInterval(start.toEpochMilli(), start.toEpochMilli())))
                        .getOrThrow()
                )
            }
        }
    }

    @Test
    fun sparseBatchedRecordsUseNearbySamplesAndPersistAnInterpolatedHistoryAverage() {
        val start = Instant.now().minusSeconds(3_600)
        val interval = WorkoutInterval(start.toEpochMilli(), start.plusSeconds(60).toEpochMilli())
        LofitRepository(context).apply {
            data.history.add(WorkoutSession(name = "Sparse", intervals = mutableListOf(interval)))
            saveHistory()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // The needed samples are in an old batch behind a full page of unrelated records.
            val records =
                listOf(
                    record(
                        start.minusSeconds(1_800),
                        listOf(0L to 200L, 1770L to 80L, 1980L to 160L),
                    )
                ) +
                    List(1000) { index ->
                        record(start.minusSeconds(300L + index), listOf(0L to 200L))
                    }
            withRecords(records) {
                val expected = 80.0 + 80.0 * 60.0 / 210.0
                assertEquals(expected, read(listOf(interval)).getOrThrow()!!, 0.000001)
                openHistory(scenario)
                requestHistoryHeartRates()
                awaitRequestFinished()
                assertEquals(
                    expected,
                    LofitRepository(context).data.history.single().averageHeartRateBpm!!,
                    0.000001,
                )
                onView(withText("♥ 103 bpm · average")).check(matches(isDisplayed()))
            }
        }
    }

    @Test
    fun nearbyReadingsAreUsedOnlyWhenTheWorkoutHasNoSamples() {
        val start = Instant.now().minusSeconds(3_600)
        val interval = WorkoutInterval(start.toEpochMilli(), start.plusSeconds(60).toEpochMilli())
        ActivityScenario.launch(MainActivity::class.java).use {
            withRecords(listOf(record(start.minusSeconds(121), listOf(0L to 60L, 301L to 130L)))) {
                assertEquals(130.0, read(listOf(interval)).getOrThrow()!!, 0.0)
            }
            withRecords(listOf(record(start.minusSeconds(120), listOf(0L to 90L, 301L to 200L)))) {
                assertEquals(90.0, read(listOf(interval)).getOrThrow()!!, 0.0)
            }
            withRecords(listOf(record(start.minusSeconds(121), listOf(0L to 60L, 302L to 200L)))) {
                assertNull(read(listOf(interval)).getOrThrow())
            }
            withRecords(
                listOf(record(start.minusSeconds(10), listOf(0L to 60L, 30L to 110L, 80L to 180L)))
            ) {
                assertEquals(110.0, read(listOf(interval)).getOrThrow()!!, 0.0)
            }
        }
    }

    @Test
    fun disabledIntegrationLeavesHistoryEmptyEvenWithHealthDataAndPermissions() {
        val start = Instant.now().minusSeconds(120)
        seedActiveWorkout(start)
        LofitRepository(context).apply {
            data.bodyMetricsEnabled = false
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            withRecords(listOf(record(start, listOf(10L to 120L)))) {
                finishWorkout()
                openHistory(scenario)
                onView(withContentDescription("History options")).check(doesNotExist())
                onView(withText("Request heart rates")).check(doesNotExist())
                val workout = LofitRepository(context).data.history.single()
                assertEquals(120.0, read(workout.intervals).getOrThrow()!!, 0.0)
                assertNull(LofitRepository(context).data.history.single().averageHeartRateBpm)
            }
        }
    }

    @Test
    fun readerWithoutPermissionReportsFailure() {
        val denied =
            object : ContextWrapper(context) {
                override fun checkSelfPermission(permission: String): Int =
                    PackageManager.PERMISSION_DENIED
            }
        var result: Result<Double?>? = null
        HealthConnectHeartRateReader(denied).readAverage(emptyList()) { result = it }
        assertTrue(result!!.isFailure)
    }

    private fun seedActiveWorkout(start: Instant) {
        LofitRepository(context).apply {
            data.activeWorkout =
                WorkoutSession(
                    name = "Measured",
                    startedAt = start.toEpochMilli(),
                    bodyWeightKg = 80.0,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                sets =
                                    mutableListOf(
                                        LoggedSet(type = SetType.NORMAL, weightKg = 60.0, reps = 8)
                                    ),
                            )
                        ),
                )
            save()
        }
    }

    private fun finishWorkout() {
        onView(withText("Finish workout")).perform(scrollTo(), click())
        onView(withText("Finish and save")).perform(scrollTo(), click())
    }

    private fun openHistory(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            (activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as DrawerLayout)
                .openDrawer(GravityCompat.START, false)
        }
        onView(withText(R.string.nav_history)).perform(click())
    }

    private fun awaitAverage(name: String, average: Double) {
        onView(isRoot())
            .perform(
                waitUntil("saved heart rate") {
                    LofitRepository(context)
                        .data
                        .history
                        .firstOrNull { it.name == name }
                        ?.averageHeartRateBpm == average
                }
            )
    }

    private fun requestHistoryHeartRates() {
        onView(withText("Request heart rates")).check(doesNotExist())
        onView(withContentDescription("History options")).perform(scrollTo(), click())
        onView(withText("Request heart rates")).perform(click())
    }

    private fun awaitRequestFinished() {
        onView(isRoot())
            .perform(
                waitUntil("heart rate request finished") { root ->
                    val views = ArrayList<android.view.View>()
                    root.findViewsWithText(
                        views,
                        "History options",
                        android.view.View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION,
                    )
                    views.singleOrNull()?.isEnabled == true
                }
            )
        onView(withContentDescription("History options")).check(matches(isEnabled()))
    }

    private fun record(start: Instant, samples: List<Pair<Long, Long>>) =
        HeartRateRecord.Builder(
                Metadata.Builder().build(),
                start,
                start.plusSeconds(samples.maxOf { it.first } + 1),
                samples.map { (offset, bpm) ->
                    HeartRateRecord.HeartRateSample(bpm, start.plusSeconds(offset))
                },
            )
            .build()

    private fun withRecords(records: List<HeartRateRecord>, action: () -> Unit) {
        val inserted = mutableListOf<HeartRateRecord>()
        try {
            records.chunked(500).forEach { batch ->
                inserted.addAll(
                    awaitHealthResult<InsertRecordsResponse> {
                            manager.insertRecords(batch, context.mainExecutor, it)
                        }
                        .records
                        .map { it as HeartRateRecord }
                )
            }
            action()
        } finally {
            inserted.chunked(500).forEach { batch ->
                awaitHealthResult<Void> { callback ->
                    manager.deleteRecords(
                        batch.map {
                            RecordIdFilter.fromId(HeartRateRecord::class.java, it.metadata.id)
                        },
                        context.mainExecutor,
                        callback,
                    )
                }
            }
        }
    }

    private fun read(intervals: List<WorkoutInterval>): Result<Double?> {
        val latch = CountDownLatch(1)
        var result: Result<Double?>? = null
        HealthConnectHeartRateReader(context).readAverage(intervals) {
            result = it
            latch.countDown()
        }
        assertTrue("Heart rate read timed out", latch.await(10, TimeUnit.SECONDS))
        return result!!
    }

    private fun <T> awaitHealthResult(
        action: (OutcomeReceiver<T, HealthConnectException>) -> Unit
    ): T {
        val latch = CountDownLatch(1)
        var value: T? = null
        var error: Exception? = null
        action(
            object : OutcomeReceiver<T, HealthConnectException> {
                override fun onResult(result: T) {
                    value = result
                    latch.countDown()
                }

                override fun onError(failure: HealthConnectException) {
                    error = failure
                    latch.countDown()
                }
            }
        )
        assertTrue("Health Connect timed out", latch.await(10, TimeUnit.SECONDS))
        error?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
