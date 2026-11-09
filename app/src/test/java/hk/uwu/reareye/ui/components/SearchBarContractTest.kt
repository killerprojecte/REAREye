package hk.uwu.reareye.ui.components

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.SearchBar
import hk.uwu.mixui.components.SearchBarStyle
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

/** Real backend controls. No fake input implementation; keyboard hide is observed at its local. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchBarContractTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var query by mutableStateOf("")
    private val focusEvents = mutableListOf<Boolean>()
    private var submits = 0
    private var hides = 0
    private val keyboard = object : SoftwareKeyboardController {
        override fun show() = Unit
        override fun hide() { hides++ }
    }

    @Before fun requireExplicitUiRun() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }

    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }

    private fun frames() {
        repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }

    private fun host(backend: MixUiBackend, style: SearchBarStyle, dark: Boolean) {
        compose.runOnUiThread {
            activity?.close()
            query = ""
            focusEvents.clear()
            submits = 0
            hides = 0
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend = backend,
                    colorMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                        Column(Modifier.fillMaxWidth().padding(12.dp).testTag("search-scene")) {
                            SearchBar(query = query, hint = "Find modules", style = style,
                                onQueryChange = { query = it },
                                onSearchSubmit = { submits++ },
                                onSearchFocusChange = { focusEvents += it })
                        }
                    }
                }
            }
        }
        frames()
    }

    private fun field() = compose.onNode(hasSetTextAction())

    private fun capture(name: String) {
        val output = System.getProperty("ui.capture.output")
        require(!output.isNullOrBlank()) { "Set ui.capture.output to preserve search evidence" }
        val folder = File(output, "search-bars").apply { mkdirs() }
        val bitmap = compose.onNodeWithTag("search-scene").captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
        File(folder, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun inputSubmitFocusClearAndRepeatedEntryAcrossBackendsStylesAndThemes() {
        for (backend in MixUiBackend.entries) for (style in SearchBarStyle.entries)
            for (dark in listOf(false, true)) {
                host(backend, style, dark)
                val name = "${backend.name}-${style.name}-${if (dark) "dark" else "light"}"
                compose.onNodeWithText("Find modules", useUnmergedTree = true).assertIsDisplayed()
                capture("$name-empty")
                repeat(2) { entry ->
                    field().performClick()
                    frames()
                    field().assertIsFocused()
                    compose.runOnIdle { assertEquals(true, focusEvents.lastOrNull()) }
                    field().performTextReplacement("query-$entry")
                    frames()
                    compose.runOnIdle { assertEquals("query-$entry", query) }
                    capture("$name-focused-$entry")
                    if (style == SearchBarStyle.Native) {
                        val clear = if (backend == MixUiBackend.Miuix) "Search Cleanup" else "清除"
                        compose.onNodeWithContentDescription(clear, useUnmergedTree = true).performClick()
                        frames()
                        compose.runOnIdle { assertEquals("", query); assertEquals(0, submits) }
                        field().assertIsFocused()
                        field().performTextReplacement("submit-$entry")
                    } else {
                        // The acrylic style never had clear or cancel affordances.
                        compose.onNodeWithContentDescription("Search Cleanup").assertDoesNotExist()
                        compose.onNodeWithContentDescription("清除").assertDoesNotExist()
                    }
                    val beforeSubmits = submits
                    val beforeHides = hides
                    field().performImeAction()
                    frames()
                    field().assertIsNotFocused()
                    compose.runOnIdle {
                        assertEquals(beforeSubmits + 1, submits)
                        assertTrue(hides > beforeHides)
                        assertEquals(false, focusEvents.lastOrNull())
                    }
                    capture("$name-submitted-$entry")
                    compose.runOnIdle { query = ""; submits = 0 }
                    frames()
                }
            }
    }

    @Test fun mochiNativeCancelClearsWithoutSubmittingAndCanReenter() {
        for (dark in listOf(false, true)) {
            host(MixUiBackend.Mochi, SearchBarStyle.Native, dark)
            repeat(2) {
                field().performClick()
                field().performTextReplacement("cancel me")
                frames()
                compose.runOnIdle { assertEquals(true, focusEvents.lastOrNull()) }
                val beforeHides = hides
                compose.onNodeWithContentDescription("取消", useUnmergedTree = true).performClick()
                frames()
                field().assertIsNotFocused()
                compose.runOnIdle {
                    assertEquals("", query)
                    assertEquals(0, submits)
                    assertTrue(hides > beforeHides)
                    assertEquals(false, focusEvents.lastOrNull())
                }
            }
        }
    }
}
