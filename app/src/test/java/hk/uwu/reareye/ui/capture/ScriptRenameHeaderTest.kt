package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.components.Scaffold
import hk.uwu.mixui.components.GlassBottomSheetScene
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.script.ScriptRenameDialog
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.AppThemeMode
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Actual production naming sheet; callbacks are recorded, not filesystem integration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptRenameHeaderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var fullStyleFolder: File? = null
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var initial by mutableStateOf("demo_project")
    private val saved = mutableListOf<String>()
    private var hideOnConfirm = true
    private fun frames() { repeat(16) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun input() = compose.onNode(hasSetTextAction())
    private fun save() = compose.onNodeWithTag("script-name-save")
    private fun cancel() = compose.onNodeWithTag("script-name-cancel")
    private fun open(mode: AppThemeMode, titleId: Int, mochiStyle: MixMochiStyle = MixMochiStyle.Default) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            visible = true
            initial = if (titleId == R.string.script_rename_file) "helper.lua" else "demo_project"
            saved.clear()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                AppTheme(themeMode = mode, mochiStyle = mochiStyle) {
                    Scaffold(Modifier.fillMaxSize().testTag("script-naming-host")) {
                        GlassBottomSheetScene {
                        Text("FIXTURE HOST · Production script naming sheet")
                        ScriptRenameDialog(visible, activity!!.get().getString(titleId), initial,
                            onDismiss = { visible = false },
                            onConfirm = { saved += it; if (hideOnConfirm) visible = false })
                        }
                    }
                }
            }
        }
        frames()
    }
    private fun capture(name: String) {
        val folder = (fullStyleFolder ?: File(System.getProperty("ui.capture.output"), "script-rename-header")).apply { mkdirs() }
        val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
        val bitmap = if (dialog != null) {
            val overlay = compose.runOnUiThread { captureDialogWindowBitmap(dialog) }
            File(folder, "$name-window.png").outputStream().use { overlay.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val page = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("script-naming-host")), useUnmergedTree = true)
                .captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
            android.graphics.Canvas(page).drawBitmap(overlay, 0f, 0f, null)
            page
        }
        else compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("script-name-input")), useUnmergedTree = true)
            .captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    @Test fun renderFullStyleScriptRenameMatrix() = majorSheetStyleMatrix(
        directory = "full-style-script-rename", language = "zh-rCN",
        fixture = "ScriptRenameDialog production naming sheet over labeled Scaffold fixture background; project/file/import titles; not a full application page",
        close = { compose.runOnUiThread { activity?.close(); activity = null } },
    ) { _, _, backend, _, colors, style, folder ->
        val theme = AppThemeMode.entries.single { it.backend == backend && it.colorSchemeMode == colors }
        fullStyleFolder = folder
        try {
            for ((titleId, state) in listOf(
                R.string.script_rename_project to "project",
                R.string.script_rename_file to "file",
                R.string.script_import_project to "import",
            )) {
                open(theme, titleId, style)
                input().assertTextContains(initial)
                save().assertIsDisplayed().assertIsEnabled()
                cancel().assertIsDisplayed()
                capture("$state-header")
                input().performTextReplacement("   "); frames()
                save().assertIsNotEnabled()
                capture("$state-empty-validation")
                cancel().performClick(); frames()
            }
        } finally { fullStyleFolder = null }
    }

    @Test fun headerSaveValidationCancelBackAndReopen() {
        assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        for (mode in listOf(AppThemeMode.MIUIX_LIGHT, AppThemeMode.MIUIX_DARK,
            AppThemeMode.MOCHI_LIGHT, AppThemeMode.MOCHI_DARK)) {
            for (titleId in listOf(R.string.script_rename_project, R.string.script_rename_file, R.string.script_import_project)) {
                open(mode, titleId)
                save().assertIsDisplayed().assertIsEnabled().assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
                cancel().assertIsDisplayed().assertWidthIsEqualTo(44.dp).assertHeightIsEqualTo(44.dp)
                if (mode.backend == hk.uwu.mixui.MixUiBackend.Miuix) {
                    compose.onNodeWithTag("mixui-native-glass-sheet").assertIsDisplayed()
                    assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
                    for (id in listOf(R.string.script_cancel, R.string.script_save)) {
                        compose.onNodeWithContentDescription(activity!!.get().getString(id), useUnmergedTree = true)
                            .assertWidthIsEqualTo(28.dp).assertHeightIsEqualTo(28.dp)
                    }
                } else {
                    compose.onNodeWithTag("mochi-glass-sheet").assertIsDisplayed()
                    assertFalse("Mochi naming must not fall back to a dialog",
                        ShadowDialog.getLatestDialog()?.isShowing == true)
                    for (id in listOf(R.string.script_cancel, R.string.script_save)) {
                        compose.onNodeWithContentDescription(activity!!.get().getString(id), useUnmergedTree = true)
                            .assertWidthIsEqualTo(24.dp).assertHeightIsEqualTo(24.dp)
                    }
                }
                val saveBounds = save().fetchSemanticsNode().boundsInRoot
                val cancelBounds = cancel().fetchSemanticsNode().boundsInRoot
                val fieldBounds = input().fetchSemanticsNode().boundsInRoot
                val titleBounds = compose.onNodeWithText(activity!!.get().getString(titleId)).fetchSemanticsNode().boundsInRoot
                assertTrue("Save must be above the name input", saveBounds.bottom <= fieldBounds.top)
                assertTrue("Save and title share the header row", saveBounds.top <= titleBounds.center.y && saveBounds.bottom >= titleBounds.center.y)
                assertTrue("Close must precede Save", cancelBounds.right < saveBounds.left)
                capture("${mode.name}-${titleId}-header")
                input().performTextReplacement("   "); frames()
                save().assertIsNotEnabled()
                if (mode.backend == hk.uwu.mixui.MixUiBackend.Miuix) save().assertHasNoClickAction()
                else save().performClick() // Native Mochi exposes a disabled action that returns false.
                save().performTouchInput { click() }
                assertTrue(saved.isEmpty())
                assertTrue(visible)
                input().performTextReplacement("discarded"); frames()
                cancel().performClick(); frames()
                assertFalse(visible)
                assertTrue(saved.isEmpty())
                compose.runOnUiThread { visible = true }; frames()
                input().assertTextContains(initial)
                input().performTextReplacement("back-discarded"); frames()
                compose.runOnUiThread {
                    val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
                    if (dialog != null) dialog.onBackPressed()
                    else activity!!.get().onBackPressedDispatcher.onBackPressed()
                }
                frames()
                assertFalse("Back dismisses without committing", visible)
                assertTrue(saved.isEmpty())
                compose.runOnUiThread { initial = "different_target"; visible = true }; frames()
                input().assertTextContains("different_target")
                input().performTextReplacement("renamed_target"); frames()
                save().performClick(); frames()
                assertEquals(listOf("renamed_target"), saved)
                assertFalse(visible)
                compose.runOnUiThread { visible = true }; frames()
                input().assertTextContains("different_target")
                cancel().performClick(); frames()
                assertEquals(1, saved.size)
            }
        }
    }
    @Test fun repeatedSaveCommitsOnlyOnceAndNewSessionResetsGuard() {
        assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        hideOnConfirm = false
        open(AppThemeMode.MOCHI_LIGHT, R.string.script_rename_project)
        compose.onNodeWithTag("mochi-glass-sheet").assertIsDisplayed()
        input().performTextReplacement("saved_once"); frames()
        save().performClick(); frames()
        save().assertIsNotEnabled().performTouchInput { click() }
        assertEquals(listOf("saved_once"), saved)
        compose.runOnUiThread { visible = false }
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        compose.runOnUiThread { initial = "next_target"; visible = true }
        frames()
        compose.onAllNodesWithTag("mochi-glass-sheet").assertCountEquals(1)
        input().assertTextContains("next_target")
        save().assertIsEnabled().performClick(); frames()
        assertEquals(listOf("saved_once", "next_target"), saved)
    }

}
