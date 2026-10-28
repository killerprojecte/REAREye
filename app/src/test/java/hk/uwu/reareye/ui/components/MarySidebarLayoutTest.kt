package hk.uwu.reareye.ui.components

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Offset
import hk.uwu.reareye.ui.screen.MarySidebarGestureState
import hk.uwu.reareye.ui.screen.MaryLandingTranscript
import hk.uwu.reareye.ui.screen.MaryConversationList
import androidx.compose.foundation.lazy.rememberLazyListState
import hk.uwu.reareye.ui.screen.LocalMarySidebarPageGestureHost
import hk.uwu.reareye.ui.screen.MarySidebarPagePreview
import hk.uwu.reareye.ui.screen.MarySidebarPageGestureHost
import hk.uwu.reareye.ui.screen.RegisterMarySidebarPageGestures
import hk.uwu.reareye.ui.screen.marySidebarBackground
import hk.uwu.reareye.ui.screen.marySidebarPageGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.data.AgentSessionSummary
import hk.uwu.reareye.ui.screen.MaryConversationSidebar
import hk.uwu.reareye.ui.screen.MaryFloatingChatLayout
import hk.uwu.reareye.ui.screen.MaryTopBar
import hk.uwu.reareye.ui.components.navigation.RearNavigationBar
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File

