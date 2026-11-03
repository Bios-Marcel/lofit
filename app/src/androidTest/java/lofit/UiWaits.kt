package lofit

import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import org.hamcrest.Matcher

internal fun awaitCondition(
    controller: UiController,
    description: String,
    timeoutMillis: Long = 5_000,
    condition: () -> Boolean,
) {
    val deadline = SystemClock.uptimeMillis() + timeoutMillis
    while (!condition()) {
        check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for $description" }
        // Yield to layout, input and timer callbacks; return as soon as the condition holds.
        controller.loopMainThreadForAtLeast(16)
    }
}

internal fun waitUntil(description: String, condition: (View) -> Boolean): ViewAction =
    object : ViewAction {
        override fun getDescription() = "wait for $description"

        override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)

        override fun perform(uiController: UiController, view: View) {
            awaitCondition(uiController, description) { condition(view) }
        }
    }

internal fun waitUntilMatches(matcher: Matcher<View>): ViewAction =
    waitUntil(matcher.toString()) { matcher.matches(it) }

internal fun waitForStableLayout(): ViewAction =
    object : ViewAction {
        override fun getDescription() = "wait for a focused window and stable view bounds"

        override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)

        override fun perform(uiController: UiController, view: View) {
            var previous: Rect? = null
            var previousWindow: Rect? = null
            var stableFrames = 0
            awaitCondition(uiController, getDescription()) {
                val position = IntArray(2).also(view::getLocationOnScreen)
                val bounds =
                    Rect(
                        position[0],
                        position[1],
                        position[0] + view.width,
                        position[1] + view.height,
                    )
                val window = Rect().also(view::getWindowVisibleDisplayFrame)
                stableFrames =
                    if (
                        view.hasWindowFocus() &&
                            view.isShown &&
                            !view.rootView.isLayoutRequested &&
                            !bounds.isEmpty &&
                            bounds == previous &&
                            window == previousWindow
                    )
                        stableFrames + 1
                    else 0
                previous = bounds
                previousWindow = window
                stableFrames >= 3
            }
        }
    }

/** Install before typing so a visible IME is not mistaken for a finished IME animation. */
internal class KeyboardMonitor(private val scenario: ActivityScenario<MainActivity>) :
    AutoCloseable {
    private val animations = mutableSetOf<WindowInsetsAnimation>()

    init {
        scenario.onActivity { activity ->
            activity.window.decorView.setWindowInsetsAnimationCallback(
                object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    override fun onPrepare(animation: WindowInsetsAnimation) {
                        if (animation.typeMask and WindowInsets.Type.ime() != 0)
                            animations.add(animation)
                    }

                    override fun onProgress(
                        insets: WindowInsets,
                        runningAnimations: MutableList<WindowInsetsAnimation>,
                    ): WindowInsets = insets

                    override fun onEnd(animation: WindowInsetsAnimation) {
                        animations.remove(animation)
                    }
                }
            )
        }
    }

    fun awaitVisible(expected: Boolean): ViewAction =
        waitUntil("keyboard visible=$expected with its animation finished") { view ->
            view.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == expected &&
                animations.isEmpty() &&
                !view.rootView.isLayoutRequested
        }

    override fun close() {
        scenario.onActivity { it.window.decorView.setWindowInsetsAnimationCallback(null) }
    }
}
