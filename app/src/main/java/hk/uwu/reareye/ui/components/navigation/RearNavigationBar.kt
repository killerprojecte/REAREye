package hk.uwu.reareye.ui.components.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BorderStyle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Stacks
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.capsule.ContinuousCapsule
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode
import hk.uwu.reareye.utils.BlurredBar
import hk.uwu.reareye.utils.blend.rememberBlurBackdrop
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

sealed interface NavigationQuickTarget {
    @Immutable
    data class ConfigManager(val managerType: ConfigType.ManagerType) : NavigationQuickTarget
}

@Immutable
private data class NavigationQuickAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val target: NavigationQuickTarget,
)

@Immutable
private data class NavigationDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val quickActions: List<NavigationQuickAction> = emptyList(),
)

@Immutable
private data class NavigationQuickActionStyle(
    val containerColor: Color,
    val contentColor: Color,
    val borderColor: Color,
    val hoveredContainerColor: Color,
    val selectedContainerColor: Color,
    val selectedBorderColor: Color,
    val shadowAlpha: Float,
    val shadowElevation: Dp,
    val usesGlass: Boolean,
)

private const val HOME_ROUTE = "home"
private const val STORE_ROUTE = "store"
private const val CONFIG_ROUTE = "config"
private const val ABOUT_ROUTE = "about"
private const val EDIT_QUICK_ACTIONS_KEY = "__edit_quick_actions__"
private const val QUICK_ACTION_BUTTON_WIDTH_DP = 176
private const val QUICK_ACTION_HALF_LONG_PRESS_MIN_MS = 180L

