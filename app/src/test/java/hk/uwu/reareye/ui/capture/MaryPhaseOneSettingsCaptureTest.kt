package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixUiBackend
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.screen.MarySettingsPreview
import hk.uwu.reareye.ui.screen.MarySettingsTab
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryPhaseOneSettingsCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val ui = MaryPhaseOneCapture(compose, "settings")

    @Before fun requireResources() = ui.requireResources()
    @After fun close() = ui.close()

    @Test fun miuixLightSettings() = exercise(MixUiBackend.Miuix, dark = false)
    @Test fun miuixDarkSettings() = exercise(MixUiBackend.Miuix, dark = true)
    @Test fun mochiLightSettings() = exercise(MixUiBackend.Mochi, dark = false)
    @Test fun mochiDarkSettings() = exercise(MixUiBackend.Mochi, dark = true)

    @Test fun miuixProviderDropdownUsesSheetPopupHostAndDismissesIndependently() {
        ui.open(MixUiBackend.Miuix, dark = false) { MarySettingsPreview(initialTab = MarySettingsTab.MODELS) }
        ui.node("mary-settings-model-add").performTouchInput { click() }
        ui.settle()
        val sheet = ui.node("mary-settings-model-editor")
        val hostTag = "mixui-native-glass-sheet-host"
        val host = ui.node(hostTag, unmerged = true)
        val anchor = ui.node("mary-settings-model-provider").assertTextContains("OpenAI")
        val id = field("mary-settings-model-id")
        val option = compose.onNode(
            hasText("DeepSeek") and hasClickAction() and hasAnyAncestor(hasTestTag(hostTag)) and
                !hasAnyAncestor(hasTestTag("mixui-native-glass-sheet")),
        )

        id.performTextReplacement("manual-provider-model")
        ui.settle()
        id.assertIsFocused()
        anchor.performScrollTo().performTouchInput { click() }
        ui.settle()
        option.assertIsDisplayed()
        id.assertIsNotFocused().assertTextContains("manual-provider-model")
        ui.capture("model-provider-dropdown")

        ui.back()
        option.assertDoesNotExist()
        sheet.assertIsDisplayed()
        anchor.assertTextContains("OpenAI").performTouchInput { click() }
        ui.settle()
        option.assertIsDisplayed()

        // Left of both panels: this tap would hit the sheet scrim without the popup's dismiss layer.
        host.performTouchInput { click(Offset(4f, center.y)) }
        ui.settle()
        option.assertDoesNotExist()
        sheet.assertIsDisplayed()
        anchor.assertTextContains("OpenAI").performTouchInput { click() }
        ui.settle()
        option.assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        option.assertDoesNotExist()
        sheet.assertIsDisplayed()
        anchor.assertTextContains("DeepSeek")
    }

    private fun exercise(backend: MixUiBackend, dark: Boolean) {
        ui.open(backend, dark) { MarySettingsPreview() }
        tab(R.string.mary_phase_tab_agent).assertIsSelected()
        ui.node("mary-settings-agent").assertIsDisplayed()
        ui.node("mary-settings-main-model").assertIsDisplayed()
        val chatMode = compose.onNode(hasText(ui.label(R.string.mary_phase_chat_mode)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("mary-settings-mode-tabs")))
        chatMode.performTouchInput { click() }
        ui.settle()
        chatMode.assertIsSelected()
        ui.capture("01-agent")

        selectTab(R.string.mary_phase_tab_models, "mary-settings-models")
        ui.node("mary-settings-agent").assertDoesNotExist()
        val mainModel = ui.node("mary-settings-model-main")
        mainModel.assertIsDisplayed()
        ui.node("mary-settings-model-add").assertContentDescriptionEquals(ui.label(R.string.mary_phase_register_model))
        ui.capture("02-models")
        mainModel.performTouchInput { click() }
        ui.settle()
        ui.node("mary-settings-model-editor").assertIsDisplayed()
        deleteAction(R.string.mary_phase_remove_model, R.string.mary_phase_apply_model).assertIsNotEnabled()
        field("mary-settings-model-id").assertIsDisplayed().assertTextContains("gpt-6.2-sol")
        val name = field("mary-settings-model-name")
        name.performScrollTo().assertIsDisplayed().assertTextContains("GPT 6.2 Sol")
        ui.capture("03-model-editor", includeExtraRoots = true)
        name.performTextReplacement("Sol test alias")
        ui.settle()
        name.assertTextContains("Sol test alias")
        editorAction(R.string.mary_phase_apply_model, "mary-settings-model-editor")

        selectTab(R.string.mary_phase_tab_providers, "mary-settings-providers")
        ui.node("mary-settings-models").assertDoesNotExist()
        val mainProvider = ui.node("mary-settings-provider-main").assertIsDisplayed()
        ui.node("mary-settings-provider-add").assertContentDescriptionEquals(ui.label(R.string.mary_phase_register_provider))
        val test = ui.node("mary-settings-provider-test")
        test.assertDoesNotExist()
        ui.capture("04-providers")
        mainProvider.performTouchInput { click() }
        ui.settle()
        ui.node("mary-settings-provider-editor").assertIsDisplayed()
        deleteAction(R.string.mary_phase_remove_provider, R.string.mary_phase_apply_provider).assertIsNotEnabled()
        val fetch = ui.node("mary-settings-provider-fetch")
        fetch.performScrollTo().assertIsDisplayed().assertIsEnabled()
        ui.capture("05-provider-editor", includeExtraRoots = true)
        test.assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        ui.settle()
        val tested = compose.onNode(hasText(ui.label(R.string.mary_provider_chat_test_ok)))
        tested.performScrollTo().assertIsDisplayed()
        fetch.performScrollTo().assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        compose.onNode(hasText("1 个模型") and
            hasAnyAncestor(hasTestTag("mary-settings-provider-editor"))).performScrollTo().assertIsDisplayed()
        ui.capture("06-provider-models-fetched", includeExtraRoots = true)
        editorAction(R.string.mary_phase_apply_provider, "mary-settings-provider-editor")

        selectTab(R.string.mary_phase_tab_models, "mary-settings-models")
        mainModel.performScrollTo().assertTextContains("Sol test alias")
        ui.node("mary-settings-model-add").assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        ui.node("mary-settings-model-editor").assertIsDisplayed()
        compose.onNodeWithContentDescription(ui.label(R.string.mary_phase_remove_model)).assertDoesNotExist()
        selectSheetOption("mary-settings-model-provider", "DeepSeek")
        selectSheetOption("mary-settings-model-provider", "OpenAI")
        val id = field("mary-settings-model-id").assertIsDisplayed()
        val candidate = compose.onNode(hasText("GPT 6.2 Sol") and hasText("gpt-6.2-sol") and hasClickAction() and
            hasAnyAncestor(hasTestTag("glass-suggestion-options")))
        id.performTextReplacement("not-in-provider-list")
        ui.settle()
        candidate.assertDoesNotExist()
        id.performTextReplacement("6.2")
        ui.settle()
        candidate.assertIsDisplayed()
        ui.capture("07-register-model-filtered", includeExtraRoots = true)
        candidate.performTouchInput { click() }
        ui.settle()
        candidate.assertDoesNotExist()
        id.assertTextContains("gpt-6.2-sol")
        field("mary-settings-model-name").assertTextContains("GPT 6.2 Sol")
        editorAction(R.string.mary_phase_apply_model, "mary-settings-model-editor")
        val addedModel = compose.onNode(hasText("GPT 6.2 Sol") and hasClickAction() and hasAnyAncestor(hasTestTag("mary-settings-models")))
        addedModel.assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        deleteAction(R.string.mary_phase_remove_model, R.string.mary_phase_apply_model)
            .assertIsEnabled().performTouchInput { click() }
        ui.settle()
        addedModel.assertDoesNotExist()

        selectTab(R.string.mary_phase_tab_providers, "mary-settings-providers")
        ui.node("mary-settings-provider-add").performTouchInput { click() }
        ui.settle()
        ui.node("mary-settings-provider-editor").assertIsDisplayed()
        compose.onNodeWithContentDescription(ui.label(R.string.mary_phase_remove_provider)).assertDoesNotExist()
        selectSheetOption("mary-settings-provider-protocol", ui.label(R.string.mary_protocol_responses))
        field("mary-settings-provider-name").performTextReplacement("Offline fixture")
        field("mary-settings-provider-address").performTextReplacement("https://fixture.example/v1")
        ui.settle()
        ui.capture("08-register-provider", includeExtraRoots = true)
        editorAction(R.string.mary_phase_apply_provider, "mary-settings-provider-editor")
        val addedProvider = compose.onNode(hasText("Offline fixture") and hasClickAction() and hasAnyAncestor(hasTestTag("mary-settings-providers")))
        addedProvider.assertIsDisplayed().assertTextContains(ui.label(R.string.mary_protocol_responses)).performTouchInput { click() }
        ui.settle()
        val removeProvider = deleteAction(R.string.mary_phase_remove_provider, R.string.mary_phase_apply_provider).assertIsEnabled()
        ui.capture("09-provider-header-delete", includeExtraRoots = true)
        removeProvider.performTouchInput { click() }
        ui.settle()
        addedProvider.assertDoesNotExist()
    }

    private fun deleteAction(label: Int, saveLabel: Int): SemanticsNodeInteraction {
        val remove = compose.onNodeWithContentDescription(ui.label(label)).assertIsDisplayed()
        val save = compose.onNodeWithContentDescription(ui.label(saveLabel)).assertIsDisplayed()
        val removeBounds = remove.getUnclippedBoundsInRoot()
        val saveBounds = save.getUnclippedBoundsInRoot()
        assertTrue("Delete must be to the left of Save", removeBounds.right <= saveBounds.left)
        assertEquals("Delete and Save must share the same header row",
            (removeBounds.top.value + removeBounds.bottom.value) / 2f,
            (saveBounds.top.value + saveBounds.bottom.value) / 2f, 1f)
        return remove
    }

    private fun tab(label: Int) = compose.onNode(
        hasText(ui.label(label)) and hasClickAction() and hasAnyAncestor(hasTestTag("mary-settings-tabs")),
    )

    private fun selectTab(label: Int, page: String) {
        tab(label).assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        tab(label).assertIsSelected()
        ui.node(page).assertIsDisplayed()
    }

    private fun selectSheetOption(tag: String, option: String) {
        val anchor = ui.node(tag).performScrollTo().assertIsDisplayed()
        anchor.performTouchInput { click() }
        ui.settle()
        compose.onNode(hasText(option) and hasClickAction() and !hasTestTag(tag))
            .assertIsDisplayed().performTouchInput { click() }
        ui.settle()
        compose.onNode(hasTestTag(tag) and (hasText(option) or hasAnyDescendant(hasText(option))), useUnmergedTree = true)
            .assertIsDisplayed()
    }

    private fun editorAction(label: Int, sheet: String) {
        compose.onNodeWithContentDescription(ui.label(label)).assertIsDisplayed().performClick()
        ui.settle()
        ui.node(sheet).assertDoesNotExist()
    }

    private fun field(tag: String) = compose.onNode(
        hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true,
    )
}
