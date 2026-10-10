package hk.uwu.reareye.ui.components.config

import android.annotation.SuppressLint
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearstore.RearStoreInstalledWallpaper
import hk.uwu.reareye.repository.rearstore.RearStoreRepository
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperInfo
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperMetadataOptions
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperRepository
import hk.uwu.reareye.ui.FeatureGuideAction
import hk.uwu.reareye.ui.LocalFeatureGuideDemo
import hk.uwu.reareye.ui.components.config.template.RearWallpaperTemplateConfigScreen
import hk.uwu.reareye.ui.components.config.template.TemplateConfigRouteTransition
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar

@Stable
class RearWallpaperManagerState internal constructor() {
    internal val wallpapers = mutableStateListOf<RearWallpaperInfo>()
    internal val schedule = mutableStateListOf<RearWallpaperScheduleEntry>()
    internal val storeWallpaperSources = mutableStateMapOf<Int, RearStoreInstalledWallpaper>()
    internal val currentWallpaperId = mutableStateOf<Int?>(null)
    internal val loading = mutableStateOf(true)
    internal val refreshing = mutableStateOf(false)
    internal val scheduleEnabled = mutableStateOf(false)
    internal val catalogInitialized = mutableStateOf(false)
}

@Composable
fun rememberRearWallpaperManagerState(): RearWallpaperManagerState {
    return remember { RearWallpaperManagerState() }
}

