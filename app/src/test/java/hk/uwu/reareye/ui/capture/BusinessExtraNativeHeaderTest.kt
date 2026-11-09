package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfig
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigRepository
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigFields
import hk.uwu.reareye.ui.components.config.BusinessExtraConfigManagerScreen
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



/** Production extra-settings manager and child page, with isolated in-memory preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BusinessExtraNativeHeaderTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun open(backend: MixUiBackend, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light, content = content)
            }
        }
        frames()
    }
    private fun back() {
        compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
        frames()
    }
    private fun capture(name: String) {
        val output = System.getProperty("ui.capture.output") ?: return
        val folder = File(output, "business-extra-native-header").apply { mkdirs() }
        val windowBitmap = compose.runOnUiThread {
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            if (dialog?.isShowing == true) captureDialogWindowBitmap(dialog)
            else captureWindowBitmap(activity!!.get().window)
        }
        File(folder, "$name-window.png").outputStream().use {
            check(windowBitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true)
        roots.fetchSemanticsNodes().indices.forEach { index ->
            val bitmap = roots[index].captureToImage().asAndroidBitmap()
            File(folder, "$name-root$index.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }

    private fun awaitText(value: String) {
        compose.waitUntil(10_000) {
            compose.mainClock.advanceTimeBy(100)
            compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
        }
        frames()
    }

    @Test(timeout = 60_000) fun managerAndChildKeepRealConfigurationAndReturnNavigation() {
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            val prefs = PrefsManager(GuideMemoryPrefs())
            repeat(24) { RearBusinessExtraConfigRepository.saveConfigForBusiness(prefs, "fixture.%02d".format(it), RearBusinessExtraConfig()) }
            var backs = 0
            open(backend, dark) { BusinessExtraConfigManagerScreen(prefs, onBack = { backs++ }) }
            awaitText("fixture.00")
            compose.onNodeWithContentDescription(label(R.string.rear_widget_business_extra_add)).assertIsEnabled()
            capture("${backend.name}-$dark-manager-top")
            compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }; frames()
            compose.onNode(hasScrollAction()).performScrollToIndex(24); frames()
            compose.onNodeWithText("fixture.23").assertIsDisplayed().performClick(); frames()
            awaitText(label(R.string.rear_widget_business_hide_time_tip))
            capture("${backend.name}-$dark-child")
            compose.onNodeWithText(label(R.string.rear_widget_business_hide_time_tip)).performClick(); frames()
            assertTrue(RearBusinessExtraConfigRepository.getConfigForBusiness(prefs, "fixture.23")
                .getBoolean(RearBusinessExtraConfigFields.HIDE_TIME_TIP, false))
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick(); frames()
            assertEquals(0, backs)
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
            assertEquals(1, backs)
        }
    }

    @Test(timeout = 60_000) fun disconnectedManagerDisablesItsCreateActionButKeepsBack() {
        for (backend in MixUiBackend.entries) {
            val memory = GuideMemoryPrefs()
            val unavailable = object : hk.uwu.reareye.hook.support.HookPrefs by memory {
                override fun isRemoteReady() = false
            }
            var backs = 0
            open(backend) { BusinessExtraConfigManagerScreen(PrefsManager(unavailable), onBack = { backs++ }) }
            compose.onNodeWithContentDescription(label(R.string.rear_widget_business_extra_add)).assertIsNotEnabled()
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
            assertEquals(1, backs)
        }
    }
}
