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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
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

    private fun editorReferenceMaterial(dark: Boolean): top.yukonga.miuix.kmp.material.GlassMaterial =
        top.yukonga.miuix.kmp.material.GlassMaterials.dialogGlass(dark)

        /** Small, neutral-background comparison; not the full transmission matrix. */
        @Test fun miuixOfficialGreyDialogAndSheetMatchWrappedSurfaces() {
            val folder = File(System.getProperty("ui.capture.output"),
                "miuix-modal-grey-tabs/SYNTHETIC").apply { mkdirs() }
            val samples = mutableMapOf<String, List<Double>>()
            val report = StringBuilder("theme\thost\tmean_r\tmean_g\tmean_b\n")
            val hosts = listOf("official-dialog", "wrapped-form", "wrapped-overlay", "official-sheet", "wrapped-sheet")
            for (dark in listOf(false, true)) for (host in hosts) {
                compose.runOnUiThread {
                    activity?.close()
                    activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                    activity!!.get().setContent {
                        CaptureTheme("miuix", dark) {
                            check(isRuntimeShaderSupported())
                            val directSource = rememberLayerBackdrop()
                            val body: @Composable () -> Unit = {
                                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Sharp foreground")
                                Tabs(listOf("First", "Second"), selectedTabIndex = 0, onTabSelected = {}, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.fillMaxWidth().height(112.dp).testTag("grey-interior"))
                                    Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Confirm") }
                                }
                            }
                            Scaffold {
                                GlassBottomSheetScene {
                                    GlassPreferenceScene(overlayContent = {
                                        when (host) {
                                            "official-dialog" -> top.yukonga.miuix.kmp.glass.GlassDialog(
                                                show = true, title = "Grey material comparison", onDismissRequest = {},
                                                backdrop = directSource, content = body)
                                            "wrapped-form" -> GlassFormDialog(
                                                show = true, title = "Grey material comparison", onDismissRequest = {}, content = body)
                                            "official-sheet" -> top.yukonga.miuix.kmp.glass.GlassBottomSheet(
                                                show = true, title = "Grey material comparison", onDismissRequest = {},
                                                backdrop = directSource, content = body)
                                        }
                                    }) {
                                        Canvas(Modifier.fillMaxSize().layerBackdrop(directSource)) {
                                            drawRect(Color(0xFF808080))
                                        }
                                    }
                                    when (host) {
                                        "wrapped-overlay" -> GlassOverlayDialog(
                                            show = true, title = "Grey material comparison", onDismissRequest = {}, content = body)
                                        "wrapped-sheet" -> GlassBottomSheet(
                                            show = true, title = "Grey material comparison", onDismissRequest = {}, content = body)
                                    }
                                }
                            }
                        }
                    }
                }
                settle()
                val theme = if (dark) "dark" else "light"
                if (host == "wrapped-sheet") {
                compose.onNodeWithTag("glass-tabs-miuix", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("tabs-miuix", useUnmergedTree = true).assertDoesNotExist()
            } else {
                compose.onNodeWithTag("tabs-miuix", useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithTag("glass-tabs-miuix", useUnmergedTree = true).assertDoesNotExist()
            }
            val region = compose.onNodeWithTag("grey-interior").fetchSemanticsNode().boundsInWindow
                val bitmap = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
                File(folder, "$theme-$host.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val sums = DoubleArray(3)
                var count = 0
                for (y in region.top.toInt() + 20 until region.bottom.toInt() - 20) {
                    for (x in region.left.toInt() + 32 until region.right.toInt() - 32) {
                        val pixel = bitmap.getPixel(x, y)
                        sums[0] += android.graphics.Color.red(pixel)
                        sums[1] += android.graphics.Color.green(pixel)
                        sums[2] += android.graphics.Color.blue(pixel)
                        count++
                    }
                }
                check(count > 0)
                val rgb = sums.map { it / count }
                samples["$theme-$host"] = rgb
                report.append("$theme\t$host\t${rgb.joinToString("\t")}\n")
                bitmap.recycle()
            }
            File(folder, "grey-material.tsv").writeText(report.toString())
            File(folder, "scope.txt").writeText("SYNTHETIC neutral-background diagnostic, Robolectric API36.\n" +
                "Official and wrapped MIUIX grey material only; not device GPU/IME certification.\n")
            println("MIUIX_GREY_MATERIAL\n$report")
            for (theme in listOf("light", "dark")) for (host in hosts) {
                val rgb = samples.getValue("$theme-$host")
                Assert.assertTrue("$theme $host must stay grey, not a white/black override: $rgb", rgb.all { it in 8.0..248.0 })
                Assert.assertTrue("$theme $host neutral input must remain neutral: $rgb", rgb.max() - rgb.min() < 3)
                if (host.startsWith("wrapped")) {
                    val reference = samples.getValue("$theme-${if (host == "wrapped-sheet") "official-sheet" else "official-dialog"}")
                    rgb.indices.forEach { channel -> assertEquals("$theme $host must match official grey", reference[channel], rgb[channel], 2.0) }
                }
            }
        }

    /** Wrapped modals must match this pinned version's official dialog/sheet material. */
    @Test fun miuixOfficialAndWrappedMaterialTransmission() {
        val folder = File(System.getProperty("ui.capture.output"),
            "miuix-editor-transmission-d8efe187").apply { mkdirs() }
        val report = StringBuilder("theme\thost\tsource\tmean_r\tmean_g\tmean_b\tshader_supported\troi\n")
        val samples = mutableMapOf<String, List<Double>>()
        val regions = mutableMapOf<String, androidx.compose.ui.geometry.Rect>()
        var sourceMode by mutableIntStateOf(0)
        val sources = listOf("red", "blue", "white", "red-text", "blue-text", "wide-bands")
        val hosts = listOf("official-dialog", "editor-reference-dialog", "wrapped-form", "wrapped-overlay", "official-sheet", "editor-reference-sheet", "wrapped-sheet")
        for (dark in listOf(false, true)) for (host in hosts) {
            compose.runOnUiThread {
                activity?.close()
                sourceMode = 0
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    CaptureTheme("miuix", dark) {
                        check(isRuntimeShaderSupported()) { "This diagnostic requires runtime shader support" }
                        val directSource = rememberLayerBackdrop()
                        val body: @Composable () -> Unit = {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Sharp foreground / blurred page")
                                TextField(value = "Ordinary form field", onValueChange = {}, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.fillMaxWidth().height(112.dp).testTag("transmission-interior"))
                                Button(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("Confirm") }
                            }
                        }
                        Scaffold {
                            GlassBottomSheetScene {
                                GlassPreferenceScene(overlayContent = {
                                    when (host) {
                                        "official-dialog", "editor-reference-dialog" -> top.yukonga.miuix.kmp.glass.GlassDialog(
                                            show = true, title = "Glass Dialog", onDismissRequest = {},
                                            material = if (host == "official-dialog") top.yukonga.miuix.kmp.glass.GlassDialogDefaults.material() else editorReferenceMaterial(dark),
                                            backdrop = directSource, content = body)
                                        "wrapped-form" -> GlassFormDialog(
                                            show = true, title = "Glass Dialog", onDismissRequest = {}, content = body)
                                        "official-sheet", "editor-reference-sheet" -> top.yukonga.miuix.kmp.glass.GlassBottomSheet(
                                            show = true, title = "Glass BottomSheet", onDismissRequest = {},
                                            material = if (host == "official-sheet") top.yukonga.miuix.kmp.glass.GlassBottomSheetDefaults.material() else editorReferenceMaterial(dark),
                                            backdrop = directSource, content = body)
                                    }
                                }) {
                                    Canvas(Modifier.fillMaxSize().layerBackdrop(directSource)) {
                                        drawRect(when (sourceMode) {
                                            0 -> Color(0xffe04020)
                                            1 -> Color(0xff2050e0)
                                            else -> Color.White
                                        })
                                        if (sourceMode == 3 || sourceMode == 4) drawIntoCanvas { canvas ->
                                            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                                color = if (sourceMode == 3) 0xffe04020.toInt() else 0xff2050e0.toInt()
                                                textSize = 28.dp.toPx()
                                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                            }
                                            for (row in 1..24) canvas.nativeCanvas.drawText(
                                                "RED BLUE TEXT 0123", 8.dp.toPx(), row * 38.dp.toPx(), paint)
                                        }
                                        if (sourceMode == 5) {
                                            drawRect(Color(0xffe04020), size = Size(size.width / 2, size.height))
                                            drawRect(Color(0xff2050e0), topLeft = Offset(size.width / 2, 0f),
                                                size = Size(size.width / 2, size.height))
                                        }
                                    }
                                }
                                when (host) {
                                    "wrapped-overlay" -> GlassOverlayDialog(
                                        show = true, title = "Glass Dialog", onDismissRequest = {}, content = body)
                                    "wrapped-sheet" -> GlassBottomSheet(
                                        show = true, title = "Glass BottomSheet", onDismissRequest = {}, content = body)
                                }
                            }
                        }
                    }
                }
            }
            settle()
            val theme = if (dark) "dark" else "light"
            for ((mode, name) in sources.withIndex()) {
                compose.runOnUiThread { sourceMode = mode }
                settle()
                val region = compose.onNodeWithTag("transmission-interior").fetchSemanticsNode().boundsInWindow
                val image = compose.runOnUiThread { captureWindowBitmap(activity!!.get().window) }
                File(folder, "$theme-$host-$name.png").outputStream().use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val sums = DoubleArray(3)
                var count = 0
                // No foreground, rounded corners, rim, text or controls enter this ROI.
                for (y in region.top.toInt() + 12 until region.bottom.toInt() - 12) {
                    for (x in region.left.toInt() + 24 until region.right.toInt() - 24) {
                        val pixel = image.getPixel(x, y)
                        sums[0] += android.graphics.Color.red(pixel)
                        sums[1] += android.graphics.Color.green(pixel)
                        sums[2] += android.graphics.Color.blue(pixel)
                        count++
                    }
                }
                check(count > 0)
                val rgb = sums.map { it / count }
                samples["$theme-$host-$name"] = rgb
                regions["$theme-$host-$name"] = region
                report.append("$theme\t$host\t$name\t${rgb.joinToString("\t")}\ttrue\t$region\n")
                image.recycle()
            }
        }
        File(folder, "transmission.tsv").writeText(report.toString())
        println("MIUIX_TRANSMISSION\n$report")
        val improvements = StringBuilder("theme\tcompose_default_rgb_response\teditor_rgb_response\n")
        for (theme in listOf("light", "dark")) {
            fun response(host: String): Double {
                val red = samples.getValue("$theme-$host-red")
                val blue = samples.getValue("$theme-$host-blue")
                return red.indices.sumOf { abs(red[it] - blue[it]) } / 3
            }
            val before = response("official-dialog")
            val after = response("wrapped-form")
            improvements.append("$theme\t$before\t$after\n")
            assertEquals("$theme editor must retain the official material's source response", before, after, 1.0)
        }
        File(folder, "policy-comparison.tsv").writeText(improvements.toString())
        for (theme in listOf("light", "dark")) for (host in hosts) {
            val red = samples.getValue("$theme-$host-red")
            val blue = samples.getValue("$theme-$host-blue")
            val response = red.indices.sumOf { abs(red[it] - blue[it]) } / 3
            Assert.assertTrue("$theme $host must transmit broad source colors, RGB MAD=$response", response > 12)
            if (host.startsWith("wrapped")) {
                val official = if (host == "wrapped-sheet") "editor-reference-sheet" else "editor-reference-dialog"
                for (source in sources) {
                    val actual = samples.getValue("$theme-$host-$source")
                    val reference = samples.getValue("$theme-$official-$source")
                    val difference = actual.indices.maxOf { abs(actual[it] - reference[it]) }
                    Assert.assertTrue("$theme $host/$source must match explicit modal policy, max RGB delta=$difference",
                        difference < 3)
                    val region = regions.getValue("$theme-$host-$source")
                    assertEquals("Compare the same window-space material pixels", region,
                        regions.getValue("$theme-$official-$source"))
                    val actualImage = android.graphics.BitmapFactory.decodeFile(
                        File(folder, "$theme-$host-$source.png").absolutePath)
                    val referenceImage = android.graphics.BitmapFactory.decodeFile(
                        File(folder, "$theme-$official-$source.png").absolutePath)
                    var pixelDifference = 0L
                    var channels = 0
                    for (y in region.top.toInt() + 12 until region.bottom.toInt() - 12) {
                        for (x in region.left.toInt() + 24 until region.right.toInt() - 24) {
                            val a = actualImage.getPixel(x, y)
                            val b = referenceImage.getPixel(x, y)
                            pixelDifference += abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                                abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) +
                                abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
                            channels += 3
                        }
                    }
                    actualImage.recycle(); referenceImage.recycle()
                    val pixelMad = pixelDifference.toDouble() / channels
                    println("MIUIX_SPATIAL_COMPARE $theme $host $source RGB_MAD=$pixelMad")
                    Assert.assertTrue("$theme $host/$source spatial RGB MAD=$pixelMad", pixelMad < 1)
                }
            }
        }
    }
}
