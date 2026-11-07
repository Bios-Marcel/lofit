package lofit

import android.Manifest
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isClickable
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.ZoneId
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryPagingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

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
            save()
        }
    }

    private fun seedHistory(count: Int) {
        val noon =
            LocalDate.now().atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        LofitRepository(context).apply {
            data.history.clear()
            val workouts =
                List(count) { index ->
                    val completed = noon - (count - index) * 60_000L
                    WorkoutSession(
                        name = "Paged workout ${index + 1}",
                        startedAt = completed - 60_000,
                        completedAt = completed,
                        exercises =
                            mutableListOf(
                                WorkoutExercise(
                                    exerciseId = "bench-press",
                                    sets =
                                        mutableListOf(
                                            LoggedSet(
                                                type = SetType.NORMAL,
                                                weightKg = 40.0,
                                                reps = 8,
                                            )
                                        ),
                                )
                            ),
                    )
                }
            // Deliberately unsorted; page boundaries must follow completion time.
            data.history.addAll(workouts.filterIndexed { index, _ -> index % 2 == 0 })
            data.history.addAll(workouts.filterIndexed { index, _ -> index % 2 != 0 })
            saveHistory()
        }
    }

    private fun openHistory() {
        onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
        onView(allOf(withText(R.string.nav_history), isClickable())).perform(click())
    }

    private fun openDrawer() =
        object : ViewAction {
            override fun getDescription() = "open navigation drawer"

            override fun getConstraints(): Matcher<View> = isDisplayed()

            override fun perform(uiController: UiController, view: View) {
                (view as DrawerLayout).openDrawer(GravityCompat.START, false)
                uiController.loopMainThreadUntilIdle()
            }
        }

    private fun descendants(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup)
                (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) }
            else emptyList()

    private fun checkWorkouts(expected: IntProgression, continuable: Int = 0) {
        onView(isAssignableFrom(ScrollView::class.java)).check { view, _ ->
            val descriptions = descendants(view).mapNotNull { it.contentDescription?.toString() }
            assertEquals(
                expected.map { "View Paged workout $it" },
                descriptions.filter { it.startsWith("View Paged workout ") },
            )
            assertEquals(
                continuable,
                descriptions.count { it.startsWith("Continue Paged workout ") },
            )
        }
    }

    @Test
    fun pagingAndNavigationPreserveWorkoutOrderAndScrollPosition() {
        seedHistory(23)
        ActivityScenario.launch(MainActivity::class.java).use {
            openHistory()
            checkWorkouts(23 downTo 14, continuable = 1)
            onView(withContentDescription("History page 1 of 3")).check(matches(isDisplayed()))
            onView(withContentDescription("Newer workouts")).check(matches(not(isEnabled())))
            var pagerY = 0
            onView(withContentDescription("History pages")).check { view, _ ->
                pagerY = IntArray(2).also(view::getLocationOnScreen)[1]
            }
            onView(withContentDescription("View Paged workout 14")).perform(scrollTo())
            onView(withContentDescription("History pages")).check(matches(isDisplayed())).check {
                view,
                _ ->
                assertEquals(
                    "Pager must stay fixed while scrolling",
                    pagerY,
                    IntArray(2).also(view::getLocationOnScreen)[1],
                )
            }
            onView(withContentDescription("Older workouts")).perform(click())
            checkWorkouts(13 downTo 4)
            onView(withContentDescription("History page 2 of 3")).check(matches(isDisplayed()))
            onView(withContentDescription("View Paged workout 13")).check(matches(isDisplayed()))
            onView(isAssignableFrom(ScrollView::class.java)).check { view, _ ->
                assertEquals(0, (view as ScrollView).scrollY)
            }
            onView(withContentDescription("Older workouts")).perform(click())
            checkWorkouts(3 downTo 1)
            onView(withContentDescription("History page 3 of 3")).check(matches(isDisplayed()))
            onView(withContentDescription("Older workouts")).check(matches(not(isEnabled())))
            onView(withContentDescription("Newer workouts")).perform(click())
            checkWorkouts(13 downTo 4)
            onView(withContentDescription("Newer workouts")).perform(click())
            checkWorkouts(23 downTo 14, continuable = 1)

            onView(withContentDescription("Older workouts")).perform(click())
            onView(withContentDescription("View Paged workout 8")).perform(scrollTo())
            var scrollY = 0
            onView(isAssignableFrom(ScrollView::class.java)).check { view, _ ->
                scrollY = (view as ScrollView).scrollY
            }
            assertTrue(scrollY > 0)
            onView(withContentDescription("View Paged workout 8")).perform(click())
            onView(withContentDescription("History pages")).check(matches(not(isDisplayed())))
            pressBack()
            onView(withContentDescription("History page 2 of 3")).check(matches(isDisplayed()))
            onView(withContentDescription("View Paged workout 8")).check(matches(isDisplayed()))
            onView(isAssignableFrom(ScrollView::class.java)).check { view, _ ->
                assertEquals(scrollY, (view as ScrollView).scrollY)
            }
            onView(withContentDescription("View Paged workout 8")).perform(click())
            onView(withContentDescription("Options for Paged workout 8")).perform(click())
            onView(withText("Edit")).perform(click())
            onView(withContentDescription("History pages")).check(matches(not(isDisplayed())))
            onView(withText("Save changes")).perform(scrollTo(), click())
            onView(withContentDescription("History page 2 of 3")).check(matches(isDisplayed()))
            checkWorkouts(13 downTo 4)
            onView(isAssignableFrom(ScrollView::class.java)).check { view, _ ->
                assertEquals(scrollY, (view as ScrollView).scrollY)
            }
            onView(isAssignableFrom(DrawerLayout::class.java)).perform(openDrawer())
            onView(withText(R.string.nav_settings)).perform(click())
            onView(withContentDescription("History pages")).check(matches(not(isDisplayed())))
            pressBack()
            onView(withContentDescription("History page 2 of 3")).check(matches(isDisplayed()))
            onView(withContentDescription("View Paged workout 8")).check(matches(isDisplayed()))
            openHistory()
            onView(withContentDescription("History page 1 of 3")).check(matches(isDisplayed()))
            checkWorkouts(23 downTo 14, continuable = 1)
        }
    }

    @Test
    fun emptyAndSinglePageHistoriesHaveNoInvalidNextPage() {
        for (count in listOf(0, 1, 10)) {
            seedHistory(count)
            ActivityScenario.launch(MainActivity::class.java).use {
                openHistory()
                if (count == 0) {
                    onView(withText("Complete a workout and it will appear here."))
                        .check(matches(isDisplayed()))
                    onView(withContentDescription("History pages"))
                        .check(matches(not(isDisplayed())))
                } else {
                    checkWorkouts(count downTo 1, continuable = 1)
                    onView(withContentDescription("History page 1 of 1"))
                        .check(matches(isDisplayed()))
                    onView(withContentDescription("Newer workouts"))
                        .check(matches(not(isEnabled())))
                    onView(withContentDescription("Older workouts"))
                        .check(matches(not(isEnabled())))
                }
            }
        }
    }
}
