package hk.uwu.reareye.ui.screen

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.config.AppListSelectorScreen
import hk.uwu.reareye.ui.components.config.BusinessExtraConfigManagerScreen
import hk.uwu.reareye.ui.components.config.BusinessExtraConfigScreen
import hk.uwu.reareye.ui.components.config.BusinessManagerScreen
import hk.uwu.reareye.ui.components.config.CardManagerScreen
import hk.uwu.reareye.ui.components.config.ConfigDashboard
import hk.uwu.reareye.ui.components.config.ConfigNodeRow
import hk.uwu.reareye.ui.components.config.CustomBoundsCompatManagerScreen
import hk.uwu.reareye.ui.components.config.RearWallpaperManagerScreen
import hk.uwu.reareye.ui.components.config.SceneRouteManagerScreen
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigGroup
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigNode
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.ui.config.REAREyeConfig
import hk.uwu.reareye.ui.theme.AppThemeMode
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SpinnerDefaults
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.SpinnerItemImpl
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import java.util.IdentityHashMap

private sealed interface ConfigRoute {
    data object Root : ConfigRoute
    data object Favorites : ConfigRoute
    data class Category(val category: ConfigCategory) : ConfigRoute
    data class AppList(val item: ConfigItem) : ConfigRoute
    data object RearWallpaperManager : ConfigRoute
    data object BusinessManager : ConfigRoute
    data object SceneRouteManager : ConfigRoute
    data object CardManager : ConfigRoute
    data object BusinessExtraManager : ConfigRoute
    data class BusinessExtraDetail(val business: String) : ConfigRoute
    data object CustomBoundsCompatManager : ConfigRoute
}

private const val NAV_BAR_EXIT_DURATION_MS = 220L
private const val OVERLAY_ROUTE_EXIT_DURATION_MS = 220L

private data class FavoriteConfigNodeEntry(
    val id: String,
    val node: ConfigNode,
)

private data class FavoriteConfigNodeIndex(
    val entries: List<FavoriteConfigNodeEntry>,
    val nodeIdLookup: IdentityHashMap<ConfigNode, String>,
)

private data class ConfigAnimatedRoute(
    val route: ConfigRoute,
    val depth: Int,
)

private fun ConfigRoute.isOverlayRoute(): Boolean {
    return this is ConfigRoute.AppList ||
            this is ConfigRoute.RearWallpaperManager ||
            this is ConfigRoute.BusinessManager ||
            this is ConfigRoute.SceneRouteManager ||
            this is ConfigRoute.CardManager ||
            this is ConfigRoute.BusinessExtraManager ||
            this is ConfigRoute.BusinessExtraDetail ||
            this is ConfigRoute.CustomBoundsCompatManager
}

