package hk.uwu.reareye.ui.components

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.ui.screen.MaryFloatingChatLayout
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
import java.io.File
import kotlin.math.abs

/** Real layout/draw frames, not a second implementation of the composer's geometry. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36],
    manifest = Config.NONE,
    application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaryComposerMotionLayoutTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var focusManager: FocusManager

    @Before
    fun requireExplicitResourceMode() {
        assumeTrue(
            "Run with -PmaryUiResources=true",
            java.lang.Boolean.getBoolean("mary.ui.resources")
        )
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close() }
    }

    @Test
    fun focusAndClearFocusAnimateUniqueAnchoredControlsWithoutInputReflow() {
        host()
        exerciseRoundTrip(firstScreenshot = 0)
    }

    @Test
    fun narrowScreenAndLargeFontKeepEveryFrameUsable() {
        host(widthDp = 280, fontScale = 1.6f)
        exerciseRoundTrip(firstScreenshot = 100)
    }

    @Test
    fun collapseCanReverseMidFlightAndRecoverTheExpandedLayout() {
        host()
        val compact = frame()
        compose.mainClock.autoAdvance = false
        node(INPUT).performClick().assertIsFocused()
        advance(320)
        val expanded = frame()
        assertTrue(expanded.composer.height > compact.composer.height)

        compose.runOnIdle { focusManager.clearFocus(force = true) }
        advance(16)
        val collapse = sampleFrames(count = 4, firstScreenshot = 200)
        val middle = collapse.last()
        assertTrue(
            "Reverse while the layout is genuinely between its endpoints",
            middle.composer.height > compact.composer.height && middle.composer.height < expanded.composer.height
        )
        assertAnchored(collapse, expanded.composer.bottom.value)
        assertNoInputReflow(collapse)

        node(INPUT).performClick().assertIsFocused()
        val recovery = sampleFrames(count = 16)
        assertAnchored(recovery, expanded.composer.bottom.value)
        assertNoInputReflow(recovery)
        assertTrue(
            "Refocusing must not snap directly to the expanded endpoint",
            recovery.first().composer.height < expanded.composer.height
        )
        assertSameLayout(expanded, recovery.last())
        node("mary-composer-tools").assertIsDisplayed()
        node(INPUT).assertIsFocused()

        compose.runOnIdle { focusManager.clearFocus(force = true) }
        val finalCollapse = sampleFrames(count = 16, firstScreenshot = 220)
        assertAnchored(finalCollapse, compact.composer.bottom.value)
        assertNoInputReflow(finalCollapse)
        assertSameLayout(compact, finalCollapse.last())
    }

    private fun exerciseRoundTrip(firstScreenshot: Int) {
        val compact = frame()
        compose.mainClock.autoAdvance = false
        node(INPUT).performClick().assertIsFocused()
        val expanding = listOf(compact) + sampleFrames(count = 16)
        val expanded = expanding.last()
        assertTrue(
            "Focus expands the actual composer bounds",
            expanded.composer.height > compact.composer.height
        )
        assertTrue("Expansion contains real intermediate layout frames", expanding.any {
            it.composer.height > compact.composer.height && it.composer.height < expanded.composer.height
        })
        assertAnchored(expanding, compact.composer.bottom.value)
        assertNoInputReflow(expanding)
        node("mary-composer-tools").assertIsDisplayed()

        compose.runOnIdle { focusManager.clearFocus(force = true) }
        val collapsing =
            listOf(expanded) + sampleFrames(count = 16, firstScreenshot = firstScreenshot)
        assertTrue("Clear focus shrinks through intermediate layout frames", collapsing.any {
            it.composer.height > compact.composer.height && it.composer.height < expanded.composer.height
        })
        assertAnchored(collapsing, compact.composer.bottom.value)
        assertNoInputReflow(collapsing)
        assertSameLayout(compact, collapsing.last())
        node("mary-composer-tools").assertDoesNotExist()
    }

    private data class Frame(val composer: DpRect, val input: DpRect)

    private fun frame(): Frame {
        listOf("mary-composer", INPUT, ATTACH, SEND).forEach { tag ->
            assertEquals(
                "Exactly one $tag even during a transition", 1,
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
            )
        }
        val composer = bounds("mary-composer")
        val input = bounds(INPUT)
        listOf(ATTACH, SEND).forEach { tag ->
            val button = bounds(tag)
            assertTrue(
                "$tag remains horizontally inside the composer",
                button.left.value >= composer.left.value - 1f &&
                        button.right.value <= composer.right.value + 1f
            )
            assertTrue(
                "$tag remains vertically inside the composer",
                button.top.value >= composer.top.value - 1f &&
                        button.bottom.value <= composer.bottom.value + 1f
            )
            assertTrue(
                "$tag retains its touch target",
                button.width.value >= 43f && button.height.value >= 43f
            )
        }
        assertTrue(
            "Input has a usable viewport",
            input.width.value >= 44f && input.height.value >= 43f
        )
        assertTrue(
            "Input stays within the horizontal viewport",
            input.left.value >= composer.left.value - 1f &&
                    input.right.value <= composer.right.value + 1f
        )
        return Frame(composer, input)
    }

    private fun sampleFrames(count: Int, firstScreenshot: Int? = null): List<Frame> =
        (0 until count).map { index ->
            // Deliberately sample both single-frame and two-frame intervals with the clock paused.
            advance(if (index % 2 == 0) 16 else 32)
            frame().also {
                if (firstScreenshot != null) screenshot("composer-collapse-frame-${firstScreenshot + index}.png")
            }
        }

    private fun advance(milliseconds: Long) {
        compose.mainClock.advanceTimeBy(milliseconds)
        // Includes the Android measure/draw pass; advancing the animation clock alone is insufficient.
        compose.waitForIdle()
    }

    private fun assertAnchored(frames: List<Frame>, bottom: Float) {
        frames.forEach { frame ->
            assertEquals(
                "The footer bottom must not bounce",
                bottom,
                frame.composer.bottom.value,
                1f
            )
        }
    }

    private fun assertNoInputReflow(frames: List<Frame>) {
        val heights = frames.map { it.input.height.value }
        assertTrue(
            "Input line height must not repeatedly reflow",
            heights.maxOrNull()!! - heights.minOrNull()!! <= 1f
        )
        val widths = frames.map { it.input.width.value }
        val travel = widths.zipWithNext { a, b -> abs(b - a) }.sum()
        // Integer layout rounding is allowed; repeated width backtracking is not. Do not assert
        // exact animation progress or require every sampled float to be strictly monotonic.
        assertTrue(
            "Input width must not oscillate during a one-direction transition",
            travel <= abs(widths.last() - widths.first()) + 4f
        )
    }

    private fun assertSameLayout(expected: Frame, actual: Frame) {
        assertEquals(expected.composer.height.value, actual.composer.height.value, 1f)
        assertEquals(expected.composer.bottom.value, actual.composer.bottom.value, 1f)
        assertEquals(expected.input.width.value, actual.input.width.value, 1f)
        assertEquals(expected.input.height.value, actual.input.height.value, 1f)
    }

    private fun host(widthDp: Int = 360, fontScale: Float = 1f) {
        val text = mutableStateOf("")
        compose.runOnUiThread {
            val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity = controller
            controller.get().setContent {
                MiuixTheme(ThemeController(ColorSchemeMode.Light)) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(
                        LocalDensity provides Density(
                            density.density,
                            fontScale
                        )
                    ) {
                        focusManager = LocalFocusManager.current
                        Box(
                            Modifier
                                .requiredSize(widthDp.dp, 640.dp)
                                .background(Color(0xFFF4F5F7))
                                .testTag("composer-motion-host")
                        ) {
                            MaryFloatingChatLayout(
                                bottomInset = 80.dp,
                                modifier = Modifier.fillMaxSize(),
                                content = { _ -> Box(Modifier.fillMaxSize()) },
                                footer = {
                                    MaryComposer(
                                        value = text.value,
                                        onValueChange = { text.value = it },
                                        running = false,
                                        locked = false,
                                        configuring = false,
                                        provider = ProviderConfig(model = "gpt-5.2"),
                                        onReasoningSelected = {},
                                        onSend = {},
                                        onStop = {},
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String) {
        val directory = File(requireNotNull(System.getProperty("mary.ui.screenshotDir")))
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create $directory" }
        val image = node("composer-motion-host").captureToImage().asAndroidBitmap()
        File(directory, name).outputStream().use { output ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun bounds(tag: String) = node(tag).getUnclippedBoundsInRoot()

    private companion object {
        const val INPUT = "mary-composer-input"
        const val ATTACH = "mary-composer-attach"
        const val SEND = "mary-composer-send"
    }
}
