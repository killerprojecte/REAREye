package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.ChoiceOption
import hk.uwu.mixui.components.GlassChoicePreference
import hk.uwu.mixui.components.GlassPreferenceScene
import hk.uwu.mixui.components.GlassSelectPreference
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

/** Real pointer input on the small native trigger; whole-window close frames include overlays. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MochiPreferenceAnchorTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var scroll: ScrollState
    private var selections = 0
    private val title = "Lyrics mode"
    private val current = "Current A"
    private val next = "Pick B"
    private val description = "Description one\nDescription two\nDescription three\nDescription four"

    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    private fun settle() { repeat(16) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun chip() = compose.onNodeWithText(current, useUnmergedTree = true)
    private fun titleNode() = compose.onNodeWithText(title, useUnmergedTree = true)
    private fun noPopup() {
        compose.onNodeWithText(next).assertDoesNotExist()
        assertEquals("A cancelled or scrolling gesture cannot select", 0, selections)
    }
    private fun open(multiple: Boolean, dark: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            selections = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(MixUiBackend.Mochi, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    GlassPreferenceScene {
                        scroll = rememberScrollState()
                        Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("preferences")) {
                            Spacer(Modifier.height(120.dp))
                            Card {
                                if (multiple) {
                                    var expanded by remember { mutableStateOf(false) }
                                    GlassChoicePreference(title = title, summary = description,
                                        options = listOf(ChoiceOption(current, selected = true), ChoiceOption(next)),
                                        multiple = true, expanded = expanded,
                                        onExpandedChange = { expanded = it },
                                        onOptionClick = { selections++ })
                                } else {
                                    GlassSelectPreference(title, listOf(current, next), 0,
                                        { selections++ }, summary = description)
                                }
                            }
                            Spacer(Modifier.height(1600.dp))
                        }
                    }
                }
            }
        }
        settle()
        compose.onNodeWithText(description, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun smallAnchorDownCancelNeverOpensAndFreshReleaseStillWorks() {
        for (multiple in listOf(false, true)) for (dark in listOf(false, true)) {
            open(multiple, dark)
            chip().performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
            noPopup() // Short DOWN must not open, even before the scroll detector can act.
            chip().performTouchInput { cancel() }
            settle()
            noPopup() // Deliberately catches the vendor LongPress cancellation boundary.
            chip().performTouchInput { click(center) }
            settle()
            compose.onNodeWithText(next).assertIsDisplayed().performTouchInput { click() }
            settle()
            assertEquals(1, selections)
        }
    }

    @Test fun smallAnchorVerticalDragScrollsWithoutOpeningOrSelecting() {
        for (multiple in listOf(false, true)) for (dark in listOf(false, true)) {
            println("ANCHOR-PHASE $multiple/$dark open")
            open(multiple, dark)
            println("ANCHOR-PHASE $multiple/$dark swipe")
            chip().performTouchInput {
                swipe(center, Offset(center.x, center.y - 110f), durationMillis = 240)
            }
            settle()
            noPopup()
            assertTrue("Native trailing trigger must let its parent scroll", scroll.value > 0)
            println("ANCHOR-PHASE $multiple/$dark scroll-to-title value=${scroll.value}")
            compose.onNodeWithTag("preferences").performTouchInput {
                swipe(Offset(center.x, center.y * .6f), Offset(center.x, center.y * 1.7f), durationMillis = 500)
            }
            settle()
            assertEquals("Real reverse drag restores top", 0, scroll.value)
            println("ANCHOR-PHASE $multiple/$dark click")
            chip().performTouchInput { click(center) }
            settle()
            compose.onNodeWithText(next).assertIsDisplayed()
            println("ANCHOR-PHASE $multiple/$dark back")
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            settle()
            noPopup()
        }
    }

    @Test fun nativeLongPressOpensWhileFingerIsStillDown() {
        for (multiple in listOf(false, true)) for (dark in listOf(false, true)) {
            open(multiple, dark)
            chip().performTouchInput { down(center) }
            val started = compose.mainClock.currentTime
            val holdTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
            val deadline = started + holdTimeout + 2_000L
            var firstNodeAt: Long? = null
            // The native pointer timeout, compatibility frame, overlay attachment/
            // measurement and menu content springs are separate scheduling steps.
            // Pump frames AND Android's due work while the original finger stays
            // DOWN. A bulk clock jump plus one frame cannot prove visual readiness.
            while (compose.mainClock.currentTime < deadline && !compose.onNodeWithText(next).isDisplayed()) {
                compose.mainClock.advanceTimeByFrame()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                compose.waitForIdle()
                if (firstNodeAt == null && compose.onAllNodesWithText(next).fetchSemanticsNodes().isNotEmpty()) {
                    firstNodeAt = compose.mainClock.currentTime - started
                }
            }
            println("ANCHOR-HOLD $multiple/$dark timeout=$holdTimeout firstNodeMs=$firstNodeAt " +
                "checkedAtMs=${compose.mainClock.currentTime - started} " +
                "displayed=${compose.onNodeWithText(next).isDisplayed()}")
            // This assertion is deliberately BEFORE UP. A release-only gate must fail it.
            compose.onNodeWithText(next).assertIsDisplayed()
            assertEquals(0, selections)
            compose.onNodeWithTag("preferences").performTouchInput { up() }
            settle()
            compose.onNodeWithText(next).assertIsDisplayed()
            assertEquals("Releasing a stationary long press does not select", 0, selections)
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            settle()
            noPopup()
        }
    }

    @Test fun accessibilityAfterTouchCancellationDoesNotRequireAnotherPointerRelease() {
        for (multiple in listOf(false, true)) for (dark in listOf(false, true)) {
            open(multiple, dark)
            chip().performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(64); compose.waitForIdle()
            chip().performTouchInput { cancel() }; settle()
            noPopup()
            // Select the actual native trailing control, not its outer native title row.
            compose.onNode(hasClickAction() and hasAnyDescendant(hasText(current)) and
                !hasAnyDescendant(hasText(title)), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnClick) { it() }
            settle()
            compose.onNodeWithText(next).assertIsDisplayed()
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            settle()
            noPopup()
            chip().performTouchInput { click() }; settle()
            compose.onNodeWithText(next).performTouchInput { click() }; settle()
            assertEquals("Fresh tap remains usable after cancellation and accessibility", 1, selections)
        }
    }

    private fun window(): Bitmap = compose.runOnUiThread {
        captureWindowBitmap(activity!!.get().window)
    }
    private fun save(bitmap: Bitmap, name: String) {
        if (!java.lang.Boolean.getBoolean("ui.capture.enabled")) return
        val directory = File(System.getProperty("ui.capture.output", "build/reports/ui-capture"), "mochi-preference-anchor")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }
    private fun luminance(pixel: Int): Double =
        .2126 * android.graphics.Color.red(pixel) + .7152 * android.graphics.Color.green(pixel) +
            .0722 * android.graphics.Color.blue(pixel)
    private fun edge(bitmap: Bitmap, x: Int, y: Int): Double =
        abs(luminance(bitmap.getPixel(x, y)) - luminance(bitmap.getPixel(x + 1, y)))

    @Test fun nativeCloseFramesNeverReplaceTheTitleWithAnEntireRowBlob() {
        for (multiple in listOf(false, true)) for (dark in listOf(false, true)) {
            for (dismissal in listOf("select", "back", "outside")) {
                open(multiple, dark)
                val label = titleNode().fetchSemanticsNode()
                val origin = IntArray(2)
                compose.runOnUiThread { activity!!.get().window.decorView.getLocationOnScreen(origin) }
                val x = label.positionOnScreen.x.roundToInt() - origin[0]
                val y = label.positionOnScreen.y.roundToInt() - origin[1]
                // The normal menu may temporarily cover the row's trailing portion.
                // Its leftmost 32dp title glyphs are outside a correctly anchored menu.
                val width = minOf(32, label.size.width - 1)
                val height = label.size.height
                val baseline = window()
                val glyphEdges = buildList {
                    for (dy in 0 until height) for (dx in 0 until width) {
                        if (edge(baseline, x + dx, y + dy) >= 35.0) add((x + dx) to (y + dy))
                    }
                }
                assertTrue("PixelCopy must contain actual title glyphs", glyphEdges.size >= 12)
                val prefix = "${if (multiple) "multiple" else "single"}-${if (dark) "dark" else "light"}-$dismissal"
                save(baseline, "$prefix-baseline")
                chip().performTouchInput { click() }; settle()
                compose.onNodeWithText(next).assertIsDisplayed()
                when (dismissal) {
                    "select" -> compose.onNodeWithText(next).performTouchInput { click() }
                    "back" -> compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
                    else -> compose.onAllNodes(isRoot(), useUnmergedTree = true).onLast().performTouchInput {
                        click(Offset(8f, 8f))
                    }
                }
                repeat(48) { frame ->
                    compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
                    val shot = window()
                    save(shot, "$prefix-close-${frame.toString().padStart(2, '0')}")
                    val remaining = glyphEdges.count { (px, py) -> edge(shot, px, py) >= 20.0 }
                    assertTrue("$prefix frame $frame replaced the row title with a blank surface: $remaining/${glyphEdges.size}",
                        remaining >= glyphEdges.size * .5)
                }
                settle()
                compose.onNodeWithText(next).assertDoesNotExist()
                compose.onNodeWithText(description, useUnmergedTree = true).assertIsDisplayed()
                assertEquals(if (dismissal == "select") 1 else 0, selections)
            }
        }
    }
}
