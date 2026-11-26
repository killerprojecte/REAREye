package hk.uwu.reareye.ui.components

import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import java.io.File
import androidx.compose.ui.graphics.asAndroidBitmap
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.reareye.ui.capture.captureWindowBitmap
import org.junit.Assert.assertTrue
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.components.LargeTitleColumnScaffold
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.Card
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.data.LEGACY_DIALOGUE_MODEL_ID
import hk.uwu.reareye.agent.data.LEGACY_DIALOGUE_PROVIDER_ID
import hk.uwu.reareye.agent.data.MaryWebSearchEngine
import hk.uwu.reareye.agent.data.SettingsSnapshot
import hk.uwu.reareye.ui.screen.MarySettingsScreen
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Offline field transactions, using real controls and native in-window form hosts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarySettingsFieldInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var value by mutableStateOf("")
    private val writes = mutableListOf<String>()
    private var label = R.string.mary_provider_endpoint

    @Before fun explicitUiRun() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }

    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }

    private fun frames() {
        repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }

    private fun host(backend: MixUiBackend, initial: String, password: Boolean = false, dark: Boolean = false,
        rtl: Boolean = false, fieldLabel: Int? = null) {
        compose.runOnUiThread {
            activity?.close()
            value = initial
            writes.clear()
            label = fieldLabel ?: if (password) R.string.mary_provider_api_key else R.string.mary_provider_endpoint
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend = backend, colorMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    CompositionLocalProvider(LocalLayoutDirection provides
                        if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    LargeTitleColumnScaffold(title = "Mary settings") {
                        Card {
                            MarySettingsField(value, { value = it; writes += it }, label, password)
                        }
                    }
                    }
                }
            }
        }
        frames()
    }

    private fun open() {
        compose.onNodeWithText(activity!!.get().getString(label)).performClick()
        frames()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    private fun screenshot(name: String) {
        val directory = File(System.getProperty("ui.capture.output"), "mary-settings-field")
        check(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onAllNodes(isRoot()).filter(hasAnyDescendant(hasSetTextAction()))
            .onFirst().captureToImage().asAndroidBitmap()
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    private fun button(resource: Int) {
        compose.onNodeWithText(activity!!.get().getString(resource)).performClick()
        frames()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
    }

    private class RecordingKeyboard : SoftwareKeyboardController {
        val calls = mutableListOf<String>()
        override fun show() { calls += "show" }
        override fun hide() { calls += "hide" }
    }

    /** Real pointer and relocation gestures against the public scaffold; no Android/device IME window. */
    @Test fun inlineModelAutomaticScrollKeepsKeyboardButPointerDragDismisses() {
        val keyboard = RecordingKeyboard()
        val scroll = ScrollState(0)
        val bringIntoView = BringIntoViewRequester()
        var relocationTargetBounds = Rect.Zero
        lateinit var scope: CoroutineScope
        var input by mutableStateOf("model-name")
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(MixUiBackend.Mochi, MixColorSchemeMode.Light) {
                    CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                        scope = rememberCoroutineScope()
                        LargeTitleColumnScaffold(title = "Mary settings", scrollState = scroll) {
                            hk.uwu.mixui.components.TextField(
                                value = input, onValueChange = { input = it },
                                label = "Model", singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("inline-model"),
                            )
                            Spacer(Modifier.height(1000.dp))
                            // A widthless Spacer has an empty relocation rectangle and
                            // bringIntoView correctly ignores it. Give the target real area.
                            Spacer(Modifier.fillMaxWidth().height(100.dp)
                                .bringIntoViewRequester(bringIntoView)
                                .onGloballyPositioned { coordinates ->
                                    val position = coordinates.positionInRoot()
                                    relocationTargetBounds = Rect(position.x, position.y,
                                        position.x + coordinates.size.width,
                                        position.y + coordinates.size.height)
                                })
                            Spacer(Modifier.height(800.dp))
                        }
                    }
                }
            }
        }
        frames()
        val field = inlineField("inline-model")
        compose.mainClock.autoAdvance = true
        field.performScrollTo()
        compose.mainClock.autoAdvance = false
        frames()
        field.performTouchInput { click() }; frames()
        field.assertIsFocused()
        val nodeId = field.fetchSemanticsNode().id
        // Simulate an already shown keyboard. Platform input sessions can bypass
        // LocalSoftwareKeyboardController.show(), so do not infer device IME visibility.
        compose.runOnUiThread { keyboard.calls.clear(); keyboard.show() }
        val before = compose.runOnIdle { scroll.value }
        compose.runOnUiThread { scope.launch { scroll.animateScrollTo(before + 80) } }
        frames()
        compose.runOnIdle {
            assertTrue("Automatic scroll must actually move", scroll.value > before)
            assertEquals("Automatic positioning must not dismiss the IME", listOf("show"), keyboard.calls)
        }
        field.assertIsFocused()
        assertEquals(nodeId, field.fetchSemanticsNode().id)
        field.performTextInput("x"); frames()
        assertEquals(listOf("show"), keyboard.calls)
        val beforeBringIntoView = compose.runOnIdle { scroll.value }
        val viewportBounds = compose.onNode(hasScrollAction()).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            println("INLINE_IME_RELOCATION scroll=$beforeBringIntoView max=${scroll.maxValue} " +
                "viewport=$viewportBounds target=$relocationTargetBounds")
            assertTrue("Relocation target must have nonempty area",
                relocationTargetBounds.width > 0f && relocationTargetBounds.height > 0f)
            assertTrue("Relocation target must start below the visible viewport",
                relocationTargetBounds.top >= viewportBounds.bottom)
            assertTrue("Page must have remaining scroll range", scroll.maxValue > beforeBringIntoView)
        }
        compose.runOnUiThread { scope.launch { bringIntoView.bringIntoView() } }
        frames()
        compose.runOnIdle {
            assertTrue("BringIntoView must actually reposition the page", scroll.value > beforeBringIntoView)
            assertEquals("BringIntoView must not dismiss the IME", listOf("show"), keyboard.calls)
        }
        // A real pointer drag on the page, not emit(DragInteraction.Start).
        compose.onNode(hasScrollAction()).performTouchInput {
            swipe(Offset(width * 0.8f, height * 0.8f),
                Offset(width * 0.8f, height * 0.45f), 420)
        }
        frames()
        assertTrue("A user page drag must dismiss the keyboard", "hide" in keyboard.calls)
        screenshot("Mochi-inline-public-scaffold-after-drag")
        println("INLINE_IME_POLICY calls=${keyboard.calls}")
    }

    @Test fun actualAgentEditorAutomaticScrollKeepsKeyboardButPointerDragDismisses() {
        val keyboard = RecordingKeyboard()
        hostSettings(MixUiBackend.Mochi, dark = false, keyboard = keyboard)
        // Model and provider fields now live in the native sheet. The Agent budget
        // is still an inline editor in the real settings page's scroll viewport.
        val field = inlineField("mary-settings-turn-budget")
        reveal(field)
        field.performTouchInput { click() }; frames()
        field.assertIsFocused()
        val id = field.fetchSemanticsNode().id
        compose.runOnUiThread { keyboard.calls.clear(); keyboard.show() }

        val page = compose.onNodeWithTag("mary-settings-agent")
        val scrollAxis = androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange
        val before = page.fetchSemanticsNode().config[scrollAxis].value()
        val delta = if (before > 0f) -48f else 48f
        page.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
        frames()
        assertTrue("Programmatic scroll must actually move the Agent page", page.fetchSemanticsNode().config[scrollAxis].value() != before)
        compose.runOnIdle { assertEquals(listOf("show"), keyboard.calls) }
        field.assertIsFocused()
        assertEquals("Automatic scroll must not remount the budget editor", id, field.fetchSemanticsNode().id)
        field.performTextInput("1"); frames()
        compose.runOnIdle { assertEquals(listOf("show"), keyboard.calls) }

        val canReturnToTop = page.fetchSemanticsNode().config[scrollAxis].value() > 0f
        page.performTouchInput {
            val upper = Offset(width * 0.8f, height * 0.5f)
            val lower = Offset(width * 0.8f, height * 0.85f)
            swipe(if (canReturnToTop) upper else lower, if (canReturnToTop) lower else upper, 420)
        }
        frames()
        compose.runOnIdle {
            assertTrue("A pointer drag on the Agent page must dismiss the keyboard", "hide" in keyboard.calls)
        }
        screenshot("Mochi-inline-actual-mary-agent-after-drag")
    }

    @Test fun actualAgentInlineFieldsRemainVisibleWhileKeyboardOpensAndResizes() {
        for (backend in MixUiBackend.entries) {
            val keyboard = RecordingKeyboard()
            hostSettings(backend, dark = false, keyboard = keyboard)
            val page = compose.onNodeWithTag("mary-settings-agent")
            val budget = inlineField("mary-settings-turn-budget")
            val resultChars = inlineField("mary-settings-result-chars")
            // Align the last field with the original bottom of the scroll viewport.
            reveal(resultChars)
            budget.performTouchInput { click() }; frames()
            budget.assertIsFocused()
            val budgetId = budget.fetchSemanticsNode().id
            val pageBottom = page.getUnclippedBoundsInRoot().bottom.value
            val originalFieldBottom = budget.getUnclippedBoundsInRoot().bottom.value
            // The keyboard must cover the old field position if the page does not scroll.
            val keyboardHeight = maxOf(280, (pageBottom - originalFieldBottom).roundToInt() + 88)
            compose.runOnUiThread { keyboard.calls.clear() }
            dispatchSettingsIme(keyboardHeight)

            fun assertAboveKeyboard(field: SemanticsNodeInteraction, ime: Int) {
                field.assertIsDisplayed().assertIsFocused()
                val bounds = field.getUnclippedBoundsInRoot()
                assertTrue("$backend field $bounds is behind IME top ${pageBottom - ime}",
                    bounds.bottom.value <= pageBottom - ime + 1f)
                assertTrue("$backend field is clipped after relocation",
                    field.fetchSemanticsNode().boundsInRoot.height >= bounds.height.value - 1f)
            }

            assertAboveKeyboard(budget, keyboardHeight)
            assertEquals(budgetId, budget.fetchSemanticsNode().id)
            budget.performTextReplacement("24"); frames()
            budget.performTextInput("1"); frames()
            assertCaretAndText(budget, "mary-settings-turn-budget", "241", 3)
            // Tap the other field without closing the IME, then enlarge the keyboard.
            reveal(resultChars)
            resultChars.performTouchInput { click() }; frames()
            dispatchSettingsIme(keyboardHeight + 56)
            assertAboveKeyboard(resultChars, keyboardHeight + 56)
            resultChars.performTextReplacement("4096"); frames()
            resultChars.assertTextContains("4096")
            assertAboveKeyboard(resultChars, keyboardHeight + 56)
            captureSettings("${backend.name}-inline-keyboard")
            dispatchSettingsIme(0)
            resultChars.assertIsFocused().assertIsDisplayed()
            assertEquals(pageBottom, page.getUnclippedBoundsInRoot().bottom.value, 1f)
            compose.runOnIdle { assertTrue("$backend automatic placement must not hide the IME", "hide" !in keyboard.calls) }
        }
    }

    private fun dispatchSettingsIme(bottomPx: Int) {
        compose.runOnUiThread {
            val window = checkNotNull(activity).get().window
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottomPx))
                .setVisible(WindowInsetsCompat.Type.ime(), bottomPx > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(window.decorView, insets)
        }
        frames()
    }

    @Test fun nativeFieldKeepsFocusAndNodeDuringHorizontalSelectionAndTyping() {
        for (backend in MixUiBackend.entries) {
            host(backend, "https://example.invalid/v1/model-with-long-name")
            open()
            val field = compose.onNode(hasSetTextAction())
            field.performTouchInput { click() }; frames()
            field.assertIsFocused()
            val identity = field.fetchSemanticsNode().id
            repeat(4) {
                field.performTouchInput {
                    swipe(Offset(width * 0.8f, height / 2f), Offset(width * 0.2f, height / 2f), 420)
                }
                frames()
                field.assertIsFocused()
                assertEquals("Native field must not remount while moving cursor", identity, field.fetchSemanticsNode().id)
            }
            field.performTextInput("x"); frames()
            field.assertIsFocused()
            assertEquals(identity, field.fetchSemanticsNode().id)
            assertEquals("Typing must not commit the draft", emptyList<String>(), writes)
            screenshot("${backend.name}-horizontal-cursor-focus")
            button(R.string.rear_widget_cancel)
            assertEquals(emptyList<String>(), writes)
        }
    }

    @Test fun cancelDiscardsAndReopeningCopiesTheLatestParentValue() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, "https://offline.example.invalid/v1", dark = dark)
            open()
            screenshot("${backend.name}-${if (dark) "dark" else "light"}")
            compose.onNode(hasSetTextAction()).performTextReplacement("not saved")
            compose.runOnIdle { assertEquals(emptyList<String>(), writes) }
            button(R.string.rear_widget_cancel)
            compose.runOnIdle {
                assertEquals("https://offline.example.invalid/v1", value)
                assertEquals(emptyList<String>(), writes)
                value = "https://updated.example.invalid/v2"
            }
            frames()
            open()
            compose.onNode(hasSetTextAction()).assertTextContains("https://updated.example.invalid/v2")
            compose.onNode(hasSetTextAction()).performTextReplacement("https://saved.example.invalid/v3")
            button(R.string.rear_widget_confirm)
            compose.runOnIdle { assertEquals(listOf("https://saved.example.invalid/v3"), writes) }
            open()
            compose.onNode(hasSetTextAction()).assertTextContains("https://saved.example.invalid/v3")
            button(R.string.rear_widget_cancel)
            compose.runOnIdle { assertEquals(1, writes.size) }
        }
    }

    private fun assertInitialSelection(expectedLength: Int) {
        val field = compose.onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
        val selection = field.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assertEquals("Selection starts at the beginning", 0, selection.min)
        assertEquals("Selection covers the entire draft", expectedLength, selection.max)
    }

    private fun assertDraftText(expected: String) {
        // EditableText is deliberately transformed for passwords. InputText is
        // the underlying draft; checking it does not unmask the rendered field.
        val field = compose.onNode(hasSetTextAction(), useUnmergedTree = true)
        field.assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.InputText,
            androidx.compose.ui.text.AnnotatedString(expected),
        ))
        val semantics = field.fetchSemanticsNode().config
        if (semantics.contains(androidx.compose.ui.semantics.SemanticsProperties.Password)) {
            val displayed = semantics[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
            assertEquals("Password presentation retains the draft length", expected.length, displayed.length)
            assertTrue("Password presentation stays masked", displayed.all { it == '\u2022' })
        }
    }

    /** Every label below is an actual MarySettingsField call-site in production. All values are inert fixtures. */
    @Test fun everyProductionFieldOpensFocusedSelectedAndRendersBothThemes() {
        val fields = listOf(
            Triple(R.string.mary_provider_endpoint, "https://offline.example.invalid/v1", "endpoint"),
            Triple(R.string.mary_provider_api_key, "fake-offline-secret", "api-key"),
            Triple(R.string.mary_behaviour_temperature, "0.7", "temperature"),
            Triple(R.string.mary_behaviour_max_tokens, "4096", "max-tokens"),
            Triple(R.string.mary_provider_context, "32768", "context"),
            Triple(R.string.mary_behaviour_iterations, "12", "iterations"),
            Triple(R.string.mary_behaviour_tool_failures, "3", "tool-failures"),
            Triple(R.string.mary_behaviour_turn_budget, "240", "turn-budget"),
            Triple(R.string.mary_behaviour_result_chars, "12000", "result-chars"),
        )
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true))
            for ((resource, initial, name) in fields) {
                host(backend, initial, password = resource == R.string.mary_provider_api_key,
                    dark = dark, fieldLabel = resource)
                open(); assertInitialSelection(initial.length)
                screenshot("${backend.name}-${if (dark) "dark" else "light"}-field-$name")
                compose.onNode(hasSetTextAction()).performTextInput("7"); frames()
                assertDraftText("7")
                assertEquals("Typing edits only the field draft", emptyList<String>(), writes)
                button(R.string.rear_widget_cancel)
                assertEquals(initial, value)
                open(); assertInitialSelection(initial.length)
                button(R.string.rear_widget_cancel)
            }
    }

    @Test fun ordinarySecretAndEmptyInputsReplaceOnceThenRespectManualSelection() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true))
            for ((initial, secret) in listOf("original" to false, "fake-offline-secret" to true, "" to false, "" to true)) {
                host(backend, initial, password = secret, dark = dark)
                open(); assertInitialSelection(initial.length)
                val field = compose.onNode(hasSetTextAction(), useUnmergedTree = true)
                field.performTextInput("draft"); frames()
                assertDraftText("draft")
                field.performTextInputSelection(androidx.compose.ui.text.TextRange(1, 4)); frames()
                field.performTextInput("X"); frames()
                assertDraftText("dXt")
                field.performTextInput("Y"); frames()
                assertDraftText("dXYt")
                assertEquals(emptyList<String>(), writes)
                button(R.string.rear_widget_cancel)
                assertEquals(initial, value)
                open(); assertInitialSelection(initial.length)
                field.performTextInput("saved"); frames()
                button(R.string.rear_widget_confirm)
                assertEquals(listOf("saved"), writes)
                open(); assertInitialSelection("saved".length)
                button(R.string.rear_widget_cancel)
            }
    }

    @Test fun secretSummaryStaysMaskedAndClearingRequiresConfirm() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, "fake-offline-secret", password = true, dark = dark)
            compose.onNodeWithText("••••••", substring = true).assertIsDisplayed()
            compose.onNodeWithText("fake-offline-secret", substring = true).assertDoesNotExist()
            open()
            compose.onNode(hasSetTextAction()).performTextClearance()
            button(R.string.rear_widget_cancel)
            compose.runOnIdle { assertEquals("fake-offline-secret", value); assertEquals(0, writes.size) }
            open()
            compose.onNode(hasSetTextAction()).performTextClearance()
            button(R.string.rear_widget_confirm)
            compose.runOnIdle { assertEquals("", value); assertEquals(listOf(""), writes) }
        }
    }
    @Test fun repeatedSubmitOldCallbacksAndNativeDismissalDoNotChangeNewDrafts() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            host(backend, "original", dark = dark)
            open()
            compose.onNode(hasSetTextAction()).performTextReplacement("first")
            val confirm = compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_confirm))
                .fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnUiThread { confirm(); confirm() }
            frames()
            assertEquals(listOf("first"), writes)
            open()
            compose.onNode(hasSetTextAction()).performTextReplacement("second")
            // Vendor overlays may detach or retain their exiting native node.
            // Only the known detached-node rejection is allowed; retained actions
            // must still leave the new draft and committed value unchanged below.
            compose.runOnUiThread {
                val failure = runCatching { confirm() }.exceptionOrNull()
                assertTrue("Unexpected failure invoking retired native action: $failure",
                    failure == null || (failure is IllegalStateException &&
                        failure.message.orEmpty().contains("not currently attached")))
            }
            frames()
            compose.onNode(hasSetTextAction()).assertTextContains("second")
            assertEquals(listOf("first"), writes)
            compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_confirm))
                .performTouchInput { down(center); advanceEventTime(32); cancel() }
            frames()
            assertEquals(listOf("first"), writes)
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            frames()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            open()
            compose.onNode(hasSetTextAction()).assertTextContains("first")
            compose.onNode(isRoot() and hasAnyDescendant(hasSetTextAction()), useUnmergedTree = true)
                .performTouchInput { click(Offset(2f, 2f)) }
            frames()
            compose.onNode(hasSetTextAction()).assertDoesNotExist()
            open()
            button(R.string.rear_widget_cancel)
            assertEquals(listOf("first"), writes)
        }
    }

    /** Real field/form, controlled page source. Native-renderer evidence, not device shader QA. */
    @Test fun fieldGlassRespondsToPageButDoesNotSampleItsOwnEditor() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            var body by mutableStateOf(Color(0xFF202020))
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                label = R.string.mary_provider_endpoint
                activity!!.get().setContent {
                    MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                        GlassPreferenceScene {
                            Box(Modifier.fillMaxSize().background(body)) {
                                MarySettingsField("short", {}, label)
                            }
                        }
                    }
                }
            }
            frames(); open()
            val target = compose.onNodeWithTag("mary-field-dialog-$label", useUnmergedTree = true)
            fun shot(name: String): Bitmap {
                val window = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
                val dir = File(System.getProperty("ui.capture.output"), "mary-settings-field").apply { mkdirs() }
                File(dir, "${backend.name}-$dark-$name-window.png").outputStream().use {
                    check(window.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val bounds = target.fetchSemanticsNode().boundsInWindow
                return Bitmap.createBitmap(window, bounds.left.roundToInt(), bounds.top.roundToInt(),
                    bounds.width.roundToInt(), bounds.height.roundToInt())
            }
            val before = shot("initial")
            compose.onNode(hasSetTextAction()).performTextReplacement("MMMMMMMMMMMMMMMMMMMMMMMM")
            frames()
            val edited = shot("edited")
            compose.runOnIdle { body = Color(0xFF80C0F0) }
            frames()
            val changed = shot("body-changed")
            fun difference(a: Bitmap, b: Bitmap): Double {
                var sum = 0L; var count = 0L
                // Blank left interior margin, outside input/button fills and corner clipping.
                for (y in 48 until a.height - 48) for (x in 8 until 16) {
                    val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                    for (shift in listOf(0, 8, 16)) {
                        sum += abs(((p shr shift) and 255) - ((q shr shift) and 255)); count++
                    }
                }
                return sum.toDouble() / count
            }
            val self = difference(before, edited)
            val source = difference(edited, changed)
            println("MARY_FIELD_SAMPLING backend=$backend dark=$dark self=$self source=$source")
            assertTrue("Editor must not contaminate its own glass: $self", self < 1.5)
            assertTrue("Glass must sample the page: $source", source > 2.0)
        }
    }

    @Test fun actualSettingsPageEditsEndpointAndBudgetWithoutPersistingBeforeSave() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            val snapshot = hostSettings(backend, dark)
            val address = inlineField("mary-settings-provider-address")
            val budget = inlineField("mary-settings-turn-budget")
            val endpointDraft = "https://offline.example.invalid/v1"
            val budgetDraft = "240"

            selectSettingsTab(R.string.mary_phase_tab_providers, "mary-settings-providers")
            openProviderEditor()
            reveal(address)
            address.performTextReplacement(endpointDraft)
            frames()
            address.assertTextContains(endpointDraft)
            captureSettings("${backend.name}-$dark-address")
            dismissProviderEditor(R.string.mary_phase_apply_provider)

            selectSettingsTab(R.string.mary_phase_tab_agent, "mary-settings-agent")
            reveal(budget)
            budget.performTextReplacement(budgetDraft)
            frames()
            budget.assertTextContains(budgetDraft)
            captureSettings("${backend.name}-$dark-budget")

            selectSettingsTab(R.string.mary_phase_tab_providers, "mary-settings-providers")
            budget.assertDoesNotExist()
            openProviderEditor()
            reveal(address)
            address.assertTextContains(endpointDraft)
            address.performTextReplacement("https://discard.example.invalid/v1")
            frames()
            dismissProviderEditor(R.string.rear_widget_cancel)
            openProviderEditor()
            address.assertTextContains(endpointDraft)
            dismissProviderEditor(R.string.rear_widget_cancel)
            selectSettingsTab(R.string.mary_phase_tab_agent, "mary-settings-agent")
            address.assertDoesNotExist()
            reveal(budget)
            budget.assertTextContains(budgetDraft)
            assertEquals("Switching tabs must not commit either draft", snapshot, activity!!.get().getSharedPreferences(
                "reareye_agent_secrets", 0).getString("settings_snapshot_v1", null))
        }
    }

    @Test fun miuixActualSettingsPreservesCaretBetweenKeystrokes() = actualSettingsPreservesCaret(MixUiBackend.Miuix)

    @Test fun mochiActualSettingsPreservesCaretBetweenKeystrokes() = actualSettingsPreservesCaret(MixUiBackend.Mochi)

    private fun actualSettingsPreservesCaret(backend: MixUiBackend) {
        hostSettings(backend, dark = false)
        selectSettingsTab(R.string.mary_phase_tab_providers, "mary-settings-providers")
        openProviderEditor()
        assertSequentialEditing("mary-settings-provider-address", "https://example.invalid/", "a", "b", "Q")
        assertSequentialEditing("mary-settings-provider-key", "", "a", "b", "Q", secure = true)
        dismissProviderEditor(R.string.rear_widget_cancel)

        selectSettingsTab(R.string.mary_phase_tab_models, "mary-settings-models")
        compose.onNodeWithTag("mary-settings-model-$LEGACY_DIALOGUE_MODEL_ID").performTouchInput { click() }
        frames()
        assertSequentialEditing("mary-settings-model-id", "offline-model", "a", "b", "Q")
        compose.onNodeWithContentDescription(activity!!.get().getString(R.string.rear_widget_cancel)).performClick()
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithTag("mary-settings-model-editor").fetchSemanticsNodes().isEmpty()
        }

        selectSettingsTab(R.string.mary_phase_tab_agent, "mary-settings-agent")
        assertSequentialEditing("mary-settings-turn-budget", "24", "1", "2", "3")
    }

    private fun assertSequentialEditing(tag: String, initial: String, first: String, second: String, middle: String, secure: Boolean = false) {
        val field = inlineField(tag)
        reveal(field)
        field.performTextReplacement(initial)
        frames()
        field.performTextInputSelection(TextRange(initial.length), relativeToOriginalText = true)
        frames()
        field.performTextInput(first)
        frames()
        assertCaretAndText(field, tag, initial + first, initial.length + first.length)
        field.performTextInput(second)
        frames()
        val appended = initial + first + second
        assertCaretAndText(field, tag, appended, appended.length)
        field.performTextInputSelection(TextRange(1), relativeToOriginalText = true)
        frames()
        field.performTextInput(middle)
        frames()
        assertCaretAndText(field, tag, appended.substring(0, 1) + middle + appended.substring(1), 1 + middle.length)
        if (secure) assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
    }

    private fun assertCaretAndText(field: SemanticsNodeInteraction, tag: String, expected: String, caret: Int) {
        val semantics = field.fetchSemanticsNode().config
        assertEquals("$tag text", AnnotatedString(expected), semantics[SemanticsProperties.InputText])
        assertEquals("$tag caret", TextRange(caret), semantics[SemanticsProperties.TextSelectionRange])
    }

    @Test fun actualSettingsPersistsUnassignedProviderAndRunModeOnlyOnPageSave() {
        val snapshot = hostSettings(MixUiBackend.Miuix, dark = false)
        selectSettingsTab(R.string.mary_phase_tab_providers, "mary-settings-providers")
        compose.onNodeWithTag("mary-settings-provider-add").performClick()
        frames()
        val name = inlineField("mary-settings-provider-name")
        val address = inlineField("mary-settings-provider-address")
        reveal(name)
        name.performTextReplacement("Local test connection")
        frames()
        reveal(address)
        address.performTextReplacement("https://local-registry.example.invalid/v1")
        frames()
        dismissProviderEditor(R.string.mary_phase_apply_provider)
        selectSettingsTab(R.string.mary_phase_tab_agent, "mary-settings-agent")
        val chat = activity!!.get().getString(R.string.mary_phase_chat_mode)
        compose.onNode(hasText(chat) and hasClickAction() and hasAnyAncestor(hasTestTag("mary-settings-mode-tabs")))
            .performTouchInput { click() }
        frames()
        val prefs = activity!!.get().getSharedPreferences("reareye_agent_secrets", 0)
        assertEquals(snapshot, prefs.getString("settings_snapshot_v1", null))
        compose.onNodeWithTag("mary-settings-save").performClick()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            prefs.getString("settings_snapshot_v1", null) != snapshot
        }
        val saved = Json.decodeFromString(SettingsSnapshot.serializer(), requireNotNull(prefs.getString("settings_snapshot_v1", null)))
        val registry = requireNotNull(saved.registry)
        assertEquals(false, registry.agentMode)
        val provider = registry.providers.single { it.displayName == "Local test connection" }
        assertEquals("https://local-registry.example.invalid/v1", provider.baseUrl)
        assertTrue(registry.models.none { it.providerId == provider.id })
    }

    @Test fun modelAndProviderDeletionNeedConfirmationAndPageSave() {
        val snapshot = hostSettings(MixUiBackend.Miuix, dark = false)
        val context = activity!!.get()
        val prefs = context.getSharedPreferences("reareye_agent_secrets", 0)
        val modelTag = "mary-settings-model-$LEGACY_DIALOGUE_MODEL_ID"
        val providerTag = "mary-settings-provider-$LEGACY_DIALOGUE_PROVIDER_ID"
        selectSettingsTab(R.string.mary_phase_tab_models, "mary-settings-models")
        compose.onNodeWithTag(modelTag).performClick()
        frames()
        compose.onNodeWithTag("mary-settings-delete-model").performClick()
        frames()
        compose.onNodeWithText(context.getString(R.string.mary_settings_model_delete_confirmation)).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.rear_widget_cancel)) and hasClickAction()).performClick()
        frames()
        compose.onNodeWithTag("mary-settings-model-editor").assertIsDisplayed()
        assertEquals(snapshot, prefs.getString("settings_snapshot_v1", null))
        compose.onNodeWithTag("mary-settings-delete-model").performClick()
        frames()
        compose.onNode(hasText(context.getString(R.string.rear_widget_action_delete)) and hasClickAction()).performClick()
        frames()
        compose.onNodeWithTag(modelTag).assertDoesNotExist()
        selectSettingsTab(R.string.mary_phase_tab_providers, "mary-settings-providers")
        compose.onNodeWithTag(providerTag).performClick()
        frames()
        compose.onNodeWithTag("mary-settings-delete-provider").performClick()
        frames()
        compose.onNodeWithText(context.getString(R.string.mary_settings_provider_delete_confirmation)).assertIsDisplayed()
        compose.onNode(hasText(context.getString(R.string.rear_widget_action_delete)) and hasClickAction()).performClick()
        frames()
        compose.onNodeWithTag(providerTag).assertDoesNotExist()
        assertEquals(snapshot, prefs.getString("settings_snapshot_v1", null))
        compose.onNodeWithTag("mary-settings-save").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            prefs.getString("settings_snapshot_v1", null) != snapshot
        }
        val saved = Json.decodeFromString<SettingsSnapshot>(requireNotNull(prefs.getString("settings_snapshot_v1", null)))
        val registry = requireNotNull(saved.registry)
        assertEquals(null, registry.mainModelId)
        assertTrue(registry.models.isEmpty())
        assertTrue(registry.providers.isEmpty())
    }

    @Test fun webPolicyCanBeSavedWithoutShowingAnEngineSelector() {
        val original = SettingsSnapshot(
            AgentConfig(provider = ProviderConfig(model = "legacy", contextWindowTokens = 2_048), maxIterations = 90),
            webSearchEngine = "GOOGLE",
        )
        val snapshot = hostSettings(MixUiBackend.Miuix, dark = false, initial = original)
        val prefs = activity!!.get().getSharedPreferences("reareye_agent_secrets", 0)
        val web = compose.onNodeWithTag("mary-settings-web-enabled")
        reveal(web)
        compose.onNodeWithTag("mary-settings-web-engine").assertDoesNotExist()
        web.assertIsOn().performClick()
        frames()
        web.assertIsOff()
        assertEquals(snapshot, prefs.getString("settings_snapshot_v1", null))
        compose.onNodeWithTag("mary-settings-save").assertIsEnabled().performClick()
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            prefs.getString("settings_snapshot_v1", null) != snapshot
        }
        val saved = Json.decodeFromString<SettingsSnapshot>(requireNotNull(prefs.getString("settings_snapshot_v1", null)))
        assertEquals(false, saved.webEnabled)
        assertEquals(MaryWebSearchEngine.BING.name, saved.webSearchEngine)
        assertEquals(original.configuration, saved.configuration)
    }

    private fun hostSettings(
        backend: MixUiBackend,
        dark: Boolean,
        keyboard: RecordingKeyboard? = null,
        initial: SettingsSnapshot = SettingsSnapshot(AgentConfig(provider = ProviderConfig(model = "offline-model"))),
    ): String {
        val snapshot = Json.encodeToString(SettingsSnapshot.serializer(), initial)
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val current = activity!!.get()
            current.enableEdgeToEdge()
            current.getSharedPreferences("reareye_agent_secrets", 0)
                .edit().clear().putString("settings_snapshot_v1", snapshot).commit()
            current.setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    if (keyboard == null) MarySettingsScreen(onBack = {})
                    else CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                        MarySettingsScreen(onBack = {})
                    }
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithTag("mary-settings-mode-tabs").fetchSemanticsNodes().isNotEmpty()
        }
        frames()
        return snapshot
    }

    private fun selectSettingsTab(label: Int, pageTag: String) {
        val tab = compose.onNode(hasText(activity!!.get().getString(label)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("mary-settings-tabs")))
        tab.assertIsDisplayed().performTouchInput { click() }
        frames()
        tab.assertIsSelected()
        compose.onNodeWithTag(pageTag).assertIsDisplayed()
    }

    private fun openProviderEditor() {
        compose.onNodeWithTag("mary-settings-provider-$LEGACY_DIALOGUE_PROVIDER_ID").assertIsDisplayed().performTouchInput { click() }
        frames()
        compose.onNodeWithTag("mary-settings-provider-editor").assertIsDisplayed()
    }

    private fun dismissProviderEditor(label: Int) {
        compose.onNodeWithContentDescription(activity!!.get().getString(label)).performClick()
        // While the edited field loses focus, the native sheet can finish its exit on a later frame.
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithTag("mary-settings-provider-editor").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun inlineField(tag: String) = compose.onNode(hasSetTextAction() and
        (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)

    private fun reveal(field: SemanticsNodeInteraction) {
        // Both native backends need clock progress for animated relocation.
        compose.mainClock.autoAdvance = true
        try {
            field.performScrollTo().assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = false
        }
        frames()
    }

    private fun captureSettings(name: String) {
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        val output = File(System.getProperty("ui.capture.output"), "mary-settings-field").apply { mkdirs() }
        File(output, "actual-settings-$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun fieldActionsStaySideBySideInLogicalOrderForBothDirections() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true))
            for (rtl in listOf(false, true)) {
                host(backend, "original", dark = dark, rtl = rtl)
                open()
                val cancel = compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_cancel))
                val confirm = compose.onNodeWithText(activity!!.get().getString(R.string.rear_widget_confirm))
                cancel.assertIsDisplayed(); confirm.assertIsDisplayed()
                val cancelBounds = cancel.fetchSemanticsNode().boundsInWindow
                val confirmBounds = confirm.fetchSemanticsNode().boundsInWindow
                assertEquals("Actions must share one row", cancelBounds.center.y, confirmBounds.center.y, 1f)
                assertTrue("Cancel must precede confirm in layout direction",
                    if (rtl) confirmBounds.right <= cancelBounds.left else cancelBounds.right <= confirmBounds.left)
                assertTrue("Labels must have nonempty visible bounds", cancelBounds.width > 0 && confirmBounds.width > 0)
                screenshot("${backend.name}-$dark-${if (rtl) "rtl" else "ltr"}-actions")
                compose.onNode(hasSetTextAction()).performTextReplacement("cancelled")
                cancel.performClick(); frames()
                assertEquals(emptyList<String>(), writes)
                open()
                compose.onNode(hasSetTextAction()).assertTextContains("original")
                compose.onNode(hasSetTextAction()).performTextReplacement("confirmed")
                button(R.string.rear_widget_confirm)
                assertEquals(listOf("confirmed"), writes)
            }
    }

}
