package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixMochiStyle
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

/**
 * Regression harness, not a business page or device/IME proof. Real mixui form hosts and Maven native
 * inputs are used without replacing pointer, focus, text selection, or normal cursor blinking.
 * Samples are taken while DOWN is still held. Title geometry is asserted stable during field gestures; native widget
 * interaction is not replaced. InputConnection creation is deliberately not claimed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StableMochiFormDialogTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var value by mutableStateOf("中文选区测试 abcdefghijklmnopqrstuvwxyz 0123456789 很长的模型名称")
    private var shown by mutableStateOf(true)
    private var writes = 0
    private var pageTaps = 0
    private var rejected = 0
    private lateinit var folder: File
    private lateinit var report: File
    private var initialNode = -1
    private var stableTitle: androidx.compose.ui.geometry.Rect? = null

    @Before fun enabled() {
        Assume.assumeTrue("Explicit native UI diagnostic only", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    @Test fun mochiPlainDuringSelection() = exercise(MixUiBackend.Mochi, false)
    @Test fun mochiGlassDuringSelection() = exercise(MixUiBackend.Mochi, true)

    @Composable private fun form(glass: Boolean) {
        val body: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("普通输入控件：左右选择文字", Modifier.testTag("cursor-label"))
                TextField(value = value, onValueChange = { value = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Button(onClick = { rejected++ }) { Text("验证失败") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { shown = false }, modifier = Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = { writes++; shown = false }, modifier = Modifier.weight(1f)) { Text("保存") }
                }
            }
        }
        if (glass) GlassFormDialog(shown, "输入诊断", { shown = false },
            modifier = Modifier.testTag("cursor-dialog"), content = body)
        else FormDialog(shown, "输入诊断", { shown = false },
            modifier = Modifier.testTag("cursor-dialog"), content = body)
    }
    private fun tapWindow(point: Offset) {
        compose.runOnUiThread {
            val now = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(now, now + if (action == 1) 16 else 0,
                    action, point.x, point.y, 0)
                try { activity!!.get().window.decorView.dispatchTouchEvent(event) } finally { event.recycle() }
            }
        }
    }

    @Test fun narrowGlassBodyKeepsNativeLeadingInset() = narrowBody(true)
    @Test fun narrowSolidBodyKeepsNativeLeadingInset() = narrowBody(false)

    private fun narrowBody(glass: Boolean) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend = MixUiBackend.Mochi, colorMode = MixColorSchemeMode.Light,
                    mochiStyle = MixMochiStyle.Ios) {
                    val body: @Composable () -> Unit = { Text("x") }
                    if (glass) GlassPreferenceScene(overlayContent = {
                        GlassFormDialog(true, "Narrow", {}, Modifier.testTag("cursor-dialog"), body)
                    }) { Box(Modifier.fillMaxSize()) }
                    else FormDialog(true, "Narrow", {}, Modifier.testTag("cursor-dialog"), body)
                }
            }
        }
        repeat(24) { tick(100) }
        val root = overlayRoot().fetchSemanticsNode().boundsInWindow
        val density = activity!!.get().resources.displayMetrics.density
        val width = minOf(root.width - 72f * density, 320f * density)
        val expected = root.center.x - width / 2 + 24f * density
        assertEquals("Narrow title retains native leading inset", expected,
            compose.onNodeWithText("Narrow").fetchSemanticsNode().boundsInWindow.left, 0.1f)
        assertEquals(expected, compose.onNodeWithText("x").fetchSemanticsNode().boundsInWindow.left, 0.1f)
    }

    private fun tick(ms: Long = 64) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }
    private fun field() = compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("cursor-dialog")), useUnmergedTree = true)
    private fun overlayRoot() = compose.onNode(
        isRoot() and hasAnyDescendant(hasTestTag("cursor-dialog")), useUnmergedTree = true)

    private fun sample(name: String, image: Boolean = true) {
        val node = field().fetchSemanticsNode()
        val range = node.config.getOrNull(SemanticsProperties.TextSelectionRange)
        val layouts = mutableListOf<TextLayoutResult>()
        if (node.config.contains(SemanticsActions.GetTextLayoutResult)) {
            field().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        }
        val caret = layouts.firstOrNull()?.let { layout ->
            range?.let { layout.getCursorRect(it.end.coerceIn(0, layout.layoutInput.text.length)) }
        }
        val row = listOf(name, compose.mainClock.currentTime, node.id,
            node.config.getOrNull(SemanticsProperties.Focused), range,
            node.boundsInRoot, node.boundsInWindow,
            compose.onNodeWithTag("cursor-dialog", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow,
            compose.onNodeWithTag("cursor-label", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow,
            caret, value.length).joinToString("\t")
        report.appendText(row + "\n")
        if (image) {
            val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
            File(folder, "$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        stableTitle?.let { expected ->
            val actual = compose.onNodeWithText("输入诊断").fetchSemanticsNode().boundsInWindow
            assertEquals("Title must not follow native selection horizontally", expected.left, actual.left, 0.1f)
            assertEquals("Title must not follow native selection vertically", expected.top, actual.top, 0.1f)
        }
        assertTrue("Dialog must stay open during field gesture", shown)
        field().assertIsFocused()
        assertEquals("No native editable node replacement", initialNode, node.id)
        assertEquals("Pointer selection must not save the draft", 0, writes)
    }
    private fun exercise(backend: MixUiBackend, glass: Boolean) {
        folder = File(System.getProperty("ui.capture.output"),
            "stable-mochi-form/${backend.name}/${if (glass) "glass" else "plain"}").apply { mkdirs() }
        report = File(folder, "frames.tsv")
        report.writeText("phase\tclock_ms\tnode\tfocused\tselection\tfield_root\tfield_window\tdialog_window\tlabel_window\tcaret_local\ttext_length\n")
        File(folder, "scope.txt").writeText(
            "Native Robolectric diagnostic, real native widgets, synthetic form content. Light theme; Mochi iOS.\n" +
            "No Android/device IME or InputConnection assertion. Geometry and blink are observations.\n" +
            "A long field may pan text instead of changing selection; inspect recorded range, not presumed selection.\n" +
            "Coordinates for each drag are fixed in the overlay root, not recomputed from a deforming field.\n")
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend = backend, colorMode = MixColorSchemeMode.Light,
                    mochiStyle = MixMochiStyle.Ios) {
                    GlassPreferenceScene(overlayContent = { form(glass) }) {
                        Canvas(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { pageTaps++ } }) {
                            val colors = listOf(Color(0xFFB8D8FF), Color(0xFFFFD6B8), Color(0xFFC9E8CA))
                            val band = 96.dp.toPx()
                            for (i in 0..(size.width / band).toInt())
                                drawRect(colors[i % colors.size], Offset(i * band, 0f), Size(band, size.height))
                        }
                    }
                }
            }
        }
        repeat(24) { tick(100) }
        stableTitle = compose.onNodeWithText("输入诊断").fetchSemanticsNode().boundsInWindow
        field().performTouchInput { click() }
        repeat(12) { tick(100) }
        initialNode = field().fetchSemanticsNode().id
        // Unmodified native cursor baseline over two seconds, not just one arbitrary blink phase.
        for (i in 0..25) { sample("idle-${i.toString().padStart(2, '0')}", i % 5 == 0); tick(80) }
        for (hold in listOf(0L, 640L)) {
            val root = overlayRoot()
            val rb = root.fetchSemanticsNode().boundsInRoot
            val fb = field().fetchSemanticsNode().boundsInRoot
            val y = fb.center.y - rb.top
            val right = fb.left + fb.width * 0.82f - rb.left
            val left = fb.left + fb.width * 0.18f - rb.left
            root.performTouchInput { down(Offset(right, y)) }
            tick(16)
            sample("hold-$hold-down")
            if (hold > 0) {
                root.performTouchInput { advanceEventTime(hold) }
                tick(hold)
                sample("hold-$hold-still")
            }
            for (step in 1..12) {
                val fraction = if (step <= 6) step / 6f else (12 - step) / 6f
                root.performTouchInput {
                    advanceEventTime(64)
                    moveTo(Offset(right + (left - right) * fraction, y))
                }
                tick(64)
                sample("hold-$hold-move-${step.toString().padStart(2, '0')}")
            }
            root.performTouchInput { up() }
            tick(16); sample("hold-$hold-up")
            repeat(12) { tick(100) }
            sample("hold-$hold-settled")
        }
        val old = value
        field().performTextInput("测")
        tick(100)
        assertNotEquals("Native field must still accept input after selection", old, value)
        sample("typed-after-drag")
        compose.onNodeWithText("验证失败").performClick()
        tick(100)
        assertTrue("Validation rejection must leave form open", shown)
        assertEquals(0, writes)
        field().performClick()
        // Synthetic insets only: this does not establish a real Android IME or InputConnection.
        compose.runOnUiThread {
            activity!!.get().window.decorView.dispatchApplyWindowInsets(
                android.view.WindowInsets.Builder()
                    .setInsets(android.view.WindowInsets.Type.ime(), android.graphics.Insets.of(0, 0, 0, 260))
                    .setVisible(android.view.WindowInsets.Type.ime(), true).build())
        }
        repeat(12) { tick(100) }
        stableTitle = compose.onNodeWithText("输入诊断").fetchSemanticsNode().boundsInWindow
        field().performTextInput("法")
        tick(100); sample("synthetic-ime-typed")
        // Dispatch through DecorView, not directly to the Compose test root,
        // so the Android parent touch gate is actually exercised.
        val savePoint = compose.onNodeWithText("保存").fetchSemanticsNode().boundsInWindow.center
        tapWindow(compose.onNodeWithText("取消").fetchSemanticsNode().boundsInWindow.center)
        tick(16)
        assertFalse(shown)
        tapWindow(savePoint)
        assertEquals("New Save gesture during exit must be swallowed", 0, writes)
        assertEquals("Exiting overlay must not leak touches to page", 0, pageTaps)
        // Reverse exit before it completes; the same editable node must survive.
        compose.runOnUiThread { shown = true }
        repeat(12) { tick(100) }
        assertEquals(initialNode, field().fetchSemanticsNode().id)
        val previousRejected = rejected
        tapWindow(compose.onNodeWithText("验证失败").fetchSemanticsNode().boundsInWindow.center)
        tick(16)
        assertEquals("New gestures must work after rapid reopen", previousRejected + 1, rejected)
        field().performClick()
        stableTitle = compose.onNodeWithText("输入诊断").fetchSemanticsNode().boundsInWindow
        tick(100); sample("rapid-reopen")
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        repeat(12) { tick(100) }
        assertFalse(shown)
        field().assertDoesNotExist()
        compose.runOnUiThread { shown = true }
        repeat(12) { tick(100) }
        field().performClick()
        field().assertIsFocused()
        compose.onNodeWithText("取消").performClick()
        repeat(12) { tick(100) }
        assertFalse(shown)
        assertEquals(0, writes)
        field().assertDoesNotExist()
        compose.runOnUiThread {
            assertNotEquals("Focus policy must restore after posted cleanup",
                android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS,
                (activity!!.get().window.decorView as android.view.ViewGroup).descendantFocusability)
        }
    }
}