@Composable
fun RearNavigationBar(
    modifier: Modifier = Modifier,
    currentScreen: String,
    navigationBarMode: ModuleNavigationBarMode,
    backdrop: Backdrop,
    shadowVisibilityProgress: Float = 1f,
    quickActionIds: List<String> = DefaultNavigationQuickActionIds,
    onQuickActionIdsChanged: (List<String>) -> Unit = {},
    onScreenSelected: (String) -> Unit,
    onQuickActionSelected: (NavigationQuickTarget) -> Unit = {},
) {
    val homeLabel = stringResource(R.string.home_navigation)
    val storeLabel = stringResource(R.string.store_navigation)
    val configLabel = stringResource(R.string.configuration_navigation)
    val aboutLabel = stringResource(R.string.about_navigation)
    val editLabel = stringResource(R.string.navigation_quick_edit)
    val allQuickActions = rememberNavigationQuickActions()
    val quickActionLookup = remember(allQuickActions) { allQuickActions.associateBy { it.id } }
    val enabledQuickActions = remember(quickActionIds, allQuickActions) {
        quickActionIds
            .mapNotNull { quickActionLookup[it] }
            .distinctBy { it.id }
            .take(MaxNavigationQuickActions)
    }

    val items = remember(
        homeLabel,
        storeLabel,
        configLabel,
        aboutLabel,
        enabledQuickActions,
    ) {
        listOf(
            NavigationDestination(
                route = HOME_ROUTE,
                label = homeLabel,
                icon = Icons.Rounded.Cottage,
            ),
            NavigationDestination(
                route = STORE_ROUTE,
                label = storeLabel,
                icon = Icons.Rounded.CloudDownload,
            ),
            NavigationDestination(
                route = CONFIG_ROUTE,
                label = configLabel,
                icon = Icons.Rounded.Settings,
                quickActions = enabledQuickActions,
            ),
            NavigationDestination(
                route = ABOUT_ROUTE,
                label = aboutLabel,
                icon = Icons.Rounded.Info,
            ),
        )
    }
    val quickMenuController = remember { NavigationQuickMenuController() }
    var expandedQuickActionsRoute by remember { mutableStateOf<String?>(null) }
    var showQuickActionEditor by remember { mutableStateOf(false) }

    fun openQuickActions(
        item: NavigationDestination,
        anchorCenterX: Float,
        anchorTop: Float,
        density: Float
    ) {
        if (item.quickActions.isNotEmpty()) {
            quickMenuController.begin(anchorCenterX, anchorTop, density)
            expandedQuickActionsRoute = item.route
        }
    }

    fun closeQuickActions() {
        expandedQuickActionsRoute = null
        quickMenuController.reset()
    }

    fun openEditor() {
        closeQuickActions()
        showQuickActionEditor = true
    }

    fun selectQuickAction(target: NavigationQuickTarget) {
        quickMenuController.reset()
        onQuickActionSelected(target)
        expandedQuickActionsRoute = null
    }

    NavigationQuickActionEditor(
        show = showQuickActionEditor,
        selectedIds = quickActionIds,
        allActions = allQuickActions,
        onDismissRequest = { showQuickActionEditor = false },
        onSave = { nextIds ->
            onQuickActionIdsChanged(nextIds)
            showQuickActionEditor = false
        },
    )

    if (navigationBarMode == ModuleNavigationBarMode.NORMAL || navigationBarMode == ModuleNavigationBarMode.SEMI_TRANSPARENT) {
        val bar: @Composable () -> Unit = {
            NavigationBar(
                modifier = modifier,
                color = if (navigationBarMode == ModuleNavigationBarMode.NORMAL) {
                    MiuixTheme.colorScheme.surface
                } else {
                    Color.Transparent
                },
                mode = NavigationBarDisplayMode.IconAndText,
            ) {
                items.forEach { item ->
                    RearNavigationBarItem(
                        item = item,
                        selected = currentScreen == item.route,
                        expanded = expandedQuickActionsRoute == item.route,
                        navigationBarMode = navigationBarMode,
                        editLabel = editLabel,
                        quickMenuController = quickMenuController,
                        onClick = {
                            closeQuickActions()
                            onScreenSelected(item.route)
                        },
                        onOpenQuickActions = { anchorCenterX, anchorTop, density ->
                            openQuickActions(item, anchorCenterX, anchorTop, density)
                        },
                        onDismissQuickActions = ::closeQuickActions,
                        onOpenEditor = ::openEditor,
                        onQuickActionSelected = ::selectQuickAction,
                    )
                }
            }
        }
        if (navigationBarMode == ModuleNavigationBarMode.SEMI_TRANSPARENT) {
            val enable = rememberBlurBackdrop(true)
            BlurredBar(backdrop = enable, bar)
        } else {
            bar()
        }
        return
    }

    val enableGlass = navigationBarMode == ModuleNavigationBarMode.FLOATING_GLASS
    val selectedIndex = items.indexOfFirst { it.route == currentScreen }.coerceAtLeast(0)
    val selectedItem = items.getOrNull(selectedIndex)
    val selectedOverlayQuickGesture = selectedItem?.let { item ->
        Modifier.navigationQuickActionDragGesture(
            enabled = item.quickActions.isNotEmpty(),
            actions = item.quickActions,
            quickMenuController = quickMenuController,
            onOpenQuickActions = { anchorCenterX, anchorTop, density ->
                openQuickActions(item, anchorCenterX, anchorTop, density)
            },
            onDismissQuickActions = ::closeQuickActions,
            onOpenEditor = ::openEditor,
            onQuickActionSelected = ::selectQuickAction,
        )
    } ?: Modifier

    FloatingBottomBar(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(
                bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues()
                    .calculateBottomPadding()
            ),
        selectedIndex = selectedIndex,
        onSelected = {
            closeQuickActions()
            onScreenSelected(items[it].route)
        },
        backdrop = backdrop,
        tabsCount = items.size,
        isBlurEnabled = enableGlass,
        shadowVisibilityProgress = shadowVisibilityProgress,
        selectedOverlayModifier = Modifier,
        renderDuplicateContentLayer = true,
        quickGestureModifier = selectedOverlayQuickGesture,
    ) {
        items.forEachIndexed { index, item ->
            FloatingBottomBarItem(
                onClick = {
                    closeQuickActions()
                    onScreenSelected(items[index].route)
                },
                quickGestureModifier = Modifier.navigationQuickActionDragGesture(
                    enabled = item.quickActions.isNotEmpty(),
                    actions = item.quickActions,
                    quickMenuController = quickMenuController,
                    onOpenQuickActions = { anchorCenterX, anchorTop, density ->
                        openQuickActions(item, anchorCenterX, anchorTop, density)
                    },
                    onDismissQuickActions = ::closeQuickActions,
                    onOpenEditor = ::openEditor,
                    onQuickActionSelected = ::selectQuickAction,
                ),
                popup = {
                    NavigationQuickActionsPopup(
                        expanded = expandedQuickActionsRoute == item.route,
                        actions = item.quickActions,
                        navigationBarMode = navigationBarMode,
                        editLabel = editLabel,
                        quickMenuController = quickMenuController,
                        onDismissRequest = ::closeQuickActions,
                        onOpenEditor = ::openEditor,
                        onActionSelected = ::selectQuickAction,
                    )
                },
                modifier = Modifier.defaultMinSize(minWidth = 76.dp),
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    tint = MiuixTheme.colorScheme.onSurface,
                )
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

@Composable
private fun rememberNavigationQuickActions(): List<NavigationQuickAction> {
    val componentManagerLabel = stringResource(R.string.navigation_quick_component_manager)
    val cardManagerLabel = stringResource(R.string.navigation_quick_card_manager)
    val wallpaperManagerLabel = stringResource(R.string.rear_wallpaper_manager)
    val sceneRouteManagerLabel = stringResource(R.string.rear_widget_scene_route_manager)
    val boundsManagerLabel = stringResource(R.string.custom_bounds_compat_manager)
    val lyricsManagerLabel = stringResource(R.string.navigation_quick_lyrics_manager)

    return remember(
        componentManagerLabel,
        cardManagerLabel,
        wallpaperManagerLabel,
        sceneRouteManagerLabel,
        boundsManagerLabel,
        lyricsManagerLabel,
    ) {
        listOf(
            NavigationQuickAction(
                id = NavigationQuickActionComponentManagerId,
                label = componentManagerLabel,
                icon = Icons.Rounded.Widgets,
                target = NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.BUSINESS),
            ),
            NavigationQuickAction(
                id = NavigationQuickActionCardManagerId,
                label = cardManagerLabel,
                icon = MaterialSymbols.Rounded.Stacks,
                target = NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.CARD),
            ),
            NavigationQuickAction(
                id = NavigationQuickActionWallpaperManagerId,
                label = wallpaperManagerLabel,
                icon = Icons.Rounded.Wallpaper,
                target = NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.REAR_WALLPAPER),
            ),
            NavigationQuickAction(
                id = NavigationQuickActionSceneRouteManagerId,
                label = sceneRouteManagerLabel,
                icon = Icons.Rounded.Route,
                target = NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.SCENE_ROUTE),
            ),
            NavigationQuickAction(
                id = NavigationQuickActionBoundsManagerId,
                label = boundsManagerLabel,
                icon = Icons.Rounded.BorderStyle,
                target = NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.BOUNDS),
            ),
            NavigationQuickAction(
                id = NavigationQuickActionLyricsManagerId,
                label = lyricsManagerLabel,
                icon = Icons.Rounded.LibraryMusic,
                target = NavigationQuickTarget.ConfigManager(ConfigType.ManagerType.LYRICS),
            ),
        )
    }
}

