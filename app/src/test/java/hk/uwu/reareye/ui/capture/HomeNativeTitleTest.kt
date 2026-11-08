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

    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }

    private fun open(
        backend: MixUiBackend,
        dark: Boolean,
        bottomInset: Int,
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
    ) {
        compose.mainClock.autoAdvance = false
        presetOpens = 0
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                NativeTitleTestEnvironment(direction, fontScale) {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    Box(Modifier.fillMaxSize()) {
                        HomeScreen(
                            bottomInnerPadding = 96.dp,
                            onOpenPresetPackDialog = { presetOpens++ },
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
            ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomInset))
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomInset))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build())
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
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
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
                assertTrue("Home must render Mochi's 34sp bold native title, not MIUIX TopAppBar",
                    layouts("REAREye").any {
                        it.layoutInput.style.fontSize == 34.sp && it.layoutInput.style.fontWeight == FontWeight.Bold
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

}
