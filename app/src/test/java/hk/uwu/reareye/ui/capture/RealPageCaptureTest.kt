package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.geometry.Offset
import org.junit.After
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
import java.io.File

/** Actual production-page Compose rendering in Robolectric, not device integration evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealPageCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<UiCaptureActivity>? = null

    @Before fun requireExplicitMode() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    @Test fun renderImplementedPagesAndSamplePressFrames() {
        val directory = File(System.getProperty("ui.capture.output"))
        for (backend in System.getProperty("ui.capture.backends", "miuix,mochi").split(",")) {
            for (page in UiCaptureActivity.PAGES.sorted()) {
                for (dark in listOf(false, true)) {
                    compose.mainClock.autoAdvance = false
                    compose.runOnUiThread {
                        activity?.close()
                        activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                            Intent().putExtra("page", page).putExtra("backend", backend).putExtra("dark", dark)).setup()
                    }
                    repeat(10) {
                        compose.mainClock.advanceTimeBy(250)
                        compose.waitForIdle()
                    }
                    if (page == "version-dialog") {
                        compose.onNodeWithText("Fixture target app", useUnmergedTree = true).assertIsDisplayed()
                    }
                    val editorTitle = when (page) {
                        "card-editor" -> hk.uwu.reareye.R.string.rear_widget_edit_card
                        "component-editor" -> hk.uwu.reareye.R.string.rear_widget_edit_business
                        else -> null
                    }
                    if (editorTitle != null) {
                        compose.onNodeWithText(activity!!.get().getString(editorTitle), useUnmergedTree = true).assertIsDisplayed()
                    }
                    if (page == "cards" && !java.lang.Boolean.getBoolean("ui.capture.baseline")) {
                        compose.onAllNodesWithTag("tabs-$backend", useUnmergedTree = true).assertCountEquals(2)
                        compose.onAllNodesWithTag("tabs-$backend", useUnmergedTree = true)[1].assertIsDisplayed()
                    }
                    val folder = File(directory, "$backend/${if (dark) "dark" else "light"}/$page").apply { mkdirs() }
                    save(folder, "initial.png")
                    if (!dark && page == "cards") {
                        val clickables = compose.onAllNodes(hasClickAction())
                        if (clickables.fetchSemanticsNodes().isNotEmpty()) {
                            clickables[0].performTouchInput { down(center) }
                            repeat(12) { index ->
                                compose.mainClock.advanceTimeBy(16)
                                save(folder, "pressed-${index.toString().padStart(2, '0')}.png")
                            }
                            clickables[0].performTouchInput { up() }
                            repeat(12) { index ->
                                compose.mainClock.advanceTimeBy(16)
                                save(folder, "released-${index.toString().padStart(2, '0')}.png")
                            }
                        }
                    }
                    if (!dark && page == "cards" && !java.lang.Boolean.getBoolean("ui.capture.baseline")) {
                        compose.onAllNodesWithTag("tabs-$backend", useUnmergedTree = true)[1].performTouchInput {
                            down(Offset(width * 0.75f, height * 0.5f))
                            up()
                        }
                        repeat(24) { index ->
                            compose.mainClock.advanceTimeBy(16)
                            save(folder, "tab-selection-${index.toString().padStart(2, '0')}.png")
                        }
                    }
                    if (page == "scene-routes-populated") {
                        compose.onRoot(useUnmergedTree = true).performTouchInput { swipeUp() }
                        repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
                        val sceneRows = compose.onAllNodesWithText("example.fixture", useUnmergedTree = true)
                        check(sceneRows.fetchSemanticsNodes().indices.any { sceneRows[it].isDisplayed() }) {
                            "No populated scene route is visible after scrolling"
                        }
                        save(folder, "scrolled.png")
                        if (!dark && !java.lang.Boolean.getBoolean("ui.capture.baseline")) {
                            val actions = compose.onAllNodes(hasClickAction())
                            val nodes = actions.fetchSemanticsNodes()
                            val backIndex = nodes.indices.firstOrNull {
                                nodes[it].boundsInRoot.top < 100f && nodes[it].boundsInRoot.center.x < 80f
                            } ?: error("No visible navigation button for glass press evidence")
                            actions[backIndex].performTouchInput { down(center) }
                            repeat(12) { frame ->
                                compose.mainClock.advanceTimeBy(16)
                                save(folder, "glass-pressed-${frame.toString().padStart(2, '0')}.png")
                            }
                            actions[backIndex].performTouchInput { up() }
                            repeat(12) { frame ->
                                compose.mainClock.advanceTimeBy(16)
                                save(folder, "glass-released-${frame.toString().padStart(2, '0')}.png")
                            }
                        }
                    }
                    File(folder, "evidence.txt").writeText(
                        "Renderer: Robolectric 4.17 native graphics / Compose\n" +
                        "Fixture: $page; backend: $backend; dark: $dark\n" +
                        "Not a physical-device or full application integration test.\n" +
                        "Sampled frames require visual review before claiming an animation effect.\n")
                }
            }
        }
    }

    @Test fun actualEditorFieldsAcceptChanges() {
        for (backend in System.getProperty("ui.capture.backends", "miuix,mochi").orEmpty().split(",")) {
            for (page in listOf("card-editor", "component-editor")) {
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread {
                    activity?.close()
                    activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                        Intent().putExtra("page", page).putExtra("backend", backend).putExtra("dark", false)).setup()
                }
                repeat(10) { compose.mainClock.advanceTimeBy(250); compose.waitForIdle() }
                if (page == "card-editor") {
                    compose.onNode(hasSetTextAction() and hasText("Fixture clock"))
                        .performTextReplacement("Edited fixture")
                    // The capture clock is manual: editing schedules recomposition and
                    // focus/label motion, so advance frames before checking visible state.
                    repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
                    compose.onNode(hasSetTextAction() and hasText("Edited fixture")).assertIsDisplayed()
                } else {
                    compose.onNode(hasSetTextAction() and hasText("/demo/clock.zip"))
                        .performTextReplacement("/demo/edited.zip")
                    repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
                    compose.onNode(hasSetTextAction() and hasText("/demo/edited.zip")).assertIsDisplayed()
                }
            }
        }
    }

    private fun save(folder: File, name: String) {
        val image = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        if (name == "initial.png") {
            val colors = mutableSetOf<Int>()
            for (y in 32 until image.height step 8) for (x in 0 until image.width step 8) {
                colors.add(image.getPixel(x, y))
            }
            check(colors.size > 3) { "Blank fixture content: $folder" }
        }
        File(folder, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
