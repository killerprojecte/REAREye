package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.effect.glass.MochiGlassSurface
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBackdrop
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.theme.*
import org.junit.Before
import org.junit.Assume.assumeTrue
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Public Maven glass materials in the MixUI sheet host, not a surface model. Requires native graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MochiLargeSheetGlassTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var alternate by mutableStateOf(false)
    private var saved: ThemeStyleDraft? = null
    private var saves = 0

    @Before fun requireCapture() { assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled")) }

    private fun settle() {
        repeat(24) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }
    private fun open(dark: Boolean, style: AppMochiThemeStyle) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            visible = true; alternate = false; saved = null; saves = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                val mode = if (dark) AppThemeMode.MOCHI_DARK else AppThemeMode.MOCHI_LIGHT
                AppTheme(themeMode = mode) {
                    Scaffold(Modifier.fillMaxSize()) {
                        GlassBottomSheetScene {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(if (alternate) Color.Green else Color.Red)
                                drawRect(if (alternate) Color.Yellow else Color.Blue,
                                    topLeft = Offset(size.width / 2f, 0f),
                                    size = Size(size.width / 2f, size.height))
                            }
                            ThemeStyleSheet(show = visible, appliedMode = mode,
                                appliedStyle = style,
                                onDismiss = { visible = false },
                                onSave = { saved = it; saves++; visible = false })
                        }
                    }
                }
            }
        }
        settle()
        compose.onNodeWithTag("theme-style-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("glass-tabs-mochi").assertCountEquals(3)
        assertFalse("MixUI sheet host must share the Activity window", ShadowDialog.getLatestDialog()?.isShowing == true)
    }
    private fun frame(): Bitmap = compose.onNode(
        isRoot() and hasAnyDescendant(hasTestTag("theme-style-sheet")), useUnmergedTree = true
    ).captureToImage().asAndroidBitmap()
    private fun distance(a: Int, b: Int): Int =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
    private fun capture(name: String, image: Bitmap) {
        val output = System.getProperty("ui.capture.output") ?: return
        val folder = File(output, "mochi-large-sheet-glass").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun assertMaterialNotJustTransparent(bitmap: Bitmap) {
        // The bottom tenth is empty sheet body, away from controls and navigation.
        // A plain transparent surface + uniform scrim preserves a sharp color seam;
        // the real glass softens it. Sample several rows, not one lucky pixel.
        val x = bitmap.width / 2
        var near = 0L; var far = 0L
        for (y in bitmap.height * 86 / 100 until bitmap.height * 91 / 100) {
            near += distance(bitmap.getPixel(x - 2, y), bitmap.getPixel(x + 2, y))
            far += distance(bitmap.getPixel(x - 40, y), bitmap.getPixel(x + 40, y))
        }
        assertTrue("Page colors must remain distinguishable through Large glass ($far)", far > 200)
        assertTrue("Glass must blur the seam, not merely expose a transparent page ($near/$far)", near < far * 0.8)
    }
    @Test fun stableLargeSamplesChangingPageInAllSixPalettes() {
        for (dark in listOf(false, true)) for (style in AppMochiThemeStyle.entries) {
            open(dark, style)
            val first = frame()
            val contentTop = compose.onNodeWithTag("theme-style-sheet").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Keep Large height, never substitute Medium", contentTop < first.height * 0.3f)
            assertMaterialNotJustTransparent(first)
            capture("${style.name}-$dark-background-a", first)
            // No close/reopen: source must update under an already settled Large sheet.
            compose.runOnUiThread { alternate = true }; settle()
            val second = frame()
            assertMaterialNotJustTransparent(second)
            capture("${style.name}-$dark-background-b", second)
            val y = first.height * 88 / 100
            assertTrue("Settled glass must resample the changed page",
                distance(first.getPixel(first.width / 4, y), second.getPixel(second.width / 4, y)) > 15)
            settle()
            val stable = frame()
            assertTrue("Measurement is at rest, not only during the opening transition",
                distance(second.getPixel(second.width / 4, y), stable.getPixel(stable.width / 4, y)) < 5)
        }
    }
    @Test fun cancelDiscardsDraftAndSaveCommitsExactlyOnce() {
        open(false, AppMochiThemeStyle.Mochi)
        fun label(id: Int) = activity!!.get().getString(id)
        fun chooseNothing() {
            compose.onNode(hasClickAction() and (hasText("Nothing") or hasAnyDescendant(hasText("Nothing"))) and
                hasAnyAncestor(hasTestTag("theme-palette-tabs")), useUnmergedTree = true).performClick()
            settle()
        }
        chooseNothing()
        compose.onNodeWithContentDescription(label(R.string.theme_editor_close)).performClick(); settle()
        assertFalse(visible); assertNull(saved); assertEquals(0, saves)
        compose.runOnUiThread { visible = true }; settle()
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick(); settle()
        assertEquals(AppMochiThemeStyle.Mochi, saved?.mochiStyle)
        assertEquals(1, saves); assertFalse(visible)
        compose.runOnUiThread { visible = true }; settle()
        chooseNothing()
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick(); settle()
        assertEquals(AppMochiThemeStyle.Nothing, saved?.mochiStyle)
        assertEquals(2, saves); assertFalse(visible)
    }

    // All dimensions below are physical pixels, deliberately independent of dp.
    // The two phases have identical mean luminance. A change of overall tint is
    // therefore not evidence that a track sampled the correct surface.
    private var directControlBounds by mutableStateOf<Rect?>(null)
    private var flatProbe by mutableStateOf(false)

    private fun openSamplingFixture(dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            visible = true; alternate = false; directControlBounds = null; flatProbe = false
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                val mode = if (dark) AppThemeMode.MOCHI_DARK else AppThemeMode.MOCHI_LIGHT
                AppTheme(themeMode = mode) {
                    Box(Modifier.fillMaxSize().testTag("sampling-fixture")) {
                        Scaffold(Modifier.fillMaxSize()) {
                            GlassBottomSheetScene {
                                val source = rememberMochiBackdrop(Color.Black)
                                val scrim = MochiDefaults.scrim()
                                // Source contains ONLY the page, never the control.
                                Canvas(Modifier.fillMaxSize().mochiBackdropSource(source)) {
                                    drawRect(if (alternate) Color.White else Color.Black)
                                    // Diagonal 16px bands make both x and y relevant to
                                    // source registration, without hundreds of thousands
                                    // of per-pixel drawing commands in native Skia.
                                    for (band in 0..ceil((size.width + size.height) / 16f).toInt()) {
                                        if (band % 2 != 0) continue
                                        val edge = band * 16f
                                        drawPath(Path().apply {
                                            moveTo(edge, 0f); lineTo(edge + 16f, 0f)
                                            lineTo(edge + 16f - size.height, size.height)
                                            lineTo(edge - size.height, size.height); close()
                                        }, if (alternate) Color.Black else Color.White)
                                    }
                                    if (flatProbe) drawRect(if (alternate) Color(0xFFFFC000) else Color(0xFF0030FF))
                                    // A readable card-like witness in screenshots, outside
                                    // measured rows. The numerical oracle uses the stripes.
                                    drawContext.canvas.nativeCanvas.drawText("CARDS 0123456789",
                                        24f, 60f, android.graphics.Paint().apply {
                                            color = android.graphics.Color.MAGENTA
                                            textSize = 24f; isAntiAlias = true
                                        })
                                    // Match B = page + settled native scrim for the direct
                                    // negative control after the sheet has been dismissed.
                                    if (directControlBounds != null) drawRect(scrim)
                                }
                                directControlBounds?.let { bounds ->
                                    val density = LocalDensity.current
                                    val material = checkNotNull(MochiTheme.materials.controlGlass)
                                    MochiGlassSurface(
                                        modifier = Modifier.offset {
                                            IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt())
                                        }.size(with(density) { bounds.width.toDp() },
                                            with(density) { bounds.height.toDp() })
                                            .testTag("direct-page-control"),
                                        backdrop = source,
                                        material = material.copy(
                                            light = material.light.copy(shadowOpacity = 0f),
                                            dark = material.dark.copy(shadowOpacity = 0f)),
                                    ) {
                                        ui.feice.mochi.basic.MochiSegmentedControl(
                                            items = listOf("MIUIX", "Mochi").toImmutableList(),
                                            selectedIndex = 1, onSelect = {},
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = ui.feice.mochi.basic.MochiSegmentedDefaults.colors(
                                                track = Color.Transparent),
                                        )
                                    }
                                }
                                ThemeStyleSheet(show = visible, appliedMode = mode,
                                    appliedStyle = AppMochiThemeStyle.Mochi,
                                    onDismiss = { visible = false }, onSave = {})
                            }
                        }
                    }
                }
            }
        }
        settle()
        compose.onAllNodesWithTag("glass-tabs-mochi").assertCountEquals(3)
        assertFalse("Sheet and page must remain in one Activity window",
            ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    private fun samplingRoot() = compose.onNode(
        isRoot() and hasAnyDescendant(hasTestTag(
            if (visible) "theme-style-sheet" else "sampling-fixture")), useUnmergedTree = true)

    private fun samplingFrame(): Bitmap = samplingRoot().captureToImage().asAndroidBitmap()

    private fun samplingTrack() = compose.onNode(
        hasTestTag("glass-tabs-mochi") and hasAnyAncestor(hasTestTag("theme-style-tabs")),
        useUnmergedTree = true)

    private fun bitmapBounds(node: SemanticsNodeInteraction): Rect {
        // Never mix boundsInRoot from different Compose roots / overlay owners.
        // Window coordinates are the common currency, translated into this capture.
        val root = samplingRoot().fetchSemanticsNode().boundsInWindow
        val bounds = node.fetchSemanticsNode().boundsInWindow
        return Rect(bounds.left - root.left, bounds.top - root.top,
            bounds.right - root.left, bounds.bottom - root.top)
    }

    private data class SampleMask(val bounds: Rect, val pixels: List<Pair<Int, Int>>)

    private fun sampleMask(): SampleMask {
        val bounds = bitmapBounds(samplingTrack())
        val root = samplingRoot().fetchSemanticsNode().boundsInWindow
        val labels = compose.onAllNodes(hasText("MIUIX"), useUnmergedTree = true)
            .fetchSemanticsNodes().map { node ->
                val b = node.boundsInWindow
                Rect(b.left - root.left - 4, b.top - root.top - 4,
                    b.right - root.left + 4, b.bottom - root.top + 4)
            }
        // Only first (UNSELECTED) segment. Avoid the capsule lens/outline at
        // the outside, the selected thumb and all label bounds including padding.
        val pixels = buildList {
            for (y in ceil(bounds.top + 10).toInt() until floor(bounds.bottom - 10).toInt()) {
                for (x in ceil(bounds.left + 18).toInt() until floor(bounds.center.x - 12).toInt()) {
                    if (labels.none { it.contains(Offset(x.toFloat(), y.toFloat())) }) add(x to y)
                }
            }
        }
        assertTrue("Need a nontrivial label-free, unselected sample: ${pixels.size}", pixels.size >= 250)
        return SampleMask(bounds, pixels)
    }

    private fun luminance(pixel: Int): Double =
        (android.graphics.Color.red(pixel) * 0.2126 +
            android.graphics.Color.green(pixel) * 0.7152 +
            android.graphics.Color.blue(pixel) * 0.0722) / 255.0

    private fun stripeResponse(first: Bitmap, inverted: Bitmap, mask: SampleMask): Double {
        val difference = mask.pixels.map { (x, y) ->
            assertTrue("Sample must be inside the same captured window", x in 0 until first.width && y in 0 until first.height)
            luminance(first.getPixel(x, y)) - luminance(inverted.getPixel(x, y))
        }
        // Centered phase-difference RMS removes static foreground and uniform
        // tint. No means-of-colors assertion can distinguish B from S here.
        val mean = difference.average()
        return sqrt(difference.sumOf { (it - mean) * (it - mean) } / difference.size)
    }

    private fun phasePair(name: String): Pair<Bitmap, Bitmap> {
        compose.runOnUiThread { alternate = false }; settle()
        val first = samplingFrame(); capture("$name-phase-a", first)
        compose.runOnUiThread { alternate = true }; settle()
        val second = samplingFrame(); capture("$name-phase-b", second)
        return first to second
    }

    private fun assertSheetSource(dark: Boolean, drag: Boolean) {
        openSamplingFixture(dark)
        val restingBounds = bitmapBounds(samplingTrack())
        if (drag) {
            val contentTop = bitmapBounds(compose.onNodeWithTag("theme-style-sheet")).top
            // Hold the gesture: do not release into a spring before capturing.
            samplingRoot().performTouchInput {
                down(Offset(width / 2f, contentTop - 24f))
                moveBy(Offset(0f, 64f), delayMillis = 200)
            }
            compose.waitForIdle()
        }
        val mask = sampleMask()
        if (drag) assertTrue("The MixUI Sheet must actually move while held",
            mask.bounds.top - restingBounds.top > 8f)
        val name = "source-$dark-${if (drag) "held-drag" else "rest"}"
        val sheet = phasePair("$name-sheet")
        val after = bitmapBounds(samplingTrack())
        assertTrue("Capture both phases at the SAME window coordinates",
            abs(after.top - mask.bounds.top) < 1f && abs(after.left - mask.bounds.left) < 1f)
        val sheetEnergy = stripeResponse(sheet.first, sheet.second, mask)
        settle()
        val stable = samplingFrame(); capture("$name-stable", stable)
        val drift = mask.pixels.map { (x, y) ->
            abs(luminance(stable.getPixel(x, y)) - luminance(sheet.second.getPixel(x, y)))
        }.average()
        // A solid fallback could trivially suppress every stripe. Separately
        // require the track itself to respond to low spatial frequencies.
        compose.runOnUiThread { flatProbe = true }
        val flat = phasePair("$name-low-frequency")
        val lowFrequencyLuminance = mask.pixels.map { (x, y) ->
            abs(luminance(flat.first.getPixel(x, y)) - luminance(flat.second.getPixel(x, y)))
        }.average()
        // This probe changes hue, unlike the black/white stripe oracle. Opposing
        // channel changes can cancel in luminance even for visibly live glass.
        // Use channel distance to reject a solid fallback; keep the same 8/255 floor.
        val lowFrequencyResponse = mask.pixels.map { (x, y) ->
            distance(flat.first.getPixel(x, y), flat.second.getPixel(x, y)) / (3.0 * 255.0)
        }.average()
        if (drag) samplingRoot().performTouchInput { up() }
        compose.runOnUiThread { visible = false; flatProbe = false }; settle()
        // Convert the captured-window rect to the fixture's own local coordinates.
        val fixture = bitmapBounds(compose.onNodeWithTag("sampling-fixture"))
        compose.runOnUiThread {
            directControlBounds = Rect(mask.bounds.left - fixture.left, mask.bounds.top - fixture.top,
                mask.bounds.right - fixture.left, mask.bounds.bottom - fixture.top)
        }
        settle()
        val directBounds = bitmapBounds(compose.onNodeWithTag("direct-page-control"))
        assertTrue("Direct control must occupy exactly the same window rectangle",
            abs(directBounds.left - mask.bounds.left) <= 1f && abs(directBounds.top - mask.bounds.top) <= 1f &&
                abs(directBounds.width - mask.bounds.width) <= 1f && abs(directBounds.height - mask.bounds.height) <= 1f)
        val direct = phasePair("$name-direct")
        val directEnergy = stripeResponse(direct.first, direct.second, mask)
        val ratio = sheetEnergy / directEnergy
        val report = "$name samples=${mask.pixels.size} rect=${mask.bounds} " +
            "sheetRms=$sheetEnergy directRms=$directEnergy ratio=$ratio stableDrift=$drift " +
            "lowFrequencyResponse=$lowFrequencyResponse lowFrequencyLuminance=$lowFrequencyLuminance"
        println("SHEET_SOURCE_MEASUREMENT $report")
        System.getProperty("ui.capture.output")?.let { output ->
            File(output, "mochi-large-sheet-glass/$name-measurement.txt").writeText(report + "\n")
        }
        // Explicit acceptance specification, NOT an empirically calibrated claim:
        // B/controlGlass must preserve measurable 16px detail (>8/255 RMS);
        // S must remove at least 6 dB of that detail (amplitude <= 1/2).
        // The parent A/B runner must report measured values, not relax these
        // limits merely to make a build green. A failed control is inconclusive.
        assertTrue("Invalid fixture: direct control does not resolve stripes: $report", directEnergy > 8.0 / 255)
        assertTrue("A solid fallback must not pass as correctly sampled S: $report", lowFrequencyResponse > 8.0 / 255)
        assertTrue("Sheet tabs must sample blurred S, not original page B: $report", ratio < 0.5)
        assertTrue("No temporal feedback / self-sampling after settling: $report", drift < 1.0 / 255)
    }

    @Test fun tabsSampleSheetMaterialRatherThanSharpPage() {
        for (dark in listOf(false, true)) assertSheetSource(dark, drag = false)
    }

    @Test fun heldSheetDragKeepsWindowCoordinatesAndSheetMaterialSource() {
        for (dark in listOf(false, true)) assertSheetSource(dark, drag = true)
    }

    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
}
