package lofit

import android.Manifest
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.not
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExerciseLibraryFilterTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun resetData() {
        instrumentation.uiAutomation.grantRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            listOf("Recent curl", "Unused curl").forEach { name ->
                data.exercises.add(
                    Exercise(
                        name,
                        name,
                        ExerciseType.REPETITIONS,
                        false,
                        "Biceps",
                        "",
                        custom = true,
                    )
                )
            }
            data.exerciseLastSelectedAt["Recent curl"] = 30
            data.history.add(
                WorkoutSession(
                    name = "Past",
                    completedAt = 20,
                    exercises =
                        mutableListOf(
                            WorkoutExercise(
                                exerciseId = "bench-press",
                                sets =
                                    mutableListOf(
                                        LoggedSet(
                                            type = SetType.NORMAL,
                                            weightKg = 20.0,
                                            reps = 8,
                                            completedAt = 20,
                                        )
                                    ),
                            )
                        ),
                )
            )
            saveHistory()
            save()
        }
    }

    private fun openLibrary(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val root = activity.findViewById<ViewGroup>(android.R.id.content)
            (root.getChildAt(0) as DrawerLayout).openDrawer(GravityCompat.START, false)
        }
        onView(allOf(withText(R.string.nav_exercises), isClickable())).perform(click())
    }

    private fun search(query: String) {
        onView(withHint("Search name or muscle")).perform(scrollTo(), replaceText(query))
    }

    private fun exerciseName(name: String) =
        onView(allOf(withText(name), not(isAssignableFrom(EditText::class.java))))

    @Test
    fun recentIsDefaultAndFiltersKeepSearchQuery() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLibrary(scenario)
            onView(withText("Recent")).check(matches(isChecked()))
            exerciseName("Unused curl").check(doesNotExist())
            exerciseName("Plank").check(doesNotExist())
            var recentTop = 0
            exerciseName("Recent curl").check { view, _ ->
                recentTop = IntArray(2).also { view.getLocationOnScreen(it) }[1]
            }
            exerciseName("Bench press").check { view, _ ->
                val benchTop = IntArray(2).also { view.getLocationOnScreen(it) }[1]
                assertTrue(recentTop < benchTop)
            }

            onView(withText("Custom")).perform(scrollTo(), click())
            exerciseName("Bench press").check(doesNotExist())
            exerciseName("Recent curl").check(matches(isDisplayed()))
            exerciseName("Unused curl").check(matches(isDisplayed()))
            search("Unused curl")
            exerciseName("Recent curl").check(doesNotExist())
            exerciseName("Unused curl").check(matches(isDisplayed()))
            onView(withText("Recent")).perform(scrollTo(), click())
            onView(withText("No matching exercises.")).check(matches(isDisplayed()))
            onView(withText("All")).perform(scrollTo(), click())
            onView(withHint("Search name or muscle")).check(matches(withText("Unused curl")))
            exerciseName("Unused curl").check(matches(isDisplayed()))
            search("Bench press")
            exerciseName("Bench press").check(matches(isDisplayed()))
            onView(withText("Custom")).perform(scrollTo(), click())
            onView(withText("No matching exercises.")).check(matches(isDisplayed()))
        }
    }

    @Test
    fun emptyRecentStillAllowsBrowsingAllAndCustomExercises() {
        LofitRepository(context).apply {
            data.exerciseLastSelectedAt.clear()
            data.history.clear()
            saveHistory()
            save()
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openLibrary(scenario)
            onView(withText("Recent")).check(matches(isChecked()))
            onView(withText("No matching exercises.")).check(matches(isDisplayed()))
            onView(withText("All")).perform(scrollTo(), click())
            search("Bench press")
            exerciseName("Bench press").check(matches(isDisplayed()))
            search("")
            onView(withText("Custom")).perform(scrollTo(), click())
            exerciseName("Recent curl").check(matches(isDisplayed()))
            exerciseName("Unused curl").check(matches(isDisplayed()))
        }
    }
}
