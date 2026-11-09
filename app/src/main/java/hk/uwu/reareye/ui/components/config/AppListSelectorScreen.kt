package hk.uwu.reareye.ui.components.config

import hk.uwu.mixui.icons.Icons as UiIcons

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.RearSearchBar
import hk.uwu.reareye.ui.components.rememberApplicationIconBitmap
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.platform.testTag
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.ButtonDefaults
import hk.uwu.mixui.components.GlassAnchoredMenu
import hk.uwu.mixui.components.GlassMenuOption
import hk.uwu.mixui.components.GlassTopBarScaffold
import hk.uwu.mixui.components.TopBarIconButton
import hk.uwu.mixui.components.pageScrollFeedback
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.InfiniteProgressIndicator
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.theme.MixTheme

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
                tint = MixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_list_empty_result),
                style = MixTheme.textStyles.body2,
                color = MixTheme.colorScheme.onSurfaceVariantSummary,
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
    val primaryColor = MixTheme.colorScheme.primary
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
                        style = MixTheme.textStyles.body2,
                        color = MixTheme.colorScheme.onSurfaceVariantSummary,
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
    val primaryColor = MixTheme.colorScheme.primary
    val outlineColor = MixTheme.colorScheme.outline
    val onPrimaryColor = MixTheme.colorScheme.onPrimary
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
                imageVector = UiIcons.Basic.Check,
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
    val isContentScrolled by remember { derivedStateOf { listState.canScrollBackward } }

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

    GlassTopBarScaffold(
        title = stringResource(configItem.titleRes),
        isContentScrolled = isContentScrolled,
        contentListState = listState,
        navigationIcon = {
            TopBarIconButton(onClick = onCancel, modifier = Modifier.testTag("app-list-back")) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = MixTheme.colorScheme.onBackground,
                )
            }
        },
        actions = {
            GlassAnchoredMenu(
                expanded = showSortMenu.value,
                onExpandedChange = { showSortMenu.value = it },
                options = listOf(
                    GlassMenuOption(stringResource(R.string.app_list_sort_by_name), selected = sortMode == AppSortMode.LABEL),
                    GlassMenuOption(stringResource(R.string.app_list_sort_by_package), selected = sortMode == AppSortMode.PACKAGE),
                    GlassMenuOption(stringResource(R.string.app_list_sort_reverse), selected = reverseOrder),
                ),
                width = 240.dp,
                onOptionClick = { index ->
                    dismissThenApply({ showSortMenu.value = false }) {
                        when (index) {
                            0 -> sortMode = AppSortMode.LABEL
                            1 -> sortMode = AppSortMode.PACKAGE
                            2 -> reverseOrder = !reverseOrder
                        }
                    }
                },
            ) {
                TopBarIconButton(onClick = { showSortMenu.value = true }) {
                    Icon(UiIcons.Regular.Sort, contentDescription = stringResource(R.string.app_list_sort),
                        tint = MixTheme.colorScheme.onBackground)
                }
            }
            GlassAnchoredMenu(
                expanded = showFilterMenu.value,
                onExpandedChange = { showFilterMenu.value = it },
                options = listOf(GlassMenuOption(
                    stringResource(R.string.show_system_apps), selected = showSystemApps,
                    icon = { modifier -> Icon(Icons.Filled.Apps, contentDescription = null,
                        modifier = modifier, tint = MixTheme.colorScheme.onBackground) },
                )),
                width = 240.dp,
                onOptionClick = {
                    dismissThenApply({ showFilterMenu.value = false }) { showSystemApps = !showSystemApps }
                },
            ) {
                TopBarIconButton(onClick = { showFilterMenu.value = true }) {
                    Icon(UiIcons.Regular.Tune, contentDescription = stringResource(R.string.app_list_filter),
                        tint = MixTheme.colorScheme.onBackground)
                }
            }
            GlassAnchoredMenu(
                expanded = showMoreMenu.value,
                onExpandedChange = { showMoreMenu.value = it },
                options = listOf(GlassMenuOption(
                    stringResource(R.string.selection_clear),
                    icon = { modifier -> Icon(Icons.Filled.Delete, contentDescription = null,
                        modifier = modifier, tint = MixTheme.colorScheme.onBackground) },
                )),
                width = 240.dp,
                onOptionClick = {
                    dismissThenApply({ showMoreMenu.value = false }) { selectedOrder.clear() }
                },
            ) {
                TopBarIconButton(onClick = { showMoreMenu.value = true }) {
                    Icon(UiIcons.MoreCircle, contentDescription = stringResource(R.string.app_list_more_actions),
                        tint = MixTheme.colorScheme.onBackground)
                }
            }
        },
        pinnedHeader = {
            AppSelectorHeader(
                prefsManager = prefsManager,
                modifier = Modifier.padding(horizontal = 12.dp).padding(top = 12.dp, bottom = 12.dp),
                searchQuery = searchInput,
                onSearchQueryChange = { searchInput = it },
                onSearchFocusChange = { searchFocused.value = it },
            )
        },
    ) { paddingValues ->
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .pageScrollFeedback()
                .testTag("app-list-results")
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
    Button(
        onClick = {
            if (selectedOrderLoaded) {
                prefsManager.putStringSet(configItem.key, selectedLookup)
                onSave()
            }
        },
        modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding()
            .padding(16.dp).size(56.dp).testTag("app-list-save"),
        cornerRadius = 16.dp,
        minWidth = 56.dp,
        minHeight = 56.dp,
        insideMargin = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColorsPrimary(),
    ) {
        Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.selection_save),
            tint = MixTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp))
    }
        }
    }
}