@Composable
private fun RowScope.RearNavigationBarItem(
    item: NavigationDestination,
    selected: Boolean,
    expanded: Boolean,
    navigationBarMode: ModuleNavigationBarMode,
    editLabel: String,
    quickMenuController: NavigationQuickMenuController,
    onClick: () -> Unit,
    onOpenQuickActions: (anchorCenterX: Float, anchorTop: Float, density: Float) -> Unit,
    onDismissQuickActions: () -> Unit,
    onOpenEditor: () -> Unit,
    onQuickActionSelected: (NavigationQuickTarget) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val onSurfaceContainerColor = MiuixTheme.colorScheme.onSurfaceContainer
    val tint = when {
        isPressed -> if (selected) {
            onSurfaceContainerColor.copy(alpha = 0.5f)
        } else {
            onSurfaceContainerColor.copy(alpha = 0.6f)
        }

        selected -> onSurfaceContainerColor
        else -> onSurfaceContainerColor.copy(alpha = 0.4f)
    }
    val fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal

    Box(
        modifier = Modifier
            .height(64.dp)
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationQuickActionDragGesture(
                    enabled = item.quickActions.isNotEmpty(),
                    actions = item.quickActions,
                    quickMenuController = quickMenuController,
                    onOpenQuickActions = onOpenQuickActions,
                    onDismissQuickActions = onDismissQuickActions,
                    onOpenEditor = onOpenEditor,
                    onQuickActionSelected = onQuickActionSelected,
                )
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                    onLongClick = null,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .size(26.dp),
            )
            Text(
                modifier = Modifier.padding(bottom = 8.dp),
                text = item.label,
                color = tint,
                fontSize = 12.sp,
                fontWeight = fontWeight,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }

        NavigationQuickActionsPopup(
            expanded = expanded,
            actions = item.quickActions,
            navigationBarMode = navigationBarMode,
            editLabel = editLabel,
            quickMenuController = quickMenuController,
            onDismissRequest = onDismissQuickActions,
            onOpenEditor = onOpenEditor,
            onActionSelected = onQuickActionSelected,
        )
    }
}

