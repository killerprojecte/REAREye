package hk.uwu.reareye.ui.components.script

import android.annotation.SuppressLint
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
import hk.uwu.reareye.ui.components.RearBadgeItem
import hk.uwu.reareye.ui.components.card.ModuleStyleDeleteAction
import hk.uwu.reareye.ui.components.card.ModuleStyleIconAction
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.reareye.ui.components.card.SuperCard
import hk.uwu.reareye.ui.components.rememberRearAccentBadgePalette
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowBottomSheet
import top.yukonga.miuix.kmp.window.WindowDialog

private sealed interface ScriptRoute {
    data object List : ScriptRoute
    data class Editor(val project: ScriptProject) : ScriptRoute
}

private sealed interface ScriptImportRequest {
    data class Project(val uri: Uri, val suggestedName: String) : ScriptImportRequest
}

private data class ScriptOverwriteRequest(
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
        request = overwriteRequest,
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
    val haze = rememberAcrylicHazeState()
    val style = rememberAcrylicHazeStyle()
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.rearAcrylicEffect(haze, style),
                color = Color.Transparent,
                title = stringResource(R.string.script_management_title),
                navigationIconPadding = 12.dp,
                actionIconPadding = 12.dp,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            null
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNew) {
                        Icon(
                            Icons.Outlined.NoteAdd,
                            stringResource(R.string.script_new)
                        )
                    }
                    IconButton(onClick = onNewProject) {
                        Icon(
                            Icons.Outlined.CreateNewFolder,
                            stringResource(R.string.script_new_project)
                        )
                    }
                    IconButton(onClick = onImport) {
                        Icon(
                            Icons.Outlined.FileUpload,
                            stringResource(R.string.script_import)
                        )
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Outlined.Refresh,
                            stringResource(R.string.script_refresh)
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic()
                .overScrollVertical()
                .rearAcrylicSource(haze)
                .padding(horizontal = 12.dp)
                .testTag("script-project-list"),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + 12.dp,
                bottom = padding.calculateBottomPadding() + bottomInnerPadding + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            overscrollEffect = null,
        ) {
            if (projects.isEmpty()) item(key = "empty") {
                Card(Modifier.fillMaxWidth()) { SuperCard(title = stringResource(R.string.script_empty)) }
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
                        RearBadgeItem(
                            kindLabel, palette = rememberRearAccentBadgePalette(
                                when (project.kind) {
                                    ScriptProjectKind.PROJECT -> Color(0xFF4A78D0)
                                    ScriptProjectKind.STANDALONE -> Color(0xFF279879)
                                    ScriptProjectKind.COMPONENT -> Color(0xFF9470C8)
                                },
                            )
                        )
                    ) +
                            listOfNotNull(project.componentId?.let { RearBadgeItem(it) }),
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
                                icon = MiuixIcons.Delete,
                                text = stringResource(R.string.script_delete),
                                onClick = { onDelete(project) },
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ScriptEditor(
    project: ScriptProject,
    store: ScriptProjectStore,
    bottomInnerPadding: Dp,
    onBack: () -> Unit,
    onSaved: () -> Unit
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
    val scriptContext = LocalContext.current
    val executor = remember(scriptContext) { hk.uwu.reareye.script.ScriptExecutor(scriptContext) }
    LaunchedEffect(project.id) {
        try {
            providers = executor.providers(project)
            providerId = providers.firstOrNull()?.id.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runResult = e.message.orEmpty()
        }
    }
    val editor = rememberLuaCodeEditorState()
    val haze = rememberAcrylicHazeState()
    val style = rememberAcrylicHazeStyle()
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
            SmallTopAppBar(
                modifier = Modifier.rearAcrylicEffect(haze, style),
                color = Color.Transparent,
                title = selectedFile.name,
                navigationIconPadding = 12.dp,
                actionIconPadding = 12.dp,
                navigationIcon = {
                    Row {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                null
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
                .rearAcrylicSource(haze)
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + bottomInnerPadding
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
                            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
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
                                            file.isDirectory -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                            isLua -> MiuixTheme.colorScheme.primary
                                            else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                                        },
                                    )
                                    Text(
                                        text = file.name,
                                        modifier = Modifier.weight(1f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = if (file.absolutePath == selectedFile.absolutePath) {
                                            MiuixTheme.colorScheme.primary
                                        } else {
                                            MiuixTheme.colorScheme.onSurface
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
                    val result = executor.execute(project, selectedProvider, params)
                    result.error ?: result.data?.toString().orEmpty()
                }
            }
        },
        onDismiss = { showDebug = false },
    )
}

@Composable
private fun ScriptRenameDialog(
    show: Boolean,
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(show, initialName) { mutableStateOf(initialName) }
    if (!show) return
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TextField(
                value = value,
                onValueChange = { value = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.script_name),
                singleLine = true,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { if (value.isNotBlank()) onConfirm(value) }) {
                    Icon(Icons.Outlined.Check, stringResource(R.string.script_save))
                }
            }
        }
    }
}

@Composable
private fun ScriptOverwriteDialog(
    request: ScriptOverwriteRequest?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (request == null) return
    WindowDialog(show = true, title = request.title, onDismissRequest = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(request.message, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    text = stringResource(R.string.script_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss,
                )
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColorsPrimary(),
                ) {
                    Text(stringResource(R.string.script_overwrite))
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
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary
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
