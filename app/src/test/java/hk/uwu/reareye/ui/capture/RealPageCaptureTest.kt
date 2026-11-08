package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
                    compose.mainClock.advanceTimeBy(2500)
                    compose.waitForIdle()
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
                    File(folder, "evidence.txt").writeText(
                        "Renderer: Robolectric 4.17 native graphics / Compose\n" +
                        "Fixture: $page; backend: $backend; dark: $dark\n" +
                        "Not a physical-device or full application integration test.\n" +
                        "Sampled frames require visual review before claiming an animation effect.\n")
                }
            }
        }
    }

    private fun save(folder: File, name: String) {
        val image = compose.onNodeWithTag("fixture-root", useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        File(folder, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
