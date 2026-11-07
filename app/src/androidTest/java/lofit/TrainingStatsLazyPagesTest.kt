package lofit

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrainingStatsLazyPagesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun prepareData() {
        context.clearRepositoryTestData()
        LofitRepository(context).apply {
            data.onboardingComplete = true
            data.bodyMetricsEnabled = false
            save()
        }
    }

    @Test
    fun hiddenStatsAppearOnlyWhenSelectedAndRetainTheirControls() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("Weekly rhythm")).check(doesNotExist())
            onView(withText("Muscle balance")).check(doesNotExist())
            onView(withContentDescription("Weekly rhythm, page 2 of 3"))
                .perform(scrollTo(), click())
            onView(withText("Weekly rhythm")).check(matches(isDisplayed()))
            onView(withText("Muscle balance")).check(doesNotExist())
            onView(withContentDescription("Muscle balance, page 3 of 3"))
                .perform(scrollTo(), click())
            onView(withText("Muscle balance")).check(matches(isDisplayed()))
            onView(withText("7d")).perform(click()).check(matches(isSelected()))
            onView(withContentDescription("Weekly rhythm, page 2 of 3")).perform(click())
            onView(withContentDescription("Muscle balance, page 3 of 3")).perform(click())
            onView(withText("7d")).check(matches(isSelected()))
        }
    }

    @Test
    fun swipingLoadsTheRevealedPage() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Calendar and training stats"))
                .perform(scrollTo(), swipeLeft())
            onView(withText("Weekly rhythm")).check(matches(isDisplayed()))
            onView(withText("Muscle balance")).check(doesNotExist())
        }
    }
}
