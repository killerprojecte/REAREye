package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.components.ColorPalette
import hk.uwu.mixui.components.ColorPicker
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.config.CustomBoundsColorEditor
import hk.uwu.reareye.ui.components.config.template.ColorValueEditor
import org.junit.After
import org.junit.Assert.*
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

/** Actual production editor bodies, not full-page navigation or physical-device evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ColorEditorInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @Before fun captureMode() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun open(backend: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                CaptureTheme(backend, dark) {
                    CaptureDialogHost {
                        Column(Modifier.fillMaxWidth().padding(16.dp).testTag("color-fixture")) { content() }
                    }
                }
            }
        }
        settle()
    }
    private fun progress(labelRes: Int, value: Float, inDialog: Boolean = false) {
        val scope = if (inDialog) hasAnyAncestor(isDialog()) else SemanticsMatcher("any window") { true }
        compose.onNode(hasContentDescription(activity!!.get().getString(labelRes)) and scope and
            SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(value)) }
        settle()
    }
    private fun save(backend: String, dark: Boolean, name: String, dialog: Boolean = false) {
        val folder = File(System.getProperty("ui.capture.output"),
            "color-editors/$backend/${if (dark) "dark" else "light"}").apply { mkdirs() }
        val bitmap = if (dialog) {
            compose.runOnUiThread {
                captureDialogWindowBitmap(requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()))
            }
        } else compose.onNodeWithTag("color-fixture").captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(folder, "evidence.txt").writeText(
            "Robolectric native graphics / real production editor bodies.\n" +
            "Not full-page navigation, persistence integration, or physical-device evidence.\n")
    }

    @Test fun mochiPalettePointerSelectionPreservesAlphaAndExternalSelection() {
        for (dark in listOf(false, true)) {
            val color = mutableStateOf(Color.Red.copy(alpha = 0.4f))
            var callbacks = 0
            open("mochi", dark) { ColorPalette(color.value, { color.value = it; callbacks++ }) }
            val green = compose.onNode(hasContentDescription("#FF00FF00") and hasClickAction())
            green.performTouchInput { click(center) }
            settle()
            assertEquals(Color.Green.copy(alpha = 0.4f).toArgb(), color.value.toArgb())
            assertEquals(1, callbacks)
            green.assertIsSelected()
            save("mochi", dark, "palette-pointer-green")
            compose.runOnUiThread { color.value = Color.Blue.copy(alpha = 0.7f) }
            settle()
            compose.onNode(hasContentDescription("#FF0000FF") and hasClickAction()).assertIsSelected()
            green.assertIsNotSelected()
            assertEquals("External input must not emit a callback", 1, callbacks)
            progress(hk.uwu.mixui.R.string.mixui_color_alpha, 0.25f)
            assertEquals(0.25f, color.value.alpha, 0.005f)
            assertEquals(Color.Blue.copy(alpha = 0.25f).toArgb(), color.value.toArgb())
        }
    }

    @Test fun mochiNativeSliderPointerSemanticsExternalUpdateAndBlackRecovery() {
        for (dark in listOf(false, true)) {
            val color = mutableStateOf(Color.Red.copy(alpha = 0.6f))
            var callbacks = 0
            open("mochi", dark) { ColorPicker(color.value, { color.value = it; callbacks++ }) }
            val hue = compose.onNode(hasContentDescription(activity!!.get().getString(hk.uwu.mixui.R.string.mixui_color_hue)) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            hue.performTouchInput { swipe(Offset(width * 0.1f, center.y), Offset(width * 0.65f, center.y), 320) }
            settle()
            assertTrue("Native pointer gesture must invoke the callback", callbacks > 0)
            assertNotEquals(Color.Red.copy(alpha = 0.6f).toArgb(), color.value.toArgb())
            progress(hk.uwu.mixui.R.string.mixui_color_hue, 210f)
            progress(hk.uwu.mixui.R.string.mixui_color_brightness, 0f)
            assertEquals(Color.Black.copy(alpha = 0.6f).toArgb(), color.value.toArgb())
            // A hue change while black cannot be recovered by RGB -> HSV conversion.
            progress(hk.uwu.mixui.R.string.mixui_color_hue, 120f)
            progress(hk.uwu.mixui.R.string.mixui_color_alpha, 0.3f)
            progress(hk.uwu.mixui.R.string.mixui_color_brightness, 1f)
            assertEquals(Color.Green.copy(alpha = 0.3f).toArgb(), color.value.toArgb())
            save("mochi", dark, "picker-black-recovered")
            val count = callbacks
            compose.runOnUiThread { color.value = Color.Blue.copy(alpha = 0.8f) }
            settle()
            assertEquals(count, callbacks)
            val hueRange = hue.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
            assertEquals(240f, hueRange.current, 0.1f)
            progress(hk.uwu.mixui.R.string.mixui_color_alpha, 0.5f)
            assertEquals(Color.Blue.copy(alpha = 0.5f).toArgb(), color.value.toArgb())
            save("mochi", dark, "picker-external-update")
        }
    }

    @Test fun miuixDelegatedPickerNativePointerForwardsAlpha() {
        for (dark in listOf(false, true)) {
            val color = mutableStateOf(Color.Red.copy(alpha = 0.8f))
            var callbacks = 0
            open("miuix", dark) {
                ColorPicker(color.value, { color.value = it; callbacks++ }, Modifier.testTag("native-picker"))
            }
            // MIUIX ColorPicker has no SetProgress semantics. Its public layout ends
            // in a 26dp alpha track; exercise the vendor's actual pointer handler.
            compose.onNodeWithTag("native-picker").performTouchInput {
                // ea1b935 ColorSlider exposes a draggable track, not a tap handler.
                swipe(Offset(width * 0.8f, height - 13f),
                    Offset(width * 0.25f, height - 13f), durationMillis = 400)
            }
            settle()
            assertTrue("Delegated native alpha track must emit a color", callbacks > 0)
            assertTrue(color.value.alpha in 0.15f..0.35f)
            assertTrue(color.value.red > 0.95f)
            assertTrue(color.value.green < 0.05f && color.value.blue < 0.05f)
            save("miuix", dark, "picker-pointer-alpha")
        }
    }

    @Test fun productionCustomBoundsEditorCommitsAndReflectsExternalValue() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            val value = mutableStateOf("#66FF0000")
            var callbacks = 0
            open(backend, dark) {
                CustomBoundsColorEditor(value.value, { value.value = it; callbacks++ }, false)
            }
            compose.onNode(hasSetTextAction()).performTextReplacement("#800000FF")
            settle()
            assertEquals("#800000FF", value.value)
            assertTrue(callbacks > 0)
            if (backend == "mochi") {
                compose.onNode(hasContentDescription("#FF00FF00") and hasClickAction())
                    .performTouchInput { click(center) }
                settle()
                assertEquals("#8000FF00", value.value.uppercase())
            }
            val count = callbacks
            compose.runOnUiThread { value.value = "#99FFFF00" }
            settle()
            compose.onNode(hasSetTextAction()).assertTextContains("#99FFFF00")
            assertEquals(count, callbacks)
            save(backend, dark, "custom-bounds-editor")
        }
    }

    @Test fun productionTemplateEditorAdvancedCommitsImmediatelyAndConfirmOnlyCloses() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            val value = mutableStateOf("#66FF0000")
            val writes = mutableListOf<String>()
            open(backend, dark) {
                ColorValueEditor("Fixture color", listOf("#FF0000", "#0000FF"), value.value,
                    { value.value = it; writes += it })
            }
            compose.onNode(hasSetTextAction()).performTextReplacement("#800000FF")
            settle()
            assertEquals("#800000FF", value.value.uppercase())
            save(backend, dark, "template-editor")
            val open = activity!!.get().getString(R.string.rear_widget_card_template_open_color_picker)
            val confirm = activity!!.get().getString(R.string.rear_widget_confirm)
            compose.onNodeWithText(open).performTouchInput { click(center) }
            settle()
            compose.onNodeWithText(confirm).assertIsDisplayed()
            if (backend == "mochi") {
                progress(hk.uwu.mixui.R.string.mixui_color_hue, 120f, inDialog = true)
                assertEquals("#8000FF00", value.value.uppercase())
                progress(hk.uwu.mixui.R.string.mixui_color_alpha, 0.25f, inDialog = true)
                assertEquals("#4000FF00", value.value.uppercase())
            }
            save(backend, dark, "template-advanced", dialog = true)
            val count = writes.size
            val committed = value.value
            compose.onNodeWithText(confirm).performClick()
            settle()
            compose.onNodeWithText(confirm).assertDoesNotExist()
            assertEquals("Confirm closes, it must not commit again", count, writes.size)
            assertEquals(committed, value.value)
            compose.onNodeWithText(open).performClick()
            settle()
            compose.onNodeWithText(confirm).assertIsDisplayed()
            if (backend == "mochi") {
                progress(hk.uwu.mixui.R.string.mixui_color_hue, 240f, inDialog = true)
                assertEquals("#400000FF", value.value.uppercase())
            }
            val beforeBack = value.value
            val beforeBackCount = writes.size
            compose.runOnUiThread {
                org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed()
            }
            settle()
            compose.onNodeWithText(confirm).assertDoesNotExist()
            assertEquals("Dismiss does not roll back already committed colors", beforeBack, value.value)
            assertEquals(beforeBackCount, writes.size)
        }
    }
}
