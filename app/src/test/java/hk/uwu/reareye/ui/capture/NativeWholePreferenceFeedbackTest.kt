package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.mixui.components.GlassSelectPreference
import hk.uwu.mixui.components.PreferenceRow
import hk.uwu.mixui.components.PreferenceSupportingText
import hk.uwu.mixui.components.SwitchPreference
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/** Runs with the existing -PuiCapture=true app Robolectric native-graphics harness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeWholePreferenceFeedbackTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var clicks = 0
    private val description = List(6) { "Full description line $it." }.joinToString("\n")

    // Drive both native coroutine delay (main looper) and Compose animation frames.
    // No sleeps: a held pointer remains held while the native delay elapses.
    private fun advance(ms: Long = 500) {
        // Mochi attaches a new ComposeView to DecorView during composition.
        // Its Android traversal must run BETWEEN Compose frames so containerSize
        // and origin can feed the next overlay composition. Advancing either
        // clock in one bulk chunk starves the other scheduler until the end.
        // Keep the same total duration, interleave at frame cadence instead.
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            compose.mainClock.advanceTimeBy(step, ignoreFrameDuration = true)
            compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step)) }
            compose.waitForIdle()
            remaining -= step
        }
    }
    private fun open(content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(MixUiBackend.Mochi, MixColorSchemeMode.Light) {
                    GlassPreferenceScene {
                        Box(Modifier.width(320.dp).background(Color.White)) { content() }
                    }
                }
            }
        }
        advance()
    }
    private fun action(enabled: Boolean = true, scroll: Boolean = false) {
        open {
            Column(if (scroll) Modifier.height(360.dp).verticalScroll(rememberScrollState()) else Modifier) {
                PreferenceRow("Title", Modifier.testTag("preference"), summary = description,
                    enabled = enabled, onClick = { clicks++ })
                if (scroll) Box(Modifier.height(800.dp))
            }
        }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    private fun snapshot(row: SemanticsNodeInteraction, name: String) {
        val file = File("build/outputs/preference-feedback/$name.png")
        file.parentFile.mkdirs()
        file.outputStream().use { row.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertFullDescription() {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(description, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        layouts.forEach {
            assertFalse(it.hasVisualOverflow)
            assertEquals(description.length, it.getLineEnd(it.lineCount - 1))
        }
    }

    @Test fun titleAndDescriptionHitNativeBackgroundExactlyOnce() {
        action()
        val row = compose.onNodeWithTag("preference")
        row.performTouchInput { click(Offset(width / 2f, 20f)) }; advance()
        row.performTouchInput { click(Offset(width / 2f, height - 20f)) }; advance()
        compose.runOnIdle { assertEquals(2, clicks) }
        assertFullDescription()
        row.assertContentDescriptionEquals("Title")
        row.assertTextEquals(description)
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
        row.performSemanticsAction(SemanticsActions.OnClick); advance()
        compose.runOnIdle { assertEquals(3, clicks) }
    }

    @Test fun descriptionHoldLightsWholeNativeRowAndReleaseFades() {
        action()
        val row = compose.onNodeWithTag("preference")
        val idle = row.captureToImage().toPixelMap()
        snapshot(row, "idle")
        row.performTouchInput { down(Offset(width / 2f, height - 20f)) }
        advance(150)
        val held = row.captureToImage().toPixelMap()
        snapshot(row, "held")
        assertNotEquals("Description pointer must reach native background row", idle[2, 2], held[2, 2])
        assertEquals("Title and footer must have the same highlight", held[2, 2], held[2, held.height - 2])
        compose.runOnIdle { assertEquals(0, clicks) }
        row.performTouchInput { up() }; advance()
        val released = row.captureToImage().toPixelMap()
        snapshot(row, "released")
        assertEquals(idle[2, 2], released[2, 2])
        assertEquals(idle[2, idle.height - 2], released[2, released.height - 2])
        compose.runOnIdle { assertEquals(1, clicks) }
    }

    @Test fun canceledDescriptionHoldClearsHighlightWithoutClick() {
        action()
        val row = compose.onNodeWithTag("preference")
        val idle = row.captureToImage().toPixelMap()
        row.performTouchInput { down(Offset(width / 2f, height - 20f)) }; advance(150)
        row.performTouchInput { cancel() }; advance()
        assertEquals(idle[2, 2], row.captureToImage().toPixelMap()[2, 2])
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun descriptionScrollCancelsNativeRowGesture() {
        action(scroll = true)
        compose.onNodeWithTag("preference").performTouchInput {
            swipe(Offset(width / 2f, height - 30f), Offset(width / 2f, 25f), 250)
        }
        advance()
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun disabledTitleDescriptionAndAccessibilityCannotActivate() {
        action(enabled = false)
        val row = compose.onNodeWithTag("preference")
        row.assertIsNotEnabled()
        row.performTouchInput { click(Offset(width / 2f, 20f)) }
        row.performTouchInput { click(Offset(width / 2f, height - 20f)) }
        row.performSemanticsAction(SemanticsActions.OnClick); advance()
        compose.runOnIdle { assertEquals(0, clicks) }
    }

    @Test fun publicSwitchDescriptionAndNativeControlEachToggleOnce() {
        open {
            var checked by remember { mutableStateOf(false) }
            SwitchPreference(checked, { checked = it; clicks++ }, "Switch title",
                modifier = Modifier.testTag("switch-preference"), summary = description)
        }
        val row = compose.onNodeWithTag("switch-preference")
        row.assertContentDescriptionEquals("Switch title")
        row.assertTextEquals(description)
        row.performTouchInput { click(Offset(width / 2f, height - 20f)) }; advance()
        row.assertIsOn()
        compose.runOnIdle { assertEquals(1, clicks) }
        // Actual native trailing switch center (row padding 16dp, native width about 51dp).
        row.performTouchInput { click(Offset(width - 40f, 28f)) }; advance()
        row.assertIsOff()
        compose.runOnIdle { assertEquals(2, clicks) }
    }

    @Test fun publicPickerDescriptionOpensAndSelectionPersistsOnce() {
        open {
            var selected by remember { mutableStateOf(0) }
            GlassSelectPreference("Picker title", listOf("First choice", "Second choice"), selected,
                onSelectedIndexChange = { selected = it; clicks++ },
                modifier = Modifier.testTag("picker"),
                supportingText = PreferenceSupportingText(description, if (selected == 0) "First choice" else "Second choice"))
        }
        assertFullDescription()
        val row = compose.onNodeWithTag("picker")
        compose.onNodeWithText(description, useUnmergedTree = true).assertWidthIsAtLeast(280.dp)
        snapshot(row, "picker-wide-description")
        row.assertContentDescriptionEquals("Picker title")
        row.assertTextEquals(description)
        row.performTouchInput { click(Offset(width / 2f, height - 20f)) }; advance()
        try {
            compose.onNodeWithText("Second choice").assertIsDisplayed()
        } catch (failure: AssertionError) {
            // Distinguish absent overlay, zero/unplaced Android root, and a
            // clipped/transparent menu before changing production behavior.
            compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().indices.forEach { index ->
                println("WHOLE-PREFERENCE ROOT $index " +
                    compose.onAllNodes(isRoot(), useUnmergedTree = true)[index].printToString())
            }
            compose.runOnUiThread {
                fun dump(view: android.view.View, depth: Int) {
                    println("WHOLE-PREFERENCE VIEW ${" ".repeat(depth)}${view.javaClass.name} " +
                        "bounds=${view.left},${view.top},${view.right},${view.bottom} " +
                        "shown=${view.isShown} alpha=${view.alpha} attached=${view.isAttachedToWindow}")
                    if (view is android.view.ViewGroup) for (index in 0 until view.childCount) dump(view.getChildAt(index), depth + 1)
                }
                dump(activity!!.get().window.decorView, 0)
            }
            throw failure
        }
        compose.onNodeWithText("Second choice").performTouchInput { click() }; advance()
        compose.runOnIdle { assertEquals(1, clicks) }
        // Native trailing picker is an independent accessible button. Its text
        // is intentionally not merged into the outer description action. Scope
        // to this row's actual descendants, excluding any exiting popup clone.
        // Full-row action is labelled without repeating the selected capsule.
        row.assertContentDescriptionEquals("Picker title")
        row.assertTextEquals(description)
        val inPicker = hasAnyAncestor(hasTestTag("picker"))
        compose.onNode(hasText("Picker title") and inPicker, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("Second choice") and inPicker, useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("First choice") and inPicker, useUnmergedTree = true).assertDoesNotExist()
        row.performTouchInput { click(Offset(width / 2f, height - 20f)) }; advance()
        compose.onNodeWithText("First choice").assertIsDisplayed()
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }; advance()
        compose.runOnIdle { assertEquals(1, clicks) }
    }
}
