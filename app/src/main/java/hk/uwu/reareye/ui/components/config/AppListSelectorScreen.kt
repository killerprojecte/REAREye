package hk.uwu.reareye.ui.components.config

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.style.ExperimentalFoundationStyleApi
import androidx.compose.foundation.style.MutableStyleState
import androidx.compose.foundation.style.Style
import androidx.compose.foundation.style.animate
import androidx.compose.foundation.style.border
import androidx.compose.foundation.style.scale
import androidx.compose.foundation.style.selected
import androidx.compose.foundation.style.styleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.RearSearchBar
import hk.uwu.reareye.ui.components.rememberApplicationIconBitmap
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.FabPosition
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.MoreCircle
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private enum class AppSortMode {
    LABEL,
    PACKAGE,
}

data class AppItem(
    val applicationInfo: ApplicationInfo,
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val labelKey: String,
    val packageKey: String,
)

private data class AppCatalog(
    val byLabel: List<AppItem>,
    val byPackage: List<AppItem>,
)

private fun buildAppCatalog(packageManager: PackageManager): AppCatalog {
    val allApps = packageManager
        .getInstalledApplications(PackageManager.GET_META_DATA)
        .map { app ->
            val label = app.loadLabel(packageManager).toString()
            AppItem(
                applicationInfo = app,
                label = label,
                packageName = app.packageName,
                isSystem =
                    (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                labelKey = label.lowercase(),
                packageKey = app.packageName.lowercase(),
            )
        }

    return AppCatalog(
        byLabel = allApps.sortedBy(AppItem::labelKey),
        byPackage = allApps.sortedBy(AppItem::packageKey),
    )
}

private fun AppCatalog.buildVisibleApps(
    sortMode: AppSortMode,
    reverseOrder: Boolean,
    showSystemApps: Boolean,
    query: String,
    selectedOrder: List<String>,
): List<AppItem> {
    val normalizedQuery = query.trim().lowercase()
    val source = when (sortMode) {
        AppSortMode.LABEL -> byLabel
        AppSortMode.PACKAGE -> byPackage
    }
    val visibleApps = source.filter { app ->
        val matchesVisibility = showSystemApps || !app.isSystem
        val matchesQuery = normalizedQuery.isEmpty() ||
                app.labelKey.contains(normalizedQuery) ||
                app.packageKey.contains(normalizedQuery)
        matchesVisibility && matchesQuery
    }
    val orderedApps = if (reverseOrder) visibleApps.asReversed() else visibleApps

    if (selectedOrder.isEmpty()) {
        return orderedApps
    }

    val selectedLookup = selectedOrder.toHashSet()
    val selectedItems = HashMap<String, AppItem>(selectedOrder.size)
    val unselectedItems = ArrayList<AppItem>(orderedApps.size)

    orderedApps.forEach { app ->
        if (app.packageName in selectedLookup) {
            selectedItems[app.packageName] = app
        } else {
            unselectedItems += app
        }
    }

    return buildList(orderedApps.size) {
        selectedOrder.forEach { packageName ->
            selectedItems[packageName]?.let(::add)
        }
        addAll(unselectedItems)
    }
}

private fun MutableList<String>.toggleSelection(packageName: String) {
    if (!remove(packageName)) {
        add(packageName)
    }
}

@Composable
private fun AppIcon(
    appInfo: ApplicationInfo,
    pm: PackageManager,
    modifier: Modifier = Modifier,
) {
    val imageBitmap = rememberApplicationIconBitmap(
        packageManager = pm,
        applicationInfo = appInfo,
    )

    if (imageBitmap != null) {
        Image(bitmap = imageBitmap, contentDescription = null, modifier = modifier.size(44.dp))
    } else {
        Spacer(modifier = modifier.size(44.dp))
    }
}

@Composable
private fun LoadingAppsCard() {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
        insideMargin = PaddingValues(vertical = 26.dp),
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            InfiniteProgressIndicator()
        }
    }
}

