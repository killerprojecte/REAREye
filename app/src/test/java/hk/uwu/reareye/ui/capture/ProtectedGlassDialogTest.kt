package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.KeyEvent
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** Public production host, inert actions. Synthetic graphics evidence, not real permission/download. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProtectedGlassDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var show by mutableStateOf(true)
    private var locked by mutableStateOf(true)
    private var phase by mutableStateOf(false)
    private var hideOnDismiss by mutableStateOf(false)
    private var dismisses = 0
    private var pageBacks = 0
    private var pageTaps = 0
    private var actions = 0
    private val busySummary = "Protected operation: busy"
    private val readySummary = "Protected operation: ready"

    @Before fun requireCapture() {
        assertTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close() }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun tick() { compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
    private fun awaitHostDismissState(blocked: Boolean) {
        val summary = if (blocked) busySummary else readySummary
        // Summary and nullable dismiss are arguments of the same host call. Its native
        // summary witnesses the scene entry receiving those new arguments. Body controls
        // read the owner's state independently, so their enabled state alone is not enough.
        repeat(20) {
            tick()
            if (compose.onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText(summary).assertIsDisplayed()
                if (blocked) compose.onNodeWithText("Ordinary action").assertIsNotEnabled()
                else compose.onNodeWithText("Ordinary action").assertIsEnabled()
                return
            }
        }
        fail("Native host did not apply its ${if (blocked) "blocked" else "dismissible"} arguments")
    }
    private fun back() { compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; tick() }
    private fun startBack(progress: Float) {
        compose.runOnUiThread {
            val dispatcher = activity!!.get().onBackPressedDispatcher
            dispatcher.dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
            dispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 200f, progress, BackEventCompat.EDGE_LEFT))
        }
        tick()
    }
    private fun cancelBack() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.dispatchOnBackCancelled() }
        settle()
    }
    private fun outside() {
        compose.runOnUiThread {
            val time = android.os.SystemClock.uptimeMillis()
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(time, time + if (action == MotionEvent.ACTION_UP) 16 else 0,
                    action, 2f, 2f, 0)
                try { activity!!.get().window.decorView.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
        tick()
    }
    private fun bounds() = compose.onNodeWithText("Protected title").fetchSemanticsNode().boundsInWindow
    private fun assertStationary(expected: Rect) {
        val actual = bounds()
        assertEquals(expected.left, actual.left, 0.1f)
        assertEquals(expected.top, actual.top, 0.1f)
        assertEquals(expected.width, actual.width, 0.1f)
        assertEquals(expected.height, actual.height, 0.1f)
        assertEquals("Modal Back never reaches underlying page", 0, pageBacks)
        assertEquals("Scrim never leaks a click to page", 0, pageTaps)
    }
    private fun bitmap() = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
    private fun capture(backend: MixUiBackend, dark: Boolean, name: String) {
        val folder = File(System.getProperty("ui.capture.output"), "protected-glass-dialog").apply { mkdirs() }
        File(folder, "${backend.name.lowercase()}-${if (dark) "dark" else "light"}-$name.png")
            .outputStream().use { assertTrue(bitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun exercise(backend: MixUiBackend, dark: Boolean) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    Scaffold {
                        GlassBottomSheetScene {
                            BackHandler { pageBacks++ }
                            Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { pageTaps++ } }) {
                                drawRect(if (phase) Color(0xff2050e0) else Color(0xffe04020))
                            }
                            GlassOverlayDialog(show, "Protected title",
                                onDismissRequest = if (locked) null else ({
                                    // Match production owners: defend a busy transition before
                                    // recomposition has delivered the new nullable callback.
                                    if (!locked) {
                                        dismisses++
                                        if (hideOnDismiss) show = false
                                    }
                                }),
                                modifier = Modifier.testTag("protected-panel"),
                                summary = if (locked) busySummary else readySummary,
                            ) {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    // Empty material area for source-transmission evidence.
                                    Spacer(Modifier.fillMaxWidth().height(56.dp).testTag("protected-material"))
                                    Button(onClick = { actions++ }, enabled = !locked,
                                        modifier = Modifier.fillMaxWidth()) { Text("Ordinary action") }
                                }
                            }
                        }
                    }
                }
            }
        }
        settle()
        val initial = bounds()
        compose.onNodeWithText("Ordinary action").assertIsNotEnabled()
        outside(); back(); settle()
        assertEquals(0, dismisses); assertStationary(initial)
        val beforeBack = bitmap()
        startBack(.8f); assertStationary(initial); back(); settle()
        assertEquals(0, dismisses); assertStationary(initial)
        val afterBack = bitmap()
        assertTrue("Blocked completed Back must restore the whole scrim/material", beforeBack.sameAs(afterBack))
        capture(backend, dark, "locked-after-completed-back")
        startBack(.6f); cancelBack(); assertStationary(initial)

        // An unlock cannot repurpose a gesture that began while busy.
        startBack(.5f)
        compose.runOnUiThread { locked = false }; awaitHostDismissState(blocked = false)
        back(); settle()
        assertEquals(0, dismisses); assertStationary(initial)
        compose.onNodeWithText("Ordinary action").performClick()
        assertEquals(1, actions)
        // No composition or frame is allowed between the owner becoming busy and Back.
        // The callback retained by the host must still enforce the new business state.
        compose.runOnUiThread {
            locked = true
            activity!!.get().onBackPressedDispatcher.onBackPressed()
        }
        settle()
        assertEquals(0, dismisses); assertStationary(initial)
        compose.runOnUiThread { locked = false }; awaitHostDismissState(blocked = false)
        // A new lock vetoes a gesture that began dismissible.
        startBack(.7f)
        compose.runOnUiThread { locked = true }; awaitHostDismissState(blocked = true)
        back(); settle()
        assertEquals(0, dismisses); assertStationary(initial)

        // Material must sample a changing page, not an opaque imitation or its own content.
        val sample = compose.onNodeWithTag("protected-material").fetchSemanticsNode().boundsInWindow.center
        val first = bitmap().getPixel(sample.x.toInt(), sample.y.toInt())
        compose.runOnUiThread { phase = true }; settle()
        val second = bitmap().getPixel(sample.x.toInt(), sample.y.toInt())
        val delta = abs(android.graphics.Color.red(first) - android.graphics.Color.red(second)) +
            abs(android.graphics.Color.green(first) - android.graphics.Color.green(second)) +
            abs(android.graphics.Color.blue(first) - android.graphics.Color.blue(second))
        assertTrue("Live page changes must reach dialog material (delta=$delta)", delta > 4)
        capture(backend, dark, "changed-source")

        // Owners may reject a request without hiding; that cannot strand a partial transform.
        compose.runOnUiThread { locked = false }; awaitHostDismissState(blocked = false)
        startBack(.8f); back(); settle()
        assertEquals(1, dismisses); assertStationary(initial)
        outside(); settle(); assertEquals(2, dismisses)
        compose.runOnUiThread { hideOnDismiss = true }; tick()
        back(); back() // The second Back is swallowed while native exit remains visible.
        assertEquals(0, pageBacks)
        settle(); assertEquals(3, dismisses)
        compose.onNodeWithText("Protected title").assertDoesNotExist()
        compose.runOnUiThread { show = true; locked = true }; settle()
        assertStationary(initial)
        startBack(.4f); cancelBack(); assertStationary(initial)
    }

    @Test fun miuixTakesAndContainsPageKeyboardFocus() {
        var visible by mutableStateOf(false)
        var value by mutableStateOf("Original page draft")
        var pageFocused = false
        var pageKeys = 0
        var keyboardHides = 0
        var modalActions = 0
        var modalFocused = false
        lateinit var inputMode: InputModeManager
        val pageFocus = FocusRequester()
        val keyboard = object : SoftwareKeyboardController {
            override fun show() = Unit
            override fun hide() { keyboardHides++ }
        }
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(MixUiBackend.Miuix, MixColorSchemeMode.Light) {
                    val actualInputMode = LocalInputModeManager.current
                    SideEffect { inputMode = actualInputMode }
                    CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                        Scaffold {
                            GlassBottomSheetScene {
                                Column(Modifier.fillMaxSize()) {
                                    TextField(value, { value = it }, modifier = Modifier
                                        .focusRequester(pageFocus)
                                        .onFocusChanged { pageFocused = it.hasFocus }
                                        .onPreviewKeyEvent { pageKeys++; false }
                                        .testTag("background-edit"))
                                    Button(onClick = {}, modifier = Modifier.testTag("background-action")) {
                                        Text("Background action")
                                    }
                                }
                                GlassOverlayDialog(visible, "Keyboard modal", onDismissRequest = null) {
                                    Button(onClick = { modalActions++ }, modifier = Modifier
                                        .onFocusChanged { modalFocused = it.hasFocus }
                                        .testTag("keyboard-modal-action")) { Text("Modal action") }
                                }
                            }
                        }
                    }
                }
            }
        }
        settle()
        compose.runOnUiThread { pageFocus.requestFocus() }; tick()
        assertTrue("Precondition: actual page field owns focus", pageFocused)
        val oldHides = keyboardHides
        compose.runOnUiThread { visible = true }; settle()
        assertFalse("Opening the modal must end page focus", pageFocused)
        assertTrue("Opening the modal requests IME dismissal", keyboardHides > oldHides)
        // A decorView dispatch bypasses ViewRootImpl's physical-key touch-mode switch.
        // Native clickable targets are SystemDefined and not focusable in Touch mode.
        // Establish the real public keyboard mode, then still require raw Tab/Enter to work.
        compose.runOnUiThread {
            assertTrue("The keyboard fixture must enter keyboard mode",
                inputMode.requestInputMode(InputMode.Keyboard))
        }
        tick()
        assertEquals("Keyboard mode must be applied before raw key dispatch",
            InputMode.Keyboard, inputMode.inputMode)
        assertFalse("Preparing keyboard input must not focus the background", pageFocused)
        val oldKeys = pageKeys
        fun hardwareKey(code: Int) {
            compose.runOnUiThread {
                for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                    activity!!.get().window.decorView.dispatchKeyEvent(KeyEvent(action, code))
                }
            }
            tick()
        }
        // Keyboard isolation must still leave a usable keyboard target, rather than
        // stranding focus on the empty full-window host. Reach the actual native button.
        var tabs = 0
        do {
            hardwareKey(KeyEvent.KEYCODE_TAB)
            tabs++
        } while (!modalFocused && tabs < 5)
        assertTrue("Hardware Tab must focus the real modal button", modalFocused)
        val beforeEnter = modalActions
        hardwareKey(KeyEvent.KEYCODE_ENTER)
        assertEquals("Hardware Enter must activate the focused modal button once",
            beforeEnter + 1, modalActions)
        repeat(5) { hardwareKey(KeyEvent.KEYCODE_TAB) }
        hardwareKey(KeyEvent.KEYCODE_A)
        assertFalse("Tab/Enter cannot return focus to the background field", pageFocused)
        assertEquals("Background field must not receive modal keys", oldKeys, pageKeys)
        assertEquals("Original page draft", value)
        compose.runOnUiThread { pageFocus.requestFocus() }; tick()
        assertFalse("A page focus request is rejected while the modal owns focus", pageFocused)
        val previousActions = modalActions
        compose.onNodeWithText("Modal action").performClick()
        assertEquals("Ordinary modal control remains usable", previousActions + 1, modalActions)
        compose.runOnUiThread { visible = false }; tick()
        compose.runOnUiThread { pageFocus.requestFocus() }; tick()
        assertFalse("Focus remains isolated through native exit", pageFocused)
        settle()
        compose.runOnUiThread { pageFocus.requestFocus() }; tick()
        assertTrue("Page focus becomes available after complete dismissal", pageFocused)
        assertEquals("Original page draft", value)
    }
    @Test fun miuixLight() = exercise(MixUiBackend.Miuix, false)
    @Test fun miuixDark() = exercise(MixUiBackend.Miuix, true)
    @Test fun mochiLight() = exercise(MixUiBackend.Mochi, false)
    @Test fun mochiDark() = exercise(MixUiBackend.Mochi, true)
}
