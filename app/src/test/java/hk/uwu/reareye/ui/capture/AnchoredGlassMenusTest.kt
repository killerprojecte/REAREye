package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassAnchoredMenu
import hk.uwu.mixui.components.GlassMenuOption
import hk.uwu.mixui.components.IconButton
import hk.uwu.mixui.components.LargeTitleColumnScaffold
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ProviderPreset
import hk.uwu.reareye.ui.components.ModelConfigurationCard
import hk.uwu.reareye.ui.components.ModelDraft
import hk.uwu.reareye.ui.screen.MaryProviderPresetMenu
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

private val LocalMenuMarker = staticCompositionLocalOf { "missing" }

/** Native popup behavior plus production Mary's preset/model controls with inert data only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AnchoredGlassMenusTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun open(backend: MixUiBackend, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light, content = content)
            }
        }
        frames()
    }
    private fun back() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        frames()
    }
    private fun capture(name: String) {
        val output = System.getProperty("ui.capture.output") ?: return
        val folder = File(output, "anchored-glass-menus").apply { mkdirs() }
        val windowBitmap = compose.runOnUiThread {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            if (dialog?.isShowing == true) captureDialogWindowBitmap(dialog)
            else captureWindowBitmap(activity!!.get().window)
        }
        File(folder, "$name-window.png").outputStream().use {
            check(windowBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots.fetchSemanticsNodes().indices.forEach { index ->
            val bitmap = roots[index].captureToImage().asAndroidBitmap()
            File(folder, "$name-root$index.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    @Test(timeout = 60_000) fun anchoredMenuRetainsLongDescriptionsDisabledRowsAndSelectionCallbacks() {
        val detail = "第一行说明\n第二行说明\n第三行说明\n第四行说明"
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            val events = mutableListOf<String>()
            var seenLocal: String? = null
            open(backend, dark) {
                var expanded by remember { mutableStateOf(false) }
                var selected by remember { mutableStateOf(true) }
                // Nonzero host origin catches root-vs-host anchoring errors.
                LargeTitleColumnScaffold(title = "Menu fixture", modifier = Modifier.padding(top = 36.dp),
                    actions = {
                        CompositionLocalProvider(LocalMenuMarker provides "caller-local") {
                        GlassAnchoredMenu(
                            expanded, { expanded = it; if (!it) events += "dismiss" },
                            options = listOf(
                                GlassMenuOption("Disabled", enabled = false, icon = { modifier ->
                                    val marker = LocalMenuMarker.current
                                    SideEffect { seenLocal = marker }
                                    Spacer(modifier)
                                }),
                                GlassMenuOption("Independent", summary = detail, selected = selected, dismissOnClick = false),
                                GlassMenuOption("Run action", dividerBefore = true),
                            ),
                            onOptionClick = { index -> events += "select:$index"; if (index == 1) selected = !selected },
                        ) {
                            IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("open-menu")) { Text("…") }
                        }
                        }
                    },
                ) { Text("Actual page backdrop") }
            }
            compose.onNodeWithTag("open-menu").performClick(); frames()
            compose.onNodeWithText("Independent").assertIsSelected()
            compose.onNodeWithText(detail, useUnmergedTree = true).assertIsDisplayed()
            assertEquals("The relocated popup must keep caller composition locals", "caller-local", seenLocal)
            if (backend == MixUiBackend.Miuix) {
                val anchorTop = compose.onNodeWithTag("open-menu").fetchSemanticsNode().boundsInRoot.top
                val firstTop = compose.onNodeWithText("Disabled").fetchSemanticsNode().boundsInRoot.top
                assertTrue("The 36dp host origin must not be applied twice: $anchorTop / $firstTop",
                    firstTop >= anchorTop - 2f && firstTop <= anchorTop + 30f)
            }
            compose.onNodeWithText("Disabled").assertIsNotEnabled().performTouchInput { click() }
            assertTrue(events.isEmpty())
            capture("${backend.name}-$dark-rich-options")
            compose.onNodeWithText("Independent").performClick(); frames()
            compose.onNodeWithText("Independent").assertIsNotSelected()
            assertEquals(listOf("select:1"), events)
            compose.onNodeWithText("Run action").performClick(); frames()
            assertEquals(listOf("select:1", "dismiss", "select:2"), events)
            compose.onNodeWithText("Run action").assertDoesNotExist()
            compose.onNodeWithTag("open-menu").performClick(); frames()
            back()
            compose.onNodeWithText("Run action").assertDoesNotExist()
            assertEquals(2, events.count { it.startsWith("select:") })
        }
    }

    @Test(timeout = 60_000) fun disablingAnOpenAnchorClosesWithoutRunningAnAction() {
        for (backend in MixUiBackend.entries) {
            val enabled = mutableStateOf(true)
            var calls = 0
            open(backend) {
                var expanded by remember { mutableStateOf(false) }
                LargeTitleColumnScaffold(title = "State change", actions = {
                    GlassAnchoredMenu(expanded, { expanded = it },
                        options = listOf(GlassMenuOption("Only action")),
                        onOptionClick = { calls++ }, enabled = enabled.value) {
                        IconButton(onClick = { expanded = true }, enabled = enabled.value,
                            modifier = Modifier.testTag("conditional-anchor")) { Text("…") }
                    }
                }) { Text("Page") }
            }
            compose.onNodeWithTag("conditional-anchor").performClick(); frames()
            compose.onNodeWithText("Only action").assertIsDisplayed()
            compose.runOnIdle { enabled.value = false }; frames()
            compose.onNodeWithText("Only action").assertDoesNotExist()
            compose.onNodeWithTag("conditional-anchor").assertIsNotEnabled().performTouchInput { click() }
            frames()
            compose.onNodeWithText("Only action").assertDoesNotExist()
            assertEquals(0, calls)
            compose.runOnIdle { enabled.value = true }; frames()
            compose.onNodeWithTag("conditional-anchor").performClick(); frames()
            compose.onNodeWithText("Only action").performClick(); frames()
            assertEquals(1, calls)
        }
    }

    @Test(timeout = 60_000) fun productionMaryPresetsKeepDialogueAndApprovalTargetsSeparate() {
        for (backend in MixUiBackend.entries) for (independent in listOf(false, true)) {
            val selections = mutableListOf<Pair<Boolean, ProviderPreset>>()
            open(backend) {
                var expanded by remember { mutableStateOf(false) }
                LargeTitleColumnScaffold(title = "Mary", actions = {
                    MaryProviderPresetMenu(expanded, { expanded = it }, enabled = true,
                        independentApproval = independent,
                        onPreset = { approval, preset -> selections += approval to preset })
                }) { Text("Production preset control") }
            }
            val targets = if (independent) listOf(false, true) else listOf(false)
            for (approval in targets) for (preset in ProviderPreset.entries) {
                compose.onNodeWithTag("mary-settings-presets").performClick(); frames()
                val targetLabel = label(if (approval) R.string.mary_settings_ui_approval_draft
                    else R.string.mary_settings_ui_dialogue_draft)
                val optionLabel = if (independent) activity!!.get().getString(
                    R.string.mary_settings_ui_preset_target, preset.label, targetLabel) else preset.label
                compose.onNodeWithText(optionLabel).performScrollTo(); frames()
                compose.onNodeWithText(optionLabel).assertIsDisplayed().performClick(); frames()
                compose.onNodeWithText(optionLabel).assertDoesNotExist()
                assertEquals(approval to preset, selections.last())
            }
            assertEquals(targets.size * ProviderPreset.entries.size, selections.size)
            compose.onNodeWithTag("mary-settings-presets").performClick(); frames()
            capture("${backend.name}-mary-presets-independent-$independent")
            back()
            assertEquals(targets.size * ProviderPreset.entries.size, selections.size)
        }
    }

    @Test(timeout = 60_000) fun productionMaryModelProtocolSelectorPreservesDraftAndDismisses() {
        for (backend in MixUiBackend.entries) {
            val original = ModelDraft(ProviderConfig(model = "offline-model", baseUrl = "https://example.invalid/v1"),
                temperature = "0.4", output = "1024", context = "64000")
            val draft = mutableStateOf(original)
            open(backend) {
                LargeTitleColumnScaffold(title = "Mary") {
                    ModelConfigurationCard(R.string.mary_settings_provider, draft.value, { draft.value = it }, false)
                }
            }
            compose.onNodeWithText(label(R.string.mary_settings_ui_protocol)).performClick(); frames()
            capture("${backend.name}-mary-protocol")
            compose.onNodeWithText(label(R.string.mary_protocol_anthropic)).performClick(); frames()
            assertEquals(Protocol.ANTHROPIC, draft.value.provider.protocol)
            assertEquals(original.provider.copy(protocol = Protocol.ANTHROPIC), draft.value.provider)
            assertEquals(original.temperature, draft.value.temperature)
            assertEquals(original.output, draft.value.output)
            assertEquals(original.context, draft.value.context)
            compose.onNodeWithText(label(R.string.mary_settings_ui_protocol)).performClick(); frames()
            back()
            assertEquals(Protocol.ANTHROPIC, draft.value.provider.protocol)
        }
    }
}
