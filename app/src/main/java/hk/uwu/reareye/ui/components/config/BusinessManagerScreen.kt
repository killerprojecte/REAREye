package hk.uwu.reareye.ui.components.config

import android.annotation.SuppressLint
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Delete
import com.composables.icons.materialsymbols.rounded.Edit_note
import com.composables.icons.materialsymbols.rounded.Expand_more
import com.composables.icons.materialsymbols.rounded.Open_in_new
import com.composables.icons.materialsymbols.rounded.Storefront
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearAppCardRepository
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigFields
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigRepository
import hk.uwu.reareye.repository.rearwidget.RearCardConfig
import hk.uwu.reareye.repository.rearwidget.RearCardOrderSetting
import hk.uwu.reareye.repository.rearwidget.RearCardPriorityManager
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.ui.components.OverlayDialog
import hk.uwu.reareye.ui.components.RearBadgeGroup
import hk.uwu.reareye.ui.components.card.ModuleStyleDeleteAction
import hk.uwu.reareye.ui.components.card.ModuleStyleIconAction
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.reareye.ui.components.motion.ArtRevealItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.ui.theme.rearAcrylicEffect
import hk.uwu.reareye.ui.theme.rearAcrylicSource
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeState
import hk.uwu.reareye.ui.theme.rememberAcrylicHazeStyle
import hk.uwu.reareye.widgetapi.RearAppCardInfo
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
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val DEFAULT_COMPONENT_ROUTE_PACKAGE = "com.xiaomi.subscreencenter"
private const val REAR_WIDGET_DEBUG_TAG = "RearWidgetDebug"

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun BusinessManagerScreen(
    prefsManager: PrefsManager,
    onBack: () -> Unit,
    onOpenBusinessExtra: (String) -> Unit = {},
    embedded: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    focusBusiness: String? = null,
    onFocusBusinessHandled: () -> Unit = {},
    onOpenCard: (String) -> Unit = {},
    onOpenStoreDetail: (String) -> Unit = {},
    actionRequest: ConfigDashboardAction? = null,
    onActionHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val scrollBehavior = MiuixScrollBehavior()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val widgets = remember { mutableStateListOf<RearBusinessConfig>() }
    val cards = remember { mutableStateListOf<RearCardConfig>() }
    val appCards = remember { mutableStateListOf<RearAppCardInfo>() }
    var widgetsLoaded by remember { mutableStateOf(false) }
    var dataCardsVisible by remember { mutableStateOf(false) }
    val remotePrefsStatusRevision = rememberRemotePrefsStatusRevision()

    val showDialog = remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var draftWidget by remember { mutableStateOf("") }
    var draftFilePath by remember { mutableStateOf("") }

    val showRegisterCardDialog = remember { mutableStateOf(false) }
    var draftCardId by remember { mutableStateOf(RearWidgetConfigCodec.newCardId()) }
    var draftCardTitle by remember { mutableStateOf("") }
    var draftCardPackageName by remember { mutableStateOf("hk.uwu.reareye") }
    var draftCardBusiness by remember { mutableStateOf("") }
    var draftCardPriorityText by remember { mutableStateOf("500") }
    var draftCardAutomaticPriority by remember { mutableStateOf(true) }
    var draftCardSticky by remember { mutableStateOf(true) }
    var registerCardMode by remember { mutableIntStateOf(0) }
    var draftAppCardTitle by remember { mutableStateOf("") }
    var selectedAppBusinessIndex by remember { mutableIntStateOf(0) }
    var draftHideTimeTip by remember { mutableStateOf(false) }
    var expandedBusinessId by remember { mutableStateOf<String?>(null) }
    var highlightedBusinessId by remember { mutableStateOf<String?>(null) }

    fun debugLog(message: String) {
        if (prefsManager.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            Log.d(REAR_WIDGET_DEBUG_TAG, message)
        }
    }

    LaunchedEffect(prefsManager, remotePrefsStatusRevision) {
        val remoteReady = withContext(Dispatchers.IO) { prefsManager.isRemoteReady() }
        if (!remoteReady) {
            if (widgets.isEmpty()) {
                widgetsLoaded = false
                dataCardsVisible = false
            }
            debugLog("business load deferred: remote preferences not ready revision=$remotePrefsStatusRevision")
            return@LaunchedEffect
        }

        val loadedWidgets = withContext(Dispatchers.IO) {
            RearWidgetManagerRepository.loadBusinesses(prefsManager)
        }
        val loadedCards = withContext(Dispatchers.IO) {
            RearWidgetManagerRepository.loadCards(prefsManager)
        }
        widgets.clear()
        widgets.addAll(loadedWidgets)
        cards.clear()
        cards.addAll(loadedCards)
        widgetsLoaded = true
        dataCardsVisible = true
        withContext(Dispatchers.IO) {
            RearWidgetManagerRepository.refreshRuntimeFromPrefs(context, prefsManager)
        }
        val loadedAppCards = withContext(Dispatchers.IO) {
            RearAppCardRepository.loadCatalog(context)
        }
        appCards.clear()
        appCards.addAll(loadedAppCards)
    }

    fun persist() {
        val nextWidgets = widgets.toList()
        scope.launch(Dispatchers.IO) {
            RearWidgetManagerRepository.saveBusinesses(context, prefsManager, nextWidgets)
        }
    }

    fun openCreateDialog() {
        editingId = null
        draftWidget = ""
        draftFilePath = ""
        draftHideTimeTip = false
        showDialog.value = true
    }

    LaunchedEffect(actionRequest, widgetsLoaded) {
        if (actionRequest == ConfigDashboardAction.ADD_COMPONENT && widgetsLoaded) {
            openCreateDialog()
            onActionHandled()
        }
    }

    LaunchedEffect(focusBusiness, widgetsLoaded) {
        val requestedBusiness = focusBusiness?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (!widgetsLoaded) return@LaunchedEffect

        val targetIndex = widgets.indexOfFirst { it.business == requestedBusiness }
        if (targetIndex < 0) {
            onFocusBusinessHandled()
            return@LaunchedEffect
        }

        highlightedBusinessId = widgets[targetIndex].id
        listState.animateScrollToItem(targetIndex)
        delay(1_600)
        highlightedBusinessId = null
        onFocusBusinessHandled()
    }

    fun openEditDialog(item: RearBusinessConfig) {
        if (item.downloadedFromStore) {
            debugLog(
                "open business editor: business=${item.business}, renameable=${item.renameable}, storeWidgetId=${item.storeWidgetId}"
            )
        }
        if (!item.renameable) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_business_locked_summary),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        editingId = item.id
        draftWidget = item.business
        draftFilePath = item.filePath
        draftHideTimeTip = !RearBusinessExtraConfigRepository
            .getConfigForBusiness(prefsManager, item.business)
            .getShowTimeTipOrDefault()
        showDialog.value = true
    }

    fun openRegisterCardDialog(item: RearBusinessConfig) {
        registerCardMode = 0
        draftCardId = RearWidgetConfigCodec.newCardId()
        draftCardTitle = item.business
        draftCardPackageName = "hk.uwu.reareye"
        draftCardBusiness = item.business
        draftCardPriorityText = item.defaultPriority.toString()
        draftCardAutomaticPriority = true
        draftCardSticky = true
        draftAppCardTitle = item.business
        selectedAppBusinessIndex = widgets.indexOfFirst { it.business == item.business }
            .coerceAtLeast(0)
        showRegisterCardDialog.value = true
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun submitDialog() {
        val editingBusiness = editingId?.let { id -> widgets.firstOrNull { it.id == id } }
        if (editingBusiness?.renameable == false) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_business_locked_summary),
                Toast.LENGTH_SHORT
            ).show()
            showDialog.value = false
            return
        }
        val widget = draftWidget.trim()
        val path = draftFilePath.trim()
        if (widget.isBlank() || path.isBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_form_invalid),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val existingLockedBusiness = widgets.firstOrNull {
            it.business == widget && !it.renameable && it.id != editingBusiness?.id
        }
        if (existingLockedBusiness != null) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_business_locked_summary),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val config = RearBusinessConfig(
            id = editingBusiness?.id
                ?: RearWidgetConfigCodec.newBusinessId(DEFAULT_COMPONENT_ROUTE_PACKAGE, widget),
            packageName = DEFAULT_COMPONENT_ROUTE_PACKAGE,
            business = widget,
            filePath = path,
            defaultIndex = editingBusiness?.defaultIndex ?: 0,
            defaultPriority = editingBusiness?.defaultPriority ?: 500,
            renameable = editingBusiness?.renameable ?: true,
            downloadedFromStore = editingBusiness?.downloadedFromStore ?: false,
            storeWidgetId = editingBusiness?.storeWidgetId,
            storeWidgetName = editingBusiness?.storeWidgetName,
            storeReleaseTag = editingBusiness?.storeReleaseTag,
            storeReleaseAssetName = editingBusiness?.storeReleaseAssetName,
            storeReleasePublishedAt = editingBusiness?.storeReleasePublishedAt,
            storeInstalledAt = editingBusiness?.storeInstalledAt,
        )

        RearBusinessExtraConfigRepository.updateConfigForBusiness(
            prefsManager = prefsManager,
            business = widget,
        ) { extra ->
            extra.withBoolean(RearBusinessExtraConfigFields.HIDE_TIME_TIP, draftHideTimeTip)
        }

        editingId?.let { id ->
            val oldIndex = widgets.indexOfFirst { it.id == id }
            if (oldIndex >= 0) widgets.removeAt(oldIndex)
        }

        val existingIndex = widgets.indexOfFirst { it.business == widget }
        if (existingIndex >= 0) {
            widgets[existingIndex] = config
        } else {
            widgets.add(config)
        }
        widgets.sortBy { it.id }

        persist()
        showDialog.value = false
        Toast.makeText(
            context,
            context.getString(R.string.rear_widget_business_saved),
            Toast.LENGTH_SHORT
        ).show()
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun submitRegisterCardDialog() {
        val packageName = draftCardPackageName.trim()
        val business = draftCardBusiness.trim()
        if (packageName.isBlank() || business.isBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_form_invalid),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val card = RearCardConfig(
            id = draftCardId,
            title = draftCardTitle.trim().ifBlank { business },
            packageName = packageName,
            business = business,
            enabled = true,
            sticky = draftCardSticky,
            priority = draftCardPriorityText.toIntOrNull() ?: 500,
        )
        val nextCards = RearWidgetManagerRepository.loadCards(prefsManager).toMutableList().apply {
            add(card)
        }
        val nextSettings = RearWidgetManagerRepository.loadCardOrderSettings(prefsManager)
            .toMutableMap()
            .apply {
                this[card.id] = RearCardOrderSetting(
                    automatic = draftCardAutomaticPriority,
                    position = nextCards.lastIndex,
                )
            }
        val reorderedCards = RearCardPriorityManager.assignAutomaticPriorities(
            cards = nextCards,
            settings = nextSettings,
        )
        cards.clear()
        cards.addAll(reorderedCards)
        scope.launch(Dispatchers.IO) {
            RearWidgetManagerRepository.saveCardOrderSettings(prefsManager, nextSettings)
            RearWidgetManagerRepository.saveCards(context, prefsManager, reorderedCards)
        }
        showRegisterCardDialog.value = false
        Toast.makeText(
            context,
            context.getString(R.string.rear_widget_card_saved),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun submitRegisterAppCardDialog() {
        val title = draftAppCardTitle.trim()
        val business = widgets.getOrNull(selectedAppBusinessIndex)?.business.orEmpty()
        if (title.isBlank() || business.isBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_form_invalid),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                RearAppCardRepository.register(
                    context = context,
                    prefsManager = prefsManager,
                    title = title,
                    componentBusiness = business,
                )
            }
            Toast.makeText(
                context,
                if (result.success) {
                    context.getString(R.string.rear_widget_app_registered)
                } else {
                    result.error.orEmpty().ifBlank {
                        context.getString(R.string.rear_widget_app_operation_failed)
                    }
                },
                Toast.LENGTH_SHORT,
            ).show()
            if (result.success) {
                val refreshed = withContext(Dispatchers.IO) {
                    RearAppCardRepository.loadCatalog(context)
                }
                appCards.clear()
                appCards.addAll(refreshed)
                showRegisterCardDialog.value = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val copied = RearWidgetManagerRepository.copyTemplateToManagedPath(
            context = context,
            uri = uri,
            businessNameHint = draftWidget,
        )
        if (copied.isNullOrBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_file_pick_failed),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            draftFilePath = copied
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_file_pick_success),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    Scaffold(
        topBar = {
            if (!embedded) TopAppBar(
                modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                color = Color.Transparent,
                title = stringResource(R.string.rear_widget_business_manager),
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
                    IconButton(
                        onClick = { if (widgetsLoaded) openCreateDialog() }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.rear_widget_add_business),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .rearAcrylicSource(hazeState)
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(
                top = if (embedded) {
                    contentPadding.calculateTopPadding()
                } else {
                    paddingValues.calculateTopPadding() + contentPadding.calculateTopPadding()
                },
                bottom = paddingValues.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            overscrollEffect = null,
        ) {
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
                    items = widgets,
                    key = { _, item -> item.id },
                    contentType = { _, _ -> "business_item" },
                ) { _, item ->
                    val relatedCards = cards.filter { card ->
                        card.business == item.business
                    }
                    val relatedAppCards = appCards.filter { appCard ->
                        appCard.ownedByRearEye && appCard.componentBusiness == item.business
                    }
                    val isHighlighted = highlightedBusinessId == item.id
                    ModuleStyleManagerCard(
                        backgroundColor = if (isHighlighted) {
                            MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                        } else null,
                        title = item.business,
                        summaryLines = listOf(item.filePath),
                        badges = buildList {
                            addAll(
                                rearWidgetSourceBadges(
                                    downloadedFromStore = item.downloadedFromStore,
                                    storeWidgetId = item.storeWidgetId,
                                )
                            )
                            if (!item.renameable) {
                                add(rearWidgetLockedBadge())
                            }
                        },
                        onCardClick = if (item.renameable) {
                            { openEditDialog(item) }
                        } else {
                            null
                        },
                        leftAction = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                if (item.renameable) {
                                    ModuleStyleIconAction(
                                        icon = MaterialSymbols.Rounded.Edit_note,
                                        contentDescription = stringResource(R.string.rear_widget_edit_business),
                                        onClick = { openEditDialog(item) },
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.82f),
                                    )
                                }
                                item.storeWidgetId
                                    ?.trim()
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { storeWidgetId ->
                                        ModuleStyleIconAction(
                                            icon = MaterialSymbols.Rounded.Storefront,
                                            contentDescription = stringResource(R.string.rear_store_open_detail),
                                            onClick = { onOpenStoreDetail(storeWidgetId) },
                                        )
                                    }
                                ModuleStyleIconAction(
                                    icon = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.rear_widget_add_card),
                                    onClick = { openRegisterCardDialog(item) },
                                )
                                if (relatedCards.isNotEmpty() || relatedAppCards.isNotEmpty()) {
                                    ModuleStyleIconAction(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .graphicsLayer {
                                                rotationZ =
                                                    if (expandedBusinessId == item.id) 180f else 0f
                                            },
                                        icon = MaterialSymbols.Rounded.Expand_more,
                                        contentDescription = stringResource(R.string.rear_widget_action_related_cards),
                                        onClick = {
                                            expandedBusinessId =
                                                if (expandedBusinessId == item.id) null else item.id
                                        },
                                    )
                                }
                            }
                        },
                        rightAction = {
                            ModuleStyleDeleteAction(
                                icon = MiuixIcons.Delete,
                                text = stringResource(R.string.rear_widget_action_delete),
                                onClick = {
                                    widgets.remove(item)
                                    persist()
                                },
                            )
                        },
                    )
                    if (
                        expandedBusinessId == item.id &&
                        (relatedCards.isNotEmpty() || relatedAppCards.isNotEmpty())
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                        ) {
                            Column {
                                relatedCards.forEach { card ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = card.title,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        ModuleStyleIconAction(
                                            icon = MaterialSymbols.Rounded.Open_in_new,
                                            contentDescription = stringResource(R.string.rear_widget_action_jump),
                                            onClick = { onOpenCard(card.id) },
                                        )
                                        ModuleStyleIconAction(
                                            icon = MaterialSymbols.Rounded.Delete,
                                            contentDescription = stringResource(R.string.rear_widget_action_delete),
                                            contentColor = Color(0xFFD32F2F),
                                            onClick = {
                                                cards.remove(card)
                                                scope.launch(Dispatchers.IO) {
                                                    RearWidgetManagerRepository.saveCards(
                                                        context,
                                                        prefsManager,
                                                        cards.toList(),
                                                    )
                                                }
                                            },
                                        )
                                    }
                                }
                                relatedAppCards.forEach { appCard ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = appCard.title.ifBlank { appCard.appId },
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        RearBadgeGroup(
                                            badges = buildList {
                                                add(rearWidgetAppCardBadge())
                                                addAll(
                                                    rearWidgetSourceBadges(
                                                        downloadedFromStore = item.downloadedFromStore,
                                                        storeWidgetId = item.storeWidgetId,
                                                    )
                                                )
                                            },
                                        )
                                        item.storeWidgetId
                                            ?.trim()
                                            ?.takeIf { it.isNotEmpty() }
                                            ?.let { storeWidgetId ->
                                                ModuleStyleIconAction(
                                                    icon = MaterialSymbols.Rounded.Storefront,
                                                    contentDescription = stringResource(
                                                        R.string.rear_store_open_detail,
                                                    ),
                                                    onClick = {
                                                        onOpenStoreDetail(storeWidgetId)
                                                    },
                                                )
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                if (dataCardsVisible && widgets.isEmpty()) {
                    ArtRevealItem(visible = true, delayMillis = 40) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(R.string.rear_widget_empty_business),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    val dialogEditingBusiness = editingId?.let { id -> widgets.firstOrNull { it.id == id } }
    val lockedDialogBusiness = dialogEditingBusiness?.takeIf { !it.renameable }

    LaunchedEffect(showDialog.value, lockedDialogBusiness?.id) {
        if (showDialog.value && lockedDialogBusiness != null) {
            if (lockedDialogBusiness.downloadedFromStore) {
                debugLog(
                    "blocked business dialog: business=${lockedDialogBusiness.business}, renameable=${lockedDialogBusiness.renameable}, storeWidgetId=${lockedDialogBusiness.storeWidgetId}"
                )
            }
            showDialog.value = false
            editingId = null
        }
    }

    OverlayDialog(
        show = showDialog.value && lockedDialogBusiness == null,
        title = stringResource(
            if (editingId == null) R.string.rear_widget_add_business else R.string.rear_widget_edit_business,
        ),
        onDismissRequest = { showDialog.value = false },
    ) {
        val lockedBusiness = dialogEditingBusiness?.takeIf { !it.renameable }
        DialogFormColumn {
            TextField(
                value = draftWidget,
                onValueChange = { draftWidget = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.rear_widget_business_name),
                enabled = lockedBusiness == null,
                readOnly = lockedBusiness != null,
                singleLine = true,
            )
            TextField(
                value = draftFilePath,
                onValueChange = { draftFilePath = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.rear_widget_template_file),
                enabled = lockedBusiness == null,
                readOnly = lockedBusiness != null,
                singleLine = true,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = lockedBusiness == null,
                onClick = { if (lockedBusiness == null) picker.launch(arrayOf("*/*")) }) {
                Icon(
                    imageVector = Icons.Filled.UploadFile,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(stringResource(R.string.rear_widget_pick_file))
            }
            SwitchPreference(
                title = stringResource(R.string.rear_widget_business_hide_time_tip),
                summary = stringResource(R.string.rear_widget_business_hide_time_tip_desc),
                checked = draftHideTimeTip,
                onCheckedChange = { draftHideTimeTip = it },
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
                Button(onClick = { showDialog.value = false }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.rear_widget_cancel))
                }
            }
        }
    }

    RearCardCreateDialog(
        show = showRegisterCardDialog.value,
        mode = registerCardMode,
        onModeChange = { registerCardMode = it },
        businesses = widgets,
        normalTitle = draftCardTitle,
        onNormalTitleChange = { draftCardTitle = it },
        normalPackageName = draftCardPackageName,
        onNormalPackageNameChange = { draftCardPackageName = it },
        normalBusiness = draftCardBusiness,
        onNormalBusinessChange = { draftCardBusiness = it },
        normalPriorityText = draftCardPriorityText,
        onNormalPriorityTextChange = { draftCardPriorityText = it },
        normalAutomaticPriority = draftCardAutomaticPriority,
        onNormalAutomaticPriorityChange = { draftCardAutomaticPriority = it },
        normalSticky = draftCardSticky,
        onNormalStickyChange = { draftCardSticky = it },
        appTitle = draftAppCardTitle,
        onAppTitleChange = { draftAppCardTitle = it },
        selectedAppBusinessIndex = selectedAppBusinessIndex,
        onSelectedAppBusinessIndexChange = { selectedAppBusinessIndex = it },
        onConfirmNormal = ::submitRegisterCardDialog,
        onConfirmApp = ::submitRegisterAppCardDialog,
        onDismissRequest = { showRegisterCardDialog.value = false },
    )
}
