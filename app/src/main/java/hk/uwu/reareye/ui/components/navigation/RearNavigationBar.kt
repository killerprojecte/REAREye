package hk.uwu.reareye.ui.components.navigation

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BorderStyle
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.Widgets
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
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
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.zIndex
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Stacks
import com.kyant.capsule.ContinuousCapsule
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.theme.AppNavigationBarStyle
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode
import hk.uwu.mixui.components.BottomSheetAction
import hk.uwu.mixui.components.BottomSheetActionButton
import hk.uwu.mixui.components.BottomSheetActionIcon
import hk.uwu.mixui.components.GlassBottomSheet
import hk.uwu.mixui.components.GlassBottomSheetSize
import androidx.compose.ui.platform.testTag
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.components.NavigationBar
import hk.uwu.mixui.components.NavigationBarMode
import hk.uwu.mixui.components.NavigationBackdrop
import hk.uwu.mixui.components.NavigationItem
import hk.uwu.mixui.components.NavigationAnchor
import hk.uwu.mixui.theme.MixTheme

sealed interface NavigationQuickTarget {
    @Immutable
    data class ConfigManager(val managerType: ConfigType.ManagerType) : NavigationQuickTarget
}

@Immutable
internal data class NavigationQuickAction(
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

private const val HOME_ROUTE = "home"
private const val MARY_ROUTE = "mary"
private const val CONFIG_ROUTE = "config"
private const val ABOUT_ROUTE = "about"
private const val EDIT_QUICK_ACTIONS_KEY = "__edit_quick_actions__"
private const val QUICK_ACTION_BUTTON_WIDTH_DP = 176

@Composable
fun AppNavigationBar(
    modifier: Modifier = Modifier,
    currentScreen: String,
    navigationBarMode: ModuleNavigationBarMode,
    backdrop: NavigationBackdrop,
    navigationBarStyle: AppNavigationBarStyle = AppNavigationBarStyle.default,
    shadowVisibilityProgress: Float = 1f,
    quickActionIds: List<String> = DefaultNavigationQuickActionIds,
    onQuickActionIdsChanged: (List<String>) -> Unit = {},
    onScreenSelected: (String) -> Unit,
    onQuickActionSelected: (NavigationQuickTarget) -> Unit = {},
) {
    val homeLabel = stringResource(R.string.home_navigation)
    val maryLabel = stringResource(R.string.mary_title)
    val configLabel = stringResource(R.string.configuration_navigation)
    val aboutLabel = stringResource(R.string.about_navigation)
    val editLabel = stringResource(R.string.navigation_quick_edit)
    val quickMenuLabel = stringResource(R.string.navigation_quick_available_title)
    val editActionLabel = stringResource(R.string.navigation_quick_edit_title)
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
        maryLabel,
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
                route = MARY_ROUTE,
                label = maryLabel,
                icon = Icons.Rounded.AutoAwesome,
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

    var menuAnchor by remember { mutableStateOf(NavigationAnchor(0f, 0f, 1f)) }
    var navigationHostPosition by remember { mutableStateOf(Offset.Zero) }

    fun openQuickActions(
        item: NavigationDestination,
        anchorCenterX: Float,
        anchorTop: Float,
        density: Float
    ) {
        if (item.route == CONFIG_ROUTE || item.quickActions.isNotEmpty()) {
            menuAnchor = NavigationAnchor(anchorCenterX, anchorTop, density)
            quickMenuController.begin()
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
        // Retire ownership before invoking application code; retained closing callbacks
        // and a release following Back/cancel must never execute the same action again.
        if (expandedQuickActionsRoute == null) return
        expandedQuickActionsRoute = null
        quickMenuController.reset()
        onQuickActionSelected(target)
    }

    BackHandler(enabled = expandedQuickActionsRoute != null) { closeQuickActions() }

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

    Box(modifier.onGloballyPositioned {
        navigationHostPosition = it.positionInRoot()
        quickMenuController.positionHost(it)
    }) {
        NavigationBar(
            items = items.map { item ->
                NavigationItem(
                    key = item.route,
                    label = item.label,
                    icon = item.icon,
                    secondaryActionLabel = if (item.route == CONFIG_ROUTE || item.quickActions.isNotEmpty()) quickMenuLabel else null,
                    editActionLabel = if (item.route == CONFIG_ROUTE) editActionLabel else null,
                )
            },
            style = navigationBarStyle.style,
            selectedKey = currentScreen,
            onSelect = { route -> closeQuickActions(); onScreenSelected(route) },
            backdrop = backdrop,
            mode = when (navigationBarMode) {
                ModuleNavigationBarMode.NORMAL -> NavigationBarMode.Normal
                ModuleNavigationBarMode.FLOATING -> NavigationBarMode.Floating
                ModuleNavigationBarMode.FLOATING_GLASS -> NavigationBarMode.FloatingGlass
                ModuleNavigationBarMode.SEMI_TRANSPARENT -> NavigationBarMode.Translucent
            },
            shadowVisibilityProgress = shadowVisibilityProgress,
            onEditAction = { route -> if (route == CONFIG_ROUTE) openEditor() },
            onSecondaryAction = { route, anchor ->
                items.firstOrNull { it.route == route }?.let { item ->
                    openQuickActions(item, anchor.centerX, anchor.top, anchor.density)
                }
            },
            onSecondaryDrag = { pointer ->
                items.firstOrNull { it.route == expandedQuickActionsRoute }?.let { item ->
                    quickMenuController.updateHover(pointer, item.quickActions) != null
                } ?: false
            },
            onSecondaryEnd = { cancelled ->
                val action = items.firstOrNull { it.route == expandedQuickActionsRoute }
                    ?.quickActions?.firstOrNull { it.id == quickMenuController.hoveredKey }
                when {
                    cancelled -> closeQuickActions()
                    quickMenuController.hoveredKey == EDIT_QUICK_ACTIONS_KEY -> openEditor()
                    action != null -> selectQuickAction(action.target)
                    else -> closeQuickActions()
                }
            },
        )
        items.forEach { item ->
            NavigationQuickActionsPopup(
                expanded = expandedQuickActionsRoute == item.route,
                actions = item.quickActions,
                canEditActions = item.route == CONFIG_ROUTE,
                backdrop = backdrop,
                editLabel = editLabel,
                quickMenuController = quickMenuController,
                anchor = menuAnchor,
                hostPosition = navigationHostPosition,
                onDismissRequest = ::closeQuickActions,
                onOpenEditor = ::openEditor,
                onActionSelected = ::selectQuickAction,
            )
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
private fun NavigationQuickActionsPopup(
    anchor: NavigationAnchor,
    hostPosition: Offset,
    expanded: Boolean,
    actions: List<NavigationQuickAction>,
    canEditActions: Boolean = false,
    backdrop: NavigationBackdrop,
    editLabel: String,
    quickMenuController: NavigationQuickMenuController,
    onDismissRequest: () -> Unit,
    onOpenEditor: () -> Unit,
    onActionSelected: (NavigationQuickTarget) -> Unit,
) {
    val density = LocalDensity.current
    val positionProvider = remember(density, anchor, hostPosition) {
        NavigationQuickActionsPopupPositionProvider(
            anchor = anchor,
            hostPosition = hostPosition,
            spacingPx = with(density) { 10.dp.roundToPx() },
            windowPaddingPx = with(density) { 12.dp.roundToPx() },
        )
    }
    val progress = remember { Animatable(0f) }
    var popupVisible by remember { mutableStateOf(false) }
    val hasMenuEntries = canEditActions || actions.isNotEmpty()

    LaunchedEffect(expanded, hasMenuEntries) {
        if (expanded && hasMenuEntries) {
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

    if ((!expanded && !popupVisible) || !hasMenuEntries) return

    hk.uwu.mixui.components.NavigationQuickActionPopup(
        positionProvider = positionProvider,
        onDismissRequest = onDismissRequest,
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
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (canEditActions) {
                    Box(
                        modifier = Modifier
                            .width(buttonWidth)
                            .height(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        NavigationQuickActionButton(
                            label = editLabel,
                            icon = Icons.Rounded.Edit,
                            backdrop = backdrop,
                            enabled = expanded,
                            testTag = "navigation-quick-edit-target",
                            width = buttonWidth,
                            hovered = quickMenuController.hoveredKey == EDIT_QUICK_ACTIONS_KEY,
                            isEditTarget = true,
                            delayProgress = progressValue,
                            onClick = onOpenEditor,
                            onPositioned = { quickMenuController.positionTarget(EDIT_QUICK_ACTIONS_KEY, it) },
                        )
                    }
                }
                if (actions.isNotEmpty()) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        actions.forEachIndexed { index, action ->
                            NavigationQuickActionButton(
                                label = action.label,
                                icon = action.icon,
                                backdrop = backdrop,
                                enabled = expanded,
                                testTag = "navigation-quick-action-${action.id}",
                                width = buttonWidth,
                                hovered = quickMenuController.hoveredKey == action.id,
                                delayProgress = ((progressValue - index * 0.05f).coerceIn(0f, 1f)),
                                onClick = { onActionSelected(action.target) },
                                onPositioned = { quickMenuController.positionTarget(action.id, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationQuickActionButton(
    label: String,
    icon: ImageVector,
    backdrop: NavigationBackdrop,
    enabled: Boolean,
    testTag: String,
    width: Dp,
    hovered: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    delayProgress: Float = 1f,
    isEditTarget: Boolean = false,
    onPositioned: (LayoutCoordinates) -> Unit = {},
) {
    val contentColor = MixTheme.colorScheme.onSurface
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
        }
        .testTag(testTag)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            enabled = enabled,
            onClick = onClick,
        )

    hk.uwu.mixui.components.NavigationQuickActionSurface(
        backdrop = backdrop,
        modifier = baseModifier,
        hovered = hovered,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(19.dp),
            )
            AutoShrinkText(
                text = label,
                color = contentColor,
                maxFontSize = 12.sp,
                minFontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }
    }
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
    private var hostCoordinates: LayoutCoordinates? = null
    // Coordinates stay live through popup/hover animations. Detached entries cannot hit;
    // retaining them across close/reopen also covers an interrupted exit without a relayout.
    private val targetCoordinates = mutableMapOf<String, LayoutCoordinates>()

    var hoveredKey by mutableStateOf<String?>(null)
        private set

    var showEditTarget by mutableStateOf(false)
        private set

    fun positionHost(coordinates: LayoutCoordinates) {
        hostCoordinates = coordinates
    }

    fun positionTarget(key: String, coordinates: LayoutCoordinates) {
        targetCoordinates[key] = coordinates
    }

    fun begin() = reset()

    fun reset() {
        hoveredKey = null
        showEditTarget = false
    }

    fun updateHover(pointer: Offset, actions: List<NavigationQuickAction>): String? {
        val host = hostCoordinates?.takeIf { it.isAttached }
        if (host == null) {
            hoveredKey = null
            return null
        }
        // The navigation pointer is in the activity's root; the targets live in a Popup
        // with its own root/window. Screen conversion accounts for the real padding,
        // popup placement/clamping and transforms instead of reconstructing anchor offsets.
        val pointerOnScreen = host.findRootCoordinates().localToScreen(pointer)
        fun target(key: String) = targetCoordinates[key]?.takeIf { it.isAttached }
        fun contains(key: String): Boolean {
            val coordinates = target(key) ?: return false
            val local = coordinates.screenToLocal(pointerOnScreen)
            return local.x >= 0f && local.x < coordinates.size.width &&
                local.y >= 0f && local.y < coordinates.size.height
        }
        if (contains(EDIT_QUICK_ACTIONS_KEY)) {
            showEditTarget = true
            hoveredKey = EDIT_QUICK_ACTIONS_KEY
            return hoveredKey
        }
        val actionIndex = actions.indexOfFirst { contains(it.id) }
        hoveredKey = actions.getOrNull(actionIndex)?.id
        return hoveredKey
    }
}

@Composable
internal fun NavigationQuickActionEditor(
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
            .toMutableStateList()
    }
    val selectedLookup = selectedState.toSet()
    val availableActions = allActions
        .filterNot { it.id in selectedLookup }
        .sortedBy { it.label }
    val maxHint = stringResource(R.string.navigation_quick_max_hint, MaxNavigationQuickActions)
    val scrollState = rememberScrollState()
    LaunchedEffect(show) { if (show) scrollState.scrollTo(0) }
    // Retained native header callbacks must not commit the same session twice.
    var submitted by remember(show) { mutableStateOf(false) }

    GlassBottomSheet(
        show = show,
        size = GlassBottomSheetSize.Content,
        // Reorder handles own vertical drags, including at the scroll boundary.
        enableNestedScroll = false,
        isContentScrolled = scrollState.value > 0,
        title = stringResource(R.string.navigation_quick_edit_title),
        onDismissRequest = onDismissRequest,
        startAction = {
            BottomSheetActionButton(
                onClick = { if (show) onDismissRequest() },
                modifier = Modifier.testTag("quick-editor-cancel"),
                enabled = show,
            ) {
                BottomSheetActionIcon(
                    BottomSheetAction.Close,
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.navigation_quick_cancel),
                )
            }
        },
        endAction = {
            BottomSheetActionButton(
                onClick = {
                    if (show && !submitted) {
                        submitted = true
                        onSave(selectedState.toList())
                    }
                },
                modifier = Modifier.testTag("quick-editor-save"),
                enabled = show && !submitted,
            ) {
                BottomSheetActionIcon(
                    BottomSheetAction.Save,
                    Icons.Outlined.Check,
                    contentDescription = stringResource(R.string.navigation_quick_save),
                )
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .testTag("quick-editor-body")
                .verticalScroll(scrollState)
                // The sheet host consumes safe drawing/IME insets once.
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = maxHint,
                color = MixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            NavigationQuickEditorSectionTitle(stringResource(R.string.navigation_quick_active_title))
            NavigationQuickEditorDropList(
                activeIds = selectedState,
                availableActions = availableActions,
                allActionLookup = allActionLookup,
            )
        }
    }
}

@Composable
private fun NavigationQuickEditorSectionTitle(text: String) {
    Text(
        text = text,
        color = MixTheme.colorScheme.onSurface,
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
                                color = MixTheme.colorScheme.dividerLine.copy(alpha = 0.9f),
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
                        color = MixTheme.colorScheme.onSurfaceVariantSummary,
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
                        color = MixTheme.colorScheme.onSurfaceVariantSummary,
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
    val contentColor = MixTheme.colorScheme.onSurfaceContainer
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
            .background(MixTheme.colorScheme.surfaceContainer, ContinuousCapsule)
            .border(
                0.75.dp,
                MixTheme.colorScheme.dividerLine.copy(alpha = 0.72f),
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
            id = action.id,
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
    id: String,
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
            .then(if (enabled) Modifier.testTag("quick-editor-drag-$id") else Modifier)
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
                            var cancelled = true
                            try {
                                var dragging = true
                                while (dragging) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { change ->
                                        if (change.changedToUp()) {
                                            cancelled = false
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
                            } finally {
                                if (cancelled) onDragCancel() else onDragEnd()
                            }
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
            tint = MixTheme.colorScheme.onSurfaceContainer.copy(alpha = 0.62f),
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
    private val anchor: NavigationAnchor,
    private val hostPosition: Offset,
    private val spacingPx: Int,
    private val windowPaddingPx: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val preferredX = (anchorBounds.left + anchor.centerX - hostPosition.x - popupContentSize.width / 2f).toInt()
        val minX = windowPaddingPx
        val maxX = (windowSize.width - popupContentSize.width - windowPaddingPx)
            .coerceAtLeast(minX)
        val x = preferredX.coerceIn(minX, maxX)

        val anchorTopInWindow = (anchorBounds.top + anchor.top - hostPosition.y).toInt()
        val preferredY = anchorTopInWindow - popupContentSize.height - spacingPx
        val maxY = (windowSize.height - popupContentSize.height - windowPaddingPx)
            .coerceAtLeast(windowPaddingPx)
        val fallbackY = anchorTopInWindow + spacingPx
        val y = if (preferredY >= windowPaddingPx) {
            preferredY
        } else {
            fallbackY.coerceIn(windowPaddingPx, maxY)
        }

        return IntOffset(x, y)
    }
}
