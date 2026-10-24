package hk.uwu.reareye.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import kotlin.math.cos
import kotlin.math.sin

/** Verify the actual button Canvas, not a test-only reconstruction of its arc. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryContextRingRenderingTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var activity: ActivityController<ComponentActivity>? = null

    @Before
    fun requireExplicitResourceMode() {
        assumeTrue(
            "Run with -PmaryUiResources=true",
            java.lang.Boolean.getBoolean("mary.ui.resources")
        )
    }

    @After
    fun tearDown() {
        compose.runOnUiThread { activity?.close() }
    }

    @Test
    fun lightThemeRingStartsAtTopAndFillsClockwise() = assertRingPixels(ColorSchemeMode.Light)

    @Test
    fun darkThemeRingStartsAtTopAndFillsClockwise() = assertRingPixels(ColorSchemeMode.Dark)

    private fun assertRingPixels(mode: ColorSchemeMode) {
        // Quadrants are ordered clockwise from twelve o'clock: NE, SE, SW, NW.
        val cases = listOf(
            RingCase("unknown", MaryUsageSummary(null, 100, null), 0),
            RingCase("zero", MaryUsageSummary(0, 100, null), 0),
            RingCase("quarter", MaryUsageSummary(25, 100, null), 1),
            RingCase("half", MaryUsageSummary(50, 100, null), 2),
            RingCase("three quarters", MaryUsageSummary(75, 100, null), 3),
            RingCase("full", MaryUsageSummary(100, 100, null), 4),
            RingCase("clamped overflow", MaryUsageSummary(150, 100, null), 4),
            RingCase("negative usage", MaryUsageSummary(-1, 100, null), 0),
            RingCase("invalid window", MaryUsageSummary(25, 0, null), 0),
        )
        var primary = Color.Unspecified
        var track = Color.Unspecified
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent {
                MiuixTheme(ThemeController(mode)) {
                    primary = MiuixTheme.colorScheme.primary
                    track = MiuixTheme.colorScheme.outline
                    Column(Modifier.background(MiuixTheme.colorScheme.background)) {
                        cases.forEach { ContextUsageButton(it.summary) }
                    }
                }
            }
        }
        compose.waitForIdle()
        assertTrue("Fixture must distinguish progress from track", primary != track)
        val rings = compose.onAllNodesWithTag("mary-context-ring", useUnmergedTree = true)
        rings.assertCountEquals(cases.size)
        cases.forEachIndexed { index, case ->
            val image = rings[index].captureToImage()
            System.getProperty("mary.ui.screenshotDir")?.let { path ->
                val directory = java.io.File(path, "context-ring")
                check(directory.mkdirs() || directory.isDirectory)
                val filename = "$mode-${case.name.replace(' ', '-')}.png"
                java.io.File(directory, filename).outputStream().use { output ->
                    check(
                        image.asAndroidBitmap()
                            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                    )
                }
            }
            val pixels = image.toPixelMap()
            assertEquals("Actual 20dp Canvas at mdpi", 20, pixels.width)
            assertEquals(20, pixels.height)
            val radius = (pixels.width - 2) / 2.0
            repeat(4) { quadrant ->
                val expected = if (quadrant < case.filledQuadrants) primary else track
                // Stay away from butt-cap endpoints and sample the middle of the real stroke.
                listOf(22.5, 45.0, 67.5).forEach { withinQuadrant ->
                    val angle = Math.toRadians(-90.0 + quadrant * 90 + withinQuadrant)
                    val x = (pixels.width / 2.0 + radius * cos(angle)).toInt()
                    val y = (pixels.height / 2.0 + radius * sin(angle)).toInt()
                    val actual = pixels[x, y]
                    val label = "$mode ${case.name}, quadrant $quadrant, pixel ($x,$y)"
                    assertEquals("$label red", expected.red, actual.red, 0.08f)
                    assertEquals("$label green", expected.green, actual.green, 0.08f)
                    assertEquals("$label blue", expected.blue, actual.blue, 0.08f)
                    assertEquals("$label alpha", 1f, actual.alpha, 0.01f)
                }
            }
        }
    }

    private data class RingCase(
        val name: String,
        val summary: MaryUsageSummary,
        val filledQuadrants: Int,
    )
}
