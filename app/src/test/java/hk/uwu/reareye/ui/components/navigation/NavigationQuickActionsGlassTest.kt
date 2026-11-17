package hk.uwu.reareye.ui.components.navigation

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.PopupPositionProvider
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassPopupDefaults
import top.yukonga.miuix.kmp.glass.glassSurface
import ui.feice.mochi.effect.glass.MochiGlassSurface
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBackdrop
import ui.feice.mochi.theme.LocalMochiMaterials
import ui.feice.mochi.theme.MochiTheme
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.components.*
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode
import hk.uwu.reareye.ui.theme.AppNavigationBarStyle
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.AppThemeMode
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
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Actual AppNavigationBar/long-press Popup, with a deliberately synthetic high-contrast page.
 * These are production-popup component fixtures, not MainActivity or device screenshots.
 * A/B changes only the lower page; a wrong top-left Popup sampler cannot pass this probe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h869dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationQuickActionsGlassTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var backdrop: NavigationBackdrop
    private var sourceB by mutableStateOf(false)
    private var siblingB by mutableStateOf(false)
    private var showNavigation by mutableStateOf(true)
    private val selected = mutableListOf<NavigationQuickTarget>()
    private val routes = mutableListOf<String>()
    private var saves = 0
    private var probeIds by mutableStateOf(listOf(0))
    private var probeGlass by mutableStateOf(true)
    private var probeControlsDisabled by mutableStateOf(false)

    // Fine stripes distinguish a real low-pass filter from a translucent tint. Broad
    // bands still survive native blur, so a blank/opaque source cannot pass either.
    private fun DrawScope.patternedPage(changed: Boolean) {
        drawRect(Color(0xFF167843))
        val top = size.height * 0.3f
        val colors = if (changed) listOf(Color(0xFF1749EC), Color(0xFF22A4A1))
            else listOf(Color(0xFFDE362B), Color(0xFFE1B12E))
        for (x in 0 until size.width.toInt() step 40) {
            drawRect(colors[(x / 40) % 2], Offset(x.toFloat(), top), Size(40f, size.height - top))
        }
        for (x in 0 until size.width.toInt() step 4) {
            drawRect(Color.White.copy(alpha = 0.8f), Offset(x.toFloat(), top), Size(2f, size.height - top))
        }
    }

    @Before fun requireUiProfile() {
        assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close() }
        compose.mainClock.autoAdvance = true
    }
    private fun frames(count: Int = 30) {
        repeat(count) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() }
    }
    private fun launch(backend: MixUiBackend, dark: Boolean, mode: ModuleNavigationBarMode,
                       standaloneFallback: Boolean = false,
                       style: AppNavigationBarStyle = AppNavigationBarStyle.HyperOS) {
        compose.runOnUiThread {
            activity?.close()
            sourceB = false; siblingB = false; showNavigation = true
            selected.clear(); routes.clear(); saves = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                val theme = when {
                    backend == MixUiBackend.Mochi && dark -> AppThemeMode.MOCHI_DARK
                    backend == MixUiBackend.Mochi -> AppThemeMode.MOCHI_LIGHT
                    dark -> AppThemeMode.MIUIX_DARK
                    else -> AppThemeMode.MIUIX_LIGHT
                }
                AppTheme(themeMode = theme) {
                    val page = rememberNavigationBackdrop()
                    SideEffect { backdrop = page }
                    val permanent = mode == ModuleNavigationBarMode.FLOATING_GLASS ||
                        mode == ModuleNavigationBarMode.SEMI_TRANSPARENT
                    GlassBottomSheetScene {
                        Box(Modifier.fillMaxSize().testTag("quick-scene")) {
                            // Same recording boundary/demand as MainActivity; siblings stay excluded.
                            Canvas(Modifier.fillMaxSize().testTag("quick-page-source").then(
                                if (permanent || page.isQuickActionRecordingRequested)
                                    Modifier.navigationBackdrop(page, recordNavigation = permanent)
                                else Modifier,
                            )) {
                                patternedPage(sourceB)
                            }
                            // A scrim-like foreground sibling must never enter the sampling source.
                            Box(Modifier.fillMaxWidth().fillMaxHeight(0.7f).align(Alignment.BottomCenter)
                                .background(if (siblingB) Color.Yellow else Color.Transparent))
                            if (standaloneFallback) {
                                NavigationQuickActionSurface(page, glassEnabled = false,
                                    modifier = Modifier.align(Alignment.Center).size(176.dp, 40.dp)
                                        .testTag("fallback-entry")) { Text("Fallback") }
                            } else if (showNavigation) {
                                AppNavigationBar(currentScreen = "config", navigationBarMode = mode,
                                    navigationBarStyle = style,
                                    backdrop = page, modifier = Modifier.align(Alignment.BottomCenter),
                                    onScreenSelected = { routes += it },
                                    onQuickActionSelected = { selected += it },
                                    onQuickActionIdsChanged = { saves++ })
                            }
                        }
                    }
                }
            }
        }
        frames(50)
    }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun scene() = compose.onNodeWithTag("quick-scene")
    private fun entry() = compose.onNodeWithTag("navigation-quick-action-$NavigationQuickActionCardManagerId")
    private fun configPoint(): Offset = compose.onNode(hasClickAction() and
        (hasText(label(R.string.configuration_navigation)) or
            hasContentDescription(label(R.string.configuration_navigation))))
        .fetchSemanticsNode().boundsInRoot.center
    private fun hold() {
        val point = configPoint()
        scene().performTouchInput { down(point) }
        frames(45)
        scene().performTouchInput { advanceEventTime(720) }
        entry().assertExists()
        frames(24)
    }
    private fun pointerAt(node: SemanticsNodeInteraction): Offset {
        val target = node.fetchSemanticsNode()
        return target.positionOnScreen + Offset(target.size.width / 2f, target.size.height / 2f) -
            scene().fetchSemanticsNode().positionOnScreen
    }
    private fun moveTo(node: SemanticsNodeInteraction) {
        val point = pointerAt(node)
        scene().performTouchInput { moveTo(point, delayMillis = 64) }
    }
    private fun cancel() {
        scene().performTouchInput { cancel() }
        frames(35)
        entry().assertDoesNotExist()
        compose.runOnIdle { assertFalse(backdrop.isQuickActionRecordingRequested) }
    }
    // Sample two horizontal bands away from icon/text; opacity/geometry are not the signal.
    private fun difference(a: ImageBitmap, b: ImageBitmap): Float {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        val xRange = a.width / 5 until a.width * 4 / 5
        val rows = listOf(a.height / 5, a.height * 4 / 5)
        val p = a.toPixelMap(); val q = b.toPixelMap()
        var total = 0f; var count = 0
        for (y in rows) for (x in xRange) {
            total += abs(p[x, y].red - q[x, y].red) + abs(p[x, y].green - q[x, y].green) +
                abs(p[x, y].blue - q[x, y].blue)
            count += 3
        }
        return total / count
    }
    private fun capture(name: String, image: ImageBitmap) {
        val output = System.getProperty("ui.capture.output") ?: return
        val directory = File(output, "navigation-quick-actions-glass/production-popup-fixture").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun realLongPressSamplesChangingPageInEveryModeAndTheme() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true))
            for (mode in ModuleNavigationBarMode.entries) {
                launch(backend, dark, mode)
                compose.runOnIdle { assertFalse("Idle popup recording", backdrop.isQuickActionRecordingRequested) }
                hold()
                val geometry = entry().fetchSemanticsNode()
                assertEquals("Original capsule width", 176, geometry.size.width)
                assertEquals("Original capsule height", 40, geometry.size.height)
                compose.runOnIdle { assertTrue("$backend $dark $mode source requested", backdrop.isQuickActionRecordingRequested) }
                val a = entry().captureToImage()
                frames(50)
                val stable = entry().captureToImage()
                assertTrue("$backend $dark $mode must not recursively sample its own popup",
                    difference(a, stable) < 0.005f)
                compose.runOnIdle { sourceB = true }
                frames(35)
                val b = entry().captureToImage()
                assertTrue("$backend dark=$dark $mode actual lower-page A/B sampling",
                    difference(a, b) > 0.008f)
                compose.runOnIdle { siblingB = true }
                frames(35)
                val foreground = entry().captureToImage()
                assertTrue("$backend $dark $mode excludes foreground/scrim sibling",
                    difference(b, foreground) < 0.005f)
                val prefix = "$backend-${if (dark) "dark" else "light"}-$mode"
                capture("$prefix-source-a", a); capture("$prefix-source-b", b)
                capture("$prefix-excluded-sibling", foreground)
                cancel()
                compose.runOnIdle { assertTrue(selected.isEmpty()); assertTrue(routes.isEmpty()); assertEquals(0, saves) }
            }
    }

    @Test fun realPopupDragCancellationBackAndEditKeepSingleTerminalActions() {
        for (backend in MixUiBackend.entries) for (mode in ModuleNavigationBarMode.entries) {
            launch(backend, dark = false, mode = mode)
            hold()
            moveTo(entry())
            frames(8)
            scene().performTouchInput { up() }
            frames(45)
            compose.runOnIdle { assertEquals(1, selected.size); assertTrue(routes.isEmpty()); assertEquals(0, saves) }
            entry().assertDoesNotExist()
            frames(40)
            compose.runOnIdle { assertEquals("release only once", 1, selected.size) }
            hold()
            moveTo(entry())
            frames(8)
            cancel()
            compose.runOnIdle { assertEquals(1, selected.size) }
            hold()
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            frames(35)
            scene().performTouchInput { up() }
            frames(35)
            entry().assertDoesNotExist()
            compose.runOnIdle { assertEquals("Back then release", 1, selected.size); assertTrue(routes.isEmpty()) }
            val openMenuAction = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
                .fetchSemanticsNode().config[SemanticsActions.CustomActions]
                .first { it.label == label(R.string.navigation_quick_available_title) }
            compose.runOnUiThread { assertTrue(openMenuAction.action()) }
            frames(35)
            entry().assertExists()
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            roots[roots.fetchSemanticsNodes().lastIndex].performTouchInput { click(Offset(4f, 4f)) }
            frames(35)
            entry().assertDoesNotExist()
            compose.runOnIdle { assertEquals("outside dismissal", 1, selected.size); assertTrue(routes.isEmpty()) }
            hold()
            moveTo(compose.onNodeWithTag("navigation-quick-action-$NavigationQuickActionComponentManagerId"))
            frames(20)
            val edit = compose.onNodeWithTag("navigation-quick-edit-target")
            edit.assertExists()
            moveTo(edit)
            frames(8)
            scene().performTouchInput { up() }
            frames(45)
            compose.onNodeWithTag("quick-editor-cancel").assertExists()
            compose.runOnIdle { assertEquals(1, selected.size); assertEquals(0, saves) }
            // Only verify the drag handoff; editor header imagery is NOT popup evidence.
            compose.onNodeWithTag("quick-editor-cancel").performClick()
            frames(45)
            compose.runOnIdle { assertEquals(1, selected.size); assertEquals(0, saves) }
        }
    }

    @Test fun defaultGlassTriggerAndActionButtonsSampleAndFilterPatternedPage() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            for (style in listOf(AppNavigationBarStyle.default, AppNavigationBarStyle.HyperOS)) {
                launch(backend, dark, ModuleNavigationBarMode.FLOATING_GLASS, style = style)
                val bar = compose.onNodeWithTag("navigation-bar-material")
                val barBounds = bar.fetchSemanticsNode().boundsInRoot
                val a = bar.captureToImage()
                val prefix = "$backend-$dark-$style-trigger"
                capture("$prefix-bar", a)
                // Save the actual Config trigger, not a similarly styled test-only button.
                val trigger = compose.onNode(hasClickAction() and
                    (hasText(label(R.string.configuration_navigation)) or
                        hasContentDescription(label(R.string.configuration_navigation))))
                val triggerA = trigger.captureToImage()
                capture("$prefix-config", triggerA)
                compose.runOnIdle { sourceB = true }
                frames(35)
                val b = bar.captureToImage()
                assertTrue("$prefix page is sampled before the menu opens", difference(a, b) > 0.008f)
                assertTrue("$prefix actual Config trigger samples the page",
                    difference(triggerA, trigger.captureToImage()) > 0.005f)
                compose.runOnIdle { sourceB = false; showNavigation = false }
                frames(35)
                val rawPage = compose.onNodeWithTag("quick-page-source").captureToImage()
                val raw = Bitmap.createBitmap(rawPage.asAndroidBitmap(), barBounds.left.roundToInt(),
                    barBounds.top.roundToInt(), a.width, a.height).asImageBitmap()
                val tintBitmap = raw.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
                val tintChannel = if (dark) 0 else 255
                android.graphics.Canvas(tintBitmap).drawColor(
                    android.graphics.Color.argb(128, tintChannel, tintChannel, tintChannel))
                val tint = tintBitmap.asImageBitmap()
                assertTrue("$prefix native bar filters fine pattern edges unlike tint only",
                    fineEdgeEnergy(a) < fineEdgeEnergy(tint) * 0.6f)
                capture("$prefix-raw-page-control", raw)
                capture("$prefix-tint-only-control", tint)
                compose.runOnIdle { showNavigation = true }
                frames(35)
                hold()
                val actionA = entry().captureToImage()
                compose.runOnIdle { sourceB = true }
                frames(35)
                assertTrue("$prefix action button samples while trigger is held",
                    difference(actionA, entry().captureToImage()) > 0.008f)
                capture("$prefix-held-action", actionA)
                cancel()
                compose.runOnIdle { assertTrue(selected.isEmpty()); assertTrue(routes.isEmpty()) }
            }
        }
    }

    private enum class Probe { Adapter, Native, Tint }

    private fun launchProbe(backend: MixUiBackend, dark: Boolean, probe: Probe,
                            controlsDisabled: Boolean = false, consumers: Int = 1) {
        compose.runOnUiThread {
            activity?.close()
            sourceB = false
            probeIds = (0 until consumers).toList()
            probeGlass = true
            probeControlsDisabled = controlsDisabled
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                AppTheme(themeMode = when {
                    backend == MixUiBackend.Mochi && dark -> AppThemeMode.MOCHI_DARK
                    backend == MixUiBackend.Mochi -> AppThemeMode.MOCHI_LIGHT
                    dark -> AppThemeMode.MIUIX_DARK
                    else -> AppThemeMode.MIUIX_LIGHT
                }) {
                    val materials = MochiTheme.materials
                    CompositionLocalProvider(LocalMochiMaterials provides
                        if (probeControlsDisabled) materials.copy(control = null, bar = null) else materials) {
                        val page = rememberNavigationBackdrop()
                        val background = hk.uwu.mixui.theme.MixTheme.colorScheme.surface
                        val nativeMiuix = rememberLayerBackdrop { drawRect(background); drawContent() }
                        val nativeMochi = rememberMochiBackdrop(background)
                        SideEffect { backdrop = page }
                        Box(Modifier.fillMaxSize()) {
                            Canvas(Modifier.fillMaxSize().testTag("quick-page-source").then(
                                if (page.isQuickActionRecordingRequested)
                                    Modifier.navigationBackdrop(page, recordNavigation = false)
                                else Modifier,
                            ).layerBackdrop(nativeMiuix).mochiBackdropSource(nativeMochi)) {
                                patternedPage(sourceB)
                            }
                            NavigationQuickActionPopup(
                                positionProvider = remember {
                                    object : PopupPositionProvider {
                                        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
                                            layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                                            popupContentSize: IntSize) = IntOffset(100, 600)
                                    }
                                },
                                onDismissRequest = {},
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    probeIds.forEach { id -> key(id) {
                                        val modifier = Modifier.size(176.dp, 40.dp).testTag("pattern-probe-$id")
                                        val shape = RoundedCornerShape(50)
                                        when (probe) {
                                            Probe.Adapter -> NavigationQuickActionSurface(page, modifier,
                                                glassEnabled = probeGlass) {}
                                            Probe.Native -> when (backend) {
                                                MixUiBackend.Miuix -> Box(modifier.then(glassSurface(
                                                    nativeMiuix, GlassPopupDefaults.visuals(), 20.dp)(shape)))
                                                MixUiBackend.Mochi -> MochiGlassSurface(modifier,
                                                    material = MochiTheme.materials.menuGlass,
                                                    backdrop = nativeMochi, cornerRadius = 20.dp) {}
                                            }
                                            Probe.Tint -> Box(modifier.clip(shape).background(
                                                (if (dark) Color.Black else Color.White).copy(alpha = 0.5f)))
                                        }
                                    } }
                                }
                            }
                        }
                    }
                }
            }
        }
        frames(50)
    }

    private fun fineEdgeEnergy(image: ImageBitmap): Float {
        val pixels = image.toPixelMap()
        var total = 0f
        var count = 0
        for (y in listOf(image.height / 5, image.height * 4 / 5)) {
            for (x in image.width / 5 until image.width * 4 / 5 - 1) {
                val a = pixels[x, y]; val b = pixels[x + 1, y]
                total += abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)
                count += 3
            }
        }
        return total / count
    }

    @Test fun patternedPopupMatchesOwnNativeMaterialAndFiltersUnlikeTintOnly() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            launchProbe(backend, dark, Probe.Adapter)
            val actual = compose.onNodeWithTag("pattern-probe-0").captureToImage()
            compose.runOnIdle { sourceB = true }
            frames(35)
            val changed = compose.onNodeWithTag("pattern-probe-0").captureToImage()
            assertTrue("$backend dark=$dark patterned page remains live", difference(actual, changed) > 0.008f)
            launchProbe(backend, dark, Probe.Native)
            val native = compose.onNodeWithTag("pattern-probe-0").captureToImage()
            assertTrue("$backend dark=$dark exact native menu optics", difference(actual, native) < 0.012f)
            launchProbe(backend, dark, Probe.Tint)
            val tint = compose.onNodeWithTag("pattern-probe-0").captureToImage()
            assertTrue("$backend dark=$dark native blur removes fine edges, tint does not",
                fineEdgeEnergy(actual) < fineEdgeEnergy(tint) * 0.6f)
            capture("$backend-$dark-pattern-adapter", actual)
            capture("$backend-$dark-pattern-native", native)
            capture("$backend-$dark-pattern-tint-only", tint)
        }
    }

    @Test fun menuSourceSurvivesControlGlassToggleAndIndependentConsumerRemoval() {
        for (backend in MixUiBackend.entries) {
            launchProbe(backend, dark = false, Probe.Adapter, consumers = 2)
            compose.runOnIdle { assertTrue(backdrop.isQuickActionRecordingRequested) }
            // This is a real material configuration, not a mocked shader capability.
            compose.runOnIdle { probeControlsDisabled = true; probeIds = listOf(1) }
            frames(35)
            compose.onNodeWithTag("pattern-probe-0").assertDoesNotExist()
            val remaining = compose.onNodeWithTag("pattern-probe-1")
            compose.runOnIdle { assertTrue("remaining menu consumer retains source", backdrop.isQuickActionRecordingRequested) }
            val a = remaining.captureToImage()
            compose.runOnIdle { sourceB = true }
            frames(35)
            assertTrue("$backend menu source is independent of control glass",
                difference(a, remaining.captureToImage()) > 0.008f)
            compose.runOnIdle { probeGlass = false }
            frames(8)
            compose.runOnIdle { assertFalse("disabled consumer releases source", backdrop.isQuickActionRecordingRequested) }
            compose.runOnIdle { probeGlass = true }
            frames(8)
            compose.runOnIdle { assertTrue("re-enabled consumer reacquires source", backdrop.isQuickActionRecordingRequested) }
            compose.runOnIdle { probeIds = emptyList() }
            frames(8)
            compose.runOnIdle { assertFalse("last consumer releases source", backdrop.isQuickActionRecordingRequested) }
        }
    }

    @Test fun disabledGlassUsesOpaqueFallbackWithoutRecording() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            launch(backend, dark, ModuleNavigationBarMode.NORMAL, standaloneFallback = true)
            val a = compose.onNodeWithTag("fallback-entry").captureToImage()
            compose.runOnIdle { sourceB = true; siblingB = true }
            frames(40)
            val b = compose.onNodeWithTag("fallback-entry").captureToImage()
            assertTrue("$backend dark=$dark disabled glass remains solid", difference(a, b) < 0.005f)
            compose.runOnIdle { assertFalse(backdrop.isQuickActionRecordingRequested) }
        }
    }

}
