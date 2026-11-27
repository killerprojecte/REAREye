package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.CompactGlassTopBarScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ChatEntry
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.context.ContextCategory
import hk.uwu.reareye.agent.context.ContextComposition
import hk.uwu.reareye.agent.context.ContextSegment
import hk.uwu.reareye.ui.components.MaryComposer
import hk.uwu.reareye.ui.components.MaryContextProgressBlue
import hk.uwu.reareye.ui.components.MaryUsageSummary
import hk.uwu.reareye.ui.screen.*
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

/**
 * Production Mary transcript/list, native header, composer, context popup and sidebar.
 * Only the transcript/session data are local fixtures. No Mary controller, network,
 * fake header, synthetic glass painting or programmatic scroll is used.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryNativeTopBarTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var list: LazyListState
    private lateinit var inputModes: InputModeManager
    private var topPadding = 0.dp
    private var openedSidebar = 0
    private var closedSidebar = 0
    private var sent = 0
    private val draft = mutableStateOf("")
    private lateinit var folder: File

    private val entries = List(48) { index ->
        if (index % 2 == 0) ChatEntry.UserMessage("turn-$index", "請幫我整理第 ${index / 2 + 1} 個想法。")
        else ChatEntry.AssistantMessage("turn-$index",
            "我們先保留原本的目標，再一步一步檢查細節。這是第 ${index / 2 + 1} 段完整的對話內容。\n\n**下一步**：確認文字、操作和閱讀位置都能保持穩定。")
    }
    private val summary = MaryUsageSummary(1000, 4000, null,
        composition = ContextComposition.fromSegments(listOf(
            ContextSegment(ContextCategory.SYSTEM, 100),
            ContextSegment(ContextCategory.USER, 250, 0),
            ContextSegment(ContextCategory.ASSISTANT, 650, 1),
        )), sourceKey = "native-toolbar-fixture")

    @Before fun requireCaptureProfile() {
        assumeTrue("Run with -PuiCapture=true -PmaryUiResources=true",
            java.lang.Boolean.getBoolean("ui.capture.enabled") && java.lang.Boolean.getBoolean("mary.ui.resources"))
    }
    @After fun close() { compose.runOnUiThread { activity?.close(); activity = null } }

    @Test fun miuixLightTranscriptKeepsNativeGlassScrollActionsAndFocus() = exercise(MixUiBackend.Miuix, false)
    @Test fun miuixDarkTranscriptKeepsNativeGlassScrollActionsAndFocus() = exercise(MixUiBackend.Miuix, true)
    @Test fun mochiLightTranscriptKeepsNativeGlassScrollActionsAndFocus() = exercise(MixUiBackend.Mochi, false)
    @Test fun mochiDarkTranscriptKeepsNativeGlassScrollActionsAndFocus() = exercise(MixUiBackend.Mochi, true)

    @Test fun miuixLightAsymmetricTitleAndShadowCapture() = asymmetricTitle(MixUiBackend.Miuix, false)
    @Test fun miuixDarkAsymmetricTitleAndShadowCapture() = asymmetricTitle(MixUiBackend.Miuix, true)
    @Test fun mochiLightAsymmetricTitleAndShadowCapture() = asymmetricTitle(MixUiBackend.Mochi, false)
    @Test fun mochiDarkAsymmetricTitleAndShadowCapture() = asymmetricTitle(MixUiBackend.Mochi, true)

    @Test fun mochiNoButtonsCompactTitleActualGlyphCenter() = noButtonTitle(0)
    @Test fun mochiNoButtonsGlassTitleActualGlyphCenter() = noButtonTitle(1)
    @Test fun mochiNoButtonsColumnTitleActualGlyphCenter() = noButtonTitle(2)
    @Test fun mochiNoButtonsLazyTitleActualGlyphCenter() = noButtonTitle(3)
    @Test fun mochiNoButtonsGlassScaffoldTitleActualGlyphCenter() = noButtonTitle(4)

    private fun noButtonTitle(mode: Int) {
        val title = "没有按钮的标题"
        for (rtl in listOf(false, true)) {
            compose.runOnUiThread {
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().enableEdgeToEdge()
                activity!!.get().setContent {
                    MixUiHost(MixUiBackend.Mochi, MixColorSchemeMode.Light) {
                        CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides
                            if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr) {
                            when (mode) {
                                0 -> hk.uwu.mixui.components.CompactTopBar(title)
                                1 -> CompactGlassTopBarScene(isContentScrolled = true) {
                                    hk.uwu.mixui.components.CompactGlassTopBar(title)
                                }
                                2 -> hk.uwu.mixui.components.LargeTitleColumnScaffold(title) {
                                    repeat(40) { hk.uwu.mixui.components.Text("Row $it", Modifier.height(48.dp)) }
                                }
                                4 -> hk.uwu.mixui.components.GlassTopBarScaffold(title, isContentScrolled = true) { padding ->
                                    Box(Modifier.fillMaxSize().padding(padding))
                                }
                                else -> hk.uwu.mixui.components.LargeTitleLazyScaffold(title) {
                                    items(40) { hk.uwu.mixui.components.Text("Row $it", Modifier.height(48.dp)) }
                                }
                            }
                        }
                    }
                }
            }
            frames()
            folder = File(System.getProperty("ui.capture.output", "build/ui-captures"), "mochi-no-buttons").apply { mkdirs() }
            save("mode$mode-rtl$rtl-expanded")
            writeNativeTitleGeometry("mochi-no-buttons", "mode$mode-rtl$rtl-expanded", compose.nativeTitleGeometry(title))
            if (mode == 2 || mode == 3) {
                compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }; frames()
            }
            val compact = compose.nativeTitleGeometry(title).first { it.layout.layoutInput.style.fontSize.value == 17f }
            val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val glyphs = title.indices.map { compact.layout.getBoundingBox(it) }
            val center = compact.bounds.left + (glyphs.minOf { it.left } + glyphs.maxOf { it.right }) / 2f
            assertEquals("No-button actual glyph center mode=$mode rtl=$rtl", root.center.x, center, 1.5f)
            writeNativeTitleGeometry("mochi-no-buttons", "mode$mode-rtl$rtl-collapsed", listOf(compact))
            save("mode$mode-rtl$rtl-collapsed")
            compose.runOnUiThread {
                androidx.core.view.ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView,
                    androidx.core.view.WindowInsetsCompat.Builder()
                        .setDisplayCutout(androidx.core.view.DisplayCutoutCompat(android.graphics.Rect(48, 0, 0, 0),
                            listOf(android.graphics.Rect(0, 0, 48, 100))))
                        .setInsets(androidx.core.view.WindowInsetsCompat.Type.displayCutout(), androidx.core.graphics.Insets.of(48, 0, 0, 0))
                        .setVisible(androidx.core.view.WindowInsetsCompat.Type.displayCutout(), true).build())
            }
            frames()
            val insetTitle = compose.nativeTitleGeometry(title).first { it.layout.layoutInput.style.fontSize.value == 17f }
            val insetGlyphs = title.indices.map { insetTitle.layout.getBoundingBox(it) }
            val insetCenter = insetTitle.bounds.left + (insetGlyphs.minOf { it.left } + insetGlyphs.maxOf { it.right }) / 2f
            assertEquals("No-button title centers safe width after 48dp left cutout mode=$mode rtl=$rtl", root.center.x + 24f, insetCenter, 1.5f)
            save("mode$mode-rtl$rtl-left-cutout")
            compose.runOnUiThread { activity!!.close(); activity = null }
        }
    }

    private fun asymmetricTitle(backend: MixUiBackend, dark: Boolean) {
        for (rtl in listOf(false, true)) {
            for (titleCase in listOf("short", "medium", "long")) {
                val title = when (titleCase) {
                    "short" -> "Mary"
                    "medium" -> "Mary session"
                    else -> "Mary conversation with a very long title"
                }
                compose.runOnUiThread {
                    activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                    activity!!.get().enableEdgeToEdge()
                    activity!!.get().setContent {
                        MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                            CompositionLocalProvider(
                                androidx.compose.ui.platform.LocalLayoutDirection provides
                                    if (rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr,
                                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(1f, 1.3f),
                            ) {
                                CompactGlassTopBarScene(isContentScrolled = true) { body ->
                                    Box(Modifier.fillMaxSize().then(body))
                                    hk.uwu.mixui.components.CompactGlassTopBar(title,
                                        navigationIcon = {
                                            hk.uwu.mixui.components.CompactTopBarIconButton({}, Modifier.testTag("asym-nav")) {
                                                hk.uwu.mixui.components.Text("N")
                                            }
                                        }, actions = {
                                            repeat(3) { index ->
                                                hk.uwu.mixui.components.CompactTopBarIconButton({}, Modifier.testTag("asym-action-$index")) {
                                                    hk.uwu.mixui.components.Text("$index")
                                                }
                                            }
                                        })
                                }
                            }
                        }
                    }
                }
                frames()
                val nav = bounds("asym-nav")
                val actions = (0..2).map { bounds("asym-action-$it") }
                val text = compose.onNodeWithText(title, useUnmergedTree = true).getUnclippedBoundsInRoot()
                val left = if (rtl) actions.maxOf { it.right.value } else nav.right.value
                val right = if (rtl) nav.left.value else actions.minOf { it.left.value }
                assertTrue("Title must stay between controls", text.left.value >= left && text.right.value <= right)
                val screenCenter = 200f
                val halfTitle = (text.right.value - text.left.value) / 2f
                val canCenter = screenCenter - halfTitle >= left + 8f && screenCenter + halfTitle <= right - 8f
                val expected = if (backend == MixUiBackend.Mochi && canCenter) screenCenter else (left + right) / 2f
                assertEquals("Title prefers whole-bar center, then actual free interval",
                    expected, (text.left.value + text.right.value) / 2f, 1f)
                if (backend == MixUiBackend.Miuix && !dark) {
                    // Native controls end at y=49dp, the compact layout at y=52dp.
                    // Check actual shadow pixels beyond that layout, not only bounds.
                    val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
                    val y = (nav.bottom.value + 6f).toInt()
                    val shadow = android.graphics.Color.red(bitmap.getPixel(((nav.left.value + nav.right.value) / 2f).toInt(), y))
                    val background = android.graphics.Color.red(bitmap.getPixel(((left + right) / 2f).toInt(), y))
                    assertTrue("Native shadow must remain visible below the compact bar", background - shadow > 2)
                }
                val geometry = compose.nativeTitleGeometry(title).single()
                assertEquals("Only titles wider than the free interval should ellipsize", titleCase == "long", geometry.layout.isLineEllipsized(0))
                folder = File(System.getProperty("ui.capture.output", "build/ui-captures"), "mary-asymmetric-title").apply { mkdirs() }
                writeNativeTitleGeometry("mary-asymmetric-title", "$backend-$dark-rtl$rtl-$titleCase", listOf(geometry))
                save("$backend-$dark-rtl$rtl-$titleCase")
                compose.runOnUiThread { activity!!.close(); activity = null }
            }
        }
    }

    @Test fun mochiIosAndNothingKeepBlueContextAndOrdinaryPopupTabs() {
        for (style in listOf(MixMochiStyle.Ios, MixMochiStyle.Nothing)) {
            for (dark in listOf(false, true)) exercise(MixUiBackend.Mochi, dark, style)
        }
    }

    private fun exercise(backend: MixUiBackend, dark: Boolean, style: MixMochiStyle = MixMochiStyle.Default) {
        host(backend, dark, style)
        node("mary-transcript-turn-0").assertIsDisplayed()
        compose.runOnIdle { assertFalse(list.canScrollBackward); assertEquals(48, list.layoutInfo.totalItemsCount) }
        assertControls()
        val initialGeometry = geometry()
        val initialMenu = pixels("mary-open-sidebar")
        val initialContext = pixels("mary-context-usage")
        save("initial")

        // The actual production LazyColumn receives real pointer movement. No scrollToItem,
        // fake canScrollBackward flag or native-bar state assignment can make this pass.
        node("mary-native-transcript").performTouchInput {
            swipe(Offset(center.x, height * 0.72f), Offset(center.x, height * 0.25f), 700)
        }
        frames()
        compose.runOnIdle { assertTrue("The transcript must really move", list.canScrollBackward) }
        assertEquals("Header and content padding must not collapse", initialGeometry, geometry())
        assertControls()
        assertMaterialChanged("menu", initialMenu, pixels("mary-open-sidebar"))
        assertMaterialChanged("context", initialContext, pixels("mary-context-usage"))
        save("scrolled")
        val readingPosition = compose.runOnIdle { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }

        compose.runOnIdle { assertTrue(inputModes.requestInputMode(InputMode.Keyboard)) }
        repeat(2) { attempt ->
            if (attempt == 0) node("mary-open-sidebar").performTouchInput { click() }
            else node("mary-open-sidebar").performKeyInput { pressKey(Key.Enter) }
            frames()
            node("mary-sidebar-panel").assertIsDisplayed()
            if (attempt == 0) save("sidebar")
            node("mary-sidebar-close").performClick()
            frames()
            node("mary-sidebar-panel").assertDoesNotExist()
            node("mary-open-sidebar").assertIsFocused()
            compose.runOnIdle { assertEquals(attempt + 1, openedSidebar); assertEquals(attempt + 1, closedSidebar) }
        }

        repeat(2) { attempt ->
            if (attempt == 0) node("mary-context-usage").performTouchInput { click() }
            else node("mary-context-usage").performKeyInput { pressKey(Key.Spacebar) }
            frames()
            compose.onAllNodesWithTag("mary-context-popup").assertCountEquals(1)
            node("mary-context-popup").assertIsDisplayed()
            node("mary-context-capacity").assert(hasAnyDescendant(hasText("≈1.0K / 4.0K")))
            if (backend == MixUiBackend.Miuix) node("compact-glass-tabs-miuix").assertIsDisplayed()
            else {
                node("compact-glass-tabs-miuix").assertDoesNotExist()
                node("compact-tabs-track").assertIsDisplayed()
            }
            tab(true).performClick()
            frames()
            tab(true).assertIsSelected()
            node("mary-context-sequence-0").assertIsDisplayed()
            if (attempt == 0) save("context-sequence")
            tab(false).performClick()
            frames()
            tab(false).assertIsSelected()
            node("mary-context-legend-ASSISTANT").assertIsDisplayed()
            if (attempt == 0) save("context-category")
            // Close and Escape exercise both explicit dismissal and the real key handler.
            if (attempt == 0) node("mary-context-close").performClick()
            else node("mary-context-popup").performKeyInput { pressKey(Key.Escape) }
            frames()
            node("mary-context-popup").assertDoesNotExist()
            node("mary-context-usage").assertIsFocused()
            assertControls()
        }
        compose.runOnIdle {
            assertEquals("Opening a toolbar surface must not move the transcript", readingPosition,
                list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
            assertEquals(0, sent)
            assertEquals(48, list.layoutInfo.totalItemsCount)
        }

        repeat(12) {
            if (compose.runOnIdle { list.canScrollBackward }) {
                node("mary-native-transcript").performTouchInput {
                    swipe(Offset(center.x, height * 0.25f), Offset(center.x, height * 0.72f), 700)
                }
                frames()
            }
        }
        compose.runOnIdle { assertFalse("Pointer scrolling must restore the first row", list.canScrollBackward) }
        node("mary-transcript-turn-0").assertIsDisplayed()
        assertEquals(initialGeometry, geometry())
        assertControls()
        // Material has gone, without fading out the menu glyph or the blue progress ring.
        assertImagesNear(initialMenu, pixels("mary-open-sidebar"))
        assertImagesNear(initialContext, pixels("mary-context-usage"))
        save("restored")
        File(folder, "evidence.txt").writeText(
            "Native Robolectric/Compose production-component capture, not MainActivity or a physical device.\n" +
                "Backend=$backend, style=$style, dark=$dark. Forty-eight real UserBubble/AssistantBubble rows.\n" +
                "Initial, pointer-scrolled, sidebar, context category/sequence, restored-top frames.\n" +
                "Verified two toolbar actions; stable geometry/padding; 20dp blue ring, 25% semantics; repeated dismiss/focus return; no sends.\n")
    }

    private fun host(backend: MixUiBackend, dark: Boolean, style: MixMochiStyle) {
        openedSidebar = 0; closedSidebar = 0; sent = 0; draft.value = ""
        folder = File(requireNotNull(System.getProperty("ui.capture.output")),
            "mary-native-toolbar/${backend.name}/${style.name}/${if (dark) "dark" else "light"}").apply { mkdirs() }
        listOf("initial", "scrolled", "sidebar", "context-sequence", "context-category", "restored")
            .forEach { File(folder, "$it.png").delete() }
        File(folder, "evidence.txt").delete()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend = backend, colorMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light, mochiStyle = style) {
                    val state = rememberLazyListState()
                    val menuFocus = remember { FocusRequester() }
                    val blocks = remember { BlockExpansion() }
                    var sidebar by remember { mutableStateOf(false) }
                    inputModes = LocalInputModeManager.current
                    SideEffect { list = state }
                    CompactGlassTopBarScene(isContentScrolled = state.canScrollBackward) { bodyModifier ->
                        Box(Modifier.fillMaxSize().testTag("mary-native-root")) {
                            Scaffold(popupHost = {}, topBar = {
                                Box(Modifier.testTag("mary-native-topbar")) {
                                    MaryTopBar(summary, onOpenMenu = { openedSidebar++; sidebar = true }, menuFocusRequester = menuFocus)
                                }
                            }) { padding ->
                                SideEffect { topPadding = padding.calculateTopPadding() }
                                MaryFloatingChatLayout(bottomInset = 32.dp, modifier = Modifier.fillMaxSize().then(bodyModifier),
                                    content = { bottomPadding ->
                                        MaryConversationList(state = state,
                                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp,
                                                top = padding.calculateTopPadding() + 8.dp, bottom = bottomPadding),
                                            gestureModifier = Modifier.testTag("mary-native-transcript")) {
                                            itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                                                Column(Modifier.testTag("mary-transcript-${entry.id}")) {
                                                    if (index > 0) Spacer(Modifier.height(16.dp))
                                                    EntryAppearance(new = false) {
                                                        when (entry) {
                                                            is ChatEntry.UserMessage -> UserBubble(entry.text)
                                                            is ChatEntry.AssistantMessage -> AssistantBubble(entry.text, entry.streaming, entry.reasoning, blocks, entry.id)
                                                            else -> error("Unexpected fixture row")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }, footer = {
                                        MaryComposer(value = draft.value, onValueChange = { draft.value = it }, running = false,
                                            locked = false, configuring = false, provider = ProviderConfig(model = "offline-fixture"),
                                            onReasoningSelected = {}, onSend = { sent++ }, onStop = {})
                                    })
                            }
                            MaryConversationSidebar(show = sidebar, revision = 0, currentSessionId = "offline-fixture",
                                hasSession = true, locked = false, load = { emptyList() }, onDismiss = { sidebar = false },
                                onNewChat = { sidebar = false }, onLock = {}, onOpenSettings = {}, onSelect = {}, onDelete = {},
                                onClosed = { closedSidebar++; menuFocus.requestFocus() })
                        }
                    }
                }
            }
        }
        frames()
    }

    private fun assertControls() {
        // Both native controls merge their icon/ring semantics into the actionable node.
        // Accessibility must see the label, role and action on that same merged control;
        // the unmerged tagged layout node need not own the icon's description directly.
        listOf("mary-open-sidebar", "mary-context-usage").forEach { tag ->
            compose.onAllNodesWithTag(tag, useUnmergedTree = false).assertCountEquals(1)
            accessibleControl(tag).assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            // Measure the actual control, rather than its merged descendant bounds.
            assertEquals(48f, bounds(tag).width.value, 0.5f)
            assertEquals(48f, bounds(tag).height.value, 0.5f)
        }
        compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag("mary-native-topbar")),
            useUnmergedTree = false).assertCountEquals(2)
        accessibleControl("mary-open-sidebar").assertContentDescriptionEquals(
            activity!!.get().getString(R.string.mary_sidebar_open))
        accessibleControl("mary-context-usage")
            .assertContentDescriptionEquals(activity!!.get().getString(R.string.mary_chat_context_action,
                String.format(java.util.Locale.getDefault(), "≈%.1f%%", 25.0)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.25f, 0f..1f)))
        assertEquals(20f, bounds("mary-context-ring").width.value, 0.5f)
        assertEquals(20f, bounds("mary-context-ring").height.value, 0.5f)
        val ring = pixels("mary-context-ring")
        val blue = ring[(ring.width * 0.83f).toInt(), (ring.height * 0.17f).toInt()]
        assertColorNear(MaryContextProgressBlue, blue, 0.08f)
    }

    private fun geometry() = Triple(bounds("mary-open-sidebar"), bounds("mary-context-usage"), topPadding)
    private fun tab(sequence: Boolean) = compose.onNode(
        hasAnyAncestor(hasTestTag("mary-context-modes")) and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and
            hasAnyDescendant(hasText(activity!!.get().getString(if (sequence) R.string.mary_context_sequence_mode else R.string.mary_context_category_mode))),
        useUnmergedTree = true)
    private fun accessibleControl(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = false)
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun bounds(tag: String): DpRect = node(tag).getUnclippedBoundsInRoot()
    private fun pixels(tag: String) = node(tag).captureToImage().toPixelMap()
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun save(name: String) {
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun assertColorNear(expected: Color, actual: Color, tolerance: Float) {
        assertEquals(expected.red, actual.red, tolerance)
        assertEquals(expected.green, actual.green, tolerance)
        assertEquals(expected.blue, actual.blue, tolerance)
    }
    private fun assertMaterialChanged(label: String, before: PixelMap, after: PixelMap) {
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        var changed = 0
        // Outside the 24dp glyph/ring and away from the extreme control boundary.
        for (y in 3 until before.height - 3) for (x in 3 until before.width - 3) {
            val dx = x - before.width / 2f; val dy = y - before.height / 2f
            if (dx * dx + dy * dy in 15f * 15f..21f * 21f && difference(before[x, y], after[x, y]) > 0.018f) changed++
        }
        assertTrue("$label must gain visible native material outside its glyph; changed=$changed", changed > 12)
    }
    private fun assertImagesNear(before: PixelMap, after: PixelMap) {
        assertEquals(before.width, after.width); assertEquals(before.height, after.height)
        var total = 0f
        for (y in 0 until before.height) for (x in 0 until before.width) total += difference(before[x, y], after[x, y])
        assertTrue("Restored top must remove glass without hiding glyphs", total / (before.width * before.height) < 0.012f)
    }
    private fun difference(a: Color, b: Color) = (abs(a.red - b.red) + abs(a.green - b.green) + abs(a.blue - b.blue)) / 3f
}
