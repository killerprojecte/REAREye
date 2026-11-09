package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.components.*
import org.junit.*
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

/** SYNTHETIC DIAGNOSTIC ONLY: not a manager page, device result, or shader certification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManagerDialogSamplingDiagnosticTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var phase by mutableStateOf(0f)
    @Before fun enabled() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(24) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    @Composable private fun source() {
        Canvas(Modifier.fillMaxSize()) {
            val w = 48.dp.toPx()
            val colors = listOf(Color.Red, Color.Blue, Color.Yellow, Color.Green)
            for (i in -2..(size.width / w).toInt() + 2) {
                drawRect(colors[(i % 4 + 4) % 4], Offset(i * w + phase.dp.toPx(), 0f), Size(w, size.height))
            }
        }
    }
    @Composable private fun dialog() {
        GlassFormDialog(show = true, title = "Synthetic dialog probe", onDismissRequest = {}) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Stationary ordinary controls", Modifier.testTag("probe-label"))
                TextField(value = "Fixed field", onValueChange = {}, modifier = Modifier.fillMaxWidth())
                // Empty interior measures backdrop only, excluding ordinary foreground controls.
                Spacer(Modifier.fillMaxWidth().height(36.dp).testTag("probe-interior"))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("Confirm") }
                }
            }
        }
    }
    @Test fun sourceOnlyMotionThroughBothManagerHostTypes() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) for (embedded in listOf(false, true)) {
            compose.runOnUiThread {
                activity?.close(); phase = 0f
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    CaptureTheme(backend, dark) {
                        if (embedded) GlassPreferenceScene(overlayContent = { dialog() }) { source() }
                        else GlassTopBarScaffold(title = "Synthetic host", isContentScrolled = false, overlayContent = { dialog() }) { source() }
                    }
                }
            }
            settle()
            val folder = File(System.getProperty("ui.capture.output"),
                "remaining-manager-glass/SYNTHETIC-DIAGNOSTIC/$backend/$dark-$embedded").apply { mkdirs() }
            val region = compose.onNodeWithTag("probe-interior").fetchSemanticsNode().boundsInRoot
            val label = compose.onNodeWithTag("probe-label").fetchSemanticsNode().boundsInRoot
            fun capture(name: String): Bitmap {
                val bitmap = compose.onNode(isRoot() and hasAnyDescendant(hasTestTag("probe-interior")),
                    useUnmergedTree = true).captureToImage().asAndroidBitmap()
                File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                return bitmap
            }
            val baseline = capture("phase-0")
            val report = StringBuilder("phase_dp\tpixels\tRGB_MAD_from_phase0\n")
            for ((name, value) in listOf("24" to 24f, "48" to 48f, "return-0" to 0f)) {
                compose.runOnUiThread { phase = value }; settle()
                assertEquals(region, compose.onNodeWithTag("probe-interior").fetchSemanticsNode().boundsInRoot)
                assertEquals(label, compose.onNodeWithTag("probe-label").fetchSemanticsNode().boundsInRoot)
                val changed = capture("phase-$name")
                var difference = 0L; var count = 0
                for (y in (region.top.toInt() + 4) until (region.bottom.toInt() - 4)) {
                    for (x in (region.left.toInt() + 12) until (region.right.toInt() - 12)) {
                        val a = baseline.getPixel(x, y); val b = changed.getPixel(x, y)
                        difference += abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                            abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                            abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
                        count++
                    }
                }
                check(count > 0)
                report.append("$name\t$count\t${difference.toDouble() / count / 3}\n")
            }
            File(folder, "metrics.tsv").writeText(report.toString())
            File(folder, "scope.txt").writeText("SYNTHETIC DIAGNOSTIC ONLY. Not an actual manager page.\n" +
                "Only source bands move through 0/24/48/0dp; dialog/label geometry remains fixed.\n" +
                "Interior ROI $region excludes text, controls, corners, rim and shadow.\n" +
                "Response and return residual require inspection; metrics are NOT a shader pass/fail or device certification.\n")
        }
    }
}
