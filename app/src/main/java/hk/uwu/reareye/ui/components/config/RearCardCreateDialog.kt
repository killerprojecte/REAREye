package hk.uwu.reareye.ui.components.config

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.roundToIntRect
import androidx.compose.ui.res.stringResource
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.mixui.components.GlassFormDialog
import hk.uwu.mixui.components.DialogActions
import hk.uwu.mixui.components.Tabs
import hk.uwu.mixui.components.Text
import androidx.compose.ui.platform.testTag
import hk.uwu.mixui.components.SuggestionMenu
import hk.uwu.mixui.components.TextField
import hk.uwu.mixui.components.SwitchPreference
import hk.uwu.mixui.components.SpinnerPreference

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
    submitting: Boolean = false,
) {
    val safeMode = mode.coerceIn(0, 1)
    var formBounds by remember { mutableStateOf(IntRect.Zero) }
    var suggestionsExpanded by remember(show, safeMode) { mutableStateOf(false) }
    val businessSnapshot = businesses.toList()
    val autocompleteBusinesses = remember(businessSnapshot) {
        businessSnapshot
            .distinctBy { it.business }
            .sortedBy { it.business.lowercase() }
    }
    GlassFormDialog(
        modifier = Modifier.testTag("rear-card-create-dialog"),
        show = show,
        title = stringResource(
            if (safeMode == 1) R.string.rear_widget_add_app_card else R.string.rear_widget_add_card,
        ),
        onDismissRequest = {
            // This dialog owns Back. A non-focusable suggestion window must not
            // take IME focus merely to consume it. Close suggestions first.
            if (suggestionsExpanded && autocompleteBusinesses.any {
                    it.business.contains(normalBusiness.trim(), ignoreCase = true)
                }) suggestionsExpanded = false
            else onDismissRequest()
        },
    ) {
        // The native form is an in-tree overlay. Measure its clipped, scrollable
        // viewport in this window, not the old Android Dialog window or the page.
        DialogFormColumn(Modifier.testTag("rear-card-create-form").onGloballyPositioned {
            formBounds = it.boundsInWindow().roundToIntRect()
        }) {
            Tabs(
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
                    modifier = Modifier.fillMaxWidth().testTag("rear-card-create-app-title"),
                    label = stringResource(R.string.rear_widget_app_card_name),
                    singleLine = true,
                )
                if (businesses.isNotEmpty()) {
                    val safeBusinessIndex =
                        selectedAppBusinessIndex.coerceIn(0, businesses.lastIndex)
                    SpinnerPreference(
                        modifier = Modifier.testTag("rear-card-business-selector"),
                        options = businesses.map { business -> business.business },
                        selectedIndex = safeBusinessIndex,
                        title = stringResource(R.string.rear_widget_app_bound_component_title),
                        summary = businesses[safeBusinessIndex].business,
                        onSelectedIndexChange = onSelectedAppBusinessIndexChange,
                    )
                } else {
                    Text(stringResource(R.string.rear_widget_app_no_component))
                }
                RearCardDialogButtons(
                    confirmEnabled = !submitting && appTitle.isNotBlank() && businesses.isNotEmpty(),
                    onConfirm = onConfirmApp,
                    onCancel = onDismissRequest,
                )
            } else {
                TextField(
                    value = normalTitle,
                    onValueChange = onNormalTitleChange,
                    modifier = Modifier.fillMaxWidth().testTag("rear-card-create-title"),
                    label = stringResource(R.string.rear_widget_card_title),
                    singleLine = true,
                )
                TextField(
                    value = normalPackageName,
                    onValueChange = onNormalPackageNameChange,
                    modifier = Modifier.fillMaxWidth().testTag("rear-card-create-package"),
                    label = stringResource(R.string.rear_widget_target_package),
                    singleLine = true,
                )
                RearBusinessAutocompleteTextField(
                    value = normalBusiness,
                    expanded = suggestionsExpanded,
                    onExpandedChange = { suggestionsExpanded = it },
                    businesses = autocompleteBusinesses,
                    boundsInWindow = formBounds,
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
                    modifier = Modifier.fillMaxWidth().testTag("rear-card-create-priority"),
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
                    confirmEnabled = !submitting && normalPackageName.isNotBlank() && normalBusiness.isNotBlank(),
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
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    businesses: List<RearBusinessConfig>,
    boundsInWindow: IntRect,
    onValueChange: (String) -> Unit,
    onSelect: (RearBusinessConfig) -> Unit,
) {
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

    SuggestionMenu(
        expanded = expanded,
        options = suggestions.map { it.business },
        selectedIndex = suggestions.indexOfFirst { it.business.equals(value, ignoreCase = true) },
        onSelectedIndexChange = { index ->
            suggestions.getOrNull(index)?.let(onSelect)
            onExpandedChange(false)
        },
        onDismissRequest = { onExpandedChange(false) },
        boundsInWindow = boundsInWindow,
    ) {
        TextField(
            value = value,
            onValueChange = { nextValue ->
                onValueChange(nextValue)
                onExpandedChange(true)
            },
            modifier = Modifier.fillMaxWidth().testTag("rear-card-business-input")
                .onFocusChanged { onExpandedChange(it.isFocused) },
            label = stringResource(R.string.rear_widget_business_name),
            singleLine = true,
        )
    }
}

@Composable
private fun RearCardDialogButtons(
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    DialogActions(
        modifier = Modifier.testTag("rear-card-create-actions"),
        cancelText = stringResource(R.string.rear_widget_cancel),
        confirmText = stringResource(R.string.rear_widget_confirm),
        onCancel = onCancel,
        onConfirm = onConfirm,
        confirmEnabled = confirmEnabled,
    )
}
