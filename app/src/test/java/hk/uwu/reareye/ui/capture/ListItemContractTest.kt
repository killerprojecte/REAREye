package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.*
import hk.uwu.mixui.components.ListItem
import hk.uwu.mixui.components.ListItemColors
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.contributor.ContributorLoadState
import hk.uwu.reareye.repository.contributor.ContributorProfile
import hk.uwu.reareye.ui.screen.AboutScreenContent
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ListItemContractTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun open(backend: MixUiBackend, dark: Boolean, rtl: Boolean, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    CompositionLocalProvider(LocalLayoutDirection provides
                        if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, content = content)
                }
            }
        }
        settle()
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    private fun capture(name: String) {
        val folder = File(System.getProperty("ui.capture.output"), "list-item").apply { mkdirs() }
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun completeSlotsColorsLongTextMarginsAndIndependentClicks() {
        val long = "First line\nSecond line\nThird line\nFourth line\nFifth line"
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) for (rtl in listOf(false, true)) {
            var parentClicks = 0
            var bottomClicks = 0
            var trailingClicks = 0
            open(backend, dark, rtl) {
                Column {
                    ListItem("Long title\nSecond title line", summary = long,
                        modifier = Modifier.testTag("long"),
                        titleColor = ListItemColors(Color.Red, Color.Blue),
                        summaryColor = ListItemColors(Color.Green, Color.Magenta),
                        insideMargin = PaddingValues(start = 27.dp, end = 13.dp, top = 9.dp, bottom = 19.dp),
                        startAction = { Text("L", Modifier.testTag("leading")) },
                        endActions = { Text("R", Modifier.testTag("trailing").clickable { trailingClicks++ }) },
                        bottomAction = { Text("Bottom", Modifier.testTag("bottom").clickable { bottomClicks++ }) },
                        onClick = { parentClicks++ }, animateContentSize = true)
                    ListItem("Short", summary = "One line", modifier = Modifier.testTag("short"))
                    ListItem("Disabled", summary = "Disabled summary", enabled = false,
                        titleColor = ListItemColors(Color.Red, Color.Blue),
                        summaryColor = ListItemColors(Color.Green, Color.Magenta),
                        modifier = Modifier.testTag("disabled"), onClick = { parentClicks++ },
                        bottomAction = { Text("Independent child", Modifier.testTag("child").clickable { bottomClicks++ }) })
                }
            }
            compose.onNodeWithText(long, useUnmergedTree = true).assertIsDisplayed()
            val row = compose.onNodeWithTag("long").fetchSemanticsNode().boundsInRoot
            val short = compose.onNodeWithTag("short").fetchSemanticsNode().boundsInRoot
            assertTrue("$backend keeps all lines", row.height > short.height + 80f)
            val leading = compose.onNodeWithTag("leading", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val trailing = compose.onNodeWithTag("trailing", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("Relative start padding", 27f, if (rtl) row.right - leading.right else leading.left - row.left, 1f)
            assertTrue("Relative slot order", if (rtl) leading.left > trailing.left else leading.left < trailing.left)
            compose.onNodeWithTag("bottom", useUnmergedTree = true).performTouchInput { click() }
            compose.onNodeWithTag("trailing", useUnmergedTree = true).performTouchInput { click() }
            assertEquals(0, parentClicks)
            assertEquals(1, bottomClicks)
            assertEquals(1, trailingClicks)
            compose.onNodeWithText("Long title\nSecond title line", useUnmergedTree = true).performTouchInput { click() }
            assertEquals(1, parentClicks)
            compose.onNodeWithTag("disabled").assertIsNotEnabled()
            compose.onNodeWithText("Disabled", useUnmergedTree = true).performTouchInput { click() }
            assertEquals(1, parentClicks)
            compose.onNodeWithTag("child", useUnmergedTree = true).performTouchInput { click() }
            assertEquals(2, bottomClicks)
            settle() // Check text colors after the native click indication has completed.
            val pixels = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            val expected = setOf(android.graphics.Color.RED, android.graphics.Color.BLUE,
                android.graphics.Color.GREEN, android.graphics.Color.MAGENTA)
            val found = mutableSetOf<Int>()
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
                val pixel = pixels.getPixel(x, y)
                if (pixel in expected) found += pixel
            }
            assertEquals("Enabled and disabled title/summary colors are rendered", expected, found)
            capture("slots-${backend.name}-$dark-$rtl")
        }
    }


    @Test fun mochiHeldStateChangesHighlightWithoutTriggeringClick() {
        for (dark in listOf(false, true)) {
            var held by mutableStateOf(false)
            var clicks = 0
            open(MixUiBackend.Mochi, dark, false) {
                ListItem("Held row", modifier = Modifier.testTag("held"),
                    holdDownState = held, onClick = { clicks++ })
            }
            val normal = compose.onNodeWithTag("held").captureToImage().asAndroidBitmap().getPixel(2, 2)
            compose.runOnUiThread { held = true }; settle()
            val highlighted = compose.onNodeWithTag("held").captureToImage().asAndroidBitmap().getPixel(2, 2)
            assertNotEquals("Held list highlight", normal, highlighted)
            assertEquals(0, clicks)
            compose.onNodeWithTag("held").performTouchInput { click() }; settle()
            assertEquals(1, clicks)
            compose.runOnUiThread { held = false }; settle()
            assertEquals(normal, compose.onNodeWithTag("held").captureToImage().asAndroidBitmap().getPixel(2, 2))
        }
    }

    @Test fun actualAboutContributorRowsKeepCompleteMultilineDescription() {
        val detail = "Production contributor details\nSecond line\nThird line\nFourth line\nFifth line"
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark, false) {
                AboutScreenContent(ContributorLoadState.Loaded(listOf(ContributorProfile("Long contributor", detail))))
            }
            val title = activity!!.get().getString(R.string.credits_contributors_title)
            fun visible(): Boolean {
                val nodes = compose.onAllNodesWithText(title, useUnmergedTree = true)
                return nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
            }
            var attempts = 0
            while (!visible() && attempts++ < 15) {
                compose.onNodeWithTag("about-root-list").performTouchInput { swipeUp() }; settle()
            }
            compose.onNodeWithText(title).assertIsDisplayed().performClick(); settle()
            compose.onNodeWithText("Long contributor", useUnmergedTree = true).assertIsDisplayed()
            val description = compose.onNodeWithText(detail, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$backend actual page retains five lines", description.height > 70f)
            capture("about-${backend.name}-$dark")
            compose.onNodeWithTag("about-back").performClick(); settle()
            compose.onNodeWithTag("about-root-list").assertIsDisplayed()
        }
    }
}