@Composable
private fun EmptyAppsCard() {
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth(),
        insideMargin = PaddingValues(vertical = 22.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_list_empty_result),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationStyleApi::class)
@Composable
private fun AppSelectorRow(
    appItem: AppItem,
    packageManager: PackageManager,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val density = LocalDensity.current
    val primaryColor = MiuixTheme.colorScheme.primary
    val selectionStyleState = remember(appItem.packageName) { MutableStyleState(null) }
    selectionStyleState.isSelected = selected
    val overlayShape = remember { RoundedCornerShape(20.dp) }
    val selectionOverlayStyle = remember(primaryColor, overlayShape) {
        Style {
            alpha(0f)
            scale(0.965f)
            background(primaryColor)
            shape(overlayShape)

            selected {
                animate(
                    spring(
                        dampingRatio = 0.9f,
                        stiffness = 520f,
                    )
                ) {
                    alpha(0.08f)
                }
                animate(
                    spring(
                        dampingRatio = 0.92f,
                        stiffness = 540f,
                    )
                ) {
                    scale(1f)
                }
            }
        }
    }
    val rowContentOffsetPx = remember(density) { with(density) { 1.5.dp.toPx() } }
    val rowContentStyle = remember(rowContentOffsetPx) {
        Style {
            translationX(0f)
            scale(1f)

            selected {
                animate(
                    spring(
                        dampingRatio = 0.92f,
                        stiffness = 560f,
                    )
                ) {
                    translationX(rowContentOffsetPx)
                }
                animate(
                    spring(
                        dampingRatio = 0.95f,
                        stiffness = 600f,
                    )
                ) {
                    scale(0.998f)
                }
            }
        }
    }

    Card(
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth(),
        insideMargin = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        onClick = onToggle,
        showIndication = true,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .styleable(selectionStyleState, selectionOverlayStyle)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .styleable(selectionStyleState, rowContentStyle),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(
                    appInfo = appItem.applicationInfo,
                    pm = packageManager,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = appItem.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = appItem.packageName,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                SelectionIndicator(selected = selected)
            }
        }
    }
}

@OptIn(ExperimentalFoundationStyleApi::class)
@Composable
private fun SelectionIndicator(selected: Boolean) {
    val primaryColor = MiuixTheme.colorScheme.primary
    val outlineColor = MiuixTheme.colorScheme.outline
    val onPrimaryColor = MiuixTheme.colorScheme.onPrimary
    val selectionStyleState = remember { MutableStyleState(null) }
    selectionStyleState.isSelected = selected
    val haloStyle = remember(primaryColor) {
        Style {
            alpha(0f)
            scale(0.7f)
            background(primaryColor)
            shape(CircleShape)

            selected {
                animate(
                    spring(
                        dampingRatio = 0.88f,
                        stiffness = 460f,
                    )
                ) {
                    alpha(0.18f)
                }
                animate(
                    spring(
                        dampingRatio = 0.82f,
                        stiffness = 420f,
                    )
                ) {
                    scale(1.7f)
                }
            }
        }
    }
    val ringStyle = remember(primaryColor, outlineColor) {
        Style {
            scale(0.94f)
            border(2.dp, outlineColor)
            shape(CircleShape)

            selected {
                animate(spring(stiffness = 500f)) {
                    borderColor(primaryColor)
                }
                animate(
                    spring(
                        dampingRatio = 0.88f,
                        stiffness = 520f,
                    )
                ) {
                    scale(1f)
                }
            }
        }
    }
    val fillStyle = remember(primaryColor) {
        Style {
            scale(0f)
            background(primaryColor)
            shape(CircleShape)

            selected {
                animate(
                    spring(
                        dampingRatio = 0.78f,
                        stiffness = 680f,
                    )
                ) {
                    scale(1f)
                }
            }
        }
    }
    val iconStyle = remember {
        Style {
            alpha(0f)
            rotationZ(-16f)
            scale(0.9f)

            selected {
                animate(
                    spring(
                        dampingRatio = 0.9f,
                        stiffness = 750f,
                    )
                ) {
                    alpha(1f)
                }
                animate(
                    spring(
                        dampingRatio = 0.84f,
                        stiffness = 560f,
                    )
                ) {
                    rotationZ(0f)
                }
                animate(
                    spring(
                        dampingRatio = 0.78f,
                        stiffness = 680f,
                    )
                ) {
                    scale(1f)
                }
            }
        }
    }

    Box(
        modifier = Modifier.size(30.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .styleable(selectionStyleState, haloStyle)
        )

        Box(
            modifier = Modifier
                .size(24.dp)
                .styleable(selectionStyleState, ringStyle),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .styleable(selectionStyleState, fillStyle)
            )

            Icon(
                imageVector = MiuixIcons.Basic.Check,
                contentDescription = null,
                tint = onPrimaryColor,
                modifier = Modifier
                    .size(16.dp)
                    .styleable(selectionStyleState, iconStyle)
            )
        }
    }
}

