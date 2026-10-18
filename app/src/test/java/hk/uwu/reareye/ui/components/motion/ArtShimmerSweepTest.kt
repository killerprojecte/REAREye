package hk.uwu.reareye.ui.components.motion

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.width
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sweep is the whole feature, and it fails silently if it draws nothing, so it is checked on real
 * pixels: the highlight must land on the label, and it must not leak past what the label painted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [35],
    manifest = Config.NONE,
    application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtShimmerSweepTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var activity: ActivityController<ComponentActivity>? = null

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close() }
    }

    @Test
    fun theHighlightPaintsTheSweptLabelAndLeavesEverythingElseAlone() {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent { Host() }
        }
        compose.waitForIdle()

        val host = compose.onNodeWithTag("shimmer-host").getUnclippedBoundsInRoot()
        val plain = compose.onNodeWithTag("shimmer-plain").getUnclippedBoundsInRoot()
        val swept = compose.onNodeWithTag("shimmer-swept").getUnclippedBoundsInRoot()
        assertEquals("the sweep must not resize its label", plain.width, swept.width)
        assertEquals("the sweep must not resize its label", plain.height, swept.height)

        // Sample a whole period, so at least one frame has the band over the words.
        val baseline = capture()
        var sweptEverChanged = false
        var plainEverChanged = false
        var outsideEverChanged = false
        repeat(SAMPLES) {
            compose.mainClock.advanceTimeBy(STEP_MILLIS)
            val current = capture()
            if (differs(host, baseline, current, swept)) sweptEverChanged = true
            if (differs(host, baseline, current, plain)) plainEverChanged = true
            if (differs(host, baseline, current, outsideLabels(host, plain, swept))) {
                outsideEverChanged = true
            }
        }

        assertTrue("the sweep never painted anything", sweptEverChanged)
        assertFalse("the unswept label must be pixel-stable", plainEverChanged)
        assertFalse("the sweep leaked outside the label it wraps", outsideEverChanged)
    }

    @Composable
    private fun Host() {
        Box(
            Modifier
                .requiredSize(240.dp, 90.dp)
                .background(Backdrop)
                .testTag("shimmer-host")
        ) {
            Column(Modifier.padding(6.dp)) {
                BasicText(
                    text = "Thinking…",
                    modifier = Modifier.testTag("shimmer-plain"),
                    style = TextStyle(color = Ink, fontSize = 14.sp),
                )
                ArtShimmerSweep {
                    BasicText(
                        text = "Thinking…",
                        modifier = Modifier.testTag("shimmer-swept"),
                        style = TextStyle(color = Ink, fontSize = 14.sp),
                    )
                }
            }
        }
    }

    private fun capture(): PixelMap =
        compose.onNodeWithTag("shimmer-host").captureToImage().toPixelMap()

    /** Every pixel difference inside [rect], in whole device pixels. */
    private fun differs(
        host: DpRect,
        before: PixelMap,
        after: PixelMap,
        rect: DpRect,
    ): Boolean {
        val left = px(host, before, rect.left)
        val right = px(host, before, rect.right).coerceAtMost(before.width - 1)
        val top = px(host, before, rect.top)
        val bottom = py(host, before, rect.bottom).coerceAtMost(before.height - 1)
        for (y in top..bottom) {
            for (x in left..right) {
                if (before[x, y] != after[x, y]) return true
            }
        }
        return false
    }

    private fun outsideLabels(host: DpRect, plain: DpRect, swept: DpRect) = DpRect(
        left = host.left,
        top = swept.bottom + 2.dp,
        right = host.right,
        bottom = host.bottom,
    ).also { assertTrue("fixture must leave room below the labels", it.top < it.bottom) }

    private fun px(host: DpRect, pixels: PixelMap, x: androidx.compose.ui.unit.Dp): Int =
        ((x.value - host.left.value) / host.width.value * pixels.width).toInt()
            .coerceIn(0, pixels.width - 1)

    private fun py(host: DpRect, pixels: PixelMap, y: androidx.compose.ui.unit.Dp): Int =
        ((y.value - host.top.value) / host.height.value * pixels.height).toInt()
            .coerceIn(0, pixels.height - 1)

    private companion object {
        val Backdrop = Color(0xFF101010)
        val Ink = Color(0xFFCFCFCF)
        const val SAMPLES = 20
        const val STEP_MILLIS = 80L
    }
}
