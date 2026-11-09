package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.*
import hk.uwu.mixui.components.HorizontalDivider
import hk.uwu.mixui.components.Surface
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.ui.screen.RearStoreDownloadBadge
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SurfacePrimitivesContractTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun open(backend: MixUiBackend, dark: Boolean, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light, content = content)
            }
        }
        settle()
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    private fun capture(name: String) {
        val output = System.getProperty("ui.capture.output") ?: return
        val folder = File(output, "surface-primitives").apply { mkdirs() }
        val image = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun solidSurfaceClipsPropagatesColorAndRespectsEnabledClicks() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            var clicks = 0
            var observed = Color.Unspecified
            open(backend, dark) {
                Column(Modifier.background(Color.White)) {
                    Surface(Modifier.size(100.dp, 60.dp).testTag("plain"),
                        shape = RoundedCornerShape(20.dp), color = Color.Red, contentColor = Color.Green) {
                        // A child fills propagated minimum constraints and must be clipped by the surface.
                        Box(Modifier.background(Color.Blue).testTag("child"))
                    }
                    Surface(Modifier.size(100.dp, 40.dp).testTag("content"), color = Color.Red,
                        contentColor = Color.Green) {
                        val local = if (backend == MixUiBackend.Mochi) ui.feice.mochi.theme.LocalContentColor.current
                            else top.yukonga.miuix.kmp.theme.LocalContentColor.current
                        SideEffect { observed = local }
                        Text("CONTENT", style = TextStyle.Default)
                    }
                    Surface(Modifier.size(100.dp, 40.dp).testTag("click"), onClick = { clicks++ }) { Text("Click") }
                    Surface(Modifier.size(100.dp, 40.dp).testTag("disabled"), enabled = false,
                        onClick = { clicks++ }) { Text("Disabled") }
                }
            }
            compose.onNodeWithTag("plain").assertHasNoClickAction().assertWidthIsEqualTo(100.dp).assertHeightIsEqualTo(60.dp)
            compose.onNodeWithTag("child", useUnmergedTree = true).assertWidthIsEqualTo(100.dp).assertHeightIsEqualTo(60.dp)
            val plain = compose.onNodeWithTag("plain").captureToImage().asAndroidBitmap()
            assertEquals(android.graphics.Color.BLUE, plain.getPixel(50, 30))
            assertNotEquals("Rounded surface clips overflowing children", android.graphics.Color.BLUE, plain.getPixel(0, 0))
            assertEquals(Color.Green, observed)
            val content = compose.onNodeWithTag("content").captureToImage().asAndroidBitmap()
            assertEquals(android.graphics.Color.RED, content.getPixel(98, 38))
            assertTrue("Default text consumes contentColor", (0 until content.height).any { y ->
                (0 until content.width).any { x -> content.getPixel(x, y) == android.graphics.Color.GREEN }
            })
            compose.onNodeWithTag("click").performTouchInput { click() }; settle()
            assertEquals(1, clicks)
            compose.onNodeWithTag("disabled").assertIsNotEnabled().performTouchInput { click() }; settle()
            assertEquals(1, clicks)
            capture("surfaces-${backend.name}-$dark")
        }
    }

    @Test fun dividerKeepsDefaultAndExplicitThicknessAndSolidColor() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, dark) {
                Column(Modifier.width(120.dp).background(Color.White)) {
                    HorizontalDivider(Modifier.testTag("default"))
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(Modifier.testTag("explicit"), thickness = 4.dp, color = Color.Magenta)
                }
            }
            // 0.75dp rounds to one layout pixel at mdpi, matching the existing native divider.
            compose.onNodeWithTag("default").assertHeightIsEqualTo(1.dp)
            compose.onNodeWithTag("explicit").assertWidthIsEqualTo(120.dp).assertHeightIsEqualTo(4.dp)
            val pixels = compose.onNodeWithTag("explicit").captureToImage().asAndroidBitmap()
            for (y in 0 until pixels.height) assertEquals(android.graphics.Color.MAGENTA, pixels.getPixel(60, y))
            capture("dividers-${backend.name}-$dark")
        }
    }

    @Test fun actualStoreDownloadBadgeUsesSurfaceWithoutInvokingInstaller() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            var clicks = 0
            open(backend, dark) {
                RearStoreDownloadBadge("Fixture download", onClick = { clicks++ })
            }
            compose.onNodeWithText("Fixture download").assertIsDisplayed().performTouchInput { click() }
            settle()
            assertEquals(1, clicks)
            capture("store-badge-${backend.name}-$dark")
        }
    }
    @Test fun nativeCheckboxRetainsSelectionDisabledAndReadOnlyContracts() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            var checked by mutableStateOf(false)
            var callbacks = 0
            open(backend, dark) {
                Column {
                    hk.uwu.mixui.components.Checkbox(checked, { checked = it; callbacks++ }, Modifier.testTag("check"))
                    hk.uwu.mixui.components.Checkbox(false, { callbacks++ }, Modifier.testTag("disabled-check"), enabled = false)
                    hk.uwu.mixui.components.Checkbox(true, null, Modifier.testTag("readonly-check"))
                }
            }
            compose.onNodeWithTag("check").assertIsOff().performTouchInput { click() }; settle()
            compose.onNodeWithTag("check").assertIsOn()
            assertEquals(1, callbacks)
            compose.onNodeWithTag("check").performTouchInput { click() }; settle()
            compose.onNodeWithTag("check").assertIsOff()
            assertEquals(2, callbacks)
            compose.onNodeWithTag("disabled-check").assertIsNotEnabled().performTouchInput { click() }; settle()
            compose.onNodeWithTag("readonly-check").assertHasNoClickAction()
            assertEquals(2, callbacks)
            capture("checkbox-${backend.name}-$dark")
        }
    }

}
