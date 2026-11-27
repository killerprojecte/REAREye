package hk.uwu.reareye.ui.capture

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.reareye.ui.screen.LicenseContent
import hk.uwu.reareye.utils.other.LibraryLicenseSheetState
import hk.uwu.reareye.utils.other.loadLibraries
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

/** Actual About license page with the generated Maven license data and its real row click. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutLicenseSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null

    private fun settle() {
        compose.mainClock.autoAdvance = true
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test fun miuixLight() = exercise(MixUiBackend.Miuix, MixColorSchemeMode.Light)
    @Test fun miuixDark() = exercise(MixUiBackend.Miuix, MixColorSchemeMode.Dark)
    @Test fun mochiLight() = exercise(MixUiBackend.Mochi, MixColorSchemeMode.Light)
    @Test fun mochiDark() = exercise(MixUiBackend.Mochi, MixColorSchemeMode.Dark)

    private fun exercise(backend: MixUiBackend, mode: MixColorSchemeMode) {
        val state = LibraryLicenseSheetState()
        val list = LazyListState()
        val haze = HazeState()
        lateinit var data: hk.uwu.reareye.utils.other.AboutLibraries
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            data = loadLibraries(activity!!.get())
        }
        // Move an existing library first and show just one of its real badges, so
        // row-overflow layout does not make the modal lifecycle test ambiguous.
        val source = data.libraries.first { lib -> lib.licenses.any { data.licenses[it] != null } }
        val id = source.licenses.first { data.licenses[it] != null }
        val first = data.licenses.getValue(id)
        val second = data.licenses.values.first { it != first }
        val pageData = data.copy(libraries = listOf(source.copy(developers = emptyList(), licenses = listOf(id))) +
            data.libraries.filter { it.uniqueId != source.uniqueId })
        assertTrue("Real dependency list must overflow", pageData.libraries.size > 20)
        compose.runOnUiThread {
            activity!!.get().setContent {
                MixUiHost(backend, mode) {
                    hk.uwu.mixui.components.Scaffold {
                        GlassBottomSheetScene {
                            LicenseContent(0.dp, PaddingValues(0.dp), list, haze, pageData, state)
                        }
                    }
                }
            }
        }
        settle()
        compose.onAllNodesWithText(first.name).filter(hasClickAction()).onFirst().performClick()
        settle()
        assertEquals(first, state.selected)
        assertTrue(state.visible)
        assertBody(first.name + "\n\n" + (first.content ?: first.url))
        if (backend == MixUiBackend.Mochi) {
            // Prevent a silent fallback to the stock Maven MochiSheet.
            compose.onNodeWithTag("mochi-glass-sheet-host", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("mochi-glass-sheet", useUnmergedTree = true).assertIsDisplayed()
        } else {
            compose.onNodeWithTag("mixui-native-glass-sheet-host", useUnmergedTree = true).assertExists()
        }

        val bitmap = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("about-license-body")),
            useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val folder = java.io.File(System.getProperty("ui.capture.output"), "about-license-sheet").apply { mkdirs() }
        java.io.File(folder, "${backend.name}-${mode.name}.png").outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }

        // Scroll the source item away while the modal remains open. This uses the
        // real LazyColumn state because pointer input correctly belongs to the modal.
        compose.runOnUiThread { list.requestScrollToItem(pageData.libraries.lastIndex) }
        settle()
        assertTrue(list.firstVisibleItemIndex > 0)
        compose.runOnIdle {
            assertTrue(list.layoutInfo.visibleItemsInfo.none { it.key == source.uniqueId })
        }
        assertBody(first.name + "\n\n" + (first.content ?: first.url))

        compose.mainClock.autoAdvance = false
        val oldSession = state.session
        compose.runOnUiThread { state.dismiss(oldSession) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        assertFalse(state.visible)
        assertEquals("Keep text during exit", first, state.selected)
        compose.onNodeWithTag("about-license-body", useUnmergedTree = true).assertExists()
        compose.runOnUiThread {
            state.open(second)
            state.finishDismiss(oldSession) // A queued old completion cannot clear a new selection.
            state.dismiss(oldSession)       // Nor may an old request dismiss it.
        }
        settle()
        assertTrue(state.visible)
        assertEquals(second, state.selected)
        assertBody(second.name + "\n\n" + (second.content ?: second.url))
        if (backend == MixUiBackend.Mochi) {
            compose.onAllNodesWithTag("mochi-glass-sheet-host", useUnmergedTree = true).assertCountEquals(1)
        }
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        settle()
        assertFalse(state.visible)
        assertNull("Finished dismissal releases selected license", state.selected)
        compose.onAllNodesWithTag("about-license-body", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("mochi-glass-sheet-host", useUnmergedTree = true).assertCountEquals(0)

        compose.onAllNodesWithTag("mixui-native-glass-sheet-host", useUnmergedTree = true).assertCountEquals(0)

        // Reopen the same license through the actual badge after full dismissal.
        compose.runOnUiThread { list.requestScrollToItem(0) }
        settle()
        compose.onAllNodesWithText(first.name).filter(hasClickAction()).onFirst().performClick()
        settle()
        assertTrue(state.visible)
        assertEquals(first, state.selected)
        assertBody(first.name + "\n\n" + (first.content ?: first.url))
    }

    private fun assertBody(expected: String) {
        val text = compose.onNodeWithTag("about-license-body", useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals("Maven license content must be unchanged", expected, text.text)
        val urls = Regex("(https?://[\\w-]+(\\.[\\w-]+)+(/\\S*)?)").findAll(expected).toList()
        assertEquals("Preserve existing clickable URL annotations", urls.size,
            text.getLinkAnnotations(0, text.length).size)
    }

    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
}
