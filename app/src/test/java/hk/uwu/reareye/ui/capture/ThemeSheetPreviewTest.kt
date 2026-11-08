package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixUiBackend
import hk.uwu.reareye.ui.config.ConfigKeys
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Local design preview pending user approval, using the native sheet and real preview controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class, qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeSheetPreviewTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<UiCaptureActivity>? = null
    private fun frames() { repeat(16) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun open(backend: String, dark: Boolean, style: String = "Default") {
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(UiCaptureActivity::class.java,
                Intent().putExtra("page", "cards").putExtra("backend", backend).putExtra("dark", dark)
                    .putExtra("style", style).putExtra("themeSheet", true)).setup()
        }
        frames()
        compose.onNodeWithText("主题样式").assertIsDisplayed()
        compose.onNodeWithContentDescription("保存").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").assertIsDisplayed()
    }
    private fun tab(tag: String, text: String) {
        compose.onNode(hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text))) and
            hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).performTouchInput { click() }
        frames()
    }
    private fun capture(name: String) {
        val folder = File(System.getProperty("ui.capture.output"), "theme-sheet-preview").apply { mkdirs() }
        val bitmap = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("theme-style-sheet")), useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @After fun close() { compose.mainClock.autoAdvance = true; compose.runOnUiThread { activity?.close(); activity = null } }
    @Test fun renderDraftStylesAndKeepSaveCancelTransactional() {
        assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
        for (dark in listOf(false, true)) {
            open("miuix", dark)
            capture("miuix-default-${if (dark) "dark" else "light"}")
            tab("theme-palette-tabs", "Monet")
            capture("miuix-monet-${if (dark) "dark" else "light"}")
            for (style in listOf("Default", "Ios", "Nothing")) {
                open("mochi", dark, style)
                capture("mochi-${style.lowercase()}-${if (dark) "dark" else "light"}")
            }
        }
        open("miuix", false)
        val host = activity!!.get()
        tab("theme-style-tabs", "Mochi")
        tab("theme-palette-tabs", "Nothing")
        tab("theme-brightness-tabs", "深色")
        assertEquals(MixUiBackend.Miuix, host.renderedBackend)
        assertEquals(0, host.themeSheetPrefs.getInt(ConfigKeys.MODULE_THEME_MODE, -1))
        assertEquals(0, host.themeSaveCount)
        capture("unsaved-mochi-nothing-dark")
        compose.onNodeWithContentDescription("关闭").performClick(); frames()
        assertFalse(host.themeSheetVisible)
        assertEquals(0, host.themeSheetPrefs.getInt(ConfigKeys.MODULE_THEME_MODE, -1))
        compose.runOnUiThread { host.themeSheetVisible = true }; frames()
        tab("theme-style-tabs", "Mochi")
        tab("theme-palette-tabs", "iOS")
        tab("theme-brightness-tabs", "深色")
        compose.onNodeWithContentDescription("保存").performClick(); frames()
        assertEquals(8, host.themeSheetPrefs.getInt(ConfigKeys.MODULE_THEME_MODE, -1))
        assertEquals(1, host.themeSheetPrefs.getInt(ConfigKeys.MODULE_MOCHI_THEME_STYLE, -1))
        assertEquals(1, host.themeSaveCount)
        assertEquals(MixUiBackend.Mochi, host.renderedBackend)
        assertFalse(host.themeSheetVisible)
    }
}