@Composable
private fun AppSelectorHeader(
    prefsManager: PrefsManager,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onSearchFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    RearSearchBar(
        query = searchQuery,
        hint = stringResource(R.string.search_apps),
        prefsManager = prefsManager,
        modifier = modifier,
        onQueryChange = onSearchQueryChange,
        onSearchFocusChange = onSearchFocusChange,
    )
}

@Composable
fun AppListSelectorScreen(
    configItem: ConfigItem,
    prefsManager: PrefsManager,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val layoutDirection = LocalLayoutDirection.current
    LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val popupScope = rememberCoroutineScope()

    var appCatalog by remember { mutableStateOf<AppCatalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    val selectedOrder = remember(configItem.key) { mutableStateListOf<String>() }
    var selectedOrderLoaded by remember(configItem.key) { mutableStateOf(false) }
    val remotePrefsStatusRevision = rememberRemotePrefsStatusRevision()

    var showSystemApps by remember(configItem.key) { mutableStateOf(false) }
    var searchInput by remember(configItem.key) { mutableStateOf("") }
    var searchQuery by remember(configItem.key) { mutableStateOf("") }
    val searchFocused = remember(configItem.key) { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var sortMode by remember(configItem.key) { mutableStateOf(AppSortMode.LABEL) }
    var reverseOrder by remember(configItem.key) { mutableStateOf(false) }

    val showSortMenu = remember(configItem.key) { mutableStateOf(false) }
    val showFilterMenu = remember(configItem.key) { mutableStateOf(false) }
    val showMoreMenu = remember(configItem.key) { mutableStateOf(false) }
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()

    BackHandler(enabled = searchFocused.value) {
        focusManager.clearFocus(force = true)
        keyboardController?.hide()
    }

    LaunchedEffect(configItem.key, remotePrefsStatusRevision) {
        val remoteReady = withContext(Dispatchers.IO) { prefsManager.isRemoteReady() }
        if (!remoteReady) {
            selectedOrderLoaded = false
            return@LaunchedEffect
        }

        val loadedSelection = withContext(Dispatchers.IO) {
            prefsManager.getStringSet(configItem.key, configItem.type.defaultStringSet)
        }
        selectedOrder.clear()
        selectedOrder.addAll(loadedSelection)
        selectedOrderLoaded = true
    }

    LaunchedEffect(configItem.key) {
        loading = true
        appCatalog = null
        delay(220)
        appCatalog = withContext(Dispatchers.IO) { buildAppCatalog(pm) }
        delay(80)
        loading = false
    }

    LaunchedEffect(searchInput) {
        delay(90)
        if (searchQuery != searchInput) {
            searchQuery = searchInput
        }
    }

    val selectedOrderSnapshot by remember {
        derivedStateOf { selectedOrder.toList() }
    }
    val selectedLookup = remember(selectedOrderSnapshot) {
        selectedOrderSnapshot.toHashSet()
    }
    val filteredApps by produceState(
        initialValue = emptyList<AppItem>(),
        appCatalog,
        showSystemApps,
        searchQuery,
        selectedOrderSnapshot,
        sortMode,
        reverseOrder,
    ) {
        val catalog = appCatalog
        if (catalog == null) {
            value = emptyList()
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            catalog.buildVisibleApps(
                sortMode = sortMode,
                reverseOrder = reverseOrder,
                showSystemApps = showSystemApps,
                query = searchQuery,
                selectedOrder = selectedOrderSnapshot,
            )
        }
    }
    val dismissThenApply = remember(popupScope) {
        { dismiss: () -> Unit, apply: () -> Unit ->
            dismiss()
            popupScope.launch {
                withFrameNanos { }
                apply()
            }
        }
    }

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .rearAcrylicEffect(hazeState, hazeStyle),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TopAppBar(
                    color = Color.Transparent,
                    title = stringResource(configItem.titleRes),
                    navigationIconPadding = 12.dp,
                    actionIconPadding = 12.dp,
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(
                                modifier = Modifier.graphicsLayer {
                                    if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                                },
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                                tint = MiuixTheme.colorScheme.onBackground,
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(
                                onClick = { showSortMenu.value = true },
                                holdDownState = showSortMenu.value
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Regular.Sort,
                                    contentDescription = stringResource(R.string.app_list_sort),
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                            OverlayListPopup(
                                show = showSortMenu.value,
                                popupModifier = Modifier,
                                popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                                alignment = PopupPositionProvider.Align.End,
                                enableWindowDim = true,
                                onDismissRequest = { showSortMenu.value = false },
                                maxHeight = null,
                                minWidth = 200.dp,
                                renderInRootScaffold = true,
                                content = {
                                    ListPopupColumn {
                                        DropdownImpl(
                                            item = DropdownItem(title = stringResource(R.string.app_list_sort_by_name)),
                                            optionSize = 3,
                                            isSelected = sortMode == AppSortMode.LABEL,
                                            index = 0,
                                            dropdownColors = DropdownDefaults.dropdownColors(),
                                            onSelectedIndexChange = {
                                                dismissThenApply(
                                                    { showSortMenu.value = false },
                                                    { sortMode = AppSortMode.LABEL },
                                                )
                                            }
                                        )
                                        DropdownImpl(
                                            item = DropdownItem(title = stringResource(R.string.app_list_sort_by_package)),
                                            optionSize = 3,
                                            isSelected = sortMode == AppSortMode.PACKAGE,
                                            index = 1,
                                            dropdownColors = DropdownDefaults.dropdownColors(),
                                            onSelectedIndexChange = {
                                                dismissThenApply(
                                                    { showSortMenu.value = false },
                                                    { sortMode = AppSortMode.PACKAGE },
                                                )
                                            }
                                        )
                                        DropdownImpl(
                                            item = DropdownItem(title = stringResource(R.string.app_list_sort_reverse)),
                                            optionSize = 3,
                                            isSelected = reverseOrder,
                                            index = 2,
                                            dropdownColors = DropdownDefaults.dropdownColors(),
                                            onSelectedIndexChange = {
                                                dismissThenApply(
                                                    { showSortMenu.value = false },
                                                    { reverseOrder = !reverseOrder },
                                                )
                                            }
                                        )
                                    }
                                })
                        }

                        Box {
                            IconButton(
                                onClick = { showFilterMenu.value = true },
                                holdDownState = showFilterMenu.value
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Regular.Tune,
                                    contentDescription = stringResource(R.string.app_list_filter),
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                            OverlayListPopup(
                                show = showFilterMenu.value,
                                popupModifier = Modifier,
                                popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                                alignment = PopupPositionProvider.Align.End,
                                enableWindowDim = true,
                                onDismissRequest = { showFilterMenu.value = false },
                                maxHeight = null,
                                minWidth = 200.dp,
                                renderInRootScaffold = true,
                                content = {
                                    ListPopupColumn {
                                        DropdownImpl(
                                            item = DropdownItem(
                                                icon = { modifier ->
                                                    Icon(
                                                        imageVector = Icons.Filled.Apps,
                                                        contentDescription = null,
                                                        modifier = modifier,
                                                        tint = MiuixTheme.colorScheme.onBackground,
                                                    )
                                                },
                                                title = stringResource(R.string.show_system_apps),
                                            ),
                                            optionSize = 1,
                                            isSelected = showSystemApps,
                                            index = 0,
                                            dropdownColors = DropdownDefaults.dropdownColors(),
                                            onSelectedIndexChange = {
                                                dismissThenApply(
                                                    { showFilterMenu.value = false },
                                                    { showSystemApps = !showSystemApps },
                                                )
                                            },
                                        )
                                    }
                                })
                        }

                        Box {
                            IconButton(
                                onClick = { showMoreMenu.value = true },
                                holdDownState = showMoreMenu.value,
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.MoreCircle,
                                    contentDescription = stringResource(R.string.app_list_more_actions),
                                    tint = MiuixTheme.colorScheme.onBackground,
                                )
                            }
                            OverlayListPopup(
                                show = showMoreMenu.value,
                                popupModifier = Modifier,
                                popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
                                alignment = PopupPositionProvider.Align.End,
                                enableWindowDim = true,
                                onDismissRequest = { showMoreMenu.value = false },
                                maxHeight = null,
                                minWidth = 200.dp,
                                renderInRootScaffold = true,
                                content = {
                                    ListPopupColumn {
                                        DropdownImpl(
                                            item = DropdownItem(
                                                icon = { modifier ->
                                                    Icon(
                                                        imageVector = Icons.Filled.Delete,
                                                        contentDescription = null,
                                                        modifier = modifier,
                                                        tint = MiuixTheme.colorScheme.onBackground,
                                                    )
                                                },
                                                title = stringResource(R.string.selection_clear),
                                            ),
                                            optionSize = 1,
                                            isSelected = false,
                                            index = 0,
                                            dropdownColors = DropdownDefaults.dropdownColors(),
                                            onSelectedIndexChange = {
                                                dismissThenApply(
                                                    { showMoreMenu.value = false },
                                                    { selectedOrder.clear() },
                                                )
                                            },
                                        )
                                    }
                                })
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )

                AppSelectorHeader(
                    prefsManager = prefsManager,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                    searchQuery = searchInput,
                    onSearchQueryChange = { searchInput = it },
                    onSearchFocusChange = { searchFocused.value = it },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!selectedOrderLoaded) return@FloatingActionButton
                    prefsManager.putStringSet(configItem.key, selectedLookup)
                    onSave()
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Save,
                    contentDescription = stringResource(R.string.selection_save),
                    tint = MiuixTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(30.dp),
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End,
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.systemBars,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .rearAcrylicSource(hazeState)
                .padding(horizontal = 12.dp),
            state = listState,
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 12.dp,
                bottom = paddingValues.calculateBottomPadding() + 84.dp,
            ),
            overscrollEffect = null,
        ) {
            if (loading || appCatalog == null || !selectedOrderLoaded) {
                item {
                    LoadingAppsCard()
                }
            } else if (filteredApps.isEmpty()) {
                item {
                    EmptyAppsCard()
                }
            } else {
                items(filteredApps, key = { it.packageName }) { appItem ->
                    AppSelectorRow(
                        appItem = appItem,
                        packageManager = pm,
                        selected = appItem.packageName in selectedLookup,
                        onToggle = { selectedOrder.toggleSelection(appItem.packageName) },
                    )
                }
            }
        }
    }
}
