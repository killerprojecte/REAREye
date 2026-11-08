package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.components.Scaffold
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.theme.AppMochiThemeStyle
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.AppThemeMode
import hk.uwu.reareye.ui.theme.ThemeStyleDraft
import hk.uwu.reareye.ui.theme.ThemeStyleSheet
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

/** Real native glass overlays: system insets and a preview unlike the applied app host. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeSheetWindowInsetsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var saving by mutableStateOf(false)
    private var saveFailed by mutableStateOf(false)
    private var applied by mutableStateOf(AppThemeMode.MIUIX_LIGHT)
    private var saved: ThemeStyleDraft? = null
    private var saveCount = 0
    private var pageClicks = 0

    private fun frames() { repeat(16) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun open(expectNativeGlass: Boolean = true) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                AppTheme(themeMode = applied) {
                    Scaffold(Modifier.fillMaxSize()) {
                        GlassBottomSheetScene {
                        Box(Modifier.fillMaxSize()) {
                            Text("Applied app theme", Modifier.testTag("applied-theme-text").clickable { pageClicks++ })
                            ThemeStyleSheet(
                                show = visible, appliedMode = applied,
                                appliedStyle = AppMochiThemeStyle.default,
                                saving = saving, saveFailed = saveFailed,
                                onDismiss = { visible = false },
                                onSave = { saved = it; saveCount++ },
                            )
                        }
                        }
                    }
                }
            }
        }
        frames()
        compose.onNodeWithTag("theme-style-sheet").assertIsDisplayed()
        if (expectNativeGlass) {
            compose.onNodeWithTag("mixui-native-glass-sheet").assertIsDisplayed()
            assertFalse("Glass uses the page window, never a cross-window source",
                ShadowDialog.getLatestDialog()?.isShowing == true)
        }
    }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun choose(tag: String, text: String) {
        compose.onNode(hasClickAction() and (hasText(text) or hasAnyDescendant(hasText(text))) and
            hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).performTouchInput { click() }
        frames()
    }
    private fun injectInsets(bottomPx: Int, imePx: Int = 0) {
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomPx))
                .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottomPx))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), bottomPx > 0)
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imePx))
                .setVisible(WindowInsetsCompat.Type.ime(), imePx > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView, insets)
        }
        frames()
    }
    private fun lastTabsBottom() = compose.onNodeWithTag("theme-brightness-tabs")
        .fetchSemanticsNode().boundsInRoot.bottom
    private fun windowRoot() = compose.onNode(
        isRoot() and hasAnyDescendant(hasTestTag("theme-style-sheet")), useUnmergedTree = true)
    private fun textLuminance(node: SemanticsNodeInteraction): Float {
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        return layouts.single().layoutInput.style.color.luminance()
    }
    private fun captureSheet(): Bitmap = windowRoot().captureToImage().asAndroidBitmap()
    private fun capture(name: String, bitmap: Bitmap = captureSheet()) {
        val folder = File(System.getProperty("ui.capture.output"), "theme-sheet-system-insets").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    @Test fun gestureAndThreeButtonInsetsKeepLastTabsAboveNavigationBarWithoutDoublePadding() {
        open()
        injectInsets(0)
        val baseline = lastTabsBottom()
        val windowBottom = windowRoot().fetchSemanticsNode().boundsInRoot.bottom
        for (bottom in listOf(24, 48, 24, 0)) {
            injectInsets(bottom)
            val tabsBottom = lastTabsBottom()
            assertEquals("Only one navigation inset is added ($bottom px)", baseline - bottom, tabsBottom, 1f)
            assertTrue("Brightness tabs must end above the system navigation bar", tabsBottom <= windowBottom - bottom)
            compose.onNodeWithTag("theme-brightness-tabs").assertIsDisplayed()
            choose("theme-brightness-tabs", label(R.string.theme_editor_dark))
            capture("miuix-dark-navigation-$bottom")
        }
        // MIUIX owns IME padding. Our content inset must not add navigation again.
        injectInsets(48, imePx = 160)
        assertEquals("IME and navigation insets must be consumed once", baseline - 160, lastTabsBottom(), 1f)
    }

    @Test fun darkDraftUpdatesNativeTitleBodyAndActionsWhileAppliedAppRemainsLight() {
        open()
        injectInsets(48)
        val appText = compose.onNodeWithTag("applied-theme-text")
        val lightAppColor = textLuminance(appText)
        assertTrue(lightAppColor < 0.2f)
        choose("theme-brightness-tabs", label(R.string.theme_editor_dark))
        assertEquals(AppThemeMode.MIUIX_LIGHT, applied)
        assertEquals(lightAppColor, textLuminance(appText), 0.001f)
        for (text in listOf(R.string.theme_editor_title, R.string.theme_editor_style,
            R.string.theme_editor_palette, R.string.theme_editor_brightness)) {
            assertTrue("Draft native header and labels must use light text: ${label(text)}",
                textLuminance(compose.onNodeWithText(label(text), useUnmergedTree = true)) > 0.5f)
        }
        for (action in listOf(R.string.theme_editor_close, R.string.theme_editor_save)) {
            compose.onNodeWithContentDescription(label(action)).assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
            compose.onNodeWithContentDescription(label(action), useUnmergedTree = true)
                .assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
        }
        val bitmap = captureSheet()
        capture("light-app-dark-draft-native-header", bitmap)
        for (action in listOf(R.string.theme_editor_close, R.string.theme_editor_save)) {
            val bounds = compose.onNodeWithContentDescription(label(action), useUnmergedTree = true)
                .fetchSemanticsNode().boundsInWindow
            var lightPixels = 0
            for (y in bounds.top.toInt() until bounds.bottom.toInt()) for (x in bounds.left.toInt() until bounds.right.toInt()) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 180 && android.graphics.Color.green(pixel) > 180 &&
                    android.graphics.Color.blue(pixel) > 180 && android.graphics.Color.alpha(pixel) > 200) lightPixels++
            }
            assertTrue("The native $action icon must remain visible on the dark sheet", lightPixels > 8)
        }
        val content = compose.onNodeWithTag("theme-style-sheet").fetchSemanticsNode().boundsInWindow
        val blankPixel = bitmap.getPixel(content.left.toInt() + 2, content.top.toInt() + 2)
        assertTrue("Draft sheet surface must actually be painted", android.graphics.Color.alpha(blankPixel) > 200)
        assertTrue("Draft sheet background must be dark", android.graphics.Color.red(blankPixel) < 128)
        compose.onNodeWithContentDescription(label(R.string.theme_editor_close)).performClick(); frames()
        assertFalse(visible)
        assertEquals(0, saveCount)
        assertEquals(AppThemeMode.MIUIX_LIGHT, applied)
        compose.runOnUiThread { visible = true }; frames()
        assertTrue("Reopening restores the applied light theme",
            textLuminance(compose.onNodeWithText(label(R.string.theme_editor_title))) < 0.2f)
    }

    @Test fun nativeOverlayBackSaveFailureRetryAndRecreationKeepDraftTransactional() {
        open()
        choose("theme-brightness-tabs", label(R.string.theme_editor_dark))
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick(); frames()
        assertEquals(AppThemeMode.MIUIX_DARK, saved!!.mode)
        assertEquals(AppThemeMode.MIUIX_LIGHT, applied)
        compose.runOnUiThread { saving = true }; frames()
        compose.onNodeWithContentDescription(label(R.string.theme_editor_close)).assertHasNoClickAction()
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; frames()
        assertTrue("Native Back cannot dismiss during a save", visible)
        compose.onNodeWithTag("theme-style-sheet").assertIsDisplayed()
        compose.runOnUiThread { saving = false; saveFailed = true }; frames()
        compose.onNodeWithTag("theme-save-error").assertIsDisplayed()
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick(); frames()
        assertEquals(2, saveCount)
        assertEquals(AppThemeMode.MIUIX_DARK, saved!!.mode)
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; frames()
        assertFalse(visible)
        assertEquals(AppThemeMode.MIUIX_LIGHT, applied)
        compose.runOnUiThread { visible = true; saveFailed = false }; frames()
        val oldActivity = activity!!.get()
        open()
        assertTrue("Activity teardown must dispose its native sheet host", oldActivity.isDestroyed)
        compose.onAllNodesWithTag("mixui-native-glass-sheet").assertCountEquals(1)
        assertTrue(textLuminance(compose.onNodeWithText(label(R.string.theme_editor_title))) < 0.2f)
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick(); frames()
        assertEquals(AppThemeMode.MIUIX_LIGHT, saved!!.mode)
    }

    @Test fun interruptedReopenKeepsOneSheetAndNoStaleDismissal() {
        open()
        choose("theme-brightness-tabs", label(R.string.theme_editor_dark))
        compose.onNodeWithContentDescription(label(R.string.theme_editor_close)).performClick()
        compose.mainClock.advanceTimeBy(60); compose.waitForIdle()
        compose.runOnUiThread { visible = true }
        frames()
        compose.onAllNodesWithTag("mixui-native-glass-sheet").assertCountEquals(1)
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick(); frames()
        assertEquals(1, saveCount)
        assertEquals(AppThemeMode.MIUIX_LIGHT, saved!!.mode)
        assertTrue(visible)
        compose.onNodeWithContentDescription(label(R.string.theme_editor_close)).performClick(); frames()
        compose.onAllNodesWithTag("mixui-native-glass-sheet").assertCountEquals(0)
        compose.onAllNodesWithTag("mixui-native-glass-sheet-host").assertCountEquals(0)
        compose.onNodeWithTag("applied-theme-text").performTouchInput { click() }
        assertEquals("Dismissal removes the overlay's full-window input layer", 1, pageClicks)
    }

    @Test fun appliedMochiCanPreviewNativeMiuixWithoutChangingAppliedTheme() {
        applied = AppThemeMode.MOCHI_LIGHT
        open(expectNativeGlass = false)
        choose("theme-style-tabs", "MIUIX")
        choose("theme-brightness-tabs", label(R.string.theme_editor_dark))
        compose.onNodeWithTag("mixui-native-glass-sheet").assertIsDisplayed()
        assertEquals(AppThemeMode.MOCHI_LIGHT, applied)
        assertTrue(textLuminance(compose.onNodeWithText(label(R.string.theme_editor_title))) > 0.5f)
        compose.onNodeWithContentDescription(label(R.string.theme_editor_save)).performClick(); frames()
        assertEquals(AppThemeMode.MIUIX_DARK, saved!!.mode)
        assertEquals(AppThemeMode.MOCHI_LIGHT, applied)
    }

}