@Composable
private fun NavigationQuickActionsPopup(
    expanded: Boolean,
    actions: List<NavigationQuickAction>,
    navigationBarMode: ModuleNavigationBarMode,
    editLabel: String,
    quickMenuController: NavigationQuickMenuController,
    onDismissRequest: () -> Unit,
    onOpenEditor: () -> Unit,
    onActionSelected: (NavigationQuickTarget) -> Unit,
) {
    val density = LocalDensity.current
    val menuBackdrop = rememberLayerBackdrop()
    val positionProvider = remember(density) {
        NavigationQuickActionsPopupPositionProvider(
            spacingPx = with(density) { 10.dp.roundToPx() },
            windowPaddingPx = with(density) { 12.dp.roundToPx() },
        )
    }
    val progress = remember { Animatable(0f) }
    var popupVisible by remember { mutableStateOf(false) }

    LaunchedEffect(expanded, actions) {
        if (expanded && actions.isNotEmpty()) {
            popupVisible = true
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            )
        } else if (popupVisible) {
            progress.animateTo(
                targetValue = 0f,
                animationSpec = tween(170, easing = FastOutSlowInEasing),
            )
            popupVisible = false
        }
    }

    if ((!expanded && !popupVisible) || actions.isEmpty()) return

    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        ),
    ) {
        val progressValue = progress.value
        Box(
            modifier = Modifier
                .padding(2.dp)
                .graphicsLayer {
                    alpha = progressValue
                    val scale = 0.92f + 0.08f * progressValue
                    scaleX = scale
                    scaleY = scale
                    translationY = with(density) { (1f - progressValue) * 8.dp.toPx() }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            val buttonWidth = QUICK_ACTION_BUTTON_WIDTH_DP.dp
            val showEditTarget =
                quickMenuController.showEditTarget || quickMenuController.hoveredKey == EDIT_QUICK_ACTIONS_KEY
            val editTargetProgress by animateFloatAsState(
                targetValue = if (showEditTarget) 1f else 0f,
                animationSpec = tween(
                    if (showEditTarget) 150 else 180,
                    easing = FastOutSlowInEasing
                ),
                label = "QuickActionEditTargetProgress",
            )

            NavigationQuickActionsBackdropLayer(
                actionsCount = actions.size,
                editTargetProgress = editTargetProgress,
                width = buttonWidth,
                modifier = Modifier.layerBackdrop(menuBackdrop),
            )

            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .width(buttonWidth)
                        .height(40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (editTargetProgress > 0.01f) {
                        NavigationQuickActionButton(
                            label = editLabel,
                            icon = Icons.Rounded.Edit,
                            navigationBarMode = navigationBarMode,
                            backdrop = menuBackdrop,
                            width = buttonWidth,
                            hovered = quickMenuController.hoveredKey == EDIT_QUICK_ACTIONS_KEY,
                            isEditTarget = true,
                            modifier = Modifier.graphicsLayer {
                                alpha = editTargetProgress
                                val appearScale = 0.94f + 0.06f * editTargetProgress
                                scaleX = appearScale
                                scaleY = appearScale
                            },
                            onClick = onOpenEditor,
                            onPositioned = { _ -> },
                        )
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    actions.forEachIndexed { index, action ->
                        NavigationQuickActionButton(
                            label = action.label,
                            icon = action.icon,
                            navigationBarMode = navigationBarMode,
                            backdrop = menuBackdrop,
                            width = buttonWidth,
                            hovered = quickMenuController.hoveredKey == action.id,
                            delayProgress = ((progressValue - index * 0.05f).coerceIn(0f, 1f)),
                            onClick = { onActionSelected(action.target) },
                            onPositioned = { _ -> },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationQuickActionsBackdropLayer(
    actionsCount: Int,
    editTargetProgress: Float,
    width: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .width(width)
                .height(40.dp)
                .graphicsLayer { alpha = editTargetProgress }
                .background(
                    MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f),
                    ContinuousCapsule
                ),
        )
        repeat(actionsCount) {
            Box(
                modifier = Modifier
                    .width(width)
                    .height(40.dp)
                    .background(
                        MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.66f),
                        ContinuousCapsule
                    ),
            )
        }
    }
}

@Composable
private fun NavigationQuickActionButton(
    label: String,
    icon: ImageVector,
    navigationBarMode: ModuleNavigationBarMode,
    backdrop: Backdrop,
    width: Dp,
    hovered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    delayProgress: Float = 1f,
    isEditTarget: Boolean = false,
    onPositioned: (LayoutCoordinates) -> Unit = {},
) {
    val density = LocalDensity.current
    val style = rememberNavigationQuickActionStyle(navigationBarMode)
    val shouldUseGlass = style.usesGlass
    val targetContainerColor = when {
        hovered && shouldUseGlass -> style.selectedContainerColor
        hovered -> style.hoveredContainerColor
        else -> style.containerColor
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "QuickActionContainerColor",
    )
    val borderColor by animateColorAsState(
        targetValue = if (hovered && shouldUseGlass) style.selectedBorderColor else style.borderColor,
        animationSpec = tween(160, easing = FastOutSlowInEasing),
        label = "QuickActionBorderColor",
    )
    val targetScale = when {
        hovered && isEditTarget -> 1.12f
        hovered -> 1.08f
        isEditTarget -> 1.04f
        else -> 1f
    }
    val animatedScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 420f),
        label = "QuickActionScale",
    )
    val progressScale = 0.96f + 0.04f * delayProgress

    val baseModifier = modifier
        .onGloballyPositioned(onPositioned)
        .width(width)
        .height(40.dp)
        .graphicsLayer {
            shape = ContinuousCapsule
            clip = true
            scaleX = animatedScale * progressScale
            scaleY = animatedScale * progressScale
            shadowElevation =
                with(density) { (style.shadowElevation + if (hovered) 8.dp else 0.dp).toPx() }
            ambientShadowColor = Color.Black.copy(alpha = style.shadowAlpha)
            spotShadowColor = Color.Black.copy(alpha = style.shadowAlpha)
        }
        .then(
            if (shouldUseGlass) {
                Modifier.drawBackdrop(
                    backdrop = backdrop,
                    shape = { ContinuousCapsule },
                    effects = {
                        vibrancy()
                        blur(if (hovered) 10f.dp.toPx() else 8f.dp.toPx())
                        lens(
                            if (hovered) 34f.dp.toPx() else 24f.dp.toPx(),
                            if (hovered) 38f.dp.toPx() else 26f.dp.toPx(),
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = if (hovered) 1f else 0.86f)
                    },
                    shadow = {
                        Shadow.Default.copy(
                            color = Color.Black.copy(alpha = 0.18f),
                            alpha = if (hovered) 0.58f else 0.32f,
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = if (hovered) 12.dp else 7.dp,
                            alpha = if (hovered) 0.42f else 0.22f,
                        )
                    },
                    onDrawSurface = {
                        drawRect(containerColor)
                        drawRect(Color.White.copy(alpha = if (hovered) 0.16f else 0.055f))
                        drawRect(Color.Black.copy(alpha = if (hovered) 0.025f else 0.012f))
                    },
                )
            } else {
                Modifier.background(containerColor, ContinuousCapsule)
            }
        )
        .border(0.75.dp, borderColor, ContinuousCapsule)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )

    Box(
        modifier = baseModifier,
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = style.contentColor,
                modifier = Modifier.size(19.dp),
            )
            AutoShrinkText(
                text = label,
                color = style.contentColor,
                maxFontSize = 12.sp,
                minFontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun rememberNavigationQuickActionStyle(
    navigationBarMode: ModuleNavigationBarMode,
): NavigationQuickActionStyle {
    val isLight = MiuixTheme.colorScheme.surface.luminance() >= 0.5f
    val outline = MiuixTheme.colorScheme.dividerLine.copy(alpha = if (isLight) 0.7f else 0.86f)
    val primaryTint = MiuixTheme.colorScheme.primary.copy(alpha = if (isLight) 0.14f else 0.2f)
    return when (navigationBarMode) {
        ModuleNavigationBarMode.NORMAL -> NavigationQuickActionStyle(
            containerColor = MiuixTheme.colorScheme.surface,
            contentColor = MiuixTheme.colorScheme.onSurface,
            borderColor = outline,
            hoveredContainerColor = blendColors(MiuixTheme.colorScheme.surface, primaryTint),
            selectedContainerColor = blendColors(MiuixTheme.colorScheme.surface, primaryTint),
            selectedBorderColor = outline,
            shadowAlpha = if (isLight) 0.13f else 0.28f,
            shadowElevation = 10.dp,
            usesGlass = false,
        )

        ModuleNavigationBarMode.SEMI_TRANSPARENT -> NavigationQuickActionStyle(
            containerColor = MiuixTheme.colorScheme.surface.copy(alpha = 0.9f),
            contentColor = MiuixTheme.colorScheme.onSurface,
            borderColor = outline.copy(alpha = 0.9f),
            hoveredContainerColor = blendColors(
                MiuixTheme.colorScheme.surface.copy(alpha = 0.94f),
                primaryTint
            ),
            selectedContainerColor = blendColors(
                MiuixTheme.colorScheme.surface.copy(alpha = 0.94f),
                primaryTint
            ),
            selectedBorderColor = outline.copy(alpha = 0.9f),
            shadowAlpha = if (isLight) 0.16f else 0.3f,
            shadowElevation = 12.dp,
            usesGlass = false,
        )

        ModuleNavigationBarMode.FLOATING -> NavigationQuickActionStyle(
            containerColor = MiuixTheme.colorScheme.surfaceContainer,
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
            borderColor = outline,
            hoveredContainerColor = blendColors(
                MiuixTheme.colorScheme.surfaceContainer,
                primaryTint
            ),
            selectedContainerColor = blendColors(
                MiuixTheme.colorScheme.surfaceContainer,
                primaryTint
            ),
            selectedBorderColor = outline,
            shadowAlpha = if (isLight) 0.18f else 0.34f,
            shadowElevation = 14.dp,
            usesGlass = false,
        )

        ModuleNavigationBarMode.FLOATING_GLASS -> NavigationQuickActionStyle(
            containerColor = if (isLight) {
                Color.White.copy(alpha = 0.115f)
            } else {
                Color.White.copy(alpha = 0.065f)
            },
            contentColor = MiuixTheme.colorScheme.onSurface,
            borderColor = Color.White.copy(alpha = if (isLight) 0.34f else 0.15f),
            hoveredContainerColor = if (isLight) {
                Color.White.copy(alpha = 0.18f)
            } else {
                Color.White.copy(alpha = 0.105f)
            },
            selectedContainerColor = if (isLight) {
                Color.White.copy(alpha = 0.245f)
            } else {
                Color.White.copy(alpha = 0.145f)
            },
            selectedBorderColor = Color.White.copy(alpha = if (isLight) 0.58f else 0.28f),
            shadowAlpha = if (isLight) 0.14f else 0.3f,
            shadowElevation = 16.dp,
            usesGlass = true,
        )
    }
}

private fun blendColors(base: Color, overlay: Color): Color {
    val alpha = overlay.alpha.coerceIn(0f, 1f)
    return Color(
        red = base.red * (1f - alpha) + overlay.red * alpha,
        green = base.green * (1f - alpha) + overlay.green * alpha,
        blue = base.blue * (1f - alpha) + overlay.blue * alpha,
        alpha = base.alpha,
    )
}

@Composable
private fun AutoShrinkText(
    text: String,
    color: Color,
    maxFontSize: TextUnit,
    minFontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
) {
    var fontSize by remember(text, maxFontSize) { mutableStateOf(maxFontSize) }
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layoutResult ->
            if (layoutResult.hasVisualOverflow && fontSize.value > minFontSize.value) {
                fontSize = (fontSize.value - 0.5f).coerceAtLeast(minFontSize.value).sp
            }
        },
    )
}

