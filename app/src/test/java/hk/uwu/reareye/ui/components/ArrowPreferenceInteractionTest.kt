package hk.uwu.reareye.ui.components

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.ArrowPreference
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.Scaffold
import hk.uwu.mixui.theme.MixTheme
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
import java.io.File

/** Exercises real Maven native rows, without replacing either backend with a test renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi",
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArrowPreferenceInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var enabled by mutableStateOf(true)
    private var callbackVersion by mutableStateOf(0)
    private var summary by mutableStateOf<String?>("Current value")
    private val clicks = mutableListOf<Int>()

    @Before fun requireExplicitUiRun() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }

    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }

    private fun frames() {
        repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }

    private fun host(
        backend: MixUiBackend,
        direction: LayoutDirection = LayoutDirection.Ltr,
        style: MixMochiStyle = MixMochiStyle.Default,
        dark: Boolean = false,
        content: @Composable () -> Unit = { SampleRows() },
    ) {
        compose.runOnUiThread {
            activity?.close()
            enabled = true
            callbackVersion = 0
            summary = "Current value"
            clicks.clear()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(
                    backend = backend,
                    colorMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light,
                    mochiStyle = style,
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0)) {
                            Column(Modifier.fillMaxSize().testTag("arrow-scene").padding(12.dp)) {
                                Card { content() }
                            }
                        }
                    }
                }
            }
        }
        frames()
    }

    @Composable
    private fun SampleRows() {
        val version = callbackVersion
        ArrowPreference(
            title = "Open details",
            summary = summary,
            enabled = enabled,
            modifier = Modifier.testTag("action-row"),
            startAction = {
                Box(Modifier.size(28.dp).testTag("leading-icon").background(MixTheme.colorScheme.primary))
            },
            onClick = { clicks += version },
        )
        ArrowPreference(title = "Read only", modifier = Modifier.testTag("passive-row"))
    }

    @Test fun labelsLeadingContentAndNativeClicksWorkAcrossBackends() {
        for (backend in MixUiBackend.entries) for (direction in LayoutDirection.entries) {
            host(backend, direction)
            val row = compose.onNodeWithTag("action-row")
            row.assertTextContains("Open details").assertTextContains("Current value")
                .assertIsEnabled().assertHasClickAction()
            compose.onNodeWithTag("leading-icon", useUnmergedTree = true).assertIsDisplayed()
            val bounds = row.getUnclippedBoundsInRoot()
            assertTrue("Keep an accessible row target", (bounds.bottom - bounds.top).value >= 48f)
            // Real pointer input must invoke once per release, not on down or twice via wrappers.
            row.performTouchInput { down(center) }
            compose.runOnIdle { assertTrue(clicks.isEmpty()) }
            row.performTouchInput { up() }
            frames()
            row.performTouchInput { click() }
            frames()
            row.performClick()
            frames()
            compose.runOnIdle { assertEquals(listOf(0, 0, 0), clicks) }
            compose.onNodeWithTag("passive-row").assertTextContains("Read only").assertHasNoClickAction()
        }
    }

    @Test fun disabledAndCancelledGesturesNeverDispatchAndReenabledRowUsesLatestCallback() {
        for (backend in MixUiBackend.entries) {
            host(backend)
            val row = compose.onNodeWithTag("action-row")
            row.performTouchInput { down(center); cancel() }
            frames()
            compose.runOnIdle { assertTrue(clicks.isEmpty()); enabled = false }
            frames()
            row.assertIsNotEnabled().assertHasNoClickAction()
            row.performTouchInput { click() }
            frames()
            compose.runOnIdle { assertTrue(clicks.isEmpty()); enabled = true }
            frames()

            // Both vendors retain pointer handlers. Recomposition must refresh their callbacks.
            row.performTouchInput { down(center) }
            compose.runOnIdle { callbackVersion = 1; summary = "Updated value" }
            frames()
            row.performTouchInput { up() }
            frames()
            row.assertTextContains("Updated value")
            compose.runOnIdle { assertEquals(listOf(1), clicks) }

            // Disabling an already pressed row must cancel that pending action.
            row.performTouchInput { down(center) }
            compose.runOnIdle { enabled = false }
            frames()
            row.performTouchInput { up() }
            frames()
            compose.runOnIdle { assertEquals(listOf(1), clicks); enabled = true; summary = null }
            frames()
            row.assertTextContains("Open details").assertIsEnabled()
            compose.onNodeWithText("Updated value").assertDoesNotExist()
            row.performClick()
            frames()
            compose.runOnIdle { assertEquals(listOf(1, 1), clicks) }
        }
    }

    @Test fun nativeRowsCaptureEveryAppearanceAndDirection() {
        val output = System.getProperty("ui.capture.output")
        require(!output.isNullOrBlank()) { "Set ui.capture.output to preserve native row evidence" }
        val folder = File(output, "arrow-preferences").apply { mkdirs() }
        for (backend in MixUiBackend.entries) {
            val styles = if (backend == MixUiBackend.Mochi) MixMochiStyle.entries else listOf(MixMochiStyle.Default)
            for (style in styles) for (dark in listOf(false, true)) for (direction in LayoutDirection.entries) {
                host(backend, direction, style, dark)
                for (disabled in listOf(false, true)) {
                    compose.runOnIdle { enabled = !disabled }
                    frames()
                    val image = compose.onNodeWithTag("arrow-scene").captureToImage().asAndroidBitmap()
                    assertTrue(image.width > 0 && image.height > 0)
                    val name = "${backend.name}-${style.name}-${if (dark) "dark" else "light"}-${direction.name}-${if (disabled) "disabled" else "enabled"}.png"
                    File(folder, name).outputStream().use {
                        check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                    }
                }
            }
        }
    }
}
