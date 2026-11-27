package hk.uwu.reareye.ui.capture

import android.app.Application
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import hk.uwu.reareye.ui.FeatureGuideDemoState
import hk.uwu.reareye.ui.FeatureGuideDemoContext
import hk.uwu.reareye.ui.LocalFeatureGuideDemo
import hk.uwu.reareye.ui.components.config.CardManagerScreen
import hk.uwu.reareye.ui.components.config.BusinessManagerScreen
import hk.uwu.reareye.ui.components.config.RearWallpaperManagerScreen
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    private var compositionScope: CoroutineScope? = null
    private var searchClicks = 0
    private var storeClicks = 0
    private var categoryClicks = 0
    private var favoriteClicks = 0
    private var scriptClicks = 0
    private var cardPage: hk.uwu.reareye.ui.components.config.DashboardPage? = null
    private var businessPage: hk.uwu.reareye.ui.components.config.DashboardPage? = null
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
    private fun open(backend: String, dark: Boolean, managers: Boolean = false, linkTargetBusiness: String? = null) {
        compose.runOnUiThread {
            activity?.close()
            searchClicks = 0; storeClicks = 0; categoryClicks = 0
            favoriteClicks = 0; scriptClicks = 0; scrollPosition = 0 to 0
            cardPage = null; businessPage = null; compositionScope = null
            selectedTab.value = if (managers) 0 else 3
            val fixture = FeatureGuideDemoState("Fixture clock").apply {
                cards = (1..16).map {
                    cards.first().copy(id = "fixture-card-$it", title = "Fixture clock $it",
                        business = linkTargetBusiness ?: cards.first().business)
                }
                businesses = (1..16).map {
                    // Production loadBusinesses sorts by ID, not insertion/numeric order.
                    // Keep the linked widget_12 at index 11 rather than lexicographic index 3.
                    val id = if (linkTargetBusiness != null) "fixture-business-${it.toString().padStart(2, '0')}"
                        else "fixture-business-$it"
                    businesses.first().copy(id = id, business = "fixture_widget_$it")
                }
            }
            fixture.prefsManager.putString(ConfigKeys.REAR_WIDGET_CARD_DATA, RearWidgetConfigCodec.encodeCards(fixture.cards))
            fixture.prefsManager.putString(ConfigKeys.REAR_WIDGET_BUSINESS_DATA, RearWidgetConfigCodec.encodeBusinesses(fixture.businesses))
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity!!.get().setContent {
                val scope = rememberCoroutineScope()
                SideEffect { compositionScope = scope }
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
                            cardContent = { focus, handled, openBusiness, action, actionHandled ->
                                val page = hk.uwu.reareye.ui.components.config.LocalDashboardPage.current
                                androidx.compose.runtime.SideEffect { cardPage = page }
                                if (managers) CardManagerScreen(
                                    prefsManager = fixture.prefsManager, onBack = {}, embedded = true,
                                    focusCardId = focus, onFocusCardHandled = handled, onOpenComponent = openBusiness,
                                    actionRequest = action, onActionHandled = actionHandled,
                                )
                            },
                            componentContent = { focus, handled, openCard, action, actionHandled ->
                                val page = hk.uwu.reareye.ui.components.config.LocalDashboardPage.current
                                androidx.compose.runtime.SideEffect { businessPage = page }
                                if (managers) BusinessManagerScreen(
                                    prefsManager = fixture.prefsManager, onBack = {}, embedded = true,
                                    focusBusiness = focus, onFocusBusinessHandled = handled, onOpenCard = openCard,
                                    actionRequest = action, onActionHandled = actionHandled,
                                )
                            },
                            wallpaperContent = { action, actionHandled ->
                                if (managers) CompositionLocalProvider(
                                    LocalFeatureGuideDemo provides FeatureGuideDemoContext(fixture, null, {}, {}),
                                ) {
                                    RearWallpaperManagerScreen(
                                        prefsManager = fixture.prefsManager, onBack = {}, embedded = true,
                                        actionRequest = action, onActionHandled = actionHandled,
                                    )
                                }
                            },
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

    @Test fun moreEntryRowsUseTheSameNativeDescriptionAndWholeRowActions() {
        val output = File(System.getProperty("ui.capture.output"), "config-more-native-entries")
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            open(backend, dark)
            val favoriteSummary = compose.onNodeWithText(text(R.string.config_favorites_empty))
            val scriptSummary = compose.onNodeWithText(text(R.string.config_more_scripts_desc))
            val categorySummary = compose.onNodeWithText(text(categories.first().group.descriptionRes))
            // All configuration entries share the same backend's description layout.
            assertEquals(categorySummary.fetchSemanticsNode().boundsInRoot.left,
                favoriteSummary.fetchSemanticsNode().boundsInRoot.left, 0.5f)
            assertEquals(categorySummary.fetchSemanticsNode().boundsInRoot.left,
                scriptSummary.fetchSemanticsNode().boundsInRoot.left, 0.5f)
            favoriteSummary.performTouchInput { down(center); cancel() }; settle()
            scriptSummary.performTouchInput { down(center); cancel() }; settle()
            assertEquals(0, favoriteClicks); assertEquals(0, scriptClicks)
            repeat(2) {
                favoriteSummary.performTouchInput { click() }; settle()
                scriptSummary.performTouchInput { click() }; settle()
            }
            tapText(R.string.config_favorites_title)
            tapText(R.string.config_more_scripts_title)
            assertEquals(3, favoriteClicks); assertEquals(3, scriptClicks)
            save(File(output, "$backend/${if (dark) "dark" else "light"}"), "entries.png")
        }
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
    @Test fun realCardToComponentColdAndWarmJumpsRestoreGlassWithoutTouch() {
        val output = File(System.getProperty("ui.capture.output"), "glass-interaction-refinement/dashboard-card-component")
        for (dark in listOf(false, true)) {
            open("miuix", dark, managers = true, linkTargetBusiness = "fixture_widget_12")
            val folder = File(output, if (dark) "dark" else "light")
            fun openLinkedComponent() {
                // Real card action and the Dashboard navigation callback, not a fixture tab/scroll mutation.
                compose.onNode(hasContentDescription(text(R.string.rear_widget_action_open_component)) and
                    hasAnyAncestor(hasTestTag("rear-card-item-fixture-card-1")), useUnmergedTree = true)
                    .assertIsDisplayed().performClick()
                try {
                    compose.waitUntil(10_000) {
                        compose.mainClock.advanceTimeBy(100)
                        // Direct state reads do not synchronize the Android layout of the
                        // newly composed Components page; semantics queries elsewhere do.
                        compose.waitForIdle()
                        businessPage?.listState?.let { it.firstVisibleItemIndex >= 10 && !it.isScrollInProgress } == true
                    }
                } catch (timeout: ComposeTimeoutException) {
                    val list = businessPage?.listState
                    throw AssertionError("Real card->component dark=$dark selectedTab=${selectedTab.value}, " +
                        "measured items=${list?.layoutInfo?.totalItemsCount}, " +
                        "position=${list?.firstVisibleItemIndex}:${list?.firstVisibleItemScrollOffset}, " +
                        "scrolling=${list?.isScrollInProgress}, canScrollBack=${list?.canScrollBackward}", timeout)
                }
                settle()
                assertEquals(1, selectedTab.value)
                assertTrue("BusinessManager's data-loaded animateScrollToItem must finish without a body gesture",
                    businessPage!!.listState!!.firstVisibleItemIndex >= 10)
                compose.onNode(hasText("fixture_widget_12") and
                    hasAnyAncestor(hasTestTag("BusinessManagerScreen-list"))).assertIsDisplayed()
            }
            save(folder, "cards-before-cold-navigation.png")
            openLinkedComponent()
            val state = businessPage!!.listState!!
            val firstTarget = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            save(folder, "components-cold-jump-no-touch.png")
            // Programmatic return-to-top, without a compensating gesture on the body.
            compose.runOnIdle { requireNotNull(compositionScope).launch { state.animateScrollToItem(0) } }
            settle()
            compose.mainClock.advanceTimeUntil(timeoutMillis = 5000) { !state.isScrollInProgress }
            compose.waitForIdle()
            assertEquals(0, state.firstVisibleItemIndex)
            assertEquals(0, state.firstVisibleItemScrollOffset)
            save(folder, "components-programmatic-top.png")
            tapText(R.string.config_tab_cards)
            openLinkedComponent()
            assertEquals(firstTarget, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
            save(folder, "components-warm-jump-no-touch.png")
            // Native gesture reference, then restore the selected page without losing its anchor.
            compose.onNodeWithTag("BusinessManagerScreen-list").performTouchInput { swipeUp() }; settle()
            compose.mainClock.advanceTimeUntil(timeoutMillis = 5000) { !state.isScrollInProgress }
            compose.waitForIdle()
            val manual = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            save(folder, "components-native-scroll-reference.png")
            tapText(R.string.config_tab_cards)
            tapText(R.string.config_tab_components)
            assertEquals(manual, state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset)
            save(folder, "components-restored-page-no-touch.png")
            File(folder, "evidence.txt").writeText(
                "Real card ModuleStyleIconAction -> Dashboard focus -> BusinessManager data-loaded animateScrollToItem.\n" +
                    "Cold target=$firstTarget; repeated loaded target, programmatic top, native scroll reference and page restoration.\n" +
                    "No body gesture occurs before either jump capture. Native graphics Robolectric, not physical-device shader proof.\n")
        }
    }

    @Test fun realManagersShareDashboardGlassAndPreserveIndependentScroll() {
        val output = File(System.getProperty("ui.capture.output"), "dashboard-real-managers")
        for (backend in listOf("miuix", "mochi")) for (dark in listOf(false, true)) {
            open(backend, dark, managers = true)
            val folder = File(output, "$backend/${if (dark) "dark" else "light"}")
            fun list(tag: String) = compose.onNodeWithTag(tag)
            fun position(tag: String) = list(tag).fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange].value()
            fun bodyUnderBar(tag: String) {
                val bar = compose.onNodeWithContentDescription(text(R.string.store_navigation))
                assertTrue("Real manager viewport must extend behind shared glass: $tag",
                    list(tag).fetchSemanticsNode().boundsInRoot.top < bar.fetchSemanticsNode().boundsInRoot.bottom)
            }
            val cards = "CardManagerScreen-list"
            val apps = "CardManagerScreen-app-list"
            val business = "BusinessManagerScreen-list"
            val wallpaper = "RearWallpaperManagerScreen-list"
            bodyUnderBar(cards)
            save(folder, "cards-top.png")
            list(cards).performTouchInput { swipeUp() }; settle()
            assertTrue(position(cards) > 0f)
            // Pointer swipe launches a real native fling. A fixed animation delay
            // is not its completion; do not compare a still-moving offset to a later frame.
            val initialMoving = cardPage!!.listState!!.isScrollInProgress
            compose.mainClock.advanceTimeUntil(timeoutMillis = 5000) { !cardPage!!.listState!!.isScrollInProgress }
            compose.waitForIdle()
            val cardsPosition = position(cards)
            val cardState = cardPage!!.listState!!
            val cardAnchor = cardState.firstVisibleItemIndex to cardState.firstVisibleItemScrollOffset
            val timeline = mutableListOf("movingAfterFixedDelay=$initialMoving", "before=$cardAnchor")
            fun recordCard(step: String) { timeline += "$step=${cardState.firstVisibleItemIndex to cardState.firstVisibleItemScrollOffset}" }
            save(folder, "cards-scrolled.png")
            tapText(R.string.rear_widget_card_tab_app)
            bodyUnderBar(apps)
            recordCard("selected-apps")
            list(apps).performTouchInput { swipeUp() }; settle()
            save(folder, "apps-up.png")
            recordCard("apps-up")
            list(apps).performTouchInput { swipeDown() }; settle()
            save(folder, "apps-down.png")
            recordCard("apps-down")
            tapText(R.string.rear_widget_card_tab_normal)
            save(folder, "cards-returned.png")
            recordCard("selected-normal")
            File(folder, "card-state-timeline.txt").writeText(timeline.joinToString("\n"))
            File(folder, "card-return-metrics.txt").writeText(
                "before=$cardAnchor semantic=$cardsPosition\nafter=${cardState.firstVisibleItemIndex to cardState.firstVisibleItemScrollOffset} semantic=${position(cards)}\n")
            assertEquals("Normal/App switch preserves Normal list", cardsPosition, position(cards), 1f)
            tapText(R.string.config_tab_components)
            bodyUnderBar(business)
            assertEquals(0f, position(business), 0f)
            list(business).performTouchInput { swipeUp() }; settle()
            assertTrue(position(business) > 0f)
            compose.mainClock.advanceTimeUntil(timeoutMillis = 5000) { !businessPage!!.listState!!.isScrollInProgress }
            compose.waitForIdle()
            val businessPosition = position(business)
            save(folder, "components-scrolled.png")
            tapText(R.string.config_tab_wallpapers)
            bodyUnderBar(wallpaper)
            list(wallpaper).performTouchInput { swipeUp() }; settle()
            // One real guide wallpaper is a boundary case, not a fabricated long list.
            assertEquals(0f, position(wallpaper), 0f)
            save(folder, "wallpaper-up-short.png")
            list(wallpaper).performTouchInput { swipeDown() }; settle()
            assertEquals(0f, position(wallpaper), 0f)
            save(folder, "wallpaper-down-short.png")
            tapText(R.string.config_tab_components)
            assertEquals(businessPosition, position(business), 1f)
            save(folder, "components-return.png")
            repeat(12) {
                if (position(business) > 0f) {
                    list(business).performTouchInput {
                        swipe(androidx.compose.ui.geometry.Offset(center.x, height * 0.4f),
                            androidx.compose.ui.geometry.Offset(center.x, height * 0.9f), durationMillis = 350)
                    }; settle()
                }
            }
            assertEquals(0f, position(business), 0f)
            save(folder, "components-restored-top.png")
            tapText(R.string.config_tab_cards)
            assertEquals(cardsPosition, position(cards), 1f)
            save(folder, "cards-return.png")
            repeat(12) {
                if (position(cards) > 0f) {
                    list(cards).performTouchInput {
                        swipe(androidx.compose.ui.geometry.Offset(center.x, height * 0.4f),
                            androidx.compose.ui.geometry.Offset(center.x, height * 0.9f), durationMillis = 350)
                    }; settle()
                }
            }
            assertEquals(0f, position(cards), 0f)
            save(folder, "cards-restored-top.png")
            File(folder, "evidence.txt").writeText(
                "Production Dashboard and three actual managers. Card/business use ordinary memory prefs, no guide override.\n" +
                "Wallpaper uses the single guide item; Apps uses its real empty/runtime-unavailable catalog.\n" +
                "Pointer up/down, Normal/Apps and Cards/Components/Wallpapers switches, real list positions and viewport overlap.\n" +
                "PNG inspection required for native glass appearance; not physical-device shader certification.\n",
            )
        }
    }

}