private class NavigationQuickMenuController {
    private var densityScaleFallback = 1f

    var hoveredKey by mutableStateOf<String?>(null)
        private set

    var showEditTarget by mutableStateOf(false)
        private set

    private var anchorCenterX = 0f
    private var anchorTop = 0f

    fun begin(anchorCenterX: Float, anchorTop: Float, density: Float) {
        densityScaleFallback = density
        this.anchorCenterX = anchorCenterX
        this.anchorTop = anchorTop
        hoveredKey = null
        showEditTarget = false
    }

    fun reset() {
        hoveredKey = null
        showEditTarget = false
        anchorCenterX = 0f
        anchorTop = 0f
    }

    fun updateHover(pointer: Offset, actions: List<NavigationQuickAction>): String? {
        val buttonWidth = QUICK_ACTION_BUTTON_WIDTH_DP * densityScaleFallback
        val buttonHeight = 40f * densityScaleFallback
        val gap = 8f * densityScaleFallback
        val menuBottom = anchorTop - 10f * densityScaleFallback
        val menuTop =
            menuBottom - actions.size * buttonHeight - (actions.size - 1).coerceAtLeast(0) * gap
        val xInMenu =
            pointer.x in (anchorCenterX - buttonWidth / 2f)..(anchorCenterX + buttonWidth / 2f)

        if (pointer.y <= menuTop + buttonHeight / 2f && xInMenu) {
            showEditTarget = true
        }

        val actionIndex = ((pointer.y - menuTop) / (buttonHeight + gap)).toInt()
        val action = actions.getOrNull(actionIndex)
        val actionTop = menuTop + actionIndex * (buttonHeight + gap)
        val actionHovered =
            action != null && xInMenu && pointer.y in actionTop..(actionTop + buttonHeight)

        if (showEditTarget) {
            val editTop = menuTop - gap - buttonHeight
            val editBottom = editTop + buttonHeight
            if (xInMenu && pointer.y in editTop..editBottom) {
                hoveredKey = EDIT_QUICK_ACTIONS_KEY
                return hoveredKey
            }
            if (actionHovered && actionIndex > 0) {
                showEditTarget = false
            }
        }

        hoveredKey = if (actionHovered) {
            action.id
        } else {
            null
        }
        return hoveredKey
    }
}

