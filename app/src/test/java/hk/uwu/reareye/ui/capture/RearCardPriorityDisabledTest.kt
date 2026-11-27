package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.AnnotatedString
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearCardConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.config.CardManagerScreen
import hk.uwu.reareye.ui.components.config.ConfigDashboardAction
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
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

/** Actual populated CardManager create/edit sheets, using memory prefs, not device/IME evidence. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RearCardPriorityDisabledTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val action = mutableStateOf<ConfigDashboardAction?>(null)

    @After fun close() { compose.runOnUiThread { activity?.close(); activity = null } }

    private fun settle() {
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    private fun host(backend: String, dark: Boolean) {
        val prefs = PrefsManager(GuideMemoryPrefs()).also {
            it.putString(ConfigKeys.REAR_WIDGET_CARD_DATA, RearWidgetConfigCodec.encodeCards(listOf(
                RearCardConfig(id = "disabled-card", title = "Priority contract card",
                    packageName = "fixture.target", business = "clock", priority = 642),
            )))
            it.putString(ConfigKeys.REAR_WIDGET_BUSINESS_DATA, RearWidgetConfigCodec.encodeBusinesses(listOf(
                RearBusinessConfig(id = "disabled-business", packageName = "fixture.target",
                    business = "clock", filePath = "/fixture/clock", defaultPriority = 642),
            )))
        }
        compose.runOnUiThread {
            activity?.close()
            action.value = null
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                CaptureTheme(backend, dark) {
                    CardManagerScreen(prefs, {}, embedded = true, actionRequest = action.value,
                        onActionHandled = { action.value = null })
                }
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("rear-card-item-disabled-card").fetchSemanticsNodes().isNotEmpty()
        }
        settle()
    }

    private fun editor(tag: String) = compose.onNode(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText) and
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)
    private fun SemanticsNodeInteraction.assertNotInputFocused() = assert(
        SemanticsMatcher("Input is not focused (including removed disabled focus semantics)") {
            it.config.getOrNull(SemanticsProperties.Focused) != true
        })
    private fun assertEditingRejected(tag: String) {
        val config = editor(tag).fetchSemanticsNode().config
        compose.runOnIdle {
            // Both missing actions and native handlers returning false are valid disabled contracts.
            assertFalse(config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("999")) ?: false)
            assertFalse(config.getOrNull(SemanticsActions.InsertTextAtCursor)?.action?.invoke(AnnotatedString("9")) ?: false)
        }
    }
    private fun reveal(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        settle()
    }
    private fun toggleAutomatic() {
        val label = activity!!.get().getString(R.string.rear_widget_priority_mode_auto)
        compose.onNodeWithText(label).performScrollTo().performClick()
        settle()
    }
    private fun value(tag: String) = editor(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** Save the actual full page, compare only the current real field's local pixels. */
    private fun captureField(name: String, tag: String): Bitmap {
        reveal(tag)
        val window = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        val directory = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"),
            "sheet-disabled-controls/ACTUAL_CARD_PAGES").apply { mkdirs() }
        File(directory, "SOURCE.txt").writeText("Actual CardManagerScreen create/edit sheets with memory preferences. " +
            "Robolectric native pixels; no physical-device shader or IME claim.\n")
        File(directory, "$name.png").outputStream().use { check(window.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
        assertTrue("The whole priority input must be visible", bounds.top >= 0 && bounds.bottom <= window.height)
        return try { Bitmap.createBitmap(window, bounds.left.roundToInt(), bounds.top.roundToInt(),
            bounds.width.roundToInt(), bounds.height.roundToInt()) } finally { window.recycle() }
    }

    private fun verifyPriority(backend: String, dark: Boolean, kind: String) {
        val tag = "rear-card-$kind-priority"
        reveal(tag)
        editor(tag).assertIsNotEnabled().assertNotInputFocused()
        val original = value(tag)
        assertEditingRejected(tag)
        editor(tag).performTouchInput { click() }
        settle()
        editor(tag).assertNotInputFocused()
        assertEquals(original, value(tag))
        toggleAutomatic()
        reveal(tag)
        editor(tag).assertIsEnabled().performTextReplacement("731")
        settle()
        assertEquals("731", value(tag))
        val prefix = "$backend-${if (dark) "dark" else "light"}-$kind"
        val enabled = captureField("$prefix-manual", tag)
        toggleAutomatic()
        reveal(tag)
        editor(tag).assertIsNotEnabled().assertNotInputFocused()
        assertEditingRejected(tag)
        assertEquals("731", value(tag))
        val disabled = captureField("$prefix-automatic", tag)
        try {
            assertEquals("Disabling must not resize the field", enabled.width, disabled.width)
            assertEquals("Disabling must not change native field height", enabled.height, disabled.height)
            var differences = 0
            // Exclude the focus outline: interior pixels, not merely cursor/border, must change.
            for (y in 8 until enabled.height - 8) for (x in 16 until enabled.width - 16) {
                val a = enabled.getPixel(x, y)
                val b = disabled.getPixel(x, y)
                val delta = maxOf(abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)),
                    abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)),
                    abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)))
                if (delta > 3) differences++
            }
            assertTrue("Actual disabled input interior must visibly differ, not just enabled=false: $differences",
                differences > enabled.width * enabled.height / 100)
        } finally { enabled.recycle(); disabled.recycle() }
        toggleAutomatic()
        reveal(tag)
        editor(tag).assertIsEnabled()
        assertEquals("Re-enabling keeps the unsaved manual draft", "731", value(tag))
        editor(tag).performTextReplacement("864")
        settle()
        assertEquals("864", value(tag))
    }

    @Test fun actualCreateAndEditPrioritiesAreVisiblyDisabledUneditableAndPreserveDraftsInBothBackendsAndSchemes() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            host(backend, dark)
            compose.onNodeWithTag("CardManagerScreen-list").performScrollToKey("disabled-card")
            compose.onNodeWithText("Priority contract card").performTouchInput { click() }
            settle()
            verifyPriority(backend, dark, "edit")
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            settle()
            compose.onNodeWithTag("rear-card-edit-dialog").assertDoesNotExist()
            compose.runOnIdle { action.value = ConfigDashboardAction.ADD_CARD }
            settle()
            verifyPriority(backend, dark, "create")
        }
    }
}
