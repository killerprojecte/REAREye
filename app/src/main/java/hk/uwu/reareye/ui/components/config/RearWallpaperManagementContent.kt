package hk.uwu.reareye.ui.components.config

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SyncDisabled
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearstore.RearStoreInstalledWallpaper
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperInfo
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperMetadataOptions
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.ui.components.OverlayDialog
import hk.uwu.reareye.ui.components.RearBadgeGroup
import hk.uwu.reareye.ui.components.card.ModuleStyleDeleteAction
import hk.uwu.reareye.ui.components.card.ModuleStyleIconAction
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.reareye.ui.components.card.ModuleStyleTextAction
import hk.uwu.reareye.ui.components.card.SuperCard
import hk.uwu.reareye.ui.components.rememberRearWallpaperPreviewBitmap
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.widgetapi.RearWallpaperScheduleEntry
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val MANAGE_PREVIEW_RATIO = 1.6f

@Composable
fun RearWallpaperManagementContent(
    paddingValues: PaddingValues,
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState,
    wallpapers: List<RearWallpaperInfo>,
    storeWallpaperSources: Map<Int, RearStoreInstalledWallpaper>,
    currentWallpaperId: Int?,
    loading: Boolean,
    refreshing: Boolean,
    importRequest: Boolean = false,
    onImportRequestHandled: () -> Unit = {},
    schedule: List<RearWallpaperScheduleEntry>,
    scheduleEnabled: Boolean,
    onScheduleEnabledChange: (Boolean) -> Unit,
    onScheduleChange: (List<RearWallpaperScheduleEntry>) -> Unit,
    onRefresh: () -> Unit,
    onSetCurrent: (Int) -> Unit,
    onImport: (Uri, Uri?, Uri?, RearWallpaperMetadataOptions) -> Unit,
    onUpdateMetadata: (RearWallpaperInfo, RearWallpaperMetadataOptions, Uri?) -> Unit,
    onEditTemplate: (RearWallpaperInfo) -> Unit,
    onGeneratePreview: (RearWallpaperInfo) -> Unit,
    onDelete: (RearWallpaperInfo) -> Unit,
) {
    var showImportDialog by remember { mutableStateOf(false) }
    var packageUri by remember { mutableStateOf<Uri?>(null) }
    var packageLabel by remember { mutableStateOf("") }
    var metadataUri by remember { mutableStateOf<Uri?>(null) }
    var metadataLabel by remember { mutableStateOf("") }
    var previewUri by remember { mutableStateOf<Uri?>(null) }
    var previewLabel by remember { mutableStateOf("") }
    var importTitleFallback by remember { mutableStateOf("") }
    var importTitleZhCn by remember { mutableStateOf("") }
    var importDescriptionFallback by remember { mutableStateOf("Imported by REAREye") }
    var importDescriptionZhCn by remember { mutableStateOf("由 REAREye 导入") }
    var importAuthor by remember { mutableStateOf("") }
    var importDesigner by remember { mutableStateOf("") }
    var importCategory by remember { mutableStateOf("REAREye") }
    var importResSubType by remember { mutableStateOf("reareye_import") }
    var importEditable by remember { mutableStateOf(false) }
    var importThirdParties by remember { mutableStateOf(true) }
    var importSupportAon by remember { mutableStateOf(false) }

    var editTarget by remember { mutableStateOf<RearWallpaperInfo?>(null) }
    var editTitleFallback by remember { mutableStateOf("") }
    var editTitleZhCn by remember { mutableStateOf("") }
    var editDescriptionFallback by remember { mutableStateOf("") }
    var editDescriptionZhCn by remember { mutableStateOf("") }
    var editAuthor by remember { mutableStateOf("") }
    var editDesigner by remember { mutableStateOf("") }
    var editCategory by remember { mutableStateOf("") }
    var editResSubType by remember { mutableStateOf("") }
    var editEditable by remember { mutableStateOf(false) }
    var editThirdParties by remember { mutableStateOf(true) }
    var editSupportAon by remember { mutableStateOf(false) }
    var editPreviewUri by remember { mutableStateOf<Uri?>(null) }
    var editPreviewLabel by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<RearWallpaperInfo?>(null) }
    var intervalTargetId by remember { mutableStateOf<Int?>(null) }
    var intervalInput by remember { mutableStateOf("") }

    val packagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        packageUri = uri
        val label = uri.displayLabel()
        packageLabel = label
        val defaultTitle = label.substringBeforeLast('.', label).ifBlank { label }
        if (importTitleFallback.isBlank()) importTitleFallback = defaultTitle
        if (importTitleZhCn.isBlank()) importTitleZhCn = defaultTitle
    }
    val metadataPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        metadataUri = uri
        metadataLabel = uri?.displayLabel().orEmpty()
    }
    val previewPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        previewUri = uri
        previewLabel = uri?.displayLabel().orEmpty()
    }
    val editPreviewPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        editPreviewUri = uri
        editPreviewLabel = uri?.displayLabel().orEmpty()
    }

    LaunchedEffect(editTarget?.wallpaperId) {
        val target = editTarget ?: return@LaunchedEffect
        editTitleFallback = target.name
        editTitleZhCn = target.name
        editDescriptionFallback = target.description
        editDescriptionZhCn = target.description
        editAuthor = target.author
        editDesigner = target.designer
        editCategory = target.title
        editResSubType = target.resSubType.ifBlank { "reareye_import" }
        editEditable = target.editable
        editThirdParties = target.thirdParties
        editSupportAon = target.supportAon
        editPreviewUri = null
        editPreviewLabel = ""
    }

    fun resetImportDialog() {
        packageUri = null
        packageLabel = ""
        metadataUri = null
        metadataLabel = ""
        previewUri = null
        previewLabel = ""
        importTitleFallback = ""
        importTitleZhCn = ""
        importDescriptionFallback = "Imported by REAREye"
        importDescriptionZhCn = "由 REAREye 导入"
        importAuthor = ""
        importDesigner = ""
        importCategory = "REAREye"
        importResSubType = "reareye_import"
        importEditable = false
        importThirdParties = true
        importSupportAon = false
    }

    LaunchedEffect(importRequest) {
        if (importRequest) {
            resetImportDialog()
            showImportDialog = true
            onImportRequestHandled()
        }
    }

    RearWallpaperManagementList(
        paddingValues = paddingValues,
        scrollBehavior = scrollBehavior,
        hazeState = hazeState,
        wallpapers = wallpapers,
        storeWallpaperSources = storeWallpaperSources,
        currentWallpaperId = currentWallpaperId,
        loading = loading,
        refreshing = refreshing,
        schedule = schedule,
        scheduleEnabled = scheduleEnabled,
        onScheduleEnabledChange = onScheduleEnabledChange,
        onScheduleChange = onScheduleChange,
        onRefresh = onRefresh,
        onImportClick = {
            resetImportDialog()
            showImportDialog = true
        },
        onSetCurrent = onSetCurrent,
        onEditInterval = { entry ->
            intervalTargetId = entry.wallpaperId
            intervalInput = entry.delayMs.toString()
        },
        onEditMetadata = { editTarget = it },
        onEditTemplate = onEditTemplate,
        onGeneratePreview = onGeneratePreview,
        onDelete = { deleteTarget = it },
    )

    OverlayDialog(
        show = showImportDialog,
        title = stringResource(R.string.rear_wallpaper_import),
        onDismissRequest = { showImportDialog = false },
    ) {
        WallpaperImportForm(
            packageLabel = packageLabel,
            metadataLabel = metadataLabel,
            previewLabel = previewLabel,
            titleFallback = importTitleFallback,
            titleZhCn = importTitleZhCn,
            descriptionFallback = importDescriptionFallback,
            descriptionZhCn = importDescriptionZhCn,
            author = importAuthor,
            designer = importDesigner,
            category = importCategory,
            resSubType = importResSubType,
            editable = importEditable,
            thirdParties = importThirdParties,
            supportAon = importSupportAon,
            onPickPackage = {
                packagePickerLauncher.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream",
                        "*/*",
                    )
                )
            },
            onPickMetadata = {
                metadataPickerLauncher.launch(
                    arrayOf("application/json", "application/octet-stream", "*/*")
                )
            },
            onClearMetadata = {
                metadataUri = null
                metadataLabel = ""
            },
            onPickPreview = {
                previewPickerLauncher.launch(arrayOf("image/*", "application/octet-stream", "*/*"))
            },
            onClearPreview = {
                previewUri = null
                previewLabel = ""
            },
            onTitleFallbackChange = { importTitleFallback = it },
            onTitleZhCnChange = { importTitleZhCn = it },
            onDescriptionFallbackChange = { importDescriptionFallback = it },
            onDescriptionZhCnChange = { importDescriptionZhCn = it },
            onAuthorChange = { importAuthor = it },
            onDesignerChange = { importDesigner = it },
            onCategoryChange = { importCategory = it },
            onResSubTypeChange = { importResSubType = it },
            onEditableChange = { importEditable = it },
            onThirdPartiesChange = { importThirdParties = it },
            onSupportAonChange = { importSupportAon = it },
            onConfirm = {
                val source = packageUri ?: return@WallpaperImportForm
                onImport(
                    source,
                    metadataUri,
                    previewUri,
                    RearWallpaperMetadataOptions(
                        titleFallback = importTitleFallback.ifBlank {
                            packageLabel.ifBlank { "Imported Wallpaper" }
                        },
                        titleZhCn = importTitleZhCn.ifBlank { importTitleFallback },
                        descriptionFallback = importDescriptionFallback,
                        descriptionZhCn = importDescriptionZhCn.ifBlank { importDescriptionFallback },
                        author = importAuthor,
                        designer = importDesigner,
                        category = importCategory.ifBlank { "REAREye" },
                        resSubType = importResSubType.ifBlank { "reareye_import" },
                        editable = importEditable,
                        thirdParties = importThirdParties,
                        supportAon = importSupportAon,
                    ),
                )
                showImportDialog = false
            },
            onCancel = { showImportDialog = false },
        )
    }

    OverlayDialog(
        show = editTarget != null,
        title = stringResource(R.string.rear_wallpaper_edit_metadata),
        onDismissRequest = { editTarget = null },
    ) {
        WallpaperMetadataForm(
            titleFallback = editTitleFallback,
            titleZhCn = editTitleZhCn,
            descriptionFallback = editDescriptionFallback,
            descriptionZhCn = editDescriptionZhCn,
            author = editAuthor,
            designer = editDesigner,
            category = editCategory,
            resSubType = editResSubType,
            editable = editEditable,
            thirdParties = editThirdParties,
            supportAon = editSupportAon,
            previewLabel = editPreviewLabel,
            onTitleFallbackChange = { editTitleFallback = it },
            onTitleZhCnChange = { editTitleZhCn = it },
            onDescriptionFallbackChange = { editDescriptionFallback = it },
            onDescriptionZhCnChange = { editDescriptionZhCn = it },
            onAuthorChange = { editAuthor = it },
            onDesignerChange = { editDesigner = it },
            onCategoryChange = { editCategory = it },
            onResSubTypeChange = { editResSubType = it },
            onEditableChange = { editEditable = it },
            onThirdPartiesChange = { editThirdParties = it },
            onSupportAonChange = { editSupportAon = it },
            onPickPreview = {
                editPreviewPickerLauncher.launch(
                    arrayOf(
                        "image/*",
                        "application/octet-stream",
                        "*/*"
                    )
                )
            },
            onClearPreview = {
                editPreviewUri = null
                editPreviewLabel = ""
            },
            onConfirm = {
                val target = editTarget ?: return@WallpaperMetadataForm
                onUpdateMetadata(
                    target,
                    RearWallpaperMetadataOptions(
                        titleFallback = editTitleFallback.ifBlank { target.name },
                        titleZhCn = editTitleZhCn.ifBlank {
                            editTitleFallback.ifBlank { target.name }
                        },
                        descriptionFallback = editDescriptionFallback,
                        descriptionZhCn = editDescriptionZhCn.ifBlank { editDescriptionFallback },
                        author = editAuthor,
                        designer = editDesigner,
                        category = editCategory.ifBlank { "REAREye" },
                        resSubType = editResSubType.ifBlank { "reareye_import" },
                        editable = editEditable,
                        thirdParties = editThirdParties,
                        supportAon = editSupportAon,
                    ),
                    editPreviewUri,
                )
                editTarget = null
            },
            onCancel = { editTarget = null },
        )
    }

    OverlayDialog(
        show = intervalTargetId != null,
        title = stringResource(R.string.rear_wallpaper_edit_interval),
        onDismissRequest = { intervalTargetId = null },
    ) {
        DialogFormColumn {
            TextField(
                value = intervalInput,
                onValueChange = { intervalInput = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.rear_wallpaper_interval_millis),
                singleLine = true,
            )
            Button(
                onClick = {
                    val targetId = intervalTargetId ?: return@Button
                    val delayMs = intervalInput.toLongOrNull()
                    if (delayMs == null || delayMs < hk.uwu.reareye.widgetapi.RearWallpaperScheduleCodec.MIN_DELAY_MS) return@Button
                    val updated = schedule.map {
                        if (it.wallpaperId == targetId) it.copy(delayMs = delayMs) else it
                    }
                    onScheduleChange(updated)
                    intervalTargetId = null
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.rear_widget_confirm)) }
            Button(
                onClick = { intervalTargetId = null },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.rear_widget_cancel)) }
        }
    }

    OverlayDialog(
        show = deleteTarget != null,
        title = stringResource(R.string.rear_wallpaper_delete_title),
        onDismissRequest = { deleteTarget = null },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                SuperCard(
                    title = stringResource(
                        R.string.rear_wallpaper_delete_message,
                        deleteTarget?.name.orEmpty(),
                    ),
                )
            }
            Button(
                onClick = {
                    deleteTarget?.let(onDelete)
                    deleteTarget = null
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rear_widget_action_delete))
            }
            Button(
                onClick = { deleteTarget = null },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.rear_widget_cancel))
            }
        }
    }
}