private fun Modifier.navigationQuickActionDragGesture(
    enabled: Boolean,
    actions: List<NavigationQuickAction>,
    quickMenuController: NavigationQuickMenuController,
    onOpenQuickActions: (anchorCenterX: Float, anchorTop: Float, density: Float) -> Unit,
    onDismissQuickActions: () -> Unit,
    onOpenEditor: () -> Unit,
    onQuickActionSelected: (NavigationQuickTarget) -> Unit,
): Modifier {
    if (!enabled) return this

    var rootOffset = Offset.Zero
    return this
        .onGloballyPositioned { coordinates ->
            rootOffset = coordinates.positionInRoot()
        }
        .pointerInput(actions) {
            awaitEachGesture {
                val down =
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val longPressTimeout = maxOf(
                    QUICK_ACTION_HALF_LONG_PRESS_MIN_MS,
                    viewConfiguration.longPressTimeoutMillis / 2,
                )
                val longPressTriggered = withTimeoutOrNull(longPressTimeout) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                            ?: return@withTimeoutOrNull false
                        if (!change.pressed) {
                            return@withTimeoutOrNull false
                        }
                    }
                } == null

                if (!longPressTriggered) return@awaitEachGesture

                val anchorCenterX = rootOffset.x + size.width / 2f
                val anchorTop = rootOffset.y
                onOpenQuickActions(anchorCenterX, anchorTop, density)
                var latestRootPosition = rootOffset + down.position
                quickMenuController.updateHover(latestRootPosition, actions)

                var released = false
                while (!released) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { change ->
                        if (change.id == down.id) {
                            latestRootPosition = rootOffset + change.position
                            quickMenuController.updateHover(latestRootPosition, actions)
                            if (change.changedToUpIgnoreConsumed()) {
                                released = true
                            }
                            change.consume()
                        }
                    }
                }

                quickMenuController.updateHover(latestRootPosition, actions)
                val hoveredKey = quickMenuController.hoveredKey
                quickMenuController.reset()
                when (hoveredKey) {
                    EDIT_QUICK_ACTIONS_KEY -> onOpenEditor()
                    null -> onDismissQuickActions()
                    else -> {
                        val action = actions.firstOrNull { it.id == hoveredKey }
                        if (action != null) {
                            onQuickActionSelected(action.target)
                        } else {
                            onDismissQuickActions()
                        }
                    }
                }
            }
        }
}

@Composable
private fun NavigationQuickActionEditor(
    show: Boolean,
    selectedIds: List<String>,
    allActions: List<NavigationQuickAction>,
    onDismissRequest: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    val allActionLookup = remember(allActions) { allActions.associateBy { it.id } }
    val selectedState = remember(show, selectedIds, allActions) {
        selectedIds
            .mapNotNull { allActionLookup[it] }
            .distinctBy { it.id }
            .take(MaxNavigationQuickActions)
            .map { it.id }
            .ifEmpty { DefaultNavigationQuickActionIds }
            .toMutableStateList()
    }
    val selectedLookup = selectedState.toSet()
    val availableActions = allActions
        .filterNot { it.id in selectedLookup }
        .sortedBy { it.label }
    val maxHint = stringResource(R.string.navigation_quick_max_hint, MaxNavigationQuickActions)
    val bottomPadding = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()

    OverlayBottomSheet(
        show = show,
        title = stringResource(R.string.navigation_quick_edit_title),
        onDismissRequest = onDismissRequest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottomPadding + 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = maxHint,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            NavigationQuickEditorSectionTitle(stringResource(R.string.navigation_quick_active_title))
            NavigationQuickEditorDropList(
                activeIds = selectedState,
                availableActions = availableActions,
                allActionLookup = allActionLookup,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.navigation_quick_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismissRequest,
                )
                Button(
                    onClick = { onSave(selectedState.toList()) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(text = stringResource(R.string.navigation_quick_save))
                }
            }
        }
    }
}

