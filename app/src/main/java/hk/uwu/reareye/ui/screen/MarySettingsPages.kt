package hk.uwu.reareye.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.components.BottomSheetActionButton
import hk.uwu.mixui.components.Button
import hk.uwu.mixui.components.Card
import hk.uwu.mixui.components.CardDefaults
import hk.uwu.mixui.components.CircularProgressIndicator
import hk.uwu.mixui.components.DialogActions
import hk.uwu.mixui.components.EditorTextField
import hk.uwu.mixui.components.FormDialog
import hk.uwu.mixui.components.GlassEditorSheet
import hk.uwu.mixui.components.GlassSelectPreference
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.InlineGlassTabs
import hk.uwu.mixui.components.ListItem
import hk.uwu.mixui.components.PreferenceSupportingText
import hk.uwu.mixui.components.SuggestionMenu
import hk.uwu.mixui.components.SwitchPreference
import hk.uwu.mixui.components.Text
import hk.uwu.mixui.components.TextField
import hk.uwu.mixui.theme.MixTheme
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.catalog.ReasoningCatalogIndex
import hk.uwu.reareye.agent.data.MaryModelCapability
import hk.uwu.reareye.agent.data.MaryModelValueSource
import hk.uwu.reareye.ui.components.MaryApprovalModePreference
import hk.uwu.reareye.ui.components.MarySettingsDiagnostic
import hk.uwu.reareye.ui.components.MarySettingsHint
import hk.uwu.reareye.ui.components.MarySettingsSectionTitle
import hk.uwu.reareye.ui.components.ReasoningConfiguration
import hk.uwu.mixui.icons.Icons as MixIcons

@Composable
private fun MaryRunMode(agentMode: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MarySettingsSectionTitle(R.string.mary_phase_run_mode)
        InlineGlassTabs(
            tabs = listOf(stringResource(R.string.mary_phase_chat_mode), stringResource(R.string.mary_phase_agent_mode)),
            selectedTabIndex = if (agentMode) 1 else 0,
            onTabSelected = { if (enabled) onChange(it == 1) },
            modifier = Modifier.fillMaxWidth().testTag("mary-settings-mode-tabs"),
            surfaceColor = MixTheme.colorScheme.surface,
        )
    }
}

@Composable
internal fun MaryAgentPage(state: MarySettingsDraft, enabled: Boolean, onChange: (MarySettingsDraft) -> Unit) {
    val namedModels = state.models.filter { it.draft.provider.model.isNotBlank() }
    val unselected = stringResource(R.string.mary_phase_no_model)
    val untitled = stringResource(R.string.mary_phase_untitled_model)
    val mainModels = state.models.filter { it.id == state.mainId || it.draft.provider.model.isNotBlank() }
    MaryRunMode(state.agentMode, enabled) { onChange(state.copy(agentMode = it)) }
    MarySettingsGroup(R.string.mary_phase_main_agent, Modifier.testTag("mary-model-card-${R.string.mary_settings_provider}")) {
        MaryChoice(
            R.string.mary_phase_main_model, listOf(unselected) + mainModels.map { it.name.ifBlank { untitled } },
            mainModels.indexOfFirst { it.id == state.mainId } + 1,
            Modifier.testTag("mary-settings-main-model"), enabled = enabled && mainModels.isNotEmpty(),
        ) { index -> onChange(state.copy(mainId = mainModels.getOrNull(index - 1)?.id)) }
    }
    MarySettingsGroup(R.string.mary_approval_policy) {
        MaryApprovalModePreference(mode = state.approvalMode,
            enabled = enabled, onSelected = { onChange(state.copy(approvalMode = it)) })
        SwitchPreference(
            title = stringResource(R.string.mary_approval_inherit), checked = state.inheritApprovalModel,
            modifier = Modifier.testTag("mary-settings-approval-inherit"), enabled = enabled,
            onCheckedChange = { onChange(state.copy(inheritApprovalModel = it)) },
        )
        if (!state.inheritApprovalModel) {
            val approvalModels = state.models.filter { it.id == state.approvalId || it.draft.provider.model.isNotBlank() }
            MaryChoice(
                R.string.mary_approval_model, listOf(unselected) + approvalModels.map { it.name.ifBlank { untitled } },
                approvalModels.indexOfFirst { it.id == state.approvalId } + 1,
                Modifier.testTag("mary-settings-approval-model"), enabled,
            ) { index -> onChange(state.copy(approvalId = approvalModels.getOrNull(index - 1)?.id)) }
        }
    }
    MarySettingsGroup(R.string.mary_phase_subagents) {
        MaryChoice(
            R.string.mary_phase_subagent_default,
            listOf(stringResource(R.string.mary_phase_use_main)) + namedModels.map { it.name },
            namedModels.indexOfFirst { it.id == state.subagentId } + 1,
            Modifier.testTag("mary-settings-subagent-model"), enabled,
        ) { index -> onChange(state.copy(subagentId = namedModels.getOrNull(index - 1)?.id)) }
    }
    MarySettingsGroup(R.string.mary_settings_web) {
        SwitchPreference(
            title = stringResource(R.string.mary_settings_web_enabled), checked = state.webEnabled,
            modifier = Modifier.testTag("mary-settings-web-enabled"), enabled = enabled,
            onCheckedChange = { onChange(state.copy(webEnabled = it)) },
        )
    }
    MarySettingsGroup(R.string.mary_settings_behaviour) {
        DetailsFields {
            MaryInlineField(state.iterations, R.string.mary_behaviour_iterations, enabled, keyboard = KeyboardType.Number) {
                onChange(state.copy(iterations = it))
            }
            MaryInlineField(state.failures, R.string.mary_behaviour_tool_failures, enabled, keyboard = KeyboardType.Number) {
                onChange(state.copy(failures = it))
            }
            MaryInlineField(state.budget, R.string.mary_behaviour_turn_budget, enabled,
                tag = "mary-settings-turn-budget", keyboard = KeyboardType.Number) {
                onChange(state.copy(budget = it))
            }
            MaryInlineField(state.resultChars, R.string.mary_behaviour_result_chars, enabled,
                tag = "mary-settings-result-chars", keyboard = KeyboardType.Number) {
                onChange(state.copy(resultChars = it))
            }
        }
    }
}

