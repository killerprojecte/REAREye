package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.R
import hk.uwu.reareye.script.ScriptProject
import hk.uwu.reareye.script.ScriptProjectKind
import hk.uwu.reareye.ui.components.script.ScriptList
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual ScriptList with inert callbacks. Never opens files or executes Lua. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptListNativeTitleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val events = mutableListOf<String>()
    private fun settle() { repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun open(backend: MixUiBackend, dark: Boolean, projects: List<ScriptProject>) {
        compose.mainClock.autoAdvance = false
        events.clear()
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    ScriptList(projects, 80.dp, onBack = { events += "back" },
                        onRefresh = { events += "refresh" }, onImport = { events += "import" },
                        onExport = { events += "export:${it.id}" }, onOpen = { events += "open:${it.id}" },
                        onRename = { events += "rename:${it.id}" }, onDelete = { events += "delete:${it.id}" },
                        onNew = { events += "new" }, onNewProject = { events += "project" })
                }
            }
        }
        settle()
    }
    private fun capture(name: String) {
        val folder = File(System.getProperty("ui.capture.output"), "script-native-list").apply { mkdirs() }
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    @Test fun actualEmptyListKeepsEveryToolbarAction() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark, emptyList())
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
            compose.onNodeWithText(label(R.string.script_empty)).assertIsDisplayed()
            capture("${backend.name}-${if (dark) "dark" else "light"}-empty")
            listOf(R.string.script_new, R.string.script_new_project, R.string.script_import,
                R.string.script_refresh, R.string.scene_route_navigate_back).forEach { id ->
                compose.onNodeWithContentDescription(label(id)).assertIsDisplayed().performClick()
            }
            assertEquals(listOf("new", "project", "import", "refresh", "back"), events)
        }
    }
    @Test fun actualProjectRowsKeepCallbacksAndLongListReachability() {
        val projects = List(32) { index -> ScriptProject("id-$index", "Script $index", File("/fixture"),
            File("/fixture/script-$index.lua"), ScriptProjectKind.STANDALONE,
            description = "A script description that wraps across the native row.") }
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark, projects)
            compose.onNodeWithText("Script 0").assertIsDisplayed().performClick()
            compose.onAllNodesWithContentDescription(label(R.string.script_edit))[0].performClick()
            compose.onAllNodesWithContentDescription(label(R.string.script_rename))[0].performClick()
            compose.onAllNodesWithContentDescription(label(R.string.script_export))[0].performClick()
            compose.onAllNodesWithText(label(R.string.script_delete))[0].performClick()
            assertEquals("$backend dark=$dark", listOf("open:id-0", "open:id-0", "rename:id-0", "export:id-0", "delete:id-0"), events)
            events.clear()
            compose.onAllNodesWithContentDescription(label(R.string.script_rename))[0].performTouchInput { click() }
            settle()
            assertEquals(listOf("rename:id-0"), events)
            capture("${backend.name}-${if (dark) "dark" else "light"}-projects")
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
            compose.onNode(hasScrollAction()).performScrollToKey(projects.last().mainFile.absolutePath)
            settle()
            compose.onNodeWithText("Script 31").assertIsDisplayed()
            capture("${backend.name}-${if (dark) "dark" else "light"}-last")
        }
    }
}