@Composable
private fun RearWallpaperManagementList(
    paddingValues: PaddingValues,
    scrollBehavior: ScrollBehavior,
    hazeState: HazeState,
    wallpapers: List<RearWallpaperInfo>,
    storeWallpaperSources: Map<Int, RearStoreInstalledWallpaper>,
    currentWallpaperId: Int?,
    loading: Boolean,
    refreshing: Boolean,
    schedule: List<RearWallpaperScheduleEntry>,
    scheduleEnabled: Boolean,
    onScheduleEnabledChange: (Boolean) -> Unit,
    onScheduleChange: (List<RearWallpaperScheduleEntry>) -> Unit,
    onRefresh: () -> Unit,
    onImportClick: () -> Unit,
    onSetCurrent: (Int) -> Unit,
    onEditInterval: (RearWallpaperScheduleEntry) -> Unit,
    onEditMetadata: (RearWallpaperInfo) -> Unit,
    onEditTemplate: (RearWallpaperInfo) -> Unit,
    onGeneratePreview: (RearWallpaperInfo) -> Unit,
    onDelete: (RearWallpaperInfo) -> Unit,
) {
    val currentWallpaperName = wallpapers.firstOrNull { it.wallpaperId == currentWallpaperId }?.name
        ?: stringResource(R.string.rear_wallpaper_current_none)
    val overviewBadges = rearWallpaperManagementOverviewBadges(
        currentWallpaperName = currentWallpaperName,
        wallpaperCount = wallpapers.size,
    )
    val listState = rememberLazyListState()
    val dragScope = rememberCoroutineScope()
    val orderedSchedule = remember { mutableStateListOf<RearWallpaperScheduleEntry>() }
    val dragState = rememberRearLongPressDragState()
    var dragOriginSchedule by remember { mutableStateOf<List<RearWallpaperScheduleEntry>>(emptyList()) }
    LaunchedEffect(schedule.toList()) {
        if (dragState.draggedId == null) {
            orderedSchedule.clear()
            orderedSchedule.addAll(schedule)
        }
    }
    val scheduledIds = orderedSchedule.mapTo(HashSet()) { it.wallpaperId }
    val remainingWallpapers = wallpapers.filterNot { it.wallpaperId in scheduledIds }
    val wallpaperMap = wallpapers.associateBy { it.wallpaperId }

    fun moveDraggedItem(draggingId: Int, targetId: Int) {
        val from = orderedSchedule.indexOfFirst { it.wallpaperId == draggingId }
        val to = orderedSchedule.indexOfFirst { it.wallpaperId == targetId }
        if (from < 0 || to < 0 || from == to) return
        val item = orderedSchedule.removeAt(from)
        orderedSchedule.add(to, item)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .rearAcrylicSource(hazeState)
            .padding(horizontal = 12.dp),
        state = listState,
        contentPadding = PaddingValues(
            top = paddingValues.calculateTopPadding(),
            bottom = paddingValues.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        overscrollEffect = null,
        userScrollEnabled = dragState.draggedId == null,
    ) {
        item(key = "wallpaper-overview") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                insideMargin = PaddingValues(16.dp),
            ) {
                RearBadgeGroup(badges = overviewBadges)
            }
        }

        item(key = "wallpaper-rotation-control") {
            Card(modifier = Modifier.fillMaxWidth()) {
                BasicComponent(
                    title = stringResource(R.string.rear_wallpaper_rotation_toggle),
                    startAction = {
                        Icon(
                            imageVector = if (scheduleEnabled) Icons.Rounded.Sync else Icons.Rounded.SyncDisabled,
                            contentDescription = null,
                        )
                    },
                    onClick = { onScheduleEnabledChange(!scheduleEnabled) },
                    endActions = {
                        Switch(
                            checked = scheduleEnabled,
                            onCheckedChange = onScheduleEnabledChange,
                        )
                    },
                )
            }
        }

        if (orderedSchedule.isNotEmpty()) {
            items(orderedSchedule, key = { "rotation-${it.wallpaperId}" }) { entry ->
                val wallpaper = wallpaperMap[entry.wallpaperId]
                WallpaperManageCard(
                    wallpaper = wallpaper,
                    storeSource = wallpaper?.let { storeWallpaperSources[it.wallpaperId] },
                    isCurrent = wallpaper?.wallpaperId == currentWallpaperId,
                    inSchedule = true,
                    intervalLabel = formatDelay(entry.delayMs, LocalLocale.current.platformLocale),
                    onToggleSchedule = {
                        orderedSchedule.removeAll { it.wallpaperId == entry.wallpaperId }
                        onScheduleChange(orderedSchedule.toList())
                    },
                    onSetCurrent = { wallpaper?.let { onSetCurrent(it.wallpaperId) } },
                    onEditInterval = { onEditInterval(entry) },
                    onEditMetadata = { wallpaper?.let(onEditMetadata) },
                    onEditTemplate = { wallpaper?.let(onEditTemplate) },
                    onGeneratePreview = { wallpaper?.let(onGeneratePreview) },
                    onDelete = { wallpaper?.let(onDelete) },
                    dragModifier = Modifier
                        .rearDragVisual(entry.wallpaperId, dragState)
                        .rearLongPressDrag(
                            id = entry.wallpaperId,
                            state = dragState,
                            listState = listState,
                            scope = dragScope,
                            layoutKeyToId = { key ->
                                key.toString().removePrefix("rotation-")
                                    .toIntOrNull()
                                    ?.takeIf { key.toString().startsWith("rotation-") }
                            },
                            onMove = { fromId, toId ->
                                moveDraggedItem(fromId as Int, toId as Int)
                            },
                            onDragStart = { dragOriginSchedule = orderedSchedule.toList() },
                            onDragCancel = {
                                orderedSchedule.clear()
                                orderedSchedule.addAll(dragOriginSchedule)
                                dragOriginSchedule = emptyList()
                            },
                            onDragEnd = {
                                onScheduleChange(orderedSchedule.toList())
                                dragOriginSchedule = emptyList()
                            },
                        ),
                )
            }
        }

        if (loading) {
            item(key = "wallpaper-loading") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    SuperCard(
                        title = stringResource(R.string.rear_wallpaper_loading),
                        startAction = { InfiniteProgressIndicator() })
                }
            }
        }
        if (!loading && wallpapers.isEmpty()) {
            item(key = "wallpaper-empty") {
                Card(modifier = Modifier.fillMaxWidth()) { SuperCard(title = stringResource(R.string.rear_wallpaper_catalog_empty)) }
            }
        }
        items(remainingWallpapers, key = { "library-${it.wallpaperId}" }) { wallpaper ->
            WallpaperManageCard(
                wallpaper = wallpaper,
                storeSource = storeWallpaperSources[wallpaper.wallpaperId],
                isCurrent = wallpaper.wallpaperId == currentWallpaperId,
                inSchedule = false,
                intervalLabel = null,
                onToggleSchedule = {
                    val updated = orderedSchedule + RearWallpaperScheduleEntry(
                        wallpaperId = wallpaper.wallpaperId,
                        delayMs = hk.uwu.reareye.widgetapi.RearWallpaperScheduleCodec.DEFAULT_DELAY_MS,
                    )
                    orderedSchedule.clear()
                    orderedSchedule.addAll(updated)
                    onScheduleChange(updated)
                },
                onSetCurrent = { onSetCurrent(wallpaper.wallpaperId) },
                onEditInterval = {},
                onEditMetadata = { onEditMetadata(wallpaper) },
                onEditTemplate = { onEditTemplate(wallpaper) },
                onGeneratePreview = { onGeneratePreview(wallpaper) },
                onDelete = { onDelete(wallpaper) },
            )
        }
    }
}