@SuppressLint("LocalContextResourcesRead")
@Composable
fun ConfigScreen(
    bottomInnerPadding: Dp = 0.dp,
    quickManagerTarget: ConfigType.ManagerType? = null,
    onQuickManagerTargetHandled: () -> Unit = {},
    onAppListModeChange: (Boolean) -> Unit = {},
    onThemeModeChange: (Int) -> Unit = {},
    onNavigationBarModeChange: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val prefsManager = remember { context.getPrefsManager() }

    fun findLyricsCategoryRoute(): ConfigRoute? {
        return findConfigCategoryByTitleRes(REAREyeConfig, R.string.subcategory_lyrics)?.let(
            ConfigRoute::Category
        )
    }

    fun managerRoute(managerType: ConfigType.ManagerType?): ConfigRoute? {
        return when (managerType) {
            ConfigType.ManagerType.REAR_WALLPAPER -> ConfigRoute.RearWallpaperManager
            ConfigType.ManagerType.BUSINESS -> ConfigRoute.BusinessManager
            ConfigType.ManagerType.SCENE_ROUTE -> ConfigRoute.SceneRouteManager
            ConfigType.ManagerType.CARD -> ConfigRoute.CardManager
            ConfigType.ManagerType.BUSINESS_EXTRA -> ConfigRoute.BusinessExtraManager
            ConfigType.ManagerType.BOUNDS -> ConfigRoute.CustomBoundsCompatManager
            ConfigType.ManagerType.LYRICS -> findLyricsCategoryRoute()
            null -> null
        }
    }

    var routeStack by remember {
        mutableStateOf(
            listOf(
                ConfigRoute.Root,
                managerRoute(quickManagerTarget)
            ).filterNotNull()
        )
    }
    var dashboardTabIndex by rememberSaveable { mutableStateOf(0) }
    var moreScrollIndex by rememberSaveable { mutableStateOf(0) }
    var moreScrollOffset by rememberSaveable { mutableStateOf(0) }
    val currentRoute = routeStack.last()
    val isOverlayMode = currentRoute.isOverlayRoute()
    val animatedRoute = remember(currentRoute, routeStack.size) {
        ConfigAnimatedRoute(
            route = currentRoute,
            depth = routeStack.size,
        )
    }
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val routeScope = rememberCoroutineScope()

    val favoriteNodeIndex = remember(REAREyeConfig) {
        buildFavoriteConfigNodeIndex(
            nodes = REAREyeConfig,
            resolveResourceEntryName = context.resources::getResourceEntryName,
        )
    }
    val availableFavoriteNodeIds = remember(favoriteNodeIndex.entries) {
        favoriteNodeIndex.entries.mapTo(mutableSetOf()) { it.id }
    }
    val storedFavoriteNodeIds = remember(prefsManager) {
        prefsManager.getStringSet(ConfigKeys.MODULE_FAVORITE_CONFIG_NODES, emptySet()).toSet()
    }
    val initialFavoriteNodeIds = remember(storedFavoriteNodeIds, availableFavoriteNodeIds) {
        storedFavoriteNodeIds.filterTo(mutableSetOf()) { it in availableFavoriteNodeIds }
    }
    var favoriteNodeIds by remember {
        mutableStateOf(initialFavoriteNodeIds.toSet())
    }
    val favoriteNodes = remember(favoriteNodeIds, favoriteNodeIndex.entries) {
        favoriteNodeIndex.entries
            .filter { favoriteNodeIds.contains(it.id) }
            .map { it.node }
    }

    LaunchedEffect(prefsManager, storedFavoriteNodeIds, initialFavoriteNodeIds) {
        if (storedFavoriteNodeIds != initialFavoriteNodeIds) {
            prefsManager.putStringSet(
                ConfigKeys.MODULE_FAVORITE_CONFIG_NODES,
                initialFavoriteNodeIds
            )
        }
    }

    fun toggleFavoriteNode(node: ConfigNode) {
        val nodeId = favoriteNodeIndex.nodeIdLookup[node] ?: return
        val nextFavoriteNodeIds = favoriteNodeIds.toMutableSet()
        if (!nextFavoriteNodeIds.add(nodeId)) {
            nextFavoriteNodeIds.remove(nodeId)
        }
        favoriteNodeIds = nextFavoriteNodeIds
        prefsManager.putStringSet(ConfigKeys.MODULE_FAVORITE_CONFIG_NODES, nextFavoriteNodeIds)
    }

    val handlePreferenceChanged = remember(
        prefsManager,
        onThemeModeChange,
        onNavigationBarModeChange,
    ) {
        { item: ConfigItem ->
            when (item.key) {
                ConfigKeys.MODULE_THEME_MODE -> {
                    onThemeModeChange(
                        prefsManager.getInt(
                            ConfigKeys.MODULE_THEME_MODE,
                            AppThemeMode.default.value,
                        )
                    )
                }

                ConfigKeys.MODULE_NAVIGATION_BAR_MODE -> {
                    onNavigationBarModeChange(
                        prefsManager.getInt(
                            ConfigKeys.MODULE_NAVIGATION_BAR_MODE,
                            ModuleNavigationBarMode.default.value,
                        )
                    )
                }
            }
        }
    }

    BackHandler(enabled = routeStack.size > 1) {
        if (isOverlayMode) {
            routeScope.launch {
                val newStack = routeStack.dropLast(1)
                routeStack = newStack
                delay(OVERLAY_ROUTE_EXIT_DURATION_MS)
                if (!newStack.last().isOverlayRoute()) {
                    onAppListModeChange(false)
                }
            }
        } else {
            routeStack = routeStack.dropLast(1)
        }
    }

    fun openOverlayRoute(route: ConfigRoute) {
        routeScope.launch {
            onAppListModeChange(true)
            delay(NAV_BAR_EXIT_DURATION_MS)
            routeStack = routeStack + route
        }
    }

    fun closeOverlayRoute() {
        routeScope.launch {
            routeStack = listOf(ConfigRoute.Root)
            delay(OVERLAY_ROUTE_EXIT_DURATION_MS)
            onAppListModeChange(false)
        }
    }

    fun openManagerRoute(managerType: ConfigType.ManagerType?) {
        val route = managerRoute(managerType)
        if (route != null) {
            openOverlayRoute(route)
        }
    }

    fun openManagerItem(item: ConfigItem) {
        openManagerRoute((item.type as? ConfigType.Manager)?.managerType)
    }

    LaunchedEffect(quickManagerTarget) {
        val managerType = quickManagerTarget ?: return@LaunchedEffect
        val route = managerRoute(managerType) ?: return@LaunchedEffect
        routeStack = listOf(ConfigRoute.Root, route)
        if (route.isOverlayRoute()) {
            onAppListModeChange(true)
        }
        onQuickManagerTargetHandled()
    }

    Scaffold(
        topBar = {
            if (!isOverlayMode && currentRoute != ConfigRoute.Root) {
                TopAppBar(
                    modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                    color = Color.Transparent,
                    title = when (currentRoute) {
                        ConfigRoute.Root -> stringResource(R.string.configuration_title)
                        ConfigRoute.Favorites -> stringResource(R.string.config_favorites_title)
                        is ConfigRoute.Category -> stringResource(currentRoute.category.titleRes)
                        else -> stringResource(R.string.configuration_title)
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        }
    ) { paddingValues ->
        AnimatedContent(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { clip = true },
            targetState = animatedRoute,
            contentKey = { it.route },
            transitionSpec = {
                if (targetState.route == ConfigRoute.Root) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                val forward = targetState.depth >= initialState.depth

                fadeIn(
                    animationSpec = tween(
                        durationMillis = 210,
                        delayMillis = 50,
                        easing = LinearOutSlowInEasing,
                    )
                ) + slideInHorizontally(
                    animationSpec = tween(
                        durationMillis = 280,
                        easing = FastOutSlowInEasing,
                    )
                ) { fullWidth ->
                    if (forward) fullWidth / 9 else -fullWidth / 9
                } togetherWith (
                        fadeOut(
                            animationSpec = tween(
                                durationMillis = 110,
                                easing = FastOutLinearInEasing,
                            )
                        ) + slideOutHorizontally(
                            animationSpec = tween(
                                durationMillis = 190,
                                easing = FastOutLinearInEasing,
                            )
                        ) { fullWidth ->
                            if (forward) -fullWidth / 12 else fullWidth / 12
                        }
                        )
                }
            },
            label = "ConfigRouteTransition"
        ) { target ->
            when (val route = target.route) {
                ConfigRoute.Root -> ConfigDashboard(
                    nodes = REAREyeConfig,
                    prefsManager = prefsManager,
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                    ),
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier.rearAcrylicSource(hazeState),
                    onOpenCategory = { category ->
                        // More is the owner of legacy setting categories. Keep that tab selected
                        // when the child route is popped back to the dashboard.
                        dashboardTabIndex = 3
                        routeStack = routeStack + ConfigRoute.Category(category)
                    },
                    onOpenAppList = { item ->
                        dashboardTabIndex = 3
                        openOverlayRoute(ConfigRoute.AppList(item))
                    },
                    onOpenManager = { item ->
                        dashboardTabIndex = 3
                        openManagerItem(item)
                    },
                    onPreferenceChanged = handlePreferenceChanged,
                    favoriteNodeCount = favoriteNodes.size,
                    onOpenFavoriteCategory = {
                        dashboardTabIndex = 3
                        routeStack = routeStack + ConfigRoute.Favorites
                    },
                    favoriteNodeIds = favoriteNodeIds,
                    resolveFavoriteNodeId = { node ->
                        favoriteNodeIndex.nodeIdLookup[node]
                    },
                    onToggleFavorite = { node ->
                        toggleFavoriteNode(node)
                    },
                    selectedTabIndex = dashboardTabIndex,
                    onSelectedTabIndexChange = { dashboardTabIndex = it },
                    moreScrollIndex = moreScrollIndex,
                    moreScrollOffset = moreScrollOffset,
                    onMoreScrollChanged = { index, offset ->
                        moreScrollIndex = index
                        moreScrollOffset = offset
                    },
                    cardContent = { focusCardId, onFocusHandled, actionRequest, onActionHandled ->
                        CardManagerScreen(
                            prefsManager = prefsManager,
                            onBack = {},
                            embedded = true,
                            contentPadding = PaddingValues(
                                bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                            ),
                            focusCardId = focusCardId,
                            onFocusCardHandled = onFocusHandled,
                            actionRequest = actionRequest,
                            onActionHandled = onActionHandled,
                        )
                    },
                    componentContent = { onCardRequested, actionRequest, onActionHandled ->
                        BusinessManagerScreen(
                            prefsManager = prefsManager,
                            onBack = {},
                            embedded = true,
                            contentPadding = PaddingValues(
                                bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                            ),
                            onOpenCard = onCardRequested,
                            actionRequest = actionRequest,
                            onActionHandled = onActionHandled,
                        )
                    },
                    wallpaperContent = { actionRequest, onActionHandled ->
                        RearWallpaperManagerScreen(
                            prefsManager = prefsManager,
                            onBack = {},
                            embedded = true,
                            contentPadding = PaddingValues(
                                bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                            ),
                            actionRequest = actionRequest,
                            onActionHandled = onActionHandled,
                        )
                    },
                )

                is ConfigRoute.Category -> ConfigNodeList(
                    nodes = route.category.children,
                    prefsManager = prefsManager,
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                    ),
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier.rearAcrylicSource(hazeState),
                    onOpenCategory = { category ->
                        routeStack = routeStack + ConfigRoute.Category(category)
                    },
                    onOpenAppList = { item ->
                        openOverlayRoute(ConfigRoute.AppList(item))
                    },
                    onOpenManager = { item -> openManagerItem(item) },
                    onPreferenceChanged = handlePreferenceChanged,
                    favoriteNodeIds = favoriteNodeIds,
                    resolveFavoriteNodeId = { node ->
                        favoriteNodeIndex.nodeIdLookup[node]
                    },
                    onToggleFavorite = { node ->
                        toggleFavoriteNode(node)
                    },
                )

                ConfigRoute.Favorites -> ConfigNodeList(
                    nodes = favoriteNodes,
                    prefsManager = prefsManager,
                    contentPadding = PaddingValues(
                        top = paddingValues.calculateTopPadding(),
                        bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                    ),
                    scrollBehavior = scrollBehavior,
                    modifier = Modifier.rearAcrylicSource(hazeState),
                    onOpenCategory = { category ->
                        routeStack = routeStack + ConfigRoute.Category(category)
                    },
                    onOpenAppList = { item ->
                        openOverlayRoute(ConfigRoute.AppList(item))
                    },
                    onOpenManager = { item -> openManagerItem(item) },
                    onPreferenceChanged = handlePreferenceChanged,
                    emptyStateRes = R.string.config_favorites_empty,
                    favoriteNodeIds = favoriteNodeIds,
                    resolveFavoriteNodeId = { node ->
                        favoriteNodeIndex.nodeIdLookup[node]
                    },
                    onToggleFavorite = { node ->
                        toggleFavoriteNode(node)
                    },
                )

                is ConfigRoute.AppList -> AppListSelectorScreen(
                    configItem = route.item,
                    prefsManager = prefsManager,
                    onCancel = { closeOverlayRoute() },
                    onSave = { closeOverlayRoute() }
                )

                ConfigRoute.RearWallpaperManager -> RearWallpaperManagerScreen(
                    prefsManager = prefsManager,
                    onBack = { closeOverlayRoute() },
                )

                ConfigRoute.BusinessManager -> BusinessManagerScreen(
                    prefsManager = prefsManager,
                    onBack = { closeOverlayRoute() },
                    onOpenBusinessExtra = { business ->
                        routeStack = routeStack + ConfigRoute.BusinessExtraDetail(business)
                    },
                )

                ConfigRoute.SceneRouteManager -> SceneRouteManagerScreen(
                    prefsManager = prefsManager,
                    onBack = { closeOverlayRoute() },
                )

                ConfigRoute.CardManager -> CardManagerScreen(
                    prefsManager = prefsManager,
                    onBack = { closeOverlayRoute() },
                )

                ConfigRoute.BusinessExtraManager -> BusinessExtraConfigManagerScreen(
                    prefsManager = prefsManager,
                    onBack = { closeOverlayRoute() },
                )

                is ConfigRoute.BusinessExtraDetail -> BusinessExtraConfigScreen(
                    prefsManager = prefsManager,
                    business = route.business,
                    onBack = { routeStack = routeStack.dropLast(1) },
                )

                ConfigRoute.CustomBoundsCompatManager -> CustomBoundsCompatManagerScreen(
                    prefsManager = prefsManager,
                    onBack = { closeOverlayRoute() },
                )
            }
        }
    }
}

