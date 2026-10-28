package hk.uwu.reareye.ui.components

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.context.ContextCategory
import hk.uwu.reareye.agent.context.ContextComposition
import hk.uwu.reareye.agent.context.ContextSegment
import hk.uwu.reareye.ui.components.navigation.RearNavigationBar
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode
import hk.uwu.reareye.ui.screen.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File

/** Production header/composer/navigation/sidebar with deterministic local composition snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryContextCompositionLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ContextPreviewActivity>? = null
    private val summary = mutableStateOf(MaryUsageSummary(1000, 4000, null))
    private val sidebar = mutableStateOf(false)
    private val draft = mutableStateOf("")
    private var sent = 0
    private var navigated = 0
    private val composition = ContextComposition.fromSegments(listOf(
        ContextSegment(ContextCategory.SYSTEM, 100),
        ContextSegment(ContextCategory.TOOL_DEFINITIONS, 200),
        ContextSegment(ContextCategory.USER, 150, 0),
        ContextSegment(ContextCategory.ASSISTANT, 50, 1),
        ContextSegment(ContextCategory.TOOL_CALLS, 100, 1),
        ContextSegment(ContextCategory.TOOL_RESULTS, 350, 2),
        ContextSegment(ContextCategory.NATIVE_PROTOCOL, 20, 3),
        ContextSegment(ContextCategory.FORMATTING, 30),
    ))

    @Before fun requireResources() {
        Assume.assumeTrue("Run with -PmaryUiResources=true", java.lang.Boolean.getBoolean("mary.ui.resources"))
    }
    @After fun cleanup() { compose.runOnUiThread { try { activity?.close() } finally { ContextPreviewActivity.testFontScale = 1f } } }

    @Test fun collapsedContextKeepsOriginalRingAndDoesNotAddAChatRow() {
        useComposition()
        host()
        val chip = node("mary-context-usage").getUnclippedBoundsInRoot()
        assertEquals("Existing Miuix IconButton visual size", 40f, chip.height.value, 1f)
        assertEquals("Existing Miuix IconButton visual size", 40f, chip.width.value, 1f)
        val ring = node("mary-context-ring").getUnclippedBoundsInRoot()
        assertEquals(20f, ring.width.value, 1f)
        assertEquals(20f, ring.height.value, 1f)
        node("mary-context-meter").assertDoesNotExist()
        node("mary-open-sidebar").assertIsDisplayed()
        node("mary-composer-input").assertIsDisplayed()
        assertTrue(hasPercent(semanticText("mary-context-usage"), 25))
        screenshot("closed-light")
    }

    @Test fun selectedCategoryShareIsDistinctFromWindowCapacity() {
        useComposition()
        host()
        openPopup()
        selectItem("mary-context-legend-TOOL_RESULTS")
        assertTrue(hasPercent(semanticText("mary-context-detail"), 35))
        assertTrue(hasPercent(semanticText("mary-context-capacity"), 25))
        assertPopupWithinViewport()
        screenshot("expanded-tool-results", "mary-context-popup")
        selectItem("mary-context-legend-SYSTEM")
        assertTrue(hasPercent(semanticText("mary-context-detail"), 10))
        screenshot("expanded-system", "mary-context-popup")
    }

    @Test fun popupBarAndLegendSelectTheSameCategory() {
        useComposition()
        host()
        openPopup()
        val bar = node("mary-context-breakdown-bar")
        assertTrue(bar.getUnclippedBoundsInRoot().height.value >= 48f)
        bar.performTouchInput { click(Offset(width * 0.8f, center.y)) }
        assertTrue(hasPercent(semanticText("mary-context-detail"), 35))
        selectItem("mary-context-legend-USER")
        assertTrue(hasPercent(semanticText("mary-context-detail"), 15))
    }

    @Test fun unknownCapacityNeverInventsZeroOrFullPercent() {
        summary.value = MaryUsageSummary(1000, 0, null, composition = composition)
        host()
        node("mary-context-usage").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ProgressBarRangeInfo))
        openPopup()
        assertFalse(semanticText("mary-context-capacity").contains("0%"))
        assertFalse(semanticText("mary-context-capacity").contains("100%"))
        screenshot("unknown-capacity", "mary-context-popup")
    }

    @Test fun zeroAndOverflowRemainHonestWithoutNaN() {
        summary.value = MaryUsageSummary(0, 4000, null, composition = ContextComposition.fromSegments(emptyList()))
        host()
        assertTrue(hasPercent(semanticText("mary-context-usage"), 0))
        openPopup()
        assertFalse(semanticText("mary-context-popup").contains("NaN"))
        closePopup()
        compose.runOnIdle { summary.value = MaryUsageSummary(1000, 800, null, composition = composition) }
        assertTrue(hasPercent(semanticText("mary-context-usage"), 125))
        openPopup()
        assertTrue(hasPercent(semanticText("mary-context-capacity"), 125))
        screenshot("overflow-capacity", "mary-context-popup")
    }

    @Test fun loadingThenModelResetDoesNotKeepOldSelectedCategory() {
        useComposition()
        host()
        openPopup()
        selectItem("mary-context-legend-TOOL_RESULTS")
        compose.runOnIdle { summary.value = MaryUsageSummary(null, 4000, null, sourceKey = "model-b", loading = true) }
        assertFalse(semanticText("mary-context-usage").contains("25%"))
        node("mary-context-legend-TOOL_RESULTS").assertDoesNotExist()
        compose.runOnIdle { summary.value = MaryUsageSummary(100, 2000, null,
            composition = ContextComposition.fromSegments(listOf(ContextSegment(ContextCategory.USER, 100))), sourceKey = "model-b") }
        node("mary-context-usage").performClick()
        compose.waitForIdle()
        node("mary-context-legend-USER").assertExists()
        node("mary-context-legend-TOOL_RESULTS").assertDoesNotExist()
        assertTrue(hasPercent(semanticText("mary-context-usage"), 5))
    }

    @Test fun categoryAndTraversalModesStayBoundedWithAllCategories() {
        useComposition()
        host()
        openPopup()
        ContextCategory.entries.forEach { node("mary-context-legend-list").performScrollToNode(hasTestTag("mary-context-legend-${it.name}")); node("mary-context-legend-${it.name}").assertIsDisplayed() }
        node("mary-context-mode-sequence").performClick()
        selectItem("mary-context-sequence-0")
        assertTrue(hasPercent(semanticText("mary-context-detail"), 10))
        assertPopupWithinViewport()
        screenshot("expanded-order", "mary-context-popup")
        node("mary-context-mode-category").performClick()
        selectItem("mary-context-legend-TOOL_RESULTS")
        node("mary-context-legend-TOOL_RESULTS").assertIsDisplayed()
        assertTrue(hasPercent(semanticText("mary-context-detail"), 35))
    }

    @Test fun backEscapeAndCloseDismissWithoutSendingDraftOrNavigating() {
        useComposition()
        host()
        compose.runOnIdle { draft.value = "保留草稿" }
        openPopup()
        dismissPopupWithKey(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        node("mary-context-popup").assertDoesNotExist()
        openPopup()
        dismissPopupWithKey(android.view.KeyEvent.KEYCODE_ESCAPE)
        compose.waitForIdle()
        node("mary-context-popup").assertDoesNotExist()
        openPopup()
        closePopup()
        compose.runOnIdle { assertEquals("保留草稿", draft.value); assertEquals(0, sent); assertEquals(0, navigated) }
    }

    @Test fun contextDismissThenSidebarKeepsTheSingleActivityDrawerWorking() {
        useComposition()
        host()
        openPopup()
        closePopup()
        node("mary-open-sidebar").performClick()
        node("mary-sidebar-overlay").assertExists()
        node("mary-context-popup").assertDoesNotExist()
        node("mary-sidebar-close").performClick()
        compose.waitForIdle()
        openPopup()
        node("mary-context-popup").assertExists()
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h568dp-mdpi")
    fun narrowDarkPopupKeepsSelectedDetailInsideViewport() {
        useComposition()
        host(ColorSchemeMode.Dark)
        openPopup()
        selectItem("mary-context-legend-TOOL_RESULTS")
        assertPopupWithinViewport()
        screenshot("expanded-narrow-dark", "mary-context-popup")
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h360dp-mdpi")
    fun shortLandscapePopupRemainsBoundedAndDismissible() {
        useComposition()
        host()
        openPopup()
        assertPopupWithinViewport()
        screenshot("expanded-landscape", "mary-context-popup")
        node("mary-context-close").performClick()
        node("mary-context-popup").assertDoesNotExist()
    }

    @Test fun providerBillingCountersAreNotUsedAsCompositionOrCapacity() {
        summary.value = MaryUsageSummary(1400, 4000, ChatEvent.Usage(inputTokens = 900, cachedInputTokens = 450), composition = composition, budgetCorrectionTokens = 400)
        host()
        openPopup()
        selectItem("mary-context-legend-TOOL_RESULTS")
        assertTrue(hasPercent(semanticText("mary-context-detail"), 35))
        assertTrue(hasPercent(semanticText("mary-context-capacity"), 25))
        assertTrue(semanticText("mary-context-provider").contains("900"))
        assertTrue(semanticText("mary-context-provider").contains("400"))
        assertTrue(hasPercent(semanticText("mary-context-provider"), 50))
    }

    @Test fun nativeOutsideTouchDismissesPopupWithoutSendingOrNavigating() {
        useComposition()
        host()
        openPopup()
        val popupView = popupView()
        compose.runOnUiThread {
            val event = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
            try { popupView.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        compose.waitForIdle()
        node("mary-context-popup").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, sent); assertEquals(0, navigated) }
    }

    @Test fun saturationDoesNotInventCategoryShares() {
        summary.value = MaryUsageSummary(Int.MAX_VALUE, 4000, null,
            composition = ContextComposition.fromSegments(listOf(ContextSegment(ContextCategory.SYSTEM, Int.MAX_VALUE), ContextSegment(ContextCategory.TOOL_RESULTS, Int.MAX_VALUE))))
        host()
        openPopup()
        assertFalse(semanticText("mary-context-detail").contains("%"))
        node("mary-context-legend-SYSTEM").assertDoesNotExist()
        assertFalse(semanticText("mary-context-popup").contains("NaN"))
        screenshot("saturated", "mary-context-popup")
    }

    @Test fun shorterSnapshotResetsOutOfRangeSequenceSelection() {
        useComposition()
        host()
        openPopup()
        node("mary-context-mode-sequence").performClick()
        selectItem("mary-context-sequence-7")
        compose.runOnIdle { summary.value = MaryUsageSummary(100, 4000, null,
            composition = ContextComposition.fromSegments(listOf(ContextSegment(ContextCategory.USER, 100))), sourceKey = "model-a") }
        compose.waitForIdle()
        node("mary-context-sequence-0").assertExists()
        assertTrue(hasPercent(semanticText("mary-context-detail"), 100))
    }

    @Test fun injectedImeInsetsPreserveDraftAndKeepPopupOnScreen() {
        useComposition()
        host()
        compose.runOnIdle { draft.value = "輸入中的草稿" }
        node("mary-composer-input").performClick()
        compose.runOnUiThread {
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, 280))
                .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true).build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(requireNotNull(activity).get().window.decorView, insets)
        }
        openPopup()
        assertPopupWithinViewport()
        screenshot("expanded-injected-ime", "mary-context-popup")
        compose.runOnIdle { assertEquals("輸入中的草稿", draft.value); assertEquals(0, sent) }
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h568dp-mdpi")
    fun largerTextStillAllowsSelectionAndDismissal() {
        useComposition()
        host(fontScale = 1.5f)
        openPopup()
        assertEquals("Native Popup must use actual large-text density", 1.5f, node("mary-context-detail").fetchSemanticsNode().layoutInfo.density.fontScale, 0.01f)
        selectItem("mary-context-legend-TOOL_RESULTS")
        assertPopupWithinViewport()
        node("mary-context-detail").performScrollTo().assertIsDisplayed()
        screenshot("expanded-large-text", "mary-context-popup")
        node("mary-context-close").performScrollTo().performClick()
        node("mary-context-popup").assertDoesNotExist()
    }

    private fun popupView(): android.view.View = (node("mary-context-popup").fetchSemanticsNode().root as ViewRootForTest).view.rootView
    private fun dismissPopupWithKey(code: Int) {
        val view = popupView()
        compose.runOnUiThread {
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, code))
            view.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, code))
        }
    }
    private fun hasPercent(text: String, value: Int) = Regex("(?<![0-9])" + value + "(?:[.,]0+)?\\s*%").containsMatchIn(text)

    private fun selectItem(tag: String) { node("mary-context-legend-list").performScrollToNode(hasTestTag(tag)); node(tag).performClick() }

    private fun useComposition() { summary.value = MaryUsageSummary(1000, 4000, null, composition = composition, sourceKey = "model-a") }
    private fun openPopup() { node("mary-context-usage").performClick(); compose.waitForIdle(); node("mary-context-popup").assertExists() }
    private fun closePopup() { node("mary-context-close").performClick(); compose.waitForIdle(); node("mary-context-popup").assertDoesNotExist() }
    private fun semanticText(tag: String): String {
        fun collect(node: androidx.compose.ui.semantics.SemanticsNode): String =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString { it.text } + " " +
                node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.joinToString() + " " + node.children.joinToString { collect(it) }
        return collect(node(tag).fetchSemanticsNode())
    }
    private fun assertPopupWithinViewport() {
        val popup = node("mary-context-popup").fetchSemanticsNode()
        val root = node("context-host").fetchSemanticsNode()
        val origin = popup.positionOnScreen - root.positionOnScreen
        assertTrue("Popup left=$origin", origin.x >= -1f)
        assertTrue("Popup top=$origin", origin.y >= -1f)
        assertTrue(origin.x + popup.size.width <= root.size.width + 1f)
        assertTrue(origin.y + popup.size.height <= root.size.height + 1f)
        assertTrue("Popup must remain compact", popup.size.width <= 340)
    }

    private fun host(mode: ColorSchemeMode = ColorSchemeMode.Light, fontScale: Float = 1f) {
        compose.runOnUiThread {
            ContextPreviewActivity.testFontScale = fontScale
            activity = Robolectric.buildActivity(ContextPreviewActivity::class.java).setup().also { controller ->
                controller.get().enableEdgeToEdge()
                controller.get().setContent {
                    MiuixTheme(ThemeController(mode)) {
                        val pageHost = remember { MarySidebarPageGestureHost() }
                        val gestures = remember { MarySidebarGestureState() }
                        val menuFocus = remember { FocusRequester() }
                        val focus = LocalFocusManager.current
                        val keyboard = LocalSoftwareKeyboardController.current
                        RegisterMarySidebarPageGestures(pageHost, gestures, enabled = !sidebar.value,
                            beforeOpen = { focus.clearFocus(force = true); keyboard?.hide(); menuFocus.requestFocus() },
                            onOpen = { sidebar.value = true })
                        CompositionLocalProvider(LocalMarySidebarPageGestureHost provides pageHost) {
                            Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
                                .marySidebarPageGesture(pageHost, true).testTag("context-host")) {
                                Box(Modifier.fillMaxSize().marySidebarBackground(pageHost)) {
                                    MaryFloatingChatLayout(bottomInset = 80.dp, modifier = Modifier.fillMaxSize(),
                                        content = { padding -> MaryLandingTranscript(padding, 56.dp, Modifier.testTag("context-transcript"), onPick = {}) },
                                        footer = { MaryComposer(value = draft.value, onValueChange = { draft.value = it },
                                            running = false, locked = false, configuring = false,
                                            provider = ProviderConfig(model = "local-preview"), onReasoningSelected = {},
                                            onSend = { sent++ }, onStop = {}) })
                                    MaryTopBar(summary.value, onOpenMenu = { sidebar.value = true }, menuFocusRequester = menuFocus)
                                }
                                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).marySidebarBackground(pageHost)) {
                                    RearNavigationBar(currentScreen = "mary", navigationBarMode = ModuleNavigationBarMode.NORMAL,
                                        backdrop = rememberLayerBackdrop(), onScreenSelected = { navigated++ })
                                }
                                MaryConversationSidebar(show = sidebar.value, revision = 0, currentSessionId = null,
                                    hasSession = false, locked = false, load = { emptyList() }, onDismiss = { sidebar.value = false },
                                    onNewChat = { sidebar.value = false }, onLock = {}, onOpenSettings = {}, onSelect = {}, onDelete = {},
                                    onClosed = { menuFocus.requestFocus() }, gestureState = gestures)
                                MarySidebarPagePreview(pageHost, true)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    /** Per-Activity configuration context also reaches Popup windows without mutating global resources. */
    class ContextPreviewActivity : ComponentActivity() {
        override fun attachBaseContext(newBase: android.content.Context) {
            val config = android.content.res.Configuration(newBase.resources.configuration)
            config.fontScale = testFontScale
            super.attachBaseContext(newBase.createConfigurationContext(config))
        }
        companion object { var testFontScale = 1f }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    /** Retain actual native windows; use real on-screen coordinates rather than fake popup paint. */
    private fun screenshot(name: String, popupTag: String? = null) {
        val directory = File(requireNotNull(System.getProperty("mary.ui.screenshotDir")), "context")
        check(directory.mkdirs() || directory.isDirectory)
        val baseNode = node("context-host")
        val base = baseNode.captureToImage().asAndroidBitmap()
        fun save(bitmap: Bitmap, suffix: String) = File(directory, "$name$suffix.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        save(base, if (popupTag == null) "" else "-activity")
        if (popupTag != null) {
            val popup = node(popupTag)
            val native = popup.captureToImage().asAndroidBitmap()
            save(native, "-popup")
            val origin = popup.fetchSemanticsNode().positionOnScreen - baseNode.fetchSemanticsNode().positionOnScreen
            val combined = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(combined).apply { drawBitmap(base, 0f, 0f, null); drawBitmap(native, origin.x, origin.y, null) }
            save(combined, "-native-composite")
            println("MARY_CONTEXT_CAPTURE name=$name activity=${base.width}x${base.height} popup=${native.width}x${native.height} origin=$origin")
            File(directory, "$name-position.txt").writeText("Native popup position relative to Activity: $origin. No scaling or replacement UI.\n")
        }
    }
}
