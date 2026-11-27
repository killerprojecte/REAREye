package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.PreferenceRow
import hk.uwu.mixui.components.Slider
import hk.uwu.mixui.components.GlassTopBarScaffold
import hk.uwu.mixui.components.GlassChoicePreference
import hk.uwu.mixui.components.GlassSelectPreference
import hk.uwu.mixui.components.ChoiceOption
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigOptionControlsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun open(backend: MixUiBackend, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent { MixUiHost(backend, MixColorSchemeMode.Light, content = content) }
        }
        settle()
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    @Test fun preferencePreservesEveryDescriptionLineAndDisabledAction() {
        val long = "First line\nSecond line\nThird line\nFourth line\nFifth line"
        for (backend in MixUiBackend.entries) {
            var clicks = 0
            open(backend) {
                Column {
                    PreferenceRow("Long", summary = long, modifier = Modifier.testTag("long"),
                        onClick = { clicks++ }, holdDownState = true)
                    PreferenceRow("Short", summary = "One line", modifier = Modifier.testTag("short"))
                    PreferenceRow("Disabled", summary = long, enabled = false,
                        modifier = Modifier.testTag("disabled"), onClick = { clicks++ })
                }
            }
            val tall = compose.onNodeWithTag("long").fetchSemanticsNode().boundsInRoot.height
            val short = compose.onNodeWithTag("short").fetchSemanticsNode().boundsInRoot.height
            assertTrue("$backend keeps all five lines", tall > short + 40f)
            compose.onNodeWithTag("long").performClick()
            assertEquals(1, clicks)
            compose.onNodeWithTag("disabled").assertIsNotEnabled().performTouchInput { click() }
            assertEquals(1, clicks)
        }
    }

    @Test fun nativeSliderForwardsRangeStepsAndValueChange() {
        for (backend in MixUiBackend.entries) {
            var received: Float? = null
            open(backend) {
                Slider(value = 2f, onValueChange = { received = it }, valueRange = 0f..10f,
                    steps = 9, modifier = Modifier.testTag("slider"))
            }
            compose.onNodeWithTag("slider").performSemanticsAction(SemanticsActions.SetProgress) { it(7f) }
            assertEquals("$backend native value callback", 7f, received!!, 0.01f)
        }
    }
    @Test fun richMultipleChoicesKeepDescriptionsTicksAndDismissBeforeCallback() {
        for (backend in MixUiBackend.entries) {
            val events = mutableListOf<String>()
            val detail = "Detail one\nDetail two\nDetail three\nDetail four"
            open(backend) {
                var expanded by remember { mutableStateOf(false) }
                GlassTopBarScaffold(title = "Settings", isContentScrolled = false) { padding ->
                    GlassChoicePreference(modifier = Modifier.padding(padding), title = "Options", summary = "Choose independently",
                        expanded = expanded,
                        onExpandedChange = { expanded = it; if (!it) events += "dismiss" },
                        multiple = true,
                        options = listOf(ChoiceOption("First", detail, true), ChoiceOption("Second", selected = true)),
                        onOptionClick = { events += "select:$it" })
                }
            }
            compose.onNodeWithText("Options").performClick(); settle()
            compose.onNodeWithText("First").assertIsOn()
            compose.onNodeWithText("Second").assertIsOn()
            compose.onNodeWithText(detail, useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("Second").performClick(); settle()
            assertEquals(listOf("dismiss", "select:1"), events.take(2))
            compose.onNodeWithText("Options").performClick(); settle()
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; settle()
            assertEquals(1, events.count { it.startsWith("select:") })
        }
    }

    @Test fun nativeSingleChoiceSelectsAndEmptyListCannotOpen() {
        for (backend in MixUiBackend.entries) {
            val selections = mutableListOf<Int>()
            open(backend) {
                GlassTopBarScaffold(title = "Settings", isContentScrolled = false) { padding ->
                    Column(Modifier.padding(padding)) {
                        GlassSelectPreference("Mode", listOf("Alpha", "Beta"), 0, { selections += it })
                        GlassSelectPreference("Empty", emptyList(), -1, { selections += it })
                    }
                }
            }
            compose.onNodeWithText("Empty").assertIsNotEnabled()
            compose.onNodeWithText("Mode").performClick(); settle()
            compose.onNodeWithText("Beta").performClick(); settle()
            assertEquals(listOf(1), selections)
        }
    }

}