private fun findConfigCategoryByTitleRes(
    nodes: List<ConfigNode>,
    titleRes: Int,
): ConfigCategory? {
    nodes.forEach { node ->
        if (node is ConfigCategory) {
            if (node.titleRes == titleRes) return node
            findConfigCategoryByTitleRes(node.children, titleRes)?.let { return it }
        } else if (node is ConfigGroup) {
            findConfigCategoryByTitleRes(node.children, titleRes)?.let { return it }
        }
    }
    return null
}

@Composable
private fun ConfigNodeList(
    nodes: List<ConfigNode>,
    prefsManager: PrefsManager,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenAppList: (ConfigItem) -> Unit,
    onOpenManager: (ConfigItem) -> Unit,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
    showFavoriteCategoryEntry: Boolean = false,
    favoriteNodeCount: Int = 0,
    onOpenFavoriteCategory: (() -> Unit)? = null,
    emptyStateRes: Int? = null,
    favoriteNodeIds: Set<String> = emptySet(),
    resolveFavoriteNodeId: (ConfigNode) -> String? = { null },
    onToggleFavorite: (ConfigNode) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val isListScrolling by remember {
        derivedStateOf { listState.isScrollInProgress }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxHeight()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .then(modifier)
            .padding(horizontal = 12.dp),
        contentPadding = contentPadding,
        overscrollEffect = null
    ) {
        if (showFavoriteCategoryEntry) {
            item(key = "favorite_config_category_entry") {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                ) {
                    val favoriteIconTint = rememberFavoriteIconTint()
                    val summary = if (favoriteNodeCount <= 0) {
                        stringResource(R.string.config_favorites_empty)
                    } else {
                        stringResource(R.string.config_favorites_count, favoriteNodeCount)
                    }

                    ArrowPreference(
                        title = stringResource(R.string.config_favorites_title),
                        summary = summary,
                        onClick = onOpenFavoriteCategory,
                        startAction = {
                            Box(
                                modifier = Modifier.size(28.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Favorite,
                                    contentDescription = null,
                                    tint = favoriteIconTint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        if (nodes.isEmpty() && emptyStateRes != null) {
            item(key = "config_node_list_empty_state") {
                Card(
                    modifier = Modifier
                        .padding(top = if (showFavoriteCategoryEntry) 8.dp else 12.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(emptyStateRes),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }

        itemsIndexed(nodes, key = { index, node -> node.key ?: "node_$index" }) { index, node ->
            val topPadding = if (index == 0 && !showFavoriteCategoryEntry) 12.dp else 8.dp
            if (node is ConfigGroup) {
                Card(
                    modifier = Modifier
                        .padding(top = topPadding)
                        .fillMaxWidth()
                ) {
                    node.children.forEach { child ->
                        ConfigNodeRowWithFavoriteMenu(
                            node = child,
                            prefsManager = prefsManager,
                            isListScrolling = isListScrolling,
                            onOpenCategory = onOpenCategory,
                            onOpenAppList = onOpenAppList,
                            onOpenManager = onOpenManager,
                            onPreferenceChanged = onPreferenceChanged,
                            favoriteNodeIds = favoriteNodeIds,
                            resolveFavoriteNodeId = resolveFavoriteNodeId,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .padding(top = topPadding)
                        .fillMaxWidth()
                ) {
                    ConfigNodeRowWithFavoriteMenu(
                        node = node,
                        prefsManager = prefsManager,
                        isListScrolling = isListScrolling,
                        onOpenCategory = onOpenCategory,
                        onOpenAppList = onOpenAppList,
                        onOpenManager = onOpenManager,
                        onPreferenceChanged = onPreferenceChanged,
                        favoriteNodeIds = favoriteNodeIds,
                        resolveFavoriteNodeId = resolveFavoriteNodeId,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ConfigNodeRowWithFavoriteMenu(
    node: ConfigNode,
    prefsManager: PrefsManager,
    isListScrolling: Boolean,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenAppList: (ConfigItem) -> Unit,
    onOpenManager: (ConfigItem) -> Unit,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
    favoriteNodeIds: Set<String>,
    resolveFavoriteNodeId: (ConfigNode) -> String?,
    onToggleFavorite: (ConfigNode) -> Unit,
) {
    val favoriteNodeId = resolveFavoriteNodeId(node)
    val canFavorite = favoriteNodeId != null
    val isFavorite = favoriteNodeId != null && favoriteNodeIds.contains(favoriteNodeId)
    val favoriteIconTint = rememberFavoriteIconTint()
    var showFavoritePopup by remember(favoriteNodeId) { mutableStateOf(false) }

    Box(
        modifier = Modifier.configNodeLongPress(
            enabled = canFavorite,
            isScrolling = isListScrolling,
            onLongPress = { showFavoritePopup = true },
        )
    ) {
        ConfigNodeRow(
            node = node,
            prefsManager = prefsManager,
            onOpenCategory = onOpenCategory,
            onOpenAppList = onOpenAppList,
            onOpenManager = onOpenManager,
            onPreferenceChanged = onPreferenceChanged,
        )

        if (canFavorite) {
            OverlayListPopup(
                show = showFavoritePopup,
                popupModifier = Modifier,
                popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                alignment = PopupPositionProvider.Align.Start,
                enableWindowDim = true,
                onDismissRequest = { showFavoritePopup = false },
                maxHeight = null,
                minWidth = 180.dp,
                renderInRootScaffold = true,
            ) {
                ListPopupColumn {
                    SpinnerItemImpl(
                        entry = SpinnerEntry(
                            icon = { iconModifier ->
                                FavoritePopupIcon(
                                    isFavorite = isFavorite,
                                    tint = favoriteIconTint,
                                    modifier = iconModifier,
                                )
                            },
                            title = stringResource(
                                if (isFavorite) {
                                    R.string.config_favorite_remove
                                } else {
                                    R.string.config_favorite_add
                                }
                            )
                        ),
                        entryCount = 1,
                        isSelected = false,
                        index = 0,
                        spinnerColors = SpinnerDefaults.spinnerColors(),
                        onSelectedIndexChange = {
                            onToggleFavorite(node)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberFavoriteIconTint(): Color {
    return lerp(
        start = MiuixTheme.colorScheme.error,
        stop = MiuixTheme.colorScheme.errorContainer,
        fraction = 0.32f,
    )
}

@Composable
private fun FavoritePopupIcon(
    isFavorite: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (isFavorite) {
        Icon(
            imageVector = Icons.Rounded.FavoriteBorder,
            contentDescription = null,
            tint = tint,
            modifier = modifier.size(20.dp),
        )
    } else {
        Box(
            modifier = modifier.size(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun Modifier.configNodeLongPress(
    enabled: Boolean,
    isScrolling: Boolean,
    onLongPress: () -> Unit,
): Modifier {
    if (!enabled || isScrolling) return this

    return this.pointerInput(onLongPress, false) {
        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial,
            )
            if (isScrolling) return@awaitEachGesture
            val longPressTriggered = withTimeoutOrNull(
                timeMillis = viewConfiguration.longPressTimeoutMillis,
            ) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                        ?: return@withTimeoutOrNull false
                    if (!change.pressed || change.isConsumed || change.positionChanged() || isScrolling) {
                        return@withTimeoutOrNull false
                    }
                }
            } == null

            if (longPressTriggered) {
                onLongPress()

                var released = false
                while (!released) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { change ->
                        if (change.id == down.id) {
                            change.consume()
                            if (!change.pressed) {
                                released = true
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildFavoriteConfigNodeIndex(
    nodes: List<ConfigNode>,
    resolveResourceEntryName: (Int) -> String,
): FavoriteConfigNodeIndex {
    val entries = mutableListOf<FavoriteConfigNodeEntry>()
    val nodeIdLookup = IdentityHashMap<ConfigNode, String>()
    val idCounters = mutableMapOf<String, Int>()

    fun nextNodeId(counters: MutableMap<String, Int>, baseId: String): String {
        val current = (counters[baseId] ?: 0) + 1
        counters[baseId] = current
        return if (current == 1) {
            baseId
        } else {
            "$baseId#$current"
        }
    }

    fun registerNode(node: ConfigNode, nodeId: String) {
        entries += FavoriteConfigNodeEntry(id = nodeId, node = node)
        nodeIdLookup[node] = nodeId
    }

    fun walk(currentNodes: List<ConfigNode>, categoryPath: List<String>) {
        currentNodes.forEach { node ->
            when (node) {
                is ConfigCategory -> {
                    val pathToken = node.key?.let { "key:$it" }
                        ?: "title:${resolveResourceEntryName(node.titleRes)}"
                    val baseId = "category:path:${(categoryPath + pathToken).joinToString("/")}"
                    val nodeId = nextNodeId(idCounters, baseId)
                    registerNode(node, nodeId)
                    walk(node.children, categoryPath + pathToken)
                }

                is ConfigGroup -> {
                    walk(node.children, categoryPath)
                }

                is ConfigItem -> {
                    val nodeId = nextNodeId(idCounters, "item:key:${node.key}")
                    registerNode(node, nodeId)
                }
            }
        }
    }

    walk(nodes, emptyList())
    return FavoriteConfigNodeIndex(
        entries = entries,
        nodeIdLookup = nodeIdLookup,
    )
}
