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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Deployed_code
import com.composables.icons.materialsymbols.rounded.Storefront
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearAppCardRepository
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearCardConfig
import hk.uwu.reareye.repository.rearwidget.RearCardOrderSetting
import hk.uwu.reareye.repository.rearwidget.RearCardPriorityManager
import hk.uwu.reareye.repository.rearwidget.RearWidgetConfigCodec
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.repository.widgettemplate.WidgetTemplateConfigRepository
import hk.uwu.reareye.ui.FeatureGuideAction
import hk.uwu.reareye.ui.LocalFeatureGuideDemo
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.ui.components.OverlayDialog
import hk.uwu.reareye.ui.components.RearBadgeGroup
import hk.uwu.reareye.ui.components.card.ModuleStyleDeleteAction
import hk.uwu.reareye.ui.components.card.ModuleStyleIconAction
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.reareye.ui.components.card.SuperCard
import hk.uwu.reareye.ui.components.config.draggable.library.draggable.DraggableItem
import hk.uwu.reareye.ui.components.config.draggable.library.draggable.rememberDraggableLazyListState
import hk.uwu.reareye.ui.components.config.draggable.longPressDraggable
import hk.uwu.reareye.ui.components.config.template.TemplateConfigRouteTransition
import hk.uwu.reareye.ui.components.config.template.WidgetTemplateConfigScreen
import hk.uwu.reareye.ui.components.motion.ArtRevealItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.ui.featureGuideAnchor
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
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val REAR_WIDGET_DEBUG_TAG = "RearWidgetDebug"

private fun normalizeTemplateBusinessName(raw: String): String {
    return when (raw.trim()) {
        "taxi", "car_hailing", "carHailing" -> "carHailing"
        "food_Delivery", "food_delivery", "foodDelivery" -> "foodDelivery"
        "miHomeCamera", "mihomeCamera" -> "mihomeCamera"
        "xiaomi_ev", "xiaomiev" -> "xiaomiev"
        else -> raw.trim()
    }
}