@Composable
private fun WallpaperManageCard(
    wallpaper: RearWallpaperInfo?,
    storeSource: RearStoreInstalledWallpaper?,
    isCurrent: Boolean,
    inSchedule: Boolean,
    intervalLabel: String?,
    onToggleSchedule: () -> Unit,
    onSetCurrent: () -> Unit,
    onEditInterval: () -> Unit,
    onEditMetadata: () -> Unit,
    onEditTemplate: () -> Unit,
    onGeneratePreview: () -> Unit,
    onDelete: () -> Unit,
    dragModifier: Modifier = Modifier,
) {
    if (wallpaper == null) return
    val rotationAction = stringResource(
        if (inSchedule) R.string.rear_wallpaper_rotation_remove else R.string.rear_wallpaper_rotation_add,
    )
    ModuleStyleManagerCard(
        modifier = dragModifier,
        title = wallpaper.name,
        summaryLines = emptyList(),
        badges = rearWallpaperManagementBadges(
            wallpaper = wallpaper,
            storeSource = storeSource,
            isCurrent = isCurrent,
            inSchedule = inSchedule,
            intervalLabel = intervalLabel,
        ),
        headerVerticalAlignment = Alignment.Top,
        trailing = {
            ManagedWallpaperPreview(
                cachePath = wallpaper.cachePath,
                modifier = Modifier
                    .width(104.dp)
                    .aspectRatio(MANAGE_PREVIEW_RATIO),
            )
        },
        leftAction = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModuleStyleIconAction(
                    modifier = Modifier
                        .size(20.dp)
                        .semantics { contentDescription = rotationAction },
                    icon = if (inSchedule) Icons.Rounded.Sync else Icons.Rounded.SyncDisabled,
                    onClick = onToggleSchedule,
                )
                ModuleStyleTextAction(
                    icon = Icons.Filled.Check,
                    text = stringResource(R.string.rear_wallpaper_set_now),
                    enabled = !isCurrent,
                    onClick = onSetCurrent,
                )
                if (inSchedule) {
                    ModuleStyleTextAction(
                        icon = Icons.Filled.Tune,
                        text = stringResource(R.string.rear_wallpaper_edit_interval_short),
                        onClick = onEditInterval,
                    )
                }
                if (wallpaper.canEditMetadata) {
                    ModuleStyleIconAction(
                        icon = Icons.Outlined.PhotoCamera,
                        modifier = Modifier.size(18.dp),
                        onClick = onGeneratePreview
                    )
                }
                if (wallpaper.canEditMetadata || wallpaper.editable) {
                    ModuleStyleIconAction(icon = Icons.Rounded.EditNote, onClick = onEditMetadata)
                }
                if (wallpaper.templateConfigAvailable) {
                    ModuleStyleTextAction(
                        icon = Icons.Filled.Tune,
                        text = stringResource(R.string.rear_widget_action_config),
                        onClick = onEditTemplate
                    )
                }
            }
        },
        rightAction = {
            if (wallpaper.canDelete || wallpaper.imported) {
                ModuleStyleDeleteAction(
                    icon = MiuixIcons.Delete,
                    text = stringResource(R.string.rear_widget_action_delete),
                    onClick = onDelete
                )
            }
        },
    )
}

