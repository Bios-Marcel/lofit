package lofit

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ScrollView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Description
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.hamcrest.TypeSafeMatcher
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveWorkoutMeasurementFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val selected = CraneScaleDevice("AA:BB:CC:DD:EE:01", "Scale")
    private var savedState: ByteArray? = null
    private var savedHistory: ByteArray? = null
    private var scaleTimeMillis = 0L
    private val movement =
        Exercise(
            "live-test",
            "Scale Pull",
            ExerciseType.TIMED,
            false,
            "Forearms",
            "—",
            weighted = true,
            liveMeasurement = true,
            custom = true,
        )

    @Before
    fun setup() {
        instrumentation.runOnMainSync { WorkoutVoiceService.stop() }
        instrumentation.waitForIdleSync()
        listOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            .forEach {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
            }
        savedState = runCatching { AtomicFile(context.repositoryStateFile).readFully() }.getOrNull()
        savedHistory =
            runCatching { AtomicFile(context.repositoryHistoryFile).readFully() }.getOrNull()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.mcpEnabled = false
            data.craneScale = selected
            data.exercises.add(movement)
            data.activeWorkout =
                WorkoutSession(
                    name = "Live workout",
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = movement.id,
                                plannedSets =
                                    mutableListOf(PlannedSet(weightKg = 50.0, holdSeconds = 1)),
                            )
                        ),
                )
            save()
        }
    }

    @After
    fun restore() {
        instrumentation.runOnMainSync { WorkoutVoiceService.stop() }
        instrumentation.waitForIdleSync()
        listOf(
                context.repositoryStateFile to savedState,
                context.repositoryHistoryFile to savedHistory,
            )
            .forEach { (path, bytes) ->
                val file = AtomicFile(path)
                if (bytes == null) file.delete()
                else file.finishWrite(file.startWrite().apply { write(bytes) })
            }
    }

    private class FakeScanner : CraneScaleScanner {
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

    private fun fakeScanner(activity: MainActivity): FakeScanner {
        scaleTimeMillis = SystemClock.elapsedRealtime()
        activity.liveMeasurementNow = { scaleTimeMillis }
        val delegate =
            MainActivity::class
                .java
                .getDeclaredField("workoutScaleReader\$delegate")
                .apply { isAccessible = true }
                .get(activity) as Lazy<*>
        val reader = delegate.value as WorkoutScaleReader
        reader.update(null, false)
        val scanner = FakeScanner()
        WorkoutScaleReader::class
            .java
            .getDeclaredField("scanner")
            .apply { isAccessible = true }
            .set(reader, scanner)
        reader.update(selected, true)
        return scanner
    }

    // AtomicFile reads through a new repository can delete an in-flight deferred write's .new file.
    private fun savedWorkoutExercise(): JSONObject =
        JSONObject(context.repositoryStateFile.readText())
            .getJSONObject("activeWorkout")
            .getJSONArray("exercises")
            .getJSONObject(0)

    @Test
    fun scaleCompletion_revealsNextRowAfterRender() {
        LofitRepository(context).apply {
            val item = data.activeWorkout!!.exercises.single()
            item.plannedSets.clear()
            item.plannedSets.addAll(List(20) { PlannedSet(holdSeconds = 1) })
            item.sets.addAll(item.plannedSets.take(9).map { loggedSetFrom(it, movement) })
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scanner: FakeScanner
            scenario.onActivity { scanner = fakeScanner(it) }
            onView(withContentDescription("Set 10 row")).check { view, _ ->
                val scroll = graphScroll(view)
                scroll.scrollTo(0, naturalTop(view, scroll) + view.height - scroll.height)
            }
            onView(withContentDescription("Set 10 row")).perform(waitForStableLayout())
            onView(withContentDescription("Set 11 row"))
                .check(matches(not(isCompletelyDisplayed())))
            scenario.onActivity {
                scanner.reading(selected, 20.0)
                scaleTimeMillis += 1_100
                scanner.reading(selected, 0.0)
            }
            onView(withContentDescription("Set 10 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 11 row"))
                .perform(waitUntilMatches(isCompletelyDisplayed()))
                .check(matches(isCompletelyDisplayed()))
        }
    }

    @Test
    fun voiceDoneCompletesActiveScaleSetAndKeepsEnteredWeight() {
        LofitRepository(context).apply {
            data.activeWorkout!!.voiceCommandsEnabled = true
            data.activeWorkout!!.exercises.single().plannedSets.single().holdSeconds = 60
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Scale Pull live weight graph"))
                .perform(scrollTo(), waitForStableLayout())
            lateinit var scanner: FakeScanner
            scenario.onActivity {
                scanner = fakeScanner(it)
                scanner.reading(selected, 20.0)
                scaleTimeMillis += 2_000
                scanner.reading(selected, 20.0)
                val target = it.visibleWorkoutVoiceTarget()!!
                assertNull(target.timer)
                assertNotNull(target.done)
                it.onWorkoutVoiceCommand(VoiceCommand.DONE, target)
            }
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            val saved = LofitRepository(context).data.activeWorkout!!.exercises.single()
            assertEquals(1, saved.sets.size)
            assertEquals(50.0, saved.plannedSets.single().weightKg, 0.0)
            assertEquals(50.0, saved.sets.single().weightKg, 0.0)
            onView(withContentDescription("Set 1 weight")).check(matches(withText("50")))
            onView(withContentDescription("Scale Pull live weight graph"))
                .perform(waitUntilMatches(withLastPullAverage(20.0)))
            assertEquals(2, saved.plannedSets.single().actualHoldSeconds)
        }
    }

    @Test
    fun graphStaysVisibleThroughManySetsAndReturnsToItsOriginalPosition() {
        seedLongExercises()
        ActivityScenario.launch(MainActivity::class.java).use {
            val graph = onView(withContentDescription("Scale Pull live weight graph"))
            graph.check { view, _ ->
                val scroll = graphScroll(view)
                scroll.scrollTo(0, naturalTop(view, scroll) + view.height * 2)
            }
            graph.perform(waitUntilMatches(graphAtViewportTop()))
            graph.check(matches(isCompletelyDisplayed()))
            var beforeSwipe = 0
            graph.check { view, _ -> beforeSwipe = graphScroll(view).scrollY }
            graph.perform(swipeUp())
            graph.perform(waitUntilMatches(graphAtViewportTop()))
            graph.check { view, _ -> assertTrue(graphScroll(view).scrollY > beforeSwipe) }
            graph.check { view, _ ->
                val scroll = graphScroll(view)
                scroll.scrollTo(0, naturalTop(view, scroll) + view.height * 3)
            }
            graph.perform(waitUntilMatches(graphAtViewportTop()))
            graph.check(matches(isCompletelyDisplayed()))
            graph.check { view, _ -> graphScroll(view).scrollTo(0, 0) }
            graph.perform(
                waitUntilMatches(
                    object : TypeSafeMatcher<View>() {
                        override fun describeTo(description: Description) {
                            description.appendText("graph back at its original position")
                        }

                        override fun matchesSafely(view: View) = view.translationY == 0f
                    }
                )
            )
        }
    }

    @Test
    fun graphLeavesWithItsExerciseAndNextGraphBecomesSticky() {
        seedLongExercises()
        ActivityScenario.launch(MainActivity::class.java).use {
            val first = onView(withContentDescription("Scale Pull live weight graph"))
            val next = onView(withContentDescription("Next Pull live weight graph"))
            first.check { view, _ ->
                val scroll = graphScroll(view)
                val card = view.parent as ViewGroup
                val cardBottom =
                    naturalTop(view, scroll) - view.top + card.height - card.paddingBottom
                scroll.scrollTo(0, cardBottom - view.height / 2)
            }
            first.perform(
                waitUntilMatches(
                    object : TypeSafeMatcher<View>() {
                        override fun describeTo(description: Description) {
                            description.appendText("graph pushed above viewport by exercise bottom")
                        }

                        override fun matchesSafely(view: View): Boolean {
                            val card = view.parent as ViewGroup
                            return view.translationY ==
                                (card.height - card.paddingBottom - view.bottom).toFloat() &&
                                windowTop(view) < windowTop(graphScroll(view))
                        }
                    }
                )
            )
            next.check { view, _ ->
                val scroll = graphScroll(view)
                scroll.scrollTo(0, naturalTop(view, scroll) + view.height * 2)
            }
            next.perform(waitUntilMatches(graphAtViewportTop()))
            next.check(matches(isCompletelyDisplayed()))
            first.check(matches(not(isDisplayed())))
            first.check { view, _ ->
                val scroll = graphScroll(view)
                scroll.scrollTo(0, naturalTop(view, scroll) + view.height)
            }
            first.perform(waitUntilMatches(graphAtViewportTop()))
            first.check(matches(isCompletelyDisplayed()))
        }
    }

    private fun seedLongExercises() {
        LofitRepository(context).apply {
            val next = movement.copy(id = "next-live-test", name = "Next Pull")
            data.exercises.add(next)
            data.activeWorkout!!.exercises.apply {
                single().plannedSets.apply {
                    clear()
                    repeat(40) { add(PlannedSet(holdSeconds = 1)) }
                }
                add(
                    WorkoutExercise(
                        exerciseId = next.id,
                        plannedSets = MutableList(40) { PlannedSet(holdSeconds = 1) },
                    )
                )
            }
            save()
        }
    }

    private fun graphScroll(view: View): ScrollView =
        generateSequence(view.parent) { it.parent }.filterIsInstance<ScrollView>().first()

    private fun windowTop(view: View): Int = IntArray(2).also(view::getLocationInWindow)[1]

    private fun naturalTop(view: View, scroll: ScrollView): Int =
        windowTop(view.parent as View) + view.top - windowTop(scroll) + scroll.scrollY

    private fun graphAtViewportTop() =
        object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("graph pinned at viewport top")
            }

            override fun matchesSafely(view: View): Boolean =
                view.translationY > 0 && windowTop(view) == windowTop(graphScroll(view))
        }

    @Test
    fun greenTargetLineFollowsCurrentSetAndStaysVisibleAboveReadings() {
        LofitRepository(context).apply {
            data.activeWorkout!!.exercises.single().plannedSets.apply {
                clear()
                add(PlannedSet(weightKg = 20.0, holdSeconds = 1))
                add(PlannedSet(weightKg = 40.0, holdSeconds = 1))
            }
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scanner: FakeScanner
            scenario.onActivity { scanner = fakeScanner(it) }
            fun targetLinePosition(): Float? {
                var position: Float? = null
                onView(withContentDescription("Scale Pull live weight graph")).check { view, _ ->
                    val bitmap =
                        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                    view.draw(Canvas(bitmap))
                    val green = context.getColor(R.color.target_green)
                    val row =
                        (0 until bitmap.height).firstOrNull { y ->
                            (1..3).all { index ->
                                bitmap.getPixel(bitmap.width * index / 4, y) == green
                            }
                        }
                    position = row?.toFloat()?.div(bitmap.height)
                    bitmap.recycle()
                }
                return position
            }
            // An empty graph must already fit the first target.
            assertTrue(requireNotNull(targetLinePosition()) < 0.5f)
            scenario.onActivity { scanner.reading(selected, 50.0) }
            assertTrue(requireNotNull(targetLinePosition()) > 0.5f)
            scenario.onActivity { scaleTimeMillis += 1_100 }
            scenario.onActivity { scanner.reading(selected, 0.0) }
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            // The next target is higher, while the same readings remain in the graph.
            assertTrue(requireNotNull(targetLinePosition()) < 0.5f)
            scenario.onActivity { scanner.reading(selected, 50.0) }
            scenario.onActivity { scaleTimeMillis += 1_100 }
            scenario.onActivity { scanner.reading(selected, 0.0) }
            onView(withContentDescription("Set 2 done")).check(matches(isChecked()))
            assertNull(targetLinePosition())
        }
    }

    @Test
    fun nonFailureScaleTimerStopsAtGoalAndKeepsEnteredWeight() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scanner: FakeScanner
            scenario.onActivity { scanner = fakeScanner(it) }
            onView(withContentDescription("Scale Pull live weight graph"))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withContentDescription("Start set 1 hold")).check(doesNotExist())
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(isEnabled()))
                .perform(
                    replaceText("7"),
                    waitUntil("edited active time saved") {
                        savedWorkoutExercise()
                            .getJSONArray("plannedSets")
                            .getJSONObject(0)
                            .optInt("actualHoldSeconds", -1) == 7
                    },
                )
            onView(withContentDescription("Set 1 weight"))
                .check(matches(isEnabled()))
                .perform(replaceText("20.5"))
                .perform(
                    waitUntil("edited target weight saved") {
                        savedWorkoutExercise()
                            .getJSONArray("plannedSets")
                            .getJSONObject(0)
                            .getDouble("weightKg") == 20.5
                    }
                )
            assertEquals(
                20.5,
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises
                    .single()
                    .plannedSets
                    .single()
                    .weightKg,
                0.0,
            )
            scenario.onActivity { scanner.reading(selected, 1.1) }
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(not(isEnabled())))
                .check(matches(withText("0")))
            onView(withContentDescription("Set 1 weight"))
                .check(matches(withText("20.5")))
                .perform(replaceText("21.75"))
            scenario.onActivity { scaleTimeMillis += 150 }
            scenario.onActivity {
                scanner.reading(selected, 15.113)
                scanner.reading(selected, 15.133)
            }
            scenario.onActivity { scaleTimeMillis += 1_100 }
            onView(withContentDescription("Set 1 Active time in seconds"))
                .perform(waitUntilMatches(withText("1")))
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(isEnabled()))
            scenario.onActivity { scaleTimeMillis += 2_000 }
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(withText("1")))
            scenario.onActivity {
                scanner.reading(CraneScaleDevice("AA:BB:CC:DD:EE:02", "Other"), 99.0)
                scanner.reading(selected, 2.0)
                scanner.reading(selected, 1.0)
            }
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 1 weight")).check(matches(withText("21.75")))
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            val saved =
                LofitRepository(context).data.activeWorkout!!.exercises.single().sets.single()
            assertEquals(21.75, saved.weightKg, 0.0)
            assertTrue(saved.holdSeconds >= 1)
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(isEnabled()))
                .perform(
                    replaceText("3"),
                    waitUntil("edited measured time saved") {
                        val item = savedWorkoutExercise()
                        val planned = item.getJSONArray("plannedSets").getJSONObject(0)
                        planned.optInt("actualHoldSeconds", -1) == 3 &&
                            planned.getInt("holdSeconds") == 1 &&
                            item.getJSONArray("sets").getJSONObject(0).getInt("holdSeconds") == 3
                    },
                )
            onView(withContentDescription("Scale Pull live weight graph")).check { view, _ ->
                assertEquals(
                    listOf(1.1, 15.113, 15.133, 2.0, 1.0),
                    (view as LiveMeasurementGraphView).series.samples.map { it.kilograms },
                )
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val headerBottom = (20 * view.resources.displayMetrics.density).toInt()
                assertTrue(
                    "Average label is drawn above the graph",
                    (0 until headerBottom).any { y ->
                        (view.width / 2 until view.width).any { x ->
                            bitmap.getPixel(x, y) ushr 24 != 0
                        }
                    },
                )
                bitmap.recycle()
            }
            onView(withContentDescription("Scale Pull live weight graph"))
                .perform(waitUntilMatches(withLastPullAverage(15.12)))
            onView(withContentDescription("Set 1 weight"))
                .perform(
                    replaceText("16.5"),
                    waitUntil("edited weight saved") {
                        LofitRepository(context)
                            .data
                            .activeWorkout!!
                            .exercises
                            .single()
                            .sets
                            .single()
                            .weightKg == 16.5
                    },
                )
            assertEquals(
                16.5,
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises
                    .single()
                    .sets
                    .single()
                    .weightKg,
                0.0,
            )
            scenario.recreate()
            onView(withContentDescription("Scale Pull live weight graph")).check { view, _ ->
                assertTrue((view as LiveMeasurementGraphView).series.samples.isEmpty())
                assertNull(view.series.lastPullAverageKg)
            }
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 1 weight")).check(matches(withText("16.5")))
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(isEnabled()))
                .check(matches(withText("3")))
        }
    }

    @Test
    fun failureScaleTimerKeepsRunningPastGoalAndStopsAtOneKilo() {
        LofitRepository(context).apply {
            data.activeWorkout!!.exercises.single().plannedSets.single().type = SetType.FAILURE
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scanner: FakeScanner
            scenario.onActivity {
                scanner = fakeScanner(it)
                scanner.reading(selected, 20.0)
                scaleTimeMillis += 2_000
                scanner.reading(selected, 20.0)
            }
            onView(withContentDescription("Set 1 Active time in seconds"))
                .perform(waitUntilMatches(withText("2")))
                .check(matches(not(isEnabled())))
            onView(withContentDescription("Set 1 done")).check(matches(not(isChecked())))
            onView(withContentDescription("Rest timer overlay")).check(matches(not(isDisplayed())))
            scenario.onActivity {
                scaleTimeMillis += 1_000
                scanner.reading(selected, 2.0)
            }
            onView(withContentDescription("Set 1 Active time in seconds"))
                .perform(waitUntilMatches(withText("3")))
            onView(withContentDescription("Set 1 done")).check(matches(not(isChecked())))
            scenario.onActivity { scanner.reading(selected, 1.0) }
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(isEnabled()))
            onView(withContentDescription("Set 1 weight")).check(matches(withText("50")))
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            val logged =
                LofitRepository(context).data.activeWorkout!!.exercises.single().sets.single()
            assertEquals(SetType.FAILURE, logged.type)
            assertEquals(3, logged.holdSeconds)
            assertEquals(50.0, logged.weightKg, 0.0)
            onView(withContentDescription("Scale Pull live weight graph"))
                .perform(waitUntilMatches(withLastPullAverage(20.0)))
        }
    }

    @Test
    fun shortPullCanBeMarkedDoneAndUndoneManually() {
        LofitRepository(context).apply {
            data.activeWorkout!!.exercises.single().plannedSets.single().holdSeconds = 30
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scanner: FakeScanner
            scenario.onActivity {
                scanner = fakeScanner(it)
                scanner.reading(selected, 12.0)
            }
            scenario.onActivity { scaleTimeMillis += 150 }
            scenario.onActivity { scanner.reading(selected, 0.0) }
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(isEnabled()))
                .perform(replaceText("8"))
            onView(withContentDescription("Set 1 done"))
                .check(matches(not(isChecked())))
                .check(matches(isEnabled()))
                .perform(scrollTo(), click())
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Rest timer overlay")).check(matches(isDisplayed()))
            val logged =
                LofitRepository(context).data.activeWorkout!!.exercises.single().sets.single()
            assertEquals(50.0, logged.weightKg, 0.0)
            assertEquals(8, logged.holdSeconds)
            scenario.recreate()
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(isEnabled()))
                .check(matches(withText("8")))
            onView(withContentDescription("Set 1 done"))
                .check(matches(isChecked()))
                .perform(scrollTo(), click())
            assertTrue(
                LofitRepository(context).data.activeWorkout!!.exercises.single().sets.isEmpty()
            )
        }
    }

    @Test
    fun manualCompletionDuringPullStopsTimerAndWaitsForReleaseBeforeNextSet() {
        LofitRepository(context).apply {
            data.activeWorkout!!.exercises.single().plannedSets.apply {
                clear()
                add(PlannedSet(weightKg = 50.0, holdSeconds = 30))
                add(PlannedSet(weightKg = 60.0, holdSeconds = 1))
            }
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scanner: FakeScanner
            scenario.onActivity {
                scanner = fakeScanner(it)
                scanner.reading(selected, 20.0)
            }
            scenario.onActivity { scaleTimeMillis += 1_100 }
            scenario.onActivity { scanner.reading(selected, 20.0) }
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(not(isEnabled())))
            onView(withContentDescription("Set 2 Active time in seconds"))
                .check(matches(isEnabled()))
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(isEnabled()))
            val first =
                LofitRepository(context).data.activeWorkout!!.exercises.single().sets.single()
            assertEquals(50.0, first.weightKg, 0.0)
            onView(withContentDescription("Scale Pull live weight graph"))
                .perform(waitUntilMatches(withLastPullAverage(20.0)))
            assertTrue(first.holdSeconds in 1 until 30)
            scenario.onActivity { scanner.reading(selected, 40.0) }
            scenario.onActivity { scaleTimeMillis += 1_100 }
            scenario.onActivity { scanner.reading(selected, 0.0) }
            onView(withContentDescription("Set 1 done")).check(matches(isChecked()))
            onView(withContentDescription("Set 2 done")).check(matches(not(isChecked())))
            onView(withContentDescription("Set 2 Active time in seconds"))
                .check(matches(withText("0")))
            assertEquals(
                listOf(first),
                LofitRepository(context).data.activeWorkout!!.exercises.single().sets,
            )
            scenario.onActivity { scanner.reading(selected, 30.0) }
            scenario.onActivity { scaleTimeMillis += 1_100 }
            scenario.onActivity { scanner.reading(selected, 0.0) }
            onView(withContentDescription("Set 2 done")).check(matches(isChecked()))
            val sets = LofitRepository(context).data.activeWorkout!!.exercises.single().sets
            assertEquals(2, sets.size)
            assertEquals(first, sets.first())
            assertEquals(60.0, sets.last().weightKg, 0.0)
            onView(withContentDescription("Scale Pull live weight graph"))
                .perform(waitUntilMatches(withLastPullAverage(30.0)))
        }
    }

    @Test
    fun shortPullAndScanFailureStayUnfinishedThenManualRestoresNormalTimer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scanner: FakeScanner
            scenario.onActivity {
                scanner = fakeScanner(it)
                scanner.reading(selected, 1.1)
            }
            scenario.onActivity { scaleTimeMillis += 150 }
            scenario.onActivity { scanner.reading(selected, 12.0) }
            scenario.onActivity { scaleTimeMillis += 150 }
            scenario.onActivity { scanner.reading(selected, 0.0) }
            onView(withContentDescription("Set 1 done")).check(matches(not(isChecked())))
            assertEquals(
                50.0,
                LofitRepository(context)
                    .data
                    .activeWorkout!!
                    .exercises
                    .single()
                    .plannedSets
                    .single()
                    .weightKg,
                0.0,
            )
            scenario.onActivity {
                scanner.reading(selected, 25.0)
                scanner.error("Scale unavailable")
            }
            assertTrue(
                LofitRepository(context).data.activeWorkout!!.exercises.single().sets.isEmpty()
            )
            onView(withContentDescription("Scale Pull scale status"))
                .check(matches(withText("Scale unavailable")))
            onView(withContentDescription("Set 1 Active time in seconds"))
                .check(matches(isEnabled()))
            openScaleOption(checked = true)
            onView(withText("Use crane scale")).perform(click())
            assertTrue(
                LofitRepository(context).data.activeWorkout!!.exercises.single().manualMeasurement
            )
            assertFalse(scanner.running)
            onView(withContentDescription("Scale Pull live weight graph")).check(doesNotExist())
            onView(withContentDescription("Set 1 weight")).check(matches(isEnabled()))
            onView(withContentDescription("Start set 1 hold")).perform(scrollTo(), click())
            onView(withContentDescription("Stop set 1 hold")).perform(click())
            scenario.recreate()
            onView(withContentDescription("Start set 1 hold")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun routineManualChoiceStartsManualAndWorkoutCanSwitchBackToLive() {
        LofitRepository(context).apply {
            data.activeWorkout = null
            data.routines.clear()
            data.routines.add(
                Routine(
                    name = "Scale routine",
                    exercises = mutableListOf(RoutineExercise(exerciseId = movement.id)),
                )
            )
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            navigate(scenario, R.string.nav_routines)
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withContentDescription("Scale Pull measurement mode")).check(doesNotExist())
            openScaleOption(checked = true)
            onView(withText("Use crane scale")).perform(click())
            openScaleOption(checked = false)
            androidx.test.espresso.Espresso.pressBack()
            onView(withText("Save routine")).perform(scrollTo(), click())
            onView(withText("Start")).perform(scrollTo(), click())
            onView(withContentDescription("Scale Pull live weight graph")).check(doesNotExist())
            openScaleOption(checked = false)
            onView(withText("Use crane scale")).perform(click())
            onView(withContentDescription("Scale Pull live weight graph"))
                .check(matches(isDisplayed()))
            assertFalse(
                LofitRepository(context).data.activeWorkout!!.exercises.single().manualMeasurement
            )
            assertTrue(
                LofitRepository(context).data.routines.single().exercises.single().manualMeasurement
            )
            scenario.recreate()
            openScaleOption(checked = true)
            androidx.test.espresso.Espresso.pressBack()
        }
    }

    @Test
    fun forgettingScaleHidesWorkoutGraphAndWorkoutAndRoutineScaleOptions() {
        LofitRepository(context).apply {
            data.routines.clear()
            data.routines.add(
                Routine(
                    name = "Scale routine",
                    exercises = mutableListOf(RoutineExercise(exerciseId = movement.id)),
                )
            )
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var scanner: FakeScanner
            scenario.onActivity { scanner = fakeScanner(it) }
            onView(withContentDescription("Scale Pull live weight graph"))
                .check(matches(isDisplayed()))
            openScaleOption(checked = true)
            androidx.test.espresso.Espresso.pressBack()
            navigate(scenario, R.string.nav_settings)
            onView(withText("Forget scale")).perform(scrollTo(), click())
            assertNull(LofitRepository(context).data.craneScale)
            assertFalse(scanner.running)
            navigate(scenario, R.string.nav_workout)
            onView(withText("Resume workout")).perform(scrollTo(), click())
            onView(withContentDescription("Scale Pull live weight graph")).check(doesNotExist())
            onView(withContentDescription("Set 1 weight")).check(matches(isEnabled()))
            onView(withContentDescription("Start set 1 hold"))
                .perform(scrollTo())
                .check(matches(isDisplayed()))
            onView(withContentDescription("Options for Scale Pull")).perform(scrollTo(), click())
            onView(withText("Use crane scale")).check(doesNotExist())
            androidx.test.espresso.Espresso.pressBack()
            scenario.recreate()
            onView(withContentDescription("Scale Pull live weight graph")).check(doesNotExist())
            onView(withContentDescription("Set 1 weight")).check(matches(isEnabled()))
            onView(withContentDescription("Options for Scale Pull")).perform(scrollTo(), click())
            onView(withText("Use crane scale")).check(doesNotExist())
            androidx.test.espresso.Espresso.pressBack()
            navigate(scenario, R.string.nav_routines)
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withContentDescription("Options for Scale Pull")).perform(scrollTo(), click())
            onView(withText("Use crane scale")).check(doesNotExist())
            androidx.test.espresso.Espresso.pressBack()
            val data = LofitRepository(context).data
            assertFalse(data.activeWorkout!!.exercises.single().manualMeasurement)
            assertFalse(data.routines.single().exercises.single().manualMeasurement)
        }
    }

    @Test
    fun liveMeasurementRestoresRequiredOptionsWhenEditingExercise() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            navigate(scenario, R.string.nav_exercises)
            onView(withText("Custom")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle")).perform(replaceText("Scale Pull"))
            onView(withText("Edit")).perform(scrollTo(), click())
            onView(withText("Use crane scale"))
                .check(matches(isChecked()))
                .check(matches(isEnabled()))
            onView(withText("Weighted")).perform(scrollTo(), click())
            onView(withText("Use crane scale"))
                .check(matches(not(isChecked())))
                .check(matches(isEnabled()))
            onView(withText("Use crane scale")).perform(click())
            onView(withText("Weighted")).check(matches(isChecked()))
            onView(withText("Use crane scale")).check(matches(isChecked()))
            onView(withText("Repetitions")).perform(scrollTo(), click())
            onView(withText("Use crane scale"))
                .check(matches(not(isChecked())))
                .check(matches(isEnabled()))
            onView(withText("Weighted")).perform(click())
            onView(withText("Use crane scale")).perform(click())
            onView(withText("Weighted")).check(matches(isChecked()))
            onView(withText("Timed")).check(matches(isChecked()))
            onView(withText("Use crane scale")).check(matches(isChecked()))
            scenario.recreate()
            onView(withText("Weighted")).check(matches(isChecked()))
            onView(withText("Timed")).check(matches(isChecked()))
            onView(withText("Use crane scale")).check(matches(isChecked()))
            onView(withText("Save exercise")).perform(scrollTo(), click())
            assertTrue(
                LofitRepository(context)
                    .data
                    .exercises
                    .first { it.id == movement.id }
                    .supportsLiveMeasurement
            )
        }
    }

    @Test
    fun newExerciseLiveMeasurementSelectsTimedAndWeightedAndPersists() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            navigate(scenario, R.string.nav_exercises)
            onView(withText("Create custom exercise")).perform(scrollTo(), click())
            onView(withHint("Exercise name")).perform(replaceText("Custom live pull"))
            onView(withHint("Search primary muscle")).perform(scrollTo(), replaceText("Forearms"))
            onView(withText("Repetitions")).check(matches(isChecked()))
            onView(withText("Weighted")).check(matches(not(isChecked())))
            onView(withText("Use crane scale")).perform(scrollTo(), click())
            onView(withText("Weighted")).check(matches(isChecked()))
            onView(withText("Timed")).check(matches(isChecked()))
            onView(withText("Use crane scale")).check(matches(isChecked()))
            onView(withText("Use crane scale")).perform(click())
            onView(withText("Weighted")).check(matches(isChecked()))
            onView(withText("Timed")).check(matches(isChecked()))
            onView(withText("Use crane scale")).perform(click())
            scenario.recreate()
            onView(withText("Use crane scale")).check(matches(isChecked()))
            onView(withText("Weighted")).check(matches(isChecked()))
            onView(withText("Timed")).check(matches(isChecked()))
            onView(withText("Save exercise")).perform(scrollTo(), click())
            assertTrue(
                LofitRepository(context)
                    .data
                    .exercises
                    .single { it.name == "Custom live pull" }
                    .supportsLiveMeasurement
            )
        }
    }

    private fun withLastPullAverage(kilograms: Double) =
        object : TypeSafeMatcher<View>() {
            override fun describeTo(description: Description) {
                description.appendText("last pull average ${formatWeight(kilograms)} kg")
            }

            override fun matchesSafely(view: View): Boolean =
                view.stateDescription?.contains(
                    "Last pull average ${formatWeight(kilograms)} kg"
                ) == true
        }

    private fun openScaleOption(checked: Boolean) {
        onView(withContentDescription("Options for Scale Pull")).perform(scrollTo(), click())
        onView(withText("Use crane scale"))
            .check(
                matches(
                    isDescendantOfA(
                        hasDescendant(
                            allOf(
                                isAssignableFrom(CheckBox::class.java),
                                if (checked) isChecked() else not(isChecked()),
                            )
                        )
                    )
                )
            )
    }

    private fun navigate(scenario: ActivityScenario<MainActivity>, label: Int) {
        scenario.onActivity {
            val root = it.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        onView(allOf(withText(label), isClickable())).perform(click())
    }
}
