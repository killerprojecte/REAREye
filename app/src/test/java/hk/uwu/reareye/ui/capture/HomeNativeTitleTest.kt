package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.R
import hk.uwu.reareye.hook.support.ModuleActivationState
import hk.uwu.reareye.ui.components.PresetPackHomeSnapshot
import hk.uwu.reareye.ui.components.PresetPackLocalSnapshot
import hk.uwu.reareye.ui.components.PresetPackLocalStatus
import hk.uwu.reareye.ui.screen.HomeScreen
import hk.uwu.reareye.ui.screen.HomeScreenSnapshot
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
import java.io.File

/** Production Home with offline state, Chinese labels and real nonzero window insets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeNativeTitleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var presetOpens = 0
    private val quickActions = mutableListOf<Int>()
    private var observedStatusBarInset = 0

    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }

    private fun open(
        backend: MixUiBackend,
        dark: Boolean,
        bottomInset: Int,
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
        quickMenu: Boolean = false,
        mochiStyle: MixMochiStyle = MixMochiStyle.Default,
    ) {
        compose.mainClock.autoAdvance = false
        presetOpens = 0
        quickActions.clear()
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                NativeTitleTestEnvironment(direction, fontScale) {
                val statusBarInset = WindowInsets.statusBars.getTop(LocalDensity.current)
                SideEffect { observedStatusBarInset = statusBarInset }
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light, mochiStyle) {
                    Box(Modifier.fillMaxSize()) {
                        HomeScreen(
                            bottomInnerPadding = 96.dp,
                            onOpenPresetPackDialog = { presetOpens++ },
                            quickActionOverride = if (quickMenu) ({ quickActions += it }) else null,
                            snapshot = HomeScreenSnapshot(
                                activationState = ModuleActivationState.ACTIVE,
                                hasRootAccess = true,
                                latestCommitHash = "123abcd",
                                presetPack = PresetPackHomeSnapshot(
                                    PresetPackLocalSnapshot(PresetPackLocalStatus.NOT_INSTALLED),
                                ),
                            ),
                        )
                        // MainActivity supplies the measured overlay height including its inset.
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp)
                            .testTag("home-test-bottom-overlay"))
                    }
                }
                }
            }
        }
        frames()
        compose.runOnUiThread {
            val decor = activity!!.get().window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomInset))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomInset))
                .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build()
            // Native Mochi overlays attach a ComposeView to this same DecorView.
            // Its requestApplyInsets must not replace our synthetic device insets
            // with Robolectric's zero-inset window after opening/closing a menu.
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, _ -> insets }
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
        }
        frames()
    }

    private fun label(id: Int) = activity!!.get().getString(id)
    private fun scroll(delta: Float) {
        compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        frames()
    }
    private fun layouts(text: String): List<TextLayoutResult> {
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true)
        return nodes.fetchSemanticsNodes().indices.flatMap { index ->
            val result = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
            result
        }
    }
    private fun capture(name: String) {
        val output = System.getProperty("ui.capture.output") ?: return
        val folder = File(output, "home-native-title").apply { mkdirs() }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots.fetchSemanticsNodes().indices.forEach { index ->
            val bitmap = roots[index].captureToImage().asAndroidBitmap()
            val suffix = if (index == 0) "" else "-root$index"
            File(folder, "$name$suffix.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }

    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    @Test fun actualHomeUsesNativeMochiTitleAndCompactChineseInfoLabels() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark, bottomInset = 24)
            compose.onNodeWithText(label(R.string.home_status_working), useUnmergedTree = true).assertIsDisplayed()
            if (backend == MixUiBackend.Mochi) {
                assertTrue("Home must render Mochi's 32sp normal native title, not MIUIX TopAppBar",
                    layouts("REAREye").any {
                        it.layoutInput.style.fontSize == 32.sp && it.layoutInput.style.fontWeight == FontWeight.Normal
                    })
                assertEquals("Chinese info labels are row headlines, not large page titles",
                    17.sp, layouts(label(R.string.status_card)).single().layoutInput.style.fontSize)
            }
            capture("${backend.name}-${if (dark) "dark" else "light"}-initial")
        }
    }

    @Test fun actualHomeReachesLastRowAboveOverlayAndPreservesPresetCallback() {
        for (backend in MixUiBackend.entries) for (bottomInset in listOf(24, 48)) {
            open(backend, dark = false, bottomInset = bottomInset)
            val preset = compose.onNode(hasClickAction() and
                hasAnyDescendant(hasText(label(R.string.preset_pack_missing_title))), useUnmergedTree = true)
            preset.performScrollTo()
            frames()
            repeat(2) { preset.performClick(); frames() }
            assertEquals("The production preset callback is retained", 2, presetOpens)

            scroll(10000f)
            val latest = compose.onNodeWithText("123abcd", useUnmergedTree = true)
            latest.assertIsDisplayed()
            val overlayTop = compose.onNodeWithTag("home-test-bottom-overlay")
                .fetchSemanticsNode().boundsInRoot.top
            assertTrue("Last update row must clear the measured bottom overlay and system inset",
                latest.fetchSemanticsNode().boundsInRoot.bottom < overlayTop - 16f)
            capture("${backend.name}-navigation-$bottomInset-scrolled")

            scroll(-10000f)
            compose.onNodeWithText(label(R.string.home_status_working), useUnmergedTree = true).assertIsDisplayed()
        }
    }
    @Test fun productionHomeTitleParagraphMatchesMiuixAndKeepsNativeCollapsedTitle() {
        val failures = mutableListOf<String>()
        for (dark in listOf(false, true)) for (fontScale in listOf(1f, 1.3f)) {
            for (backend in MixUiBackend.entries) {
                open(backend, dark, bottomInset = 24, fontScale = fontScale)
                val key = "alignment-${backend.name}-$dark-$fontScale"
                val expanded = compose.nativeTitleGeometry("REAREye")
                writeNativeTitleGeometry("home-native-title", "$key-expanded", expanded)
                capture("$key-expanded")
                val title = expanded.maxBy { it.layout.layoutInput.style.fontSize.value }
                if (backend == MixUiBackend.Mochi) {
                    assertEquals(32.sp, title.layout.layoutInput.style.fontSize)
                    assertEquals(androidx.compose.ui.text.font.FontWeight.Normal, title.layout.layoutInput.style.fontWeight)
                }
                val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                val inset = title.startInset(root, LayoutDirection.Ltr)
                if (kotlin.math.abs(inset - 26f) > 1f) failures += "$key title first-glyph inset=$inset, expected 26px"
                // A title fix must not turn ordinary Chinese info rows into page titles.
                if (backend == MixUiBackend.Mochi) {
                    assertEquals(17.sp, layouts(label(R.string.status_card)).single().layoutInput.style.fontSize)
                }
                repeat(2) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }; frames() }
                val collapsed = compose.nativeTitleGeometry("REAREye")
                writeNativeTitleGeometry("home-native-title", "$key-collapsed", collapsed)
                capture("$key-collapsed")
                val compact = collapsed.firstOrNull {
                    it.layout.layoutInput.style.fontSize.value < 28f && it.bounds.height > 0f &&
                        it.bounds.top >= 24f && it.bounds.bottom < 150f
                }
                assertNotNull("$key must retain the native collapsed title", compact)
                if (backend == MixUiBackend.Mochi) {
                    assertEquals(17.sp, compact!!.layout.layoutInput.style.fontSize)
                    assertTrue("Compact title must not inherit a large-title indent",
                        compact.layout.layoutInput.style.textIndent == null ||
                            compact.layout.layoutInput.style.textIndent == androidx.compose.ui.text.style.TextIndent.None)
                    assertEquals(root.center.x, compact.bounds.center.x, 1.5f)
                }
                scroll(-100000f)
                compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }; frames()
                writeNativeTitleGeometry("home-native-title", "$key-restored", compose.nativeTitleGeometry("REAREye"))
                capture("$key-restored")
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun actualHomeQuickMenuPreservesFourActionsAndBackDismissal() {
        val actionLabels = listOf(R.string.quick_stop_subscreencenter, R.string.quick_stop_thememanager,
            R.string.quick_stop_personalassistant, R.string.quick_stop_systemui)
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark, bottomInset = 24, quickMenu = true)
            fun openMenu() {
                compose.onNodeWithTag("home-quick-actions").performClick(); frames()
                actionLabels.forEach { compose.onNodeWithText(label(it)).assertIsDisplayed() }
            }
            openMenu()
            capture("${backend.name}-${if (dark) "dark" else "light"}-quick-menu")
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; frames()
            compose.onNodeWithText(label(actionLabels.first())).assertDoesNotExist()
            assertTrue(quickActions.isEmpty())
            for (index in actionLabels.indices) {
                openMenu()
                compose.onNodeWithText(label(actionLabels[index])).performClick(); frames()
                compose.onNodeWithText(label(actionLabels.first())).assertDoesNotExist()
                assertEquals((0..index).toList(), quickActions)
            }
            // All callbacks are inert injected spies, never a root/system operation.
            compose.onNodeWithText(label(R.string.home_status_working), useUnmergedTree = true).assertIsDisplayed()
        }
    }

    @Test @Config(qualifiers = "zh-rCN-w400dp-h1200dp-mdpi")
    fun shortProductionHomeCanFinishCollapseAndReturnToSharpTitle() {
        for (fontScale in listOf(1f, 1.3f)) {
            open(MixUiBackend.Mochi, false, 24, fontScale = fontScale)
            val before = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            val initial = compose.nativeTitleGeometry("REAREye").maxBy { it.layout.layoutInput.style.fontSize.value }
            val range = compose.onNode(hasScrollAction()).fetchSemanticsNode()
                .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
            assertTrue("Short Home needs enough scroll extent to finish the title transition", range.maxValue() >= initial.bounds.height)
            repeat(2) { compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }; frames() }
            val collapsed = compose.nativeTitleGeometry("REAREye").first { it.layout.layoutInput.style.fontSize == 17.sp }
            assertTrue("Compact title stays below the status inset", collapsed.bounds.top >= 24f)
            capture("short-home-$fontScale-collapsed")
            repeat(3) { compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }; frames() }
            val after = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            val restored = compose.nativeTitleGeometry("REAREye").maxBy { it.layout.layoutInput.style.fontSize.value }
            assertEquals(initial.bounds.top, restored.bounds.top, 1f)
            // Inspect rendered pixels, not merely Text semantics (which survive alpha=0).
            var changed = 0
            var total = 0
            for (y in initial.bounds.top.toInt().coerceAtLeast(0) until initial.bounds.bottom.toInt().coerceAtMost(before.height)) {
                for (x in initial.bounds.left.toInt().coerceAtLeast(0) until initial.bounds.right.toInt().coerceAtMost(before.width)) {
                    total++
                    if (before.getPixel(x, y) != after.getPixel(x, y)) changed++
                }
            }
            assertTrue("Expanded title pixels must recover after pointer scrolling: $changed/$total", changed < total * 0.05f)
            capture("short-home-$fontScale-restored")
        }
    }

    @Test fun actualHomeToolbarMaterialOnlyAppearsAfterPointerScrolling() {
        verifyHomeToolbarMaterial("long")
    }

    @Test @Config(qualifiers = "zh-rCN-w400dp-h1200dp-mdpi")
    fun shortActualHomeToolbarMaterialFollowsNativeCollapseAndReturn() {
        verifyHomeToolbarMaterial("short")
    }

    private fun verifyHomeToolbarMaterial(contentSize: String) {
        for (backend in MixUiBackend.entries) {
            val styles = if (backend == MixUiBackend.Mochi) MixMochiStyle.entries.toList()
                else listOf(MixMochiStyle.Default)
            for (style in styles) for (dark in listOf(false, true)) {
                open(backend, dark, 24, quickMenu = true, mochiStyle = style)
                val name = "toolbar-material-$contentSize-${backend.name}-${style.name}-${if (dark) "dark" else "light"}"
                fun assertStatusInset() {
                    assertEquals("$name must retain the fixture's real 24px status inset", 24, observedStatusBarInset)
                }
                assertStatusInset()
                val button = compose.onNodeWithTag("home-quick-actions")
                val initialBounds = button.fetchSemanticsNode().boundsInRoot
                // Both native contracts are 44dp; assert the public geometry
                // without leaking vendor dependencies into the app module.
                val buttonSize = 44f
                assertEquals("Keep the native toolbar hit target", buttonSize, initialBounds.width, 1f)
                assertEquals("Keep the native toolbar hit target", buttonSize, initialBounds.height, 1f)
                fun image() = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                fun contrast(bitmap: Bitmap, inner: Float, outer: Float): Double {
                    val b = button.fetchSemanticsNode().boundsInRoot
                    val radius = minOf(b.width, b.height) / 2f
                    var sum = 0.0
                    var count = 0
                    for (y in b.top.toInt() until b.bottom.toInt()) {
                        // Compare at the same y, outside the button's native glass footprint.
                        val background = bitmap.getPixel((b.left - 6f).toInt(), y)
                        for (x in b.left.toInt() until b.right.toInt()) {
                            val dx = (x + 0.5f - b.center.x) / radius
                            val dy = (y + 0.5f - b.center.y) / radius
                            val r2 = dx * dx + dy * dy
                            if (r2 < inner * inner || r2 > outer * outer) continue
                            val pixel = bitmap.getPixel(x, y)
                            sum += maxOf(
                                kotlin.math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(background)),
                                kotlin.math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(background)),
                                kotlin.math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(background)),
                            )
                            count++
                        }
                    }
                    assertTrue("The tested pixel region must be nonempty", count > 0)
                    return sum / count
                }
                fun assertNoMaterial(bitmap: Bitmap) {
                    // The 24dp icon stays inside this annulus. Transparent material
                    // must show the untouched page background, not a faint glass disc.
                    assertTrue("$name material must be absent at top: ${contrast(bitmap, .65f, .9f)}",
                        contrast(bitmap, .65f, .9f) < .75)
                    assertTrue("$name icon must stay visible with no material", contrast(bitmap, 0f, .55f) > 2.0)
                }
                val initial = image()
                assertNoMaterial(initial)
                capture("$name-initial")
                val list = compose.onNode(hasScrollAction())
                // Real pointer drag followed by a fling, not a semantics-only scroll.
                list.performTouchInput { swipeUp(durationMillis = 800) }; frames()
                assertTrue("$name drag must reveal native glass", contrast(image(), .65f, .9f) > .75)
                capture("$name-dragged")
                list.performTouchInput { swipeUp(durationMillis = 120) }; frames()
                assertTrue("$name fling must retain native glass", contrast(image(), .65f, .9f) > .75)
                capture("$name-flung")
                assertStatusInset()
                assertEquals("Toolbar bounds cannot move during collapse", initialBounds,
                    button.fetchSemanticsNode().boundsInRoot)
                // Exercise the real native click after the glass/plain transition.
                button.performTouchInput { click() }; frames()
                compose.onNodeWithText(label(R.string.quick_stop_subscreencenter)).assertIsDisplayed()
                assertStatusInset()
                compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; frames()
                assertStatusInset()
                assertEquals("Menu dismissal cannot move the toolbar", initialBounds,
                    button.fetchSemanticsNode().boundsInRoot)
                assertTrue("Dismissing the menu must not invoke a quick action", quickActions.isEmpty())
                repeat(5) { list.performTouchInput { swipeDown(durationMillis = 120) }; frames() }
                val range = list.fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
                assertEquals("Pointer recovery must reach the true top", 0f, range.value(), 0f)
                assertNoMaterial(image())
                assertStatusInset()
                assertEquals(initialBounds, button.fetchSemanticsNode().boundsInRoot)
                capture("$name-restored")
                // The native control is still actionable with its material hidden.
                button.performTouchInput { click() }; frames()
                compose.onNodeWithText(label(R.string.quick_stop_subscreencenter)).assertIsDisplayed()
                assertStatusInset()
                compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; frames()
            }
        }
    }

}
