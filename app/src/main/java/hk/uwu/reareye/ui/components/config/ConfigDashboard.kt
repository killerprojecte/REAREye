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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigCategory
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

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
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenScriptManagement: () -> Unit,
    onMoreSearchRequested: () -> Unit,
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
    var selectedTab by remember(selectedTabIndex) {
        mutableStateOf(DashboardTab.entries.getOrNull(selectedTabIndex) ?: DashboardTab.CARDS)
    }
    // Keep each manager composed after its first visit. This avoids tearing down its loaded
    // state and starting a second repository load on every tab switch.
    var loadedTabMask by rememberSaveable {
        mutableStateOf(1 shl (selectedTabIndex.coerceIn(0, DashboardTab.entries.lastIndex)))
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
    ) {
        TopAppBar(
            title = stringResource(
                when (selectedTab) {
                    DashboardTab.CARDS -> R.string.config_tab_cards
                    DashboardTab.COMPONENTS -> R.string.config_tab_components
                    DashboardTab.WALLPAPERS -> R.string.config_tab_wallpapers
                    DashboardTab.MORE -> R.string.configuration_title
                }
            ),
            actions = {
                when (selectedTab) {
                    DashboardTab.CARDS -> IconButton(onClick = {
                        pendingAction = ConfigDashboardAction.ADD_CARD
                    }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.rear_widget_add_card)
                        )
                    }

                    DashboardTab.COMPONENTS -> IconButton(onClick = {
                        pendingAction = ConfigDashboardAction.ADD_COMPONENT
                    }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.rear_widget_add_business)
                        )
                    }

                    DashboardTab.WALLPAPERS -> {
                        IconButton(onClick = {
                            pendingAction = ConfigDashboardAction.IMPORT_WALLPAPER
                        }) {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = stringResource(R.string.rear_wallpaper_import)
                            )
                        }
                        IconButton(onClick = {
                            pendingAction = ConfigDashboardAction.REFRESH_WALLPAPER
                        }) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.rear_wallpaper_refresh)
                            )
                        }
                    }

                    DashboardTab.MORE -> IconButton(onClick = onMoreSearchRequested) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.config_more_search),
                        )
                    }
                }
            },
            scrollBehavior = scrollBehavior,
        )
        TabRowWithContour(
            tabs = tabs,
            selectedTabIndex = selectedTab.ordinal,
            onTabSelected = ::selectTab,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = 4.dp,
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 8.dp,
                ),
        )
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            val pageWidth = maxWidth
            DashboardTab.entries.forEach { tab ->
                if (loadedTabMask and (1 shl tab.ordinal) == 0) return@forEach
                key(tab) {
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
                                bottomPadding = contentPadding.calculateBottomPadding(),
                                onOpenCategory = onOpenCategory,
                                onOpenScriptManagement = onOpenScriptManagement,
                                onOpenFavoriteCategory = onOpenFavoriteCategory,
                                favoriteNodeCount = favoriteNodeCount,
                                initialScrollIndex = moreScrollIndex,
                                initialScrollOffset = moreScrollOffset,
                                onScrollChanged = onMoreScrollChanged,
                            )
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
    bottomPadding: Dp,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenScriptManagement: () -> Unit,
    onOpenFavoriteCategory: () -> Unit,
    favoriteNodeCount: Int,
    initialScrollIndex: Int,
    initialScrollOffset: Int,
    onScrollChanged: (Int, Int) -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollIndex,
        initialFirstVisibleItemScrollOffset = initialScrollOffset,
    )
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
            .overScrollVertical()
            .scrollEndHaptic(),
        contentPadding = PaddingValues(bottom = bottomPadding + 12.dp),
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
                            tint = MiuixTheme.colorScheme.primary
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
                            tint = MiuixTheme.colorScheme.primary
                        )
                    },
                    onClick = onOpenScriptManagement,
                )
            }
        }
        items(categories, key = { it.group.id }) { entry ->
            MoreSurface {
                ArrowPreference(
                    title = stringResource(entry.group.titleRes),
                    summary = stringResource(entry.group.descriptionRes),
                    startAction = {
                        Icon(
                            entry.group.icon,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary,
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
