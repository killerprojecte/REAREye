package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.theme.MixTheme
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.presetpack.PresetPackContract
import hk.uwu.reareye.repository.presetpack.PresetPackRelease
import hk.uwu.reareye.service.PresetPackDownloadPhase
import hk.uwu.reareye.service.PresetPackDownloadService
import hk.uwu.reareye.service.PresetPackDownloadState
import hk.uwu.reareye.ui.components.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Robolectric's hardware PixelCopy can lag changing offscreen layers by one native draw even after
 * Compose is idle. Submit that draw, then invalidate the actual owner layers before the final copy.
 * Both passes use the same live window: no remount, state/scroll mutation or image reconstruction.
 */
internal fun primePresetWindowCapture(window: Window) {
    captureWindowBitmap(window).recycle()
    fun invalidate(view: View) {
        if (view is ViewRootForTest) view.invalidateDescendants()
        view.invalidate()
        if (view is ViewGroup) repeat(view.childCount) { invalidate(view.getChildAt(it)) }
    }
    invalidate(window.decorView)
}

/** Component fixture: callbacks only record actions; no repository, network, launcher or service mutation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PresetPackSheetPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var ui by mutableStateOf(PresetPackSheetState(
        localStatus = PresetPackLocalStatus.READY,
        current = PresetPackDetails("1.0-fixture", 2048, 2, "2000-01-01T00:00:00Z", "fixture-sha-must-never-be-visible"),
        latest = PresetPackRelease("2-fixture", "v2", "https://example.invalid/unused.rpp", "unused.rpp", 1024, "https://example.invalid/release"),
    ))
    private val actions = mutableListOf<PresetPackAction>()
    private var dismisses = 0
    private var pageBacks = 0

    @Before fun requireCapture() {
        assertTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun open(backend: MixUiBackend, dark: Boolean) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    Scaffold {
                        GlassBottomSheetScene {
                            BackHandler { pageBacks++ }
                            Box(Modifier.fillMaxSize().background(MixTheme.colorScheme.surface)) { Text("Component fixture · preset pack store") }
                            PresetPackSheet(visible, ui,
                                onDismissRequest = { dismisses++; visible = false },
                                onAction = { actions += it })
                        }
                    }
                }
            }
        }
        settle()
    }
    private fun tagged(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun reveal(node: SemanticsNodeInteraction) {
        compose.mainClock.autoAdvance = true
        node.performScrollTo()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
    }
    private fun selectTab(label: String) {
        compose.onNodeWithText(label).performClick()
        settle()
    }
    private fun capture(backend: MixUiBackend, dark: Boolean, name: String, height: Int = 480) {
        val root = File(System.getProperty("ui.capture.output"), "preset-pack-store-redesign").apply { mkdirs() }
        // Compose idle does not submit the native offscreen layers needed by PixelCopy.
        compose.runOnUiThread { primePresetWindowCapture(activity!!.get().window) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(root, "component-fixture-${backend.name.lowercase()}-${if (dark) "dark" else "light"}-400x${height}dp-$name.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun setDownload(download: PresetPackDownloadState) {
        compose.runOnUiThread { ui = ui.copy(download = download) }
        settle()
        // Terminal states need not keep a progress ring. The resource row remains the scroll target.
        reveal(tagged("preset-pack-release-row"))
    }
    private fun assertProgress(expected: Float?) {
        val rangeNodes = compose.onAllNodes(
            (hasTestTag("preset-pack-progress") or hasAnyAncestor(hasTestTag("preset-pack-progress"))) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("The active download capsule exposes progress semantics", rangeNodes.isNotEmpty())
        rangeNodes.forEach { node ->
            val info = node.config[SemanticsProperties.ProgressBarRangeInfo]
            if (expected == null) assertEquals(ProgressBarRangeInfo.Indeterminate, info)
            else assertEquals(expected, info.current, 0.0001f)
        }
        assertTrue("The compact capsule is at most 48dp high at mdpi",
            tagged("preset-pack-progress").fetchSemanticsNode().boundsInRoot.height <= 48f)
        assertTrue("The capsule keeps Rear Store's 16dp ring", rangeNodes.any {
            it.boundsInRoot.width in 15f..17f && it.boundsInRoot.height in 15f..17f
        })
    }
    private fun headerBounds(): Map<String, Rect> = listOf(
        "preset-pack-current", "preset-pack-tabs", "preset-pack-uninstall", "preset-pack-close",
    ).associateWith { tagged(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot }

    private fun assertFixedHeaderAfterScroll() {
        val before = headerBounds()
        val title = activity!!.get().getString(R.string.preset_pack_title)
        val titleBefore = compose.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val body = tagged("preset-pack-body")
        val range = body.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        compose.mainClock.autoAdvance = true
        body.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, range.maxValue()) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        assertEquals("Current version, tabs and header actions must stay fixed", before, headerBounds())
        assertEquals(titleBefore, compose.onNodeWithText(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onAllNodes(hasScrollAction(), useUnmergedTree = true).assertCountEquals(1)
    }
    private fun assertNoMaintenanceOrSha() {
        listOf("SHA", "fixture-sha-must-never-be-visible", "查看校验详情", "收起校验详情", "重新校验", "维护").forEach {
            compose.onAllNodesWithText(it, substring = true, useUnmergedTree = true).assertCountEquals(0)
        }
    }
    private fun back() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    private fun exercise(backend: MixUiBackend, dark: Boolean) {
        val serviceBefore = PresetPackDownloadService.state.value
        open(backend, dark)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        tagged("preset-pack-check").assertContentDescriptionEquals("检查更新")
        tagged("preset-pack-download").assertExists()
        compose.onNodeWithText("手动导入").assertDoesNotExist()
        compose.onNodeWithText("从网盘获取").assertDoesNotExist()
        assertNoMaintenanceOrSha()
        capture(backend, dark, "cloud")
        selectTab("本地")
        compose.onNodeWithText("手动导入").assertExists()
        compose.onNodeWithText("从网盘获取").assertExists()
        compose.onNodeWithText("下载后需手动导入").assertExists()
        tagged("preset-pack-release-row").assertDoesNotExist()
        tagged("preset-pack-check").assertDoesNotExist()
        capture(backend, dark, "local")
        selectTab("云端")
        assertTrue("Tabs must never dispatch business actions", actions.isEmpty())

        listOf(0L to 0, 512L to 50).forEach { (done, percent) ->
            setDownload(PresetPackDownloadState(running = true, downloaded = done, total = 1024))
            assertProgress(percent / 100f)
            compose.onNodeWithText("$percent%").assertExists()
            compose.onNodeWithText("下载完成 · 待应用").assertDoesNotExist()
            capture(backend, dark, "cloud-progress-$percent")
        }
        selectTab("本地")
        tagged("preset-pack-progress").assertDoesNotExist()
        assertTrue(ui.download.running)
        capture(backend, dark, "local-background-download")
        selectTab("云端")
        reveal(tagged("preset-pack-release-row"))
        compose.onNodeWithText("50%").assertExists()
        assertProgress(0.5f)
        assertTrue(actions.isEmpty())

        setDownload(PresetPackDownloadState(running = true, downloaded = 512, total = -1))
        assertProgress(null)
        listOf("0%", "50%", "100%").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        capture(backend, dark, "cloud-connecting")
        // Receiving all bytes is still processing, not a completed/100% visual.
        setDownload(PresetPackDownloadState(running = true, downloaded = 1024, total = 1024,
            phase = PresetPackDownloadPhase.PROCESSING))
        assertProgress(null)
        compose.onNodeWithText("100%").assertDoesNotExist()
        compose.onNodeWithText("下载完成 · 待应用").assertDoesNotExist()
        capture(backend, dark, "cloud-processing")

        setDownload(PresetPackDownloadState(running = true, downloaded = 512, total = 1024))
        tagged("preset-pack-close").assertIsEnabled().performClick()
        settle()
        assertEquals(1, dismisses)
        assertTrue(ui.download.running)
        assertTrue(actions.isEmpty())
        compose.runOnUiThread { visible = true }
        settle()
        reveal(tagged("preset-pack-release-row"))
        compose.onNodeWithText("50%").assertExists()
        assertProgress(0.5f)
        capture(backend, dark, "cloud-running-reopened")
        back()
        assertEquals(2, dismisses)
        assertEquals(0, pageBacks)
        compose.runOnUiThread { visible = true }
        settle()

        listOf(PresetPackDownloadPhase.PAUSED to "继续下载", PresetPackDownloadPhase.FAILED to "重试下载").forEach { (phase, text) ->
            setDownload(PresetPackDownloadState(phase = phase, downloaded = 512, total = 1024))
            tagged("preset-pack-progress").assertDoesNotExist()
            tagged("preset-pack-download").assertIsEnabled()
            compose.onNodeWithText(text).assertExists()
            capture(backend, dark, "cloud-${phase.name.lowercase()}")
        }
        compose.runOnUiThread { ui = ui.copy(staged = presetPresentationManifest(),
            download = PresetPackDownloadState(phase = PresetPackDownloadPhase.COMPLETED),
            notice = PresetPackNotice("Fixture success", PresetPackNoticeSeverity.SUCCESS)) }
        settle()
        tagged("preset-pack-notice-success").assertExists()
        tagged("preset-pack-notice-error").assertDoesNotExist()
        listOf("云端", "本地").forEach { tab ->
            selectTab(tab)
            reveal(tagged("preset-pack-apply"))
            tagged("preset-pack-apply").assertIsEnabled()
            compose.onNodeWithText("应用").assertExists()
            compose.onNodeWithText("覆盖当前版本", substring = true).assertExists()
            capture(backend, dark, "${if (tab == "云端") "cloud" else "local"}-staged")
        }
        assertFixedHeaderAfterScroll()
        PresetPackOperation.entries.forEach { operation ->
            compose.runOnUiThread { ui = ui.copy(operation = operation) }
            settle()
            tagged("preset-pack-close").assertIsNotEnabled()
            tagged("preset-pack-uninstall").assertIsNotEnabled()
            tagged("preset-pack-apply").assertIsNotEnabled()
            back()
            assertEquals(2, dismisses)
        }
        assertTrue(actions.isEmpty())
        assertEquals("Component fixture must not touch the real service", serviceBefore, PresetPackDownloadService.state.value)
    }

    @Test fun miuixLightProgressAndDismiss() = exercise(MixUiBackend.Miuix, false)
    @Test fun miuixDarkProgressAndDismiss() = exercise(MixUiBackend.Miuix, true)
    @Test fun mochiLightProgressAndDismiss() = exercise(MixUiBackend.Mochi, false)
    @Test fun mochiDarkProgressAndDismiss() = exercise(MixUiBackend.Mochi, true)

    @Test fun displayCallbacksDoNotPerformBusinessSideEffects() {
        val before = PresetPackDownloadService.state.value
        compose.runOnUiThread { ui = ui.copy(staged = presetPresentationManifest()) }
        open(MixUiBackend.Miuix, false)
        listOf("preset-pack-check" to PresetPackAction.CHECK,
            "preset-pack-download" to PresetPackAction.DOWNLOAD,
            "preset-pack-apply" to PresetPackAction.APPLY).forEach { (tag, expected) ->
            val node = tagged(tag)
            reveal(node)
            node.performClick()
            assertEquals(expected, actions.last())
        }
        selectTab("本地")
        listOf("preset-pack-cloud-drive" to PresetPackAction.CLOUD, "preset-pack-import" to PresetPackAction.IMPORT).forEach { (tag, expected) ->
            val node = tagged(tag)
            reveal(node)
            node.performClick()
            assertEquals(expected, actions.last())
        }
        selectTab("云端")
        setDownload(PresetPackDownloadState(running = true, downloaded = 512, total = 1024))
        val pauseText = activity!!.get().getString(R.string.preset_sheet_pause)
        val pause = compose.onNodeWithContentDescription(pauseText)
        reveal(pause)
        pause.performClick()
        assertEquals(PresetPackAction.PAUSE, actions.last())
        assertEquals(6, actions.size)
        assertEquals(before, PresetPackDownloadService.state.value)
    }

    @Test fun uninstallIsLeftHeaderActionWithOneCallbackAndStateGating() {
        open(MixUiBackend.Miuix, false)
        val remove = tagged("preset-pack-uninstall")
        remove.assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("preset-pack-uninstall").assertContentDescriptionEquals("卸载")
        val removeBounds = remove.fetchSemanticsNode().boundsInRoot
        val closeBounds = tagged("preset-pack-close").fetchSemanticsNode().boundsInRoot
        val bodyBounds = tagged("preset-pack-body").fetchSemanticsNode().boundsInRoot
        assertTrue("Uninstall is the left action", removeBounds.right < closeBounds.left)
        assertTrue("Uninstall belongs to the fixed topbar, not the scrolling body", removeBounds.bottom <= bodyBounds.top)
        remove.performClick()
        assertEquals(listOf(PresetPackAction.REMOVE), actions)
        assertEquals(0, dismisses)
        listOf(
            ui.copy(localStatus = PresetPackLocalStatus.NOT_INSTALLED),
            ui.copy(localStatus = PresetPackLocalStatus.LOADING),
            ui.copy(operation = PresetPackOperation.CHECK),
            ui.copy(download = PresetPackDownloadState(running = true)),
        ).forEach { disabled ->
            compose.runOnUiThread { ui = disabled }
            settle()
            remove.assertIsNotEnabled().performClick()
            assertEquals("Disabled uninstall must not dispatch", listOf(PresetPackAction.REMOVE), actions)
        }
        compose.runOnUiThread { ui = ui.copy(localStatus = PresetPackLocalStatus.INVALID,
            operation = null, download = PresetPackDownloadState()) }
        settle()
        remove.assertIsEnabled()
    }

    @Test fun emptyCloudKeepsCheckEntryWithoutStartingNetwork() {
        val before = PresetPackDownloadService.state.value
        compose.runOnUiThread { ui = ui.copy(latest = null) }
        open(MixUiBackend.Miuix, false)
        tagged("preset-pack-check").assertExists().assertIsEnabled()
        compose.onNodeWithText("GitHub").assertExists()
        tagged("preset-pack-release-row").assertDoesNotExist()
        tagged("preset-pack-download").assertDoesNotExist()
        assertTrue(actions.isEmpty())
        assertEquals(before, PresetPackDownloadService.state.value)
    }

    @Test fun tabsHideHashesAndDoNotDispatchAnyAction() {
        open(MixUiBackend.Mochi, true)
        repeat(2) {
            selectTab("本地")
            assertNoMaintenanceOrSha()
            tagged("preset-pack-check").assertDoesNotExist()
            tagged("preset-pack-download").assertDoesNotExist()
            compose.onNodeWithText("手动导入").assertExists()
            selectTab("云端")
            assertNoMaintenanceOrSha()
            compose.onNodeWithText("手动导入").assertDoesNotExist()
            compose.onNodeWithText("从网盘获取").assertDoesNotExist()
            tagged("preset-pack-download").assertExists()
        }
        assertTrue(actions.isEmpty())
        assertEquals(0, dismisses)
    }

    private fun layoutReference(backend: MixUiBackend, dark: Boolean, height: Int, longVersion: Boolean) {
        compose.runOnUiThread {
            val version = if (longVersion) "fixture-version-" + (1..8).joinToString("-") { "long-metadata-$it" } else "2.0-fixture"
            ui = ui.copy(current = ui.current!!.copy(version = version),
                latest = ui.latest!!.copy(version = version, assetName = "rear-preset-resource-very-long-file-name-fixture.rpp"),
                staged = presetPresentationManifest().copy(packVersion = version),
                download = if (longVersion) PresetPackDownloadState(running = true, downloaded = 512, total = 1024)
                    else PresetPackDownloadState())
        }
        open(backend, dark)
        headerBounds()
        if (!longVersion) {
            capture(backend, dark, "cloud-full-design", height)
            selectTab("本地")
            capture(backend, dark, "local-full-design", height)
            selectTab("云端")
            setDownload(PresetPackDownloadState(running = true, downloaded = 0, total = 1024))
            capture(backend, dark, "cloud-full-design-progress-0", height)
            setDownload(PresetPackDownloadState(running = true, downloaded = 512, total = 1024))
        }
        capture(backend, dark, if (longVersion) "cloud-long-version" else "cloud-full-design-progress-50", height)
        reveal(tagged("preset-pack-release-row"))
        assertProgress(0.5f)
        assertFixedHeaderAfterScroll()
        capture(backend, dark, if (longVersion) "cloud-long-version-scrolled" else "cloud-full-design-scrolled", height)
        selectTab("本地")
        tagged("preset-pack-apply").assertExists()
        tagged("preset-pack-progress").assertDoesNotExist()
        headerBounds()
        capture(backend, dark, if (longVersion) "local-long-version" else "local-full-design-background-download", height)
        assertFixedHeaderAfterScroll()
        assertTrue(actions.isEmpty())
    }
    @Test fun miuixLightLongVersionShort() = layoutReference(MixUiBackend.Miuix, false, 480, true)
    @Test fun miuixDarkLongVersionShort() = layoutReference(MixUiBackend.Miuix, true, 480, true)
    @Test fun mochiLightLongVersionShort() = layoutReference(MixUiBackend.Mochi, false, 480, true)
    @Test fun mochiDarkLongVersionShort() = layoutReference(MixUiBackend.Mochi, true, 480, true)

    @Test @Config(qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
    fun miuixLightFullHeight() = layoutReference(MixUiBackend.Miuix, false, 800, false)
    @Test @Config(qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
    fun miuixDarkFullHeight() = layoutReference(MixUiBackend.Miuix, true, 800, false)
    @Test @Config(qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
    fun mochiLightFullHeight() = layoutReference(MixUiBackend.Mochi, false, 800, false)
    @Test @Config(qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
    fun mochiDarkFullHeight() = layoutReference(MixUiBackend.Mochi, true, 800, false)

    @Test fun cloudActionPreservesEntireFragmentWithoutOpeningAnything() {
        val intent = presetPackCloudIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(PresetPackContract.CLOUD_SHARE_URL, intent.dataString)
        assertEquals("/list/share/799faa52857c449a85c30257c30944be", intent.data!!.fragment)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        var captured: Intent? = null
        val capturingContext = object : android.content.ContextWrapper(null) {
            override fun startActivity(value: Intent) { captured = value }
        }
        assertTrue(openPresetPackCloud(capturingContext))
        assertEquals(PresetPackContract.CLOUD_SHARE_URL, captured!!.dataString)
        val unavailableContext = object : android.content.ContextWrapper(null) {
            override fun startActivity(value: Intent) { throw android.content.ActivityNotFoundException("fixture only") }
        }
        assertFalse(openPresetPackCloud(unavailableContext))
        val deniedContext = object : android.content.ContextWrapper(null) {
            override fun startActivity(value: Intent) { throw SecurityException("fixture only") }
        }
        assertFalse(openPresetPackCloud(deniedContext))
    }
}
