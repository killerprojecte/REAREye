package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.theme.*
import org.junit.Before
import org.junit.Assume.assumeTrue
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
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlin.math.abs

/** Actual Maven native Large sheet, not a model of the surface. Requires native graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MochiLargeSheetGlassTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var alternate by mutableStateOf(false)
    private var saved: ThemeStyleDraft? = null
    private var saves = 0

    @Before fun requireCapture() { assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled")) }

    private fun settle() {
        repeat(24) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }
    private fun open(dark: Boolean, style: AppMochiThemeStyle) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            visible = true; alternate = false; saved = null; saves = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                val mode = if (dark) AppThemeMode.MOCHI_DARK else AppThemeMode.MOCHI_LIGHT
                AppTheme(themeMode = mode) {
                    Scaffold(Modifier.fillMaxSize()) {
                        GlassBottomSheetScene {
                            Canvas(Modifier.fillMaxSize()) {
                                drawRect(if (alternate) Color.Green else Color.Red)
                                drawRect(if (alternate) Color.Yellow else Color.Blue,
                                    topLeft = Offset(size.width / 2f, 0f),
                                    size = Size(size.width / 2f, size.height))
                            }
                            ThemeStyleSheet(show = visible, appliedMode = mode,
                                appliedStyle = style,
                                onDismiss = { visible = false },
                                onSave = { saved = it; saves++; visible = false })
                        }
                    }
                }
            }
        }
        settle()
        compose.onNodeWithTag("theme-style-sheet").assertIsDisplayed()
        compose.onAllNodesWithTag("glass-tabs-mochi").assertCountEquals(3)
        assertFalse("Native sheet must share the Activity window", ShadowDialog.getLatestDialog()?.isShowing == true)
    }
    private fun frame(): Bitmap = compose.onNode(
        isRoot() and hasAnyDescendant(hasTestTag("theme-style-sheet")), useUnmergedTree = true
    ).captureToImage().asAndroidBitmap()
    private fun distance(a: Int, b: Int): Int =
        abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
        abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
        abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
    private fun capture(name: String, image: Bitmap) {
        val output = System.getProperty("ui.capture.output") ?: return
        val folder = File(output, "mochi-large-sheet-glass").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun assertMaterialNotJustTransparent(bitmap: Bitmap) {
        // The bottom tenth is empty sheet body, away from controls and navigation.
        // A plain transparent surface + uniform scrim preserves a sharp color seam;
        // the real glass softens it. Sample several rows, not one lucky pixel.
        val x = bitmap.width / 2
        var near = 0L; var far = 0L
        for (y in bitmap.height * 86 / 100 until bitmap.height * 91 / 100) {
            near += distance(bitmap.getPixel(x - 2, y), bitmap.getPixel(x + 2, y))
            far += distance(bitmap.getPixel(x - 40, y), bitmap.getPixel(x + 40, y))
        }
        assertTrue("Page colors must remain distinguishable through Large glass ($far)", far > 200)
        assertTrue("Glass must blur the seam, not merely expose a transparent page ($near/$far)", near < far * 0.8)
    }
    @Test fun stableLargeSamplesChangingPageInAllSixPalettes() {
        for (dark in listOf(false, true)) for (style in AppMochiThemeStyle.entries) {
            open(dark, style)
            val first = frame()
            val contentTop = compose.onNodeWithTag("theme-style-sheet").fetchSemanticsNode().boundsInRoot.top
            assertTrue("Keep Large height, never substitute Medium", contentTop < first.height * 0.3f)
            assertMaterialNotJustTransparent(first)
            capture("${style.name}-$dark-background-a", first)
            // No close/reopen: source must update under an already settled Large sheet.
            compose.runOnUiThread { alternate = true }; settle()
            val second = frame()
            assertMaterialNotJustTransparent(second)
            capture("${style.name}-$dark-background-b", second)
            val y = first.height * 88 / 100
            assertTrue("Settled glass must resample the changed page",
                distance(first.getPixel(first.width / 4, y), second.getPixel(second.width / 4, y)) > 15)
            settle()
            val stable = frame()
            assertTrue("Measurement is at rest, not only during the opening transition",
                distance(second.getPixel(second.width / 4, y), stable.getPixel(stable.width / 4, y)) < 5)
        }
    }
    @Test fun cancelDiscardsDraftAndSaveCommitsExactlyOnce() {
        open(false, AppMochiThemeStyle.Mochi)
        fun label(id: Int) = activity!!.get().getString(id)
        fun chooseNothing() {
            compose.onNode(hasClickAction() and (hasText("Nothing") or hasAnyDescendant(hasText("Nothing"))) and
                hasAnyAncestor(hasTestTag("theme-palette-tabs")), useUnmergedTree = true).performClick()
            settle()
        }
        chooseNothing()
        compose.onNodeWithContentDescription(label(R.string.theme_editor_close)).performClick(); settle()
        assertFalse(visible); assertNull(saved); assertEquals(0, saves)
        compose.runOnUiThread { visible = true }; settle()
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick(); settle()
        assertEquals(AppMochiThemeStyle.Mochi, saved?.mochiStyle)
        assertEquals(1, saves); assertFalse(visible)
        compose.runOnUiThread { visible = true }; settle()
        chooseNothing()
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick(); settle()
        assertEquals(AppMochiThemeStyle.Nothing, saved?.mochiStyle)
        assertEquals(2, saves); assertFalse(visible)
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
}
