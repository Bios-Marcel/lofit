package lofit

import android.Manifest
import android.media.AudioManager
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutRecordFeedbackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val popupTextId = com.google.android.material.R.id.snackbar_text
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Before
    fun prepareWorkout() {
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.recordCelebrationsEnabled = true
            data.recordWorkout(
                WorkoutSession(
                    name = "Previous",
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                sets =
                                    mutableListOf(
                                        LoggedSet(type = SetType.NORMAL, weightKg = 80.0, reps = 5)
                                    ),
                            )
                        ),
                )
            )
            data.activeWorkout =
                WorkoutSession(
                    name = "Records",
                    defaultRestSeconds = 0,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                plannedSets =
                                    mutableListOf(
                                        PlannedSet(weightKg = 70.0, reps = 8),
                                        PlannedSet(weightKg = 70.0, reps = 8),
                                    ),
                            )
                        ),
                )
            save()
        }
    }

    @Test
    fun recordPopup_fadesAndDoesNotReplayForTiesUndoOrRestart() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTotals("0 sets done · 0 kg volume · 0 records")
            onView(withId(popupTextId)).check(doesNotExist())
            val completedAt = SystemClock.uptimeMillis()
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            assertTotals("1 set done · 560 kg volume · 1 record")
            onView(withId(popupTextId))
                .perform(waitUntilMatches(isDisplayed()))
                .perform(waitUntil("record fanfare playing") { audioManager.isMusicActive })
                .check(matches(withText("Bench press\n560 kg set volume")))
                .check { view, error ->
                    if (error != null) throw error
                    assertNotNull((view as TextView).compoundDrawables[0])
                }
            awaitPopupGone()
            assertTrue(
                "Record popup stays for six seconds",
                SystemClock.uptimeMillis() - completedAt >= 6_000,
            )
            onView(withContentDescription("Set 2 done")).perform(scrollTo(), click())
            assertTotals("2 sets done · 1120 kg volume · 1 record")
            onView(withId(popupTextId)).check(doesNotExist())
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            assertTotals("1 set done · 560 kg volume · 1 record")
            onView(withId(popupTextId)).check(doesNotExist())
            scenario.recreate()
            assertTotals("1 set done · 560 kg volume · 1 record")
            onView(withId(popupTextId)).check(doesNotExist())
            onView(withContentDescription("Set 2 done")).perform(scrollTo(), click())
            assertTotals("0 sets done · 0 kg volume · 0 records")
            onView(withContentDescription("Set 2 done")).perform(scrollTo(), click())
            assertTotals("1 set done · 560 kg volume · 1 record")
            onView(withId(popupTextId)).check(doesNotExist())
            onView(withContentDescription("Options for Bench press")).perform(scrollTo(), click())
            onView(withText("Remove exercise")).perform(click())
            assertTotals("0 sets done · 0 kg volume · 0 records")
        }
    }

    @Test
    fun editedCompletedSet_announcesBothWeightAndVolumeRecords() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            awaitPopupGone()
            onView(withContentDescription("Set 1 weight")).perform(scrollTo(), replaceText("90"))
            assertTotals("1 set done · 720 kg volume · 2 records")
            onView(withId(popupTextId))
                .perform(waitUntilMatches(isDisplayed()))
                .check(matches(withText("Bench press\n90 kg weight\n720 kg set volume")))
            onView(withContentDescription("Dismiss record popup"))
                .check(matches(isDisplayed()))
                .perform(click())
            awaitPopupGone()
            onView(withContentDescription("Workout totals")).perform(scrollTo()).check { view, error
                ->
                if (error != null) throw error
                val text = view as TextView
                assertEquals(1, text.lineCount)
                assertEquals(text.text.length, text.layout.getLineEnd(0))
                assertTrue(
                    text.layout.getLineWidth(0) <= text.width - text.paddingLeft - text.paddingRight
                )
            }
            scenario.recreate()
            assertTotals("1 set done · 720 kg volume · 2 records")
            onView(withId(popupTextId)).check(doesNotExist())
        }
    }

    @Test
    fun recordPopup_usesCelebrationSettingIndependentlyOfTimerSound() {
        LofitRepository(context).apply {
            data.soundCuesEnabled = false
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            onView(withId(popupTextId))
                .perform(waitUntilMatches(isDisplayed()))
                .perform(waitUntil("record fanfare playing") { audioManager.isMusicActive })
            onView(withContentDescription("Dismiss record popup")).perform(click())
            awaitPopupGone()
            assertTotals("1 set done · 560 kg volume · 1 record")
        }
    }

    @Test
    fun disabledCelebrations_hidePopupAndSoundWhileRecordsStillCount() {
        LofitRepository(context).apply {
            data.recordCelebrationsEnabled = false
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("Set 1 done")).perform(scrollTo(), click())
            assertTotals("1 set done · 560 kg volume · 1 record")
            onView(withId(popupTextId)).check(doesNotExist())
            assertFalse("Disabled celebrations remain silent", audioManager.isMusicActive)
            scenario.recreate()
            assertTotals("1 set done · 560 kg volume · 1 record")
            onView(withId(popupTextId)).check(doesNotExist())
        }
    }

    private fun assertTotals(text: String) {
        onView(withContentDescription("Workout totals")).check(matches(withText(text)))
    }

    private fun awaitPopupGone() {
        onView(isRoot())
            .perform(
                waitUntil("record popup to fade", timeoutMillis = 10_000) { root ->
                    root.findViewById<View>(popupTextId)?.isShown != true
                }
            )
        onView(withId(popupTextId)).check(doesNotExist())
    }
}
