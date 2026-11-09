package hk.uwu.reareye.ui.components.script

import hk.uwu.mixui.icons.Icons as UiIcons

import android.annotation.SuppressLint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.script.ScriptProject
import hk.uwu.reareye.script.ScriptProjectKind
import hk.uwu.reareye.script.ScriptProjectStore
import hk.uwu.mixui.components.BadgeItem
import hk.uwu.reareye.ui.components.card.ModuleStyleDeleteAction
import hk.uwu.reareye.ui.components.card.ModuleStyleIconAction
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.mixui.components.ListItem
import hk.uwu.mixui.components.rememberAccentBadgePalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import hk.uwu.mixui.components.BottomSheetAction
import hk.uwu.mixui.components.BottomSheetActionButton
import hk.uwu.mixui.components.BottomSheetActionIcon
import hk.uwu.mixui.components.GlassBottomSheet
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.ButtonDefaults
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.IconButton
import hk.uwu.mixui.components.LargeTitleLazyScaffold
import hk.uwu.mixui.components.TopBarIconButton
import hk.uwu.mixui.components.Scaffold
import hk.uwu.mixui.components.CompactTopBar
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.components.FormDialog
import hk.uwu.mixui.components.TextField
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

private sealed interface ScriptRoute {
    data object List : ScriptRoute
    data class Editor(val project: ScriptProject) : ScriptRoute
}

private sealed interface ScriptImportRequest {
    data class Project(val uri: Uri, val suggestedName: String) : ScriptImportRequest
}

private class ScriptOverwriteRequest(
    val title: String,
    val message: String,
    val confirm: () -> Unit,
)

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun ScriptManagementScreen(onBack: () -> Unit, bottomInnerPadding: Dp = 0.dp) {
    val context = LocalContext.current
    val store = remember { ScriptProjectStore(context) }
    var projects by remember { mutableStateOf(emptyList<ScriptProject>()) }
    var route by remember { mutableStateOf<ScriptRoute>(ScriptRoute.List) }
    var renameProject by remember { mutableStateOf<ScriptProject?>(null) }
    var importRequest by remember { mutableStateOf<ScriptImportRequest?>(null) }
    var overwriteRequest by remember { mutableStateOf<ScriptOverwriteRequest?>(null) }
    var pendingExport by remember { mutableStateOf<ScriptProject?>(null) }
    var pendingExportIsProject by remember { mutableStateOf(false) }
    fun refresh() {
        projects = store.list()
    }

    val importer =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                val name = store.suggestedLuaName(uri)
                val isZip = store.isZip(uri)
                if (isZip) {
                    importRequest =
                        ScriptImportRequest.Project(uri, store.suggestedProjectName(uri))
                } else {
                    if (store.hasStandalone(name)) {
                        overwriteRequest = ScriptOverwriteRequest(
                            title = context.getString(R.string.script_overwrite_title),
                            message = context.getString(R.string.script_overwrite_file, name),
                            confirm = {
                                store.importLua(uri, name, overwrite = true)
                                refresh()
                            },
                        )
                    } else {
                        store.importLua(uri, name, overwrite = false)
                        refresh()
                    }
                }
            }
        }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val project = pendingExport
        if (uri != null && project != null) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                if (pendingExportIsProject) store.exportProject(project, output)
                else store.exportLua(project, output)
            }
        }
        pendingExport = null
    }
    LaunchedEffect(Unit) { refresh() }
    BackHandler(enabled = route !is ScriptRoute.List) { route = ScriptRoute.List }
    when (val current = route) {
        ScriptRoute.List -> ScriptList(
            projects = projects,
            bottomInnerPadding = bottomInnerPadding,
            onBack = onBack,
            onRefresh = ::refresh,
            onImport = { importer.launch(arrayOf("*/*")) },
            onExport = { project ->
                pendingExport = project
                pendingExportIsProject = project.kind != ScriptProjectKind.STANDALONE
                exportLauncher.launch(if (pendingExportIsProject) "${project.name}.zip" else "${project.name}.lua")
            },
            onOpen = { route = ScriptRoute.Editor(it) },
            onRename = { renameProject = it },
            onDelete = { store.delete(it); refresh() },
            onNew = {
                val project =
                    store.createStandalone("script_${System.currentTimeMillis()}", DEFAULT_SCRIPT)
                refresh()
                route = ScriptRoute.Editor(project)
            },
            onNewProject = {
                val project =
                    store.createProject("project_${System.currentTimeMillis()}", DEFAULT_PROJECT)
                refresh()
                route = ScriptRoute.Editor(project)
            },
        )

        is ScriptRoute.Editor -> ScriptEditor(
            project = current.project,
            store = store,
            bottomInnerPadding = bottomInnerPadding,
            onBack = { route = ScriptRoute.List },
            onSaved = { refresh() },
        )
    }
    ScriptRenameDialog(
        show = renameProject != null,
        title = stringResource(R.string.script_rename_project),
        initialName = renameProject?.name.orEmpty(),
        onDismiss = { renameProject = null },
        onConfirm = { name ->
            renameProject?.let { store.renameProject(it, name) }
            renameProject = null
            refresh()
        },
    )
    val projectImport = importRequest as? ScriptImportRequest.Project
    ScriptRenameDialog(
        show = projectImport != null,
        title = stringResource(R.string.script_import_project),
        initialName = projectImport?.suggestedName.orEmpty(),
        onDismiss = { importRequest = null },
        onConfirm = { name ->
            projectImport?.let { request ->
                importRequest = null
                if (store.hasProject(name)) {
                    overwriteRequest = ScriptOverwriteRequest(
                        title = context.getString(R.string.script_overwrite_title),
                        message = context.getString(R.string.script_overwrite_project, name),
                        confirm = {
                            store.importProject(request.uri, name, overwrite = true)
                            refresh()
                        },
                    )
                } else {
                    store.importProject(request.uri, name, overwrite = false)
                    refresh()
                }
            }
        },
    )
    ScriptOverwriteDialog(
        show = overwriteRequest != null,
        sessionKey = overwriteRequest,
        title = overwriteRequest?.title.orEmpty(),
        message = overwriteRequest?.message.orEmpty(),
        onDismiss = { overwriteRequest = null },
        onConfirm = {
            overwriteRequest?.confirm?.invoke()
            overwriteRequest = null
        },
    )
}