@Composable
internal fun MaryModelsPage(state: MarySettingsDraft, enabled: Boolean, onOpen: (MaryModelEntry) -> Unit) {
    if (state.models.isEmpty()) {
        MarySettingsHint(stringResource(if (state.providers.isEmpty()) R.string.mary_phase_register_provider_first
            else R.string.mary_phase_no_registered_models))
        return
    }
    MarySettingsGroup(R.string.mary_phase_model_list) {
        state.models.forEach { entry ->
            val caption = listOfNotNull(state.provider(entry.providerId)?.let { providerLabel(it) },
                entry.draft.provider.model.takeIf { it.isNotBlank() && it != entry.name }).joinToString(" • ")
            MaryEditorRow(entry.name.ifEmpty { stringResource(R.string.mary_phase_untitled_model) },
                caption, "mary-settings-model-${entry.id}", enabled) { onOpen(entry) }
        }
    }
}

@Composable
internal fun MaryModelEditorSheet(
    show: Boolean, sessionKey: Any, model: MaryModelEntry, state: MarySettingsDraft,
    reasoningCatalog: ReasoningCatalogIndex, candidates: List<MaryModelCandidate>, suggestion: MaryModelCandidate?,
    onEdit: (MaryModelEntry) -> Unit, onCandidate: (MaryModelCandidate) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit, onRemove: () -> Unit,
) {
    val registering = remember(sessionKey) { state.model(model.id) == null }
    var confirmRemoval by remember(sessionKey) { mutableStateOf(false) }
    var bounds by remember(sessionKey) { mutableStateOf(IntRect.Zero) }
    var expanded by remember(sessionKey) { mutableStateOf(false) }
    var menuVisible by remember(sessionKey) { mutableStateOf(false) }
    val query = model.draft.provider.model.trim()
    val options = remember(candidates, query) {
        candidates.filter { it.details.id.contains(query, ignoreCase = true) || it.displayName.contains(query, ignoreCase = true) }
    }
    GlassEditorSheet(
        show = show, sessionKey = sessionKey, dismissRequestKey = expanded,
        title = stringResource(if (registering) R.string.mary_phase_register_model else R.string.mary_phase_edit_model),
        cancelText = stringResource(R.string.rear_widget_cancel),
        confirmText = stringResource(R.string.mary_phase_apply_model),
        confirmEnabled = state.validModel(model),
        onConfirm = onConfirm, onDismissRequest = onDismiss,
        modifier = Modifier.onGloballyPositioned { bounds = it.boundsInWindow().roundToIntRect() },
        beforeConfirmAction = if (registering) null else {{
            MaryRemoveAction(R.string.mary_phase_remove_model, show, "mary-settings-delete-model") { confirmRemoval = true }
        }},
    ) {
        BackHandler(enabled = show && menuVisible && !confirmRemoval) { expanded = false }
        MaryRemovalConfirmation(show && confirmRemoval, R.string.mary_phase_remove_model,
            R.string.mary_settings_model_delete_confirmation, onDismiss = { confirmRemoval = false },
            onConfirm = { confirmRemoval = false; onRemove() })
        Column(Modifier.testTag("mary-settings-model-editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MaryEditorCard {
                MarySheetChoice(
                    R.string.mary_phase_model_provider, state.providers.map { providerLabel(it) },
                    state.providers.indexOfFirst { it.id == model.providerId }, "mary-settings-model-provider", show,
                ) { index ->
                    val providerId = state.providers[index].id
                    if (providerId != model.providerId) onEdit(model.withIdentity(provider = providerId))
                }
            }
            SuggestionMenu(
                expanded = show && expanded, options = options.map { it.displayName.ifBlank { it.details.id } },
                optionSummaries = options.map { candidate -> candidate.details.id.takeIf { candidate.displayName.isNotBlank() } },
                selectedIndex = options.indexOfFirst { it.details.id.equals(query, ignoreCase = true) },
                boundsInWindow = bounds, onDismissRequest = { expanded = false },
                onVisibilityChanged = { menuVisible = it },
                onSelectedIndexChange = { index ->
                    expanded = false
                    options.getOrNull(index)?.let(onCandidate)
                },
            ) {
                EditorTextField(
                    value = model.draft.provider.model,
                    onValueChange = {
                        expanded = true
                        onEdit(model.withIdentity(model = it))
                    },
                    label = stringResource(R.string.mary_phase_model_id), enabled = show, singleLine = true,
                    backgroundColor = maryMochiEditorFill(),
                    modifier = Modifier.fillMaxWidth().testTag("mary-settings-model-id")
                        .onFocusChanged { expanded = it.isFocused },
                )
            }
            if (candidates.isEmpty() || (expanded && options.isEmpty())) {
                Text(stringResource(if (candidates.isEmpty()) R.string.mary_phase_candidates_hint else R.string.mary_phase_candidates_no_match),
                    style = MixTheme.textStyles.footnote2, color = MixTheme.colorScheme.onSurfaceVariantSummary)
            }
            MarySheetField(model.displayName, R.string.mary_phase_display_name, show, "mary-settings-model-name") {
                onEdit(model.copy(displayName = it, manualDisplayName = true))
            }
            suggestion?.displayName?.takeIf { it.isNotBlank() && it != model.displayName }?.let {
                MarySuggestion(stringResource(R.string.mary_phase_suggested_name, it))
            }
            MarySheetField(model.draft.context, R.string.mary_provider_context, show, "mary-settings-model-context", KeyboardType.Number) {
                onEdit(model.copy(draft = model.draft.copy(context = it), contextSource = MaryModelValueSource.MANUAL))
            }
            MaryValueSource(model.contextSource, "mary-settings-model-context-source")
            suggestion?.context?.takeIf { it != model.draft.context.toIntOrNull() }?.let {
                MarySuggestion(stringResource(R.string.mary_phase_suggested_tokens, it))
            }
            MarySheetField(model.draft.output, R.string.mary_behaviour_max_tokens, show, "mary-settings-model-output", KeyboardType.Number) {
                onEdit(model.copy(draft = model.draft.copy(output = it), outputSource = MaryModelValueSource.MANUAL))
            }
            if (model.outputSource != MaryModelValueSource.DEFAULT) MaryValueSource(model.outputSource, "mary-settings-model-output-source")
            suggestion?.output?.takeIf { it != model.draft.output.toIntOrNull() }?.let {
                MarySuggestion(stringResource(R.string.mary_phase_suggested_tokens, it))
            }
            MarySheetField(model.draft.temperature, R.string.mary_behaviour_temperature, show, "mary-settings-model-temperature", KeyboardType.Decimal) {
                onEdit(model.copy(draft = model.draft.copy(temperature = it)))
            }
            suggestion?.capabilities?.takeIf { it.isNotEmpty() && it != model.capabilities }?.let { capabilities ->
                val labels = capabilities.sortedBy { it.ordinal }.map { capabilityLabel(it) }
                MarySuggestion(stringResource(R.string.mary_phase_suggested_capabilities, labels.joinToString(" · ")))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MarySettingsSectionTitle(R.string.mary_phase_capabilities)
                MaryEditorCard {
                    MaryModelCapability.entries.forEach { capability ->
                        SwitchPreference(
                            title = capabilityLabel(capability), checked = capability in model.capabilities,
                            modifier = Modifier.testTag("mary-settings-capability-${capability.name.lowercase()}"), enabled = show,
                            onCheckedChange = { on -> onEdit(model.copy(
                                capabilities = if (on) model.capabilities + capability else model.capabilities - capability,
                                manualCapabilities = true,
                            )) },
                        )
                    }
                    state.combined(model)?.let { combined ->
                        if (show) ReasoningConfiguration(combined, reasoningCatalog) {
                            onEdit(model.copy(draft = model.draft.copy(provider = model.draft.provider.copy(reasoningOverride = it.provider.reasoningOverride))))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun MaryProvidersPage(
    state: MarySettingsDraft, enabled: Boolean, onOpen: (MaryProviderDraft) -> Unit,
    result: (MaryProviderDraft) -> MaryProviderResult?,
) {
    if (state.providers.isEmpty()) {
        MarySettingsHint(stringResource(R.string.mary_phase_no_registered_providers))
        return
    }
    MarySettingsGroup(R.string.mary_phase_connections) {
        state.providers.forEach { provider ->
            val count = result(provider)?.fetchedCount ?: provider.modelCount
            val protocol = protocolTitle(provider.draft.provider.protocol)
            val summary = if (count == null) protocol else stringResource(R.string.mary_phase_provider_summary,
                protocol, pluralStringResource(R.plurals.mary_phase_model_count, count, count))
            MaryEditorRow(providerLabel(provider), summary, "mary-settings-provider-${provider.id}", enabled) { onOpen(provider) }
        }
    }
}

@Composable
internal fun MaryProviderEditorSheet(
    show: Boolean, sessionKey: Any, provider: MaryProviderDraft, registering: Boolean,
    onEdit: (MaryProviderDraft) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, onRemove: () -> Unit,
    status: MaryProviderResult?, onTest: () -> Unit, onFetch: () -> Unit,
) {
    val draft = provider.draft
    val isRegistration = remember(sessionKey) { registering }
    var confirmRemoval by remember(sessionKey) { mutableStateOf(false) }
    val canConnect = show && status?.testing != true && status?.fetching != true && draft.connectionValid()
    GlassEditorSheet(
        show = show, sessionKey = sessionKey,
        title = stringResource(if (isRegistration) R.string.mary_phase_register_provider else R.string.mary_phase_edit_provider),
        cancelText = stringResource(R.string.rear_widget_cancel),
        confirmText = stringResource(R.string.mary_phase_apply_provider),
        confirmEnabled = canConnect && (!isRegistration || provider.displayName.isNotBlank()),
        onConfirm = onConfirm, onDismissRequest = onDismiss,
        beforeConfirmAction = if (isRegistration) null else {{
            MaryRemoveAction(R.string.mary_phase_remove_provider, show, "mary-settings-delete-provider") { confirmRemoval = true }
        }},
    ) {
        MaryRemovalConfirmation(show && confirmRemoval, R.string.mary_phase_remove_provider,
            R.string.mary_settings_provider_delete_confirmation, onDismiss = { confirmRemoval = false },
            onConfirm = { confirmRemoval = false; onRemove() })
        Column(Modifier.testTag("mary-settings-provider-editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MarySheetField(provider.displayName, R.string.mary_phase_provider_name, show, "mary-settings-provider-name") {
                onEdit(provider.copy(displayName = it))
            }
            MarySheetField(draft.provider.baseUrl, R.string.mary_provider_endpoint, show,
                "mary-settings-provider-address", KeyboardType.Uri) {
                onEdit(provider.copy(draft = draft.copy(provider = draft.provider.copy(baseUrl = it))))
            }
            MaryEditorCard {
                MarySheetChoice(R.string.mary_settings_ui_protocol, Protocol.entries.map { protocolTitle(it) }, draft.provider.protocol.ordinal,
                    "mary-settings-provider-protocol", show) { index ->
                    onEdit(provider.copy(draft = draft.copy(provider = draft.provider.copy(protocol = Protocol.entries[index]))))
                }
            }
            EditorTextField(draft.keyDraft, { onEdit(provider.copy(draft = draft.copy(keyDraft = it, clearKey = false))) },
                label = stringResource(R.string.mary_provider_api_key), enabled = show, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(), backgroundColor = maryMochiEditorFill(),
                modifier = Modifier.fillMaxWidth().testTag("mary-settings-provider-key"))
            if (provider.unreadable) MarySettingsDiagnostic(stringResource(R.string.mary_provider_api_key_unreadable))
            else Text(stringResource(if (draft.provider.apiKey.isNotEmpty()) R.string.mary_provider_api_key_set
                else R.string.mary_provider_api_key_desc), style = MixTheme.textStyles.footnote2,
                color = MixTheme.colorScheme.onSurfaceVariantSummary)
            if (draft.provider.apiKey.isNotEmpty() || provider.unreadable) {
                SwitchPreference(title = stringResource(R.string.mary_provider_clear_key), checked = draft.clearKey, enabled = show,
                    onCheckedChange = { onEdit(provider.copy(draft = draft.copy(clearKey = it))) })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProviderButton(Modifier.weight(1f).testTag("mary-settings-provider-test"), R.string.mary_provider_test,
                    canConnect, status?.testing == true, test = true, onClick = onTest)
                ProviderButton(Modifier.weight(1f).testTag("mary-settings-provider-fetch"), R.string.mary_phase_fetch_models,
                    canConnect, status?.fetching == true, test = false, onClick = onFetch)
            }
            status?.message?.let { if (status.error) MarySettingsDiagnostic(it) else MarySettingsHint(it) }
        }
    }
}

@Composable
private fun MaryRemoveAction(label: Int, show: Boolean, tag: String, onRemove: () -> Unit) {
    BottomSheetActionButton(onClick = onRemove, modifier = Modifier.testTag(tag), enabled = show) {
        Icon(MixIcons.Glass.Delete, stringResource(label), tint = MixTheme.colorScheme.error)
    }
}

@Composable
private fun MaryRemovalConfirmation(show: Boolean, title: Int, message: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    FormDialog(show, stringResource(title), onDismiss, Modifier.testTag("mary-settings-delete-confirmation")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(message))
            DialogActions(
                cancelText = stringResource(R.string.rear_widget_cancel),
                confirmText = stringResource(R.string.rear_widget_action_delete),
                onCancel = onDismiss, onConfirm = onConfirm, destructive = true,
            )
        }
    }
}

/** A single opaque, theme-derived form surface on top of the tinted Mochi sheet. */
@Composable
private fun maryMochiEditorFill(): Color? {
    if (LocalMixUiBackend.current != MixUiBackend.Mochi) return null
    val palette = MixTheme.colorScheme
    return (if (palette.surface.luminance() > 0.5f) palette.surface else palette.surfaceContainerHigh).copy(alpha = 1f)
}

@Composable
private fun MaryEditorCard(content: @Composable ColumnScope.() -> Unit) {
    val fill = maryMochiEditorFill()
    val colors = if (fill == null) CardDefaults.defaultColors()
        else CardDefaults.defaultColors(color = fill, contentColor = MixTheme.colorScheme.onSurface)
    Card(Modifier.fillMaxWidth(), colors = colors, content = content)
}

@Composable
private fun MarySheetChoice(label: Int, items: List<String>, index: Int, tag: String, enabled: Boolean, onSelect: (Int) -> Unit) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    GlassSelectPreference(
        title = stringResource(label), items = items, selectedIndex = index,
        modifier = Modifier.testTag(tag), enabled = enabled,
        supportingText = PreferenceSupportingText(description = null, selectedValue = items.getOrNull(index).orEmpty()),
        onSelectedIndexChange = { if (it in items.indices) onSelect(it) },
        onExpandedChange = { expanded ->
            if (expanded) {
                // End text editing before the menu appears: native popup bounds don't account for IME insets.
                focusManager.clearFocus(force = true)
                keyboard?.hide()
            }
        },
    )
}

@Composable
private fun MaryEditorRow(title: String, summary: String, tag: String, enabled: Boolean, onClick: () -> Unit) {
    ListItem(title = title, summary = summary, modifier = Modifier.testTag(tag), enabled = enabled,
        onClick = onClick, endActions = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) })
}

@Composable
private fun providerLabel(provider: MaryProviderDraft) = provider.displayName.trim().ifBlank { stringResource(provider.title) }

@Composable
private fun MarySheetField(value: String, label: Int, enabled: Boolean, tag: String,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    EditorTextField(value, onChange, label = stringResource(label), enabled = enabled, singleLine = true,
        backgroundColor = maryMochiEditorFill(), keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth().testTag(tag))
}

@Composable
private fun ProviderButton(modifier: Modifier, label: Int, enabled: Boolean, busy: Boolean, test: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, minWidth = 0.dp,
        insideMargin = PaddingValues(horizontal = 8.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (busy) CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
            else Icon(if (test) Icons.Outlined.PlayArrow else Icons.Outlined.CloudDownload, null, Modifier.size(16.dp))
            Text(stringResource(label), style = MixTheme.textStyles.button)
        }
    }
}

@Composable
private fun MaryChoice(
    label: Int, items: List<String>, selectedIndex: Int, modifier: Modifier = Modifier, enabled: Boolean = true,
    summary: String? = items.getOrNull(selectedIndex), onSelect: (Int) -> Unit,
) {
    GlassSelectPreference(title = stringResource(label), items = items, selectedIndex = selectedIndex,
        modifier = modifier, summary = summary, enabled = enabled,
        onSelectedIndexChange = { if (it in items.indices) onSelect(it) })
}

@Composable
private fun MarySettingsGroup(title: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        MarySettingsSectionTitle(title)
        Card(modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun DetailsFields(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
}

@Composable
private fun MaryInlineField(
    value: String, label: Int, enabled: Boolean, tag: String? = null,
    keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit,
) {
    val title = stringResource(label)
    // Mochi's label is a placeholder; use a persistent in-field caption there.
    val mochi = LocalMixUiBackend.current == MixUiBackend.Mochi
    val caption: (@Composable () -> Unit)? = if (mochi) ({
        Text(title, style = MixTheme.textStyles.footnote1, color = MixTheme.colorScheme.onSurfaceVariantSummary)
    }) else null
    TextField(value, onChange, Modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag)),
        label = if (mochi) "" else title, enabled = enabled, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), leadingIcon = caption)
}

