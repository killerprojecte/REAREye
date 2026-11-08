package hk.uwu.reareye.ui.components.config

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfig
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigEntry
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigFields
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigRepository
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.ui.components.OverlayDialog
import hk.uwu.reareye.ui.components.card.ModuleStyleDeleteAction
import hk.uwu.reareye.ui.components.card.ModuleStyleIconAction
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.reareye.ui.components.card.SuperCard
import hk.uwu.mixui.motion.ArtRevealItem
import hk.uwu.mixui.motion.ArtStaggeredReveal
import hk.uwu.mixui.motion.ArtSwapContent
import hk.uwu.reareye.ui.config.ConfigGroup
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigNode
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import hk.uwu.mixui.components.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import hk.uwu.mixui.components.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val BusinessExtraConfigNodes = listOf<ConfigNode>(
    ConfigGroup(
        children = listOf(
            ConfigItem(
                key = RearBusinessExtraConfigFields.HIDE_TIME_TIP,
                titleRes = R.string.rear_widget_business_hide_time_tip,
                descriptionRes = R.string.rear_widget_business_hide_time_tip_desc,
                type = ConfigType.BooleanVal(defaultValue = false),
            )
        )
    )
)

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun BusinessExtraConfigManagerScreen(
    prefsManager: PrefsManager,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val entries = remember { mutableStateListOf<RearBusinessExtraConfigEntry>() }
    var loaded by remember { mutableStateOf(false) }
    var dataCardsVisible by remember { mutableStateOf(false) }
    val remotePrefsStatusRevision = rememberRemotePrefsStatusRevision()
    var showDialog by remember { mutableStateOf(false) }
    var draftBusiness by remember { mutableStateOf("") }
    var editingBusiness by remember { mutableStateOf<String?>(null) }

    suspend fun reloadEntries(): Boolean {
        val remoteReady = withContext(Dispatchers.IO) { prefsManager.isRemoteReady() }
        if (!remoteReady) return false
        val loadedEntries = withContext(Dispatchers.IO) {
            RearBusinessExtraConfigRepository.getAllConfigs(prefsManager)
        }
        entries.clear()
        entries.addAll(loadedEntries)
        return true
    }

    LaunchedEffect(prefsManager, remotePrefsStatusRevision) {
        if (!reloadEntries()) {
            if (entries.isEmpty()) {
                loaded = false
                dataCardsVisible = false
            }
            return@LaunchedEffect
        }
        loaded = true
        delay(90)
        dataCardsVisible = true
    }

    LaunchedEffect(editingBusiness, remotePrefsStatusRevision) {
        if (editingBusiness == null && loaded) {
            if (!withContext(Dispatchers.IO) { prefsManager.isRemoteReady() }) return@LaunchedEffect
            dataCardsVisible = false
            reloadEntries()
            delay(90)
            dataCardsVisible = true
        }
    }

    fun openCreateDialog() {
        draftBusiness = ""
        showDialog = true
    }

    fun submitDialog() {
        val business = draftBusiness.trim()
        if (business.isBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_form_invalid),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val existing = entries.firstOrNull { it.business == business }
        if (existing == null) {
            RearBusinessExtraConfigRepository.saveConfigForBusiness(
                prefsManager = prefsManager,
                business = business,
                config = RearBusinessExtraConfig(),
            )

            entries.add(
                RearBusinessExtraConfigEntry(
                    business = business,
                    config = RearBusinessExtraConfigRepository.getConfigForBusiness(
                        prefsManager = prefsManager,
                        business = business,
                    ),
                )
            )
            entries.sortBy { it.business.lowercase() }
        }

        showDialog = false
        editingBusiness = business
    }

    BackHandler(enabled = editingBusiness != null) {
        editingBusiness = null
    }

    ArtSwapContent(
        targetState = editingBusiness,
        modifier = Modifier.fillMaxSize(),
        contentKey = { it ?: "manager" },
    ) { currentBusiness ->
        if (currentBusiness != null) {
            BusinessExtraConfigScreen(
                prefsManager = prefsManager,
                business = currentBusiness,
                onBack = {
                    editingBusiness = null
                },
            )
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                        color = Color.Transparent,
                        title = stringResource(R.string.rear_widget_business_extra_manager),
                        navigationIcon = {
                            IconButton(
                                modifier = Modifier.padding(start = 16.dp),
                                onClick = onBack
                            ) {
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
                            IconButton(
                                modifier = Modifier.padding(end = 16.dp),
                                onClick = { if (loaded) openCreateDialog() },
                            ) {
                                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                            }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
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
                                .fillMaxWidth()
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SuperCard(
                                    title = stringResource(R.string.rear_widget_business_extra_hint_title),
                                    summary = buildString {
                                        if (loaded) {
                                            append(
                                                context.getString(
                                                    R.string.rear_widget_business_extra_count,
                                                    entries.size,
                                                )
                                            )
                                            append('\n')
                                        }
                                        append(context.getString(R.string.rear_widget_business_extra_hint))
                                    },
                                    onClick = {},
                                    bottomAction = {
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
                                            Text(text = stringResource(R.string.rear_widget_business_extra_add))
                                        }
                                    }
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
                        itemsIndexed(entries, key = { _, item -> item.business }) { index, item ->
                            ArtStaggeredReveal(
                                visible = true,
                                revealKey = item.business,
                                delayMillis = (36 + index * 18).coerceAtMost(150),
                            ) {
                                ModuleStyleManagerCard(
                                    title = item.business,
                                    summaryLines = listOf(),
                                    onCardClick = { editingBusiness = item.business },
                                    leftAction = {
                                        ModuleStyleIconAction(
                                            icon = Icons.Rounded.EditNote,
                                            onClick = { editingBusiness = item.business },
                                        )
                                    },
                                    rightAction = {
                                        ModuleStyleDeleteAction(
                                            icon = MiuixIcons.Delete,
                                            text = stringResource(R.string.rear_widget_action_delete),
                                            onClick = {
                                                RearBusinessExtraConfigRepository.removeConfigForBusiness(
                                                    prefsManager = prefsManager,
                                                    business = item.business,
                                                )
                                                entries.remove(item)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }

                    item {
                        if (dataCardsVisible && entries.isEmpty()) {
                            ArtRevealItem(visible = true, delayMillis = 40) {
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = stringResource(R.string.rear_widget_business_extra_empty),
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
                title = stringResource(R.string.rear_widget_business_extra_add),
                onDismissRequest = { showDialog = false },
            ) {
                DialogFormColumn {
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
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.rear_widget_cancel))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BusinessExtraConfigScreen(
    prefsManager: PrefsManager,
    business: String,
    onBack: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val remotePrefsStatusRevision = rememberRemotePrefsStatusRevision()
    var remoteReady by remember(business, remotePrefsStatusRevision) {
        mutableStateOf(false)
    }
    var config by remember(business, remotePrefsStatusRevision) {
        mutableStateOf<RearBusinessExtraConfig?>(null)
    }

    LaunchedEffect(prefsManager, business, remotePrefsStatusRevision) {
        val ready = withContext(Dispatchers.IO) { prefsManager.isRemoteReady() }
        remoteReady = ready
        if (!ready) {
            config = null
            return@LaunchedEffect
        }
        config = withContext(Dispatchers.IO) {
            RearBusinessExtraConfigRepository.getConfigForBusiness(
                prefsManager = prefsManager,
                business = business,
            )
        }
    }

    fun saveConfig(updated: RearBusinessExtraConfig) {
        if (!remoteReady) return
        config = updated
        RearBusinessExtraConfigRepository.saveConfigForBusiness(
            prefsManager = prefsManager,
            business = business,
            config = updated,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                color = Color.Transparent,
                title = stringResource(R.string.rear_widget_business_extra_settings),
                navigationIconPadding = 12.dp,
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
                scrollBehavior = scrollBehavior,
            )
        }
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
            overscrollEffect = null,
        ) {
            item {
                Card(
                    modifier = Modifier
                        .padding(bottom = 8.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(
                            R.string.rear_widget_business_extra_settings_target,
                            business
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }

            val loadedConfig = config
            if (!remoteReady || loadedConfig == null) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        insideMargin = PaddingValues(vertical = 24.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            InfiniteProgressIndicator()
                        }
                    }
                }
            } else {
                itemsIndexed(
                    BusinessExtraConfigNodes,
                    key = { index, node -> node.key ?: "extra_$index" }) { index, node ->
                    if (node is ConfigGroup) {
                        Card(
                            modifier = Modifier
                                .padding(top = if (index == 0) 0.dp else 8.dp)
                                .fillMaxWidth()
                        ) {
                            node.children.forEach { child ->
                                BusinessExtraConfigNodeRow(
                                    node = child,
                                    config = loadedConfig,
                                    onConfigChange = ::saveConfig,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BusinessExtraConfigNodeRow(
    node: ConfigNode,
    config: RearBusinessExtraConfig,
    onConfigChange: (RearBusinessExtraConfig) -> Unit,
) {
    if (node !is ConfigItem) return
    when (val type = node.type) {
        is ConfigType.BooleanVal -> {
            val checked = config.getBoolean(node.key, type.defaultValue)
            SwitchPreference(
                title = stringResource(node.titleRes),
                summary = node.descriptionRes?.let { stringResource(it) },
                checked = checked,
                onCheckedChange = { value ->
                    onConfigChange(config.withBoolean(node.key, value))
                },
            )
        }

        else -> Unit
    }
}