@Composable
internal fun ScriptList(
    projects: List<ScriptProject>,
    bottomInnerPadding: Dp,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onImport: () -> Unit,
    onExport: (ScriptProject) -> Unit,
    onOpen: (ScriptProject) -> Unit,
    onRename: (ScriptProject) -> Unit,
    onDelete: (ScriptProject) -> Unit,
    onNew: () -> Unit,
    onNewProject: () -> Unit,
) {
    LargeTitleLazyScaffold(
        title = stringResource(R.string.script_management_title),
        modifier = Modifier.testTag("script-project-list"),
        bottomObstruction = bottomInnerPadding,
        navigationIcon = {
            TopBarIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.scene_route_navigate_back))
            }
        },
        actions = {
            TopBarIconButton(onClick = onNew) {
                Icon(Icons.Outlined.NoteAdd, stringResource(R.string.script_new))
            }
            TopBarIconButton(onClick = onNewProject) {
                Icon(Icons.Outlined.CreateNewFolder, stringResource(R.string.script_new_project))
            }
            TopBarIconButton(onClick = onImport) {
                Icon(Icons.Outlined.FileUpload, stringResource(R.string.script_import))
            }
            TopBarIconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.script_refresh))
            }
        },
    ) {
        if (projects.isEmpty()) item(key = "empty") {
            Card(Modifier.fillMaxWidth()) { ListItem(animateContentSize = true, title = stringResource(R.string.script_empty)) }
        }
        // Standalone scripts share a root; their entry file is their unique identity.
        items(items = projects, key = { it.mainFile.absolutePath }) { project ->
            val kindLabel = stringResource(
                when (project.kind) {
                    ScriptProjectKind.STANDALONE -> R.string.script_kind_standalone
                    ScriptProjectKind.PROJECT -> R.string.script_kind_project
                    ScriptProjectKind.COMPONENT -> R.string.script_kind_component
                }
            )
            ModuleStyleManagerCard(
                modifier = Modifier.fillMaxWidth(),
                bottomPadding = 0.dp,
                title = project.name,
                summaryLines = buildList {
                    add(project.id)
                    if (project.description.isNotBlank()) add(project.description)
                    project.error?.let { add(it) }
                },
                badges = listOf(
                    BadgeItem(
                        kindLabel, palette = rememberAccentBadgePalette(
                            when (project.kind) {
                                ScriptProjectKind.PROJECT -> Color(0xFF4A78D0)
                                ScriptProjectKind.STANDALONE -> Color(0xFF279879)
                                ScriptProjectKind.COMPONENT -> Color(0xFF9470C8)
                            },
                        )
                    )
                ) +
                        listOfNotNull(project.componentId?.let { BadgeItem(it) }),
                onCardClick = { onOpen(project) },
                leftAction = {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        ModuleStyleIconAction(
                            icon = Icons.Outlined.EditNote,
                            contentDescription = stringResource(R.string.script_edit),
                            onClick = { onOpen(project) },
                        )
                        if (project.kind != ScriptProjectKind.COMPONENT) {
                            ModuleStyleIconAction(
                                icon = Icons.Outlined.DriveFileRenameOutline,
                                contentDescription = stringResource(R.string.script_rename),
                                onClick = { onRename(project) },
                            )
                        }
                        ModuleStyleIconAction(
                            icon = Icons.Outlined.FileDownload,
                            contentDescription = stringResource(R.string.script_export),
                            onClick = { onExport(project) },
                        )
                    }
                },
                rightAction = {
                    if (project.kind != ScriptProjectKind.COMPONENT) {
                        ModuleStyleDeleteAction(
                            icon = UiIcons.Delete,
                            text = stringResource(R.string.script_delete),
                            onClick = { onDelete(project) },
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun ScriptEditor(
    project: ScriptProject,
    store: ScriptProjectStore,
    bottomInnerPadding: Dp,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val executor = remember(context) { hk.uwu.reareye.script.ScriptExecutor(context) }
    ScriptEditorContent(
        project, store, bottomInnerPadding, onBack, onSaved,
        loadProviders = { executor.providers(project) },
        runProvider = { provider, params ->
            val result = executor.execute(project, provider, params)
            result.error ?: result.data?.toString().orEmpty()
        },
    )
}

/** The production editor UI and file operations, independent of the native Lua runtime. */
@Composable
internal fun ScriptEditorContent(
    project: ScriptProject,
    store: ScriptProjectStore,
    bottomInnerPadding: Dp,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    loadProviders: suspend () -> List<hk.uwu.reareye.script.ScriptProvider>,
    runProvider: suspend (String, Map<String, String>) -> String,
) {
    var files by remember(project.root.absolutePath) { mutableStateOf(store.projectEntries(project)) }
    var selectedFile by remember(project.root.absolutePath) { mutableStateOf(project.mainFile) }
    var source by remember(selectedFile.absolutePath) { mutableStateOf(store.readFile(selectedFile)) }
    var renameFileTarget by remember(project.root.absolutePath) { mutableStateOf<java.io.File?>(null) }
    var showFiles by remember(project.root.absolutePath) { mutableStateOf(false) }
    var providerId by remember(project.id) { mutableStateOf("") }
    var providers by remember(project.id) { mutableStateOf(emptyList<hk.uwu.reareye.script.ScriptProvider>()) }
    var paramsText by remember(project.id) { mutableStateOf("") }
    var runResult by remember(project.id) { mutableStateOf("") }
    var showDebug by remember(project.id) { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(project.id) {
        try {
            providers = loadProviders()
            providerId = providers.firstOrNull()?.id.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runResult = e.message.orEmpty()
        }
    }
    val editor = rememberLuaCodeEditorState()
    fun saveCurrentFile() {
        source = editor.text()
        store.saveFile(selectedFile, source)
    }

    fun selectFile(file: java.io.File) {
        if (file.absolutePath == selectedFile.absolutePath) {
            showFiles = false
            return
        }
        saveCurrentFile()
        selectedFile = file
        source = store.readFile(file)
        showFiles = false
    }
    Scaffold(
        topBar = {
            CompactTopBar(
                title = selectedFile.name,
                navigationIcon = {
                    Row {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                stringResource(R.string.scene_route_navigate_back)
                            )
                        }
                        if (project.kind != ScriptProjectKind.STANDALONE) {
                            IconButton(onClick = { showFiles = !showFiles }) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    stringResource(R.string.script_file_structure)
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        saveCurrentFile()
                        editor.editor.value?.clearFocus()
                        showDebug = true
                    }) { Icon(Icons.Outlined.BugReport, stringResource(R.string.script_debug)) }
                    IconButton(onClick = { saveCurrentFile(); onSaved(); onBack() }) {
                        Icon(Icons.Outlined.Check, stringResource(R.string.script_save))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    start = padding.calculateStartPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
                    end = padding.calculateEndPadding(androidx.compose.ui.platform.LocalLayoutDirection.current),
                    bottom = maxOf(padding.calculateBottomPadding(), bottomInnerPadding)
                )
        ) {
            Column(Modifier.fillMaxSize()) {
                LuaCodeEditor(
                    source, editor, Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
            if (showFiles && project.kind != ScriptProjectKind.STANDALONE) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(1f / 3f)
                            .background(MixTheme.colorScheme.surfaceContainerHigh),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            files.forEach { file ->
                                val relative = file.relativeTo(project.root).invariantSeparatorsPath
                                val depth = relative.count { it == '/' }
                                val isLua =
                                    file.isFile && file.extension.equals("lua", ignoreCase = true)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            enabled = isLua,
                                            onClick = { selectFile(file) },
                                            onLongClick = {
                                                if (file.name != "main.lua") {
                                                    saveCurrentFile()
                                                    renameFileTarget = file
                                                }
                                            },
                                        )
                                        .padding(
                                            start = (depth * 6).dp,
                                            top = 10.dp,
                                            bottom = 10.dp
                                        ),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        modifier = Modifier.size(18.dp),
                                        imageVector = when {
                                            file.isDirectory -> Icons.Outlined.Folder
                                            isLua -> Icons.Outlined.Code
                                            else -> Icons.Outlined.InsertDriveFile
                                        },
                                        contentDescription = null,
                                        tint = when {
                                            file.isDirectory -> MixTheme.colorScheme.onSurfaceVariantSummary
                                            isLua -> MixTheme.colorScheme.primary
                                            else -> MixTheme.colorScheme.onSurfaceVariantSummary
                                        },
                                    )
                                    Text(
                                        text = file.name,
                                        modifier = Modifier.weight(1f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (file.absolutePath == selectedFile.absolutePath) {
                                            MixTheme.colorScheme.primary
                                        } else {
                                            MixTheme.colorScheme.onSurface
                                        },
                                    )
                                }
                            }
                        }
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { showFiles = false })
                }
            }
        }
    }
    ScriptRenameDialog(
        show = renameFileTarget != null,
        title = stringResource(R.string.script_rename_file),
        initialName = renameFileTarget?.nameWithoutExtension.orEmpty(),
        onDismiss = { renameFileTarget = null },
        onConfirm = { name ->
            val oldFile = renameFileTarget
            if (oldFile != null) {
                val renamed = store.renameFile(project, oldFile, name)
                if (renamed != null) {
                    if (selectedFile.absolutePath == oldFile.absolutePath) {
                        selectedFile = renamed
                        source = store.readFile(renamed)
                    }
                    files = store.projectEntries(project)
                }
            }
            renameFileTarget = null
        },
    )
    ScriptDebugBottomSheet(
        show = showDebug,
        providerId = providerId,
        onProviderIdChange = { providerId = it },
        paramsText = paramsText,
        onParamsTextChange = { paramsText = it },
        result = runResult,
        onRun = {
            val selectedProvider = providerId
            scope.launch {
                val params = paramsText.split('&').filter { it.contains('=') }
                    .associate { it.substringBefore('=') to it.substringAfter('=') }
                runResult = if (selectedProvider.isBlank()) "No provider" else {
                    runProvider(selectedProvider, params)
                }
            }
        },
        onDismiss = { showDebug = false },
    )
}

@Composable
internal fun ScriptRenameDialog(
    show: Boolean,
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(show, initialName) { mutableStateOf(initialName) }
    // The host retains its foreground during exit. Ignore repeated Save actions
    // in that interval, and reset the guard for the next naming session.
    var submitted by remember(show, initialName) { mutableStateOf(false) }
    GlassBottomSheet(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        startAction = {
            BottomSheetActionButton(onClick = onDismiss, modifier = Modifier.testTag("script-name-cancel")) {
                BottomSheetActionIcon(BottomSheetAction.Close, Icons.Outlined.Close, stringResource(R.string.script_cancel))
            }
        },
        endAction = {
            BottomSheetActionButton(
                onClick = {
                    if (show && !submitted && value.isNotBlank()) {
                        submitted = true
                        onConfirm(value)
                    }
                },
                enabled = show && !submitted && value.isNotBlank(),
                modifier = Modifier.testTag("script-name-save"),
            ) {
                BottomSheetActionIcon(BottomSheetAction.Save, Icons.Outlined.Check, stringResource(R.string.script_save))
            }
        },
    ) {
        TextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth().testTag("script-name-input"),
            label = stringResource(R.string.script_name),
            singleLine = true,
        )
    }
}

/** Native in-window confirmation; kept composed while the backend animates dismissal. */
@Composable
internal fun ScriptOverwriteDialog(
    show: Boolean,
    title: String,
    message: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    sessionKey: Any? = Unit,
) {
    var handled by remember(show, sessionKey) { mutableStateOf(false) }
    val currentSession = remember {
        mutableStateOf(sessionKey, androidx.compose.runtime.referentialEqualityPolicy())
    }
    currentSession.value = sessionKey
    val currentShow by androidx.compose.runtime.rememberUpdatedState(show)
    // The request is cleared immediately on dismissal, but its text must survive exit.
    var lastTitle by remember { mutableStateOf(title) }
    var lastMessage by remember { mutableStateOf(message) }
    androidx.compose.runtime.SideEffect {
        if (show) {
            lastTitle = title
            lastMessage = message
        }
    }
    val dismiss = {
        if (show && currentShow && currentSession.value === sessionKey && !handled) {
            handled = true
            onDismiss()
        }
    }
    FormDialog(
        show = show,
        title = if (show) title else lastTitle,
        onDismissRequest = dismiss,
    ) {
        // Do not reuse native clickable nodes across request generations: their
        // retained semantics actions otherwise forward to the newest callback.
        androidx.compose.runtime.key(sessionKey) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    if (show) message else lastMessage,
                    color = MixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.testTag("script-overwrite-message"),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        modifier = Modifier.weight(1f).testTag("script-overwrite-cancel"),
                        enabled = show && !handled,
                        onClick = dismiss,
                    ) {
                        Text(stringResource(R.string.script_cancel))
                    }
                    Button(
                        onClick = {
                            if (show && currentShow && currentSession.value === sessionKey && !handled) {
                                handled = true
                                onConfirm()
                            }
                        },
                        enabled = show && !handled,
                        modifier = Modifier.weight(1f).testTag("script-overwrite-confirm"),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    ) {
                        Text(stringResource(R.string.script_overwrite))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptDebugBottomSheet(
    show: Boolean,
    providerId: String,
    onProviderIdChange: (String) -> Unit,
    paramsText: String,
    onParamsTextChange: (String) -> Unit,
    result: String,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    WindowBottomSheet(
        show = show,
        title = stringResource(R.string.script_debug_title),
        onDismissRequest = onDismiss,
        endAction = {
            IconButton(onClick = onRun) {
                Icon(Icons.Outlined.PlayArrow, stringResource(R.string.script_debug_run))
            }
        },
        defaultWindowInsetsPadding = true,
        enableNestedScroll = false,
        insideMargin = DpSize(24.dp, 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextField(
                value = providerId,
                onValueChange = onProviderIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.script_debug_provider),
                singleLine = true,
            )
            TextField(
                value = paramsText,
                onValueChange = onParamsTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.script_debug_params),
                singleLine = true,
            )
            if (result.isNotBlank()) {
                Text(
                    result,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    color = MixTheme.colorScheme.onSurfaceVariantSummary
                )
            }
        }
    }
}

private val DEFAULT_SCRIPT = """
local function hello(params, config, context)
    return "Hello " .. tostring(params.name or "REAREye")
end

return {
    id = "new_script",
    name = "New Script",
    providers = { hello = hello }
}
""".trimIndent()

private val DEFAULT_PROJECT = """
local function hello(params, config, context)
    return "Hello from project: " .. tostring(params.name or "REAREye")
end

return {
    id = "project_script",
    name = "Project Script",
    description = "A project script can require Lua files next to main.lua.",
    providers = { hello = hello }
}
""".trimIndent()
