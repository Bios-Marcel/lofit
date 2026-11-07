package lofit

import android.Manifest
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarCardioTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val day = LocalDate.now().withDayOfMonth(10)
    private val cardio =
        Exercise(
            "custom-cardio",
            "Run",
            ExerciseType.TIMED,
            false,
            "Quadriceps",
            "",
            custom = true,
            cardio = true,
        )

    @Before
    fun prepare() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            data.exercises.add(cardio)
            save()
        }
    }

    private fun workout(
        name: String,
        date: LocalDate,
        hour: Int,
        vararg exerciseIds: String,
    ): WorkoutSession {
        val start = date.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return WorkoutSession(
            name = name,
            startedAt = start,
            completedAt = start + 60_000,
            exercises =
                exerciseIds
                    .map { id ->
                        WorkoutExercise(
                            exerciseId = id,
                            sets =
                                mutableListOf(
                                    LoggedSet(
                                        type = SetType.NORMAL,
                                        weightKg = 0.0,
                                        reps = if (id == cardio.id) 0 else 8,
                                        holdSeconds = if (id == cardio.id) 60 else 0,
                                    )
                                ),
                        )
                    }
                    .toMutableList(),
        )
    }

    private fun saveWorkouts(vararg workouts: WorkoutSession) {
        LofitRepository(context).apply {
            data.history.addAll(workouts)
            saveHistory()
        }
    }

    private fun checkCalendarIcon(description: String, cardio: Boolean): Int {
        var numberBaseline = 0
        onView(withContentDescription(description)).check(matches(isDisplayed())).check { view, _ ->
            val cell = view as ViewGroup
            val number =
                (0 until cell.childCount)
                    .map { cell.getChildAt(it) }
                    .filterIsInstance<TextView>()
                    .single()
            numberBaseline = number.top + number.baseline
            val icon =
                (0 until cell.childCount)
                    .map { cell.getChildAt(it) }
                    .filterIsInstance<ImageView>()
                    .singleOrNull()
            if (cardio) {
                assertNotNull(icon)
                assertNotNull(icon!!.drawable)
                assertTrue(
                    "Cardio icon must fit inside the calendar cell",
                    isCompletelyDisplayed().matches(icon),
                )
                val inset = (2 * context.resources.displayMetrics.density).toInt()
                assertEquals("Cardio icon sits at the left edge", inset, icon.left)
                assertEquals(
                    "Cardio icon sits at the bottom edge",
                    inset,
                    cell.height - icon.bottom,
                )
            } else assertNull(icon)
        }
        return numberBaseline
    }

    @Test
    fun calendarMarksCardioAndMixedLoggedWorkoutsAndKeepsNavigation() {
        saveWorkouts(
            workout("Cardio session", day, 9, cardio.id),
            workout("Strength session", day.plusDays(1), 9, "bench-press"),
            workout("Mixed session", day.plusDays(2), 9, cardio.id, "bench-press"),
            workout("Empty session", day.plusDays(3), 9),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val description = "$day, workout completed, 1 workout with cardio"
            val numberBaseline = checkCalendarIcon(description, cardio = true)
            assertEquals(
                numberBaseline,
                checkCalendarIcon(
                    "${day.plusDays(2)}, workout completed, 1 workout with cardio",
                    cardio = true,
                ),
            )
            listOf(1L, 3L).forEach { offset ->
                assertEquals(
                    "Heart must not shift the day number",
                    numberBaseline,
                    checkCalendarIcon("${day.plusDays(offset)}, workout completed", cardio = false),
                )
            }
            checkCalendarIcon("${day.minusDays(1)}, no workout", cardio = false)
            scenario.recreate()
            assertEquals(numberBaseline, checkCalendarIcon(description, cardio = true))
            onView(withContentDescription(description)).perform(scrollTo(), click())
            onView(withText("Cardio session")).check(matches(isDisplayed()))
            pressBack()
            checkCalendarIcon(description, cardio = true)
        }
    }

    @Test
    fun sameDayPickerMarksCardioAndMixedWorkoutsWithoutMarkingStrengthWorkouts() {
        saveWorkouts(
            workout("Cardio morning", day, 9, cardio.id),
            workout("Strength afternoon", day, 14, "bench-press"),
            workout("Mixed evening", day, 18, cardio.id, "bench-press"),
            workout("Cardio night", day, 21, cardio.id),
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            val description = "$day, 4 workouts completed, 3 workouts with cardio"
            checkCalendarIcon(description, cardio = true)
            listOf("Cardio morning", "Strength afternoon", "Mixed evening", "Cardio night")
                .forEach { name ->
                    onView(withContentDescription(description)).perform(scrollTo(), click())
                    onView(withText(startsWith("$name · ")))
                        .check { view, _ ->
                            val label = view as TextView
                            val isCardio = name != "Strength afternoon"
                            if (isCardio) assertNotNull(label.compoundDrawablesRelative[2])
                            else assertNull(label.compoundDrawablesRelative[2])
                            assertEquals(
                                label.text.toString() + if (isCardio) ", includes cardio" else "",
                                label.contentDescription,
                            )
                        }
                        .perform(click())
                    onView(withText(name)).check(matches(isDisplayed()))
                    pressBack()
                }
        }
    }
}
