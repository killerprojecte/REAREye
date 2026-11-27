package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.script.ScriptDebugBottomSheet
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real backend controls, synthetic output/insets only: no script runtime or network. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptDebugGlassSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var fullStyleFolder: File? = null
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var provider by mutableStateOf("hello")
    private var params by mutableStateOf("{}")
    private var result by mutableStateOf("")
    private val runs = mutableListOf<Pair<String, String>>()
    private var dismissals = 0
    @Before fun resources() { assumeTrue(java.lang.Boolean.getBoolean("mary.ui.resources")) }
    @After fun close() { compose.runOnUiThread { activity?.close() } }
    private fun node(tag: String): SemanticsNodeInteraction = when (tag) {
        "script-debug-run" -> compose.onNodeWithContentDescription(activity!!.get().getString(R.string.script_debug_run))
        "script-debug-provider", "script-debug-params" -> compose.onNode(
            hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
        else -> compose.onNodeWithTag(tag, useUnmergedTree = true)
    }
    private fun settle() { compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle() }
    private fun insets(nav: Int, ime: Int = 0) {
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, nav))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), nav > 0)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime))
                    .setVisible(WindowInsetsCompat.Type.ime(), ime > 0).build())
        }
        settle()
    }
    private fun capture(name: String) {
        val output = (fullStyleFolder ?: File(System.getProperty("ui.capture.output"), "script-debug")).apply { mkdirs() }
        compose.runOnUiThread {
            val bitmap = captureWindowBitmap(activity!!.get().window)
            File(output, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    private fun exercise(backend: MixUiBackend, dark: Boolean,
        colorMode: MixColorSchemeMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light,
        mochiStyle: MixMochiStyle = MixMochiStyle.Default) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend, colorMode, mochiStyle) {
                    Scaffold {
                        GlassBottomSheetScene {
                            Box(Modifier.fillMaxSize().background(Color(0xFF3B7A8E)))
                            ScriptDebugBottomSheet(visible, provider, { provider = it }, params, { params = it },
                                result, onRun = {
                                    runs += provider to params
                                    result = (1..60).joinToString("\n") { "Synthetic result line $it" }
                                }, onDismiss = { dismissals++; visible = false })
                        }
                    }
                }
            }
        }
        settle()
        val host = node(if (backend == MixUiBackend.Mochi) "mochi-glass-sheet" else "mixui-native-glass-sheet")
        assertTrue("Empty debug panel remains compact", host.fetchSemanticsNode().boundsInRoot.height < 400f)
        if (backend == MixUiBackend.Miuix) node("mixui-sheet-plain-action").assertExists()
        val name = "${backend.name}-${if (dark) "dark" else "light"}"
        capture("$name-empty")
        node("script-debug-provider").performTextReplacement("")
        node("script-debug-params").performTextReplacement("not-json")
        node("script-debug-run").assertIsEnabled().performClick(); settle()
        assertEquals("Run forwards even empty provider/invalid JSON for existing owner validation",
            listOf("" to "not-json"), runs)
        compose.runOnIdle { runs.clear(); result = "" }; settle()
        node("script-debug-provider").performTextReplacement("fixture_provider")
        node("script-debug-params").performTextReplacement("{\"name\":\"test\"}")
        node("script-debug-run").performClick(); settle()
        assertEquals(listOf("fixture_provider" to "{\"name\":\"test\"}"), runs)
        node("script-debug-result").assertIsDisplayed()
        if (backend == MixUiBackend.Miuix) node("mixui-sheet-plain-action").assertExists()
        node("script-debug-result").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 180f) }
        settle()
        if (backend == MixUiBackend.Miuix) node("mixui-sheet-glass-action").assertExists()
        assertTrue(visible)
        capture("$name-scrolled-synthetic-output")
        insets(0)
        val baseline = node("script-debug-content").fetchSemanticsNode().boundsInRoot.bottom
        insets(48)
        assertEquals("Navigation reserved once", baseline - 48,
            node("script-debug-content").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        insets(48, 400)
        assertEquals("IME consumes navigation rather than adding it", baseline - 400,
            node("script-debug-content").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        node("script-debug-run").assertIsDisplayed()
        node("script-debug-params").performScrollTo().assertIsDisplayed()
        val windowBottom = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom
        assertTrue("Last editable field stays above synthetic keyboard",
            node("script-debug-params").fetchSemanticsNode().boundsInRoot.bottom <= windowBottom - 400 + 1f)
        capture("$name-synthetic-ime")
        insets(0)
        node("script-debug-run").performTouchInput { down(center); cancel() }
        settle()
        assertEquals("Canceled press never runs", 1, runs.size)
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        settle()
        node("script-debug-run").assertDoesNotExist()
        assertEquals(1, dismissals)
        compose.runOnIdle { visible = true }; settle()
        node("script-debug-provider").assertTextContains("fixture_provider")
        node("script-debug-params").assertTextContains("{\"name\":\"test\"}")
        if (backend == MixUiBackend.Miuix) node("mixui-sheet-plain-action").assertExists()
        node("script-debug-run").performClick(); settle()
        assertEquals(2, runs.size)
        compose.onRoot().performTouchInput { click(Offset(200f, 80f)) }; settle()
        node("script-debug-run").assertDoesNotExist()
        assertEquals(2, dismissals)
        assertEquals("Dismissals do not execute", 2, runs.size)
    }
    @Test fun renderFullStyleScriptDebugMatrix() = majorSheetStyleMatrix(
        directory = "full-style-script-debug", language = "en", fixture = "ScriptDebugBottomSheet production sheet over solid teal fixture background; synthetic 60-line results and synthetic IME/nav insets; no script execution; not a full application page",
        close = { compose.runOnUiThread { activity?.close(); activity = null } },
    ) { _, _, backend, dark, colors, style, folder ->
        compose.runOnIdle { visible = true; provider = "hello"; params = "{}"; result = ""; runs.clear(); dismissals = 0 }
        fullStyleFolder = folder
        try { exercise(backend, dark, colors, style) } finally { fullStyleFolder = null }
    }

    @Test fun miuixLight() = exercise(MixUiBackend.Miuix, false)
    @Test fun miuixDark() = exercise(MixUiBackend.Miuix, true)
    @Test fun mochiLight() = exercise(MixUiBackend.Mochi, false)
    @Test fun mochiDark() = exercise(MixUiBackend.Mochi, true)
}
