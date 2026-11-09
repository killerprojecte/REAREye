package hk.uwu.reareye.ui.components.config

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import hk.uwu.mixui.components.LocalPageTopBarScrollConnection
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.featureGuideAnchor
import kotlinx.coroutines.flow.distinctUntilChanged
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.TopBarIconButton
import hk.uwu.mixui.components.GlassTopBarScaffold
import hk.uwu.mixui.components.pageScrollFeedback
import hk.uwu.mixui.components.GlassTabs
import hk.uwu.mixui.components.PreferenceRow
import hk.uwu.mixui.components.ArrowPreference
import hk.uwu.mixui.theme.MixTheme

private enum class DashboardTab { CARDS, COMPONENTS, WALLPAPERS, MORE }

enum class ConfigDashboardAction {
    ADD_CARD,
    ADD_COMPONENT,
    IMPORT_WALLPAPER,
    REFRESH_WALLPAPER,
}

/** Four object tabs; More uses the same categories as its search results. */
@Composable
internal fun ConfigDashboard(
    moreCategories: List<MoreCategory>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenScriptManagement: () -> Unit,
    onMoreSearchRequested: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenFavoriteCategory: () -> Unit,
    favoriteNodeCount: Int,
    selectedTabIndex: Int = 0,
    onSelectedTabIndexChange: (Int) -> Unit = {},
    moreScrollIndex: Int = 0,
    moreScrollOffset: Int = 0,
    onMoreScrollChanged: (Int, Int) -> Unit = { _, _ -> },
    cardContent: @Composable (String?, () -> Unit, (String) -> Unit, ConfigDashboardAction?, () -> Unit) -> Unit = { _, _, _, _, _ -> },
    componentContent: @Composable (String?, () -> Unit, (String) -> Unit, ConfigDashboardAction?, () -> Unit) -> Unit = { _, _, _, _, _ -> },
    wallpaperContent: @Composable (ConfigDashboardAction?, () -> Unit) -> Unit = { _, _ -> },
) {
    // Keep the More list at page scope so its glass bar observes the actual scroll.
    val pages = remember { DashboardTab.entries.associateWith { DashboardPage() } }
    val moreListState = rememberLazyListState(moreScrollIndex, moreScrollOffset)
    var selectedTab by remember(selectedTabIndex) {
        mutableStateOf(DashboardTab.entries.getOrNull(selectedTabIndex) ?: DashboardTab.CARDS)
    }
    // Keep each manager composed after its first visit. This avoids tearing down its loaded
    // state and starting a second repository load on every tab switch.
    var loadedTabMask by rememberSaveable {
        mutableStateOf(1 shl (selectedTabIndex.coerceIn(0, DashboardTab.entries.lastIndex)))
    }
    // Programmatic navigation must load the destination just like a tab tap.
    LaunchedEffect(selectedTab) {
        loadedTabMask = loadedTabMask or (1 shl selectedTab.ordinal)
    }
    var focusCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusComponentBusiness by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<ConfigDashboardAction?>(null) }

    fun selectTab(index: Int) {
        val tab = DashboardTab.entries.getOrNull(index) ?: return
        selectedTab = tab
        loadedTabMask = loadedTabMask or (1 shl tab.ordinal)
        onSelectedTabIndexChange(index)
    }

    val tabs = listOf(
        stringResource(R.string.config_tab_cards),
        stringResource(R.string.config_tab_components),
        stringResource(R.string.config_tab_wallpapers),
        stringResource(R.string.config_tab_more),
    )
    val tabTransition = updateTransition(selectedTab, label = "ConfigDashboardTabs")

    val activeList = if (selectedTab == DashboardTab.MORE) moreListState else pages.getValue(selectedTab).listState
    GlassTopBarScaffold(
        modifier = modifier.fillMaxSize(),
        isContentScrolled = activeList?.canScrollBackward == true,
        contentListState = activeList,
        // Use the controls' native material directly over the page backdrop.
        // The bar still owns collapse, material visibility and button motion.
        inheritMiuixBarMaterial = false,
        attachNestedScrollToContent = false,
        topBarModifier = Modifier.featureGuideAnchor("config_header_${selectedTab.ordinal}"),
        title = stringResource(
            when (selectedTab) {
                DashboardTab.CARDS -> R.string.config_tab_cards
                DashboardTab.COMPONENTS -> R.string.config_tab_components
                DashboardTab.WALLPAPERS -> R.string.config_tab_wallpapers
                DashboardTab.MORE -> R.string.configuration_title
            }
        ),
        navigationIcon = {
            TopBarIconButton(
                onClick = onOpenStore,
                modifier = Modifier.featureGuideAnchor("config_store_button"),
            ) {
                Icon(
                    Icons.Outlined.CloudDownload,
                    contentDescription = stringResource(R.string.store_navigation),
                )
            }
        },
        actions = {
            when (selectedTab) {
                DashboardTab.CARDS -> TopBarIconButton(onClick = {
                    pendingAction = ConfigDashboardAction.ADD_CARD
                }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.rear_widget_add_card)
                    )
                }

                DashboardTab.COMPONENTS -> TopBarIconButton(onClick = {
                    pendingAction = ConfigDashboardAction.ADD_COMPONENT
                }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.rear_widget_add_business)
                    )
                }

                DashboardTab.WALLPAPERS -> {
                    TopBarIconButton(onClick = {
                        pendingAction = ConfigDashboardAction.IMPORT_WALLPAPER
                    }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.rear_wallpaper_import)
                        )
                    }
                    TopBarIconButton(onClick = {
                        pendingAction = ConfigDashboardAction.REFRESH_WALLPAPER
                    }) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.rear_wallpaper_refresh)
                        )
                    }
                }

                DashboardTab.MORE -> TopBarIconButton(onClick = onMoreSearchRequested) {
                    Icon(
                        Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.config_more_search),
                    )
                }
            }
        },
        pinnedHeader = {
            Column {
            GlassTabs(
                tabs = tabs,
                selectedTabIndex = selectedTab.ordinal,
                onTabSelected = ::selectTab,
                modifier = Modifier
                    .featureGuideAnchor("config_tabs")
                    .fillMaxWidth()
                    .padding(
                        top = 4.dp,
                        start = 12.dp,
                        end = 12.dp,
                        bottom = 8.dp,
                    ),
            )
            pages.getValue(selectedTab).header?.invoke()
            }
        },
    ) { pagePadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize(),
        ) {
            val pageWidth = maxWidth
            val scrollConnection = LocalPageTopBarScrollConnection.current
            DashboardTab.entries.forEach { tab ->
                if (tab != selectedTab && loadedTabMask and (1 shl tab.ordinal) == 0) return@forEach
                key(tab) {
                    // Header heights differ (Cards has a second tab row). A hidden page
                    // retains its own padding instead of being relaid out by another tab.
                    val retainedPadding = remember { mutableStateOf(pagePadding) }
                    if (tab == selectedTab) SideEffect { retainedPadding.value = pagePadding }
                    val tabPosition by tabTransition.animateFloat(
                        transitionSpec = {
                            tween(durationMillis = 320, easing = FastOutSlowInEasing)
                        },
                        label = "${tab.name}Position",
                    ) { selected ->
                        when {
                            tab.ordinal < selected.ordinal -> -1f
                            tab.ordinal > selected.ordinal -> 1f
                            else -> 0f
                        }
                    }
                    val isTransitionPage =
                        tab == tabTransition.currentState || tab == tabTransition.targetState
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // Offscreen retained pages cannot send scroll deltas to the bar.
                            .then(if (tab == selectedTab && scrollConnection != null)
                                Modifier.nestedScroll(scrollConnection) else Modifier)
                            .zIndex(
                                when (tab) {
                                    tabTransition.targetState -> 1f
                                    tabTransition.currentState -> 0.5f
                                    else -> 0f
                                }
                            )
                            .graphicsLayer {
                                alpha = if (isTransitionPage) 1f else 0f
                                translationX = tabPosition * pageWidth.toPx()
                            },
                    ) {
                        CompositionLocalProvider(
                            LocalDashboardPage provides pages.getValue(tab),
                            LocalDashboardPadding provides if (tab == selectedTab) pagePadding else retainedPadding.value,
                        ) {
                            when (tab) {
                                DashboardTab.CARDS -> cardContent(
                                    focusCardId,
                                    { focusCardId = null },
                                    { business ->
                                        focusComponentBusiness = business
                                        selectTab(DashboardTab.COMPONENTS.ordinal)
                                    },
                                    pendingAction.takeIf { selectedTab == DashboardTab.CARDS },
                                ) { pendingAction = null }

                                DashboardTab.COMPONENTS -> componentContent(
                                    focusComponentBusiness,
                                    { focusComponentBusiness = null },
                                    { cardId ->
                                        focusCardId = cardId
                                        selectTab(DashboardTab.CARDS.ordinal)
                                    },
                                    pendingAction.takeIf { selectedTab == DashboardTab.COMPONENTS },
                                ) { pendingAction = null }

                                DashboardTab.WALLPAPERS -> wallpaperContent(
                                    pendingAction.takeIf { selectedTab == DashboardTab.WALLPAPERS },
                                ) { pendingAction = null }

                                DashboardTab.MORE -> MoreTab(
                                    categories = moreCategories,
                                    topPadding = pagePadding.calculateTopPadding(),
                                    bottomPadding = maxOf(contentPadding.calculateBottomPadding(), pagePadding.calculateBottomPadding()),
                                    listState = moreListState,
                                    onOpenCategory = onOpenCategory,
                                    onOpenScriptManagement = onOpenScriptManagement,
                                    onOpenFavoriteCategory = onOpenFavoriteCategory,
                                    favoriteNodeCount = favoriteNodeCount,
                                    onScrollChanged = onMoreScrollChanged,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreTab(
    categories: List<MoreCategory>,
    topPadding: Dp,
    bottomPadding: Dp,
    listState: LazyListState,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenScriptManagement: () -> Unit,
    onOpenFavoriteCategory: () -> Unit,
    favoriteNodeCount: Int,
    onScrollChanged: (Int, Int) -> Unit,
) {
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.distinctUntilChanged().collect { (index, offset) ->
            onScrollChanged(index, offset)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("config-more-list")
            .pageScrollFeedback(),
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding + 12.dp),
        overscrollEffect = null,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "more_favorites") {
            MoreSurface {
                ArrowPreference(
                    title = stringResource(R.string.config_favorites_title),
                    summary = if (favoriteNodeCount == 0) stringResource(R.string.config_favorites_empty)
                    else stringResource(R.string.config_favorites_count, favoriteNodeCount),
                    startAction = {
                        Icon(
                            Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = MixTheme.colorScheme.primary
                        )
                    },
                    onClick = onOpenFavoriteCategory,
                )
            }
        }
        item(key = "more_script_management") {
            MoreSurface {
                ArrowPreference(
                    title = stringResource(R.string.config_more_scripts_title),
                    summary = stringResource(R.string.config_more_scripts_desc),
                    startAction = {
                        Icon(
                            Icons.Rounded.Code,
                            contentDescription = null,
                            tint = MixTheme.colorScheme.primary
                        )
                    },
                    onClick = onOpenScriptManagement,
                )
            }
        }
        items(categories, key = { it.group.id }) { entry ->
            MoreSurface {
                PreferenceRow(
                    title = stringResource(entry.group.titleRes),
                    summary = stringResource(entry.group.descriptionRes),
                    startAction = {
                        Icon(
                            entry.group.icon,
                            contentDescription = null,
                            tint = MixTheme.colorScheme.primary,
                        )
                    },
                    onClick = { onOpenCategory(entry.category) },
                )
            }
        }
    }
}

@Composable
private fun MoreSurface(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) { content() }
}
