package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
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
import hk.uwu.reareye.script.ScriptProjectStore
import hk.uwu.reareye.ui.components.script.ScriptEditorContent
import io.github.rosemoe.sora.widget.CodeEditor
import java.io.File
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

/** Real editor and file save using test-owned files. Native Lua execution is not exercised. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScriptEditorNativeTitleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun settle() { repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun findEditor(view: View): CodeEditor? {
        if (view is CodeEditor) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findEditor(view.getChildAt(i))?.let { return it }
        return null
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    @Test fun actualEditorSavesAndKeepsNativeBackAcrossThemes() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            var saved = 0; var backs = 0
            lateinit var file: File
            compose.mainClock.autoAdvance = false
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                val context = activity!!.get()
                context.enableEdgeToEdge()
                val directory = File(context.cacheDir, "editor-fixture-${backend.name}-$dark").apply { mkdirs() }
                file = File(directory, "fixture.lua").apply { writeText("-- original fixture\nreturn {}\n") }
                val project = ScriptProject("fixture", "Fixture", directory, file, ScriptProjectKind.STANDALONE)
                context.setContent {
                    MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                        ScriptEditorContent(project, ScriptProjectStore(context), 80.dp,
                            onBack = { backs++ }, onSaved = { saved++ },
                            loadProviders = { emptyList() },
                            runProvider = { _, _ -> error("Lua execution is not part of this UI fixture") })
                    }
                }
            }
            settle()
            val context = activity!!.get()
            compose.onNodeWithText("fixture.lua").assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.script_debug)).assertIsDisplayed()
            val editor = findEditor(context.window.decorView)
            assertNotNull("Actual Android code editor must be mounted", editor)
            compose.runOnUiThread { editor!!.setText("-- edited fixture\nreturn {}\n") }
            settle()
            val folder = File(System.getProperty("ui.capture.output"), "script-native-editor").apply { mkdirs() }
            val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            File(folder, "${backend.name}-${if (dark) "dark" else "light"}.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            compose.onNodeWithContentDescription(context.getString(R.string.script_save)).performClick()
            settle()
            assertEquals("-- edited fixture\nreturn {}\n", file.readText())
            assertEquals(1, saved); assertEquals(1, backs)
            compose.onNodeWithContentDescription(context.getString(R.string.scene_route_navigate_back)).performClick()
            assertEquals(2, backs); assertEquals(1, saved)
        }
    }
}
