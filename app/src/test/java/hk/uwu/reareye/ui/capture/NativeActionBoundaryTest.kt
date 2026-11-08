package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.DisplayCutoutCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.contributor.ContributorLoadState
import hk.uwu.reareye.repository.contributor.ContributorProfile
import hk.uwu.reareye.ui.screen.AboutScreenContent
import java.io.File
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeActionBoundaryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private fun settle() { repeat(10) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun open(backend: MixUiBackend, rtl: Boolean = false, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend, MixColorSchemeMode.Dark) {
                    CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                        content()
                    }
                }
            }
        }
        settle()
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    @Test fun nativeButtonRemainsIndependentInsideClickableCard() {
        for (backend in MixUiBackend.entries) {
            var parent = 0; var child = 0
            open(backend) {
                Card(onClick = { parent++ }) {
                    Text("Parent")
                    Button(onClick = { child++ }) { Text("Child action") }
                }
            }
            compose.onNodeWithText("Child action").performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals("$backend accessibility child", 1, child)
            assertEquals(0, parent)
            compose.onNodeWithText("Child action").performTouchInput { click() }
            settle()
            assertEquals("$backend touch child", 2, child)
            assertEquals(0, parent)
            compose.onNodeWithText("Parent").performSemanticsAction(SemanticsActions.OnClick) { it() }
            assertEquals(1, parent); assertEquals(2, child)
        }
    }
    @Test fun actualAboutSecondaryHeaderClearsBothSideInsets() {
        for (backend in MixUiBackend.entries) for (rtl in listOf(false, true)) {
            open(backend, rtl) {
                AboutScreenContent(ContributorLoadState.Loaded(listOf(
                    ContributorProfile("Inset contributor", "Actual contributor row"),
                )))
            }
            compose.runOnUiThread {
                ViewCompat.dispatchApplyWindowInsets(activity!!.get().window.decorView,
                    WindowInsetsCompat.Builder()
                        .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24, 0, 0))
                        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(24, 0, 24, 24))
                        .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(), Insets.of(24, 0, 24, 24))
                        .setDisplayCutout(DisplayCutoutCompat(Rect(48, 0, 48, 0),
                            listOf(Rect(0, 0, 48, 100), Rect(352, 0, 400, 100))))
                        .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(48, 0, 48, 0))
                        .setVisible(WindowInsetsCompat.Type.systemBars(), true)
                        .setVisible(WindowInsetsCompat.Type.displayCutout(), true)
                        .build())
            }
            settle()
            val title = activity!!.get().getString(R.string.credits_contributors_title)
            fun visible(): Boolean {
                val nodes = compose.onAllNodesWithText(title, useUnmergedTree = true)
                return nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
            }
            var attempts = 0
            while (!visible() && attempts++ < 15) {
                compose.onNodeWithTag("about-root-list").performTouchInput { swipeUp() }
                settle()
            }
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            settle()
            val back = compose.onNodeWithTag("about-back").assertIsDisplayed()
            val bounds = back.fetchSemanticsNode().boundsInRoot
            assertTrue("$backend rtl=$rtl header must clear both 48px sides: $bounds",
                bounds.left >= 48f && bounds.right <= 352f && bounds.top >= 24f)
            val row = compose.onNodeWithText("Inset contributor").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Body must also clear both sides: $row", row.left >= 48f && row.right <= 352f)
            val folder = File(System.getProperty("ui.capture.output"), "about-side-insets").apply { mkdirs() }
            val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            File(folder, "${backend.name}-${if (rtl) "rtl" else "ltr"}.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            back.performClick(); settle()
            compose.onNodeWithTag("about-root-list").assertIsDisplayed()
        }
    }
}