@Composable
private fun NavigationQuickEditorSectionTitle(text: String) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun NavigationQuickEditorDropList(
    activeIds: MutableList<String>,
    availableActions: List<NavigationQuickAction>,
    allActionLookup: Map<String, NavigationQuickAction>,
) {
    val activeBounds = remember { mutableStateMapOf<String, NavigationEditorItemBounds>() }
    val availableBounds = remember { mutableStateMapOf<String, NavigationEditorItemBounds>() }
    var activeAreaBounds by remember { mutableStateOf<Rect?>(null) }
    var availableAreaBounds by remember { mutableStateOf<Rect?>(null) }
    var editorTopInRoot by remember { mutableFloatStateOf(0f) }
    var dragState by remember { mutableStateOf<NavigationEditorDragState?>(null) }

    fun previewActiveIds(): List<String> {
        val state = dragState ?: return activeIds.toList()
        if (state.targetArea != NavigationEditorDropArea.Active) {
            return activeIds.toList()
        }
        if (state.sourceArea == NavigationEditorDropArea.Available && activeIds.size >= MaxNavigationQuickActions) {
            return activeIds.toList()
        }
        val preview = activeIds.filterNot { it == state.id }.toMutableList()
        preview.add((state.insertIndex ?: preview.size).coerceIn(0, preview.size), state.id)
        return preview
    }

    fun resetDrag() {
        dragState = null
    }

    fun updateDrag(pointerRootY: Float) {
        val state = dragState ?: return
        val targetArea = when {
            activeAreaBounds?.let { pointerRootY in it.top..it.bottom } == true -> NavigationEditorDropArea.Active
            availableAreaBounds?.let { pointerRootY in it.top..it.bottom } == true -> NavigationEditorDropArea.Available
            else -> state.targetArea
        }
        val insertIndex = if (targetArea == NavigationEditorDropArea.Active) {
            findNavigationEditorInsertIndex(
                ids = activeIds,
                bounds = activeBounds,
                draggedCenter = pointerRootY,
                excludedId = if (state.sourceArea == NavigationEditorDropArea.Active) state.id else null,
            )
        } else {
            null
        }
        dragState = state.copy(
            currentPointerRootY = pointerRootY,
            targetArea = targetArea,
            insertIndex = insertIndex,
        )
    }

    fun finishDrag() {
        val state = dragState ?: return
        when (state.targetArea) {
            NavigationEditorDropArea.Active -> {
                if (state.sourceArea == NavigationEditorDropArea.Active) {
                    val fromIndex = activeIds.indexOf(state.id)
                    if (fromIndex >= 0) {
                        val item = activeIds.removeAt(fromIndex)
                        activeIds.add(
                            (state.insertIndex ?: activeIds.size).coerceIn(
                                0,
                                activeIds.size
                            ), item
                        )
                    }
                } else if (activeIds.size < MaxNavigationQuickActions && state.id !in activeIds) {
                    activeIds.add(
                        (state.insertIndex ?: activeIds.size).coerceIn(0, activeIds.size),
                        state.id
                    )
                }
            }

            NavigationEditorDropArea.Available -> {
                if (state.sourceArea == NavigationEditorDropArea.Active) {
                    activeIds.remove(state.id)
                }
            }
        }
        resetDrag()
    }

    val isFull = activeIds.size >= MaxNavigationQuickActions
    val dragId = dragState?.id
    val previewActiveIds = previewActiveIds()
    val draggingToFullActive = dragState?.let {
        it.sourceArea == NavigationEditorDropArea.Available &&
                it.targetArea == NavigationEditorDropArea.Active &&
                isFull
    } == true

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                editorTopInRoot = coordinates.positionInRoot().y
            },
    ) {
        Column(
            modifier = Modifier.animateContentSize(
                animationSpec = tween(
                    180,
                    easing = FastOutSlowInEasing
                )
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInRoot()
                        activeAreaBounds = Rect(
                            position.x,
                            position.y,
                            position.x + coordinates.size.width,
                            position.y + coordinates.size.height,
                        )
                    }
                    .then(
                        if (draggingToFullActive) {
                            Modifier.border(
                                width = 1.dp,
                                color = MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.9f),
                                shape = RoundedCornerShape(18.dp),
                            )
                        } else {
                            Modifier
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (previewActiveIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.navigation_quick_active_empty),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                previewActiveIds.forEach { id ->
                    val action = allActionLookup[id] ?: return@forEach
                    NavigationQuickEditorActionRow(
                        action = action,
                        isDragged = false,
                        isPlaceholder = dragId == id,
                        modifier = Modifier
                            .onGloballyPositioned { coordinates ->
                                val position = coordinates.positionInRoot()
                                activeBounds[id] = NavigationEditorItemBounds(
                                    top = position.y,
                                    height = coordinates.size.height.toFloat(),
                                )
                            },
                        onDragStart = { pointerRootY ->
                            val bounds = activeBounds[id]
                            dragState = NavigationEditorDragState(
                                id = id,
                                sourceArea = NavigationEditorDropArea.Active,
                                targetArea = NavigationEditorDropArea.Active,
                                pointerOffsetInItemY = pointerRootY - (bounds?.top ?: pointerRootY),
                                currentPointerRootY = pointerRootY,
                                itemHeight = bounds?.height ?: 46f,
                                insertIndex = previewActiveIds.indexOf(id).coerceAtLeast(0),
                            )
                        },
                        onDrag = { updateDrag(it) },
                        onDragEnd = ::finishDrag,
                        onDragCancel = ::resetDrag,
                    )
                }
            }

            NavigationQuickEditorSectionTitle(stringResource(R.string.navigation_quick_available_title))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInRoot()
                        availableAreaBounds = Rect(
                            position.x,
                            position.y,
                            position.x + coordinates.size.width,
                            position.y + coordinates.size.height,
                        )
                    },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (availableActions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.navigation_quick_available_empty),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                availableActions.forEach { action ->
                    NavigationQuickEditorActionRow(
                        action = action,
                        isDragged = false,
                        isPlaceholder = dragId == action.id,
                        modifier = Modifier.onGloballyPositioned { coordinates ->
                            val position = coordinates.positionInRoot()
                            availableBounds[action.id] = NavigationEditorItemBounds(
                                top = position.y,
                                height = coordinates.size.height.toFloat(),
                            )
                        },
                        onDragStart = { pointerRootY ->
                            val bounds = availableBounds[action.id]
                            dragState = NavigationEditorDragState(
                                id = action.id,
                                sourceArea = NavigationEditorDropArea.Available,
                                targetArea = NavigationEditorDropArea.Available,
                                pointerOffsetInItemY = pointerRootY - (bounds?.top ?: pointerRootY),
                                currentPointerRootY = pointerRootY,
                                itemHeight = bounds?.height ?: 46f,
                            )
                        },
                        onDrag = { updateDrag(it) },
                        onDragEnd = ::finishDrag,
                        onDragCancel = ::resetDrag,
                    )
                }
            }
        }

        dragState?.let { state ->
            val action =
                allActionLookup[state.id] ?: availableActions.firstOrNull { it.id == state.id }
            if (action != null) {
                NavigationQuickEditorActionRow(
                    action = action,
                    isDragged = true,
                    isPlaceholder = false,
                    dragEnabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            translationY =
                                state.currentPointerRootY - state.pointerOffsetInItemY - editorTopInRoot
                        }
                        .zIndex(8f),
                    onDragStart = {},
                    onDrag = {},
                    onDragEnd = {},
                    onDragCancel = {},
                )
            }
        }
    }
}