@Composable
fun CardManagerScreen(
    prefsManager: PrefsManager,
    onBack: () -> Unit,
    embedded: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    focusCardId: String? = null,
    onFocusCardHandled: () -> Unit = {},
    onOpenComponent: (String) -> Unit = {},
    onOpenStoreDetail: (String) -> Unit = {},
    actionRequest: ConfigDashboardAction? = null,
    onActionHandled: () -> Unit = {},
) {
    val demo = LocalFeatureGuideDemo.current
    val context = LocalContext.current
    val layoutDirection = LocalLayoutDirection.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    val hazeState = rememberAcrylicHazeState()
    val hazeStyle = rememberAcrylicHazeStyle()
    val cards = remember { mutableStateListOf<RearCardConfig>() }
    val businesses = remember { mutableStateListOf<RearBusinessConfig>() }
    val templateAvailability = remember { mutableStateMapOf<String, Boolean>() }
    var cardsLoaded by remember { mutableStateOf(false) }
    var dataCardsVisible by remember { mutableStateOf(false) }
    var runtimeRefreshTick by remember { mutableIntStateOf(0) }
    val cardPagerState = rememberPagerState(initialPage = 0, pageCount = { 2 })
    val selectedCardTab by remember {
        derivedStateOf {
            if (cardPagerState.isScrollInProgress) {
                cardPagerState.targetPage
            } else {
                cardPagerState.currentPage
            }
        }
    }
    var appRefreshRevision by remember { mutableIntStateOf(0) }
    val cardOrderSettings = remember { mutableStateMapOf<String, RearCardOrderSetting>() }
    var highlightedCardId by remember { mutableStateOf<String?>(null) }
    val remotePrefsStatusRevision = rememberRemotePrefsStatusRevision()

    fun debugLog(message: String) {
        if (prefsManager.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            Log.d(REAR_WIDGET_DEBUG_TAG, message)
        }
    }

    LaunchedEffect(prefsManager, remotePrefsStatusRevision) {
        if (demo != null) {
            cards.clear(); cards.addAll(demo.state.cards)
            businesses.clear(); businesses.addAll(demo.state.businesses)
            cardOrderSettings.clear(); cardOrderSettings.putAll(demo.state.orderSettings)
            cardsLoaded = true
            dataCardsVisible = true
            return@LaunchedEffect
        }
        val remoteReady = withContext(Dispatchers.IO) { prefsManager.isRemoteReady() }
        if (!remoteReady) {
            if (cards.isEmpty() && businesses.isEmpty()) {
                cardsLoaded = false
                dataCardsVisible = false
            }
            debugLog("card load deferred: remote preferences not ready revision=$remotePrefsStatusRevision")
            return@LaunchedEffect
        }

        val loadedCards = withContext(Dispatchers.IO) {
            RearWidgetManagerRepository.loadCards(prefsManager)
        }
        val loadedOrderSettings = withContext(Dispatchers.IO) {
            RearWidgetManagerRepository.loadCardOrderSettings(prefsManager)
        }
        val loadedBusinesses = withContext(Dispatchers.IO) {
            RearWidgetManagerRepository.loadBusinesses(prefsManager)
        }
        cards.clear()
        cards.addAll(loadedCards)
        cardOrderSettings.clear()
        cardOrderSettings.putAll(loadedOrderSettings)
        businesses.clear()
        businesses.addAll(loadedBusinesses)
        cardsLoaded = true
        dataCardsVisible = true
        withContext(Dispatchers.IO) {
            RearWidgetManagerRepository.refreshRuntimeFromPrefs(context, prefsManager)
        }
        runtimeRefreshTick++
    }

    LaunchedEffect(focusCardId, cardsLoaded) {
        val id = focusCardId ?: return@LaunchedEffect
        if (!cardsLoaded) return@LaunchedEffect
        val index = cards.indexOfFirst { it.id == id }
        if (index < 0) return@LaunchedEffect
        highlightedCardId = id
        listState.animateScrollToItem(index + if (embedded) 1 else 2)
        delay(1600)
        highlightedCardId = null
        onFocusCardHandled()
    }

    val showDialog = remember { mutableStateOf(false) }
    var dialogSessionId by remember { mutableIntStateOf(0) }
    var editingCardId by remember { mutableStateOf<String?>(null) }
    var draftCardId by remember { mutableStateOf(RearWidgetConfigCodec.newCardId()) }
    val activeTemplateCardId = remember { mutableStateOf<String?>(null) }
    var draftTitle by remember { mutableStateOf("") }
    var draftPackageName by remember { mutableStateOf("hk.uwu.reareye") }
    var draftBusiness by remember { mutableStateOf("") }
    var draftPriorityText by remember { mutableStateOf("500") }
    var draftAutomaticPriority by remember { mutableStateOf(true) }
    var draftSticky by remember { mutableStateOf(true) }
    var draftOneConfigJson by remember { mutableStateOf<String?>(null) }
    var addModeTab by remember { mutableIntStateOf(0) }
    var appDraftTitle by remember { mutableStateOf("") }
    var selectedBusinessIndex by remember { mutableIntStateOf(0) }

    fun persist() {
        val nextCards = cards.toList()
        if (demo != null) {
            demo.state.cards = nextCards; return
        }
        scope.launch(Dispatchers.IO) {
            RearWidgetManagerRepository.saveCards(context, prefsManager, nextCards)
        }
    }

    fun persistCardOrder() {
        // A drag is an automatic ordering action. Manual numeric priority remains
        // available from the editor and can be restored there explicitly.
        val currentSettings = cards.associate { card ->
            card.id to (cardOrderSettings[card.id] ?: RearCardOrderSetting()).copy(automatic = true)
        }
        val withAutomaticPriority = RearCardPriorityManager.assignAutomaticPriorities(
            cards = cards.toList(),
            settings = currentSettings,
        )
        val reordered = RearCardPriorityManager.sorted(withAutomaticPriority, currentSettings)
        val nextSettings = RearCardPriorityManager.rememberOrder(reordered, currentSettings)
        cards.clear()
        cards.addAll(reordered)
        cardOrderSettings.clear()
        cardOrderSettings.putAll(nextSettings)
        if (demo != null) {
            demo.state.cards = reordered
            demo.state.orderSettings = nextSettings
        } else scope.launch(Dispatchers.IO) {
            RearWidgetManagerRepository.saveCardOrderSettings(prefsManager, nextSettings)
            RearWidgetManagerRepository.saveCards(context, prefsManager, reordered)
        }
    }

    val draggableState = rememberDraggableLazyListState(
        state = listState,
        onSwap = { from, to ->
            val fromId = from.key as? String ?: return@rememberDraggableLazyListState
            val toId = to.key as? String ?: return@rememberDraggableLazyListState
            val fromIndex = cards.indexOfFirst { it.id == fromId }
            val toIndex = cards.indexOfFirst { it.id == toId }
            if (fromIndex >= 0 && toIndex >= 0 && fromIndex != toIndex) {
                val moved = cards.removeAt(fromIndex)
                cards.add(toIndex, moved)
            }
        },
        isItemLocked = { item ->
            val key = item.key as? String
            key == null || cards.none { it.id == key }
        },
        onDragFinished = { persistCardOrder() },
    )

    fun openCreateDialog(mode: Int = selectedCardTab) {
        if (demo != null) return
        editingCardId = null
        addModeTab = mode.coerceIn(0, 1)
        draftCardId = RearWidgetConfigCodec.newCardId()
        draftTitle = ""
        draftPackageName = "hk.uwu.reareye"
        draftBusiness = ""
        draftPriorityText = "500"
        draftAutomaticPriority = true
        draftSticky = true
        draftOneConfigJson = null
        appDraftTitle = ""
        selectedBusinessIndex = selectedBusinessIndex.coerceIn(
            0,
            businesses.lastIndex.coerceAtLeast(0),
        )
        dialogSessionId++
        showDialog.value = true
    }

    LaunchedEffect(actionRequest, cardsLoaded) {
        if (actionRequest == ConfigDashboardAction.ADD_CARD && cardsLoaded) {
            openCreateDialog(selectedCardTab)
            onActionHandled()
        }
    }

    fun openEditDialog(item: RearCardConfig) {
        if (item.downloadedFromStore) {
            debugLog(
                "open card editor: title=${item.title}, renameable=${item.renameable}, storeWidgetId=${item.storeWidgetId}, priority=${item.priority}"
            )
        }
        editingCardId = item.id
        draftCardId = item.id
        draftTitle = item.title
        draftPackageName = item.packageName
        draftBusiness = item.business
        draftPriorityText = item.priority.toString()
        draftAutomaticPriority = cardOrderSettings[item.id]?.automatic ?: true
        draftSticky = item.sticky
        draftOneConfigJson = item.oneConfigJson
        dialogSessionId++
        showDialog.value = true
        if (demo?.action == FeatureGuideAction.OPEN_CARD) demo.onAction(FeatureGuideAction.OPEN_CARD)
    }

    LaunchedEffect(demo?.action, cardsLoaded) {
        if (demo?.action == FeatureGuideAction.SAVE_CARD && cardsLoaded && !showDialog.value) {
            cards.firstOrNull()?.let(::openEditDialog)
        }
    }

    fun openTemplateConfig(item: RearCardConfig) {
        if (demo != null) return
        activeTemplateCardId.value = item.id
    }

    val activeTemplateCard =
        activeTemplateCardId.value?.let { id -> cards.firstOrNull { it.id == id } }

    val normalizedBusinessSourceByName by remember {
        derivedStateOf {
            businesses.associate { normalizeTemplateBusinessName(it.business) to it.filePath }
        }
    }
    val availabilityProbeBusinesses by remember {
        derivedStateOf {
            cards.map { normalizeTemplateBusinessName(it.business.trim()) }
                .filter { it.isNotBlank() }
                .distinct()
        }
    }

    LaunchedEffect(
        cardsLoaded,
        runtimeRefreshTick,
        availabilityProbeBusinesses,
        normalizedBusinessSourceByName,
    ) {
        if (!cardsLoaded || demo != null) return@LaunchedEffect
        val availability = linkedMapOf<String, Boolean>()
        availabilityProbeBusinesses.forEach { business ->
            val sourcePath = normalizedBusinessSourceByName[business].orEmpty()

            var editableCount: Int
            var available = false
            repeat(2) { attempt ->
                val state = withContext(Dispatchers.IO) {
                    RearWidgetManagerRepository.resolveTemplateConfigState(
                        context = context,
                        business = business,
                        sourceFilePath = sourcePath,
                        currentOneConfigJson = null,
                    )
                }
                val schema = state?.templateSchemaJson
                    ?.let(WidgetTemplateConfigRepository::decodeSchema)
                if (state?.templateSchemaJson?.isNotBlank() == true && schema == null) {
                    debugLog(
                        "schema decode failed business=$business source=${sourcePath.ifBlank { "<builtin>" }} schemaLength=${state.templateSchemaJson.length}"
                    )
                }
                editableCount = schema?.editableItemCount ?: -1
                available = editableCount > 0
                debugLog(
                    "availability probe business=$business source=${sourcePath.ifBlank { "<builtin>" }} attempt=${attempt + 1} editable=$editableCount available=$available"
                )
                if (available || attempt == 1) return@repeat
                delay(300)
            }
            availability[business] = available
        }
        templateAvailability.clear()
        templateAvailability.putAll(availability)
        debugLog("template availability: ${availability.entries.joinToString { "${it.key}=${it.value}" }}")
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun submitDialog() {
        val pkg = draftPackageName.trim()
        val widget = draftBusiness.trim()
        val editingCard = editingCardId?.let { id -> cards.firstOrNull { it.id == id } }
        val lockedCard = editingCard?.takeIf { !it.renameable }
        if (lockedCard != null && lockedCard.downloadedFromStore) {
            debugLog(
                "submit locked card editor: title=${lockedCard.title}, storeWidgetId=${lockedCard.storeWidgetId}, oldPriority=${lockedCard.priority}, newPriority=${draftPriorityText}"
            )
        }
        if (pkg.isBlank() || widget.isBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.rear_widget_form_invalid),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val card = lockedCard?.copy(
            priority = draftPriorityText.toIntOrNull() ?: lockedCard.priority,
            oneConfigJson = draftOneConfigJson?.takeIf { it.isNotBlank() },
        ) ?: RearCardConfig(
            id = draftCardId,
            title = draftTitle.trim().ifBlank { widget },
            packageName = pkg,
            business = widget,
            oneConfigJson = draftOneConfigJson?.takeIf { it.isNotBlank() },
            enabled = editingCard?.enabled ?: true,
            sticky = draftSticky,
            priority = draftPriorityText.toIntOrNull() ?: 500,
            renameable = editingCard?.renameable ?: true,
            downloadedFromStore = editingCard?.downloadedFromStore ?: false,
            storeWidgetId = editingCard?.storeWidgetId,
            storeWidgetName = editingCard?.storeWidgetName,
            storeReleaseTag = editingCard?.storeReleaseTag,
            storeReleaseAssetName = editingCard?.storeReleaseAssetName,
            storeReleasePublishedAt = editingCard?.storeReleasePublishedAt,
        )

        val editingIndex = cards.indexOfFirst { it.id == card.id }
        if (editingIndex >= 0) {
            cards[editingIndex] = card
        } else {
            cards.add(card)
        }

        val nextSettings = cardOrderSettings.toMutableMap().apply {
            this[card.id] = RearCardOrderSetting(
                automatic = draftAutomaticPriority,
                position = editingIndex.coerceAtLeast(0),
            )
        }
        val reorderedCards = RearCardPriorityManager.assignAutomaticPriorities(
            cards = cards.toList(),
            settings = nextSettings,
        )
        cards.clear()
        cards.addAll(reorderedCards)
        cardOrderSettings.clear()
        cardOrderSettings.putAll(nextSettings)

        if (demo != null) {
            demo.state.cards = reorderedCards
            demo.state.orderSettings = nextSettings
        } else scope.launch(Dispatchers.IO) {
            RearWidgetManagerRepository.saveCardOrderSettings(prefsManager, nextSettings)
            RearWidgetManagerRepository.saveCards(context, prefsManager, reorderedCards)
        }
        showDialog.value = false
        demo?.onAction?.invoke(FeatureGuideAction.SAVE_CARD)
        Toast.makeText(
            context,
            context.getString(R.string.rear_widget_card_saved),
            Toast.LENGTH_SHORT
        ).show()
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun submitAppCardDialog() {
        if (demo != null) return
        val title = appDraftTitle.trim()
        val business = businesses.getOrNull(selectedBusinessIndex)?.business.orEmpty()
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
                showDialog.value = false
                cardPagerState.animateScrollToPage(1)
                appRefreshRevision++
            }
        }
    }

    TemplateConfigRouteTransition(
        target = activeTemplateCard,
        contentKey = { it?.id ?: "card-manager" },
        templateContent = { card ->
            val normalizedBusiness = normalizeTemplateBusinessName(card.business)
            val sourceFilePath = businesses.firstOrNull {
                it.business == card.business || normalizeTemplateBusinessName(it.business) == normalizedBusiness
            }?.filePath.orEmpty()
            WidgetTemplateConfigScreen(
                business = card.business,
                sourceFilePath = sourceFilePath,
                cardStorageKey = card.id,
                currentConfigJson = card.oneConfigJson,
                onBack = { activeTemplateCardId.value = null },
                onSave = { normalizedJson ->
                    val index = cards.indexOfFirst { it.id == card.id }
                    if (index >= 0) {
                        cards[index] =
                            cards[index].copy(oneConfigJson = normalizedJson?.takeIf { it.isNotBlank() })
                        persist()
                    }
                    activeTemplateCardId.value = null
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                if (!embedded) TopAppBar(
                    modifier = Modifier.rearAcrylicEffect(hazeState, hazeStyle),
                    color = Color.Transparent,
                    title = stringResource(R.string.rear_widget_card_manager),
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
                        key(selectedCardTab) {
                            if (selectedCardTab == 0) {
                                IconButton(
                                    onClick = {
                                        if (cardsLoaded) openCreateDialog(0)
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.rear_widget_add_card),
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        if (cardsLoaded) openCreateDialog(1)
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = stringResource(R.string.rear_widget_add_app_card),
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            val pageTopPadding = if (embedded) {
                contentPadding.calculateTopPadding()
            } else {
                paddingValues.calculateTopPadding() + contentPadding.calculateTopPadding()
            }
            val pageBottomPadding =
                paddingValues.calculateBottomPadding() + contentPadding.calculateBottomPadding() + 12.dp
            val pageStartPadding = contentPadding.calculateLeftPadding(layoutDirection)
            val pageEndPadding = contentPadding.calculateRightPadding(layoutDirection)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .rearAcrylicSource(hazeState)
                    .padding(top = pageTopPadding),
            ) {
                TabRowWithContour(
                    tabs = listOf(
                        stringResource(R.string.rear_widget_card_tab_normal),
                        stringResource(R.string.rear_widget_card_tab_app),
                    ),
                    selectedTabIndex = selectedCardTab,
                    onTabSelected = { page ->
                        scope.launch { cardPagerState.animateScrollToPage(page) }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                )
                HorizontalPager(
                    state = cardPagerState,
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { clip = true },
                ) { activeTab ->
                    if (demo != null && activeTab != 0) return@HorizontalPager
                    if (activeTab == 0) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .scrollEndHaptic()
                                .overScrollVertical()
                                .rearAcrylicSource(hazeState)
                                .padding(horizontal = 12.dp),
                            contentPadding = PaddingValues(
                                bottom = pageBottomPadding,
                                start = pageStartPadding,
                                end = pageEndPadding,
                            ),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            overscrollEffect = null,
                        ) {
                if (!embedded) item {
                    Card(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .fillMaxWidth()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            SuperCard(
                                title = stringResource(R.string.rear_widget_card_dialog_hint_title),
                                summary = stringResource(R.string.rear_widget_card_dialog_hint),
                                onClick = {},
                                bottomAction = {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(
                                            text = stringResource(R.string.rear_widget_card_reorder_hint),
                                            fontSize = 12.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                        )
                                        if (cardsLoaded) {
                                            RearBadgeGroup(
                                                badges = listOf(rearWidgetCardCountBadge(cards.size)),
                                            )
                                        }
                                        Button(
                                            onClick = { if (cardsLoaded) openCreateDialog(0) },
                                            enabled = cardsLoaded,
                                            colors = ButtonDefaults.buttonColorsPrimary(),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Add,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 6.dp),
                                            )
                                            Text(text = stringResource(R.string.rear_widget_add_card))
                                        }
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
                    itemsIndexed(
                        items = cards,
                        key = { _, item -> item.id },
                        contentType = { _, _ -> "card_item" },
                    ) { _, item ->
                        val normalizedBusiness = normalizeTemplateBusinessName(item.business)
                        val matchingBusiness = businesses.firstOrNull { business ->
                            business.business == item.business ||
                                    normalizeTemplateBusinessName(business.business) == normalizedBusiness
                        }
                        val hasTemplateConfig =
                            templateAvailability[item.business] == true ||
                                    templateAvailability[normalizedBusiness] == true ||
                                    item.oneConfigJson.isNullOrBlank().not()
                        if (prefsManager.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
                            debugLog(
                                "card template action id=${item.id} business=${item.business} normalized=$normalizedBusiness available=$hasTemplateConfig rawAvailable=${templateAvailability[item.business]} normalizedAvailable=${templateAvailability[normalizedBusiness]} hasConfig=${
                                    item.oneConfigJson.isNullOrBlank().not()
                                }"
                            )
                        }
                        val isHighlighted = highlightedCardId == item.id
                        DraggableItem(
                            key = item.id,
                            state = draggableState,
                        ) { isDragging, hoveredItemKey ->
                        ModuleStyleManagerCard(
                            modifier = Modifier
                                .longPressDraggable(draggableState, item.id)
                                .then(
                                    if (isDragging) {
                                        Modifier.shadow(
                                            elevation = 12.dp,
                                            shape = RoundedCornerShape(20.dp),
                                            clip = false,
                                        )
                                    } else Modifier
                                ),
                            backgroundColor = if (isHighlighted) {
                                MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                            } else if (hoveredItemKey == item.id) {
                                MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                            } else null,
                            title = item.title,
                            badges = buildList {
                                add(rearWidgetPackageBadge(item.packageName))
                                add(rearWidgetBusinessBadge(item.business))
                                add(rearWidgetPriorityBadge(item.priority))
                                if (item.sticky) {
                                    add(rearWidgetStickyBadge())
                                }
                                if (!item.renameable) {
                                    add(rearWidgetLockedBadge())
                                }
                                add(
                                    rearWidgetTemplateStatusBadge(
                                        hasCustomConfig = item.oneConfigJson.isNullOrBlank().not(),
                                    )
                                )
                                addAll(
                                    rearWidgetSourceBadges(
                                        downloadedFromStore = item.downloadedFromStore,
                                        storeWidgetId = item.storeWidgetId,
                                    )
                                )
                            },
                            summaryLines = emptyList(),
                            trailing = {
                                if (item.renameable) {
                                    Switch(
                                        modifier = Modifier.featureGuideAnchor("demo_card_toggle"),
                                        checked = item.enabled,
                                        onCheckedChange = { checked ->
                                            val i = cards.indexOfFirst { it.id == item.id }
                                            if (i >= 0) {
                                                cards[i] = cards[i].copy(enabled = checked)
                                                if (demo != null) {
                                                    demo.state.cards = cards.toList()
                                                    demo.onAction(FeatureGuideAction.TOGGLE_CARD)
                                                } else scope.launch(Dispatchers.IO) {
                                                    RearWidgetManagerRepository.setCardEnabled(
                                                        context = context,
                                                        prefsManager = prefsManager,
                                                        cardId = item.id,
                                                        enabled = checked,
                                                    )
                                                }
                                            }
                                        },
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Outlined.Lock,
                                        contentDescription = null,
                                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    )
                                }
                            },
                            onCardClick = { openEditDialog(item) },
                            leftAction = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ModuleStyleIconAction(
                                        modifier = Modifier.featureGuideAnchor("demo_card"),
                                        icon = Icons.Rounded.EditNote,
                                        onClick = { openEditDialog(item) },
                                    )
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
                                    matchingBusiness?.let { business ->
                                        ModuleStyleIconAction(
                                            icon = MaterialSymbols.Rounded.Deployed_code,
                                            contentDescription = stringResource(R.string.rear_widget_action_open_component),
                                            onClick = { onOpenComponent(business.business) },
                                        )
                                    }
                                    if (hasTemplateConfig) {
                                        ModuleStyleDeleteAction(
                                            icon = Icons.Filled.Tune,
                                            text = stringResource(R.string.rear_widget_action_config),
                                            onClick = { openTemplateConfig(item) },
                                        )
                                    }
                                }
                            },
                            rightAction = {
                                if (item.renameable) {
                                    ModuleStyleDeleteAction(
                                        icon = MiuixIcons.Delete,
                                        text = stringResource(R.string.rear_widget_action_delete),
                                        onClick = {
                                            if (demo != null || !item.renameable) return@ModuleStyleDeleteAction
                                            cards.remove(item)
                                            val nextSettings =
                                                cardOrderSettings.toMutableMap().apply {
                                                    remove(item.id)
                                                }
                                            cardOrderSettings.clear()
                                            cardOrderSettings.putAll(nextSettings)
                                            scope.launch(Dispatchers.IO) {
                                                RearWidgetManagerRepository.saveCardOrderSettings(
                                                    prefsManager,
                                                    nextSettings
                                                )
                                                RearWidgetManagerRepository.saveCards(
                                                    context,
                                                    prefsManager,
                                                    cards.toList()
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                        )
                        }
                    }
                }

                item {
                    if (dataCardsVisible && cards.isEmpty()) {
                        ArtRevealItem(visible = true, delayMillis = 40) {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.rear_widget_empty_card),
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
                        }
                    } else {
                        RearAppCardManagementContent(
                            prefsManager = prefsManager,
                            onOpenStoreDetail = onOpenStoreDetail,
                            modifier = Modifier
                                .fillMaxSize()
                                .scrollEndHaptic()
                                .overScrollVertical()
                                .padding(horizontal = 12.dp),
                            contentPadding = PaddingValues(
                                bottom = pageBottomPadding,
                                start = pageStartPadding,
                                end = pageEndPadding,
                            ),
                            refreshRevision = appRefreshRevision + runtimeRefreshTick,
                        )
                    }
                }
            }
        }

        key(dialogSessionId) {
            RearCardCreateDialog(
                show = showDialog.value && editingCardId == null,
                mode = addModeTab,
                onModeChange = { addModeTab = it },
                businesses = businesses,
                normalTitle = draftTitle,
                onNormalTitleChange = { draftTitle = it },
                normalPackageName = draftPackageName,
                onNormalPackageNameChange = { draftPackageName = it },
                normalBusiness = draftBusiness,
                onNormalBusinessChange = { draftBusiness = it },
                normalPriorityText = draftPriorityText,
                onNormalPriorityTextChange = { draftPriorityText = it },
                normalAutomaticPriority = draftAutomaticPriority,
                onNormalAutomaticPriorityChange = { draftAutomaticPriority = it },
                normalSticky = draftSticky,
                onNormalStickyChange = { draftSticky = it },
                appTitle = appDraftTitle,
                onAppTitleChange = { appDraftTitle = it },
                selectedAppBusinessIndex = selectedBusinessIndex,
                onSelectedAppBusinessIndexChange = { selectedBusinessIndex = it },
                onConfirmNormal = ::submitDialog,
                onConfirmApp = ::submitAppCardDialog,
                onDismissRequest = { showDialog.value = false; demo?.onCancelEdit?.invoke() },
            )

            val editingCard = editingCardId?.let { id -> cards.firstOrNull { it.id == id } }
            val lockedCard = editingCard?.takeIf { !it.renameable }
            OverlayDialog(
                show = showDialog.value && editingCard != null,
                title = stringResource(R.string.rear_widget_edit_card),
                onDismissRequest = { showDialog.value = false; demo?.onCancelEdit?.invoke() },
            ) {
                DialogFormColumn {
                    if (demo != null) {
                        Text(stringResource(R.string.guide_demo_notice))
                        Text(stringResource(R.string.guide_demo_edit_card))
                    }
                    if (lockedCard == null) {
                        TextField(
                            value = draftTitle,
                            onValueChange = { draftTitle = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.rear_widget_card_title),
                            singleLine = true,
                        )
                        TextField(
                            value = draftPackageName,
                            onValueChange = { draftPackageName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.rear_widget_target_package),
                            singleLine = true,
                        )
                        TextField(
                            value = draftBusiness,
                            onValueChange = { draftBusiness = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = stringResource(R.string.rear_widget_business_name),
                            singleLine = true,
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.rear_widget_card_locked_summary),
                                    fontWeight = FontWeight.Medium,
                                    fontSize = 14.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.rear_widget_card_summary,
                                        lockedCard.packageName,
                                        lockedCard.business,
                                        lockedCard.priority,
                                    ),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                    }
                    SwitchPreference(
                        title = stringResource(R.string.rear_widget_priority_mode_auto),
                        summary = stringResource(
                            if (draftAutomaticPriority) R.string.rear_widget_priority_auto_desc
                            else R.string.rear_widget_priority_manual_desc,
                        ),
                        checked = draftAutomaticPriority,
                        onCheckedChange = { draftAutomaticPriority = it },
                    )
                    TextField(
                        value = draftPriorityText,
                        onValueChange = { draftPriorityText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.rear_widget_default_priority),
                        enabled = !draftAutomaticPriority,
                        singleLine = true,
                    )
                    if (lockedCard == null) {
                        SwitchPreference(
                            title = stringResource(R.string.rear_widget_card_sticky),
                            summary = stringResource(R.string.rear_widget_card_sticky_desc),
                            checked = draftSticky,
                            onCheckedChange = { draftSticky = it },
                        )
                    }
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
                            onClick = { showDialog.value = false; demo?.onCancelEdit?.invoke() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.rear_widget_cancel))
                        }
                    }
                }
            }
        }

    }

}
