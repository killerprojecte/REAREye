package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.GlassEditorSheet
import hk.uwu.mixui.components.GlassTabs
import hk.uwu.mixui.components.GlassTopBarScaffold
import hk.uwu.mixui.components.Tabs
import hk.uwu.mixui.components.Text
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
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
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Native-graphics component fixtures, not physical-device blur or sheet-drag certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MiuixGlassTabsDragTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val selected = mutableStateOf(0)
    private val labels = mutableStateOf(listOf("First", "Second", "Third", "Fourth"))
    private val commits = mutableListOf<Int>()
    private val parentScroll = ScrollState(0)

    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun track() = compose.onNodeWithTag("glass-tabs-miuix")

    // The vendor indicator isn't a semantics node. Observe its rendered fill near the track bottom.
    private fun indicatorCenter(rtl: Boolean): Double {
        val image = track().captureToImage().asAndroidBitmap()
        val y = image.height - 8
        fun redAt(x: Int) = android.graphics.Color.red(image.getPixel(x, y))
        val resting = redAt(if (rtl) image.width / 8 else image.width * 7 / 8)
        val pixels = (14 until image.width - 14).filter { abs(redAt(it) - resting) > 3 }
        assertTrue("Visible native indicator", pixels.size > image.width / 10)
        return pixels.average()
    }
    private fun commit(index: Int) { commits += index; selected.value = index }

    private fun mount(dark: Boolean = false, rtl: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            commits.clear()
            selected.value = 0
            labels.value = listOf("First", "Second", "Third", "Fourth")
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(MixUiBackend.Miuix, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        content()
                    }
                }
            }
        }
        settle()
    }

    private fun open(dark: Boolean = false, rtl: Boolean = false, scrolled: Boolean = false,
                     reference: Boolean = false, scrollParent: Boolean = false) {
        mount(dark, rtl) {
            // A separate, uniformly coloured body source gives the native reference the same
            // input as the public scaffold's body-only source, without exposing internal locals.
            val referenceBackdrop = rememberLayerBackdrop()
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (scrolled) 2 else 0)
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().layerBackdrop(referenceBackdrop).background(MiuixTheme.colorScheme.surface))
                GlassTopBarScaffold(
                    title = "Drag fixture",
                    isContentScrolled = scrolled,
                    contentListState = listState,
                    inheritMiuixBarMaterial = false,
                    pinnedHeader = {
                        val scroll = if (scrollParent) Modifier.height(160.dp).verticalScroll(parentScroll) else Modifier
                        Column(Modifier.fillMaxWidth().then(scroll)) {
                            if (reference) Spacer(Modifier.height(48.dp))
                            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                                GlassTabs(labels.value, selected.value, ::commit, Modifier.fillMaxWidth())
                            }
                            if (reference) {
                                Spacer(Modifier.height(48.dp))
                                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                                    GlassSegmentedTabRow(
                                        tabs = labels.value, selectedIndex = selected.value,
                                        onSelect = {}, backdrop = referenceBackdrop,
                                        surfaceAlpha = if (scrolled) 1f else 0f,
                                        modifier = Modifier.fillMaxWidth().testTag("native-tab-reference"),
                                    )
                                }
                                Spacer(Modifier.height(24.dp))
                            }
                            if (scrollParent) repeat(12) { Text("Scrollable row $it", Modifier.height(56.dp)) }
                        }
                    },
                ) { padding ->
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = padding) {
                        items(60) { Box(Modifier.fillMaxWidth().height(56.dp)) }
                    }
                }
            }
        }
    }

    @Test fun continuousPointerPreviewCommitsOnlyOnceOnReleaseInLtrAndRtl() {
        for (dark in listOf(false, true)) for (rtl in listOf(false, true)) {
            open(dark = dark, rtl = rtl, scrolled = true)
            track().assertWidthIsEqualTo(352.dp).assertHeightIsEqualTo(40.dp)
            val initial = indicatorCenter(rtl)
            track().performTouchInput {
                down(Offset(width * if (rtl) 0.875f else 0.125f, height / 2f))
                moveTo(Offset(width * if (rtl) 0.625f else 0.375f, height / 2f))
            }
            settle()
            val preview = indicatorCenter(rtl)
            assertTrue("Indicator follows the finger before selection", (preview - initial) * (if (rtl) -1 else 1) > 20)
            assertEquals(0, selected.value)
            assertTrue(commits.isEmpty())
            compose.onNodeWithText("First").assertIsSelected()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            track().performTouchInput { up() }
            settle()
            assertEquals(listOf(1), commits)
            compose.onNodeWithText("Second").assertIsSelected()
            compose.onNodeWithText("Fourth").performClick()
            settle()
            assertEquals(listOf(1, 3), commits)
        }
    }

    @Test fun cancellationRestoresSelectionAndFreshTapStillWorks() {
        open(scrolled = true)
        val initial = indicatorCenter(rtl = false)
        track().performTouchInput { down(Offset(width * 0.125f, height / 2f)); moveTo(Offset(width * 0.375f, height / 2f)) }
        settle()
        assertTrue(indicatorCenter(rtl = false) > initial + 20)
        track().performTouchInput { cancel() }
        settle()
        assertTrue(commits.isEmpty())
        compose.onNodeWithText("First").assertIsSelected()
        assertEquals(initial, indicatorCenter(rtl = false), 1.0)
        compose.onNodeWithText("Second").performTouchInput { click() }
        settle()
        assertEquals(listOf(1), commits)
    }

    @Test fun verticalGestureRemainsAvailableToScrollingParent() {
        open(scrollParent = true)
        track().performTouchInput { swipe(Offset(44f, height / 2f), Offset(46f, -140f), 300) }
        settle()
        assertTrue("Tabs must not consume the sheet/list vertical winner", parentScroll.value > 0)
        assertTrue(commits.isEmpty())
        assertEquals(0, selected.value)
    }

    @Test fun tabCountChangeCancelsActiveDrag() {
        open()
        track().performTouchInput { down(Offset(width * 0.125f, height / 2f)); moveTo(Offset(width * 0.625f, height / 2f)) }
        settle()
        assertTrue(commits.isEmpty())
        compose.runOnUiThread { labels.value = listOf("First", "Second"); selected.value = 1 }
        settle()
        compose.onNodeWithText("Second").assertIsSelected()
        compose.onNodeWithText("Third").assertDoesNotExist()
        track().performTouchInput { up() }
        settle()
        assertTrue("Release of the cancelled drag must not select a new tab", commits.isEmpty())
        compose.onNodeWithText("Second").assertIsSelected()
    }

    @Test fun emptySingleDuplicateLabelsAndBoundsAreSafe() {
        open()
        track().performTouchInput { swipe(Offset(44f, height / 2f), Offset(width + 120f, height / 2f), 300) }
        settle()
        assertEquals(listOf(3), commits)
        track().performTouchInput { swipe(Offset(width - 44f, height / 2f), Offset(width + 140f, height / 2f), 300) }
        settle()
        assertEquals("Dragging past last tab is not another commit", listOf(3), commits)
        compose.runOnUiThread { labels.value = listOf("Only"); selected.value = 99; commits.clear() }
        settle()
        compose.onNodeWithText("Only").assertIsSelected()
        track().performTouchInput { swipeLeft() }
        settle()
        assertTrue(commits.isEmpty())
        compose.runOnUiThread { labels.value = listOf("Same", "Same"); selected.value = 0 }
        settle()
        compose.onAllNodesWithText("Same").assertCountEquals(2)
        compose.runOnUiThread { labels.value = emptyList() }
        settle()
        track().assertDoesNotExist()
    }

    @Test fun sheetTabsCommitOnceAndDoNotBlockVerticalBodyScrolling() {
        mount {
            // Production MIUIX sheet tabs require the real page-only material scene;
            // without it GlassEditorSheet intentionally chooses the ordinary window fallback.
            GlassBottomSheetScene {
                Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface))
                GlassEditorSheet(
                    show = true, title = "Sheet drag fixture", onDismissRequest = {}, onConfirm = {},
                    confirmText = "Save", cancelText = "Cancel",
                ) {
                    Column {
                        Tabs(labels.value, selected.value, ::commit, Modifier.fillMaxWidth())
                        repeat(18) { Text("Sheet form row $it", Modifier.height(60.dp).testTag("sheet-drag-row-$it")) }
                    }
                }
            }
        }
        track().assertWidthIsEqualTo(352.dp).assertHeightIsEqualTo(40.dp)
            .assertLeftPositionInRootIsEqualTo(24.dp)
        track().performTouchInput { swipe(Offset(40f, height / 2f), Offset(width - 25f, height / 2f), 300) }
        settle()
        assertEquals(listOf(3), commits)
        val row = compose.onNodeWithTag("sheet-drag-row-0")
        val before = row.fetchSemanticsNode().boundsInRoot.top
        track().performTouchInput { swipe(Offset(44f, height / 2f), Offset(46f, -130f), 300) }
        settle()
        assertEquals(listOf(3), commits)
        assertTrue("Native sheet body still scrolls from the track", row.fetchSemanticsNode().boundsInRoot.top < before)
    }

    @Test fun restingOpticsGeometryAndOverflowShadowMatchFixedNativeReference() {
        for (dark in listOf(false, true)) for (scrolled in listOf(false, true)) {
            open(dark = dark, scrolled = scrolled, reference = true)
            track().assertWidthIsEqualTo(352.dp).assertHeightIsEqualTo(40.dp)
                .assertLeftPositionInRootIsEqualTo(24.dp)
            val adapted = track().fetchSemanticsNode().boundsInRoot
            val native = compose.onNodeWithTag("native-tab-reference").fetchSemanticsNode().boundsInRoot
            assertEquals(adapted.width, native.width, 0f)
            assertEquals(adapted.height, native.height, 0f)
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            var differences = 0
            var samples = 0
            // Include the 12px exterior: matching only the clipped track would miss lost shadows.
            for (dy in -12 until adapted.height.roundToInt() + 12) {
                for (dx in -12 until adapted.width.roundToInt() + 12) {
                    val actual = bitmap.getPixel(adapted.left.roundToInt() + dx, adapted.top.roundToInt() + dy)
                    val expected = bitmap.getPixel(native.left.roundToInt() + dx, native.top.roundToInt() + dy)
                    if ((0..2).any { channel -> abs(((actual ushr (channel * 8)) and 255) - ((expected ushr (channel * 8)) and 255)) > 3 }) differences++
                    samples++
                }
            }
            assertTrue("Fixed native optics incl. shadow, dark=$dark scrolled=$scrolled: $differences/$samples", differences <= samples / 100)
            if (scrolled) {
                val adaptedBackground = bitmap.getPixel(0, adapted.bottom.roundToInt() + 2)
                val nativeBackground = bitmap.getPixel(0, native.bottom.roundToInt() + 2)
                var adaptedShadowPixels = 0
                var nativeShadowPixels = 0
                for (dy in 1..10) for (dx in 12 until adapted.width.roundToInt() - 12) {
                    if (bitmap.getPixel(adapted.left.roundToInt() + dx, adapted.bottom.roundToInt() + dy) != adaptedBackground) adaptedShadowPixels++
                    if (bitmap.getPixel(native.left.roundToInt() + dx, native.bottom.roundToInt() + dy) != nativeBackground) nativeShadowPixels++
                }
                // Dark-on-dark Float shadow may quantize into the background at the centre.
                // Compare the full exterior against upstream, not an invented contrast floor.
                assertEquals("Exterior shadow visibility must match native Float", nativeShadowPixels > 0, adaptedShadowPixels > 0)
                if (!dark) assertTrue("Native Float visibly overflows 40dp on the light fixture", nativeShadowPixels > 0)
            }
            System.getProperty("ui.capture.output")?.let { output ->
                val folder = File(output, "miuix-glass-tabs-drag").apply { mkdirs() }
                File(folder, "native-reference-dark-$dark-scrolled-$scrolled.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
        }
    }
}
