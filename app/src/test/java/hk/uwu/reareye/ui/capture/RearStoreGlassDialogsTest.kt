package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.DialogActions
import hk.uwu.mixui.components.GlassFormDialog
import hk.uwu.mixui.components.GlassTopBarScaffold
import hk.uwu.mixui.components.TextField
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearstore.*
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperMetadataOptions
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.screen.RearStoreDetailContent
import hk.uwu.reareye.ui.screen.RearStoreDetailOperations
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast
import java.io.File

/** Actual production Store detail page, real dialog/form callbacks, inert local repository fixture.
 * Every repository effect is replaced, including release lookup, conflict lookup and preparation.
 * No network, device IPC, install/uninstall, permission grant, or real external browser is invoked.
 * WebView assertions cover the production handoff/settings only; Robolectric has no Chromium
 * rendering. JVM captures do not certify hardware glass optics, IME, or predictive-back gestures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RearStoreGlassDialogsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var fake: StoreFixture
    private var pageBacks = 0
    private var installedCallbacks = 0
    private var prefix = ""

    private class StoreFixture(type: String = "widget", postInstall: String? = null) {
        val asset = RearStoreReleaseAsset(name = "fixture.zip", size = 1024L)
        val release = RearStoreRelease(tagName = "v1", name = "Fixture release", assets = listOf(asset))
        var detail = RearStoreWidgetDetail(
            type = "card", widgetId = "fixture-store", name = "Fixture Store item",
            description = "Controlled local store item", author = RearStoreAuthor(name = "Fixture author"),
            repository = null,
            widgetInfo = RearStoreWidgetInfo(type = type, name = "Fixture Store item",
                businessSetup = RearStoreBusinessSetup(id = "fixture-business"),
                postInstall = postInstall?.let { RearStorePostInstall(it) }),
            metadata = null, readme = RearStoreReadme(), releases = listOf(release),
        )
        @Volatile var conflict: RearStoreInstallConflict? = null
        @Volatile var installCalls = 0
        @Volatile var prepareCalls = 0
        @Volatile var conflictCalls = 0
        @Volatile var releaseCalls = 0
        val uninstalls = mutableListOf<String>()
        var failInstall = false
        var failPrepare = false
        var failUninstall = false
        var prepareGate: CompletableDeferred<Unit>? = null
        var installGate: CompletableDeferred<Unit>? = null
        var lastOverwrite = false
        var lastPrepared: RearStorePreparedInstallAsset? = null
        var lastMetadata: RearWallpaperMetadataOptions? = null
        val prepared = RearStorePreparedInstallAsset(release, asset, byteArrayOf(1, 2, 3))
        val operations = RearStoreDetailOperations(
            loadInstallDetail = { _, supplied, tag ->
                check(supplied.widgetId == detail.widgetId && tag == release.tagName)
                releaseCalls++; detail
            },
            resolveInstallConflict = { _, supplied ->
                check(supplied.widgetId == detail.widgetId)
                conflictCalls++; conflict
            },
            prepareInstallAsset = { _, supplied, tag, name, progress ->
                check(supplied.widgetId == detail.widgetId && tag == release.tagName && name == asset.name)
                prepareCalls++
                progress(RearStoreInstallProgress(RearStoreInstallProgressStage.DOWNLOADING, 512L, 1024L))
                prepareGate?.await()
                if (failPrepare) error("fixture prepare failure")
                prepared
            },
            quickInstallWidget = { _, _, supplied, tag, name, overwrite, preparedAsset, options, progress ->
                check(supplied.widgetId == detail.widgetId && tag == release.tagName && name == asset.name)
                installCalls++; lastOverwrite = overwrite; lastPrepared = preparedAsset; lastMetadata = options
                progress(RearStoreInstallProgress(RearStoreInstallProgressStage.INSTALLING))
                installGate?.await()
                if (failInstall) error("fixture install failure")
                RearStoreQuickInstallResult(detail.widgetId, detail.name, tag, false, false, false)
            },
            uninstallWidget = { _, _, id ->
                uninstalls += id
                if (failUninstall) error("fixture uninstall failure")
                RearStoreUninstallResult(id, detail.name, 1, 0, 0, 0)
            },
        )
        fun installed() = RearStoreInstalledWidget(detail.widgetId, detail.name, "fixture-business",
            "v1", null, null, true)
        fun conflict(source: RearStoreInstallConflictSource) = RearStoreInstallConflict(
            "fixture-business", "Fixture business", source,
            if (source == RearStoreInstallConflictSource.OTHER_STORE_WIDGET) "other-widget" else null,
            if (source == RearStoreInstallConflictSource.OTHER_STORE_WIDGET) "Other fixture widget" else null,
        )
    }

    @Before fun captureOnly() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(20) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun await(condition: () -> Boolean) {
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); condition() }
        settle()
    }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun host(backend: MixUiBackend, dark: Boolean, installed: Boolean = false) {
        pageBacks = 0; installedCallbacks = 0
        prefix = "${backend.name.lowercase()}-${if (dark) "dark" else "light"}"
        ShadowToast.reset()
        val prefs = PrefsManager(GuideMemoryPrefs())
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    BackHandler { pageBacks++ }
                    RearStoreDetailContent(
                        widgetId = fake.detail.widgetId, prefsManager = prefs, bottomInnerPadding = 0.dp,
                        installedWidget = if (installed) fake.installed() else null,
                        onBack = { pageBacks++ }, onInstalled = { installedCallbacks++ },
                        loadDetail = { supplied, id, _ ->
                            check(supplied === prefs && id == fake.detail.widgetId); fake.detail
                        },
                        loadReadme = { _, _ -> error("Fixture embeds README; no lookup expected") },
                        operations = fake.operations,
                    )
                }
            }
        }
        await { compose.onAllNodesWithText(fake.detail.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("store-detail-list").assertIsDisplayed()
        assertEquals(0, fake.installCalls + fake.prepareCalls + fake.uninstalls.size)
    }
    private fun clickInstall() {
        compose.onNodeWithText(label(R.string.rear_store_tab_downloads)).performClick(); settle()
        compose.onNodeWithTag("store-detail-list").performScrollToNode(hasText(fake.asset.name)); settle()
        compose.onNodeWithText(label(R.string.rear_store_asset_install)).performClick(); settle()
    }
    private fun openUninstall() {
        compose.onNodeWithContentDescription(label(R.string.rear_store_uninstall_action)).performClick()
        settle(); shown("uninstall")
    }
    private fun shown(kind: String) {
        await { compose.onAllNodesWithTag("store-$kind-dialog").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("store-$kind-dialog").assertIsDisplayed()
    }
    private fun closed(kind: String) {
        settle(); compose.onNodeWithTag("store-$kind-dialog").assertDoesNotExist()
        compose.onNodeWithTag("store-detail-list").assertIsDisplayed()
    }
    private inline fun <T> traceStep(name: String, block: () -> T): T {
        System.err.println("STORE_STEP $prefix BEGIN $name")
        System.err.flush()
        return block().also {
            System.err.println("STORE_STEP $prefix END $name")
            System.err.flush()
        }
    }
    private fun SemanticsNodeInteraction.reveal(name: String): SemanticsNodeInteraction =
        traceStep(name) {
            val wasAutoAdvance = compose.mainClock.autoAdvance
            compose.mainClock.autoAdvance = true
            try { performScrollTo() } finally { compose.mainClock.autoAdvance = wasAutoAdvance }
        }
    private fun action(kind: String, id: Int): SemanticsNodeInteraction {
        compose.onNodeWithTag("store-$kind-actions").reveal("$kind.actions.scroll.$id")
        traceStep("$kind.actions.settle.$id") { settle() }
        return compose.onNode(hasText(label(id)) and hasClickAction() and
            hasAnyAncestor(hasTestTag("store-$kind-actions")))
    }
    private fun cancel(kind: String) { action(kind, R.string.rear_widget_cancel).performClick(); closed(kind) }
    private fun horizontal(kind: String, confirm: Int) {
        val cancel = action(kind, R.string.rear_widget_cancel).fetchSemanticsNode().boundsInRoot
        val accept = action(kind, confirm).fetchSemanticsNode().boundsInRoot
        assertEquals("Horizontal actions: $kind", cancel.top, accept.top, 1f)
        assertTrue("Cancel before confirm: $kind", cancel.right <= accept.left)
    }
    private fun back(kind: String) {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        closed(kind); assertEquals("Dialog must consume Back", 0, pageBacks)
    }
    private fun outside(kind: String) {
        compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("store-$kind-dialog")), useUnmergedTree = true)
            .performTouchInput { click(Offset(2f, 2f)) }
        closed(kind); assertEquals(0, pageBacks)
    }
    private fun titleEditor() = compose.onAllNodes(hasSetTextAction() and
        hasAnyAncestor(hasTestTag("store-wallpaper-dialog")), useUnmergedTree = true).onFirst()
    private fun changeTitle(value: String) {
        titleEditor().reveal("title.scroll")
        traceStep("title.scroll.settle") { settle() }
        traceStep("title.replace") { titleEditor().performTextReplacement(value) }
        traceStep("title.replace.settle") { settle() }
    }
    private fun capture(state: String) {
        val folder = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"), "store-glass/actual-page").apply { mkdirs() }
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(folder, "$prefix-$state.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(folder, "scope.txt").writeText("ACTUAL RearStoreDetailContent with inert repository fixture; not a sampling-only mock.\n" +
            "No network, real IPC, package install/uninstall or external browser. WebView handoff only, no Chromium pixel claim.\n" +
            "Native Robolectric captures: physical-device IME, predictive gestures, hardware optics and browser navigation still need device QA.\n")
    }
    private fun matrix(block: (MixUiBackend, Boolean) -> Unit) {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) block(backend, dark)
    }

    @Test fun actualPageUninstallKeepsCancelBackOutsideFailureAndDestructiveAction() = matrix { backend, dark ->
        fake = StoreFixture(); host(backend, dark, installed = true)
        openUninstall(); horizontal("uninstall", R.string.rear_store_uninstall_action)
        capture("uninstall"); cancel("uninstall"); assertTrue(fake.uninstalls.isEmpty())
        openUninstall(); back("uninstall"); assertTrue(fake.uninstalls.isEmpty())
        openUninstall(); outside("uninstall"); assertTrue(fake.uninstalls.isEmpty())
        fake.failUninstall = true
        openUninstall(); action("uninstall", R.string.rear_store_uninstall_action).performClick(); closed("uninstall")
        await { fake.uninstalls.size == 1 }
        assertTrue(ShadowToast.getTextOfLatestToast().contains("fixture uninstall failure"))
        assertEquals(0, installedCallbacks)
        fake.failUninstall = false
        openUninstall(); action("uninstall", R.string.rear_store_uninstall_action).performClick(); closed("uninstall")
        await { installedCallbacks == 1 }
        assertEquals(listOf("fixture-store", "fixture-store"), fake.uninstalls)
        assertEquals(0, fake.installCalls + fake.prepareCalls)
    }

    @Test fun actualPageBlockedNoticeNeverCallsInstallOrConflictRepository() = matrix { backend, dark ->
        fake = StoreFixture().apply { detail = detail.copy(widgetInfo = detail.widgetInfo!!.copy(minVersion = Long.MAX_VALUE)) }
        host(backend, dark)
        clickInstall(); shown("blocked")
        compose.onNode(hasText(label(R.string.rear_store_install_version_not_supported)) and
            hasAnyAncestor(hasTestTag("store-blocked-dialog"))).assertExists()
        capture("blocked")
        compose.onNode(hasText(label(R.string.rear_widget_cancel)) and
            hasAnyAncestor(hasTestTag("store-blocked-dialog"))).performClick(); closed("blocked")
        clickInstall(); shown("blocked"); back("blocked")
        assertTrue(fake.releaseCalls >= 2)
        assertEquals(0, fake.conflictCalls + fake.prepareCalls + fake.installCalls + fake.uninstalls.size)
    }

    @Test fun actualPageConflictKeepsThirdActionAndForceOverwriteWithErrorRecovery() = matrix { backend, dark ->
        fake = StoreFixture(); host(backend, dark)
        for (source in RearStoreInstallConflictSource.entries) {
            fake.conflict = fake.conflict(source)
            clickInstall(); shown("conflict")
            horizontal("conflict", R.string.rear_store_force_overwrite)
            val removeOther = compose.onNode(hasText(label(R.string.rear_store_uninstall_conflicting_widget)) and
                hasAnyAncestor(hasTestTag("store-conflict-dialog")))
            if (source == RearStoreInstallConflictSource.OTHER_STORE_WIDGET) removeOther.assertExists()
            else removeOther.assertDoesNotExist()
            capture("conflict-${source.name.lowercase()}")
            cancel("conflict")
        }
        assertEquals(0, fake.installCalls + fake.uninstalls.size)
        clickInstall(); shown("conflict"); back("conflict")
        clickInstall(); shown("conflict")
        compose.onNode(hasText(label(R.string.rear_store_uninstall_conflicting_widget)) and
            hasAnyAncestor(hasTestTag("store-conflict-dialog"))).performScrollTo().performClick()
        closed("conflict"); await { installedCallbacks == 1 }
        assertEquals(listOf("other-widget"), fake.uninstalls)
        assertEquals(0, fake.installCalls)
        fake.failInstall = true
        clickInstall(); shown("conflict")
        action("conflict", R.string.rear_store_force_overwrite).performClick(); closed("conflict")
        await { fake.installCalls == 1 }
        assertTrue(fake.lastOverwrite)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("fixture install failure"))
        assertEquals(1, installedCallbacks)
        fake.failInstall = false
        clickInstall(); shown("conflict")
        action("conflict", R.string.rear_store_force_overwrite).performClick(); closed("conflict")
        await { installedCallbacks == 2 }
        assertEquals(2, fake.installCalls); assertTrue(fake.lastOverwrite)
    }

    @Test fun actualPageWallpaperKeepsPreparationBusyFailureDraftResetAndSubmit() = matrix { backend, dark ->
        fake = StoreFixture(type = "wallpaper"); host(backend, dark)
        fake.prepareGate = CompletableDeferred()
        clickInstall(); await { fake.prepareCalls == 1 }
        compose.onNodeWithText(label(R.string.rear_store_asset_install)).assertDoesNotExist()
        compose.onNodeWithTag("store-wallpaper-dialog").assertDoesNotExist()
        fake.prepareGate!!.complete(Unit); shown("wallpaper")
        capture("wallpaper-top")
        changeTitle("Discard me")
        horizontal("wallpaper", R.string.rear_widget_confirm); capture("wallpaper-actions")
        cancel("wallpaper"); assertEquals(0, fake.installCalls)
        clickInstall(); shown("wallpaper")
        titleEditor().assertTextContains("Fixture Store item")
        changeTitle("Back discards this"); back("wallpaper")
        clickInstall(); shown("wallpaper")
        titleEditor().assertTextContains("Fixture Store item")
        changeTitle("Changed wallpaper")
        fake.installGate = CompletableDeferred()
        action("wallpaper", R.string.rear_widget_confirm).performClick(); closed("wallpaper")
        await { fake.installCalls == 1 }
        compose.onNodeWithText(label(R.string.rear_store_asset_install)).assertDoesNotExist()
        assertSame(fake.prepared, fake.lastPrepared)
        assertEquals("Changed wallpaper", fake.lastMetadata?.titleFallback)
        assertEquals("Fixture author", fake.lastMetadata?.author)
        fake.installGate!!.complete(Unit); await { installedCallbacks == 1 }
        fake.failPrepare = true
        clickInstall(); await { fake.prepareCalls == 4 }
        compose.onNodeWithTag("store-wallpaper-dialog").assertDoesNotExist()
        assertTrue(ShadowToast.getTextOfLatestToast().contains("fixture prepare failure"))
        compose.onNodeWithText(label(R.string.rear_store_asset_install)).assertIsDisplayed()
        assertEquals(1, fake.installCalls)
        fake.failPrepare = false
        clickInstall(); shown("wallpaper"); back("wallpaper")
    }

    @Test fun actualPagePostinstallBrowserKeepsLocalWebViewHandoffAndExternalIntent() = matrix { backend, dark ->
        fake = StoreFixture(postInstall = "https://example.invalid/installed/{id}")
        host(backend, dark)
        clickInstall(); shown("browser")
        assertEquals(1, installedCallbacks)
        val expectedUrl = "https://example.invalid/installed/fixture-store"
        compose.runOnUiThread {
            fun collect(view: View): List<WebView> = when (view) {
                is WebView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { collect(view.getChildAt(it)) }
                else -> emptyList()
            }
            val browser = collect(activity!!.get().window.decorView).single {
                shadowOf(it).lastLoadedUrl == expectedUrl
            }
            assertTrue(browser.settings.javaScriptEnabled)
            assertTrue(browser.settings.domStorageEnabled)
            assertFalse(browser.settings.allowFileAccess)
        }
        horizontal("browser", R.string.rear_store_postinstall_open_in_browser)
        capture("postinstall-browser")
        action("browser", R.string.rear_store_postinstall_open_in_browser).performClick(); settle()
        val intent = shadowOf(activity!!.get()).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(expectedUrl, intent.dataString)
        compose.onNodeWithTag("store-browser-dialog").assertIsDisplayed()
        back("browser")
        clickInstall(); shown("browser"); cancel("browser")
        assertEquals(2, fake.installCalls); assertEquals(0, pageBacks)
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h480dp-mdpi")
    fun actualPageLongDialogsKeepActionsReachableInShortWindow() = matrix { backend, dark ->
        fake = StoreFixture(type = "wallpaper"); host(backend, dark)
        clickInstall(); shown("wallpaper")
        horizontal("wallpaper", R.string.rear_widget_confirm)
        action("wallpaper", R.string.rear_widget_confirm).assertIsDisplayed()
        capture("wallpaper-short-window-actions"); back("wallpaper")
        fake = StoreFixture(postInstall = "https://example.invalid/local-fixture")
        host(backend, dark); clickInstall(); shown("browser")
        horizontal("browser", R.string.rear_store_postinstall_open_in_browser)
        action("browser", R.string.rear_store_postinstall_open_in_browser).assertIsDisplayed()
        capture("browser-short-window-actions"); cancel("browser")
    }

    /** Isolated vendor-host diagnostic, NOT the production Store page. Only source bands move.
     * Actual Store workflows and screenshots are exercised by the other methods above. */
    @Test fun samplingOnlyFixtureRecordsPhaseResponseAndReturnWithoutMovingControls() = matrix { backend, dark ->
        val phase = mutableStateOf(0f)
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    GlassTopBarScaffold(title = "Sampling fixture", isContentScrolled = true,
                        overlayContent = {
                            GlassFormDialog(show = true, title = "Sampling fixture, not Store", onDismissRequest = {},
                                modifier = Modifier.testTag("store-sampling-dialog")) {
                                DialogFormColumn {
                                    // Deliberately empty glass area; no input fill, glyph or button pixels in this ROI.
                                    Box(Modifier.fillMaxWidth().height(112.dp).testTag("store-sampling-roi"))
                                    TextField(value = "Stable input", onValueChange = {}, label = "Stable label",
                                        modifier = Modifier.fillMaxWidth().testTag("store-sampling-input"))
                                    DialogActions("Cancel", "Confirm", {}, {}, Modifier.testTag("store-sampling-actions"))
                                }
                            }
                        },
                    ) {
                        Canvas(Modifier.fillMaxSize()) {
                            val width = 48.dp.toPx()
                            val shift = phase.value.dp.toPx()
                            val colors = listOf(Color(0xFFE73C42), Color(0xFF2165D6), Color(0xFFF6D447), Color(0xFF249A6B))
                            for (i in -2..(size.width / width).toInt() + 2) {
                                drawRect(colors[((i % 4) + 4) % 4], Offset(i * width + shift, 0f), Size(width, size.height))
                            }
                        }
                    }
                }
            }
        }
        settle()
        val root = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("store-sampling-dialog")), useUnmergedTree = true)
        val roi = compose.onNodeWithTag("store-sampling-roi").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("store-sampling-input").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("store-sampling-actions").fetchSemanticsNode().boundsInRoot
        val folder = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"),
            "store-glass/sampling-only/${backend.name.lowercase()}-${if (dark) "dark" else "light"}").apply { mkdirs() }
        fun snapshot(name: String): Bitmap {
            val bitmap = root.captureToImage().asAndroidBitmap()
            File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return bitmap
        }
        fun difference(a: Bitmap, b: Bitmap, area: Rect): Double {
            var delta = 0L; var pixels = 0
            for (y in (area.top.toInt() + 8) until (area.bottom.toInt() - 8)) {
                for (x in (area.left.toInt() + 8) until (area.right.toInt() - 8)) {
                    val ac = a.getPixel(x, y); val bc = b.getPixel(x, y)
                    delta += kotlin.math.abs(android.graphics.Color.red(ac) - android.graphics.Color.red(bc)) +
                        kotlin.math.abs(android.graphics.Color.green(ac) - android.graphics.Color.green(bc)) +
                        kotlin.math.abs(android.graphics.Color.blue(ac) - android.graphics.Color.blue(bc))
                    pixels++
                }
            }
            check(pixels > 0) { "Empty sampling-only glass ROI" }
            return delta / (pixels * 3.0)
        }
        val first = snapshot("phase-0")
        val rows = StringBuilder("phase_dp\tglass_RGB_MAD_from_phase0\tinput_RGB_MAD_from_phase0\n")
        for ((name, value) in listOf("24" to 24f, "48" to 48f, "return-0" to 0f)) {
            compose.runOnUiThread { phase.value = value }; settle()
            assertEquals(roi, compose.onNodeWithTag("store-sampling-roi").fetchSemanticsNode().boundsInRoot)
            assertEquals(input, compose.onNodeWithTag("store-sampling-input").fetchSemanticsNode().boundsInRoot)
            assertEquals(actions, compose.onNodeWithTag("store-sampling-actions").fetchSemanticsNode().boundsInRoot)
            val frame = snapshot("phase-$name")
            rows.append("$name\t${difference(first, frame, roi)}\t${difference(first, frame, input)}\n")
        }
        File(folder, "metrics.tsv").writeText(rows.toString())
        File(folder, "scope.txt").writeText("SAMPLING-ONLY vendor GlassTopBarScaffold/GlassFormDialog fixture, NOT actual Store UI.\n" +
            "Only 48dp source bands shift through 0/24/48/0dp; foreground bounds stay fixed.\n" +
            "Glass and ordinary input interior RGB differences are diagnostics, not optical pass/fail certification.\n" +
            "Review returned-source residual and moving-source response, then verify on real hardware.\n")
    }

}