private fun formatDelay(delayMs: Long, locale: java.util.Locale): String {
    val totalSeconds = (delayMs / 1000L).coerceAtLeast(1L)
    return if (totalSeconds >= 60L && totalSeconds % 60L == 0L) {
        "${totalSeconds / 60L}m"
    } else {
        "${totalSeconds}s"
    }
}

@Composable
private fun WallpaperImportForm(
    packageLabel: String,
    metadataLabel: String,
    previewLabel: String,
    titleFallback: String,
    titleZhCn: String,
    descriptionFallback: String,
    descriptionZhCn: String,
    author: String,
    designer: String,
    category: String,
    resSubType: String,
    editable: Boolean,
    thirdParties: Boolean,
    supportAon: Boolean,
    onPickPackage: () -> Unit,
    onPickMetadata: () -> Unit,
    onClearMetadata: () -> Unit,
    onPickPreview: () -> Unit,
    onClearPreview: () -> Unit,
    onTitleFallbackChange: (String) -> Unit,
    onTitleZhCnChange: (String) -> Unit,
    onDescriptionFallbackChange: (String) -> Unit,
    onDescriptionZhCnChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onDesignerChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onResSubTypeChange: (String) -> Unit,
    onEditableChange: (Boolean) -> Unit,
    onThirdPartiesChange: (Boolean) -> Unit,
    onSupportAonChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    DialogFormColumn(maxHeight = 620.dp) {
        FilePickRow(
            title = stringResource(R.string.rear_wallpaper_package_file),
            label = packageLabel.ifBlank { stringResource(R.string.rear_wallpaper_no_file_selected) },
            onPick = onPickPackage,
        )
        FilePickRow(
            title = stringResource(R.string.rear_wallpaper_metadata_file),
            label = metadataLabel.ifBlank { stringResource(R.string.rear_wallpaper_metadata_default_hint) },
            onPick = onPickMetadata,
            onClear = onClearMetadata.takeIf { metadataLabel.isNotBlank() },
        )
        FilePickRow(
            title = stringResource(R.string.rear_wallpaper_preview_file),
            label = previewLabel.ifBlank { stringResource(R.string.rear_wallpaper_preview_default_hint) },
            onPick = onPickPreview,
            onClear = onClearPreview.takeIf { previewLabel.isNotBlank() },
        )
        WallpaperMetadataFields(
            titleFallback = titleFallback,
            titleZhCn = titleZhCn,
            descriptionFallback = descriptionFallback,
            descriptionZhCn = descriptionZhCn,
            author = author,
            designer = designer,
            category = category,
            resSubType = resSubType,
            editable = editable,
            thirdParties = thirdParties,
            supportAon = supportAon,
            onTitleFallbackChange = onTitleFallbackChange,
            onTitleZhCnChange = onTitleZhCnChange,
            onDescriptionFallbackChange = onDescriptionFallbackChange,
            onDescriptionZhCnChange = onDescriptionZhCnChange,
            onAuthorChange = onAuthorChange,
            onDesignerChange = onDesignerChange,
            onCategoryChange = onCategoryChange,
            onResSubTypeChange = onResSubTypeChange,
            onEditableChange = onEditableChange,
            onThirdPartiesChange = onThirdPartiesChange,
            onSupportAonChange = onSupportAonChange,
        )
        DialogButtons(
            confirmEnabled = packageLabel.isNotBlank(),
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
    }
}

@Composable
private fun WallpaperMetadataForm(
    titleFallback: String,
    titleZhCn: String,
    descriptionFallback: String,
    descriptionZhCn: String,
    author: String,
    designer: String,
    category: String,
    resSubType: String,
    editable: Boolean,
    thirdParties: Boolean,
    supportAon: Boolean,
    previewLabel: String,
    onTitleFallbackChange: (String) -> Unit,
    onTitleZhCnChange: (String) -> Unit,
    onDescriptionFallbackChange: (String) -> Unit,
    onDescriptionZhCnChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onDesignerChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onResSubTypeChange: (String) -> Unit,
    onEditableChange: (Boolean) -> Unit,
    onThirdPartiesChange: (Boolean) -> Unit,
    onSupportAonChange: (Boolean) -> Unit,
    onPickPreview: () -> Unit,
    onClearPreview: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    DialogFormColumn(maxHeight = 620.dp) {
        FilePickRow(
            title = stringResource(R.string.rear_wallpaper_preview_file),
            label = previewLabel.ifBlank { stringResource(R.string.rear_wallpaper_preview_keep_current) },
            onPick = onPickPreview,
            onClear = onClearPreview.takeIf { previewLabel.isNotBlank() },
        )
        WallpaperMetadataFields(
            titleFallback = titleFallback,
            titleZhCn = titleZhCn,
            descriptionFallback = descriptionFallback,
            descriptionZhCn = descriptionZhCn,
            author = author,
            designer = designer,
            category = category,
            resSubType = resSubType,
            editable = editable,
            thirdParties = thirdParties,
            supportAon = supportAon,
            onTitleFallbackChange = onTitleFallbackChange,
            onTitleZhCnChange = onTitleZhCnChange,
            onDescriptionFallbackChange = onDescriptionFallbackChange,
            onDescriptionZhCnChange = onDescriptionZhCnChange,
            onAuthorChange = onAuthorChange,
            onDesignerChange = onDesignerChange,
            onCategoryChange = onCategoryChange,
            onResSubTypeChange = onResSubTypeChange,
            onEditableChange = onEditableChange,
            onThirdPartiesChange = onThirdPartiesChange,
            onSupportAonChange = onSupportAonChange,
        )
        DialogButtons(
            confirmEnabled = titleFallback.isNotBlank() || titleZhCn.isNotBlank(),
            onConfirm = onConfirm,
            onCancel = onCancel,
        )
    }
}

@Composable
internal fun WallpaperMetadataFields(
    titleFallback: String,
    titleZhCn: String,
    descriptionFallback: String,
    descriptionZhCn: String,
    author: String,
    designer: String,
    category: String,
    resSubType: String,
    editable: Boolean,
    thirdParties: Boolean,
    supportAon: Boolean,
    onTitleFallbackChange: (String) -> Unit,
    onTitleZhCnChange: (String) -> Unit,
    onDescriptionFallbackChange: (String) -> Unit,
    onDescriptionZhCnChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onDesignerChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onResSubTypeChange: (String) -> Unit,
    onEditableChange: (Boolean) -> Unit,
    onThirdPartiesChange: (Boolean) -> Unit,
    onSupportAonChange: (Boolean) -> Unit,
) {
    TextField(
        value = titleFallback,
        onValueChange = onTitleFallbackChange,
        label = stringResource(R.string.rear_wallpaper_meta_title_fallback),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    TextField(
        value = titleZhCn,
        onValueChange = onTitleZhCnChange,
        label = stringResource(R.string.rear_wallpaper_meta_title_zh),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    TextField(
        value = descriptionFallback,
        onValueChange = onDescriptionFallbackChange,
        label = stringResource(R.string.rear_wallpaper_meta_desc_fallback),
        modifier = Modifier.fillMaxWidth(),
    )
    TextField(
        value = descriptionZhCn,
        onValueChange = onDescriptionZhCnChange,
        label = stringResource(R.string.rear_wallpaper_meta_desc_zh),
        modifier = Modifier.fillMaxWidth(),
    )
    TextField(
        value = author,
        onValueChange = onAuthorChange,
        label = stringResource(R.string.rear_wallpaper_meta_author),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    TextField(
        value = designer,
        onValueChange = onDesignerChange,
        label = stringResource(R.string.rear_wallpaper_meta_designer),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    TextField(
        value = category,
        onValueChange = onCategoryChange,
        label = stringResource(R.string.rear_wallpaper_meta_category),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    TextField(
        value = resSubType,
        onValueChange = onResSubTypeChange,
        label = stringResource(R.string.rear_wallpaper_meta_res_sub_type),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    MetadataSwitchRow(
        title = stringResource(R.string.rear_wallpaper_meta_editable),
        checked = editable,
        onCheckedChange = onEditableChange,
    )
    MetadataSwitchRow(
        title = stringResource(R.string.rear_wallpaper_meta_third_parties),
        checked = thirdParties,
        onCheckedChange = onThirdPartiesChange,
    )
    MetadataSwitchRow(
        title = stringResource(R.string.rear_wallpaper_meta_support_aon),
        checked = supportAon,
        onCheckedChange = onSupportAonChange,
    )
}

@Composable
private fun FilePickRow(
    title: String,
    label: String,
    onPick: () -> Unit,
    onClear: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = title,
            summary = label,
            summaryColor = BasicComponentDefaults.summaryColor(),
            onClick = onPick,
            bottomAction = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onPick,
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.rear_wallpaper_select_file))
                    }
                    if (onClear != null) {
                        Button(
                            onClick = onClear,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.rear_wallpaper_clear_file))
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun MetadataSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        BasicComponent(
            title = title,
            onClick = { onCheckedChange(!checked) },
            endActions = {
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            },
        )
    }
}

@Composable
private fun DialogButtons(
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Button(
        onClick = onConfirm,
        enabled = confirmEnabled,
        colors = ButtonDefaults.buttonColorsPrimary(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.rear_widget_confirm))
    }
    Button(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.rear_widget_cancel))
    }
}

@Composable
private fun ManagedWallpaperPreview(
    cachePath: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberRearWallpaperPreviewBitmap(cachePath)
    val iconTint = Color.White.copy(alpha = 0.82f)

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.Black)
            .graphicsLayer { clip = true },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Image,
                    contentDescription = null,
                    tint = iconTint,
                )
            }
        }
    }
}

private fun Uri.displayLabel(): String {
    return lastPathSegment
        ?.substringAfterLast('/')
        ?.substringAfterLast(':')
        ?.takeIf { it.isNotBlank() }
        ?: toString()
}
