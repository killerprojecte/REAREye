package hk.uwu.reareye.ui.components.config

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.ui.components.LocalOverlayDialogBounds
import hk.uwu.reareye.ui.components.OverlayDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RearCardCreateDialog(
    show: Boolean,
    mode: Int,
    onModeChange: (Int) -> Unit,
    businesses: List<RearBusinessConfig>,
    normalTitle: String,
    onNormalTitleChange: (String) -> Unit,
    normalPackageName: String,
    onNormalPackageNameChange: (String) -> Unit,
    normalBusiness: String,
    onNormalBusinessChange: (String) -> Unit,
    normalPriorityText: String,
    onNormalPriorityTextChange: (String) -> Unit,
    normalAutomaticPriority: Boolean,
    onNormalAutomaticPriorityChange: (Boolean) -> Unit,
    normalSticky: Boolean,
    onNormalStickyChange: (Boolean) -> Unit,
    appTitle: String,
    onAppTitleChange: (String) -> Unit,
    selectedAppBusinessIndex: Int,
    onSelectedAppBusinessIndexChange: (Int) -> Unit,
    onConfirmNormal: () -> Unit,
    onConfirmApp: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val safeMode = mode.coerceIn(0, 1)
    val businessSnapshot = businesses.toList()
    val autocompleteBusinesses = remember(businessSnapshot) {
        businessSnapshot
            .distinctBy { it.business }
            .sortedBy { it.business.lowercase() }
    }
    val autocompleteDensity = LocalDensity.current
    val autocompleteTextMeasurer = rememberTextMeasurer()
    val autocompleteTextStyle = TextStyle(
        fontSize = MiuixTheme.textStyles.body1.fontSize,
        fontWeight = FontWeight.Medium,
    )
    val autocompleteWidth = remember(
        autocompleteBusinesses,
        autocompleteDensity,
        autocompleteTextMeasurer,
        autocompleteTextStyle,
    ) {
        val widestTextPx = autocompleteBusinesses.maxOfOrNull { business ->
            autocompleteTextMeasurer.measure(
                text = AnnotatedString(business.business),
                style = autocompleteTextStyle,
                maxLines = 1,
                softWrap = false,
            ).size.width
        } ?: 0
        with(autocompleteDensity) {
            (
                    widestTextPx.toDp() +
                            DropdownDefaults.DialogHorizontalPadding * 2 +
                            DropdownDefaults.CheckIconStartPadding +
                            DropdownDefaults.CheckIconSize
                    ).coerceIn(ListPopupDefaults.MinWidth, 288.dp)
        }
    }
    OverlayDialog(
        show = show,
        title = stringResource(
            if (safeMode == 1) R.string.rear_widget_add_app_card else R.string.rear_widget_add_card,
        ),
        onDismissRequest = onDismissRequest,
    ) {
        DialogFormColumn {
            TabRowWithContour(
                tabs = listOf(
                    stringResource(R.string.rear_widget_card_tab_normal),
                    stringResource(R.string.rear_widget_card_tab_app),
                ),
                selectedTabIndex = safeMode,
                onTabSelected = onModeChange,
                modifier = Modifier.fillMaxWidth(),
            )

            if (safeMode == 1) {
                TextField(
                    value = appTitle,
                    onValueChange = onAppTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.rear_widget_app_card_name),
                    singleLine = true,
                )
                if (businesses.isNotEmpty()) {
                    val safeBusinessIndex =
                        selectedAppBusinessIndex.coerceIn(0, businesses.lastIndex)
                    WindowSpinnerPreference(
                        items = businesses.map { business -> DropdownItem(title = business.business) },
                        selectedIndex = safeBusinessIndex,
                        title = stringResource(R.string.rear_widget_app_bound_component_title),
                        summary = businesses[safeBusinessIndex].business,
                        onSelectedIndexChange = onSelectedAppBusinessIndexChange,
                    )
                } else {
                    Text(stringResource(R.string.rear_widget_app_no_component))
                }
                RearCardDialogButtons(
                    confirmEnabled = appTitle.isNotBlank() && businesses.isNotEmpty(),
                    onConfirm = onConfirmApp,
                    onCancel = onDismissRequest,
                )
            } else {
                TextField(
                    value = normalTitle,
                    onValueChange = onNormalTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.rear_widget_card_title),
                    singleLine = true,
                )
                TextField(
                    value = normalPackageName,
                    onValueChange = onNormalPackageNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.rear_widget_target_package),
                    singleLine = true,
                )
                RearBusinessAutocompleteTextField(
                    value = normalBusiness,
                    businesses = autocompleteBusinesses,
                    suggestionWidth = autocompleteWidth,
                    onValueChange = onNormalBusinessChange,
                    onSelect = { business ->
                        onNormalBusinessChange(business.business)
                        onNormalPriorityTextChange(business.defaultPriority.toString())
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.rear_widget_priority_mode_auto),
                    summary = stringResource(
                        if (normalAutomaticPriority) R.string.rear_widget_priority_auto_desc
                        else R.string.rear_widget_priority_manual_desc,
                    ),
                    checked = normalAutomaticPriority,
                    onCheckedChange = onNormalAutomaticPriorityChange,
                )
                TextField(
                    value = normalPriorityText,
                    onValueChange = onNormalPriorityTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.rear_widget_default_priority),
                    enabled = !normalAutomaticPriority,
                    singleLine = true,
                )
                SwitchPreference(
                    title = stringResource(R.string.rear_widget_card_sticky),
                    summary = stringResource(R.string.rear_widget_card_sticky_desc),
                    checked = normalSticky,
                    onCheckedChange = onNormalStickyChange,
                )
                RearCardDialogButtons(
                    confirmEnabled = normalPackageName.isNotBlank() && normalBusiness.isNotBlank(),
                    onConfirm = onConfirmNormal,
                    onCancel = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun RearBusinessAutocompleteTextField(
    value: String,
    businesses: List<RearBusinessConfig>,
    suggestionWidth: Dp,
    onValueChange: (String) -> Unit,
    onSelect: (RearBusinessConfig) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val suggestionShape = RoundedCornerShape(16.dp)
    val dialogBounds = LocalOverlayDialogBounds.current
    val density = LocalDensity.current
    val dialogInsetPx = with(density) { 8.dp.roundToPx() }
    val defaultPositionProvider = ListPopupDefaults.DropdownPositionProvider
    val boundedPositionProvider = remember(dialogBounds, dialogInsetPx) {
        object : PopupPositionProvider {
            override fun getMargins(): PaddingValues = defaultPositionProvider.getMargins()

            override fun calculatePosition(
                anchorBounds: IntRect,
                windowBounds: IntRect,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
                popupMargin: IntRect,
                alignment: PopupPositionProvider.Align,
            ): IntOffset {
                val boundedWindow = if (dialogBounds != IntRect.Zero) {
                    val left = maxOf(windowBounds.left, dialogBounds.left + dialogInsetPx)
                    val top = maxOf(windowBounds.top, dialogBounds.top + dialogInsetPx)
                    val right = minOf(windowBounds.right, dialogBounds.right - dialogInsetPx)
                    val bottom = minOf(windowBounds.bottom, dialogBounds.bottom - dialogInsetPx)
                    if (right > left && bottom > top) {
                        IntRect(left = left, top = top, right = right, bottom = bottom)
                    } else {
                        windowBounds
                    }
                } else {
                    windowBounds
                }
                return defaultPositionProvider.calculatePosition(
                    anchorBounds = anchorBounds,
                    windowBounds = boundedWindow,
                    layoutDirection = layoutDirection,
                    popupContentSize = popupContentSize,
                    popupMargin = popupMargin,
                    alignment = alignment,
                )
            }
        }
    }
    val suggestionMaxHeight = if (dialogBounds == IntRect.Zero) {
        DropdownDefaults.MinHeight * 3
    } else {
        with(density) {
            (dialogBounds.height - dialogInsetPx * 2)
                .coerceAtLeast(50.dp.roundToPx())
                .toDp()
                .coerceAtMost(DropdownDefaults.MinHeight * 3)
        }
    }
    val normalizedQuery = value.trim()
    val suggestions = remember(businesses, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            businesses
        } else {
            businesses.filter { business ->
                business.business.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        TextField(
            value = value,
            onValueChange = { nextValue ->
                onValueChange(nextValue)
                expanded = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focusState ->
                    expanded = focusState.isFocused
                },
            label = stringResource(R.string.rear_widget_business_name),
            singleLine = true,
        )

        OverlayListPopup(
            show = expanded && suggestions.isNotEmpty(),
            popupModifier = Modifier,
            popupPositionProvider = boundedPositionProvider,
            alignment = PopupPositionProvider.Align.Start,
            enableWindowDim = false,
            onDismissRequest = { expanded = false },
            maxHeight = suggestionMaxHeight,
            minWidth = ListPopupDefaults.MinWidth,
            renderInRootScaffold = false,
        ) {
            Box(
                modifier = Modifier
                    .clip(suggestionShape)
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        width = 1.dp,
                        color = MiuixTheme.colorScheme.outline.copy(alpha = 0.32f),
                        shape = suggestionShape,
                    ),
            ) {
                CompositionLocalProvider(LocalOverscrollFactory provides null) {
                    LazyColumn(
                        modifier = Modifier
                            .width(suggestionWidth)
                            .heightIn(max = suggestionMaxHeight),
                    ) {
                        itemsIndexed(
                            items = suggestions,
                            key = { _, business -> business.business },
                        ) { index, business ->
                            DropdownImpl(
                                item = DropdownItem(title = business.business),
                                optionSize = suggestions.size,
                                isSelected = business.business.equals(value, ignoreCase = true),
                                index = index,
                                dropdownColors = DropdownDefaults.dropdownColors(),
                                dialogMode = true,
                                onSelectedIndexChange = {
                                    onSelect(business)
                                    expanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RearCardDialogButtons(
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
}
