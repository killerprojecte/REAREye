package hk.uwu.reareye.ui.components.config

import android.annotation.SuppressLint
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.rounded.EditNote
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.repository.rearwidget.RearWidgetSceneRouteConfig
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.ui.components.OverlayDialog
import hk.uwu.reareye.ui.components.RearBadgeGroup
import hk.uwu.reareye.ui.components.card.ModuleStyleDeleteAction
import hk.uwu.reareye.ui.components.card.ModuleStyleIconAction
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.reareye.ui.components.card.SuperCard
import hk.uwu.mixui.motion.ArtRevealItem
import hk.uwu.reareye.ui.config.ConfigKeys
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
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val REAR_WIDGET_DEBUG_TAG = "RearWidgetDebug"

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun SceneRouteManagerScreen(
    prefsManager: PrefsManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val scope = rememberCoroutineScope()
    val routes = remember { mutableStateListOf<RearWidgetSceneRouteConfig>() }
    var loaded by remember { mutableStateOf(false) }
    var dataCardsVisible by remember { mutableStateOf(false) }
    val remotePrefsStatusRevision = rememberRemotePrefsStatusRevision()
    var showDialog by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draftPackageName by remember { mutableStateOf("") }
    var draftScene by remember { mutableStateOf("") }
    var draftBusiness by remember { mutableStateOf("") }

    fun debugLog(message: String) {
        if (prefsManager.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            Log.d(REAR_WIDGET_DEBUG_TAG, message)
        }
    }

    fun replaceRoutes(nextRoutes: List<RearWidgetSceneRouteConfig>) {
        routes.clear()
        routes.addAll(
            nextRoutes.sortedWith(
                compareBy(
                    { it.packageName.lowercase() },
                    { it.scene.lowercase() },
                    { it.business.lowercase() },
                )
            )
        )
    }

    LaunchedEffect(prefsManager, remotePrefsStatusRevision) {
        val remoteReady = withContext(Dispatchers.IO) { prefsManager.isRemoteReady() }
        if (!remoteReady) {
            if (routes.isEmpty()) {
                loaded = false
                dataCardsVisible = false
            }
            return@LaunchedEffect
        }

        delay(220)
        val loadedRoutes = withContext(Dispatchers.IO) {
            RearWidgetManagerRepository.loadSceneRoutes(prefsManager)
        }
        replaceRoutes(loadedRoutes)
        loaded = true
        delay(90)
        dataCardsVisible = true
        withContext(Dispatchers.IO) {
            RearWidgetManagerRepository.refreshRuntimeFromPrefs(context, prefsManager)
        }
    }

    fun persist() {
        val nextRoutes = routes.toList()
        scope.launch(Dispatchers.IO) {
            RearWidgetManagerRepository.saveSceneRoutes(context, prefsManager, nextRoutes)
        }
    }

    fun openCreateDialog() {
        editingId = null
        draftPackageName = ""
        draftScene = ""
        draftBusiness = ""
        showDialog = true
    }

    fun openEditDialog(item: RearWidgetSceneRouteConfig) {
        editingId = item.id
        draftPackageName = item.packageName
        draftScene = item.scene
        draftBusiness = item.business
        showDialog = true
    }

    fun submitDialog() {
        val packageName = draftPackageName.trim()
        val scene = draftScene.trim()
        val business = draftBusiness.trim()
        if (packageName.isBlank() || scene.isBlank() || business.isBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_form_invalid),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val config = RearWidgetSceneRouteConfig(
            id = RearWidgetConfigCodec.newSceneRouteId(packageName, scene),
            packageName = packageName,
            scene = scene,
            business = business,
        )

        editingId?.let { currentId ->
            val oldIndex = routes.indexOfFirst { it.id == currentId }
            if (oldIndex >= 0) routes.removeAt(oldIndex)
        }

        val existingIndex = routes.indexOfFirst { it.id == config.id }
        if (existingIndex >= 0) {
            routes[existingIndex] = config
        } else {
            routes.add(config)
        }

        replaceRoutes(routes.toList())
        persist()
        showDialog = false
        Toast.makeText(
            context,
            context.getString(R.string.rear_widget_scene_route_saved),
            Toast.LENGTH_SHORT,
        ).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                color = Color.Transparent,
                title = stringResource(R.string.rear_widget_scene_route_manager),
                navigationIconPadding = 12.dp,
                actionIconPadding = 12.dp,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            modifier = Modifier.graphicsLayer {
                                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
                            },
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { if (loaded) openCreateDialog() }) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .scrollEndHaptic()
                .overScrollVertical()
                .rearAcrylicSource(hazeState)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding() + 12.dp,
                bottom = paddingValues.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            overscrollEffect = null,
        ) {
            item {
                Card(
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .fillMaxWidth(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SuperCard(
                            title = stringResource(R.string.rear_widget_scene_route_hint_title),
                            summary = stringResource(R.string.rear_widget_scene_route_hint),
                            onClick = {},
                            bottomAction = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (loaded) {
                                        RearBadgeGroup(
                                            badges = listOf(rearWidgetSceneRouteCountBadge(routes.size)),
                                        )
                                    }
                                    Button(
                                        onClick = { if (loaded) openCreateDialog() },
                                        enabled = loaded,
                                        colors = ButtonDefaults.buttonColorsPrimary(),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Add,
                                            contentDescription = null,
                                            modifier = Modifier.padding(end = 6.dp),
                                        )
                                        Text(text = stringResource(R.string.rear_widget_add_scene_route))
                                    }
                                }
                            },
                        )
                    }
                }
            }

            if (!dataCardsVisible) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        insideMargin = PaddingValues(vertical = 24.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                InfiniteProgressIndicator()
                                Text(text = stringResource(R.string.rear_widget_loading_data))
                            }
                        }
                    }
                }
            }

            if (dataCardsVisible) {
                itemsIndexed(
                    items = routes,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> "scene_route_item" },
                ) { _, item ->
                    ModuleStyleManagerCard(
                        title = item.packageName,
                        summaryLines = emptyList(),
                        badges = buildList {
                            add(rearWidgetRuleBadge(item.scene))
                            add(rearWidgetBusinessBadge(item.business))
                            addAll(
                                rearWidgetSourceBadges(
                                    downloadedFromStore = item.downloadedFromStore,
                                    storeWidgetId = item.storeWidgetId,
                                )
                            )
                        },
                        onCardClick = { openEditDialog(item) },
                        leftAction = {
                            ModuleStyleIconAction(
                                icon = Icons.Rounded.EditNote,
                                onClick = { openEditDialog(item) },
                            )
                        },
                        rightAction = {
                            ModuleStyleDeleteAction(
                                icon = MiuixIcons.Delete,
                                text = stringResource(R.string.rear_widget_action_delete),
                                onClick = {
                                    routes.remove(item)
                                    replaceRoutes(routes.toList())
                                    persist()
                                },
                            )
                        },
                    )
                }
            }

            item {
                if (dataCardsVisible && routes.isEmpty()) {
                    ArtRevealItem(visible = true, delayMillis = 40) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.rear_widget_empty_scene_route),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    OverlayDialog(
        show = showDialog,
        title = stringResource(
            if (editingId == null) {
                R.string.rear_widget_add_scene_route
            } else {
                R.string.rear_widget_edit_scene_route
            }
        ),
        onDismissRequest = { showDialog = false },
    ) {
        DialogFormColumn {
            TextField(
                value = draftPackageName,
                onValueChange = { draftPackageName = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.rear_widget_target_package),
                singleLine = true,
            )
            TextField(
                value = draftScene,
                onValueChange = { draftScene = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.rear_widget_scene_name),
                singleLine = true,
            )
            TextField(
                value = draftBusiness,
                onValueChange = { draftBusiness = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.rear_widget_business_name),
                singleLine = true,
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { submitDialog() },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.rear_widget_confirm))
                }
                Button(
                    onClick = { showDialog = false },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.rear_widget_cancel))
                }
            }
        }
    }
}
