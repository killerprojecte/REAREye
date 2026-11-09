package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.*
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** Differential pixels, not screenshot aesthetics: foreground must not enter its own backdrop.
 * Whole-window PixelCopy and node captures are saved separately, never composited together.
 * Native Robolectric rendering is evidence for this renderer only, not physical-device proof.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassMenuSamplingIsolationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val foreground = mutableStateOf(Color.Red)
    private val bodyColor = mutableStateOf(Color(0xFF202020))
    private fun settle() { repeat(30) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }

    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close() }
    }

    private fun open(mode: MixColorSchemeMode) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            foreground.value = Color.Red
            bodyColor.value = Color(0xFF202020)
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(MixUiBackend.Mochi, mode) {
                    CompactGlassTopBarScene { bodyModifier ->
                        var expanded by remember { mutableStateOf(false) }
                        Scaffold(popupHost = {}, topBar = {
                            CompactGlassTopBar("Sampling probe", actions = {
                                GlassAnchoredMenu(expanded, { expanded = it }, emptyList(), {},
                                    width = 280.dp, menuContent = {
                                        Column(Modifier.fillMaxWidth().height(240.dp).testTag("sample-panel")) {
                                            Spacer(Modifier.height(96.dp))
                                            Box(Modifier.fillMaxWidth().height(48.dp).background(foreground.value))
                                        }
                                    }, anchor = {
                                        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("open")) { Text("+") }
                                    })
                            })
                        }) {
                            Box(Modifier.fillMaxSize().then(bodyModifier)) {
                                // Fixed background during foreground comparisons. No chart colors here.
                                Box(Modifier.fillMaxSize().background(bodyColor.value))
                            }
                        }
                    }
                }
            }
        }
        settle()
        compose.onNodeWithTag("open").performClick()
        settle()
    }

    private data class Shot(val window: Bitmap, val node: Bitmap, val x: Int, val y: Int)
    private fun capture(name: String): Shot {
        val target = compose.onNodeWithTag("sample-panel", useUnmergedTree = true)
        val position = target.fetchSemanticsNode().positionOnScreen
        // Capture the full window before any node extraction, avoiding synthesized composites.
        val origin = IntArray(2)
        val window = compose.runOnUiThread {
            activity!!.get().window.decorView.getLocationOnScreen(origin)
            captureWindowBitmap(activity!!.get().window)
        }
        val node = target.captureToImage().asAndroidBitmap()
        val dir = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"), "glass-menu-sampling")
        check(dir.mkdirs() || dir.isDirectory)
        for ((kind, bitmap) in listOf("window" to window, "node" to node)) {
            File(dir, "$name-$kind.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        return Shot(window, node, position.x.roundToInt() - origin[0], position.y.roundToInt() - origin[1])
    }

    private fun meanDifference(a: Shot, b: Shot, window: Boolean, top: Int, bottom: Int): Double {
        val first = if (window) a.window else a.node
        val second = if (window) b.window else b.node
        val ax = if (window) a.x else 0; val ay = if (window) a.y else 0
        val bx = if (window) b.x else 0; val by = if (window) b.y else 0
        var sum = 0L; var count = 0L
        for (y in top until bottom) for (x in 24 until a.node.width - 24) {
            val p = first.getPixel(ax + x, ay + y); val q = second.getPixel(bx + x, by + y)
            for (shift in listOf(0, 8, 16)) { sum += abs(((p shr shift) and 255) - ((q shr shift) and 255)); count++ }
        }
        return sum.toDouble() / count
    }

    @Test fun changedForegroundDoesNotColorGlassButChangedBodyDoes() {
        val failures = mutableListOf<String>()
        for (mode in listOf(MixColorSchemeMode.Light, MixColorSchemeMode.Dark)) {
            open(mode)
            val red = capture("${mode.name}-red")
            compose.runOnIdle { foreground.value = Color.Cyan }
            settle()
            val cyan = capture("${mode.name}-cyan")
            compose.runOnIdle { bodyColor.value = Color(0xFF5678A0) }
            settle()
            val bodyChanged = capture("${mode.name}-body-changed")
            for (window in listOf(true, false)) {
                val channel = if (window) "window" else "node"
                // Empty area 24px above the foreground chart, inside the panel perimeter.
                val haze = meanDifference(red, cyan, window, 36, 72)
                val chart = meanDifference(red, cyan, window, 104, 136)
                val body = meanDifference(cyan, bodyChanged, window, 36, 72)
                println("MENU_SAMPLING mode=$mode capture=$channel foregroundROI=$chart emptyROI=$haze bodyROI=$body")
                if (chart <= 30) failures += "$mode/$channel foreground mutation was not rendered: $chart"
                if (haze > 1.5) failures += "$mode/$channel foreground leaked into empty surface: $haze"
                if (body <= 2) failures += "$mode/$channel surface did not respond to its body source: $body"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
