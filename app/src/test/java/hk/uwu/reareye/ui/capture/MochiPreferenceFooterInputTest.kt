package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.*
import hk.uwu.mixui.components.*
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

/** Footer input remains part of the preference, while all row/footer visuals stay native. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MochiPreferenceFooterInputTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var scroll: ScrollState
    private val description = "Description one\nDescription two\nDescription three\nDescription four\nDescription five"
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun footer() = compose.onNodeWithText(description, useUnmergedTree = true)
    private fun entry() = compose.onNodeWithTag("entry")
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    private fun open(dark: Boolean, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(MixUiBackend.Mochi, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    GlassPreferenceScene {
                        scroll = rememberScrollState()
                        Column(Modifier.fillMaxSize().testTag("preferences").verticalScroll(scroll)) {
                            Spacer(Modifier.height(120.dp))
                            content()
                            Spacer(Modifier.height(1600.dp))
                        }
                    }
                }
            }
        }
        settle()
    }

    @Test fun actionAndSwitchDescriptionReleaseCancelDragDisabledAndNativeTitleRemainSingleAction() {
        for (switch in listOf(false, true)) for (dark in listOf(false, true)) {
            var events = 0
            val enabled = mutableStateOf(true)
            open(dark) {
                var checked by remember { mutableStateOf(false) }
                if (switch) SwitchPreference(checked = checked,
                    onCheckedChange = { checked = it; events++ }, title = "Native title",
                    summary = description, enabled = enabled.value, modifier = Modifier.testTag("entry"))
                else PreferenceRow("Native title", summary = description, enabled = enabled.value,
                    modifier = Modifier.testTag("entry"), onClick = { events++ })
            }
            footer().performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(100); compose.waitForIdle()
            assertEquals(0, events)
            footer().performTouchInput { cancel() }; settle()
            assertEquals(0, events)
            footer().performTouchInput { swipe(center, Offset(center.x, center.y - 110f), 240) }
            settle()
            assertEquals("Dragging the full description must not activate", 0, events)
            assertTrue("Description drag must scroll the page", scroll.value > 0)
            compose.onNodeWithTag("preferences").performTouchInput {
                swipe(Offset(center.x, center.y * .6f), Offset(center.x, center.y * 1.7f), 500)
            }
            settle()
            assertEquals("Real reverse drag restores the first preference", 0, scroll.value)
            footer().performTouchInput { click() }; settle()
            assertEquals("Description release activates exactly once", 1, events)
            compose.onNodeWithText("Native title", useUnmergedTree = true).performTouchInput { click() }; settle()
            assertEquals("Native child must not also activate its parent", 2, events)
            entry().performSemanticsAction(SemanticsActions.OnClick) { it() }; settle()
            assertEquals("The complete preference exposes its action", 3, events)
            footer().performTouchInput { down(center) }
            compose.runOnUiThread { enabled.value = false }
            compose.waitForIdle()
            footer().performTouchInput { cancel() }; settle()
            entry().assertIsNotEnabled()
            footer().performTouchInput { click() }; settle()
            assertEquals("Disabled description and held cancellation are inert", 3, events)
        }
    }

    @Test fun selectorDescriptionOpensAndRichOptionDescriptionSelectsOnce() {
        for (multiple in listOf(false, true)) for (dark in listOf(false, true)) {
            var selections = 0
            val optionDetail = "Option detail one\nOption detail two\nOption detail three\nOption detail four"
            open(dark) {
                if (multiple) {
                    var expanded by remember { mutableStateOf(false) }
                    GlassChoicePreference("Select title", summary = description,
                        modifier = Modifier.testTag("entry"), expanded = expanded,
                        onExpandedChange = { expanded = it }, multiple = true,
                        options = listOf(ChoiceOption("Current", selected = true), ChoiceOption("Next", optionDetail)),
                        onOptionClick = { selections++ })
                } else GlassSelectPreference("Select title", listOf("Current", "Next"), 0,
                    { selections++ }, modifier = Modifier.testTag("entry"), summary = description)
            }
            footer().performTouchInput { click() }; settle()
            compose.onNodeWithText("Next").assertIsDisplayed()
            if (multiple) compose.onNodeWithText(optionDetail, useUnmergedTree = true).performTouchInput { click() }
            else compose.onNodeWithText("Next").performTouchInput { click() }
            settle()
            assertEquals(1, selections)
            compose.onNodeWithText("Next").assertDoesNotExist()
            footer().assertIsDisplayed()
        }
    }
}