@Composable
fun RearWallpaperManagerScreen(
    prefsManager: PrefsManager,
    state: RearWallpaperManagerState? = null,
    onBack: () -> Unit,
    embedded: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onOpenStoreDetail: (String) -> Unit = {},
    onEditTemplate: ((RearWallpaperInfo) -> Unit)? = null,
    actionRequest: ConfigDashboardAction? = null,
    onActionHandled: () -> Unit = {},
) {
    val demo = LocalFeatureGuideDemo.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val retainedState = state ?: rememberRearWallpaperManagerState()
    val wallpapers = retainedState.wallpapers
    val schedule = retainedState.schedule
    val storeWallpaperSources = retainedState.storeWallpaperSources
    var currentWallpaperId by retainedState.currentWallpaperId
    var loading by retainedState.loading
    var refreshing by retainedState.refreshing
    var scheduleEnabled by retainedState.scheduleEnabled
    var catalogInitialized by retainedState.catalogInitialized
    var activeTemplateWallpaperId by remember { mutableStateOf<Int?>(null) }
    var localImportRequest by remember { mutableStateOf(false) }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun toast(resId: Int) {
        Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
    }

    fun persistSchedule() {
        if (demo != null) return
        val snapshot = schedule.toList()
        val enabled = scheduleEnabled && snapshot.isNotEmpty()
        if (scheduleEnabled && snapshot.isEmpty()) {
            scheduleEnabled = false
            toast(R.string.rear_wallpaper_schedule_empty)
        }
        scope.launch {
            val synced = withContext(Dispatchers.IO) {
                RearWallpaperRepository.saveSchedule(prefsManager, snapshot)
                RearWallpaperRepository.setScheduleEnabled(prefsManager, enabled)
                RearWallpaperRepository.syncSchedule(context, enabled, snapshot)
            }
            if (!synced) toast(R.string.rear_wallpaper_schedule_sync_failed)
        }
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun refreshCatalog(showSuccessToast: Boolean = false) {
        if (demo != null) {
            wallpapers.clear()
            wallpapers.addAll(demo.state.wallpapers(context.getString(R.string.guide_demo_wallpaper_name)))
            currentWallpaperId = demo.state.currentWallpaperId
            catalogInitialized = true
            loading = false
            refreshing = false
            return
        }
        scope.launch {
            refreshing = true
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val catalog = RearWallpaperRepository.loadCatalog(context)
                    RearStoreRepository.pruneInstalledWallpaperRecords(
                        prefsManager = prefsManager,
                        installedWallpaperIds = catalog.wallpapers.mapTo(HashSet()) { it.wallpaperId },
                    )
                    catalog to RearStoreRepository.loadInstalledWallpaperSources(prefsManager)
                }
            }
            result.onSuccess { loaded ->
                val (catalog, sources) = loaded
                wallpapers.clear()
                wallpapers.addAll(catalog.wallpapers)
                storeWallpaperSources.clear()
                storeWallpaperSources.putAll(sources)
                currentWallpaperId = catalog.currentWallpaperId
                catalogInitialized = true
                if (showSuccessToast) toast(R.string.rear_wallpaper_refresh_success)
            }.onFailure {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.rear_wallpaper_refresh_failed,
                        it.message ?: "unknown"
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            loading = false
            refreshing = false
        }
    }

    fun switchWallpaper(wallpaperId: Int) {
        if (demo != null) {
            currentWallpaperId = wallpaperId
            demo.state.currentWallpaperId = wallpaperId
            demo.onAction(FeatureGuideAction.APPLY_WALLPAPER)
            return
        }
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                RearWallpaperRepository.switchWallpaper(context, wallpaperId)
            }
            if (success) {
                currentWallpaperId = wallpaperId
            } else {
                toast(R.string.rear_wallpaper_switch_failed)
            }
        }
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun importWallpaperPackage(
        packageUri: Uri,
        metadataUri: Uri?,
        previewUri: Uri?,
        options: RearWallpaperMetadataOptions,
    ) {
        if (demo != null) return
        scope.launch {
            refreshing = true
            val result = withContext(Dispatchers.IO) {
                RearWallpaperRepository.importWallpaperPackage(
                    context = context,
                    packageUri = packageUri,
                    metadataUri = metadataUri,
                    previewUri = previewUri,
                    options = options,
                )
            }
            refreshing = false
            if (result.success) {
                toast(R.string.rear_wallpaper_import_success)
                refreshCatalog(showSuccessToast = false)
            } else {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.rear_wallpaper_import_failed,
                        result.error ?: "unknown",
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun updateWallpaperMetadata(
        wallpaper: RearWallpaperInfo,
        options: RearWallpaperMetadataOptions,
        previewUri: Uri?,
    ) {
        if (demo != null) return
        scope.launch {
            refreshing = true
            val result = withContext(Dispatchers.IO) {
                RearWallpaperRepository.updateWallpaperMetadata(
                    context = context,
                    wallpaperId = wallpaper.wallpaperId,
                    previewUri = previewUri,
                    options = options,
                )
            }
            refreshing = false
            if (result.success) {
                toast(R.string.rear_wallpaper_metadata_saved)
                refreshCatalog(showSuccessToast = false)
            } else {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.rear_wallpaper_metadata_save_failed,
                        result.error ?: "unknown",
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun generateWallpaperPreview(wallpaper: RearWallpaperInfo) {
        if (demo != null) return
        scope.launch {
            refreshing = true
            val result = withContext(Dispatchers.IO) {
                RearWallpaperRepository.generateWallpaperPreview(context, wallpaper.wallpaperId)
            }
            refreshing = false
            if (result.success) {
                toast(R.string.rear_wallpaper_preview_generated)
                refreshCatalog(showSuccessToast = false)
            } else {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.rear_wallpaper_preview_generate_failed,
                        result.error ?: "unknown",
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun deleteWallpaper(wallpaper: RearWallpaperInfo) {
        if (demo != null) return
        scope.launch {
            refreshing = true
            val result = withContext(Dispatchers.IO) {
                RearWallpaperRepository.deleteWallpaper(context, wallpaper.wallpaperId)
                    .also { result ->
                        if (result.success) {
                            RearStoreRepository.removeInstalledWallpaperRecord(
                                prefsManager = prefsManager,
                                wallpaperId = wallpaper.wallpaperId,
                            )
                        }
                    }
            }
            refreshing = false
            if (result.success) {
                schedule.removeAll { it.wallpaperId == wallpaper.wallpaperId }
                storeWallpaperSources.remove(wallpaper.wallpaperId)
                persistSchedule()
                toast(R.string.rear_wallpaper_delete_success)
                refreshCatalog(showSuccessToast = false)
            } else {
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.rear_wallpaper_delete_failed,
                        result.error ?: "unknown",
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (demo != null) {
            refreshCatalog(); return@LaunchedEffect
        }
        if (!catalogInitialized) {
            schedule.clear()
            schedule.addAll(RearWallpaperRepository.loadSchedule(prefsManager))
            scheduleEnabled = RearWallpaperRepository.isScheduleEnabled(prefsManager)
            refreshCatalog()
        }
    }

    LaunchedEffect(demo?.action) {
        if (demo?.action == FeatureGuideAction.APPLY_WALLPAPER) {
            // Replaying this lesson must leave the normal Apply button enabled.
            currentWallpaperId = null
            demo.state.currentWallpaperId = null
        }
    }

    LaunchedEffect(actionRequest) {
        when (actionRequest) {
            ConfigDashboardAction.REFRESH_WALLPAPER -> {
                refreshCatalog(showSuccessToast = true)
                onActionHandled()
            }

            else -> Unit
        }
    }

    val activeTemplateWallpaper = activeTemplateWallpaperId
        ?.let { id -> wallpapers.firstOrNull { it.wallpaperId == id } }

    TemplateConfigRouteTransition(
        target = activeTemplateWallpaper,
        contentKey = { it?.wallpaperId ?: -1 },
        templateContent = { wallpaper ->
            RearWallpaperTemplateConfigScreen(
                wallpaper = wallpaper,
                onBack = { activeTemplateWallpaperId = null },
                onSaved = {
                    activeTemplateWallpaperId = null
                    refreshCatalog()
                },
            )
        },
    ) {
        val content: @Composable (PaddingValues) -> Unit = { padding ->
            RearWallpaperManagementContent(
                paddingValues = PaddingValues(
                    top = padding.calculateTopPadding() + contentPadding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + contentPadding.calculateBottomPadding(),
                ),
                scrollBehavior = scrollBehavior,
                hazeState = if (embedded) null else hazeState,
                wallpapers = wallpapers,
                storeWallpaperSources = storeWallpaperSources,
                currentWallpaperId = currentWallpaperId,
                loading = loading,
                refreshing = refreshing,
                schedule = schedule,
                importRequest = localImportRequest || actionRequest == ConfigDashboardAction.IMPORT_WALLPAPER,
                onImportRequestHandled = {
                    localImportRequest = false
                    if (actionRequest == ConfigDashboardAction.IMPORT_WALLPAPER) {
                        onActionHandled()
                    }
                },
                scheduleEnabled = scheduleEnabled,
                onScheduleEnabledChange = { enabled ->
                    if (enabled && schedule.isEmpty()) {
                        toast(R.string.rear_wallpaper_rotation_select_first)
                    } else {
                        scheduleEnabled = enabled
                        persistSchedule()
                    }
                },
                onScheduleChange = { updated ->
                    schedule.clear()
                    schedule.addAll(updated)
                    persistSchedule()
                },
                onRefresh = { if (!refreshing) refreshCatalog(showSuccessToast = true) },
                onSetCurrent = ::switchWallpaper,
                onImport = ::importWallpaperPackage,
                onUpdateMetadata = ::updateWallpaperMetadata,
                onEditTemplate = { wallpaper ->
                    if (demo != null) Unit
                    else if (onEditTemplate != null) {
                        onEditTemplate(wallpaper)
                    } else {
                        activeTemplateWallpaperId = wallpaper.wallpaperId
                    }
                },
                onGeneratePreview = ::generateWallpaperPreview,
                onOpenStoreDetail = onOpenStoreDetail,
                onDelete = ::deleteWallpaper,
            )
        }
        if (embedded) {
            content(PaddingValues(0.dp))
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                        color = Color.Transparent,
                        title = stringResource(R.string.rear_wallpaper_manager),
                        navigationIconPadding = 12.dp,
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        },
                        actions = {
                            IconButton(onClick = { localImportRequest = true }) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.rear_wallpaper_import),
                                )
                            }
                            IconButton(
                                onClick = { if (!refreshing) refreshCatalog(showSuccessToast = true) },
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = stringResource(R.string.rear_wallpaper_refresh),
                                )
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                },
                content = content,
            )
        }
    }
}
