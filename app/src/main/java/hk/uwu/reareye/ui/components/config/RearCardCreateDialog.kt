package hk.uwu.reareye.ui.components.config

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
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
import hk.uwu.mixui.components.GlassEditorSheet
import hk.uwu.mixui.components.Tabs
import hk.uwu.mixui.components.Text
import androidx.compose.ui.platform.testTag
import hk.uwu.mixui.components.SuggestionMenu
import hk.uwu.mixui.components.EditorTextField
import hk.uwu.mixui.components.SwitchPreference
import hk.uwu.mixui.components.PreferenceRow
import androidx.compose.runtime.snapshots.SnapshotStateList

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
    sessionKey: Any? = Unit,
) {
    val safeMode = mode.coerceIn(0, 1)
    var formBounds by remember { mutableStateOf(IntRect.Zero) }
    var suggestionsExpanded by remember(show, safeMode, sessionKey) { mutableStateOf(false) }
    var suggestionsVisible by remember(show, safeMode, sessionKey) { mutableStateOf(false) }
    var bindingExpanded by remember(show, safeMode, sessionKey) { mutableStateOf(false) }
    var bindingVisible by remember(show, safeMode, sessionKey) { mutableStateOf(false) }
    // Managers own SnapshotStateLists: their immutable snapshot is O(1), and still
    // observes in-place catalog updates. Ordinary mutable lists retain copy semantics.
    val businessSnapshot = if (businesses is SnapshotStateList) businesses.toList() else businesses.toList()
    val autocompleteCatalog = remember(businessSnapshot) { RearBusinessSuggestions(businessSnapshot) }
    val bindingOptions = remember(businessSnapshot) { businessSnapshot.map { it.business } }
    GlassEditorSheet(
        // Suggestions follow this visible viewport, but glass is drawn in the
        // same-window overlay above the sheet, outside the content recording.
        modifier = Modifier.testTag("rear-card-create-form").onGloballyPositioned {
            formBounds = it.boundsInWindow().roundToIntRect()
        },
        show = show,
        title = stringResource(
            if (safeMode == 1) R.string.rear_widget_add_app_card else R.string.rear_widget_add_card,
        ),
        sessionKey = sessionKey,
        dismissRequestKey = suggestionsExpanded || bindingExpanded,
        confirmText = stringResource(R.string.rear_widget_confirm),
        cancelText = stringResource(R.string.rear_widget_cancel),
        confirmEnabled = !submitting && if (safeMode == 1) {
            appTitle.isNotBlank() && businesses.isNotEmpty()
        } else {
            normalPackageName.isNotBlank() && normalBusiness.isNotBlank()
        },
        onConfirm = { if (safeMode == 1) onConfirmApp() else onConfirmNormal() },
        // Cancel/scrim/drag dismiss the form. Only Back is intercepted below
        // for suggestions; swallowing a native drag dismissal strands its offset.
        onDismissRequest = onDismissRequest,
    ) {
        // Register inside the sheet content, after its native Back handler. A
        // suggestion dismissal must not start the native sheet hide animation.
        BackHandler(enabled = show && (suggestionsVisible || bindingVisible)) {
            suggestionsExpanded = false
            bindingExpanded = false
        }
        Column(
            modifier = Modifier.testTag("rear-card-create-dialog"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
                EditorTextField(
                    value = appTitle,
                    onValueChange = onAppTitleChange,
                    modifier = Modifier.fillMaxWidth().testTag("rear-card-create-app-title"),
                    label = stringResource(R.string.rear_widget_app_card_name),
                    singleLine = true,
                )
                if (businesses.isNotEmpty()) {
                    val safeBusinessIndex =
                        selectedAppBusinessIndex.coerceIn(0, businesses.lastIndex)
                    SuggestionMenu(
                        expanded = bindingExpanded,
                        options = bindingOptions,
                        selectedIndex = safeBusinessIndex,
                        onSelectedIndexChange = { index ->
                            if (bindingExpanded && index in businesses.indices) {
                                bindingExpanded = false
                                onSelectedAppBusinessIndexChange(index)
                            }
                        },
                        onDismissRequest = { bindingExpanded = false },
                        onVisibilityChanged = { bindingVisible = it },
                        boundsInWindow = formBounds,
                        maxHeight = 280.dp,
                    ) {
                        PreferenceRow(
                            modifier = Modifier.testTag("rear-card-business-selector"),
                            title = stringResource(R.string.rear_widget_app_bound_component_title),
                            summary = businesses[safeBusinessIndex].business,
                            onClick = { bindingExpanded = !bindingExpanded },
                        )
                    }
                } else {
                    Text(stringResource(R.string.rear_widget_app_no_component))
                }
            } else {
                EditorTextField(
                    value = normalTitle,
                    onValueChange = onNormalTitleChange,
                    modifier = Modifier.fillMaxWidth().testTag("rear-card-create-title"),
                    label = stringResource(R.string.rear_widget_card_title),
                    singleLine = true,
                )
                EditorTextField(
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
                    onVisibilityChanged = { suggestionsVisible = it },
                    catalog = autocompleteCatalog,
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
                EditorTextField(
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
            }
        }
    }
}

@Composable
private fun RearBusinessAutocompleteTextField(
    value: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onVisibilityChanged: (Boolean) -> Unit,
    catalog: RearBusinessSuggestions,
    boundsInWindow: IntRect,
    onValueChange: (String) -> Unit,
    onSelect: (RearBusinessConfig) -> Unit,
) {
    val normalizedQuery = value.trim()
    val suggestions = remember(catalog, normalizedQuery) { catalog.filter(normalizedQuery) }
    val selectedIndex = remember(suggestions, value) {
        suggestions.entries.indexOfFirst { it.business.equals(value, ignoreCase = true) }
    }
    val currentSuggestions by androidx.compose.runtime.rememberUpdatedState(suggestions.entries)
    val selectNow by androidx.compose.runtime.rememberUpdatedState(onSelect)
    SuggestionMenu(
        expanded = expanded,
        options = suggestions.labels,
        optionSummaries = suggestions.packages,
        selectedIndex = selectedIndex,
        onSelectedIndexChange = { index ->
            currentSuggestions.getOrNull(index)?.let(selectNow)
            onExpandedChange(false)
        },
        onDismissRequest = { onExpandedChange(false) },
        onVisibilityChanged = onVisibilityChanged,
        boundsInWindow = boundsInWindow,
    ) {
        EditorTextField(
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

/** Snapshot-scoped index: sort once, then preserve that order in linear match buckets. */
internal class RearBusinessSuggestions(businesses: List<RearBusinessConfig>) {
    private val all = RearBusinessSuggestionResult(businesses.distinctBy { it.business }
        .map { it to it.business.lowercase() }.sortedBy { it.second }.map { it.first })

    fun filter(query: String): RearBusinessSuggestionResult {
        val normalized = query.trim()
        if (normalized.isEmpty()) return all
        val exact = ArrayList<RearBusinessConfig>()
        val prefix = ArrayList<RearBusinessConfig>()
        val contains = ArrayList<RearBusinessConfig>()
        for (business in all.entries) {
            val name = business.business
            when {
                name.equals(normalized, ignoreCase = true) -> exact.add(business)
                name.startsWith(normalized, ignoreCase = true) -> prefix.add(business)
                name.contains(normalized, ignoreCase = true) -> contains.add(business)
            }
        }
        return RearBusinessSuggestionResult(exact + prefix + contains)
    }
}

internal class RearBusinessSuggestionResult(val entries: List<RearBusinessConfig>) {
    val labels = entries.map { it.business }
    val packages = entries.map { it.packageName }
}
