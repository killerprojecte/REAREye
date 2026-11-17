package hk.uwu.reareye.ui.components.navigation

import android.app.Application
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.NavigationBackdrop
import hk.uwu.mixui.components.NavigationQuickActionSurface
import hk.uwu.mixui.components.navigationBackdrop
import hk.uwu.mixui.components.rememberNavigationBackdrop
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
import org.robolectric.internal.ManifestFactory
import org.robolectric.internal.ManifestIdentifier
import kotlin.math.abs

/**
 * No app resources and no app manifest: the UI-capture profile's generated resource APK
 * has minSdk 36, so loading it cannot test an actual pre-RuntimeShader Android runtime.
 */
class NavigationNoResourcesTestRunner(testClass: Class<*>) : RobolectricTestRunner(testClass) {
    override fun getManifestFactory(config: Config): ManifestFactory = ManifestFactory {
        ManifestIdentifier("hk.uwu.reareye.navigation.noshader.fixture", null, null, null, emptyList())
    }
}

/**
 * Library material fallback only: public NavigationQuickActionSurface in the same window.
 * Not an app, navigation-gesture or Popup test. SDK36's real-navigation matrix covers
 * Popup interaction; this resource-free SDK28 fixture checks actual shader absence.
 */
@RunWith(NavigationNoResourcesTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationQuickActionNoShaderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private lateinit var backdrop: NavigationBackdrop
    private var changed by mutableStateOf(false)
    private var show by mutableStateOf(true)

    @After fun close() {
        compose.runOnUiThread { activity?.close() }
    }
    private fun settle() {
        repeat(8) { compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
    }
    private fun launch(backend: MixUiBackend, dark: Boolean) {
        compose.runOnUiThread {
            activity?.close()
            changed = false; show = true
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend = backend,
                    colorMode = if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    val page = rememberNavigationBackdrop()
                    SideEffect { backdrop = page }
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().then(
                            if (page.isQuickActionRecordingRequested)
                                Modifier.navigationBackdrop(page, recordNavigation = false)
                            else Modifier,
                        ).background(if (changed) Color.Blue else Color.Red))
                        if (show) NavigationQuickActionSurface(backdrop = page,
                            modifier = Modifier.align(Alignment.Center).size(176.dp, 40.dp)
                                .testTag("no-shader-entry")) {
                            // An inert foreground mark avoids fonts and application resources.
                            Box(Modifier.size(8.dp).background(Color.Green))
                        }
                    }
                }
            }
        }
        settle()
    }

    @Test fun noRuntimeShaderUsesOpaqueNativeFallbackWithoutRecording() {
        assertEquals("Actual pre-RuntimeShader sandbox, not a mocked capability", 28, Build.VERSION.SDK_INT)
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            launch(backend, dark)
            val node = compose.onNodeWithTag("no-shader-entry")
            val a = node.captureToImage().toPixelMap()
            compose.runOnIdle { assertFalse("$backend dark=$dark idle source", backdrop.isQuickActionRecordingRequested) }
            compose.runOnIdle { changed = true }
            settle()
            val b = node.captureToImage().toPixelMap()
            var difference = 0f
            for (x in a.width / 4 until a.width * 3 / 4) {
                val y = a.height / 4
                difference += abs(a[x, y].red - b[x, y].red) + abs(a[x, y].green - b[x, y].green) +
                    abs(a[x, y].blue - b[x, y].blue)
                assertTrue("$backend dark=$dark opaque solid fallback", a[x, y].alpha > 0.99f)
            }
            assertTrue("$backend dark=$dark must not sample changing page without shaders", difference < 0.01f)
            compose.runOnIdle { show = false }
            settle()
            node.assertDoesNotExist()
            compose.runOnIdle { assertFalse("No leaked page-recording demand", backdrop.isQuickActionRecordingRequested) }
        }
    }
}
