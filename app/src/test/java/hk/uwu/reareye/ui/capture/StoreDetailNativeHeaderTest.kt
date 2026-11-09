package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearstore.*
import hk.uwu.reareye.ui.screen.RearStoreDetailContent
import androidx.compose.ui.semantics.SemanticsActions
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.config.PrefsManager
import org.junit.Assume
import androidx.compose.ui.graphics.luminance
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



/** Actual store detail with injected read-only loaders; installation is never invoked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreDetailNativeHeaderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private var renderedDark = false
    private fun open(backend: MixUiBackend, dark: Boolean = false,
        colorMode: MixColorSchemeMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light,
        style: hk.uwu.mixui.MixMochiStyle = hk.uwu.mixui.MixMochiStyle.Default, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, colorMode, style) {
                    val isDark = hk.uwu.mixui.theme.MixTheme.colorScheme.background.luminance() < 0.5f
                    SideEffect { renderedDark = isDark }
                    content()
                }
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
        val folder = File(output, "store-detail-native-header").apply { mkdirs() }
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


    /** Opt-in production detail fixture. README is local Markdown, without external images. */
    @Test fun renderFullStyleDetailMatrix() = fullStyleMatrix { _, _, backend, colors, style, folder ->
        val prefs = PrefsManager(GuideMemoryPrefs())
        val readmeCalls = java.util.concurrent.atomic.AtomicInteger()
        open(backend, colorMode = colors, style = style) {
            RearStoreDetailContent(
                widgetId = "fixture-detail", prefsManager = prefs, bottomInnerPadding = 96.dp,
                installedWidget = null, onBack = {}, onInstalled = { error("Capture must not install") },
                loadDetail = { _, _, _ -> detail() },
                loadReadme = { _, _ ->
                    readmeCalls.incrementAndGet()
                    RearStoreReadme(name = "README.md", htmlUrl = "https://example.invalid/project/README.md",
                        content = "# Fixture local README\n\nA deterministic clock widget.\n\n" +
                            (1..12).joinToString("\n\n") { "## Local section $it\nConfigure the clock locally. No network resources are required." })
                },
            )
        }
        awaitText("Fixture detailed clock")
        compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); readmeCalls.get() == 1 }
        frames()
        compose.onNodeWithText("Fixture detailed clock").assertIsDisplayed()
        compose.onNodeWithText(label(R.string.rear_store_readme_empty)).assertDoesNotExist()
        fun snapshot(state: String) {
            val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
            File(folder, "$state.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        snapshot("top")
        // Robolectric does not lay out Chromium content: verify the real HTML handoff,
        // without claiming README pixels or manufacturing a scrollable WebView height.
        compose.runOnUiThread {
            fun containsLocalReadme(view: android.view.View): Boolean {
                if (view is android.webkit.WebView && view.tag.toString().contains("Fixture local README")) return true
                return view is android.view.ViewGroup && (0 until view.childCount).any { containsLocalReadme(view.getChildAt(it)) }
            }
            assertTrue("Production WebView received local README HTML", containsLocalReadme(activity!!.get().window.decorView))
        }
        compose.onNodeWithText(label(R.string.rear_store_tab_downloads)).performClick(); frames()
        compose.onNodeWithText("Fixture release 1").assertExists()
        snapshot("versions")
        compose.onNodeWithText(label(R.string.rear_store_tab_info)).performClick(); frames()
        compose.onAllNodesWithText("fixture/project", substring = true).onFirst().assertExists()
        snapshot("info")
        assertEquals(1, readmeCalls.get())
    }

    private fun captureSelection(property: String, allowed: Set<String>): List<String> {
        val raw = System.getProperty(property, "").trim()
        Assume.assumeTrue("Opt in with $property", raw.isNotEmpty())
        val values = raw.split(',').map(String::trim)
        require(values.all { it in allowed } && values.distinct().size == values.size) {
            "Invalid $property=$raw; allowed: $allowed"
        }
        return values
    }

    private fun fullStyleMatrix(capture: (String, String, MixUiBackend, MixColorSchemeMode, hk.uwu.mixui.MixMochiStyle, File) -> Unit) {
        val variants = captureSelection("ui.capture.variants", setOf("miuix-default", "miuix-monet", "mochi-default", "mochi-ios", "mochi-nothing"))
        val modes = captureSelection("ui.capture.modes", setOf("light", "dark", "system-day", "system-night"))
        val output = requireNotNull(System.getProperty("ui.capture.output"))
        val qualifiers = org.robolectric.RuntimeEnvironment.getQualifiers()
        try {
            for (variant in variants) for (mode in modes) {
                compose.runOnUiThread { activity?.close(); activity = null }
                val night = mode == "system-night"
                org.robolectric.RuntimeEnvironment.setQualifiers("zh-rCN-w400dp-h800dp-${if (night) "night" else "notnight"}-mdpi")
                val dark = mode == "dark" || night
                val system = mode.startsWith("system-")
                val backend = if (variant.startsWith("mochi-")) MixUiBackend.Mochi else MixUiBackend.Miuix
                val colors = if (variant == "miuix-monet") {
                    if (system) MixColorSchemeMode.MonetSystem else if (dark) MixColorSchemeMode.MonetDark else MixColorSchemeMode.MonetLight
                } else {
                    if (system) MixColorSchemeMode.System else if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light
                }
                val style = when (variant) {
                    "mochi-ios" -> hk.uwu.mixui.MixMochiStyle.Ios
                    "mochi-nothing" -> hk.uwu.mixui.MixMochiStyle.Nothing
                    else -> hk.uwu.mixui.MixMochiStyle.Default
                }
                val folder = File(output, "full-style-details/$variant/$mode").apply { mkdirs() }
                File(folder, "manifest.json").delete()
                listOf("top", "readme-scrolled", "versions", "info").forEach { File(folder, "$it.png").delete() }
                capture(variant, mode, backend, colors, style, folder)
                val actualNight = activity!!.get().resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
                assertEquals(night, actualNight)
                assertEquals(dark, renderedDark)
                val manifest = org.json.JSONObject()
                    .put("variant", variant).put("mode", mode).put("backend", backend.name)
                    .put("colorMode", colors.name).put("mochiStyle", style.name)
                    .put("systemDark", actualNight).put("renderedDark", renderedDark)
                    .put("qualifiers", org.robolectric.RuntimeEnvironment.getQualifiers())
                    .put("states", org.json.JSONArray(listOf("top", "versions", "info")))
                    .put("readmeEvidence", "Production WebView HTML handoff asserted; Chromium正文 pixels and README scrolling not verified")
                    .put("host", "RearStoreDetailContent with local detail/readme loaders")
                    .put("capture", "Native Robolectric production component fixture; not MainActivity network flow or device capture")
                File(folder, "manifest.json").writeText(manifest.toString(2) + "\n")
            }
        } finally {
            compose.runOnUiThread { activity?.close(); activity = null }
            org.robolectric.RuntimeEnvironment.setQualifiers(qualifiers)
        }
    }

    private fun awaitText(value: String) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
        frames()
    }

    private fun assertHeaderIconsVisible(dark: Boolean) {
        for (label in listOf(R.string.scene_route_navigate_back, R.string.rear_store_open_repository)) {
            val node = compose.onNodeWithContentDescription(this.label(label)).assertIsDisplayed()
            val bitmap = node.captureToImage().asAndroidBitmap()
            var foreground = 0
            for (y in bitmap.height / 4 until bitmap.height * 3 / 4) {
                for (x in bitmap.width / 4 until bitmap.width * 3 / 4) {
                    val color = bitmap.getPixel(x, y)
                    val luminance = (android.graphics.Color.red(color) + android.graphics.Color.green(color) + android.graphics.Color.blue(color)) / 3
                    if (android.graphics.Color.alpha(color) > 200 &&
                        (if (dark) luminance > 180 else luminance < 100)) foreground++
                }
            }
            assertTrue("Visible icon foreground for $label in dark=$dark", foreground >= 8)
        }
    }

    private fun detail() = RearStoreWidgetDetail(
        type = "card", widgetId = "fixture-detail", name = "Fixture detailed clock",
        description = "Description with no external data. ".repeat(12),
        author = RearStoreAuthor(name = "Fixture author"),
        repository = RearStoreRepositoryInfo(fullName = "fixture/project", url = "https://example.invalid/project"),
        widgetInfo = null, metadata = null, readme = null,
        releases = (1..3).map { RearStoreRelease(tagName = "v$it", name = "Fixture release $it") },
    )

    @Test(timeout = 60_000) fun actualDetailKeepsLazyReadmeTabsRepositoryActionAndBack() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            var backs = 0; val detailCalls = java.util.concurrent.atomic.AtomicInteger(); val readmeCalls = java.util.concurrent.atomic.AtomicInteger()
            val prefs = PrefsManager(GuideMemoryPrefs())
            open(backend, dark) {
                RearStoreDetailContent(
                    widgetId = "fixture-detail", prefsManager = prefs, bottomInnerPadding = 96.dp,
                    installedWidget = null, onBack = { backs++ }, onInstalled = { error("Never install from this test") },
                    loadDetail = { supplied, id, version ->
                        assertSame(prefs, supplied); assertEquals("fixture-detail", id); assertNull(version)
                        detailCalls.incrementAndGet(); detail()
                    },
                    loadReadme = { supplied, id ->
                        assertSame(prefs, supplied); assertEquals("fixture-detail", id)
                        readmeCalls.incrementAndGet(); RearStoreReadme()
                    },
                )
            }
            awaitText("Fixture detailed clock")
            compose.waitUntil(10_000) { compose.mainClock.advanceTimeBy(100); readmeCalls.get() == 1 }; frames()
            assertEquals(1, detailCalls.get())
            val initialHeroTop = compose.onNodeWithText("Fixture detailed clock").fetchSemanticsNode().boundsInRoot.top
            assertHeaderIconsVisible(dark)
            compose.onNodeWithTag(if (backend == MixUiBackend.Miuix) "glass-tabs-miuix" else "glass-tabs-mochi").assertIsDisplayed()
            capture("${backend.name}-$dark-top")
            compose.onNodeWithContentDescription(label(R.string.rear_store_open_repository)).performClick()
            val intent = org.robolectric.Shadows.shadowOf(activity!!.get()).nextStartedActivity
            assertEquals("https://example.invalid/project", intent.dataString)
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(1); frames()
            compose.onNodeWithText(label(R.string.rear_store_tab_downloads)).performClick(); frames()
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performTouchInput { swipeUp() }; frames()
            assertHeaderIconsVisible(dark)
            capture("${backend.name}-$dark-downloads")
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(1); frames()
            compose.onNodeWithText(label(R.string.rear_store_tab_info)).performClick(); frames()
            capture("${backend.name}-$dark-info")
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(1); frames()
            compose.onNodeWithText(label(R.string.rear_store_tab_downloads)).performClick(); frames()
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(0)
            repeat(2) {
                compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performTouchInput {
                    swipe(androidx.compose.ui.geometry.Offset(center.x, height * 0.5f),
                        androidx.compose.ui.geometry.Offset(center.x, height * 0.85f), durationMillis = 500)
                }; frames()
            }
            assertEquals("Body-origin pull restores the real detail header", initialHeroTop,
                compose.onNodeWithText("Fixture detailed clock").fetchSemanticsNode().boundsInRoot.top, 1.5f)
            assertHeaderIconsVisible(dark)
            capture("${backend.name}-$dark-restored")
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
            assertEquals(1, backs)
        }
    }

    @Test(timeout = 60_000) fun actualLoadingAndFailedDetailKeepBackAndDoNotLoadReadme() {
        for (backend in MixUiBackend.entries) {
            val pending = kotlinx.coroutines.CompletableDeferred<RearStoreWidgetDetail?>()
            var backs = 0
            open(backend) {
                RearStoreDetailContent(
                    widgetId = "missing", prefsManager = PrefsManager(GuideMemoryPrefs()), bottomInnerPadding = 0.dp,
                    installedWidget = null, onBack = { backs++ }, onInstalled = {},
                    loadDetail = { _, _, _ -> pending.await() }, loadReadme = { _, _ -> error("No readme without detail") },
                )
            }
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).assertIsDisplayed()
            pending.complete(null)
            awaitText(label(R.string.rear_store_detail_failed))
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
            assertEquals(1, backs)
            capture("${backend.name}-failed")
        }
    }
}
