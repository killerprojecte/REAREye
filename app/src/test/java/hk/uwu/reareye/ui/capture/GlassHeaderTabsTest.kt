package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.config.ConfigDashboard
import hk.uwu.reareye.ui.components.config.buildMoreCategories
import hk.uwu.reareye.ui.config.REAREyeConfig
import java.io.File
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

/** Real dashboard tabs over real scrolling rows, not a standalone glass preview. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GlassHeaderTabsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private val selected = mutableStateOf(3)
    private var scrollPosition = 0 to 0

    @Before fun requireCaptureProfile() {
        assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun save(folder: File, name: String) {
        folder.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun label(id: Int) = activity!!.get().getString(id)

    @Test fun productionHeaderGlassTabsKeepSelectionAcrossScrolledBackgrounds() {
        val output = File(System.getProperty("ui.capture.output"), "glass-header-tabs")
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            compose.runOnUiThread {
                activity?.close()
                selected.value = 3
                scrollPosition = 0 to 0
                activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
                activity!!.get().setContent {
                    CaptureTheme(backend, dark) {
                        ConfigDashboard(
                            moreCategories = buildMoreCategories(REAREyeConfig),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            selectedTabIndex = selected.value,
                            onSelectedTabIndexChange = { selected.value = it },
                            onOpenCategory = {},
                            onOpenScriptManagement = {},
                            onMoreSearchRequested = {},
                            onOpenStore = {},
                            onOpenFavoriteCategory = {},
                            favoriteNodeCount = 0,
                            onMoreScrollChanged = { index, offset -> scrollPosition = index to offset },
                        )
                    }
                }
            }
            settle()
            val folder = File(output, "$backend/${if (dark) "dark" else "light"}")
            compose.onNodeWithTag("glass-tabs-$backend").assertIsDisplayed()
            compose.onNode(hasText(label(R.string.config_tab_more)) and hasClickAction()).assertIsSelected()
            save(folder, "initial-more.png")
            val list = compose.onNodeWithTag("config-more-list")
            list.performTouchInput { swipeUp() }; settle()
            assertTrue(scrollPosition.first > 0 || scrollPosition.second > 0)
            save(folder, "scrolled-more.png")
            val restoredPosition = scrollPosition
            compose.onNode(hasText(label(R.string.config_tab_cards)) and hasClickAction()).performTouchInput { click() }
            settle()
            assertEquals(0, selected.value)
            compose.onNode(hasText(label(R.string.config_tab_cards)) and hasClickAction()).assertIsSelected()
            save(folder, "selected-cards.png")
            compose.onNode(hasText(label(R.string.config_tab_more)) and hasClickAction()).performTouchInput { click() }
            settle()
            assertEquals(3, selected.value)
            assertEquals(restoredPosition, scrollPosition)
            compose.onNode(hasText(label(R.string.config_tab_more)) and hasClickAction()).assertIsSelected()
            save(folder, "returned-scrolled-more.png")
            list.performTouchInput { swipeDown() }; settle()
            save(folder, "scroll-back-more.png")
            File(folder, "evidence.txt").writeText(
                "Production ConfigDashboard; $backend; dark=$dark; API 36 native graphics.\n" +
                    "Verified native glass adapter, pointer selection, selected semantics and preserved More scroll.\n" +
                    "PNGs cover rest, scrolling backgrounds, changed selection and return.\n" +
                    "Robolectric captures are not physical-device shader certification.\n",
            )
        }
    }
}
