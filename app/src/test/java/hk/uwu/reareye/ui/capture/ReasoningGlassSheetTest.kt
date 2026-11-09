package hk.uwu.reareye.ui.capture

import android.graphics.Bitmap
import java.io.File
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.GlassBottomSheetScene
import hk.uwu.mixui.components.Scaffold
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.ui.components.ReasoningStrengthButton
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
import kotlin.math.abs

/** Real scene/material/slider integration. Does not substitute for device IME or predictive Back QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReasoningGlassSheetTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var enabled by mutableStateOf(true)
    private var alternate by mutableStateOf(false)
    private var provider by mutableStateOf(ProviderConfig(model = "gpt-6-luna",
        protocol = Protocol.RESPONSES, reasoningEffort = ReasoningEffort.NONE))
    private val selections = mutableListOf<ReasoningEffort>()

    @Before fun requireResources() { assumeTrue(java.lang.Boolean.getBoolean("mary.ui.resources")) }
    @After fun close() { compose.runOnUiThread { activity?.close() } }
    private fun node(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true)
    private fun settle() { compose.mainClock.advanceTimeBy(2_000); compose.waitForIdle() }
    private fun open(backend: MixUiBackend, rtl: Boolean, dark: Boolean) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    CompositionLocalProvider(LocalLayoutDirection provides
                        if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        Scaffold {
                            GlassBottomSheetScene {
                                Box(Modifier.fillMaxSize().background(if (alternate) Color.Green else Color.Red))
                                ReasoningStrengthButton(provider, enabled, {}, {
                                    selections += it
                                    provider = provider.copy(reasoningEffort = it)
                                })
                            }
                        }
                    }
                }
            }
        }
        node("mary-reasoning-strength").performClick()
        settle()
    }
    private fun exercise(backend: MixUiBackend, rtl: Boolean, dark: Boolean = false) {
        open(backend, rtl, dark)
        val sheet = node(if (backend == MixUiBackend.Mochi) "mochi-glass-sheet" else "mixui-native-glass-sheet")
        val slider = node("mary-reasoning-slider")
        val bounds = sheet.fetchSemanticsNode().boundsInRoot
        assertTrue("Reasoning remains short", bounds.height < 400f)
        assertTrue("Reasoning stays near the bottom", bounds.top > 350f)
        val output = File(System.getProperty("ui.capture.output"), "mary-reasoning")
        check(output.isDirectory || output.mkdirs())
        compose.runOnUiThread {
            val bitmap = captureWindowBitmap(activity!!.get().window)
            File(output, "${backend.name}-${if (dark) "dark" else "light"}-${if (rtl) "rtl" else "ltr"}.png")
                .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        slider.performTouchInput {
            swipe(Offset(if (rtl) width - 20f else 20f, centerY),
                Offset(if (rtl) 20f else width - 20f, centerY), durationMillis = 600)
        }
        settle()
        assertEquals("One completed horizontal drag commits once", listOf(ReasoningEffort.MAX), selections)
        assertEquals("Horizontal gesture must not drag the sheet", bounds.top,
            sheet.fetchSemanticsNode().boundsInRoot.top, 1f)

        // Change only the page source. Sample blank material beneath the slider,
        // not foreground text or the canvas; an opaque/tint-only sheet cannot pass.
        fun sample(): Color {
            val rect = sheet.fetchSemanticsNode().boundsInRoot
            val y = (slider.fetchSemanticsNode().boundsInRoot.bottom - rect.top + 16f).toInt()
            val pixels = sheet.captureToImage().toPixelMap()
            return pixels[pixels.width / 2, y.coerceIn(0, pixels.height - 1)]
        }
        val before = sample()
        compose.runOnIdle { alternate = true }
        settle()
        val after = sample()
        assertTrue("Real material must sample the changed scene",
            abs(before.red - after.red) + abs(before.green - after.green) > 0.03f)

        compose.runOnIdle { enabled = false }
        slider.assertIsDisplayed()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(0f)) }
        assertEquals(1, selections.size)
        compose.runOnIdle { enabled = true }
        slider.performTouchInput { down(center); moveTo(Offset(20f, centerY)); cancel() }
        settle()
        assertEquals("Canceled drag does not commit", 1, selections.size)
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        settle()
        slider.assertDoesNotExist()
        node("mary-reasoning-strength").performClick()
        settle()
        slider.assertIsDisplayed()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0f)) }
        settle()
        assertEquals(listOf(ReasoningEffort.MAX, ReasoningEffort.NONE), selections)
        compose.onRoot().performTouchInput { click(Offset(200f, 100f)) }
        settle()
        slider.assertDoesNotExist()
        assertEquals("Scrim dismissal must not save again", 2, selections.size)
    }
    @Test fun miuixShortGlassLtr() = exercise(MixUiBackend.Miuix, false)
    @Test fun miuixShortGlassRtl() = exercise(MixUiBackend.Miuix, true)
    @Test fun mochiShortGlassLtr() = exercise(MixUiBackend.Mochi, false)
    @Test fun mochiShortGlassRtl() = exercise(MixUiBackend.Mochi, true)
    @Test fun miuixShortGlassDarkLtr() = exercise(MixUiBackend.Miuix, false, true)
    @Test fun miuixShortGlassDarkRtl() = exercise(MixUiBackend.Miuix, true, true)
    @Test fun mochiShortGlassDarkLtr() = exercise(MixUiBackend.Mochi, false, true)
    @Test fun mochiShortGlassDarkRtl() = exercise(MixUiBackend.Mochi, true, true)

}
