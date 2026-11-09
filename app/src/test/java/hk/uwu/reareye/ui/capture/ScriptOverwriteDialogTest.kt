package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.mixui.components.Scaffold
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.ui.components.script.ScriptOverwriteDialog
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real native dialog controls in an offline fixture. No filesystem overwrite or script navigation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptOverwriteDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var shown by mutableStateOf(false)
    private var session by mutableStateOf(Any())
    private var message by mutableStateOf("")
    private var confirms = 0
    private var dismisses = 0
    private var pageBacks = 0

    @Before fun explicitUiRun() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }

    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }

    private fun settle() {
        repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }

    private fun host(backend: MixUiBackend, dark: Boolean) {
        compose.runOnUiThread {
            activity?.close()
            shown = false
            confirms = 0
            dismisses = 0
            pageBacks = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    GlassPreferenceScene(
                        overlayContent = {
                            ScriptOverwriteDialog(
                                show = shown,
                                sessionKey = session,
                                title = if (shown) "Overwrite script?" else "",
                                message = if (shown) message else "",
                                onDismiss = { dismisses++; shown = false },
                                onConfirm = { confirms++; shown = false },
                            )
                        },
                    ) {
                        Scaffold(popupHost = {}) {
                            // Contrasting source bands make an accidentally empty/tinted-only
                            // backdrop visible in the light/dark capture matrix.
                            Box(Modifier.fillMaxSize().drawBehind {
                                val bandHeight = size.height / 12f
                                repeat(12) { index ->
                                    drawRect(
                                        color = if (index % 2 == 0) Color(0xFF426C91) else Color(0xFFC89262),
                                        topLeft = Offset(0f, index * bandHeight),
                                        size = Size(size.width, bandHeight),
                                    )
                                }
                            }) { Text("Offline script confirmation fixture") }
                            BackHandler { pageBacks++ }
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun open(text: String) {
        compose.runOnUiThread { message = text; session = Any(); shown = true }
        settle()
        compose.onNodeWithTag("script-overwrite-message").assertTextEquals(text).assertIsDisplayed()
    }

    private fun assertClosed() {
        settle()
        compose.onNodeWithTag("script-overwrite-confirm").assertDoesNotExist()
        compose.onNodeWithText("Offline script confirmation fixture").assertIsDisplayed()
    }

    @Test fun nativeAppearanceCancelConfirmAndReopen() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, dark)
            open("A script named example.lua already exists. Overwrite it?")
            val directory = File(System.getProperty("ui.capture.output"), "script-overwrite")
            check(directory.isDirectory || directory.mkdirs())
            compose.runOnUiThread {
                val bitmap = captureWindowBitmap(activity!!.get().window)
                File(directory, "${backend.name}-${if (dark) "dark" else "light"}.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            compose.onNodeWithTag("script-overwrite-cancel").performClick()
            assertClosed()
            compose.runOnIdle { assertEquals(1, dismisses); assertEquals(0, confirms) }
            open("A project named replacement already exists. Overwrite it?")
            // Invoke the same rendered action twice before recomposition/exit, reproducing rapid submit.
            val confirm = compose.onNodeWithTag("script-overwrite-confirm")
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnUiThread { confirm(); confirm() }
            assertClosed()
            compose.runOnIdle { assertEquals(1, confirms); assertEquals(1, dismisses) }
            open("A new request must be actionable after dismissal.")
            compose.onNodeWithTag("script-overwrite-confirm").performClick()
            assertClosed()
            compose.runOnIdle { assertEquals(2, confirms) }
        }
    }

    @Test fun interruptedPressCannotConfirmAnIdenticalNewRequest() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, dark)
            val body = "The same script already exists. Overwrite it?"
            open(body)
            val oldConfirmId = compose.onNodeWithTag("script-overwrite-confirm")
                .fetchSemanticsNode().id
            // Real pointer down belongs to the old request. Replacing its native
            // clickable must cancel it rather than releasing into the new one.
            compose.onNodeWithTag("script-overwrite-confirm").performTouchInput { down(center) }
            compose.runOnUiThread { shown = false }
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            open(body)
            org.junit.Assert.assertNotEquals(oldConfirmId,
                compose.onNodeWithTag("script-overwrite-confirm").fetchSemanticsNode().id)
            compose.onNodeWithTag("script-overwrite-confirm").performTouchInput { up() }
            settle()
            compose.runOnIdle { assertEquals(0, confirms); assertEquals(0, dismisses) }
            compose.onNodeWithTag("script-overwrite-message").assertIsDisplayed()
            compose.onNodeWithTag("script-overwrite-confirm").performClick()
            assertClosed()
            compose.runOnIdle { assertEquals(1, confirms) }
        }
    }

    @Test fun outsideTapCancelsWithoutOverwritingAndCanReopen() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, dark)
            open("Outside tap must not overwrite this script.")
            compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("script-overwrite-confirm")), useUnmergedTree = true)
                .performTouchInput { click(Offset(2f, 2f)) }
            assertClosed()
            compose.runOnIdle { assertEquals(1, dismisses); assertEquals(0, confirms) }
            open("Reopened after outside cancellation.")
            compose.onNodeWithTag("script-overwrite-cancel").performClick()
            assertClosed()
            compose.runOnIdle { assertEquals(2, dismisses); assertEquals(0, confirms) }
        }
    }

    @Test fun backDismissesOnlyDialogAndAllowsReopening() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, dark)
            open("Back must cancel this overwrite.")
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            assertClosed()
            compose.runOnIdle {
                assertEquals(1, dismisses)
                assertEquals(0, confirms)
                assertEquals(0, pageBacks)
            }
            open("Reopened after Back.")
            compose.onNodeWithTag("script-overwrite-cancel").performClick()
            assertClosed()
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            compose.runOnIdle { assertEquals(1, pageBacks); assertEquals(0, confirms) }
        }
    }
}
