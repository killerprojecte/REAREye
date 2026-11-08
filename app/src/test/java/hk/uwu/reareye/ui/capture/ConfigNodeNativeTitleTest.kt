package hk.uwu.reareye.ui.capture

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.mixui.MixColorSchemeMode
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixUiHost
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.GuideMemoryPrefs
import hk.uwu.reareye.ui.config.*
import hk.uwu.reareye.ui.screen.ConfigNodePage
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

/** Production Category/Favorites/Lyrics list, with in-memory preferences and no manager IO.
 * This tests page callbacks, not ConfigScreen's outer route stack or manager tab lifetime.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE, application = Application::class,
    qualifiers = "zh-rCN-w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigNodeNativeTitleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var activity: ActivityController<ComponentActivity>? = null
    private var favoriteIds by mutableStateOf(emptySet<String>())
    private val opened = mutableListOf<String>()
    private var backs = 0
    private fun frames() { repeat(12) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle() } }
    private fun label(id: Int) = activity!!.get().getString(id)
    private fun open(backend: MixUiBackend, nodes: List<ConfigNode>, favorites: Boolean = false, dark: Boolean = false) {
        compose.mainClock.autoAdvance = false
        opened.clear(); backs = 0; favoriteIds = emptySet()
        val prefs = PrefsManager(GuideMemoryPrefs())
        compose.runOnUiThread {
            activity?.close()
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().enableEdgeToEdge()
            activity!!.get().setContent {
                MixUiHost(backend, if (dark) MixColorSchemeMode.Dark else MixColorSchemeMode.Light) {
                    Box(Modifier.fillMaxSize()) {
                        ConfigNodePage(
                            title = if (favorites) "Fixture Favorites" else "Fixture Category",
                            nodes = nodes,
                            prefsManager = prefs,
                            bottomObstruction = 96.dp,
                            onBack = { backs++ },
                            onOpenCategory = { opened += "category:${it.key}" },
                            onOpenAppList = { opened += "apps:${it.key}" },
                            onOpenManager = { opened += "manager:${it.key}" },
                            favoriteNodeIds = favoriteIds,
                            resolveFavoriteNodeId = { it.key },
                            onToggleFavorite = { node ->
                                node.key?.let { id -> favoriteIds = if (id in favoriteIds) favoriteIds - id else favoriteIds + id }
                            },
                            emptyStateRes = if (favorites) R.string.config_favorites_empty else null,
                        )
                        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(96.dp)
                            .testTag("config-bottom-overlay"))
                    }
                }
            }
        }
        frames()
    }
    private fun capture(name: String) {
        val folder = File(System.getProperty("ui.capture.output"), "config-native-lists").apply { mkdirs() }
        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
        File(folder, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @After fun close() {
        compose.mainClock.autoAdvance = true
        compose.runOnUiThread { activity?.close(); activity = null }
    }
    @Test fun actualCategoryAndFavoritesUseNativeHeadersAndBackCallbacks() {
        for (backend in MixUiBackend.entries) for (favorites in listOf(false, true)) for (dark in listOf(false, true)) {
            open(backend, emptyList(), favorites, dark)
            val title = if (favorites) "Fixture Favorites" else "Fixture Category"
            val titles = compose.onAllNodesWithText(title, useUnmergedTree = true)
            assertTrue(titles.fetchSemanticsNodes().isNotEmpty())
            val layouts = titles.fetchSemanticsNodes().indices.flatMap { index ->
                val result = mutableListOf<TextLayoutResult>()
                titles[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
                result
            }
            if (backend == MixUiBackend.Mochi) {
                assertTrue("Native Mochi 34sp bold large header", layouts.any {
                    it.layoutInput.style.fontSize == 34.sp && it.layoutInput.style.fontWeight == FontWeight.Bold
                })
            } else {
                assertTrue("Native MIUIX expanded large header", layouts.any {
                    it.layoutInput.style.fontSize.value >= 28f
                })
            }
            if (favorites) compose.onNodeWithText(label(R.string.config_favorites_empty)).assertIsDisplayed()
            capture("${backend.name}-${if (dark) "dark" else "light"}-${if (favorites) "favorites" else "category"}")
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).performClick()
            compose.runOnIdle { assertEquals(1, backs) }
        }
    }
    @Test fun actualRowsPreserveCategoryAppListAndManagerCallbacks() {
        val nodes = listOf(
            ConfigCategory(key = "child", titleRes = R.string.config_tab_more),
            ConfigItem("apps", R.string.config_tab_components, type = ConfigType.AppList()),
            ConfigItem("manager", R.string.config_tab_cards, type = ConfigType.Manager(ConfigType.ManagerType.CARD)),
        )
        for (backend in MixUiBackend.entries) for (dark in listOf(false, true)) {
            open(backend, nodes, dark = dark)
            capture("${backend.name}-${if (dark) "dark" else "light"}-populated-category")
            for (id in listOf(R.string.config_tab_more, R.string.config_tab_components, R.string.config_tab_cards)) {
                compose.onNodeWithText(label(id)).performClick(); frames()
            }
            compose.runOnIdle { assertEquals(listOf("category:child", "apps:apps", "manager:manager"), opened) }
        }
    }
    @Test fun actualFavoritePopupCanDismissAddAndRemoveAcrossHosts() {
        val node = ConfigCategory(key = "favorite-node", titleRes = R.string.config_tab_more)
        for (backend in MixUiBackend.entries) {
            open(backend, listOf(node))
            fun longPressRow() {
                val row = compose.onNodeWithText(label(R.string.config_tab_more))
                row.performTouchInput { down(center) }
                compose.mainClock.advanceTimeBy(800); compose.waitForIdle()
                row.performTouchInput { up() }
                frames()
            }
            longPressRow()
            compose.onNodeWithText(label(R.string.config_favorite_add)).assertIsDisplayed()
            capture("${backend.name}-favorite-popup")
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            frames()
            compose.onNodeWithText(label(R.string.config_favorite_add)).assertDoesNotExist()
            assertEquals(0, backs)
            assertTrue(opened.isEmpty())
            longPressRow()
            compose.onNodeWithText(label(R.string.config_favorite_add)).performClick(); frames()
            assertEquals(setOf("favorite-node"), favoriteIds)
            // The production toggle deliberately keeps the popup open and refreshes its label.
            compose.onNodeWithText(label(R.string.config_favorite_remove)).assertIsDisplayed()
            compose.runOnUiThread { activity!!.get().onBackPressedDispatcher.onBackPressed() }
            frames()
            compose.onNodeWithText(label(R.string.config_favorite_remove)).assertDoesNotExist()
            longPressRow()
            compose.onNodeWithText(label(R.string.config_favorite_remove)).performClick(); frames()
            assertTrue(favoriteIds.isEmpty())
            compose.onNodeWithText(label(R.string.config_favorite_add)).assertIsDisplayed()
            assertTrue(opened.isEmpty())
        }
    }

    @Test fun actualLongListHasOneScrollContainerAndClearsBottomOverlay() {
        val nodes = List(40) { index -> ConfigCategory(
            key = "node-$index",
            titleRes = if (index == 39) R.string.config_tab_wallpapers else R.string.config_tab_more,
        ) }
        for (backend in MixUiBackend.entries) {
            open(backend, nodes)
            compose.onAllNodes(hasScrollAction()).assertCountEquals(1)
            compose.onNode(hasScrollAction()).performScrollToKey("node-39")
            frames()
            compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 10000f) }
            frames()
            val last = compose.onNodeWithText(label(R.string.config_tab_wallpapers))
            last.assertIsDisplayed()
            val overlayTop = compose.onNodeWithTag("config-bottom-overlay").fetchSemanticsNode().boundsInRoot.top
            assertTrue(last.fetchSemanticsNode().boundsInRoot.bottom < overlayTop)
            capture("${backend.name}-long-list-bottom")
            compose.onNode(hasScrollAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100000f) }
            frames()
            compose.onNodeWithContentDescription(label(R.string.scene_route_navigate_back)).assertIsDisplayed()
        }
    }
    @Test fun shortNotificationCategoryRestoresAfterBodyPullDown() {
        org.junit.Assume.assumeTrue(java.lang.Boolean.getBoolean("ui.capture.enabled"))
        val group = hk.uwu.reareye.ui.components.config.buildMoreCategories(REAREyeConfig)
            .first { it.group.id == "notifications_route" }
        for (dark in listOf(false, true)) {
            open(MixUiBackend.Miuix, group.category.children, dark = dark)
            val list = compose.onNode(hasScrollAction())
            val first = compose.onNodeWithText(label(requireNotNull(group.category.children.first().titleRes)))
            val initialTop = first.fetchSemanticsNode().boundsInRoot.top
            capture("miuix-short-notifications-$dark-expanded")
            list.performTouchInput { swipeUp(durationMillis = 500) }; frames()
            capture("miuix-short-notifications-$dark-collapsed")
            val collapsedTop = first.fetchSemanticsNode().boundsInRoot.top
            list.performTouchInput { swipeDown(durationMillis = 500) }; frames()
            capture("miuix-short-notifications-$dark-restored")
            val defaultStartRestoredTop = first.fetchSemanticsNode().boundsInRoot.top
            val rowCenter = first.fetchSemanticsNode().boundsInRoot.center
            val viewport = list.fetchSemanticsNode().boundsInRoot
            list.performTouchInput {
                val start = androidx.compose.ui.geometry.Offset(rowCenter.x - viewport.left, rowCenter.y - viewport.top)
                swipe(start, start.copy(y = (start.y + 300f).coerceAtMost(height - 1f)), durationMillis = 500)
            }; frames()
            val rowStartRestoredTop = first.fetchSemanticsNode().boundsInRoot.top
            capture("miuix-short-notifications-$dark-row-area-restored")
            assertEquals("An actual row-origin downward gesture restores the native title", initialTop, rowStartRestoredTop, 1.5f)
            list.performTouchInput { swipeUp(durationMillis = 500) }; frames()
            list.performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(center.x, height * 0.5f),
                    androidx.compose.ui.geometry.Offset(center.x, height * 0.85f), durationMillis = 500)
            }; frames()
            capture("miuix-short-notifications-$dark-blank-area-restored")
            val restoredTop = first.fetchSemanticsNode().boundsInRoot.top
            File(System.getProperty("ui.capture.output"), "config-native-lists/short-notifications-$dark.txt")
                .writeText("first row top: expanded=$initialTop collapsed=$collapsedTop defaultStartDown=$defaultStartRestoredTop rowStartDown=$rowStartRestoredTop blankAreaDown=$restoredTop\n")
            assertTrue("Upward body gesture must collapse native title", collapsedTop < initialTop)
            assertEquals("Downward body gesture must restore native title", initialTop, restoredTop, 1.5f)
        }
    }

}
