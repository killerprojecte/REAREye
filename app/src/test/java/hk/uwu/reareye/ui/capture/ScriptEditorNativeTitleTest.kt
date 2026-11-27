package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import android.view.MotionEvent
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import kotlin.math.abs
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.LayoutInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.R
import hk.uwu.reareye.script.ScriptProject
import hk.uwu.reareye.script.ScriptProjectKind
import hk.uwu.reareye.script.ScriptProjectStore
import hk.uwu.reareye.ui.components.script.ScriptEditorContent
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real editor and file save using test-owned files. Native Lua execution is not exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptEditorNativeTitleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun settle() { repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun findEditor(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findEditor(view.getChildAt(i))?.let { return it }
        return null
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    @Test fun actualEditorSavesAndKeepsNativeBackAcrossThemes() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            var saved = 0; var backs = 0
            lateinit var file: File
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                val context = activity!!.get()
                context.enableEdgeToEdge()
                val directory = File(context.cacheDir, "editor-fixture-${backend.name}-$dark").apply { mkdirs() }
                file = File(directory, "fixture.lua").apply {
                    writeText((1..180).joinToString("\n") { "local item$it = \"colored source line $it\" -- comment" })
                }
                val project = ScriptProject("fixture", "Fixture", directory, file, ScriptProjectKind.STANDALONE)
                context.setContent {
                    MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                        ScriptEditorContent(project, ScriptProjectStore(context), 80.dp,
                            onBack = { backs++ }, onSaved = { saved++ },
                            loadProviders = { emptyList() },
                            runProvider = { _, _ -> error("Lua execution is not part of this UI fixture") })
                    }
                }
            }
            settle()
            val context = activity!!.get()
            compose.onNodeWithText("fixture.lua").assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.script_debug)).assertIsDisplayed()
            val editor = findEditor(context.window.decorView)
            assertNotNull("Actual Android code editor must be mounted", editor)
            if (backend == MixUiBackend.Miuix) verifyProgressiveEditor(checkNotNull(editor), dark)
            compose.runOnUiThread { editor!!.setText("-- edited fixture\nreturn {}\n") }
            settle()
            val folder = File(System.getProperty("ui.capture.output"), "script-native-editor").apply { mkdirs() }
            val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            File(folder, "${backend.name}-${if (dark) "dark" else "light"}.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            compose.onNodeWithContentDescription(context.getString(R.string.script_save)).performClick()
            settle()
            assertEquals("-- edited fixture\nreturn {}\n", file.readText())
            assertEquals(1, saved); assertEquals(1, backs)
            compose.onNodeWithContentDescription(context.getString(R.string.scene_route_navigate_back)).performClick()
            assertEquals(2, backs); assertEquals(1, saved)
        }
    }
    @Test fun mochiEditorTitleCentersBetweenActualControls() {
        for (width in listOf(400, 280)) for (rtl in listOf(false, true)) for (titleCase in listOf("short", "medium", "long")) {
            val name = when (titleCase) {
                "short" -> "main.lua"
                "medium" -> "settings_config.lua"
                else -> "a_very_long_script_filename_for_small_screens.lua"
            }
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                val context = activity!!.get()
                context.enableEdgeToEdge()
                val dir = File(context.cacheDir, "center-title").apply { mkdirs() }
                val file = File(dir, name).apply { writeText((1..100).joinToString("\n") { "-- Source line $it" }) }
                val project = ScriptProject("center", "Center", dir, file, ScriptProjectKind.STANDALONE)
                context.setContent {
                    MixUiHost(MixUiBackend.Mochi, MixColorSchemeMode.Light) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalLayoutDirection provides
                                if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
                            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.3f),
                        ) {
                            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(width.dp)) {
                                ScriptEditorContent(project, ScriptProjectStore(context), 80.dp, {}, {},
                                    loadProviders = { emptyList() }, runProvider = { _, _ -> error("No Lua execution") })
                            }
                        }
                    }
                }
            }
            settle()
            val context = activity!!.get()
            fun checkCenter() {
                val nav = compose.onNodeWithContentDescription(context.getString(R.string.scene_route_navigate_back))
                    .getUnclippedBoundsInRoot()
                val controls = listOf(R.string.script_debug, R.string.script_save).map {
                    compose.onNodeWithContentDescription(context.getString(it)).getUnclippedBoundsInRoot()
                }
                val title = compose.onNodeWithText(name, useUnmergedTree = true).getUnclippedBoundsInRoot()
                val left = if (rtl) controls.maxOf { it.right.value } else nav.right.value
                val right = if (rtl) nav.left.value else controls.minOf { it.left.value }
                assertTrue("Title must avoid controls", title.left.value >= left && title.right.value <= right)
                val viewportCenter = width / 2f
                val halfTitle = (title.right.value - title.left.value) / 2f
                val expected = if (viewportCenter - halfTitle >= left + 8f && viewportCenter + halfTitle <= right - 8f)
                    viewportCenter else (left + right) / 2f
                assertEquals("Prefer whole-bar center, otherwise center remaining area width=$width rtl=$rtl title=$titleCase",
                    expected, (title.left.value + title.right.value) / 2f, 1f)
            }
            val folder = File(System.getProperty("ui.capture.output"), "mochi-editor-center").apply { mkdirs() }
            fun capture(state: String) {
                val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                File(folder, "$width-rtl$rtl-$titleCase-$state.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            }
            capture("top")
            checkCenter()
            val geometry = compose.nativeTitleGeometry(name).single()
            writeNativeTitleGeometry("mochi-editor-center", "$width-rtl$rtl-$titleCase", listOf(geometry))
            if (width == 400 && titleCase == "medium") {
                assertFalse("Medium title fits free interval without ellipsis", geometry.layout.isLineEllipsized(0))
                assertTrue("Medium title exercises free-area centering rather than whole-bar center", abs(geometry.bounds.center.x - 200f) > 10f)
            }
            drag(checkNotNull(findEditor(context.window.decorView)), up = true)
            checkCenter()
            capture("scrolled")
        }
    }
    private fun drag(editor: CodeEditor, up: Boolean) {
        val startTime = SystemClock.uptimeMillis()
        val x = editor.width * 0.65f
        val low = editor.height * 0.85f
        val high = editor.height * 0.25f
        fun event(action: Int, y: Float) {
            compose.runOnUiThread {
                val motion = MotionEvent.obtain(startTime, SystemClock.uptimeMillis(), action, x, y, 0)
                try { assertTrue("Sora must consume the native pointer event", editor.dispatchTouchEvent(motion)) }
                finally { motion.recycle() }
            }
        }
        val from = if (up) low else high
        val to = if (up) high else low
        event(MotionEvent.ACTION_DOWN, from)
        repeat(24) { index ->
            // Compose's animation clock does not advance Android's scroller clock
            // one-for-one. Dispatch using current uptime after a real native frame.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
            event(MotionEvent.ACTION_MOVE,
                from + (to - from) * (index + 1) / 24f)
            compose.mainClock.advanceTimeBy(30); compose.waitForIdle()
            compose.runOnUiThread {
                captureWindowBitmap(activity!!.get().window).recycle()
            }
        }
        event(MotionEvent.ACTION_UP, to)
        settle()
    }

    private fun verifyProgressiveEditor(editor: CodeEditor, dark: Boolean) {
        val initial = IntArray(2).also { editor.getLocationInWindow(it) }
        assertEquals(0, editor.offsetY)
        val title = compose.onNodeWithText("fixture.lua").fetchSemanticsNode().boundsInWindow
        assertTrue("First source line must start below the pinned title", initial[1] > title.bottom)
        assertProgressiveBand(visible = false)
        val before = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        assertTrue("Native layout must have a real scroll range", editor.scrollMaxY > initial[1])
        drag(editor, up = true)
        assertTrue("A real native drag must scroll Sora", editor.offsetY > initial[1])
        val scrolled = IntArray(2).also { editor.getLocationInWindow(it) }
        assertTrue("The real native source must extend beneath the title", scrolled[1] < title.top)
        val collapsedHeight = editor.height
        drag(editor, up = true)
        assertEquals("Once the initial spacer is consumed the viewport stays stable", collapsedHeight, editor.height)
        assertProgressiveBand(visible = true)
        val current = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        val originalColor = editor.colorScheme.getColor(EditorColorScheme.WHOLE_BACKGROUND)
        compose.runOnUiThread {
            // Change real AndroidView pixels, never the Compose backdrop or scroll flag.
            editor.colorScheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, android.graphics.Color.rgb(40, 180, 90))
            editor.invalidate()
        }
        settle()
        val changed = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        val yStart = title.top.toInt().coerceAtLeast(0)
        val yEnd = title.bottom.toInt().coerceAtMost(current.height)
        var sum = 0L; var count = 0
        // Blank strip between left navigation control and centered filename.
        for (y in yStart until yEnd) for (x in 78 until 112) {
            val a = current.getPixel(x, y); val b = changed.getPixel(x, y)
            for (shift in listOf(0, 8, 16)) { sum += abs(((a shr shift) and 255) - ((b shr shift) and 255)); count++ }
        }
        val delta = sum.toDouble() / count
        println("SCRIPT_NATIVE_PROGRESSIVE dark=$dark sourceDelta=$delta scroll=${editor.offsetY}")
        assertTrue("The toolbar must sample changed Sora pixels, not an empty Compose background: $delta", delta > 8.0)
        val folder = File(System.getProperty("ui.capture.output"), "script-native-editor").apply { mkdirs() }
        listOf("top" to before, "scrolled" to current, "changed-source" to changed).forEach { (name, bitmap) ->
            File(folder, "Miuix-$dark-progressive-$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        compose.runOnUiThread { editor.colorScheme.setColor(EditorColorScheme.WHOLE_BACKGROUND, originalColor) }
        repeat(35) { if (editor.offsetY > 0) drag(editor, up = false) }
        settle()
        assertEquals("Real reverse drags must restore the first line", 0, editor.offsetY)
        assertProgressiveBand(visible = false)
        val restored = IntArray(2).also { editor.getLocationInWindow(it) }
        assertEquals("Restoring the top must restore the first-line clearance", initial[1], restored[1])
        // Verify a real local coordinate still places the cursor on the intended visible row.
        val y = editor.getRowHeight() * 1.5f
        val t = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
            compose.runOnUiThread {
                val event = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, editor.width * 0.5f, y, 0)
                try { editor.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        }
        settle()
        assertEquals("Touch coordinates must still select the second source line", 1, editor.cursor.leftLine)
    }

    private fun assertProgressiveBand(visible: Boolean) {
        val root = generateSequence(compose.onNodeWithText("fixture.lua")
            .fetchSemanticsNode().layoutInfo) { it.parentInfo }.last()
        compose.runOnIdle {
            // Source-dependent pixels alone also pass for a transparent toolbar.
            // Require a real, enabled native progressive renderer and its visible layer.
            val band = layoutDescendants(root).firstOrNull { layout ->
                layout.getModifierInfo().any { info ->
                    val element = info.modifier
                    element.javaClass.name == "top.yukonga.miuix.kmp.blur.DrawBackdropElement" &&
                        element.javaClass.getDeclaredField("progressiveGradient")
                            .apply { isAccessible = true }.get(element) != null &&
                        element.javaClass.getDeclaredField("enabled")
                            .apply { isAccessible = true }.getBoolean(element)
                }
            } ?: error("The editor must use an enabled native progressive backdrop renderer")
            val layer = band.getModifierInfo().map { it.modifier }.first {
                it.javaClass.name == "androidx.compose.ui.graphics.BlockGraphicsLayerElement"
            }
            @Suppress("UNCHECKED_CAST")
            val block = layer.javaClass.getDeclaredField("block").apply { isAccessible = true }
                .get(layer) as (androidx.compose.ui.graphics.GraphicsLayerScope) -> Unit
            val scope = Class.forName("androidx.compose.ui.graphics.ReusableGraphicsLayerScope")
                .getDeclaredConstructor().newInstance() as androidx.compose.ui.graphics.GraphicsLayerScope
            block(scope)
            assertEquals("Progressive band visibility must follow actual native scrolling",
                if (visible) 1f else 0f, scope.alpha, 0.001f)
        }
    }

    private fun layoutDescendants(node: LayoutInfo): Sequence<LayoutInfo> = sequence {
        yield(node)
        val method = node.javaClass.methods.first {
            it.name.startsWith("getChildren$") && it.parameterCount == 0
        }
        @Suppress("UNCHECKED_CAST")
        val children = method.invoke(node) as List<LayoutInfo>
        children.forEach { yieldAll(layoutDescendants(it)) }
    }

}