@Composable
private fun NavigationQuickEditorActionRow(
    action: NavigationQuickAction,
    isDragged: Boolean,
    modifier: Modifier = Modifier,
    isPlaceholder: Boolean = false,
    dragEnabled: Boolean = true,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (isDragged) 1.03f else 1f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 360f),
        label = "EditorRowScale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (isPlaceholder) 0.2f else 1f,
        animationSpec = tween(180, easing = FastOutSlowInEasing),
        label = "EditorRowAlpha",
    )
    val contentColor = MiuixTheme.colorScheme.onSurfaceContainer
    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(180, easing = FastOutSlowInEasing))
            .height(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
                shape = ContinuousCapsule
                clip = false
            }
            .background(MiuixTheme.colorScheme.surfaceContainer, ContinuousCapsule)
            .border(
                0.75.dp,
                MiuixTheme.colorScheme.dividerLine.copy(alpha = 0.72f),
                ContinuousCapsule
            )
            .padding(start = 14.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        AutoShrinkText(
            text = action.label,
            color = contentColor,
            maxFontSize = 13.sp,
            minFontSize = 9.sp,
            modifier = Modifier.weight(1f),
        )
        NavigationQuickDragHandle(
            enabled = dragEnabled,
            onDragStart = onDragStart,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onDragCancel = onDragCancel,
        )
    }
}

@Composable
private fun NavigationQuickDragHandle(
    enabled: Boolean,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var handleTopInRoot by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 40.dp)
            .onGloballyPositioned { coordinates ->
                handleTopInRoot = coordinates.positionInRoot().y
            }
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downRootY = handleTopInRoot + down.position.y
                            onDragStart(downRootY)
                            onDrag(downRootY)
                            var cancelled = false
                            var dragging = true
                            while (dragging) {
                                val event = awaitPointerEvent()
                                event.changes.forEach { change ->
                                    if (change.changedToUpIgnoreConsumed()) {
                                        dragging = false
                                    } else if (change.pressed) {
                                        onDrag(handleTopInRoot + change.position.y)
                                        change.consume()
                                    } else {
                                        cancelled = true
                                        dragging = false
                                    }
                                }
                            }
                            if (cancelled) onDragCancel() else onDragEnd()
                        }
                    }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Menu,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.62f),
            modifier = Modifier.size(22.dp),
        )
    }
}

private enum class NavigationEditorDropArea {
    Active,
    Available,
}

private data class NavigationEditorDragState(
    val id: String,
    val sourceArea: NavigationEditorDropArea,
    val targetArea: NavigationEditorDropArea,
    val pointerOffsetInItemY: Float,
    val currentPointerRootY: Float,
    val itemHeight: Float,
    val insertIndex: Int? = null,
)

private data class NavigationEditorItemBounds(
    val top: Float,
    val height: Float,
)

private fun findNavigationEditorInsertIndex(
    ids: List<String>,
    bounds: Map<String, NavigationEditorItemBounds>,
    draggedCenter: Float,
    excludedId: String? = null,
): Int {
    val filteredIds = ids.filterNot { it == excludedId }
    val boundaries = filteredIds.mapNotNull { id ->
        bounds[id]?.let { it.top + it.height / 2f }
    }
    return boundaries.count { draggedCenter > it }.coerceIn(0, filteredIds.size)
}

private class NavigationQuickActionsPopupPositionProvider(
    private val spacingPx: Int,
    private val windowPaddingPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val minX = windowPaddingPx
        val maxX = (windowSize.width - popupContentSize.width - windowPaddingPx)
            .coerceAtLeast(minX)
        val x = preferredX.coerceIn(minX, maxX)

        val preferredY = anchorBounds.top - popupContentSize.height - spacingPx
        val maxY = (windowSize.height - popupContentSize.height - windowPaddingPx)
            .coerceAtLeast(windowPaddingPx)
        val fallbackY = anchorBounds.bottom + spacingPx
        val y = if (preferredY >= windowPaddingPx) {
            preferredY
        } else {
            fallbackY.coerceIn(windowPaddingPx, maxY)
        }

        return IntOffset(x, y)
    }
}
