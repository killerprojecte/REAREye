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



/** Actual shared template editor, preserving embedded scrolling and save/reset callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TemplateEditorNativeHeaderTest {
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
        val folder = File(output, "template-editor-native-header").apply { mkdirs() }
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

    @Test(timeout = 60_000) fun realTemplateEditorScrollsAndPreservesSaveResetAndBack() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) for (embedded in listOf(false, true)) {
            var saves = 0; var resets = 0; var backs = 0
            open(backend, dark) {
                hk.uwu.reareye.ui.components.config.template.TemplateVarConfigScreenScaffold(
                    title = "组件变量 Template", embedded = embedded,
                    loading = false, schema = 30, config = 1, hasEditableItems = true,
                    loadingText = "Loading fixture", unavailableText = "Unavailable fixture",
                    confirmText = "Save fixture", resetText = "Reset fixture",
                    onBack = { backs++ }, onConfirm = { saves++ }, onReset = { resets++ },
                    editorItems = { count, _ -> items(count) { index ->
                        Text("Editor row $index", Modifier.fillMaxWidth().height(64.dp))
                    } },
                )
            }
            if (embedded) compose.onNodeWithText("组件变量 Template").assertDoesNotExist()
            else compose.onAllNodesWithText("组件变量 Template").fetchSemanticsNodes().also { assertTrue(it.isNotEmpty()) }
            capture("${backend.name}-$dark-$embedded-top")
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }; frames()
            compose.onNode(hasScrollAction()).performScrollToIndex(30); frames()
            compose.onNodeWithText("Save fixture").performClick()
            compose.onNodeWithText("Reset fixture").assertIsDisplayed()
            compose.onNodeWithText("Reset fixture").performClick()
            assertEquals(1, saves); assertEquals(1, resets)
            capture("${backend.name}-$dark-$embedded-bottom")
            compose.onNode(hasScrollAction()).performScrollToIndex(0); frames()
            compose.onNodeWithText("Editor row 0").assertIsDisplayed()
            if (embedded) back() else compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
            assertEquals(1, backs)
        }
    }

    @Test(timeout = 60_000) fun loadingAndUnavailableKeepHeaderAndNeverExposeSaveActions() {
        for (backend in MixUiBackend.entries) for (loading in listOf(false, true)) {
            var backs = 0
            open(backend) {
                hk.uwu.reareye.ui.components.config.template.TemplateVarConfigScreenScaffold<Int, Int>(
                    title = "组件变量 Template", loading = loading, schema = null, config = null, hasEditableItems = false,
                    loadingText = "Loading fixture", unavailableText = "Unavailable fixture",
                    confirmText = "Save fixture", resetText = "Reset fixture", onBack = { backs++ },
                    onConfirm = { error("No save in unavailable editor") }, onReset = { error("No reset in unavailable editor") },
                    editorItems = { _, _ -> error("No editor content") },
                )
            }
            compose.onNodeWithText(if (loading) "Loading fixture" else "Unavailable fixture").assertIsDisplayed()
            compose.onNodeWithText("Save fixture").assertDoesNotExist()
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
            assertEquals(1, backs)
        }
    }
    @Test(timeout = 60_000) fun standaloneEditorCombinesExternalBottomObstructionWithoutASecondHeader() {
        for (backend in MixUiBackend.entries) {
            open(backend) {
                hk.uwu.reareye.ui.components.config.template.TemplateVarConfigScreenScaffold(
                    title = "Wallpaper template", loading = false, schema = 30, config = 1,
                    contentPadding = PaddingValues(bottom = 96.dp), hasEditableItems = true,
                    loadingText = "Loading fixture", unavailableText = "Unavailable fixture",
                    confirmText = "Save fixture", resetText = "Reset fixture",
                    onBack = {}, onConfirm = {}, onReset = {},
                    editorItems = { count, _ -> items(count) { index -> Text("Editor row $index", Modifier.height(64.dp)) } },
                )
            }
            compose.onNode(hasScrollAction()).performScrollToIndex(30); frames()
            val reset = compose.onNodeWithText("Reset fixture").fetchSemanticsNode().boundsInRoot
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("Bottom action must stay above the external navigation obstruction", reset.bottom <= root.bottom - 96f)
            compose.onAllNodesWithContentDescription(label(R.string.scene_route_navigate_back)).assertCountEquals(1)
            capture("${backend.name}-external-obstruction")
        }
    }

}
