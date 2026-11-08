package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.components.Text
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.config.ConfigDashboard
import hk.uwu.reareye.ui.components.config.buildMoreCategories
import hk.uwu.reareye.ui.config.REAREyeConfig
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Production ConfigDashboard, real categories and pointer input; not MainActivity integration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigDashboardTopBarTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var searchClicks = 0
    private var storeClicks = 0
    private var categoryClicks = 0
    private var favoriteClicks = 0
    private var scriptClicks = 0
    private var scrollPosition = 0 to 0
    private val selectedTab = mutableStateOf(3)
    private val categories = buildMoreCategories(REAREyeConfig)

    @Before fun requireCaptureProfile() {
        assumeTrue("Run with -PuiCapture=true", java.lang.Boolean.getBoolean("ui.capture.enabled"))
        compose.mainClock.autoAdvance = false
    }
    @After fun close() {
        compose.runOnUiThread { activity?.close(); activity = null }
        compose.mainClock.autoAdvance = true
    }
    private fun settle() {
        repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() }
    }
    private fun open(backend: String, dark: Boolean) {
        compose.runOnUiThread {
            activity?.close()
            searchClicks = 0; storeClicks = 0; categoryClicks = 0
            favoriteClicks = 0; scriptClicks = 0; scrollPosition = 0 to 0
            selectedTab.value = 3
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                CaptureTheme(backend, dark) {
                    Column(Modifier.fillMaxSize()) {
                        Text("FIXTURE HOST · production ConfigDashboard · $backend · ${if (dark) "dark" else "light"}")
                        ConfigDashboard(
                            modifier = Modifier.weight(1f),
                            moreCategories = categories,
                            contentPadding = PaddingValues(bottom = 24.dp),
                            selectedTabIndex = selectedTab.value,
                            onSelectedTabIndexChange = { selectedTab.value = it },
                            onOpenCategory = { categoryClicks++ },
                            onOpenScriptManagement = { scriptClicks++ },
                            onMoreSearchRequested = { searchClicks++ },
                            onOpenStore = { storeClicks++ },
                            onOpenFavoriteCategory = { favoriteClicks++ },
                            favoriteNodeCount = 0,
                            onMoreScrollChanged = { index, offset -> scrollPosition = index to offset },
                        )
                    }
                }
            }
        }
        settle()
    }
    private fun text(res: Int) = activity!!.get().getString(res)
    private fun tapText(res: Int) {
        compose.onNodeWithText(text(res)).performTouchInput { click() }
        settle()
    }
    private fun save(folder: File, name: String) {
        folder.mkdirs()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(folder, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun actualMorePageScrollClicksAndTabsInBothBackendsAndThemes() {
        val output = File(System.getProperty("ui.capture.output"), "config-dashboard-topbar")
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            open(backend, dark)
            val folder = File(output, "$backend/${if (dark) "dark" else "light"}")
            val list = compose.onNodeWithTag("config-more-list")
            val search = compose.onNodeWithContentDescription(text(R.string.config_more_search))
            search.assertIsDisplayed()
            // The list viewport must extend behind the bar, not start below it.
            assertTrue(list.fetchSemanticsNode().boundsInRoot.top < search.fetchSemanticsNode().boundsInRoot.bottom)
            save(folder, "initial.png")
            search.performTouchInput { click() }; settle()
            compose.onNodeWithContentDescription(text(R.string.store_navigation)).performTouchInput { click() }
            settle()
            tapText(R.string.config_favorites_title)
            tapText(R.string.config_more_scripts_title)
            compose.onNodeWithText(text(categories.first().group.titleRes)).performTouchInput { click() }
            settle()
            assertEquals(1, searchClicks); assertEquals(1, storeClicks)
            assertEquals(1, favoriteClicks); assertEquals(1, scriptClicks); assertEquals(1, categoryClicks)

            list.performTouchInput { swipeUp() }; settle()
            assertTrue("More must scroll", scrollPosition.first > 0 || scrollPosition.second > 0)
            save(folder, "scrolled.png")
            val restoredPosition = scrollPosition
            tapText(R.string.config_tab_cards)
            assertEquals(0, selectedTab.value)
            tapText(R.string.config_tab_more)
            assertEquals(3, selectedTab.value)
            assertEquals("Tab switching preserves More scroll", restoredPosition, scrollPosition)
            search.assertIsDisplayed()
            search.performTouchInput { click() }; settle()
            assertEquals(2, searchClicks)
            save(folder, "returned-scrolled.png")
            // A second movement supplies different real rows to the native bar backdrop.
            list.performTouchInput { swipeDown() }; settle()
            save(folder, "scroll-back.png")
            File(folder, "evidence.txt").writeText(
                "Production ConfigDashboard/MoreTab with real REAREye categories.\n" +
                "Native graphics Robolectric API 36; $backend; dark=$dark.\n" +
                "Pointer clicks: search (twice), store, favorite, scripts, category, Cards/More tabs.\n" +
                "Scroll offset restored across tabs; full-height list supplies bar backdrop.\n" +
                "Navigation callbacks observed only; destination pages and MainActivity not exercised.\n" +
                "Inspect PNGs for glass rendering; geometry/click checks are not device shader certification.\n",
            )
        }
    }
}
