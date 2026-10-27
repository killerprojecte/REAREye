package hk.uwu.reareye.ui.components

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Magnifier
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ChatEntry
import hk.uwu.reareye.agent.PendingApproval
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningPolicy
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.interaction.AskOption
import hk.uwu.reareye.agent.interaction.AskRequest
import hk.uwu.reareye.agent.loop.ToolCallRequest
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.provider.ProviderRequestDiagnostics
import hk.uwu.reareye.agent.voice.MaryVoicePhase
import hk.uwu.reareye.ui.screen.BlockExpansion
import hk.uwu.reareye.ui.screen.MaryFloatingChatLayout
import hk.uwu.reareye.ui.screen.MaryTopBar
import hk.uwu.reareye.ui.screen.MarySettingsScreen
import hk.uwu.reareye.ui.screen.NoticeCard
import hk.uwu.reareye.ui.screen.ReasoningCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.io.File

/** Real page layout and controls, measured and drawn by Compose on the JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryChatLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var focusManager: FocusManager
    private var primary = Color.Unspecified
    private var surfaceContainer = Color.Unspecified
    private var outline = Color.Unspecified
    private var sent = 0
    private var transcriptBottomPadding = 0.dp
    private lateinit var transcriptScroll: ScrollState

    @Before
    fun requireExplicitResourceMode() {
        assumeTrue(
            "Run with -PmaryUiResources=true",
            java.lang.Boolean.getBoolean("mary.ui.resources")
        )
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { activity?.close() }
    }

    @Test
    fun emptyUnfocusedComposerIsCompactWithAttachLeftAndDictationRight() {
        composer()
        node("mary-composer-tools").assertDoesNotExist()
        // Picker owners stay mounted for focus stability, but must leave the accessibility tree.
        compose.onNodeWithTag("mary-reasoning-strength").assertDoesNotExist()
        compose.onNodeWithTag("mary-approval-mode").assertDoesNotExist()
        // Nothing typed: the primary action is dictation, available immediately.
        node("mary-composer-send").assertIsEnabled()
        node("mary-composer-send-icon").assertDoesNotExist()
        val attach = bounds("mary-composer-attach")
        val input = bounds("mary-composer-input")
        val send = bounds("mary-composer-send")
        assertTrue(attach.right <= input.left)
        assertTrue(input.right <= send.left)
        assertEquals(attach.center.y.value, send.center.y.value, 0.5f)
        assertActionIconCentered("mary-composer-voice-icon", 20.dp)
        assertAboveNavigation()
        node("mary-composer-send").performClick()
        compose.runOnIdle { assertEquals(0, sent) }
        screenshot("compact.png")
    }

    @Test
    fun listeningPaintsTheAnimatedWaveformNotTheStopSquare() {
        // A live infinite animation is on screen, so drive the clock by hand instead of letting
        // waitForIdle chase it.
        compose.mainClock.autoAdvance = false
        try {
            composer(voicePhase = MaryVoicePhase.RECORDING)
            node("mary-composer-send-icon").assertDoesNotExist()
            node("mary-composer-voice-icon").assertIsDisplayed()
            assertActionIconCentered("mary-composer-voice-icon", 20.dp)
            // The bars themselves move between frames, so the session glyph is visibly live.
            val before = node("mary-composer-voice-icon").captureToImage().toPixelMap()
            compose.mainClock.advanceTimeBy(300)
            val after = node("mary-composer-voice-icon").captureToImage().toPixelMap()
            assertTrue(
                "the dictation waveform must animate",
                (0 until before.width).any { x ->
                    (0 until before.height).any { y -> before[x, y] != after[x, y] }
                }
            )
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun finalizingSwapsTheBobbingWaveformForAnEdgeSpinner() {
        compose.mainClock.autoAdvance = false
        try {
            composer(initial = "你好，世界", voicePhase = MaryVoicePhase.FINALIZING)
            // The waveform is gone while the punctuated final transcript is still in flight, so
            // "recording" and "finishing" never look the same.
            node("mary-composer-voice-icon").assertDoesNotExist()
            node("mary-composer-send-icon").assertDoesNotExist()
            node("mary-composer-voice-spinner").assertIsDisplayed()
            // A second press would have nothing to do, so the action is inert.
            node("mary-composer-send").assertIsNotEnabled()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun focusExpandsToolsWithReasoningImmediatelyBeforeVerticallyCenteredSend() {
        composer()
        val compactHeight = bounds("mary-composer").height
        node("mary-composer-input").performClick()
        node("mary-composer-tools").assertIsDisplayed()
        assertTrue(bounds("mary-composer").height > compactHeight)
        assertExpandedTools()
        node("mary-approval-mode").assertIsDisplayed().assertIsEnabled()
        assertSquare("mary-approval-mode", 44.dp)
        assertTrue(bounds("mary-composer-attach").right <= bounds("mary-approval-mode").left)
        assertTrue(bounds("mary-approval-mode").right <= bounds("mary-reasoning-strength").left)
        assertSquare("mary-reasoning-dial", 26.dp)
        assertActionIconCentered("mary-composer-voice-icon", 20.dp)
        assertAboveNavigation()
        screenshot("expanded.png")
    }

    @Test
    fun gpt6LunaShowsEnabledReasoningPickerAndCanSelectALevel() {
        assertReasoningPickerFor("gpt-6-luna")
    }

    @Test
    fun deepseekV41FlashShowsEnabledReasoningPickerAndCanSelectALevel() {
        assertReasoningPickerFor("deepseek-v4.1-flash")
    }

    private fun assertReasoningPickerFor(model: String) {
        val provider = mutableStateOf(
            ProviderConfig(
                model = model, protocol = Protocol.RESPONSES,
                reasoningEffort = ReasoningEffort.MAX
            )
        )
        val enabled = mutableStateOf(true)
        val selections = mutableListOf<ReasoningEffort>()
        host {
            Scaffold {
                Box(Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp)) {
                    ReasoningStrengthButton(
                        provider = provider.value,
                        enabled = enabled.value,
                        onExpandedChange = {},
                        onSelected = {
                            selections += it
                            provider.value = provider.value.copy(reasoningEffort = it)
                        },
                    )
                }
            }
        }
        node("mary-reasoning-strength").assertIsDisplayed().assertIsEnabled().performClick()
        val slider = node("mary-reasoning-slider")
        slider.assertIsDisplayed()
        fieldLabel(R.string.mary_chat_reasoning_max).assertIsDisplayed()
        // Every lower node must remain visible inside the primary-colored filled segment.
        val pixels = slider.captureToImage().toPixelMap()
        val levels = ReasoningPolicy.support(provider.value).levels
        for (index in 0 until levels.lastIndex) {
            val x = (20f + (pixels.width - 40f) * index / levels.lastIndex).toInt()
            assertColorNear(Color.White, pixels[x, pixels.height / 2])
        }
        screenshot("reasoning-nodes-$model.png", "mary-reasoning-slider")
        screenshot("reasoning-panel-$model.png", "root")

        slider.performTouchInput { click(Offset(20f, height / 2f)) }
        compose.runOnIdle { assertEquals(listOf(ReasoningEffort.NONE), selections) }
        slider.assertIsDisplayed()
        compose.runOnIdle { enabled.value = false }
        slider.assertIsDisplayed() // A temporary save/disabled state must not close the sheet.
        compose.runOnIdle { enabled.value = true }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { setProgress ->
            assertTrue(setProgress(levels.indexOf(ReasoningEffort.HIGH).toFloat()))
        }
        compose.runOnIdle {
            assertEquals(
                listOf(ReasoningEffort.NONE, ReasoningEffort.HIGH),
                selections
            )
        }
        slider.assertIsDisplayed()
        node("mary-reasoning-close").assertDoesNotExist()
        compose.onRoot().performTouchInput { click(Offset(8f, 100f)) }
        slider.assertDoesNotExist()
    }

    @Test
    fun reasoningPickerReturnKeepsEmptyEditingExpandedUntilFocusActuallyLeaves() {
        lateinit var outside: FocusRequester
        host {
            Scaffold {
                outside = remember { FocusRequester() }
                Box(
                    Modifier
                        .requiredSize(360.dp, 640.dp)
                        .background(pageBackground)
                        .testTag("layout-container")
                ) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .focusRequester(outside)
                            .focusable()
                            .testTag("outside-focus")
                    )
                    Box(Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)) {
                        MaryComposer(
                            "",
                            {},
                            false,
                            false,
                            false,
                            ProviderConfig(model = "gpt-6-luna"),
                            {},
                            {},
                            {})
                    }
                }
            }
        }
        node("mary-composer-input").performClick()
        node("mary-composer-tools").assertIsDisplayed()
        setKeyboardInset(240)
        node("mary-reasoning-strength").performClick()
        node("mary-reasoning-slider").assertIsDisplayed()
        node("mary-reasoning-close").assertDoesNotExist()
        val layouts = mutableListOf<TextLayoutResult>()
        node("mary-reasoning-title").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(
                layouts
            )
        }
        assertEquals(21f, layouts.single().layoutInput.style.fontSize.value, 0.1f)
        setKeyboardInset(0)
        compose.runOnUiThread { requireNotNull(activity).get().onBackPressedDispatcher.onBackPressed() }
        node("mary-reasoning-slider").assertDoesNotExist()
        node("mary-composer-input").assertIsFocused()
        node("mary-composer-tools").assertIsDisplayed()
        screenshot("reasoning-return-keeps-editing.png")
        compose.runOnIdle { outside.requestFocus() }
        node("mary-composer-tools").assertDoesNotExist()
    }

    @Test
    fun tappingReasoningLevelPaintsIntermediateThumbPositionBeforeSettling() {
        val provider = mutableStateOf(
            ProviderConfig(
                model = "gpt-6-luna",
                reasoningEffort = ReasoningEffort.NONE
            )
        )
        var saved = 0
        host {
            Scaffold {
                ReasoningStrengthButton(provider.value, true, {}, {
                    saved++
                    provider.value = provider.value.copy(reasoningEffort = it)
                })
            }
        }
        node("mary-reasoning-strength").performClick()
        val slider = node("mary-reasoning-slider")
        fun thumbX(): Float {
            val pixels = slider.captureToImage().toPixelMap()
            var start = 0
            var bestStart = 0
            var bestLength = 0
            for (x in 0..pixels.width) {
                if (x == pixels.width || colorDistance(
                        Color.White,
                        pixels[x, pixels.height / 2]
                    ) > 0.005f
                ) {
                    val length = x - start
                    if (length > bestLength) {
                        bestStart = start; bestLength = length
                    }
                    start = x + 1
                }
            }
            assertTrue("A filled thumb, not a small level dot, must be visible", bestLength >= 20)
            return bestStart + bestLength / 2f
        }

        val initial = thumbX()
        compose.mainClock.autoAdvance = false
        slider.performTouchInput { click(Offset(width - 20f, height / 2f)) }
        val frames = (1..8).map {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            thumbX()
        }
        compose.mainClock.advanceTimeBy(240)
        compose.waitForIdle()
        val end = thumbX()
        assertTrue(
            "Tap must paint intermediate positions: $initial -> $frames -> $end",
            frames.any { it > initial + 2 && it < end - 2 })
        compose.runOnIdle { assertEquals(1, saved) }
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun questionIsAnInlineToolThatCollapsesAfterAnswerAndReopensReadOnly() {
        val request = AskRequest(
            "drink",
            "Choose a drink",
            listOf(AskOption("coffee", "Coffee"), AskOption("tea", "Tea")),
            toolCallId = "call-drink"
        )
        val pending = mutableStateOf<AskRequest?>(request)
        val entry = mutableStateOf(
            ChatEntry.ToolCall(
                "row-drink",
                "Ask",
                """{"question":"Choose a drink","option_ids":["coffee","tea"],"option_labels":["Coffee","Tea"]}""",
                running = true,
                callId = "call-drink"
            )
        )
        var answers = 0
        host {
            val expansion = remember { ToolCardExpansion() }
            Column(
                Modifier
                    .requiredSize(360.dp, 640.dp)
                    .background(pageBackground)
                    .padding(16.dp)
                    .testTag("layout-container")
            ) {
                BasicText("Earlier chat message")
                MaryToolCallCard(
                    entry.value,
                    expansion,
                    pendingAsk = pending.value,
                    onAnswer = { id, selected, custom ->
                        assertEquals(request.id, id)
                        assertEquals(listOf("coffee"), selected)
                        assertEquals(null, custom)
                        answers++
                        pending.value = null
                        entry.value = entry.value.copy(
                            running = false,
                            result = ToolResult("""{"selected_ids":["coffee"],"custom_text":null}""")
                        )
                        true
                    },
                    onApprove = { _, _ -> false })
            }
        }
        compose.onNodeWithText("Choose a drink").assertIsDisplayed()
        node("mary-tool-submit-drink").assertIsNotEnabled()
        compose.onNodeWithText("Coffee").performClick()
        screenshot("inline-question-awaiting.png")
        node("mary-tool-submit-drink").performClick()
        compose.runOnIdle { assertEquals(1, answers) }
        compose.onNodeWithText("Choose a drink").assertDoesNotExist()
        node("mary-tool-header-row-drink").performClick()
        compose.onNodeWithText("Choose a drink").assertIsDisplayed()
        node("mary-tool-submit-drink").assertDoesNotExist()
        compose.onAllNodesWithText("Coffee").fetchSemanticsNodes()
            .also { assertTrue(it.isNotEmpty()) }
        screenshot("inline-question-answered.png")
        node("mary-tool-header-row-drink").performClick()
        compose.onNodeWithText("Choose a drink").assertDoesNotExist()
    }

    @Test
    fun selectedQuestionHasSymmetricInsetsAndDoesNotMoveItsLabel() {
        val request = AskRequest(
            "balanced", "Choose a drink", listOf(
                AskOption("coffee", "Coffee"), AskOption("tea", "Tea")
            ), allowCustom = false
        )
        host {
            Column(
                Modifier
                    .requiredSize(360.dp, 640.dp)
                    .background(pageBackground)
                    .padding(16.dp)
                    .testTag("layout-container")
            ) {
                MaryQuestionCard(
                    ChatEntry.Question("balanced-row", request),
                    remember { ToolCardExpansion() }, true, { _, _, _ -> false })
            }
        }
        val before = bounds("mary-question-label-balanced-coffee")
        node("mary-question-option-balanced-coffee").performClick().assertIsSelected()
        val row = bounds("mary-question-option-balanced-coffee")
        val number = bounds("mary-question-number-balanced-coffee")
        val mark = bounds("mary-question-mark-balanced-coffee")
        assertEquals(8f, (number.left - row.left).value, 0.5f)
        assertEquals(8f, (row.right - mark.right).value, 0.5f)
        assertEquals(number.center.y.value, mark.center.y.value, 0.5f)
        assertEquals(before, bounds("mary-question-label-balanced-coffee"))
        assertSquare("mary-confirmation-icon-balanced-row", 15.dp)
        screenshot("question-balanced-insets-and-icon.png")
    }

    @Test
    fun reasoningRendersMarkdownSnapshotsAndCanReopenAfterFinishing() {
        val text =
            mutableStateOf("# Thinking plan\n\n**Check** the result.\n\n- First step\n- Second step\n\n```kotlin\nval answer = 42\n```")
        val streaming = mutableStateOf(true)
        host {
            Column(
                Modifier
                    .requiredSize(360.dp, 640.dp)
                    .background(pageBackground)
                    .padding(16.dp)
                    .testTag("layout-container")
            ) {
                ReasoningCard(
                    text.value,
                    streaming.value,
                    "markdown-test",
                    remember { BlockExpansion() })
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Thinking plan", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Check the result.", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("val answer = 42", useUnmergedTree = true).assertIsDisplayed()
        screenshot("reasoning-markdown-streaming.png")
        compose.runOnIdle {
            text.value = "# Updated plan\n\n**Finished** without duplicated text."
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Updated plan", useUnmergedTree = true).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Thinking plan", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { streaming.value = false }
        node("mary-reasoning-markdown-markdown-test").assertDoesNotExist()
        node("mary-reasoning-header-markdown-test").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Updated plan", useUnmergedTree = true).fetchSemanticsNodes()
                .isNotEmpty()
        }
        screenshot("reasoning-markdown-finished.png")
    }

    @Test
    fun errorsUseLegibleRedForNoticesAndToolResults() {
        host(ColorSchemeMode.Dark) {
            Column(
                Modifier
                    .requiredSize(360.dp, 640.dp)
                    .background(Color(0xff171717))
                    .padding(16.dp)
                    .testTag("layout-container")
            ) {
                NoticeCard("Request failed: connection interrupted", isError = true)
                val expansion = remember { ToolCardExpansion().apply { toggle("error-tool") } }
                MaryToolCallCard(
                    ChatEntry.ToolCall(
                    "error-tool", "Read", "{}", running = false,
                    result = ToolResult("Unable to read the file", isError = true)
                ), expansion,
                    onAnswer = { _, _, _ -> false }, onApprove = { _, _ -> false })
            }
        }
        compose.onNodeWithText("Request failed: connection interrupted").assertIsDisplayed()
        compose.onNodeWithText("Unable to read the file").assertIsDisplayed()
        screenshot("error-notice-and-tool-dark.png")
    }

    @Test
    fun toolHeaderAndArgumentsHaveNoPressedOverlayButStillRespond() {
        val approval = PendingApproval(
            ToolCallRequest("press-call", "Write", "{}", ToolRisk.MUTATING),
            CompletableDeferred(), "press-approval"
        )
        host {
            Column(
                Modifier
                    .requiredSize(360.dp, 640.dp)
                    .background(pageBackground)
                    .padding(16.dp)
                    .testTag("layout-container")
            ) {
                MaryToolCallCard(
                    ChatEntry.ToolCall("press-row", "Write", "{}", running = true),
                    remember { ToolCardExpansion() }, pendingApproval = approval,
                    onAnswer = { _, _, _ -> false }, onApprove = { _, _ -> false })
            }
        }
        fun assertNoPressedOverlay(tag: String) {
            val before = node("mary-tool-card-press-row").captureToImage().toPixelMap()
            node(tag).performTouchInput { down(center) }
            val pressed = node("mary-tool-card-press-row").captureToImage().toPixelMap()
            assertEquals(before.width, pressed.width)
            assertEquals(before.height, pressed.height)
            for (y in 0 until before.height) for (x in 0 until before.width) {
                assertTrue(
                    "Pressing $tag must not paint a shadow or overlay",
                    colorDistance(before[x, y], pressed[x, y]) < 0.001f
                )
            }
            node(tag).performTouchInput { up() }
        }
        assertNoPressedOverlay("mary-tool-arguments-press-row")
        compose.onNodeWithText("{}").assertIsDisplayed()
        assertNoPressedOverlay("mary-tool-header-press-row")
        node("mary-tool-arguments-press-row").assertDoesNotExist()
    }

    @Test
    fun failedQuestionSubmissionKeepsItsSelectionAndManualCollapseKeepsTheDraft() {
        val request = AskRequest(
            "failed-question",
            "Choose one",
            listOf(AskOption("a", "First answer"), AskOption("b", "Second answer")),
            allowCustom = false
        )
        host {
            val expansion = remember { ToolCardExpansion() }
            Column(
                Modifier
                    .requiredSize(360.dp, 640.dp)
                    .background(pageBackground)
                    .padding(16.dp)
                    .testTag("layout-container")
            ) {
                MaryQuestionCard(
                    ChatEntry.Question("question-row", request),
                    expansion,
                    true,
                    { _, _, _ -> false })
            }
        }
        compose.onNodeWithText("First answer").performClick()
        node("mary-tool-submit-failed-question").performClick()
        fieldLabel(R.string.mary_tool_submit_failed).assertIsDisplayed()
        node("mary-tool-submit-failed-question").assertIsEnabled()
        node("mary-tool-header-question-row").performClick()
        node("mary-tool-submit-failed-question").assertDoesNotExist()
        node("mary-tool-header-question-row").performClick()
        node("mary-tool-submit-failed-question").assertIsEnabled()
        fieldLabel(R.string.mary_tool_submit_failed).assertIsDisplayed()
    }

    @Test
    fun ordinaryToolAndApprovalShareCollapsingShellWithoutAnsweringFromHeader() {
        val approval = PendingApproval(
            ToolCallRequest("write-call", "Write", "{}", ToolRisk.DESTRUCTIVE),
            CompletableDeferred(),
            "approve-write"
        )
        val pending = mutableStateOf<PendingApproval?>(approval)
        var approved = 0
        host {
            val expansion = remember { ToolCardExpansion() }
            Column(
                Modifier
                    .requiredSize(360.dp, 640.dp)
                    .background(pageBackground)
                    .padding(16.dp)
                    .testTag("layout-container")
            ) {
                MaryToolCallCard(
                    ChatEntry.ToolCall(
                    "read-row",
                    "Read",
                    "read arguments",
                    ToolResult("Read complete")
                ), expansion,
                    onAnswer = { _, _, _ -> false }, onApprove = { _, _ -> false })
                MaryToolCallCard(
                    ChatEntry.ToolCall(
                    "write-row",
                    "Write",
                    "{}",
                    running = true,
                    callId = "write-call"
                ),
                    expansion,
                    pendingApproval = pending.value,
                    onAnswer = { _, _, _ -> false },
                    onApprove = { id, allow ->
                        assertEquals("approve-write", id)
                        assertTrue(allow)
                        approved++
                        pending.value = null
                        true
                    })
            }
        }
        compose.onNodeWithText("Read complete").assertDoesNotExist()
        node("mary-tool-header-read-row").performClick()
        compose.onNodeWithText("Read complete").assertIsDisplayed()
        fieldLabel(R.string.mary_confirmation_allow_once).assertIsDisplayed()
        node("mary-tool-header-write-row").performClick()
        fieldLabel(R.string.mary_confirmation_allow_once).assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, approved) }
        node("mary-tool-header-write-row").performClick()
        fieldLabel(R.string.mary_confirmation_allow_once).performClick()
        fieldLabel(R.string.mary_confirmation_allow_once).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, approved) }
    }

    @Test
    fun jumpButtonIsCenteredOverComposerShowsOnlyWhenIdleAndFadesOutAfterTimeout() {
        fun advanceUi(millis: Long) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(millis)
            compose.waitForIdle()
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }

        val scrolling = mutableStateOf(true)
        val away = mutableStateOf(true)
        val session = mutableStateOf("one")
        val enabledClicks = mutableStateOf(0)
        host {
            Box(
                Modifier
                    .requiredSize(360.dp, 640.dp)
                    .background(pageBackground)
                    .testTag("layout-container")
            ) {
                Box(Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .fillMaxWidth()) {
                    MaryComposer("", {}, false, false, false, ProviderConfig(), {}, {}, {})
                    MaryScrollToLatest(
                        away.value, scrolling.value, session.value, { enabledClicks.value++ },
                        Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = (-56).dp)
                    )
                }
            }
        }
        node("mary-jump-to-latest").assertDoesNotExist()
        compose.runOnIdle { scrolling.value = false }
        node("mary-jump-to-latest").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        val button = bounds("mary-jump-to-latest")
        val composer = bounds("mary-composer")
        assertEquals(46f, button.width.value, 0.5f)
        assertEquals(composer.center.x.value, button.center.x.value, 0.5f)
        assertTrue(
            "Jump button must never cover the input or tools",
            button.bottom <= composer.top - 8.dp
        )
        node("mary-jump-to-latest").performClick()
        compose.runOnIdle { assertEquals(1, enabledClicks.value) }
        screenshot("jump-centered-over-composer.png")
        advanceUi(3_200)
        node("mary-jump-to-latest").assertDoesNotExist()
        compose.runOnIdle { scrolling.value = true }
        advanceUi(240)
        node("mary-jump-to-latest").assertDoesNotExist()
        compose.runOnIdle { scrolling.value = false }
        advanceUi(240)
        node("mary-jump-to-latest").assertIsDisplayed()
        compose.runOnIdle { scrolling.value = true }
        advanceUi(80)
        node("mary-jump-to-latest").assertIsNotEnabled()
        advanceUi(240)
        node("mary-jump-to-latest").assertDoesNotExist()
        compose.runOnIdle { away.value = false; scrolling.value = false; session.value = "two" }
        advanceUi(3_300)
        node("mary-jump-to-latest").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }

    @Test
    fun awaitingCardExpansionAndJumpToATallLastMessageKeepTheTrueBottomVisible() {
        val awaiting = mutableStateOf(false)
        val tall = mutableStateOf(false)
        val following = mutableStateOf(true)
        lateinit var list: LazyListState
        lateinit var jump: () -> Unit
        val request = AskRequest(
            "tail-question",
            "Choose a drink",
            listOf(AskOption("a", "Coffee"), AskOption("b", "Tea")),
            toolCallId = "tail-call"
        )
        host {
            list = rememberLazyListState()
            val scope = rememberCoroutineScope()
            SideEffect { jump = { scope.launch { scrollMaryConversationToBottom(list) } } }
            MaryFollowConversationTail(list, following.value, "tail-test")
            val expansion = remember { ToolCardExpansion() }
            Box(
                Modifier
                    .requiredSize(360.dp, 640.dp)
                    .background(pageBackground)
                    .testTag("layout-container")
            ) {
                MaryFloatingChatLayout(
                    bottomInset = 80.dp,
                    content = { bottom ->
                        LazyColumn(
                            state = list,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = bottom)
                        ) {
                            items(12) {
                                BasicText(
                                    "Earlier message $it",
                                    Modifier
                                        .fillMaxWidth()
                                        .height(80.dp)
                                )
                            }
                            item(key = "tail") {
                                if (tall.value) Box(Modifier
                                    .fillMaxWidth()
                                    .height(1000.dp)) {
                                    BasicText(
                                        "Actual end of tall answer",
                                        Modifier.align(Alignment.BottomStart)
                                    )
                                } else MaryToolCallCard(
                                    ChatEntry.ToolCall(
                                    "tail-row",
                                    "Ask",
                                    "{}",
                                    running = true,
                                    callId = "tail-call"
                                ),
                                    expansion, pendingAsk = if (awaiting.value) request else null,
                                    onAnswer = { _, _, _ -> false }, onApprove = { _, _ -> false })
                            }
                        }
                    },
                    footer = {
                        MaryComposer(
                            "",
                            {},
                            false,
                            false,
                            false,
                            ProviderConfig(),
                            {},
                            {},
                            {})
                    })
            }
        }
        compose.runOnIdle { awaiting.value = true }
        node("mary-tool-submit-tail-question").assertIsDisplayed()
        assertTrue(
            "Awaiting form must not be behind the composer",
            bounds("mary-tool-submit-tail-question").bottom <= bounds("mary-composer").top - 8.dp
        )
        compose.runOnIdle { tall.value = true }
        fun assertTailAligned() {
            compose.runOnIdle {
                val info = list.layoutInfo
                val last = info.visibleItemsInfo.last()
                assertEquals(info.totalItemsCount - 1, last.index)
                assertTrue(
                    "The bottom, not just the start, of a tall final row must be visible",
                    last.offset + last.size <= info.viewportEndOffset - info.afterContentPadding + 2
                )
            }
        }
        assertTailAligned()
        compose.runOnIdle { following.value = false }
        compose.runOnIdle { runBlocking { list.scrollToItem(0) } }
        compose.runOnIdle { assertEquals(0, list.firstVisibleItemIndex); jump() }
        compose.waitForIdle()
        assertTailAligned()
    }

    @Test
    fun topBarUsesLeadingMenuAndOnlyContextAction() {
        var opens = 0
        host {
            Scaffold(topBar = {
                MaryTopBar(MaryUsageSummary(1200, 32000, null), onOpenMenu = { opens++ })
            }) { }
        }
        node("mary-open-sidebar").assertIsEnabled()
        assertTrue(bounds("mary-open-sidebar").center.x < 72.dp)
        node("mary-context-usage").assertIsDisplayed()
        node("mary-open-sidebar").performClick()
        compose.runOnIdle { assertEquals(1, opens) }
        val context = requireNotNull(activity).get()
        listOf(R.string.mary_session_lock, R.string.mary_history_title,
            R.string.mary_action_new_chat, R.string.mary_settings_title).forEach { label ->
            compose.onNodeWithContentDescription(context.getString(label)).assertDoesNotExist()
        }
    }

    @Test
    fun darkQuestionUsesToolAccentAndComposerHasASubtleFeatheredHalo() {
        val request = AskRequest(
            "dark-drink", "Choose a drink", listOf(
                AskOption("coffee", "Coffee"),
                AskOption("tea", "Tea"),
                AskOption("juice", "Juice"),
                AskOption("water", "Water")
            )
        )
        host(ColorSchemeMode.Dark) {
            val expansion = remember { ToolCardExpansion() }
            Box(
                Modifier
                    .requiredSize(360.dp, 760.dp)
                    .background(MiuixTheme.colorScheme.background)
                    .testTag("layout-container")
            ) {
                Column(Modifier
                    .fillMaxWidth()
                    .padding(16.dp)) {
                    MaryQuestionCard(
                        ChatEntry.Question("dark-question", request),
                        expansion,
                        true,
                        { _, _, _ -> false })
                }
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 16.dp, vertical = 32.dp)
                ) {
                    MaryComposer(
                        "draft",
                        {},
                        true,
                        false,
                        false,
                        ProviderConfig(model = "gpt-6-luna"),
                        {},
                        {},
                        {})
                }
            }
        }
        val rect = bounds("mary-composer")
        val far = layoutPixel(rect.center.x, rect.top - 27.dp)
        val near = layoutPixel(rect.center.x, rect.top - 3.dp)
        val middle = layoutPixel(rect.center.x, rect.top - 12.dp)
        assertTrue(
            "Dark halo must visibly feather away, without a thick border",
            colorDistance(near, far) > colorDistance(middle, far)
        )
        assertTrue("Dark halo must remain subtle", colorDistance(near, far) < 0.12f)
        val stop = node("mary-composer-send-icon").captureToImage().toPixelMap()
        assertColorNear(Color.White, stop[stop.width / 2, stop.height / 2])
        // A solid stop square fills its center; the former outlined square left primary here.
        node("mary-tool-submit-dark-drink").assertIsNotEnabled()
        screenshot("dark-inline-tools-and-composer.png")
    }

    @Test
    fun permissionAndQuestionUseTheSameCompactReferenceConfirmationFrame() {
        val question = AskRequest(
            "reference-question", "Choose a drink", listOf(
                AskOption("coffee", "Coffee"),
                AskOption("tea", "Tea"),
                AskOption("juice", "Juice"),
                AskOption("water", "Water")
            )
        )
        val approval = PendingApproval(
            ToolCallRequest(
                "reference-permission",
                "WriteFile",
                """{"path":"example.txt"}""",
                ToolRisk.MUTATING
            ),
            CompletableDeferred(), "reference-approval"
        )
        host(ColorSchemeMode.Dark) {
            val expansion = remember { ToolCardExpansion() }
            Column(
                Modifier
                    .requiredSize(360.dp, 760.dp)
                    .background(MiuixTheme.colorScheme.background)
                    .padding(8.dp)
                    .testTag("layout-container"),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
            ) {
                MaryQuestionCard(
                    ChatEntry.Question("reference-ask-row", question),
                    expansion,
                    true,
                    { _, _, _ -> false })
                MaryToolCallCard(
                    ChatEntry.ToolCall(
                    "reference-permission-row",
                    "WriteFile",
                    """{"path":"example.txt"}""",
                    running = true,
                    callId = "reference-permission"
                ), expansion, pendingApproval = approval,
                    onAnswer = { _, _, _ -> false }, onApprove = { _, _ -> false })
            }
        }
        val ask = bounds("mary-confirmation-reference-ask-row")
        val permission = bounds("mary-confirmation-reference-permission-row")
        assertEquals(ask.width.value, permission.width.value, 0.1f)
        assertTrue("Permission should be compact, not a large form", permission.height < 180.dp)
        assertTrue("Four choices should remain a compact plain list", ask.height < 330.dp)
        val submit = bounds("mary-tool-submit-reference-question")
        assertTrue(
            "The action is a compact pill, not a full-width button",
            submit.width < 100.dp && submit.height <= 44.dp
        )
        val actions = bounds("mary-confirmation-actions-reference-permission-row")
        assertTrue(actions.right <= permission.right - 15.dp)
        compose.onNodeWithText("{\"path\":\"example.txt\"}").assertDoesNotExist()
        screenshot("reference-question-and-permission-dark.png")
        fieldLabel(R.string.mary_tool_arguments).performClick()
        compose.onNodeWithText("{\"path\":\"example.txt\"}").assertIsDisplayed()
    }

    @Test
    fun approvalMenuUsesReferenceIconsTwoLineSummariesAndTracksBothEntries() {
        val mode = mutableStateOf(ApprovalMode.ASK)
        val saving = mutableStateOf(false)
        host {
            Scaffold {
                Box(Modifier
                    .fillMaxSize()
                    .background(pageBackground)
                    .testTag("layout-container")) {
                    Column(Modifier.padding(top = 48.dp)) {
                        MaryApprovalModePreference(mode.value, { mode.value = it })
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 80.dp)
                    ) {
                        MaryComposer(
                            "draft",
                            {},
                            running = false,
                            locked = false,
                            configuring = saving.value,
                            provider = ProviderConfig(model = "gpt-5.2"),
                            onReasoningSelected = {},
                            onSend = {},
                            onStop = {},
                            approvalMode = mode.value,
                            onApprovalModeSelected = { mode.value = it })
                    }
                }
            }
        }
        assertEquals("Outlined.FrontHand", approvalModeIcon(ApprovalMode.ASK).name)
        assertEquals("ApprovalQuestion", approvalModeIcon(ApprovalMode.AUTO).name)
        assertEquals("Outlined.GppMaybe", approvalModeIcon(ApprovalMode.ALLOW_ALL).name)
        ApprovalMode.entries.forEach { option ->
            val icon = approvalModeIcon(option)
            assertEquals(24f, icon.viewportWidth, 0f)
            assertEquals(24f, icon.viewportHeight, 0f)
        }
        ApprovalMode.entries.forEach { next ->
            node("mary-approval-mode").performClick()
            node("mary-approval-options").assertIsDisplayed()
            node("mary-approval-option-${mode.value.name}").assertIsSelected()
            ApprovalMode.entries.forEach { option ->
                node("mary-approval-option-icon-${option.name}").assertIsDisplayed()
                val title = node("mary-approval-option-title-${option.name}")
                val summary = node("mary-approval-option-summary-${option.name}")
                title.assertIsDisplayed()
                summary.assertIsDisplayed()
                val titleBounds = title.getUnclippedBoundsInRoot()
                val summaryBounds = summary.getUnclippedBoundsInRoot()
                assertTrue(
                    "Description belongs below the title, not after a colon",
                    titleBounds.bottom <= summaryBounds.top
                )
                assertEquals(titleBounds.left.value, summaryBounds.left.value, 0.5f)
            }
            node("mary-approval-option-${next.name}").performClick()
            compose.runOnIdle { assertEquals(next, mode.value) }
            node("mary-approval-current-${next.name}").assertIsDisplayed()
            node("mary-approval-options").assertDoesNotExist()
        }
        // The settings entry shares the same menu and selected state, not a separate enum UI.
        node("mary-settings-approval-mode").performClick()
        node("mary-approval-option-ALLOW_ALL").assertIsSelected()
        node("mary-approval-option-ASK").performClick()
        node("mary-approval-current-ASK").assertIsDisplayed()
        compose.runOnIdle { saving.value = true }
        node("mary-approval-mode").assertIsNotEnabled()
    }

    @Test
    fun approvalComposerIconsMatchSelectedMenuAccentAndKeepDisabledHueInBothThemes() {
        val theme = mutableStateOf(ColorSchemeMode.Light)
        var themePrimary = Color.Unspecified
        var selections = 0
        host {
            MiuixTheme(ThemeController(theme.value)) {
                val currentPrimary = MiuixTheme.colorScheme.primary
                SideEffect { themePrimary = currentPrimary }
                Scaffold {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MiuixTheme.colorScheme.background)
                            .padding(24.dp)
                            .testTag("layout-container")
                    ) {
                        BasicText(
                            "${theme.value.name}: enabled / disabled",
                            style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceContainer)
                        )
                        ApprovalMode.entries.forEach { mode ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BasicText(
                                    mode.name, modifier = Modifier.weight(1f),
                                    style = MiuixTheme.textStyles.body2.copy(color = MiuixTheme.colorScheme.onSurfaceContainer)
                                )
                                listOf(true, false).forEach { enabled ->
                                    ApprovalModeButton(mode, enabled, {}, { selections++ })
                                }
                            }
                        }
                    }
                }
            }
        }
        listOf(ColorSchemeMode.Light, ColorSchemeMode.Dark).forEach { scheme ->
            compose.runOnIdle { theme.value = scheme }
            compose.waitForIdle()
            ApprovalMode.entries.forEachIndexed { index, mode ->
                val buttons =
                    compose.onAllNodesWithTag("mary-approval-mode", useUnmergedTree = true)
                val enabledButton = buttons[index * 2]
                val disabledButton = buttons[index * 2 + 1]
                enabledButton.assertIsEnabled()
                disabledButton.assertIsNotEnabled().performClick()
                node("mary-approval-options").assertDoesNotExist()
                listOf(enabledButton, disabledButton).forEach { button ->
                    val rect = button.getUnclippedBoundsInRoot()
                    assertEquals(44f, rect.width.value, 0.5f)
                    assertEquals(44f, rect.height.value, 0.5f)
                }
                val icons = compose.onAllNodesWithTag(
                    "mary-approval-current-${mode.name}",
                    useUnmergedTree = true
                )
                listOf(icons[0], icons[1]).forEach { icon ->
                    val rect = icon.getUnclippedBoundsInRoot()
                    assertEquals(22f, rect.width.value, 0.5f)
                    assertEquals(22f, rect.height.value, 0.5f)
                }
                val enabledPixels = icons[0].captureToImage().toPixelMap()
                val disabledPixels = icons[1].captureToImage().toPixelMap()
                val expectedAccent =
                    if (mode == ApprovalMode.ALLOW_ALL) Color(0xFFE97833) else themePrimary
                val enabledInk = strongestIconPixel(enabledPixels)
                assertColorNear(expectedAccent, enabledInk)
                assertDisabledIconRetainsHalfAccent(enabledPixels, disabledPixels)

                enabledButton.performClick()
                node("mary-approval-option-${mode.name}").assertIsSelected()
                val selectedMenuPixels = node("mary-approval-option-icon-${mode.name}")
                    .captureToImage().toPixelMap()
                assertColorNear(enabledInk, strongestIconPixel(selectedMenuPixels))
                // Close through the real menu selection so subsequent captures have no dim overlay.
                node("mary-approval-option-${mode.name}").performClick()
                node("mary-approval-options").assertDoesNotExist()
                disabledButton.assertIsNotEnabled()
            }
            screenshot("approval-mode-accents-${scheme.name.lowercase()}.png")
        }
        compose.runOnIdle { assertEquals(6, selections) }
    }

    @Test
    fun textKeepsExpandedAfterFocusClearsThenEmptyAndUnfocusedCollapses() {
        composer()
        val compactHeight = bounds("mary-composer").height
        node("mary-composer-input").performClick().performTextInput("Mary layout regression")
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        node("mary-composer-tools").assertIsDisplayed()
        node("mary-composer-send").assertIsEnabled()
        assertAboveNavigation()

        node("mary-composer-input").performTextClearance()
        compose.runOnIdle { focusManager.clearFocus(force = true) }
        node("mary-composer-tools").assertDoesNotExist()
        // An empty draft swaps send back to dictation, which stays available.
        node("mary-composer-send").assertIsEnabled()
        node("mary-composer-voice-icon").assertIsDisplayed()
        assertEquals(compactHeight.value, bounds("mary-composer").height.value, 0.5f)
        assertAboveNavigation()
    }

    @Test
    fun emptyComposerShrinksThroughIntermediateHeightInsteadOfSnapping() {
        composer()
        val compactHeight = bounds("mary-composer").height.value
        node("mary-composer-input").performClick()
        val expandedHeight = bounds("mary-composer").height.value
        assertTrue(expandedHeight > compactHeight)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { focusManager.clearFocus(force = true) }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeBy(80)
            val middleHeight = bounds("mary-composer").height.value
            assertTrue(
                "Collapse must animate a real intermediate layout height",
                middleHeight > compactHeight && middleHeight < expandedHeight
            )
            // Entering compact buttons and exiting tool-row buttons coexist during the
            // transition; assert the actual clipped drawing bounds, not a single-node tag.
            val safeBottomPx = node("app-navigation").fetchSemanticsNode().boundsInRoot.top
            assertTrue(node("mary-composer").fetchSemanticsNode().boundsInRoot.bottom <= safeBottomPx)
            listOf(
                "mary-composer-input",
                "mary-composer-attach",
                "mary-composer-send"
            ).forEach { tag ->
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()
                    .forEach { child ->
                        assertTrue(
                            "Animated $tag must not draw over navigation",
                            child.boundsInRoot.bottom <= safeBottomPx + 0.5f
                        )
                    }
            }
            compose.mainClock.advanceTimeBy(300)
            assertEquals(compactHeight, bounds("mary-composer").height.value, 0.5f)
            node("mary-composer-tools").assertDoesNotExist()
            assertAboveNavigation()
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test
    fun rootHostedSettingsEditorAndPageApplyTheKeyboardInsetOnlyOnce() {
        val endpoint = "https://offline.example.invalid/v1"
        host {
            // The popup host and navigation stay full-window; only the settings page
            // avoids the IME. This mirrors MainActivity + MarySettingsScreen.
            Scaffold {
                Box(Modifier
                    .fillMaxSize()
                    .testTag("mary-fixed-root")) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .imePadding()
                            .testTag("mary-ime-viewport")
                            .padding(top = 48.dp)
                    ) {
                        MarySettingsField(endpoint, {}, R.string.mary_provider_endpoint)
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .size(40.dp)
                            .testTag("mary-ime-navigation")
                    )
                }
            }
        }
        fieldLabel(R.string.mary_provider_endpoint).performClick()
        val editor =
            compose.onNode(hasSetTextAction() and hasText(endpoint), useUnmergedTree = true)
        editor.assertIsDisplayed()
        val editorBefore = editor.getUnclippedBoundsInRoot()
        val viewportBefore = bounds("mary-ime-viewport")
        val navigationBefore = bounds("mary-ime-navigation")
        val rootBefore = bounds("mary-fixed-root")
        setKeyboardInset(260)
        val viewportAfter = bounds("mary-ime-viewport")
        val editorAfter = editor.getUnclippedBoundsInRoot()
        assertEquals(
            "Page must lift by one keyboard height", 260f,
            viewportBefore.bottom.value - viewportAfter.bottom.value, 1f
        )
        assertEquals(
            "Root-hosted editor must lift by one keyboard height, not two", 260f,
            editorBefore.bottom.value - editorAfter.bottom.value, 1f
        )
        assertEquals(
            "Navigation must not follow the keyboard",
            navigationBefore,
            bounds("mary-ime-navigation")
        )
        assertEquals(rootBefore, bounds("mary-fixed-root"))
        editor.assertIsDisplayed()
        setKeyboardInset(0)
        assertEquals(viewportBefore, bounds("mary-ime-viewport"))
        assertEquals(editorBefore, editor.getUnclippedBoundsInRoot())
        assertEquals(navigationBefore, bounds("mary-ime-navigation"))
    }

    @Test
    fun multilineComposerAndEveryToolRemainAboveNavigation() {
        composer(initial = "line one\nline two\nline three\nline four\nline five")
        node("mary-composer-tools").assertIsDisplayed()
        assertTrue(bounds("mary-composer-input").height > 44.dp)
        assertExpandedTools()
        assertAboveNavigation()
    }

    @Test
    fun shortViewportPreservesNavigationInsetDuringExpansionAndMultilineInput() {
        composer(height = 280.dp)
        assertAboveNavigation()
        node("mary-composer-input").performClick()
        assertExpandedTools()
        assertAboveNavigation()
        node("mary-composer-input").performTextInput("one\ntwo\nthree\nfour\nfive\nsix")
        assertExpandedTools()
        assertAboveNavigation()
    }

    @Test
    fun lightComposerIsWhiteAndEmptyDictationStillHasRoundPrimaryFill() {
        composer()
        node("mary-composer-send").assertIsEnabled()
        node("mary-composer-voice-icon").assertIsDisplayed()
        assertEquals(Color.White, surfaceContainer)
        val pixels = node("layout-container").captureToImage().toPixelMap()
        val container = bounds("layout-container")
        val composer = bounds("mary-composer")
        val send = bounds("mary-composer-send-circle")
        fun pixel(x: Dp, y: Dp): Color {
            val px = ((x - container.left).value / container.width.value * pixels.width).toInt()
            val py = ((y - container.top).value / container.height.value * pixels.height).toInt()
            return pixels[px.coerceIn(0, pixels.width - 1), py.coerceIn(0, pixels.height - 1)]
        }
        assertColorNear(Color.White, pixel(composer.center.x, composer.top + 4.dp))
        // Sample above the arrow: disabled input must not fade/remove the primary circle.
        assertColorNear(primary, pixel(send.center.x, send.top + send.height * 0.15f))
        assertColorNear(Color.White, pixel(send.left + 1.dp, send.top + 1.dp))
        assertSquare("mary-composer-send", 44.dp)
        assertSquare("mary-composer-send-circle", 32.dp)
        // The outer squircle must leave the light-gray page visible at its corners.
        assertColorNear(pageBackground, pixel(composer.left + 1.dp, composer.top + 1.dp))
    }

    @Test
    fun contextIndicatorIs20DpRingWithUnpaintedInteriorRatherThanPieWedge() {
        host {
            Box(Modifier
                .requiredSize(120.dp, 80.dp)
                .background(pageBackground)) {
                ContextUsageButton(MaryUsageSummary(750, 1000, null))
            }
        }
        assertSquare("mary-context-ring", 20.dp)
        val pixels = node("mary-context-ring").captureToImage().toPixelMap()
        // With 75% usage, a filled-sector regression paints the upper-left interior.
        val reference = pixels[0, 0]
        assertColorNear(reference, pixels[pixels.width / 2, pixels.height / 2])
        assertColorNear(reference, pixels[pixels.width / 3, pixels.height / 3])
        assertTrue(
            "Ring must actually draw, not just be an empty 20dp canvas",
            (0 until pixels.height).any { y ->
                (0 until pixels.width).any { x -> colorDistance(reference, pixels[x, y]) > 0.15f }
            })
    }

    @Test
    fun modelAdvancedFieldsAreInitiallyCollapsedAndToggleWithRealUiClick() {
        modelSettings()
        fieldLabel(R.string.mary_behaviour_temperature).assertDoesNotExist()
        fieldLabel(R.string.mary_behaviour_max_tokens).assertDoesNotExist()
        fieldLabel(R.string.mary_provider_context).assertDoesNotExist()
        fieldLabel(R.string.mary_settings_advanced_model).performScrollTo().performClick()
        fieldLabel(R.string.mary_behaviour_temperature).performScrollTo().assertIsDisplayed()
        fieldLabel(R.string.mary_behaviour_max_tokens).performScrollTo().assertIsDisplayed()
        fieldLabel(R.string.mary_provider_context).performScrollTo().assertIsDisplayed()
        fieldLabel(R.string.mary_settings_advanced_model).performScrollTo().performClick()
        fieldLabel(R.string.mary_behaviour_temperature).assertDoesNotExist()
        fieldLabel(R.string.mary_behaviour_max_tokens).assertDoesNotExist()
        fieldLabel(R.string.mary_provider_context).assertDoesNotExist()
    }

    @Test
    fun modelSettingsSectionTitleSitsOutsideAndAboveTheCard() {
        modelSettings()
        val title = bounds("mary-model-section-${R.string.mary_settings_provider}")
        val card = bounds("mary-model-card-${R.string.mary_settings_provider}")
        node("mary-model-section-${R.string.mary_settings_provider}").assertIsDisplayed()
        assertTrue(
            "Section title must be outside the card: $title / $card",
            title.bottom <= card.top
        )
        assertTrue(title.left >= card.left)
        screenshot("settings.png")
    }

    @Test
    fun outlinedModelTestPlayButtonIsInsideTheFieldAtTheRightWithoutSendingRequests() {
        modelSettings()
        val field = node("mary-model-input-${R.string.mary_settings_provider}")
        field.performScrollTo().assertIsDisplayed()
        val model = field.getUnclippedBoundsInRoot()
        val play = node("mary-model-test-${R.string.mary_settings_provider}")
        play.assertIsDisplayed()
        val button = play.getUnclippedBoundsInRoot()
        assertTrue(
            "Play action must be embedded inside the model field",
            button.left >= model.left && button.right <= model.right
        )
        assertTrue(
            "Play action must sit at the right of the field",
            button.center.x > model.left + model.width * 0.75f
        )
        assertTrue(
            "Play action must fit inside the field vertically",
            button.top >= model.top && button.bottom <= model.bottom
        )
        val pixels = play.captureToImage().toPixelMap()
        assertColorNear(pixels[2, 2], pixels[pixels.width / 2, pixels.height / 2])
        assertTrue(
            "Outlined play icon must have a visible outline",
            (0 until pixels.height).any { y ->
                (0 until pixels.width).any { x ->
                    colorDistance(
                        pixels[2, 2],
                        pixels[x, y]
                    ) > 0.15f
                }
            })
        // Do not click Play or Available models: both are network actions.
    }

    @Test
    fun unknownContextRingIsGrayAndQuarterUsagePaintsOnlyUpperRightQuarterBlue() {
        host {
            Row(
                Modifier
                    .requiredSize(360.dp, 80.dp)
                    .background(pageBackground)
                    .testTag("layout-container")
            ) {
                ContextUsageButton(MaryUsageSummary(null, 1000, null))
                ContextUsageButton(MaryUsageSummary(250, 1000, null))
            }
        }
        val rings = compose.onAllNodesWithTag("mary-context-ring", useUnmergedTree = true)
        assertEquals(2, rings.fetchSemanticsNodes().size)
        compose.onNodeWithText("25%", useUnmergedTree = true).assertDoesNotExist()
        rings.fetchSemanticsNodes().indices.forEach { index ->
            val ring = rings[index].getUnclippedBoundsInRoot()
            assertEquals(20f, ring.width.value, 0.5f)
            assertEquals(20f, ring.height.value, 0.5f)
        }
        val unknown = rings[0].captureToImage().toPixelMap()
        val quarter = rings[1].captureToImage().toPixelMap()
        assertTrue(
            "Unknown ring must be neutral gray",
            kotlin.math.abs(outline.red - outline.blue) < 0.08f
        )
        fun sample(pixels: PixelMap, x: Float, y: Float) =
            pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
        listOf(0.83f to 0.17f, 0.17f to 0.17f, 0.17f to 0.83f, 0.83f to 0.83f).forEach { (x, y) ->
            assertColorNear(outline, sample(unknown, x, y))
        }
        assertColorNear(primary, sample(quarter, 0.83f, 0.17f))
        listOf(0.17f to 0.17f, 0.17f to 0.83f, 0.83f to 0.83f).forEach { (x, y) ->
            assertColorNear(outline, sample(quarter, x, y))
        }
        screenshot("context.png")
    }

    @Test
    fun compactComposerHasSemicircularEndsAndExternalShadow() {
        composer()
        val rect = bounds("mary-composer")
        // Both ends must follow a half-circle, not a near-square superellipse.
        listOf(rect.left + 2.dp, rect.right - 2.dp).forEach { x ->
            listOf(rect.top + rect.height * 0.20f, rect.bottom - rect.height * 0.20f).forEach { y ->
                val corner = layoutPixel(x, y)
                assertTrue(
                    "Capsule corners must expose the softly shaded page, not a square fill",
                    colorDistance(Color.White, corner) > 0.06f
                )
                assertTrue(
                    "The external shadow must remain light",
                    colorDistance(pageBackground, corner) < 0.08f
                )
            }
            assertColorNear(Color.White, layoutPixel(x, rect.center.y))
        }
        assertExternalShadow()
    }

    @Test
    fun expandedComposerKeepsExternalShadowAndNavigationVisible() {
        composer()
        node("mary-composer-input").performClick()
        assertExpandedTools()
        assertAboveNavigation()
        assertExternalShadow()
    }

    @Test
    fun keyboardMovesOnlyFloatingComposerAndLeavesTranscriptAndNavigationFixed() {
        composer(colorBands = true)
        val viewport = bounds("layout-container")
        val transcript = bounds("mary-transcript")
        val navigation = bounds("app-navigation")
        node("mary-composer-input").performClick()
        listOf(260, 300, 40, 0).forEach { keyboardHeight ->
            setKeyboardInset(keyboardHeight)
            val composer = bounds("mary-composer")
            val obstruction = maxOf(80, keyboardHeight)
            assertEquals(
                "The transcript must never shrink to make a composer dock", transcript,
                bounds("mary-transcript")
            )
            assertEquals(viewport, bounds("layout-container"))
            assertEquals(
                "Navigation stays at the physical window bottom", navigation,
                bounds("app-navigation")
            )
            assertEquals(
                "Keyboard and navigation must be combined with max, not added",
                (viewport.bottom - obstruction.dp - 8.dp).value, composer.bottom.value, 0.5f
            )
            assertEquals(
                composer.height.value + obstruction + 8f + 24f,
                transcriptBottomPadding.value, 0.5f
            )
            // The full-width region beside the composer is still drawn by the transcript,
            // not by an opaque bottom-bar or a reduced-height content slot.
            val stripeIndex = ((composer.center.y - viewport.top).value / 40f).toInt()
            val expected = if (stripeIndex % 2 == 0) messageBandA else messageBandB
            assertColorNear(expected, layoutPixel(viewport.left + 2.dp, composer.center.y))
            assertColorNear(expected, layoutPixel(viewport.right - 2.dp, composer.center.y))
            compose.runOnIdle { runBlocking { transcriptScroll.scrollTo(transcriptScroll.maxValue) } }
            node("mary-message-11").assertIsDisplayed()
            assertTrue(
                "The final AI message must be reachable above the floating input",
                bounds("mary-message-11").bottom <= composer.top - 11.dp
            )
        }
    }

    @Test
    fun transcriptFillsViewportAndRemainsVisibleThroughTransparentFooterMargins() {
        composer(colorBands = true)
        val viewport = bounds("layout-container")
        val transcript = bounds("mary-transcript")
        assertEquals(viewport, transcript)
        fun assertMargins() {
            val composer = bounds("mary-composer")
            val y = composer.center.y
            val stripeIndex = ((y - viewport.top).value / 40f).toInt()
            val expected = if (stripeIndex % 2 == 0) messageBandA else messageBandB
            assertColorNear(expected, layoutPixel(viewport.left + 2.dp, y))
            assertColorNear(expected, layoutPixel(viewport.right - 2.dp, y))
            assertTrue(transcript.bottom > composer.bottom)
        }
        assertMargins()
        node("mary-composer-input").performClick()
        assertMargins()
        assertAboveNavigation()
    }

    @Test
    fun measuredFooterPaddingLetsLastMessageScrollAboveBothComposerStates() {
        composer()
        fun assertLastMessageReachable() {
            val composer = bounds("mary-composer")
            assertEquals(
                composer.height.value + 80f + 8f + 24f,
                transcriptBottomPadding.value, 0.5f
            )
            compose.runOnIdle { runBlocking { transcriptScroll.scrollTo(transcriptScroll.maxValue) } }
            node("mary-message-11").assertIsDisplayed()
            assertTrue(
                "Last message must scroll clear of floating composer",
                bounds("mary-message-11").bottom <= composer.top - 11.dp
            )
        }
        assertLastMessageReachable()
        node("mary-composer-input").performClick()
        assertLastMessageReachable()
        assertAboveNavigation()
    }

    @Test
    fun realSettingsHeaderShowsSaveAfterPresetsAndDisablesActionsWhenStoreNotReady() {
        host {
            Box(Modifier
                .requiredSize(360.dp, 720.dp)
                .testTag("layout-container")) {
                MarySettingsScreen(onBack = {})
            }
        }
        val notReady = requireNotNull(activity).get().getString(R.string.mary_settings_not_ready)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(notReady, useUnmergedTree = true).fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Miuix IconButton removes clickable entirely when disabled (rather than Disabled semantics).
        node("mary-settings-presets").assertIsDisplayed().assertHasNoClickAction()
        node("mary-settings-save").assertIsDisplayed().assertHasNoClickAction()
        val presets = bounds("mary-settings-presets")
        val save = bounds("mary-settings-save")
        assertTrue(
            "Save must be to the right of Tune in the real TopAppBar",
            presets.right <= save.left
        )
        assertEquals(presets.center.y.value, save.center.y.value, 0.5f)
        screenshot("settings-header.png")
        // No click: save and presets must not mutate the real settings store.
    }

    @Test
    fun nativePreferenceEditorCancelsDraftAndOnlyCommitsOnConfirm() {
        val draft = modelSettings()
        val original = draft.value.provider.baseUrl
        val replacement = "https://edited.example.invalid/v1"
        fieldLabel(R.string.mary_provider_endpoint).performScrollTo().performClick()
        val editor =
            compose.onNode(hasSetTextAction() and hasText(original), useUnmergedTree = true)
        editor.assertIsDisplayed().performTextReplacement(replacement)
        compose.runOnIdle { assertEquals(original, draft.value.provider.baseUrl) }
        screenshot("editor.png")
        fieldLabel(R.string.rear_widget_cancel).performClick()
        compose.runOnIdle { assertEquals(original, draft.value.provider.baseUrl) }
        fieldLabel(R.string.mary_provider_endpoint).performScrollTo().performClick()
        val reopened =
            compose.onNode(hasSetTextAction() and hasText(original), useUnmergedTree = true)
        reopened.assertIsDisplayed().performTextReplacement(replacement)
        fieldLabel(R.string.rear_widget_confirm).performClick()
        compose.runOnIdle { assertEquals(replacement, draft.value.provider.baseUrl) }
        compose.onNodeWithText(replacement, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    @Config(shadows = [NoSurfaceMagnifierShadow::class])
    fun diagnosticShowsSafeHttpStatusEndpointResponseAndSupportsSelectionCopy() {
        val fakeKey = "ui-fixture-secret-not-a-real-key"
        val config = ProviderConfig(
            model = "offline-model", apiKey = fakeKey,
            baseUrl = "https://example.invalid/v1?api_key=$fakeKey"
        )
        val diagnostic = ProviderRequestDiagnostics.format(
            AgentError.Http(
                403,
                "Response body: permission denied; Authorization: Bearer $fakeKey"
            ),
            config, "test model",
        )
        assertTrue(diagnostic.contains("HTTP status: 403"))
        assertTrue(diagnostic.contains("https://example.invalid/v1"))
        assertTrue(diagnostic.contains("permission denied"))
        assertTrue(!diagnostic.contains(fakeKey))
        val toolbar = CopyTestToolbar()
        host {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides toolbar) {
                Box(
                    Modifier
                        .requiredSize(360.dp, 480.dp)
                        .background(pageBackground)
                        .testTag("layout-container")
                ) {
                    Scaffold { padding ->
                        Column(Modifier
                            .padding(padding)
                            .padding(top = 20.dp)) {
                            MarySettingsDiagnostic(diagnostic)
                        }
                    }
                }
            }
        }
        val text = compose.onNodeWithText(diagnostic, useUnmergedTree = true)
        text.assertIsDisplayed()
        screenshot("diagnostic.png")
        text.performTouchInput { longClick(Offset(48f, 8f)) }
        compose.waitForIdle()
        compose.runOnIdle {
            val copy =
                requireNotNull(toolbar.copyAction) { "SelectionContainer must expose a copy action after long press" }
            copy()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val clipboard = requireNotNull(activity).get()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied =
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(requireNotNull(activity).get())
                    ?.toString().orEmpty()
            assertTrue(
                "Real selection copy must populate clipboard with safe diagnostic text",
                copied.isNotBlank() && diagnostic.contains(copied) && !copied.contains(fakeKey)
            )
        }
    }

    // Robolectric has no native magnifier Surface; only bypass that platform decoration.
    @Implements(Magnifier::class)
    class NoSurfaceMagnifierShadow {
        @Implementation
        fun show(x: Float, y: Float) = Unit
        @Implementation
        fun show(x: Float, y: Float, windowX: Float, windowY: Float) = Unit
        @Implementation
        fun dismiss() = Unit
        @Implementation
        fun update() = Unit
    }

    // Observe the platform menu callback; selection and clipboard writes remain production code.
    private class CopyTestToolbar : TextContextMenuProvider {
        var copyAction: (() -> Unit)? = null
            private set

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
            val closed = CompletableDeferred<Unit>()
            val session = object : TextContextMenuSession {
                override fun close() {
                    closed.complete(Unit)
                }
            }
            val item = dataProvider.data().components.filterIsInstance<TextContextMenuItem>()
                .first { it.key == TextContextMenuKeys.CopyKey }
            copyAction = { item.onClick(session) }
            closed.await()
        }
    }

    private fun modelSettings(): MutableState<ModelDraft> {
        val draft = mutableStateOf(
            ModelDraft(
                provider = ProviderConfig(
                    model = "custom-model", baseUrl = "https://example.invalid/v1", apiKey = "",
                )
            )
        )
        host {
            Box(
                Modifier
                    .requiredSize(360.dp, 720.dp)
                    .background(pageBackground)
                    .testTag("layout-container")
            ) {
                Scaffold { padding ->
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(padding)
                            .padding(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        ModelConfigurationCard(
                            title = R.string.mary_settings_provider,
                            draft = draft.value,
                            onChange = { draft.value = it },
                            unreadable = false,
                        )
                    }
                }
            }
        }
        return draft
    }

    private fun fieldLabel(resource: Int) = compose.onNodeWithText(
        requireNotNull(activity).get().getString(resource), useUnmergedTree = true,
    )

    private fun composer(
        initial: String = "",
        height: Dp = 640.dp,
        colorBands: Boolean = false,
        voicePhase: MaryVoicePhase = MaryVoicePhase.IDLE
    ) {
        val text = mutableStateOf(initial)
        host {
            focusManager = LocalFocusManager.current
            Box(
                Modifier
                    .requiredSize(360.dp, height)
                    .background(pageBackground)
                    .testTag("layout-container")
            ) {
                MaryFloatingChatLayout(
                    bottomInset = 80.dp,
                    modifier = Modifier.fillMaxSize(),
                    content = { bottomPadding ->
                        val scroll = rememberScrollState()
                        SideEffect {
                            transcriptBottomPadding = bottomPadding
                            transcriptScroll = scroll
                        }
                        Box(Modifier
                            .fillMaxSize()
                            .testTag("mary-transcript")
                            .drawBehind {
                                if (colorBands) {
                                    val stripe = 40.dp.toPx()
                                    repeat((size.height / stripe).toInt() + 1) { index ->
                                        drawRect(
                                            if (index % 2 == 0) messageBandA else messageBandB,
                                            Offset(0f, index * stripe), Size(size.width, stripe),
                                        )
                                    }
                                }
                            }) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(scroll)
                                    .padding(bottom = bottomPadding)
                            ) {
                                repeat(12) { index ->
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .height(80.dp)
                                            .padding(horizontal = 24.dp)
                                            .testTag("mary-message-$index"),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        BasicText(if (index % 2 == 0) "Mary · ${index + 1}" else "Layout regression · ${index + 1}")
                                    }
                                }
                            }
                        }
                    },
                    footer = {
                        MaryComposer(
                            value = text.value,
                            onValueChange = { text.value = it },
                            running = false,
                            locked = false,
                            configuring = false,
                            provider = ProviderConfig(model = "gpt-5.2"),
                            onReasoningSelected = {},
                            onSend = { sent++ },
                            onStop = {},
                            voicePhase = voicePhase,
                        )
                    },
                )
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(80.dp)
                        .background(navigationBackground)
                        .testTag("app-navigation"),
                    contentAlignment = Alignment.Center
                ) {
                    Row {
                        repeat(3) {
                            Box(
                                Modifier
                                    .padding(horizontal = 36.dp)
                                    .size(12.dp)
                                    .background(Color(0xFF9CA3AF))
                            )
                        }
                    }
                }
            }
        }
        assertEquals(360f, bounds("layout-container").width.value, 0.5f)
        assertEquals(height.value, bounds("layout-container").height.value, 0.5f)
    }

    private fun setKeyboardInset(bottomPx: Int) {
        compose.runOnUiThread {
            val current = requireNotNull(activity).get()
            current.enableEdgeToEdge()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
                .setVisible(WindowInsetsCompat.Type.ime(), bottomPx > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(current.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    private fun host(
        mode: ColorSchemeMode = ColorSchemeMode.Light,
        content: @Composable () -> Unit
    ) {
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent {
                MiuixTheme(ThemeController(mode)) {
                    val scheme = MiuixTheme.colorScheme
                    SideEffect {
                        primary = scheme.primary
                        surfaceContainer = scheme.surfaceContainer
                        outline = scheme.outline
                    }
                    content()
                }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String, tag: String = "layout-container") {
        val directory = File(requireNotNull(System.getProperty("mary.ui.screenshotDir")))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        val image =
            (if (tag == "root") compose.onRoot() else node(tag)).captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { output ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)

    private fun bounds(tag: String): DpRect = node(tag).getUnclippedBoundsInRoot()

    private val DpRect.center: DpOffset
        get() = DpOffset((left + right) / 2f, (top + bottom) / 2f)

    private fun layoutPixel(x: Dp, y: Dp): Color {
        val rect = bounds("layout-container")
        val pixels = node("layout-container").captureToImage().toPixelMap()
        val px = ((x - rect.left).value / rect.width.value * pixels.width).toInt()
        val py = ((y - rect.top).value / rect.height.value * pixels.height).toInt()
        return pixels[px.coerceIn(0, pixels.width - 1), py.coerceIn(0, pixels.height - 1)]
    }

    private fun assertExternalShadow() {
        val composer = bounds("mary-composer")
        val pixels = node("layout-container").captureToImage().toPixelMap()
        val viewport = bounds("layout-container")
        fun sample(x: Dp, y: Dp): Color = pixels[
            ((x - viewport.left).value / viewport.width.value * pixels.width).toInt(),
            ((y - viewport.top).value / viewport.height.value * pixels.height).toInt(),
        ]
        assertTrue(
            "Native renderer must draw gray shadow outside the composer bounds",
            (1..5).any { offset ->
                val color = sample(composer.center.x, composer.bottom + offset.dp)
                color.red < pageBackground.red - 0.002f &&
                        kotlin.math.abs(color.red - color.green) < 0.015f &&
                        kotlin.math.abs(color.red - color.blue) < 0.015f
            })
    }

    private fun assertAboveNavigation() {
        val container = bounds("layout-container")
        val composer = bounds("mary-composer")
        val safeBottom = container.bottom - 80.dp
        node("app-navigation").assertIsDisplayed()
        val navigation = bounds("app-navigation")
        assertEquals(80f, navigation.height.value, 0.5f)
        assertEquals(safeBottom.value, navigation.top.value, 0.5f)
        assertColorNear(
            navigationBackground,
            layoutPixel(navigation.left + 2.dp, navigation.center.y)
        )
        assertTrue(
            "Composer $composer overlaps navigation boundary $safeBottom",
            composer.bottom <= safeBottom + 0.5.dp
        )
        listOf("mary-composer-input", "mary-composer-attach", "mary-composer-send").forEach { tag ->
            node(tag).assertIsDisplayed()
            val child = bounds(tag)
            assertTrue("$tag extends below safe area", child.bottom <= safeBottom + 0.5.dp)
            assertTrue("$tag extends above composer", child.top >= composer.top - 0.5.dp)
        }
    }

    private fun assertExpandedTools() {
        node("mary-composer-tools").assertIsDisplayed()
        val input = bounds("mary-composer-input")
        val tools = bounds("mary-composer-tools")
        val attach = bounds("mary-composer-attach")
        val reasoning = bounds("mary-reasoning-strength")
        val send = bounds("mary-composer-send")
        assertTrue(input.bottom <= tools.top + 0.5.dp)
        assertEquals(tools.left.value, attach.left.value, 0.5f)
        assertTrue(attach.right < reasoning.left)
        assertEquals(4f, (send.left - reasoning.right).value, 0.5f)
        assertEquals(tools.right.value, send.right.value, 0.5f)
        listOf(attach, reasoning, send).forEach {
            assertEquals(tools.center.y.value, it.center.y.value, 0.5f)
            assertTrue(it.bottom <= bounds("layout-container").bottom - 80.dp + 0.5.dp)
        }
    }

    private fun assertActionIconCentered(tag: String, size: Dp) {
        val action = bounds("mary-composer-send")
        val icon = bounds(tag)
        assertEquals(action.center.x.value, icon.center.x.value, 0.5f)
        assertEquals(action.center.y.value, icon.center.y.value, 0.5f)
        assertSquare(tag, size)
    }

    private fun assertSquare(tag: String, size: Dp) {
        val rect = bounds(tag)
        assertEquals(size.value, rect.width.value, 0.5f)
        assertEquals(size.value, rect.height.value, 0.5f)
    }

    private fun strongestIconPixel(pixels: PixelMap): Color {
        val background = pixels[0, 0].compositeOver(Color.Black)
        return (0 until pixels.height).flatMap { y ->
            (0 until pixels.width).map { x -> pixels[x, y].compositeOver(Color.Black) }
        }.maxBy { colorDistance(it, background) }
    }

    private fun assertDisabledIconRetainsHalfAccent(enabled: PixelMap, disabled: PixelMap) {
        assertEquals(enabled.width, disabled.width)
        assertEquals(enabled.height, disabled.height)
        // Use each actual capture's untouched corner, not an assumed theme/window background.
        // Compositing onto the same black base also handles transparent native captures.
        val enabledBackground = enabled[0, 0].compositeOver(Color.Black)
        val disabledBackground = disabled[0, 0].compositeOver(Color.Black)
        var inkPixels = 0
        for (y in 0 until enabled.height) {
            for (x in 0 until enabled.width) {
                val original = enabled[x, y].compositeOver(Color.Black)
                val faded = disabled[x, y].compositeOver(Color.Black)
                if (colorDistance(original, enabledBackground) > 0.1f) inkPixels++
                listOf(
                    Triple(
                        original.red,
                        faded.red,
                        enabledBackground.red to disabledBackground.red
                    ),
                    Triple(
                        original.green,
                        faded.green,
                        enabledBackground.green to disabledBackground.green
                    ),
                    Triple(
                        original.blue,
                        faded.blue,
                        enabledBackground.blue to disabledBackground.blue
                    ),
                ).forEach { (full, half, background) ->
                    assertEquals(
                        "Disabled icon must halve each color channel's contrast at ($x, $y)",
                        (full - background.first) * 0.5f, half - background.second, 0.025f
                    )
                }
            }
        }
        assertTrue("Native capture must contain actual icon strokes", inkPixels > 10)
    }

    private fun assertColorNear(expected: Color, actual: Color) {
        assertTrue("Expected $expected, got $actual", colorDistance(expected, actual) < 0.04f)
    }

    private fun colorDistance(a: Color, b: Color): Float = maxOf(
        kotlin.math.abs(a.red - b.red), kotlin.math.abs(a.green - b.green),
        kotlin.math.abs(a.blue - b.blue), kotlin.math.abs(a.alpha - b.alpha),
    )

    private companion object {
        val pageBackground = Color(0xFFEEEEEE)
        val navigationBackground = Color(0xFFF6F6F6)
        val messageBandA = Color(0xFFE3EDF7)
        val messageBandB = Color(0xFFE9EFE5)
    }
}
