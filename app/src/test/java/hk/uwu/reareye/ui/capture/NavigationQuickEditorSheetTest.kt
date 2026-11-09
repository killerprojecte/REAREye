package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.ui.components.navigation.NavigationQuickAction
import hk.uwu.reareye.ui.components.navigation.NavigationQuickActionEditor
import hk.uwu.reareye.ui.components.navigation.NavigationQuickTarget
import hk.uwu.reareye.ui.config.ConfigType
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real editor/host/pointer integration, not device gesture or predictive Back coverage. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationQuickEditorSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var visible by mutableStateOf(true)
    private var selected by mutableStateOf(listOf("a", "b"))
    private val saved = mutableListOf<List<String>>()
    private var dismissals = 0
    private val actions = listOf("a", "b", "c").map {
        NavigationQuickAction(it, "Action $it", Icons.Rounded.Settings,
            NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.BUSINESS))
    }
    @Before fun resources() { assumeTrue(java.lang.Boolean.getBoolean("mary.ui.resources")) }
    @After fun close() { compose.runOnUiThread { activity?.close() } }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun settle() { compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle() }
    private fun open(backend: MixUiBackend, dark: Boolean) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    Scaffold {
                        GlassBottomSheetScene {
                            Box(Modifier.fillMaxSize().background(Color(0xFF246899)))
                            NavigationQuickActionEditor(visible, selected, actions,
                                { dismissals++; visible = false }, {
                                    saved += it
                                    selected = it
                                    visible = false
                                })
                        }
                    }
                }
            }
        }
        settle()
    }
    private fun capture(name: String) {
        val directory = File(System.getProperty("ui.capture.output"), "navigation-quick-editor").apply { mkdirs() }
        compose.runOnUiThread {
            val bitmap = captureWindowBitmap(activity!!.get().window)
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
    private fun reopen() { compose.runOnIdle { visible = true }; settle() }
    private fun save() { node("quick-editor-save").performScrollTo().performClick(); settle() }
    private fun drag(cancelGesture: Boolean = false) {
        val first = node("quick-editor-drag-a")
        val from = first.fetchSemanticsNode().boundsInRoot
        val to = node("quick-editor-drag-b").fetchSemanticsNode().boundsInRoot
        first.performTouchInput {
            down(center)
            moveTo(Offset(centerX, centerY + to.center.y - from.center.y + 12f), 500)
            if (cancelGesture) cancel() else up()
        }
        settle()
    }
    private fun exercise(backend: MixUiBackend, dark: Boolean) {
        open(backend, dark)
        val sheet = node(if (backend == MixUiBackend.Mochi) "mochi-glass-sheet" else "mixui-native-glass-sheet")
        val restingTop = sheet.fetchSemanticsNode().boundsInRoot.top
        capture("${backend.name}-${if (dark) "dark" else "light"}")
        drag(cancelGesture = true)
        assertEquals("Cancelled reorder cannot dismiss", 0, dismissals)
        save()
        assertEquals(listOf(listOf("a", "b")), saved)
        reopen()
        drag()
        assertEquals("Reorder never transfers to sheet drag", restingTop,
            sheet.fetchSemanticsNode().boundsInRoot.top, 1f)
        node("quick-editor-cancel").performScrollTo().performClick()
        settle()
        assertEquals(1, saved.size)
        reopen()
        save()
        assertEquals("Cancel discards draft order", listOf("a", "b"), saved.last())
        reopen()
        drag()
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        settle()
        node("quick-editor-body").assertDoesNotExist()
        reopen()
        save()
        assertEquals("Back discards draft order", listOf("a", "b"), saved.last())
        reopen()
        drag()
        save()
        assertEquals("Save commits reordered draft once", listOf("b", "a"), saved.last())
        assertEquals(4, saved.size)
        reopen()
        save()
        assertEquals("Reopen loads committed order", listOf("b", "a"), saved.last())
        assertEquals(5, saved.size)
    }
    @Test fun miuixLight() = exercise(MixUiBackend.Miuix, false)
    @Test fun miuixDark() = exercise(MixUiBackend.Miuix, true)
    @Test fun mochiLight() = exercise(MixUiBackend.Mochi, false)
    @Test fun mochiDark() = exercise(MixUiBackend.Mochi, true)

    private fun shortInsets(backend: MixUiBackend) {
        open(backend, false)
        fun applyInset(bottom: Int) {
            compose.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
                    .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), bottom > 0).build()
                ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView, insets)
            }
            settle()
        }
        applyInset(0)
        node("quick-editor-save").performScrollTo()
        val before = node("quick-editor-body").fetchSemanticsNode().boundsInRoot.bottom
        applyInset(48)
        node("quick-editor-save").performScrollTo().assertIsDisplayed()
        val after = node("quick-editor-body").fetchSemanticsNode().boundsInRoot.bottom
        assertEquals("Bottom inset is reserved once, not caller plus host", 48f, before - after, 2f)
        // performScrollTo ensures visibility, not alignment with the body end padding.
        // Measure the actual viewport's inset, then separately assert the button is reachable.
        assertTrue(node("quick-editor-save").fetchSemanticsNode().boundsInRoot.bottom <= after + 1f)
        capture("${backend.name}-short-navigation-inset")
        save()
        assertEquals(listOf(listOf("a", "b")), saved)
    }
    @Test @Config(qualifiers = "w400dp-h400dp-mdpi")
    fun miuixShortViewportInsets() = shortInsets(MixUiBackend.Miuix)
    @Test @Config(qualifiers = "w400dp-h400dp-mdpi")
    fun mochiShortViewportInsets() = shortInsets(MixUiBackend.Mochi)
}
