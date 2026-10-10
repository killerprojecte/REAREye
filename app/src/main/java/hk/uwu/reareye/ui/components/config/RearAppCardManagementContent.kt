package hk.uwu.reareye.ui.components.config

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.rounded.Storefront
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearAppCardRepository
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.repository.rearwidget.RearWidgetManagerRepository
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.ui.components.OverlayDialog
import hk.uwu.reareye.ui.components.card.ModuleStyleDeleteAction
import hk.uwu.reareye.ui.components.card.ModuleStyleIconAction
import hk.uwu.reareye.ui.components.card.ModuleStyleManagerCard
import hk.uwu.reareye.ui.components.config.draggable.library.draggable.DraggableItem
import hk.uwu.reareye.ui.components.config.draggable.library.draggable.rememberDraggableLazyListState
import hk.uwu.reareye.ui.components.config.draggable.longPressDraggable
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.widgetapi.RearAppCardInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.theme.MiuixTheme

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
internal fun RearAppCardManagementContent(
    prefsManager: PrefsManager,
    onOpenStoreDetail: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    refreshRevision: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val cards = remember { mutableStateListOf<RearAppCardInfo>() }
    val businesses = remember { mutableStateListOf<RearBusinessConfig>() }
    var loading by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var editingCard by remember { mutableStateOf<RearAppCardInfo?>(null) }
    var draftTitle by remember { mutableStateOf("") }
    var orderChanged by remember { mutableStateOf(false) }
    var orderSaving by remember { mutableStateOf(false) }
    val previewRenderingIds = remember { mutableStateListOf<String>() }

    fun reload() {
        scope.launch {
            loading = true
            errorText = null
            val loaded = withContext(Dispatchers.IO) {
                RearAppCardRepository.loadCatalog(context)
            }
            val loadedBusinesses = withContext(Dispatchers.IO) {
                RearWidgetManagerRepository.loadBusinesses(prefsManager)
            }
            cards.clear()
            cards.addAll(loaded)
            businesses.clear()
            businesses.addAll(loadedBusinesses)
            loading = false
        }
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun showResult(success: Boolean, error: String?, successMessage: String) {
        Toast.makeText(
            context,
            if (success) successMessage else error.orEmpty().ifBlank {
                context.getString(R.string.rear_widget_app_operation_failed)
            },
            Toast.LENGTH_SHORT,
        ).show()
    }

    val draggableState = rememberDraggableLazyListState(
        state = listState,
        onSwap = { from, to ->
            val fromId = (from.key as? String)?.removePrefix("app_card_")
                ?: return@rememberDraggableLazyListState
            val toId = (to.key as? String)?.removePrefix("app_card_")
                ?: return@rememberDraggableLazyListState
            val fromIndex = cards.indexOfFirst { it.appId == fromId }
            val toIndex = cards.indexOfFirst { it.appId == toId }
            if (
                fromIndex >= 0 &&
                toIndex >= 0 &&
                fromIndex != toIndex &&
                cards[fromIndex].ownedByRearEye
            ) {
                val moved = cards.removeAt(fromIndex)
                cards.add(toIndex, moved)
                orderChanged = true
            }
        },
        isItemLocked = { item ->
            val appId = (item.key as? String)?.removePrefix("app_card_")
            appId == null || cards.none { it.appId == appId }
        },
        onDragFinished = {
            if (!orderChanged || orderSaving) return@rememberDraggableLazyListState
            orderChanged = false
            val orderedAppIds = cards.map { it.appId }
            scope.launch {
                orderSaving = true
                val result = withContext(Dispatchers.IO) {
                    RearAppCardRepository.reorder(context, orderedAppIds)
                }
                showResult(
                    result.success,
                    result.error,
                    context.getString(R.string.rear_widget_app_order_updated),
                )
                orderSaving = false
                if (!result.success) reload()
            }
        },
    )

    LaunchedEffect(refreshRevision, prefsManager) { reload() }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        overscrollEffect = null,
    ) {
        if (loading) {
            item(key = "app_card_loading") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        InfiniteProgressIndicator()
                        Text(stringResource(R.string.rear_widget_loading_data))
                    }
                }
            }
        } else if (cards.isEmpty()) {
            item(key = "app_card_empty") {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = errorText ?: stringResource(R.string.rear_widget_empty_app_card),
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        itemsIndexed(
            items = cards,
            key = { _, card -> "app_card_" + card.appId },
            contentType = { _, _ -> "app_card_item" },
        ) { _, card ->
            val itemKey = "app_card_" + card.appId
            val relatedBusiness = card.componentBusiness?.let { component ->
                businesses.firstOrNull { it.business == component }
            }
            val storeWidgetId = relatedBusiness?.storeWidgetId
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            DraggableItem(
                key = itemKey,
                state = draggableState,
            ) { isDragging, hoveredItemKey ->
                ModuleStyleManagerCard(
                    modifier = Modifier
                        .then(
                            if (card.ownedByRearEye && !orderSaving) {
                                Modifier.longPressDraggable(draggableState, itemKey)
                            } else {
                                Modifier
                            }
                        )
                        .then(
                            if (isDragging) {
                                Modifier.shadow(
                                    elevation = 12.dp,
                                    shape = RoundedCornerShape(20.dp),
                                    clip = false,
                                )
                            } else {
                                Modifier
                            }
                        ),
                    backgroundColor = if (hoveredItemKey == itemKey) {
                        MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                    } else {
                        null
                    },
                    title = card.title.ifBlank { card.appId },
                    summaryLines = emptyList(),
                    badges = buildList {
                        add(rearWidgetAppCardBadge())
                        if (card.ownedByRearEye) {
                            add(rearWidgetRearEyeBadge())
                        } else {
                            add(rearWidgetExternalBadge())
                        }
                        if (!card.canRename && !card.canDelete) {
                            add(rearWidgetReadOnlyBadge())
                        }
                        card.componentBusiness?.let { component ->
                            add(rearWidgetComponentBadge(component))
                            relatedBusiness?.let { business ->
                                addAll(
                                    rearWidgetSourceBadges(
                                        downloadedFromStore = business.downloadedFromStore,
                                        storeWidgetId = business.storeWidgetId,
                                    )
                                )
                            }
                        }
                    },
                    onCardClick = if (card.canRename) {
                        {
                            draftTitle = card.title
                            editingCard = card
                        }
                    } else {
                        null
                    },
                    leftAction = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (card.canRenderPreview) {
                                ModuleStyleIconAction(
                                    icon = Icons.Outlined.PhotoCamera,
                                    contentDescription = stringResource(
                                        R.string.rear_widget_app_render_preview,
                                    ),
                                    onClick = {
                                        if (card.appId in previewRenderingIds) {
                                            return@ModuleStyleIconAction
                                        }
                                        previewRenderingIds += card.appId
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                RearAppCardRepository.renderPreview(
                                                    context,
                                                    card.appId,
                                                )
                                            }
                                            previewRenderingIds -= card.appId
                                            showResult(
                                                result.success,
                                                result.error,
                                                context.getString(
                                                    R.string.rear_widget_app_preview_rendered,
                                                ),
                                            )
                                            if (result.success) {
                                                delay(350)
                                                reload()
                                            }
                                        }
                                    },
                                )
                            }
                            if (card.canRename) {
                                ModuleStyleIconAction(
                                    icon = Icons.Rounded.EditNote,
                                    contentDescription = stringResource(
                                        R.string.rear_widget_edit_app_card,
                                    ),
                                    onClick = {
                                        draftTitle = card.title
                                        editingCard = card
                                    },
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Outlined.Lock,
                                    contentDescription = null,
                                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                            storeWidgetId?.let { widgetId ->
                                ModuleStyleIconAction(
                                    icon = MaterialSymbols.Rounded.Storefront,
                                    contentDescription = stringResource(
                                        R.string.rear_store_open_detail,
                                    ),
                                    onClick = { onOpenStoreDetail(widgetId) },
                                )
                            }
                        }
                    },
                    rightAction = {
                        if (card.canDelete) {
                            ModuleStyleDeleteAction(
                                icon = MiuixIcons.Delete,
                                text = stringResource(R.string.rear_widget_action_delete),
                                onClick = {
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            RearAppCardRepository.delete(context, card.appId)
                                        }
                                        showResult(
                                            result.success,
                                            result.error,
                                            context.getString(R.string.rear_widget_app_deleted),
                                        )
                                        if (result.success) {
                                            delay(350)
                                            reload()
                                        }
                                    }
                                },
                            )
                        }
                    },
                )
            }
        }

        errorText?.takeIf { cards.isNotEmpty() }?.let { message ->
            item(key = "app_card_error") {
                Text(
                    text = message,
                    color = Color(0xFFD32F2F),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }

    val editing = editingCard
    OverlayDialog(
        show = editing != null,
        title = stringResource(R.string.rear_widget_edit_app_card),
        onDismissRequest = { editingCard = null },
    ) {
        DialogFormColumn {
            TextField(
                value = draftTitle,
                onValueChange = { draftTitle = it },
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(R.string.rear_widget_app_card_name),
                singleLine = true,
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = draftTitle.isNotBlank(),
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = {
                    val target = editing ?: return@Button
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            RearAppCardRepository.rename(context, target.appId, draftTitle)
                        }
                        showResult(
                            result.success,
                            result.error,
                            context.getString(R.string.rear_widget_app_name_updated),
                        )
                        if (result.success) {
                            editingCard = null
                            delay(350)
                            reload()
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.rear_widget_confirm))
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = { editingCard = null },
            ) {
                Text(stringResource(R.string.rear_widget_cancel))
            }
        }
    }
}
