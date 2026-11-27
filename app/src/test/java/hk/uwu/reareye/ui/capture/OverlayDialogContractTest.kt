package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.IntRect
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.LocalOverlayDialogBounds
import hk.uwu.mixui.components.OverlayDialog
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.components.Scaffold
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.SubscreenNotificationAccessDialog
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
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Legacy window-host regression and scene-backed production permission UI; no permission services. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayDialogContractTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null

    @Before fun enabled() { assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled")) }
    @After fun close() {
        compose.runOnUiThread { activity?.close() }
        compose.mainClock.autoAdvance = true
    }
    private fun frames() { repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun open(backend: MixUiBackend, dark: Boolean, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    Scaffold { GlassBottomSheetScene { content() } }
                }
            }
        }
        frames()
    }
    private fun outside() {
        compose.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
        frames()
    }
    @Suppress("DEPRECATION")
    private fun back() {
        compose.runOnUiThread { requireNotNull(ShadowDialog.getLatestDialog()).onBackPressed() }
        frames()
    }
    private fun capture(backend: MixUiBackend, dark: Boolean, name: String) {
        val directory = File(System.getProperty("ui.capture.output"), "overlay-dialog").apply { mkdirs() }
        val bitmap = compose.runOnUiThread {
            captureDialogWindowBitmap(requireNotNull(ShadowDialog.getLatestDialog()))
        }
        File(directory, "${backend.name.lowercase()}-${if (dark) "dark" else "light"}-$name.png")
            .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun host(backend: MixUiBackend, dark: Boolean) {
        val show = mutableStateOf(true)
        val dismissible = mutableStateOf(false)
        val dim = mutableStateOf(true)
        var dismisses = 0
        var finished = 0
        var bounds = IntRect.Zero
        open(backend, dark) {
            OverlayDialog(
                show = show.value, title = "Ordinary dialog", summary = "Window contract",
                enableWindowDim = dim.value,
                onDismissRequest = if (dismissible.value) ({ dismisses++ }) else null,
                onDismissFinished = { finished++ },
            ) {
                val currentBounds = LocalOverlayDialogBounds.current
                SideEffect { bounds = currentBounds }
                Text("Dialog body")
            }
        }
        val dialog = requireNotNull(ShadowDialog.getLatestDialog())
        assertTrue(dialog.isShowing)
        assertNotSame(activity!!.get().window, dialog.window)
        assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        assertTrue(bounds.width > 0 && bounds.height > 0)
        assertTrue(bounds.left >= 0 && bounds.top >= 0)
        capture(backend, dark, "dimmed")
        compose.onNodeWithText("Dialog body").performTouchInput { click() }
        outside(); back()
        assertEquals(0, dismisses)
        assertEquals(0, finished)
        compose.onNodeWithText("Dialog body").assertIsDisplayed()

        compose.runOnUiThread { dismissible.value = true; dim.value = false }
        frames()
        assertEquals(0, dialog.window!!.attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        capture(backend, dark, "no-dim")
        outside()
        assertEquals(1, dismisses)
        assertEquals(0, finished)
        // A request is not permission for the host to mutate its owner's visibility.
        compose.onNodeWithText("Dialog body").assertIsDisplayed()
        back()
        assertEquals(2, dismisses)
        compose.runOnUiThread { show.value = false }
        frames()
        assertEquals(1, finished)
        assertFalse(dialog.isShowing)
        compose.onNodeWithText("Dialog body").assertDoesNotExist()
        frames()
        assertEquals(1, finished)

        compose.runOnUiThread { show.value = true }
        frames()
        compose.onNodeWithText("Dialog body").assertIsDisplayed()
        assertEquals(2, dismisses)
        assertEquals(1, finished)
    }
    private fun permission(backend: MixUiBackend, dark: Boolean) {
        val show = mutableStateOf(true)
        val granting = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        var grants = 0
        var dismisses = 0
        open(backend, dark) {
            SubscreenNotificationAccessDialog(show.value, granting.value, error.value,
                onGrant = { grants++ }, onDismiss = { dismisses++; show.value = false })
        }
        fun label(id: Int) = activity!!.get().getString(id)
        compose.onNodeWithText(label(R.string.subscreen_notification_access_granting)).assertIsNotEnabled()
        compose.onNodeWithText(label(R.string.subscreen_notification_access_later)).assertIsNotEnabled()
        compose.onNode(isDialog()).performTouchInput { click(Offset(2f, 2f)) }
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        frames()
        assertEquals(0, grants)
        assertEquals(0, dismisses)
        compose.runOnUiThread { granting.value = false; error.value = "Inert failure fixture" }
        frames()
        compose.onNodeWithText("Inert failure fixture").assertIsDisplayed()
        val directory = File(System.getProperty("ui.capture.output"), "overlay-dialog").apply { mkdirs() }
        val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
        File(directory, "${backend.name.lowercase()}-${if (dark) "dark" else "light"}-permission-error.png")
            .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        assertEquals(0, grants)
        assertEquals(0, dismisses)
        // This is the production button with a deliberately inert callback, not access.grant().
        compose.onNodeWithText(label(R.string.subscreen_notification_access_confirm)).performClick()
        assertEquals(1, grants)
        assertEquals(0, dismisses)
        compose.onNodeWithText(label(R.string.subscreen_notification_access_later)).performClick()
        frames()
        assertEquals(1, grants)
        assertEquals(1, dismisses)
        compose.onNodeWithText("Inert failure fixture").assertDoesNotExist()
    }
    @Test fun hostMiuixLight() = host(MixUiBackend.Miuix, false)
    @Test fun hostMiuixDark() = host(MixUiBackend.Miuix, true)
    @Test fun hostMochiLight() = host(MixUiBackend.Mochi, false)
    @Test fun hostMochiDark() = host(MixUiBackend.Mochi, true)
    @Test fun permissionMiuixLight() = permission(MixUiBackend.Miuix, false)
    @Test fun permissionMiuixDark() = permission(MixUiBackend.Miuix, true)
    @Test fun permissionMochiLight() = permission(MixUiBackend.Mochi, false)
    @Test fun permissionMochiDark() = permission(MixUiBackend.Mochi, true)
}
