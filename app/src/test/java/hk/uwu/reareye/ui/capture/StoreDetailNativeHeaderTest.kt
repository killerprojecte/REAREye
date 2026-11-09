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

    private fun awaitText(value: String) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
        frames()
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
            capture("${backend.name}-$dark-top")
            compose.onNodeWithContentDescription(label(R.string.rear_store_open_repository)).performClick()
            val intent = org.robolectric.Shadows.shadowOf(activity!!.get()).nextStartedActivity
            assertEquals("https://example.invalid/project", intent.dataString)
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(1); frames()
            compose.onNodeWithText(label(R.string.rear_store_tab_downloads)).performClick(); frames()
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performTouchInput { swipeUp() }; frames()
            capture("${backend.name}-$dark-downloads")
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(1); frames()
            compose.onNodeWithText(label(R.string.rear_store_tab_info)).performClick(); frames()
            capture("${backend.name}-$dark-info")
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToIndex(1); frames()
            compose.onNodeWithText(label(R.string.rear_store_tab_downloads)).performClick(); frames()
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