@Composable
private fun MarySuggestion(text: String) {
    Text(text, style = MixTheme.textStyles.footnote2, color = MixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun MaryValueSource(source: MaryModelValueSource, tag: String) {
    val label = when (source) {
        MaryModelValueSource.DEFAULT -> R.string.mary_phase_context_default
        MaryModelValueSource.MANUAL -> R.string.mary_phase_context_manual
        MaryModelValueSource.PROVIDER -> R.string.mary_phase_context_provider
        MaryModelValueSource.CATALOG -> R.string.mary_phase_context_catalog
    }
    Text(stringResource(label), style = MixTheme.textStyles.footnote1, color = MixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.testTag(tag))
}

@Composable
private fun capabilityLabel(capability: MaryModelCapability): String = stringResource(when (capability) {
    MaryModelCapability.TEXT -> R.string.mary_phase_cap_text
    MaryModelCapability.IMAGE -> R.string.mary_phase_cap_image
    MaryModelCapability.AUDIO -> R.string.mary_phase_cap_audio
    MaryModelCapability.TOOLS -> R.string.mary_phase_cap_tools
})

@Composable
private fun protocolTitle(protocol: Protocol) = stringResource(when (protocol) {
    Protocol.CHAT_COMPLETIONS -> R.string.mary_protocol_chat_completions
    Protocol.RESPONSES -> R.string.mary_protocol_responses
    Protocol.ANTHROPIC -> R.string.mary_protocol_anthropic
})
