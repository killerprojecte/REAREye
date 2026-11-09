package hk.uwu.reareye.ui.capture

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.components.config.CustomBoundsCompatManagerScreen
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class, qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CustomBoundsNativeTitleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    @Before fun requireCapture() {
        Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    @Test fun populatedBoundsManagerKeepsNativeHeaderAndActionsAcrossThemes() {
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            var backs = 0
            val prefs = PrefsManager(GuideMemoryPrefs())
            prefs.putStringSet(ConfigKeys.CUSTOM_BOUNDS_COMPAT_APPS, (0..23).map { "test.bounds.app%02d".format(it) }.toSet())
            compose.runOnUiThread {
                activity?.close()
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    CaptureTheme(backend, dark) { CustomBoundsCompatManagerScreen(prefs) { backs++ } }
                }
            }
            settle()
            val context = activity!!.get()
            fun text(id: Int) = context.getString(id)
            fun picture(name: String) {
                val out = File(System.getProperty("ui.capture.output"), "custom-bounds-native/$backend/${if (dark) "dark" else "light"}").apply { mkdirs() }
                val image = compose.onRoot().captureToImage().asAndroidBitmap()
                File(out, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
            val back = compose.onNodeWithContentDescription(text(R.string.scene_route_navigate_back))
            val importButton = compose.onNodeWithContentDescription(text(R.string.script_import))
            // Package/rule loading uses Dispatchers.IO; advancing Compose's clock
            // alone is not completion of that real background load.
            compose.waitUntil(10_000) {
                compose.mainClock.advanceTimeBy(100)
                !importButton.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
            val add = compose.onNodeWithContentDescription(text(R.string.custom_bounds_pick_apps))
            back.assertIsDisplayed(); importButton.assertIsEnabled(); add.assertIsEnabled()
            picture("initial")
            compose.onNodeWithTag("bounds-manager-list").performTouchInput { swipeUp() }; settle()
            back.assertIsDisplayed(); importButton.assertIsDisplayed(); add.assertIsDisplayed()
            picture("scrolled")
            importButton.performTouchInput { click() }; settle()
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(context).nextStartedActivityForResult.intent.action)
            add.performTouchInput { click() }; settle()
            compose.onAllNodesWithText(text(R.string.custom_bounds_pick_apps)).onFirst().assertIsDisplayed()
            compose.runOnUiThread { context.onBackPressedDispatcher.onBackPressed() }; settle()
            back.assertIsDisplayed(); picture("returned-from-selector")
            back.performTouchInput { click() }; settle()
            assertEquals(1, backs)
        }
    }
}
