package hk.uwu.reareye.ui.capture

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import org.junit.Assume.assumeTrue
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.util.Locale

internal class MaryPhaseOneCapture(private val compose: ComposeTestRule, private val screen: String) {
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var output: File

    fun requireResources() {
        assumeTrue("Run with -PmaryUiResources=true", java.lang.Boolean.getBoolean("mary.ui.resources"))
    }

    fun open(backend: MixUiBackend, dark: Boolean, content: @Composable () -> Unit) {
        val scheme = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light
        val backendName = backend.name.lowercase(Locale.ROOT)
        output = File(requireNotNull(System.getProperty("mary.ui.screenshotDir")),
            "phase-one/$screen/$backendName/${if (dark) "dark" else "light"}")
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend, scheme, mochiStyle = MixMochiStyle.Ios, content = content)
            }
        }
        settle()
    }

    fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    fun settle() {
        repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }

    fun back() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        settle()
    }

    fun label(id: Int, vararg args: Any): String = activity!!.get().getString(id, *args)

    fun node(tag: String, unmerged: Boolean = false) = compose.onNodeWithTag(tag, useUnmergedTree = unmerged)

    fun capture(name: String, includeExtraRoots: Boolean = false, inspect: (Bitmap) -> Unit = {}) {
        settle()
        check(output.isDirectory || output.mkdirs()) { "Cannot create screenshot directory: $output" }
        val bitmap = compose.runOnUiThread {
            val dialog = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }
            if (dialog == null) captureWindowBitmap(activity!!.get().window) else captureDialogWindowBitmap(dialog)
        }
        try {
            writePng(bitmap, name)
            inspect(bitmap)
        } finally {
            bitmap.recycle()
        }

        if (includeExtraRoots) {
            // Some native popovers have a separate Compose root and aren't in the activity window.
            val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
            val rootIndices = roots.fetchSemanticsNodes().indices
            if (rootIndices.count() > 1) rootIndices.forEach { index ->
                writePng(roots[index].captureToImage().asAndroidBitmap(), "$name-root-${index + 1}")
            }
        }
    }

    private fun writePng(bitmap: Bitmap, name: String) {
        File(output, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "Cannot write $name.png" }
        }
    }
}
