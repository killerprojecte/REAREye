package hk.uwu.reareye.ui.components.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigGroup
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigNode
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.PrefsManager
import kotlinx.coroutines.flow.distinctUntilChanged
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TabRow
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

private data class DashboardSearchEntry(val item: ConfigItem, val path: List<Int>)

private data class MoreEntry(
    val title: String,
    val summary: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

private fun flattenConfigItems(
    nodes: List<ConfigNode>,
    path: List<Int> = emptyList(),
): List<DashboardSearchEntry> = nodes.flatMap { node ->
    when (node) {
        is ConfigItem -> listOf(DashboardSearchEntry(node, path))
        is ConfigCategory -> flattenConfigItems(node.children, path + node.titleRes)
        is ConfigGroup -> flattenConfigItems(node.children, path)
    }
}

private fun List<ConfigNode>.category(titleRes: Int): ConfigCategory? {
    for (node in this) {
        when (node) {
            is ConfigCategory -> {
                if (node.titleRes == titleRes) return node
                node.children.category(titleRes)?.let { return it }
            }

            is ConfigGroup -> node.children.category(titleRes)?.let { return it }
            is ConfigItem -> Unit
        }
    }
    return null
}

private fun List<ConfigNode>.firstManager(type: ConfigType.ManagerType): ConfigItem? =
    flattenConfigItems(this).map { it.item }.firstOrNull {
        (it.type as? ConfigType.Manager)?.managerType == type
    }

/** Four object tabs. Legacy categories are intentionally confined to More. */
@Composable
fun ConfigDashboard(
    nodes: List<ConfigNode>,
    prefsManager: PrefsManager,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenAppList: (ConfigItem) -> Unit,
    onOpenManager: (ConfigItem) -> Unit,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
    onOpenFavoriteCategory: () -> Unit,
    favoriteNodeCount: Int,
    favoriteNodeIds: Set<String>,
    resolveFavoriteNodeId: (ConfigNode) -> String?,
    onToggleFavorite: (ConfigNode) -> Unit,
    selectedTabIndex: Int = 0,
    onSelectedTabIndexChange: (Int) -> Unit = {},
    moreScrollIndex: Int = 0,
    moreScrollOffset: Int = 0,
    onMoreScrollChanged: (Int, Int) -> Unit = { _, _ -> },
    cardContent: @Composable (String?, () -> Unit, ConfigDashboardAction?, () -> Unit) -> Unit = { _, _, _, _ -> },
    componentContent: @Composable ((String) -> Unit, ConfigDashboardAction?, () -> Unit) -> Unit = { _, _, _ -> },
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

                    DashboardTab.MORE -> Unit
                }
            },
            scrollBehavior = scrollBehavior,
        )
        TabRow(
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
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        ) {
            DashboardTab.entries.forEach { tab ->
                if (loadedTabMask and (1 shl tab.ordinal) == 0) return@forEach
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (selectedTab == tab) 1f else 0f)
                        .alpha(if (selectedTab == tab) 1f else 0f),
                ) {
                    when (tab) {
                        DashboardTab.CARDS -> cardContent(
                            focusCardId,
                            { focusCardId = null },
                            pendingAction.takeIf { selectedTab == DashboardTab.CARDS },
                        ) { pendingAction = null }

                        DashboardTab.COMPONENTS -> componentContent(
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
                            nodes = nodes,
                            prefsManager = prefsManager,
                            bottomPadding = contentPadding.calculateBottomPadding(),
                            onOpenCategory = onOpenCategory,
                            onOpenAppList = onOpenAppList,
                            onOpenManager = onOpenManager,
                            onPreferenceChanged = onPreferenceChanged,
                            onOpenFavoriteCategory = onOpenFavoriteCategory,
                            favoriteNodeCount = favoriteNodeCount,
                            favoriteNodeIds = favoriteNodeIds,
                            resolveFavoriteNodeId = resolveFavoriteNodeId,
                            onToggleFavorite = onToggleFavorite,
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

@Composable
private fun MoreTab(
    nodes: List<ConfigNode>,
    prefsManager: PrefsManager,
    bottomPadding: Dp,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenAppList: (ConfigItem) -> Unit,
    onOpenManager: (ConfigItem) -> Unit,
    onPreferenceChanged: (ConfigItem) -> Unit,
    onOpenFavoriteCategory: () -> Unit,
    favoriteNodeCount: Int,
    favoriteNodeIds: Set<String>,
    resolveFavoriteNodeId: (ConfigNode) -> String?,
    onToggleFavorite: (ConfigNode) -> Unit,
    initialScrollIndex: Int,
    initialScrollOffset: Int,
    onScrollChanged: (Int, Int) -> Unit,
) {
    val subscreen = nodes.category(R.string.category_subscreencenter)
    val module = nodes.category(R.string.category_module_settings)
    val system = nodes.category(R.string.category_system)
    val weather = nodes.category(R.string.category_weather)
    val gallery = nodes.category(R.string.category_gallery)
    val theme = nodes.category(R.string.category_thememanager)
    val misc = nodes.category(R.string.category_misc)
    val lyrics = nodes.category(R.string.subcategory_lyrics)
    val music = subscreen?.children?.filterIsInstance<ConfigCategory>()?.firstOrNull {
        it.titleRes == R.string.cfg_music_control_whitelist
    }
    val sceneRoute = nodes.firstManager(ConfigType.ManagerType.SCENE_ROUTE)
    val systemBehaviorKeys = setOf(
        ConfigKeys.SUBSCREEN_DOUBLE_TAP_SLEEP_DISABLED_APPS,
        ConfigKeys.SUBSCREEN_DOUBLE_TAP_WAKE_DISABLED_APPS,
        ConfigKeys.SUBSCREEN_HIGH_LOAD_MODE_DISABLED_APPS,
    )
    // Keep the remaining system settings in compatibility; the three app lists above have their
    // own System behavior page below.
    val systemCompatibility = ConfigGroup(
        children = system?.children.orEmpty().filter { node ->
            node !is ConfigItem || node.key !in systemBehaviorKeys
        },
    )
    // Keep every non-manager rear-screen switch reachable from the new flow. The core
    // managers are represented by the Cards/Components/Wallpapers tabs, while media and
    // lyrics keep their dedicated application-oriented category.
    val rearManagerCompatibility = ConfigGroup(
        children = nodes.category(R.string.category_subscreencenter)
            ?.children
            ?.filterIsInstance<ConfigCategory>()
            ?.firstOrNull { it.titleRes == R.string.rear_widget_manager_category }
            ?.children
            .orEmpty()
            .filter { node ->
                node !is ConfigItem || (
                        node.type !is ConfigType.Manager &&
                                node.key != ConfigKeys.HOOK_ALLOW_REAR_FOCUS_NOTICES
                        )
            },
    )
    val systemBehavior = ConfigGroup(
        children = system?.children.orEmpty().filter { node ->
            node is ConfigItem && node.key in systemBehaviorKeys
        },
    )
    val subscreenCompatibility = subscreen?.children.orEmpty().filter { node ->
        when (node) {
            is ConfigCategory -> node.titleRes != R.string.rear_widget_manager_category &&
                    node.titleRes != R.string.cfg_music_control_whitelist &&
                    node.titleRes != R.string.subcategory_lyrics

            is ConfigItem -> node.type !is ConfigType.Manager && node.key !in setOf(
                ConfigKeys.HOOK_VIDEO_LOOPING,
                ConfigKeys.HOOK_VIDEO_WALLPAPER_RESUME_PROGRESS,
                ConfigKeys.VIDEO_WALLPAPER_VOLUME,
            )

            is ConfigGroup -> true
        }
    }.let { ConfigGroup(children = it) }
    val rearEnhancement = ConfigCategory(
        key = "dashboard_more_rear_enhancement",
        titleRes = R.string.config_more_rear_enhancement_title,
        descriptionRes = R.string.config_more_rear_enhancement_desc,
        children = buildList {
            val focusNotice = ConfigGroup(
                children = subscreen?.children.orEmpty()
                    .filterIsInstance<ConfigCategory>()
                    .firstOrNull { it.titleRes == R.string.rear_widget_manager_category }
                    ?.children
                    .orEmpty()
                    .filter { it is ConfigItem && it.key == ConfigKeys.HOOK_ALLOW_REAR_FOCUS_NOTICES },
            )
            val videoBehavior = ConfigGroup(
                children = subscreen?.children.orEmpty().filter {
                    it is ConfigItem && it.key in setOf(
                        ConfigKeys.HOOK_VIDEO_LOOPING,
                        ConfigKeys.HOOK_VIDEO_WALLPAPER_RESUME_PROGRESS,
                        ConfigKeys.VIDEO_WALLPAPER_VOLUME,
                    )
                },
            )
            focusNotice.takeIf { it.children.isNotEmpty() }?.let(::add)
            videoBehavior.takeIf { it.children.isNotEmpty() }?.let(::add)
        },
    )

    val media = ConfigCategory(
        key = "dashboard_more_media",
        titleRes = R.string.config_more_media_title,
        descriptionRes = R.string.config_more_media_desc,
        children = listOfNotNull(music, lyrics),
    )
    val systemModules = ConfigCategory(
        key = "dashboard_more_system",
        titleRes = R.string.config_more_system_title,
        descriptionRes = R.string.config_more_system_desc,
        children = listOfNotNull(module, theme),
    )
    val behavior = ConfigCategory(
        key = "dashboard_more_behavior",
        titleRes = R.string.config_more_behavior_title,
        descriptionRes = R.string.config_more_behavior_desc,
        children = listOfNotNull(
            systemBehavior.takeIf { it.children.isNotEmpty() },
        ),
    )
    val compatibility = ConfigCategory(
        key = "dashboard_more_compatibility",
        titleRes = R.string.config_more_compatibility_title,
        descriptionRes = R.string.config_more_compatibility_desc,
        children = listOfNotNull(
            systemCompatibility.takeIf { it.children.isNotEmpty() },
            rearManagerCompatibility.takeIf { it.children.isNotEmpty() },
            subscreenCompatibility.takeIf { it.children.isNotEmpty() },
            misc,
        ),
    )
    val legacySettings = ConfigCategory(
        key = "dashboard_more_legacy_settings",
        titleRes = R.string.config_more_legacy_settings_title,
        descriptionRes = R.string.config_more_legacy_settings_desc,
        children = nodes,
    )
    val appCategories = ConfigCategory(
        key = "dashboard_more_host_apps",
        titleRes = R.string.config_more_host_apps_title,
        descriptionRes = R.string.config_more_host_apps_desc,
        children = listOfNotNull(gallery, weather),
    )
    val entries = listOf(
        MoreEntry(
            title = stringResource(R.string.config_more_route_title),
            summary = stringResource(R.string.config_more_route_desc),
            icon = Icons.Filled.Notifications,
            onClick = { sceneRoute?.let(onOpenManager) },
        ),
        MoreEntry(
            title = stringResource(R.string.config_more_host_apps_title),
            summary = stringResource(R.string.config_more_host_apps_desc),
            icon = Icons.Filled.Image,
            onClick = { onOpenCategory(appCategories) },
        ),
        MoreEntry(
            title = stringResource(R.string.config_more_media_title),
            summary = stringResource(R.string.config_more_media_desc),
            icon = Icons.Rounded.Extension,
            onClick = { onOpenCategory(media) },
        ),
        MoreEntry(
            title = stringResource(R.string.config_more_system_title),
            summary = stringResource(R.string.config_more_system_desc),
            icon = Icons.Filled.Settings,
            onClick = { onOpenCategory(systemModules) },
        ),
        MoreEntry(
            title = stringResource(R.string.config_more_compatibility_title),
            summary = stringResource(R.string.config_more_compatibility_desc),
            icon = Icons.Filled.Build,
            onClick = { onOpenCategory(compatibility) },
        ),
        MoreEntry(
            title = stringResource(R.string.config_more_behavior_title),
            summary = stringResource(R.string.config_more_behavior_desc),
            icon = Icons.Filled.Settings,
            onClick = { onOpenCategory(behavior) },
        ),
        MoreEntry(
            title = stringResource(R.string.config_more_rear_enhancement_title),
            summary = stringResource(R.string.config_more_rear_enhancement_desc),
            icon = Icons.Rounded.Extension,
            onClick = { onOpenCategory(rearEnhancement) },
        ),
        MoreEntry(
            title = stringResource(R.string.config_more_legacy_settings_title),
            summary = stringResource(R.string.config_more_legacy_settings_desc),
            icon = Icons.Filled.Settings,
            onClick = { onOpenCategory(legacySettings) },
        ),
    )
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
        items(entries, key = { it.title }) { entry ->
            MoreSurface {
                ArrowPreference(
                    title = entry.title,
                    summary = entry.summary,
                    startAction = {
                        Icon(
                            entry.icon,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary
                        )
                    },
                    onClick = entry.onClick,
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
