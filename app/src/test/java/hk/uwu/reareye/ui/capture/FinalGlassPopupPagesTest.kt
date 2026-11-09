package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.MaryApprovalModePreference
import hk.uwu.reareye.ui.components.MaryComposer
import hk.uwu.reareye.ui.components.config.AppListSelectorScreen
import hk.uwu.reareye.ui.components.config.RearCardCreateDialog
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.PrefsManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Production pages/composables, with only inert preference and installed-package fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinalGlassPopupPagesTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun tagged(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun text(value: String) = compose.onNodeWithText(value)
    private fun option(id: Int) = compose.onNode(hasText(label(id)) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected))
    private fun open(backend: MixUiBackend, dark: Boolean = false,
                     setup: (ComponentActivity) -> Unit = {}, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            setup(activity!!.get())
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light, content = content)
            }
        }
        frames()
        compose.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 48))
                    .setVisible(WindowInsetsCompat.Type.statusBars(), true)
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true).build())
        }
        frames()
    }
    private fun awaitText(value: String) {
        compose.waitUntil(10_000) {
            // IO catalog loading resumes into frame-clock delays; tick while waiting.
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        frames()
    }
    private fun awaitAbsent(value: String) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }
    private fun back() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        frames()
    }
    @Suppress("DEPRECATION")
    private fun dialogBack() {
        compose.runOnUiThread { requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
        frames()
    }
    private fun outside() {
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots[roots.fetchSemanticsNodes().lastIndex].performTouchInput { click(Offset(4f, 4f)) }
        frames()
    }
    private fun capture(name: String) {
        val output = System.getProperty("ui.capture.output") ?: return
        val folder = File(output, "final-glass-popup-pages").apply { mkdirs() }
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
    private fun installApps(host: ComponentActivity, longList: Boolean = false) {
        val packages = listOf(
            Triple("z.fixture.alpha", "Alpha fixture", false),
            Triple("a.fixture.zulu", "Zulu fixture", false),
            Triple("m.fixture.system", "System fixture", true),
        ) + if (longList) List(40) { Triple("fixture.extra$it", "Extra fixture %02d".format(it), false) } else emptyList()
        packages.forEach { (pkg, title, system) ->
            shadowOf(host.packageManager).installPackage(PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    enabled = true
                    nonLocalizedLabel = title
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                }
            })
        }
    }
    private fun toolbar(id: Int) {
        compose.onNodeWithContentDescription(label(id)).performClick()
        frames()
    }
    private fun assertBefore(first: String, second: String) {
        assertTrue("$first must precede $second", text(first).fetchSemanticsNode().boundsInRoot.top <
            text(second).fetchSemanticsNode().boundsInRoot.top)
    }

    @Test(timeout = 60_000) fun actualAppPageSortFilterClearSaveSearchAndDismissalStayConnected() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            val prefs = PrefsManager(GuideMemoryPrefs())
            val config = ConfigItem("fixture.app.selection", R.string.search_apps, type = ConfigType.AppList())
            var saves = 0
            var cancels = 0
            open(backend, dark, setup = { installApps(it) }) {
                AppListSelectorScreen(config, prefs, onCancel = { cancels++ }, onSave = { saves++ })
            }
            awaitText("Alpha fixture")
            assertBefore("Alpha fixture", "Zulu fixture")
            val sortButton = compose.onNodeWithContentDescription(label(R.string.app_list_sort))
            assertTrue(sortButton.fetchSemanticsNode().boundsInRoot.top >= 24f)
            toolbar(R.string.app_list_sort)
            option(R.string.app_list_sort_by_name).assertIsSelected()
            option(R.string.app_list_sort_reverse).assertIsNotSelected()
            capture("${backend.name}-$dark-app-sort")
            back()
            option(R.string.app_list_sort_by_name).assertDoesNotExist()
            assertEquals(0, cancels)
            toolbar(R.string.app_list_sort)
            option(R.string.app_list_sort_by_package).performClick()
            frames()
            option(R.string.app_list_sort_by_package).assertDoesNotExist()
            assertBefore("Zulu fixture", "Alpha fixture")
            toolbar(R.string.app_list_sort)
            option(R.string.app_list_sort_by_package).assertIsSelected()
            option(R.string.app_list_sort_reverse).performClick()
            frames()
            assertBefore("Alpha fixture", "Zulu fixture")
            toolbar(R.string.app_list_filter)
            option(R.string.show_system_apps).assertIsNotSelected().performClick()
            frames()
            text("System fixture").assertIsDisplayed()
            toolbar(R.string.app_list_filter)
            option(R.string.show_system_apps).assertIsSelected()
            capture("${backend.name}-$dark-app-filter")
            outside()
            option(R.string.show_system_apps).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).performTextReplacement("Alpha fixture")
            frames()
            awaitAbsent("Zulu fixture")
            text("Zulu fixture").assertDoesNotExist()
            compose.onNode(hasClickAction() and hasAnyDescendant(hasText("Alpha fixture")), useUnmergedTree = true)
                .performClick()
            tagged("app-list-save").performClick()
            assertEquals(setOf("z.fixture.alpha"), prefs.getStringSet(config.key))
            assertEquals(1, saves)
            toolbar(R.string.app_list_more_actions)
            text(label(R.string.selection_clear)).performClick()
            frames()
            tagged("app-list-save").performClick()
            assertEquals(emptySet<String>(), prefs.getStringSet(config.key))
            assertEquals(2, saves)
            compose.onNode(hasSetTextAction()).performTextReplacement("missing fixture")
            frames()
            text(label(R.string.app_list_empty_result)).assertIsDisplayed()
            tagged("app-list-back").performClick()
            assertEquals(1, cancels)
        }
    }

    @Test(timeout = 60_000) fun actualAppPageLongListClearsSaveButtonAndBottomSystemInset() {
        for (backend in MixUiBackend.entries) {
            val prefs = PrefsManager(GuideMemoryPrefs())
            open(backend, setup = { installApps(it, longList = true) }) {
                AppListSelectorScreen(ConfigItem("fixture.long", R.string.search_apps, type = ConfigType.AppList()),
                    prefs, onCancel = {}, onSave = {})
            }
            awaitText("Alpha fixture")
            tagged("app-list-results").performScrollToKey("a.fixture.zulu")
            tagged("app-list-results").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10000f) }
            frames()
            text("a.fixture.zulu").assertIsDisplayed()
            assertTrue(text("a.fixture.zulu").fetchSemanticsNode().boundsInRoot.bottom <
                tagged("app-list-save").fetchSemanticsNode().boundsInRoot.top)
            capture("${backend.name}-app-bottom")
        }
    }

    @Test(timeout = 60_000) fun actualMaryComposerAndSettingsShareCompleteApprovalMenu() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            val mode = mutableStateOf(ApprovalMode.ASK)
            val locked = mutableStateOf(false)
            val events = mutableListOf<ApprovalMode>()
            open(backend, dark) {
                GlassPreferenceScene {
                    Box(Modifier.fillMaxSize().padding(top = 40.dp)) {
                        MaryApprovalModePreference(mode.value, { mode.value = it; events += it }, enabled = !locked.value)
                        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 80.dp)) {
                            MaryComposer("draft", {}, running = false, locked = locked.value, configuring = false,
                                provider = ProviderConfig(model = "fixture"), onReasoningSelected = {},
                                onSend = {}, onStop = {}, approvalMode = mode.value,
                                onApprovalModeSelected = { mode.value = it; events += it })
                        }
                    }
                }
            }
            ApprovalMode.entries.forEach { next ->
                tagged("mary-approval-mode").performClick()
                frames()
                tagged("mary-approval-option-${mode.value.name}").assertIsSelected()
                ApprovalMode.entries.forEach { entry ->
                    tagged("mary-approval-option-icon-${entry.name}").assertIsDisplayed()
                    val title = tagged("mary-approval-option-title-${entry.name}")
                    val summary = tagged("mary-approval-option-summary-${entry.name}")
                    title.assertIsDisplayed(); summary.assertIsDisplayed()
                    assertTrue(title.fetchSemanticsNode().boundsInRoot.bottom <= summary.fetchSemanticsNode().boundsInRoot.top)
                }
                capture("${backend.name}-$dark-approval-${next.name}")
                tagged("mary-approval-option-${next.name}").performClick()
                frames()
                assertEquals(next, mode.value)
                tagged("mary-approval-options").assertDoesNotExist()
                tagged("mary-approval-current-${next.name}").assertIsDisplayed()
            }
            tagged("mary-settings-approval-mode").performClick(); frames()
            tagged("mary-approval-option-ALLOW_ALL").assertIsSelected()
            back()
            tagged("mary-approval-options").assertDoesNotExist()
            tagged("mary-settings-approval-mode").performClick(); frames()
            tagged("mary-approval-option-ASK").performClick(); frames()
            assertEquals(ApprovalMode.ASK, mode.value)
            tagged("mary-approval-mode").performClick(); frames()
            outside()
            tagged("mary-approval-options").assertDoesNotExist()
            tagged("mary-settings-approval-mode").performClick(); frames()
            compose.runOnUiThread { locked.value = true }; frames()
            tagged("mary-approval-options").assertDoesNotExist()
            tagged("mary-settings-approval-mode").assertIsNotEnabled()
            compose.runOnUiThread { locked.value = false }; frames()
            tagged("mary-approval-options").assertDoesNotExist()
            assertEquals(ApprovalMode.entries.toList() + ApprovalMode.ASK, events)
        }
    }

}