/** Native production sidebar, header, composer and navigation with local sessions; not a live controller. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarySidebarLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val showing = mutableStateOf(false)
    private val maryActive = mutableStateOf(true)
    private val gestures = MarySidebarGestureState()
    private var newChats = 0
    private var locks = 0
    private var selected: String? = null
    private var navigationClicks = 0
    private val draft = mutableStateOf("")
    private var sentMessages = 0
    private var horizontalChild: ScrollState? = null
    private lateinit var inputModes: InputModeManager
    private val revision = mutableStateOf(0)
    private val sessions = (0 until 30).map {
        AgentSessionSummary("session-$it", listOf("週末的靈感整理", "一起規劃今天", "關於記憶與時間", "Mary 的日常")[it % 4] + " · ${it + 1}",
            1793088000000L - it * 60000L, 1793088000000L - it * 60000L, 6 + it,
            locked = it == 2)
    }

    @Before fun resources() {
        Assume.assumeTrue("Run with -PmaryUiResources=true", java.lang.Boolean.getBoolean("mary.ui.resources"))
    }
    @After fun cleanup() { compose.runOnUiThread { activity?.close() } }

    @Test fun openingKeepsOneActivityAndOnePanelThroughEveryCompletionFrame() {
        host(transcriptMode = "landing")
        assertNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
        compose.mainClock.autoAdvance = false
        val root = node("sidebar-fixture")
        val baselineRed = android.graphics.Color.red(root.captureToImage().asAndroidBitmap().getPixel(390, 450))
        root.performTouchInput { down(Offset(80f, 280f)); moveTo(Offset(240f, 280f), delayMillis = 300) }
        compose.mainClock.advanceTimeBy(64)
        fun panel() = node("mary-sidebar-stable-panel")
        val identity = panel().fetchSemanticsNode().id
        assertEquals(160f, panel().getUnclippedBoundsInRoot().right.value, 2f)
        screenshot("continuous-00-held", "sidebar-fixture")
        root.performTouchInput { advanceEventTime(200); up() }
        var previousRight = 159f
        var full = false
        for (frame in 0..40) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            assertNull("No Android Window may be introduced at frame $frame", org.robolectric.shadows.ShadowDialog.getLatestDialog())
            assertEquals("Panel composition identity must survive preview-to-interactive transition at frame $frame", identity, panel().fetchSemanticsNode().id)
            val right = panel().getUnclippedBoundsInRoot().right.value
            assertTrue("Panel must never vanish or reverse during opening frame $frame: $previousRight -> $right", right >= previousRight - 1f)
            if (right >= 327f) full = true
            if (full) assertEquals("Fully-open panel must not flash away", 328f, right, 1f)
            previousRight = right
            val bitmap = root.captureToImage().asAndroidBitmap()
            val scrimRed = android.graphics.Color.red(bitmap.getPixel(390, 450))
            if (full) assertEquals("Scrim must have one owner, not double or disappear", baselineRed * 0.68, scrimRed.toDouble(), 2.0)
            println("MARY_CONTINUOUS_FRAME index=$frame panelId=$identity right=$right scrimRed=$scrimRed dialog=false")
            if (frame in listOf(0, 1, 2, 4, 8, 9, 10, 12, 16, 24, 40)) screenshot("continuous-%02d".format(frame + 1), "sidebar-fixture")
        }
        assertTrue("Bounded opening animation reaches full width", full)
        compose.mainClock.autoAdvance = true
        node("mary-sidebar-close").assertIsDisplayed()
    }

    @Test fun keyboardTabTraversalStaysInsideSidebarAndBackgroundIsHidden() {
        host(transcriptMode = "landing")
        compose.runOnIdle { assertTrue(inputModes.requestInputMode(InputMode.Keyboard)) }
        open()
        node("sidebar-underlay").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        node("sidebar-nav-underlay").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
        repeat(40) { index ->
            compose.runOnUiThread {
                val shift = if (index >= 20) android.view.KeyEvent.META_SHIFT_ON else 0
                val decor = requireNotNull(activity).get().window.decorView
                decor.dispatchKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_TAB, 0, shift))
                decor.dispatchKeyEvent(android.view.KeyEvent(0, 0, android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_TAB, 0, shift))
            }
            compose.waitForIdle()
            compose.onNode(isFocused(), useUnmergedTree = true).assert(hasAnyAncestor(hasTestTag("mary-sidebar-overlay")))
            node("mary-composer-input").assertIsNotFocused()
        }
        node("mary-sidebar-close").performClick()
        compose.waitForIdle()
        node("sidebar-underlay").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
        node("sidebar-nav-underlay").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility))
        node("mary-open-sidebar").assertIsFocused()
    }

    @Test fun closingAnimationStillBlocksUnderlyingNavigationUntilCompletelyGone() {
        host(transcriptMode = "landing")
        open()
        compose.mainClock.autoAdvance = false
        node("mary-sidebar-close").performClick()
        compose.mainClock.advanceTimeBy(64)
        node("mary-sidebar-overlay").assertExists()
        node("sidebar-fixture").performTouchInput { click(Offset(350f, 760f)) }
        compose.runOnIdle { assertEquals(0, navigationClicks); assertTrue(maryActive.value) }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        node("mary-sidebar-overlay").assertDoesNotExist()
        node("fixture-navigation").performTouchInput { click(Offset(50f, center.y)) }
        compose.runOnIdle { assertEquals(1, navigationClicks) }
    }

    @Test fun escapeDuringHeldOpeningWithoutFocusedComposerCancelsWithoutStealingFocus() {
        host(transcriptMode = "landing")
        val initialListeners = unhandledKeyListenerCount()
        node("mary-composer-input").assertIsNotFocused()
        val root = node("sidebar-fixture")
        root.performTouchInput { down(Offset(80f, 280f)); moveTo(Offset(240f, 280f), delayMillis = 300) }
        node("mary-sidebar-preview").assertExists()
        assertEquals(initialListeners + 1, unhandledKeyListenerCount())
        node("mary-composer-input").assertIsNotFocused()
        compose.runOnUiThread {
            dispatchActivityAndUnhandledKey(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ESCAPE))
            dispatchActivityAndUnhandledKey(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ESCAPE))
        }
        root.performTouchInput { advanceEventTime(200); up() }
        compose.waitForIdle()
        node("mary-sidebar-overlay").assertDoesNotExist()
        assertEquals("Native listener must be removed when the overlay is gone", initialListeners, unhandledKeyListenerCount())
        compose.runOnIdle { assertFalse(showing.value); assertFalse(requireNotNull(activity).get().isFinishing) }
    }

    @Test fun rightDragDuringClosingCannotRestartOpeningFromPanelOrScrim() {
        host(transcriptMode = "landing")
        for (startX in listOf(80f, 290f)) {
            open()
            compose.mainClock.autoAdvance = false
            node("mary-sidebar-close").performClick()
            compose.mainClock.advanceTimeBy(64)
            node("mary-sidebar-overlay").assertExists()
            node("sidebar-fixture").performTouchInput {
                down(Offset(startX, 450f))
                moveTo(Offset(startX + 100f, 450f), delayMillis = 300)
                advanceEventTime(200)
                up()
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            node("mary-sidebar-overlay").assertDoesNotExist()
            compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress) }
        }
    }

    @Test fun productionLandingDiagonalRightSwipeOpensSidebar() {
        host(transcriptMode = "landing")
        assertProductionSwipeOpens(startX = 32f)
    }

    @Test fun productionConversationDiagonalRightSwipeOpensSidebar() {
        host(transcriptMode = "conversation")
        assertProductionSwipeOpens(startX = 32f)
    }

    @Test fun productionLandingRightSwipeAwayFromEdgeOpensSidebar() {
        host(transcriptMode = "landing")
        screenshot("production-landing-closed", "sidebar-fixture")
        assertProductionSwipeOpens(startX = 80f, verticalDelta = 0f)
        screenshot("production-landing-open", "mary-sidebar-modal")
    }

    @Test fun productionConversationRightSwipeAwayFromEdgeOpensSidebar() {
        host(transcriptMode = "conversation")
        assertProductionSwipeOpens(startX = 80f, verticalDelta = 0f)
        screenshot("production-conversation-open", "mary-sidebar-modal")
    }

    @Test fun productionTopBarRightSwipeOpensSidebar() {
        host(transcriptMode = "landing")
        node("sidebar-fixture").performTouchInput {
            swipe(Offset(80f, 25f), Offset(320f, 25f), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
    }

    @Test fun productionComposerRightSwipePreservesDraftWithoutSending() {
        draft.value = "保留這段尚未送出的草稿"
        host(transcriptMode = "conversation")
        node("mary-composer-input").performTouchInput {
            swipe(Offset(12f, center.y), Offset(width - 12f, center.y), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        node("mary-sidebar-close").performClick()
        compose.waitForIdle()
        node("mary-composer-input").assertTextContains("保留這段尚未送出的草稿")
        compose.runOnIdle { assertEquals(0, sentMessages) }
    }

    @Test fun productionNavigationRightSwipeOpensWithoutSwitchingTab() {
        host(transcriptMode = "conversation")
        node("fixture-navigation").performTouchInput {
            swipe(Offset(80f, center.y), Offset(320f, center.y), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, navigationClicks) }
    }

    @Test fun productionConversationVerticalDragStillScrollsWithoutOpening() {
        host(transcriptMode = "conversation")
        node("production-row-0").assertIsDisplayed()
        node("sidebar-transcript").performTouchInput {
            swipe(Offset(120f, 580f), Offset(140f, 180f), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        node("production-row-0").assertIsNotDisplayed()
        compose.runOnIdle { assertNull(selected) }
    }

    @Test fun productionLandingSuggestionTapStillSelectsWithoutOpening() {
        host(transcriptMode = "landing")
        val suggestion = requireNotNull(activity).get().getString(hk.uwu.reareye.R.string.mary_suggestion_0_0)
        compose.onNodeWithText(suggestion).performClick()
        compose.runOnIdle { assertEquals(suggestion, selected) }
        node("mary-sidebar-modal").assertDoesNotExist()
    }

    @Test fun productionConfigTabSlowSwipeOpensWithoutQuickMenu() {
        host(transcriptMode = "conversation")
        node("fixture-navigation").performTouchInput {
            swipe(Offset(250f, center.y), Offset(380f, center.y), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        compose.onAllNodes(isPopup()).assertCountEquals(0)
        compose.runOnIdle { assertEquals(0, navigationClicks) }
    }

    @Test fun productionConfigTabStationaryLongPressKeepsQuickMenu() {
        host(transcriptMode = "conversation")
        node("fixture-navigation").performTouchInput {
            down(Offset(250f, center.y))
            moveTo(Offset(250f, center.y), delayMillis = 700)
        }
        compose.waitForIdle()
        // This hold-to-select menu intentionally dismisses on UP; inspect it while still held.
        compose.onNode(isPopup()).assertIsDisplayed()
        node("mary-sidebar-modal").assertDoesNotExist()
        node("fixture-navigation").performTouchInput { up() }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
    }

    @Test fun productionHorizontalChildKeepsItsScrollGesture() {
        host(transcriptMode = "horizontal")
        compose.runOnIdle { assertEquals(120, requireNotNull(horizontalChild).value) }
        node("production-horizontal-child").performTouchInput {
            swipe(Offset(80f, center.y), Offset(320f, center.y), durationMillis = 600)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(requireNotNull(horizontalChild).value < 120) }
        node("mary-sidebar-modal").assertDoesNotExist()
    }

    @Test @Config(shadows = [MaryChatLayoutTest.NoSurfaceMagnifierShadow::class])
    fun productionComposerLongPressSelectionDoesNotOpenSidebar() {
        draft.value = "保留這段尚未送出的草稿"
        host(transcriptMode = "conversation")
        node("mary-composer-input").performClick()
        node("mary-composer-input").performTouchInput {
            down(Offset(24f, center.y))
            advanceEventTime(700)
            moveTo(Offset(width - 24f, center.y), delayMillis = 400)
            up()
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        node("mary-composer-input").assertTextContains("保留這段尚未送出的草稿")
        val selection = node("mary-composer-input").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertFalse("The real text field must retain a selected range", selection.collapsed)
        compose.runOnIdle { assertEquals(0, sentMessages) }
    }

    @Test fun productionRegistrationRemovalDisablesPageSwipeAndReentryRestoresIt() {
        host(transcriptMode = "landing")
        node("fixture-navigation").performTouchInput { click(Offset(50f, center.y)) }
        compose.runOnIdle { assertFalse(maryActive.value); assertEquals(1, navigationClicks) }
        node("sidebar-fixture").performTouchInput {
            swipe(Offset(80f, 280f), Offset(320f, 280f), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { maryActive.value = true }
        compose.waitForIdle()
        assertProductionSwipeOpens(startX = 80f, verticalDelta = 0f)
    }

    @Test fun productionRegistrationRemovalDuringDragCannotOpenLater() {
        host(transcriptMode = "conversation")
        node("sidebar-fixture").performTouchInput {
            down(Offset(80f, 280f))
            moveTo(Offset(240f, 280f), delayMillis = 300)
        }
        node("mary-sidebar-preview").assertExists()
        compose.runOnIdle { maryActive.value = false }
        node("sidebar-fixture").performTouchInput { up() }
        node("mary-sidebar-preview").assertDoesNotExist()
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress); maryActive.value = true }
        compose.waitForIdle()
        node("mary-sidebar-preview").assertDoesNotExist()
        node("fixture-navigation").performTouchInput { click(Offset(50f, center.y)) }
        compose.runOnIdle { assertEquals(1, navigationClicks) }
    }

    @Test fun productionConsumedUpCancelsNativeDragAfterInitialReleaseObservation() {
        // A controlled descendant consumes UP after the root's Initial-pass observer saw it.
        // Foundation must report DragInteraction.Cancel, never turn that zero velocity into open.
        host(transcriptMode = "consume-up")
        node("sidebar-transcript").performTouchInput {
            down(Offset(80f, 280f))
            moveTo(Offset(320f, 280f), delayMillis = 400)
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress) }
    }

    private fun assertProductionSwipeOpens(startX: Float, verticalDelta: Float = 80f) {
        node("sidebar-transcript").performTouchInput {
            down(Offset(startX, 280f))
            // One real MOVE crosses BOTH slops; x wins by 3:1, independent of frame rate.
            moveTo(Offset(startX + 240f, 280f + verticalDelta), delayMillis = 400)
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
    }

    @Test fun openingPreviewBecomesInteractiveOnlyAfterRelease() {
        host()
        val transcript = node("sidebar-transcript")
        transcript.performTouchInput {
            down(Offset(16f, 300f))
            moveTo(Offset(160f, 300f), delayMillis = 400)
        }
        compose.runOnIdle {
            assertFalse(showing.value)
            assertEquals(144f / 328f, requireNotNull(gestures.progress), 0.03f)
        }
        node("mary-sidebar-modal").assertDoesNotExist()
        node("mary-sidebar-preview").assertExists()
        transcript.performTouchInput {
            moveTo(Offset(300f, 300f), delayMillis = 500)
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(showing.value); assertNull(gestures.progress) }
        node("mary-sidebar-panel").assertIsDisplayed()
    }

    @Test fun productionOpeningTracksTwoMovesAndSettlesQuicklyInSameWindow() {
        host(transcriptMode = "landing")
        val root = node("sidebar-fixture")
        screenshot("opening-00-down-background", "sidebar-fixture")
        val background = root.captureToImage().asAndroidBitmap()
        compose.mainClock.autoAdvance = false
        root.performTouchInput { down(Offset(80f, 280f)); moveTo(Offset(176f, 280f), delayMillis = 160) }
        compose.mainClock.advanceTimeBy(64)
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        node("mary-sidebar-preview").assertExists()
        screenshot("opening-01-move-96px", "sidebar-fixture")
        val first = root.captureToImage().asAndroidBitmap()
        assertEquals(96f, node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().right.value, 2f)
        val firstProgress = requireNotNull(gestures.progress)
        assertEquals(96f / 328f, firstProgress, 0.03f)
        root.performTouchInput { moveTo(Offset(240f, 280f), delayMillis = 160) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        screenshot("opening-02-move-160px", "sidebar-fixture")
        val second = root.captureToImage().asAndroidBitmap()
        assertEquals(160f, node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().right.value, 2f)
        val secondProgress = requireNotNull(gestures.progress)
        assertEquals(160f / 328f, secondProgress, 0.03f)
        assertTrue(secondProgress > firstProgress)
        // Right-side blank background must progressively darken under the production scrim.
        fun luminance(image: Bitmap) = android.graphics.Color.red(image.getPixel(390, 450))
        assertTrue(luminance(first) < luminance(background))
        assertTrue(luminance(second) < luminance(first))
        node("mary-sidebar-modal").assertDoesNotExist()
        root.performTouchInput { advanceEventTime(200); up() }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertTrue("UP must not reset the visual position", node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().right.value >= 159f)
        screenshot("opening-03-up-start", "sidebar-fixture")
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(32)
        screenshot("opening-04-up-plus32ms", "sidebar-fixture")
        assertTrue(node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().right.value > 160f)
        compose.mainClock.advanceTimeBy(48)
        screenshot("opening-05-up-plus80ms", "sidebar-fixture")
        compose.mainClock.advanceTimeBy(80)
        screenshot("opening-06-up-plus160ms-activity", "sidebar-fixture")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        node("mary-sidebar-preview").assertDoesNotExist()
        screenshot("opening-06-up-full", "mary-sidebar-modal")
        compose.runOnIdle { assertTrue(showing.value); assertNull(gestures.progress) }
    }

    @Test fun shortOpeningPreviewRollsBackWithoutEverMountingDialog() {
        host(transcriptMode = "conversation")
        compose.mainClock.autoAdvance = false
        val root = node("sidebar-fixture")
        root.performTouchInput {
            down(Offset(80f, 280f)); moveTo(Offset(124f, 280f), delayMillis = 300)
        }
        compose.mainClock.advanceTimeBy(64)
        node("mary-sidebar-preview").assertExists()
        val before = node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().right.value
        root.performTouchInput { advanceEventTime(200); up() }
        compose.mainClock.advanceTimeBy(48)
        node("mary-sidebar-modal").assertDoesNotExist()
        assertTrue(node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().right.value < before)
        compose.mainClock.advanceTimeBy(160)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        node("mary-sidebar-preview").assertDoesNotExist()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress) }
    }

    @Test fun openingSettleBlocksFreshTouchesFromUnderlyingNavigation() {
        host(transcriptMode = "landing")
        compose.mainClock.autoAdvance = false
        val root = node("sidebar-fixture")
        root.performTouchInput {
            down(Offset(80f, 280f)); moveTo(Offset(240f, 280f), delayMillis = 300)
        }
        compose.mainClock.advanceTimeBy(64)
        root.performTouchInput { advanceEventTime(200); up() }
        compose.mainClock.advanceTimeBy(64)
        node("mary-sidebar-preview").assertExists()
        // A NEW hit path while the original pointer has ended must hit the noninteractive preview.
        root.performTouchInput { click(Offset(350f, 760f)) }
        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, navigationClicks); assertEquals(0, sentMessages)
            assertNull(selected); assertTrue(maryActive.value)
        }
    }

    @Test fun backDuringOpeningCompletionReversesFromCurrentPosition() {
        host(transcriptMode = "landing")
        compose.mainClock.autoAdvance = false
        val root = node("sidebar-fixture")
        root.performTouchInput { down(Offset(80f, 280f)); moveTo(Offset(240f, 280f), delayMillis = 300) }
        compose.mainClock.advanceTimeBy(64)
        root.performTouchInput { advanceEventTime(200); up() }
        compose.mainClock.advanceTimeBy(80)
        val beforeBack = node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().right.value
        assertTrue(beforeBack > 160f)
        compose.runOnUiThread { requireNotNull(activity).get().onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        val afterBack = node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().right.value
        assertTrue("Back must reverse from current animation, not snap to old drag fraction", afterBack >= beforeBack - 20f)
        compose.mainClock.advanceTimeBy(200)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        node("mary-sidebar-preview").assertDoesNotExist()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress) }
    }

    @Test fun completionBoundaryBackCancellationDoesNotLeaveOverlayOrReopen() {
        host(transcriptMode = "landing")
        compose.mainClock.autoAdvance = false
        val root = node("sidebar-fixture")
        root.performTouchInput { down(Offset(80f, 280f)); moveTo(Offset(240f, 280f), delayMillis = 300) }
        compose.mainClock.advanceTimeBy(64)
        root.performTouchInput { advanceEventTime(200); up() }
        var found = false
        for (frame in 0 until 16) {
            compose.mainClock.advanceTimeByFrame()
            if (compose.onAllNodesWithTag("mary-sidebar-modal", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
                found = true; break
            }
        }
        assertTrue("Sidebar must become interactive after bounded completion", found)
        compose.runOnUiThread { requireNotNull(activity).get().onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        node("mary-sidebar-preview").assertDoesNotExist()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress) }
    }

    @Test fun openingWithInjectedImeInsetsKeepsDraftAndPanelGeometry() {
        host(transcriptMode = "landing")
        compose.runOnIdle { draft.value = "尚未送出的鍵盤草稿" }
        node("mary-composer-input").performClick()
        compose.runOnUiThread {
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, 280))
                .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), true).build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(requireNotNull(activity).get().window.decorView, insets)
        }
        val root = node("sidebar-fixture")
        root.performTouchInput { down(Offset(80f, 280f)); moveTo(Offset(240f, 280f), delayMillis = 300) }
        node("mary-sidebar-preview").assertExists()
        node("mary-composer-input").assertIsFocused()
        assertEquals(328f, node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().width.value, 1f)
        assertEquals(800f, node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().bottom.value, 1f)
        screenshot("opening-ime-insets-held", "sidebar-fixture")
        root.performTouchInput { advanceEventTime(200); up() }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        compose.runOnIdle { assertEquals("尚未送出的鍵盤草稿", draft.value); assertEquals(0, sentMessages) }
    }

    @Test fun leavingMaryDuringAcceptedOpeningSettleCancelsPendingDialog() {
        host(transcriptMode = "landing")
        compose.mainClock.autoAdvance = false
        val root = node("sidebar-fixture")
        root.performTouchInput { down(Offset(80f, 280f)); moveTo(Offset(240f, 280f), delayMillis = 300) }
        compose.mainClock.advanceTimeBy(64)
        root.performTouchInput { advanceEventTime(200); up() }
        compose.mainClock.advanceTimeBy(48)
        node("mary-sidebar-preview").assertExists()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnUiThread { maryActive.value = false }
        compose.mainClock.advanceTimeBy(320)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        node("mary-sidebar-preview").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress); maryActive.value = true }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        node("mary-sidebar-preview").assertDoesNotExist()
        assertProductionSwipeOpens(startX = 80f, verticalDelta = 0f)
    }

    @Test fun leavingMaryWithFullyOpenDrawerClosesAndDoesNotAutoReopen() {
        host(transcriptMode = "landing")
        open()
        compose.runOnIdle { maryActive.value = false }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        node("mary-sidebar-preview").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress); maryActive.value = true }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        node("mary-sidebar-preview").assertDoesNotExist()
        assertProductionSwipeOpens(startX = 80f, verticalDelta = 0f)
    }

    @Test fun smallSlowOpeningDragRollsBack() {
        host()
        node("sidebar-transcript").performTouchInput {
            down(Offset(16f, 300f))
            moveTo(Offset(60f, 300f), delayMillis = 400)
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress) }
    }

    @Test fun fastOpeningFlingSettlesOpenBelowDistanceThreshold() {
        host()
        node("sidebar-transcript").performTouchInput {
            down(Offset(16f, 300f))
            moveTo(Offset(45f, 300f), delayMillis = 16)
            moveTo(Offset(90f, 300f), delayMillis = 16)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(showing.value) }
        node("mary-sidebar-panel").assertIsDisplayed()
    }

    @Test fun verticalAndWrongDirectionGesturesDoNotOpen() {
        host()
        node("sidebar-transcript").performTouchInput {
            swipe(Offset(16f, 180f), Offset(16f, 420f), durationMillis = 600)
            swipe(Offset(26f, 300f), Offset(0f, 300f), durationMillis = 400)
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress) }
    }

    @Test fun frameworkDeliveredSwipeHasNoAppDeadZoneAtSystemInset() {
        host()
        compose.runOnUiThread {
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemGestures(), androidx.core.graphics.Insets.of(24, 0, 0, 0))
                .build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(requireNotNull(activity).get().window.decorView, insets)
        }
        compose.waitForIdle()
        node("sidebar-transcript").performTouchInput {
            swipe(Offset(16f, 300f), Offset(300f, 300f), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        node("mary-sidebar-close").performClick()
        compose.waitForIdle()
        node("sidebar-transcript").performTouchInput {
            swipe(Offset(40f, 300f), Offset(300f, 300f), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
    }

    @Test fun openingPointerCancellationRestoresClosedState() {
        host()
        node("sidebar-transcript").performTouchInput {
            down(Offset(16f, 300f))
            moveTo(Offset(160f, 300f), delayMillis = 400)
        }
        node("mary-sidebar-preview").assertExists()
        node("sidebar-transcript").performTouchInput { cancel() }
        compose.waitForIdle()
        node("mary-sidebar-preview").assertDoesNotExist()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress) }
    }

    @Test fun leftDragClosesWithoutClickingHistoryOrUnderlyingNavigation() {
        host()
        open()
        node("mary-sidebar-modal").performTouchInput {
            swipe(Offset(280f, 400f), Offset(20f, 400f), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertNull(selected); assertEquals(0, navigationClicks) }
    }

    @Test fun closingTracksFingerBeforeReleaseAndCancellationRestoresOpen() {
        host()
        open()
        val modal = node("mary-sidebar-modal")
        modal.performTouchInput {
            down(Offset(280f, 400f))
            moveTo(Offset(120f, 400f), delayMillis = 400)
        }
        compose.runOnIdle {
            assertTrue(showing.value)
            assertEquals(1f - 160f / 328f, requireNotNull(gestures.progress), 0.03f)
        }
        screenshot("half-closed-finger-tracking", "mary-sidebar-modal")
        modal.performTouchInput { cancel() }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        compose.runOnIdle { assertTrue(showing.value); assertNull(gestures.progress) }
    }

    @Test fun closingPointerCancellationAndSmallDragRestoreOpenState() {
        host()
        open()
        node("mary-sidebar-modal").performTouchInput {
            down(Offset(280f, 400f))
            moveTo(Offset(180f, 400f), delayMillis = 400)
            cancel()
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        node("mary-sidebar-modal").performTouchInput {
            swipe(Offset(280f, 400f), Offset(240f, 400f), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        compose.runOnIdle { assertTrue(showing.value); assertNull(gestures.progress) }
    }

    @Test fun secondPointerCancelsOpeningAndClosingWithoutClickThrough() {
        host()
        node("sidebar-transcript").performTouchInput {
            down(pointerId = 0, position = Offset(16f, 300f))
            moveTo(pointerId = 0, position = Offset(150f, 300f), delayMillis = 400)
        }
        node("mary-sidebar-preview").assertExists()
        node("sidebar-transcript").performTouchInput {
            down(pointerId = 1, position = Offset(280f, 400f))
            up(pointerId = 1)
            up(pointerId = 0)
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertFalse(showing.value); assertNull(gestures.progress) }
        open()
        node("mary-sidebar-modal").performTouchInput {
            down(pointerId = 0, position = Offset(280f, 400f))
            moveTo(pointerId = 0, position = Offset(180f, 400f), delayMillis = 400)
            down(pointerId = 1, position = Offset(280f, 700f))
            up(pointerId = 1)
            up(pointerId = 0)
        }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(showing.value)
            assertNull(gestures.progress)
            assertNull(selected)
            assertEquals(0, navigationClicks)
            assertEquals(0, newChats)
            assertEquals(0, locks)
        }
    }

    @Test fun verticalHistoryScrollingDoesNotStartClosingGesture() {
        host()
        open()
        node("mary-sidebar-history").performTouchInput { swipeUp(durationMillis = 600) }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        compose.runOnIdle { assertTrue(showing.value); assertNull(gestures.progress) }
    }

    @Test @Config(qualifiers = "zh-rCN-ldrtl-w400dp-h800dp-mdpi")
    fun rtlUsesRightEdgeOpeningAndRightwardClosing() {
        host(direction = LayoutDirection.Rtl)
        node("sidebar-transcript").performTouchInput {
            down(Offset(384f, 300f))
            moveTo(Offset(224f, 300f), delayMillis = 300)
        }
        node("mary-sidebar-preview").assertExists()
        assertEquals(240f, node("mary-sidebar-preview-panel").getUnclippedBoundsInRoot().left.value, 2f)
        screenshot("opening-rtl-held", "sidebar-fixture")
        node("sidebar-transcript").performTouchInput { advanceEventTime(200); up() }
        compose.waitForIdle()
        node("mary-sidebar-panel").assertIsDisplayed()
        node("mary-sidebar-modal").performTouchInput {
            swipe(Offset(100f, 400f), Offset(380f, 400f), durationMillis = 600)
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
    }

    @Test fun modalLightClosesReopensAndKeepsFooterWhileHistoryScrolls() {
        host()
        screenshot("closed-light", "sidebar-fixture")
        open()
        node("mary-sidebar-panel").assertIsDisplayed()
        node("mary-sidebar-session-session-0").assertIsSelected()
        val panel = node("mary-sidebar-panel").getUnclippedBoundsInRoot()
        assertEquals(328f, panel.width.value, 1f)
        screenshot("open-light", "mary-sidebar-modal")
        node("mary-sidebar-history").performScrollToNode(hasTestTag("mary-sidebar-session-session-29"))
        node("mary-sidebar-new-chat").assertIsDisplayed()
        screenshot("history-scrolled", "mary-sidebar-modal")
        node("mary-sidebar-close").performClick()
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        open()
        node("mary-sidebar-new-chat").performClick()
        compose.runOnIdle { assertEquals(1, newChats) }
        node("mary-sidebar-modal").assertDoesNotExist()
    }

    @Test fun selectsSessionAndLocksWithoutClickingUnderlyingNavigation() {
        host()
        open()
        node("mary-sidebar-session-session-1").performClick()
        compose.runOnIdle { assertEquals("session-1", selected) }
        open()
        node("mary-sidebar-lock").performClick()
        compose.runOnIdle { assertEquals(1, locks); assertEquals(0, navigationClicks) }
        node("mary-sidebar-modal").assertDoesNotExist()
    }

    @Test fun repeatedNewChatClickDuringExitRunsExactlyOnceAfterThePanelCloses() {
        host()
        open()
        compose.mainClock.autoAdvance = false
        try {
            node("mary-sidebar-new-chat").performClick()
            node("mary-sidebar-new-chat").performClick()
            compose.runOnIdle { assertEquals(0, newChats) }
            compose.mainClock.advanceTimeBy(350)
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(1, newChats) }
            node("mary-sidebar-modal").assertDoesNotExist()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun openingMenuClearsComposerFocusAndDoesNotStealItBackOnClose() {
        host()
        node("mary-composer-input").performClick()
        node("mary-composer-input").assertIsFocused()
        open()
        node("mary-composer-input").assertIsNotFocused()
        node("mary-sidebar-close").performClick()
        compose.waitForIdle()
        node("mary-composer-input").assertIsNotFocused()
        // SystemDefined clickable focusability intentionally excludes touch input mode.
        // Keyboard restoration has its own strict focus assertion below.
    }

    @Test fun keyboardNavigationRestoresFocusToMenuAfterModalClose() {
        host()
        compose.runOnIdle { assertTrue(inputModes.requestInputMode(InputMode.Keyboard)) }
        open()
        node("mary-sidebar-close").performClick()
        compose.waitForIdle()
        node("mary-open-sidebar").assertIsFocused()
        node("mary-composer-input").assertIsNotFocused()
    }

    @Test fun failedHistoryLoadCanRetryWithoutLeavingTheDrawer() {
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        host(loader = { if (attempts.incrementAndGet() == 1) error("Controlled fixture read failure") else sessions })
        compose.runOnIdle { showing.value = true }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("mary-sidebar-retry", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        node("mary-sidebar-retry").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("mary-sidebar-session-session-0", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        node("mary-sidebar-retry").assertDoesNotExist()
    }

    @Test fun deletingHistoryReloadsOnlyAfterCompletionRevision() {
        val stored = java.util.concurrent.atomic.AtomicReference(sessions)
        host(loader = { stored.get() }, delete = { id ->
            stored.set(stored.get().filterNot { it.id == id })
            revision.value++
        })
        open()
        node("mary-sidebar-delete-session-1").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("mary-sidebar-session-session-1", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        node("mary-sidebar-session-session-0").assertIsDisplayed()
    }

    @Test fun androidBackClosesTheModalWithoutTriggeringNavigation() {
        host()
        open()
        compose.runOnUiThread {
            requireNotNull(activity).get().onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, navigationClicks); assertFalse(requireNotNull(activity).get().isFinishing) }
    }

    @Test fun escapeClosesTheModalAndAllowsReopening() {
        host()
        open()
        compose.runOnUiThread {
            val decor = requireNotNull(activity).get().window.decorView
            decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ESCAPE))
            decor.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ESCAPE))
        }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        open()
        node("mary-sidebar-panel").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "zh-rCN-w320dp-h568dp-mdpi")
    fun narrowDarkDrawerPreservesNewChatAndClosesByScrim() {
        host(ColorSchemeMode.Dark)
        open()
        node("mary-sidebar-new-chat").assertIsDisplayed()
        screenshot("open-narrow-dark", "mary-sidebar-modal")
        node("mary-sidebar-scrim").performTouchInput { click(bottomRight - androidx.compose.ui.geometry.Offset(8f, 24f)) }
        compose.waitForIdle()
        node("mary-sidebar-modal").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, navigationClicks) }
    }

    @Test fun alreadyLockedSessionCannotBeLockedAgain() {
        host(locked = true)
        open()
        node("mary-sidebar-lock").assertIsNotEnabled()
    }

    @Test fun noSessionDisablesLockButKeepsNewChatAvailable() {
        host(hasSession = false)
        open()
        node("mary-sidebar-lock").assertIsNotEnabled()
        node("mary-sidebar-new-chat").assertIsEnabled()
    }

    @Test @Config(qualifiers = "zh-rCN-w800dp-h360dp-mdpi")
    fun shortLandscapeRetainsFooterAndScrollableHistory() {
        host()
        open()
        node("mary-sidebar-new-chat").assertIsDisplayed()
        node("mary-sidebar-history").performScrollToNode(hasTestTag("mary-sidebar-session-session-29"))
        node("mary-sidebar-new-chat").assertIsDisplayed()
        screenshot("open-short-landscape", "mary-sidebar-modal")
    }

    @Test @Config(qualifiers = "zh-rCN-w840dp-h800dp-mdpi")
    fun wideDrawerDoesNotExpandBeyondComfortableReadingWidth() {
        host()
        open()
        assertEquals(360f, node("mary-sidebar-panel").getUnclippedBoundsInRoot().width.value, 1f)
        screenshot("open-wide", "mary-sidebar-modal")
    }

    private fun open() {
        node("mary-open-sidebar").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("mary-sidebar-session-session-0", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun host(mode: ColorSchemeMode = ColorSchemeMode.Light, locked: Boolean = false, hasSession: Boolean = true, loader: () -> List<AgentSessionSummary> = { sessions }, delete: (String) -> Unit = {}, direction: LayoutDirection = LayoutDirection.Ltr, transcriptMode: String = "fixture") {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().also { controller ->
                controller.get().enableEdgeToEdge()
                controller.get().setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    MiuixTheme(ThemeController(mode)) {
                        val menuFocus = remember { FocusRequester() }
                        val focusManager = LocalFocusManager.current
                        val keyboard = LocalSoftwareKeyboardController.current
                        val inputModeManager = LocalInputModeManager.current
                        SideEffect { inputModes = inputModeManager }
                        val pageHost = remember { MarySidebarPageGestureHost() }
                        if (maryActive.value) RegisterMarySidebarPageGestures(
                            pageHost, gestures, enabled = !showing.value,
                            beforeOpen = {
                                focusManager.clearFocus(force = true)
                                keyboard?.hide()
                                menuFocus.requestFocus()
                            }, onOpen = { showing.value = true })
                        CompositionLocalProvider(LocalMarySidebarPageGestureHost provides pageHost) {
                        Box(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)
                            .marySidebarPageGesture(pageHost, enabled = maryActive.value).testTag("sidebar-fixture")) {
                            Box(Modifier.fillMaxSize().marySidebarBackground(pageHost).testTag("sidebar-underlay")) {
                            MaryFloatingChatLayout(bottomInset = 80.dp, modifier = Modifier.fillMaxSize(),
                                content = { padding ->
                                    val edge = Modifier.testTag("sidebar-transcript")
                                        .then(if (transcriptMode == "fixture") Modifier else Modifier.pointerInput(transcriptMode) {
                                            awaitPointerEventScope {
                                                while (true) {
                                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                                    if (transcriptMode == "consume-up") event.changes
                                                        .filter { it.previousPressed && !it.pressed }.forEach { it.consume() }
                                                    println("MARY_PRODUCTION_POINTER $transcriptMode ${event.type} " + event.changes.joinToString { "pressed=${it.pressed}, consumed=${it.isConsumed}, position=${it.position}" })
                                                }
                                            }
                                        })
                                    when (transcriptMode) {
                                        "landing" -> MaryLandingTranscript(
                                            contentPaddingBottom = padding, appBarInset = 56.dp,
                                            gestureModifier = edge, onPick = { selected = it })
                                        "conversation", "horizontal", "consume-up" -> MaryConversationList(
                                            state = rememberLazyListState(),
                                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 64.dp, bottom = padding),
                                            gestureModifier = edge,
                                        ) {
                                            items(40) { index ->
                                                if (transcriptMode == "horizontal" && index == 0) {
                                                    val scroll = rememberScrollState(initial = 120)
                                                    SideEffect { horizontalChild = scroll }
                                                    Row(Modifier.fillMaxWidth().height(72.dp)
                                                        .horizontalScroll(scroll).testTag("production-horizontal-child")) {
                                                        Text("可水平捲動的程式碼內容", modifier = Modifier.width(1200.dp))
                                                    }
                                                } else Text("對話內容 $index", modifier = Modifier.fillMaxWidth().height(72.dp).testTag("production-row-$index"))
                                            }
                                        }
                                        else -> Column(Modifier.fillMaxSize().then(edge)
                                            .padding(horizontal = 24.dp).padding(top = 80.dp, bottom = padding)) {
                                            Text("今天想從哪裡開始？")
                                            Spacer(Modifier.height(20.dp))
                                            Text("我們可以把想法慢慢整理成清楚的計畫。")
                                        }
                                    }
                                }, footer = {
                                    MaryComposer(value = draft.value, onValueChange = { draft.value = it }, running = false,
                                        locked = false, configuring = false, provider = ProviderConfig(model = "preview"),
                                        onReasoningSelected = {}, onSend = { sentMessages++ }, onStop = {})
                                })
                            MaryTopBar(MaryUsageSummary(1200, 32000, null), onOpenMenu = { showing.value = true }, menuFocusRequester = menuFocus)
                            }
                            Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).marySidebarBackground(pageHost).testTag("sidebar-nav-underlay")) {
                            RearNavigationBar(modifier = Modifier.align(Alignment.BottomCenter).testTag("fixture-navigation"),
                                currentScreen = "mary", navigationBarMode = ModuleNavigationBarMode.NORMAL,
                                backdrop = rememberLayerBackdrop(), onScreenSelected = { navigationClicks++; maryActive.value = it == "mary" })
                            }
                            MaryConversationSidebar(show = showing.value, revision = revision.value, gestureState = gestures,
                                currentSessionId = "session-0", hasSession = hasSession, locked = locked,
                                load = loader, onDismiss = { showing.value = false },
                                onNewChat = { newChats++; showing.value = false },
                                onLock = { locks++; showing.value = false },
                                onOpenSettings = { showing.value = false },
                                onSelect = { selected = it; showing.value = false }, onDelete = delete,
                                onClosed = {
                                    val restored = menuFocus.requestFocus()
                                    println("MARY_FOCUS restored=$restored windowFocus=${controller.get().window.decorView.hasWindowFocus()} inputMode=${inputModeManager.inputMode}")
                                })
                            MarySidebarPagePreview(pageHost, enabled = maryActive.value)
                        }
                        }
                    }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun unhandledKeyListenerCount(): Int {
        val infoField = android.view.View::class.java.getDeclaredField("mListenerInfo").apply { isAccessible = true }
        val info = infoField.get(requireNotNull(activity).get().window.decorView.rootView) ?: return 0
        val listeners = info.javaClass.getDeclaredField("mUnhandledKeyListeners").apply { isAccessible = true }
        return (listeners.get(info) as? Collection<*>)?.size ?: 0
    }

    /** Robolectric direct Activity injection does not include ViewRootImpl's final unhandled
     * phase. Run the actual framework View traversal reflectively only when normal dispatch
     * returns false; never invoke the app listener directly or steal focus to make a test pass. */
    private fun dispatchActivityAndUnhandledKey(event: android.view.KeyEvent): Boolean {
        val current = requireNotNull(activity).get()
        if (current.dispatchKeyEvent(event)) return true
        val method = android.view.View::class.java.getDeclaredMethod("dispatchUnhandledKeyEvent", android.view.KeyEvent::class.java)
        method.isAccessible = true
        return method.invoke(current.window.decorView.rootView, event) != null
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun screenshot(name: String, tag: String) {
        val dir = File(requireNotNull(System.getProperty("mary.ui.screenshotDir")), "sidebar")
        check(dir.mkdirs() || dir.isDirectory)
        val image = node("sidebar-fixture").captureToImage().asAndroidBitmap()
        File(dir, "$name.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        File(dir, "README.txt").writeText(
            "Native single-Activity Robolectric/Compose render of production Mary sidebar, header, composer and navigation. " +
                "Controlled local sessions, not the live controller; not an OS/device screen recording. " +
                "The entire frame is captured from the real Activity root; no separate Window composite, image generation, scaling or reconstruction."
        )
    }
}
