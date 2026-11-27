package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.Text
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MochiButtonCancellationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @Before fun enabled() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close() }
        compose.mainClock.autoAdvance = true
    }

    @Test fun cancellationAccessibilityKeyboardDisabledAndSecondFinger() {
        for (dark in listOf(false, true)) {
            var clicks = 0
            val enabled = mutableStateOf(true)
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    CaptureTheme("mochi", dark) {
                        Box(Modifier.fillMaxSize()) {
                            Button(onClick = { clicks++ }, enabled = enabled.value,
                                modifier = Modifier.testTag("native-button")) { Text("Confirm") }
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(1000)
            compose.waitForIdle()
            val button = compose.onNodeWithTag("native-button")
            button.performTouchInput { down(center) }
            compose.runOnUiThread { assertEquals("DOWN is not activation", 0, clicks) }
            button.performTouchInput { cancel() }
            compose.runOnUiThread { assertEquals("CANCEL is not activation", 0, clicks) }
            button.performTouchInput { click(center) }
            compose.runOnUiThread { assertEquals("Fresh tap after cancellation", 1, clicks) }
            button.performTouchInput { down(center) }
            button.performSemanticsAction(SemanticsActions.OnClick) { it() }
            button.performTouchInput { cancel() }
            compose.runOnUiThread { assertEquals("Accessibility bypasses touch gate exactly once", 2, clicks) }
            button.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            button.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
            compose.runOnUiThread { assertEquals("Keyboard release", 3, clicks) }
            button.performTouchInput {
                down(0, center)
                down(1, center)
                up(1)
            }
            compose.runOnUiThread { assertEquals("Secondary UP cannot activate primary", 3, clicks) }
            button.performTouchInput { cancel() }
            compose.runOnUiThread { assertEquals("Primary cancel cannot borrow secondary UP", 3, clicks) }
            button.performTouchInput { down(center) }
            compose.runOnUiThread { enabled.value = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            button.performTouchInput { cancel() }
            button.assertIsNotEnabled()
            compose.runOnUiThread { assertEquals("Disabling a held press", 3, clicks) }
            compose.runOnUiThread { enabled.value = true }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            button.performTouchInput { click(center) }
            compose.runOnUiThread { assertEquals("Reenabled tap", 4, clicks) }
        }
    }
}
