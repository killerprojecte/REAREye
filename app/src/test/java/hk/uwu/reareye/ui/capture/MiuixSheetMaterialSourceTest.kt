package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.components.GlassBottomSheet
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.NativeSheetSamplingProbe
import hk.uwu.mixui.components.GlassTabs
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.AppThemeMode
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.math.*

/** Synthetic optical gate, NOT a real-page or device compatibility claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixSheetMaterialSourceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var phase by mutableStateOf(false)
    private var foregroundPhase by mutableStateOf(false)
    private var lowFrequency by mutableStateOf(false)
    private var directDimmed by mutableStateOf(false)
    private var bandWidth by mutableIntStateOf(32)
    private var directBounds by mutableStateOf<Rect?>(null)
    private var geometryMode by mutableStateOf(false)
    private var probeRtl by mutableStateOf(false)
    private var headerHeight by mutableIntStateOf(44)
    private var horizontalPadding by mutableIntStateOf(24)
    private val tabs = listOf("配置", "外观")
    private val output get() = File(System.getProperty("ui.capture.output") ?: "build/reports/ui-capture",
        "miuix-sheet-material-source").apply { mkdirs() }

    @Before fun requireNativeCapture() {
        assertTrue("Run with -PuiCapture=true, do not silently skip this gate",
            java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }

    private fun settle() {
        repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }

    private fun open(dark: Boolean, geometry: Boolean = false, rtl: Boolean = false, openingPhase: Boolean? = null) {
        compose.runOnUiThread {
            activity?.close()
            geometryMode = geometry; probeRtl = rtl; headerHeight = 44; horizontalPadding = 24
            visible = true; phase = openingPhase ?: false; foregroundPhase = false
            lowFrequency = openingPhase != null; directDimmed = false; bandWidth = 32; directBounds = null
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides if (probeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                AppTheme(themeMode = if (dark) AppThemeMode.MIUIX_DARK else AppThemeMode.MIUIX_LIGHT) {
                    Box(Modifier.fillMaxSize().testTag("material-probe-window")) {
                        Scaffold(Modifier.fillMaxSize()) {
                            GlassBottomSheetScene {
                                NativeSheetSamplingProbe(phase, lowFrequency, directDimmed, bandWidth, directBounds, tabs) {
                                GlassBottomSheet(show = visible, onDismissRequest = { visible = false },
                                    title = "材质取样诊断", horizontalPadding = horizontalPadding.dp,
                                    startAction = if (geometryMode) ({
                                        Box(Modifier.width(44.dp).height(headerHeight.dp).testTag("changing-header-action"))
                                    }) else null) {
                                    Column(Modifier.fillMaxWidth().height((if (geometryMode) 384 - headerHeight else 340).dp).testTag("material-probe-content")) {
                                        Canvas(Modifier.fillMaxWidth().height(20.dp)) {
                                            drawRect(if (foregroundPhase) Color.Magenta else Color.Green)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        GlassTabs(tabs, 1, {}, Modifier.fillMaxWidth())
                                        Spacer(Modifier.height(4.dp))
                                        Canvas(Modifier.fillMaxWidth().height(80.dp).testTag("foreground-change-witness")) {
                                            drawRect(if (foregroundPhase) Color.Cyan else Color.Red)
                                            for (x in 0..size.width.toInt() step 12) {
                                                drawRect(if (foregroundPhase) Color.White else Color.Black,
                                                    Offset(x.toFloat(), 0f), Size(4f, size.height))
                                            }
                                        }
                                    }
                                }
                                }
                            }
                        }
                    }
                }
                }
            }
        }
        if (openingPhase != null) return
        settle()
        compose.onNodeWithTag("glass-tabs-miuix", useUnmergedTree = true).assertIsDisplayed()
        assertFalse("Native overlay must remain in the Activity window", ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    private fun root() = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag(
        if (visible) "material-probe-content" else "material-probe-window")), useUnmergedTree = true)
    private fun track() = compose.onNodeWithTag(if (visible) "glass-tabs-miuix" else "direct-native-track", useUnmergedTree = true)
    private fun rect(node: SemanticsNodeInteraction): Rect {
        val b = node.fetchSemanticsNode().boundsInWindow
        val origin = root().fetchSemanticsNode().boundsInWindow
        return Rect(b.left - origin.left, b.top - origin.top, b.right - origin.left, b.bottom - origin.top)
    }
    private fun capture(name: String): Bitmap {
        val image = root().captureToImage().asAndroidBitmap()
        File(output, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return image
    }
    private fun pixels(bounds: Rect): List<Pair<Int, Int>> {
        val origin = root().fetchSemanticsNode().boundsInWindow
        val text = compose.onAllNodesWithText(tabs.first(), useUnmergedTree = true).fetchSemanticsNodes().map {
            val b = it.boundsInWindow
            Rect(b.left - origin.left - 4, b.top - origin.top - 4,
                b.right - origin.left + 4, b.bottom - origin.top + 4)
        }
        return buildList {
            for (y in ceil(bounds.top + 8).toInt() until floor(bounds.bottom - 8).toInt()) {
                for (x in ceil(if (probeRtl) bounds.center.x + 12 else bounds.left + 18).toInt()
                    until floor(if (probeRtl) bounds.right - 18 else bounds.center.x - 12).toInt()) {
                    if (text.none { it.contains(Offset(x.toFloat(), y.toFloat())) }) add(x to y)
                }
            }
        }.also { assertTrue("Need >=200 label-free unselected pixels, got ${it.size}", it.size >= 200) }
    }
    private fun luminance(p: Int) = (android.graphics.Color.red(p) * .2126 +
        android.graphics.Color.green(p) * .7152 + android.graphics.Color.blue(p) * .0722) / 255.0
    private fun energy(a: Bitmap, b: Bitmap, mask: List<Pair<Int, Int>>): Double {
        val d = mask.map { (x, y) -> luminance(a.getPixel(x, y)) - luminance(b.getPixel(x, y)) }
        val mean = d.average()
        return sqrt(d.sumOf { (it - mean).pow(2) } / d.size)
    }
    private fun delta(a: Bitmap, b: Bitmap, mask: List<Pair<Int, Int>>) = mask.map { (x, y) ->
        val p = a.getPixel(x, y); val q = b.getPixel(x, y)
        (abs(android.graphics.Color.red(p) - android.graphics.Color.red(q)) +
            abs(android.graphics.Color.green(p) - android.graphics.Color.green(q)) +
            abs(android.graphics.Color.blue(p) - android.graphics.Color.blue(q))) / (3.0 * 255)
    }.average()
    private fun pair(name: String): Pair<Bitmap, Bitmap> {
        compose.runOnUiThread { phase = false }; settle(); val a = capture("$name-a")
        compose.runOnUiThread { phase = true }; settle(); return a to capture("$name-b")
    }
    private fun sameRect(expected: Rect) {
        val actual = rect(track())
        assertTrue("Window ROI moved: $expected -> $actual", abs(actual.left - expected.left) < 1 &&
            abs(actual.top - expected.top) < 1 && abs(actual.width - expected.width) < 1 && abs(actual.height - expected.height) < 1)
    }

    @Test fun nativeSheetTabsRejectPageShortcutAndForegroundFeedbackAcrossFrequencies() {
        for (dark in listOf(false, true)) {
            open(dark)
            val bounds = rect(track()); val mask = pixels(bounds)
            val frequencies = listOf(8, 16, 32, 64, 128)
            val sheet = linkedMapOf<Int, Double>()
            frequencies.forEach { band ->
                compose.runOnUiThread { bandWidth = band }
                val images = pair("$dark-sheet-$band"); sameRect(bounds)
                sheet[band] = energy(images.first, images.second, mask)
                images.first.recycle(); images.second.recycle()
            }
            compose.runOnUiThread { lowFrequency = true }
            val low = pair("$dark-low-frequency")
            val lowResponse = delta(low.first, low.second, mask)
            low.first.recycle()
            settle(); val stable = capture("$dark-stable")
            val drift = delta(low.second, stable, mask)
            compose.runOnUiThread { foregroundPhase = true }; settle()
            val changed = capture("$dark-foreground-only")
            val witness = rect(compose.onNodeWithTag("foreground-change-witness"))
            val witnessMask = buildList {
                for (y in ceil(witness.top + 8).toInt() until floor(witness.bottom - 8).toInt() step 4)
                    for (x in ceil(witness.left + 8).toInt() until floor(witness.right - 8).toInt() step 4) add(x to y)
            }
            assertTrue("Foreground witness must actually change, not a frozen fixture",
                witnessMask.isNotEmpty() && delta(stable, changed, witnessMask) > 32.0 / 255)
            val feedback = delta(stable, changed, mask)
            low.second.recycle(); stable.recycle(); changed.recycle()

            compose.runOnUiThread { visible = false; lowFrequency = false }; settle()
            val fixture = rect(compose.onNodeWithTag("material-probe-window"))
            compose.runOnUiThread {
                directBounds = Rect(bounds.left - fixture.left, bounds.top - fixture.top,
                    bounds.right - fixture.left, bounds.bottom - fixture.top)
            }
            settle(); sameRect(bounds)
            val direct = linkedMapOf<Int, Double>()
            val dimmed = linkedMapOf<Int, Double>()
            frequencies.forEach { band ->
                compose.runOnUiThread { bandWidth = band }
                val images = pair("$dark-direct-$band"); sameRect(bounds)
                direct[band] = energy(images.first, images.second, mask)
                images.first.recycle(); images.second.recycle()
                compose.runOnUiThread { directDimmed = true }
                val tinted = pair("$dark-direct-scrim-$band"); sameRect(bounds)
                dimmed[band] = energy(tinted.first, tinted.second, mask)
                tinted.first.recycle(); tinted.second.recycle()
                compose.runOnUiThread { directDimmed = false }
            }
            val eligible = frequencies.filter { direct.getValue(it) > 4.0 / 255 && dimmed.getValue(it) > 4.0 / 255 }
            val ratios = eligible.associateWith { sheet.getValue(it) / direct.getValue(it) }
            val dimmedRatios = eligible.associateWith { sheet.getValue(it) / dimmed.getValue(it) }
            val report = "dark=$dark rect=$bounds pixels=${mask.size} sheet=$sheet direct=$direct " +
                "dimmed=$dimmed eligible=$eligible ratios=$ratios dimmedRatios=$dimmedRatios low=$lowResponse drift=$drift foreground=$feedback"
            println("MIUIX_SHEET_SOURCE $report")
            File(output, "$dark-measurement.txt").writeText(report + "\n")
            // Preset optical contract, not tuned to an observed screenshot:
            // select >=2 genuinely resolved negative-control frequencies, require
            // >=20% attenuation at two. Uniform tint cannot satisfy centered RMS
            // alone; the separate hue-changing flat field excludes opaque cheats.
            // No resolved control is an INCONCLUSIVE fixture FAILURE, never skip.
            assertTrue("INCONCLUSIVE: fewer than two resolved control bands: $report", eligible.size >= 2)
            assertTrue("Tabs must differ from direct P at >=2 resolved bands: $report", ratios.values.count { it < .8 } >= 2)
            assertTrue("Adding only scrim is insufficient: $report", dimmedRatios.values.count { it < .8 } >= 2)
            assertTrue("Opaque fallback cannot masquerade as material sampling: $report", lowResponse > 1.0 / 255)
            assertTrue("Stable source must not drift: $report", drift < 1.0 / 255)
            assertTrue("Foreground must never feed the Tab source: $report", feedback < 1.0 / 255)
        }
    }

    @Test fun changedHeaderAndPaddingMatchFreshSheetAtSamePanelSize() {
        for (rtl in listOf(false, true)) {
            open(dark = false, geometry = true, rtl = rtl)
            compose.runOnUiThread { bandWidth = 128 }; settle()
            val panelBefore = rect(compose.onNodeWithTag("mixui-native-glass-sheet", useUnmergedTree = true))
            val originalTrack = rect(track())
            // Trade equal height between header and body: panel size must NOT
            // change, otherwise size state could mask a stale anchor-offset bug.
            compose.runOnUiThread { headerHeight = 92; horizontalPadding = 16 }; settle()
            val panelAfter = rect(compose.onNodeWithTag("mixui-native-glass-sheet", useUnmergedTree = true))
            assertTrue("Fixture requires unchanged panel bounds: $panelBefore -> $panelAfter",
                abs(panelBefore.left - panelAfter.left) < 1 && abs(panelBefore.top - panelAfter.top) < 1 &&
                abs(panelBefore.width - panelAfter.width) < 1 && abs(panelBefore.height - panelAfter.height) < 1)
            val changedTrack = rect(track())
            assertTrue("Header actually moved the track", changedTrack.top - originalTrack.top > 40)
            assertTrue("Horizontal padding actually changed the track", changedTrack.width - originalTrack.width > 12)
            val mask = pixels(changedTrack)
            val changed = pair("geometry-$rtl-mutated")
            // Reopening creates a new native source at the exact same final
            // geometry. Compare against it, not an assumed material/color value.
            compose.runOnUiThread { visible = false }; settle()
            compose.runOnUiThread { visible = true }; settle(); sameRect(changedTrack)
            val fresh = pair("geometry-$rtl-fresh")
            val changedEnergy = energy(changed.first, changed.second, mask)
            val freshEnergy = energy(fresh.first, fresh.second, mask)
            val phaseAError = delta(changed.first, fresh.first, mask)
            val phaseBError = delta(changed.second, fresh.second, mask)
            val report = "rtl=$rtl changedEnergy=$changedEnergy freshEnergy=$freshEnergy phaseA=$phaseAError phaseB=$phaseBError"
            println("MIUIX_SHEET_GEOMETRY $report")
            File(output, "geometry-$rtl-measurement.txt").writeText(report + "\n")
            changed.first.recycle(); changed.second.recycle(); fresh.first.recycle(); fresh.second.recycle()
            assertTrue("INCONCLUSIVE: spatial probe must have measurable response: $report", freshEnergy > 1.0 / 255)
            assertTrue("Mutated header/inset must match fresh source coordinates: $report",
                phaseAError < 1.0 / 255 && phaseBError < 1.0 / 255)
        }
    }

    @Test fun openingFramesHaveMaterialResponseWhenHeaderBecomesVisible() {
        data class Frame(val index: Int, val bitmap: Bitmap, val bounds: Rect, val contrast: Double)
        for (dark in listOf(false, true)) {
            fun frames(pagePhase: Boolean): List<Frame> {
                open(dark, openingPhase = pagePhase)
                val frames = mutableListOf<Frame>()
                repeat(32) { index ->
                    compose.mainClock.advanceTimeBy(16)
                    compose.waitForIdle()
                    val nodes = compose.onAllNodesWithTag("glass-tabs-miuix", useUnmergedTree = true).fetchSemanticsNodes()
                    if (nodes.isNotEmpty()) {
                        val bounds = rect(track())
                        if (bounds.top >= 0 && bounds.bottom <= 800 && bounds.height >= 30) {
                            val image = capture("opening-$dark-$pagePhase-$index")
                            val title = rect(compose.onNodeWithText("材质取样诊断", useUnmergedTree = true))
                            val shades = buildList {
                                for (y in ceil(title.top).toInt() until floor(title.bottom).toInt())
                                    for (x in ceil(title.left).toInt() until floor(title.right).toInt())
                                        if (x in 0 until image.width && y in 0 until image.height)
                                            add(luminance(image.getPixel(x, y)))
                            }
                            frames += Frame(index, image, bounds, (shades.maxOrNull() ?: 0.0) - (shades.minOrNull() ?: 0.0))
                        }
                    }
                }
                return frames
            }
            val first = frames(false)
            val second = frames(true).associateBy { it.index }
            val reports = mutableListOf<String>()
            var visibleFrames = 0
            first.forEach { a ->
                val b = second[a.index] ?: return@forEach
                assertTrue("Opening geometry must be deterministic at frame ${a.index}",
                    abs(a.bounds.top - b.bounds.top) < 1 && abs(a.bounds.left - b.bounds.left) < 1)
                val response = delta(a.bitmap, b.bitmap, pixels(a.bounds))
                val meaningful = a.contrast > .2 && b.contrast > .2
                reports += "frame=${a.index} contrastA=${a.contrast} contrastB=${b.contrast} response=$response meaningful=$meaningful"
                if (meaningful) {
                    visibleFrames++
                    assertTrue("Visible native header must not precede initialized material: ${reports.last()}", response > 1.0 / 255)
                }
            }
            File(output, "opening-$dark-measurement.txt").writeText(reports.joinToString("\n"))
            first.forEach { it.bitmap.recycle() }; second.values.forEach { it.bitmap.recycle() }
            assertTrue("Need visible opening frames, not an empty or skipped fixture", visibleFrames >= 10)
        }
    }

    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
}
