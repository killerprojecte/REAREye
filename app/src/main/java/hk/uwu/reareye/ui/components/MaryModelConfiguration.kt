package hk.uwu.reareye.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.CapabilityScope
import hk.uwu.reareye.agent.CapabilitySource
import hk.uwu.reareye.agent.ModelDescriptor
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ProviderPreset
import hk.uwu.reareye.agent.ReasoningCapability
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningMapping
import hk.uwu.reareye.agent.ReasoningPolicy
import hk.uwu.reareye.agent.ReasoningStatus
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.provider.ChatRequest
import hk.uwu.reareye.agent.provider.ProviderFactory
import hk.uwu.reareye.agent.provider.ProviderRequestDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class ModelDraft(
    val provider: ProviderConfig = ProviderConfig(),
    val keyDraft: String = "",
    val clearKey: Boolean = false,
    val temperature: String = provider.temperature?.toString().orEmpty(),
    val output: String = provider.maxOutputTokens?.toString().orEmpty(),
    val context: String = provider.contextWindowTokens.toString(),
) {
    /** A preset changes connection routing only, including while a secret is being edited. */
    fun withPreset(preset: ProviderPreset) = copy(provider = preset.applyTo(provider))

    /** Only verified provider metadata may replace the manually configured capacity. */
    fun withModel(model: String, capacity: Int? = null) = copy(
        provider = provider.copy(model = model),
        context = capacity?.takeIf { it > 0 }?.toString() ?: context,
    )

    /** Missing metadata must not erase a previous manual choice or the chosen effort. */
    fun withModelDetails(details: ModelDescriptor) = withModel(details.id).let { selected ->
        val incoming = details.reasoning
        val existing =
            selected.provider.reasoningMetadata?.takeIf { it.validFor(selected.connectionConfig()) }
        when {
            incoming == null -> selected
            incoming.status == ReasoningStatus.UNKNOWN && existing != null && existing.status != ReasoningStatus.UNKNOWN -> selected
            else -> selected.copy(provider = selected.provider.copy(reasoningMetadata = incoming))
        }
    }

    fun withReasoning(
        status: ReasoningStatus?,
        levels: List<ReasoningEffort> = listOf(
            ReasoningEffort.LOW,
            ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH
        ),
        mapping: ReasoningMapping = if (provider.protocol == Protocol.ANTHROPIC) ReasoningMapping.CLAUDE_BUDGET else ReasoningMapping.OPENAI_EFFORT
    ): ModelDraft =
        copy(provider = provider.copy(reasoningOverride = status?.let {
            ReasoningCapability(
                it,
                if (it == ReasoningStatus.SUPPORTED) levels.filter { level -> level in mapping.allowedLevels }
                    .distinct()
                    .ifEmpty { listOf(mapping.allowedLevels.first()) }
                    .sortedBy { level -> level.ordinal } else emptyList(),
                if (it == ReasoningStatus.SUPPORTED) mapping else null,
                CapabilityScope.from(connectionConfig()),
                CapabilitySource.MANUAL)
        }))

    fun connectionConfig() = provider.copy(
        baseUrl = provider.baseUrl.trim(),
        apiKey = if (clearKey) "" else keyDraft.trim().ifEmpty { provider.apiKey },
        credentialScopeTag = if (clearKey || keyDraft.isNotBlank()) null else provider.credentialScopeTag,
    )

    fun connectionValid() = runCatching {
        java.net.URI(provider.baseUrl.trim())
            .let { it.scheme in listOf("http", "https") && !it.host.isNullOrEmpty() }
    }.getOrDefault(false)

    fun valid() = context.toIntOrNull()
        ?.let { it >= 4096 && (output.toIntOrNull() ?: 0).toLong() + it / 10 < it } == true &&
            runCatching {
                java.net.URI(provider.baseUrl)
                    .let { it.scheme in listOf("http", "https") && !it.host.isNullOrEmpty() }
            }.getOrDefault(false) &&
            (temperature.isBlank() || temperature.toDoubleOrNull()
                ?.let { it.isFinite() && it in 0.0..2.0 } == true) &&
            (output.isBlank() || output.toIntOrNull()?.let { it > 0 } == true)

    fun build() = provider.copy(
        baseUrl = provider.baseUrl.trim(), model = provider.model.trim(),
        apiKey = if (clearKey) "" else keyDraft.trim().ifEmpty { provider.apiKey },
        contextWindowTokens = context.toInt(), temperature = temperature.toDoubleOrNull(),
        maxOutputTokens = output.toIntOrNull(),
        credentialScopeTag = if (clearKey || keyDraft.isNotBlank()) null else provider.credentialScopeTag,
    )
}

/** The dialogue and approval model share the normal Card + Preference layout. */
@Composable
internal fun ModelConfigurationCard(
    title: Int,
    draft: ModelDraft,
    onChange: (ModelDraft) -> Unit,
    unreadable: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultError by remember { mutableStateOf(false) }
    var testJob by remember { mutableStateOf<Job?>(null) }
    var modelsJob by remember { mutableStateOf<Job?>(null) }
    var loadingModels by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<ModelDescriptor>>(emptyList()) }
    var modelStatus by remember { mutableStateOf<String?>(null) }
    var modelError by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val latestDraft by rememberUpdatedState(draft)
    val latestOnChange by rememberUpdatedState(onChange)
    val connection = draft.connectionConfig()
        .let { listOf(it.baseUrl, it.protocol.name, it.apiKey, it.credentialScopeTag) }
    DisposableEffect(connection) {
        onDispose { testJob?.cancel(); modelsJob?.cancel() }
    }
    LaunchedEffect(connection) {
        models = emptyList()
        modelStatus = null
        result = null
        modelError = false
        resultError = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MarySettingsSectionTitle(title)
        Card(Modifier
            .fillMaxWidth()
            .testTag("mary-model-card-$title")) {
            MarySettingsChoicePreference(
                title = stringResource(R.string.mary_settings_ui_protocol),
                items = Protocol.entries.map { protocol ->
                    DropdownItem(
                        title = stringResource(
                            when (protocol) {
                                Protocol.CHAT_COMPLETIONS -> R.string.mary_protocol_chat_completions
                                Protocol.RESPONSES -> R.string.mary_protocol_responses
                                Protocol.ANTHROPIC -> R.string.mary_protocol_anthropic
                            }
                        )
                    )
                },
                selectedIndex = Protocol.entries.indexOf(draft.provider.protocol),
                onSelectedIndexChange = { index ->
                    onChange(draft.copy(provider = draft.provider.copy(protocol = Protocol.entries[index])))
                },
            )
            MarySettingsField(
                draft.provider.baseUrl,
                { onChange(draft.copy(provider = draft.provider.copy(baseUrl = it))) },
                R.string.mary_provider_endpoint
            )
            MarySettingsField(
                value = draft.keyDraft,
                onChange = { onChange(draft.copy(keyDraft = it)) },
                label = R.string.mary_provider_api_key,
                password = true,
                summary = stringResource(if (draft.provider.apiKey.isNotEmpty()) R.string.mary_provider_api_key_set else R.string.mary_provider_api_key_placeholder),
            )
            if (unreadable) MarySettingsDiagnostic(stringResource(R.string.mary_provider_api_key_unreadable))
            MarySettingsHint(stringResource(R.string.mary_provider_api_key_desc))
            SwitchPreference(
                title = stringResource(R.string.mary_provider_clear_key), checked = draft.clearKey,
                onCheckedChange = { onChange(draft.copy(clearKey = it)) })
            // Match the custom API input in ConfigNodeRows: only this immediately editable
            // field is inset, rather than nesting a padded form inside the preference card.
            TextField(
                value = draft.provider.model, onValueChange = {
                    modelsJob?.cancel()
                    testJob?.cancel()
                    modelStatus = null
                    result = null
                    onChange(draft.withModel(it))
                }, label = stringResource(R.string.mary_provider_model),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 14.dp)
                    .testTag("mary-model-input-$title"), singleLine = true,
                trailingIcon = {
                    IconButton(
                        modifier = Modifier.testTag("mary-model-test-$title"),
                        enabled = !testing && !loadingModels && draft.valid() && draft.provider.model.isNotBlank(),
                        onClick = {
                            val config = draft.build()
                            testing = true
                            result = null
                            resultError = false
                            testJob = scope.launch {
                                try {
                                    val testResult = withContext(Dispatchers.IO) {
                                        try {
                                            withTimeoutOrNull(45_000L) {
                                                var normalFinish = false
                                                ProviderFactory.create { config }.chat(
                                                    ChatRequest(
                                                        model = config.model,
                                                        messages = listOf(
                                                            ChatMessage(
                                                                MessageRole.USER,
                                                                "Reply with OK only."
                                                            )
                                                        ),
                                                        tools = emptyList(), maxOutputTokens = 256,
                                                    )
                                                ).collect { event ->
                                                    when (event) {
                                                        is ChatEvent.Finished -> {
                                                            check(
                                                                event.finishReason in setOf(
                                                                    "stop",
                                                                    "end_turn",
                                                                    "completed"
                                                                )
                                                            ) {
                                                                "Chat ended without normal completion: ${event.finishReason}"
                                                            }
                                                            normalFinish = true
                                                        }

                                                        is ChatEvent.ToolCallDelta -> error("Unexpected tool call in connection test")
                                                        else -> Unit
                                                    }
                                                }
                                                check(normalFinish) { "Stream ended without a Finished event" }
                                                true
                                            }
                                                ?: error(context.getString(R.string.mary_provider_chat_test_timeout))
                                            context.getString(R.string.mary_provider_chat_test_ok) to false
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            modelRequestDiagnostic(
                                                error,
                                                config,
                                                "test model"
                                            ) to true
                                        }
                                    }
                                    result = testResult.first
                                    resultError = testResult.second
                                } finally {
                                    testing = false; testJob = null
                                }
                            }
                        }) {
                        if (testing) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                        else Icon(
                            Icons.Outlined.PlayArrow,
                            stringResource(R.string.mary_provider_test),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                })
            BasicComponent(
                title = stringResource(R.string.mary_settings_available_models),
                enabled = !loadingModels && !testing && draft.connectionValid(),
                endActions = {
                    if (loadingModels) CircularProgressIndicator(size = 16.dp, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.ExpandMore, null)
                },
                onClick = {
                    val config = draft.connectionConfig()
                    loadingModels = true
                    modelStatus = null
                    modelError = false
                    modelsJob = scope.launch {
                        try {
                            models = withContext(Dispatchers.IO) {
                                withTimeoutOrNull(30_000L) {
                                    ProviderFactory.create { config }.listModelDetails()
                                        .filter { it.id.isNotBlank() }.distinctBy { it.id }
                                        .sortedBy { it.id }
                                } ?: error(context.getString(R.string.mary_settings_models_timeout))
                            }
                            if (models.isEmpty()) modelStatus =
                                context.getString(R.string.mary_settings_models_empty)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            models = emptyList()
                            modelError = true
                            modelStatus = modelRequestDiagnostic(error, config, "list models")
                        } finally {
                            loadingModels = false; modelsJob = null
                        }
                    }
                },
            )
            val selectModelLabel = stringResource(R.string.mary_settings_select_model)
            if (models.isNotEmpty()) MarySettingsChoicePreference(
                title = selectModelLabel,
                items = listOf(DropdownItem(title = selectModelLabel)) + models.map {
                    DropdownItem(
                        title = it.id
                    )
                },
                selectedIndex = models.indexOfFirst { it.id == draft.provider.model } + 1,
                enabled = !loadingModels && !testing,
                onSelectedIndexChange = select@{ index ->
                    val details = models.getOrNull(index - 1) ?: return@select
                    val model = details.id
                    val selected = latestDraft.withModelDetails(details)
                    latestOnChange(selected)
                    val config = selected.connectionConfig()
                    val previousContext = selected.context
                    loadingModels = true
                    modelStatus = null
                    modelError = false
                    modelsJob = scope.launch {
                        try {
                            // A completed request with no metadata is not a timeout or network error.
                            val capacity = withContext(Dispatchers.IO) {
                                (withTimeoutOrNull(15_000L) {
                                    Pair(
                                        true,
                                        ProviderFactory.create { config }.modelContextWindow(model)
                                    )
                                }
                                    ?: error(context.getString(R.string.mary_settings_model_context_timeout))).second
                            }?.takeIf { it > 0 }
                            if (latestDraft.provider.model == model) {
                                if (capacity == null) {
                                    modelStatus =
                                        context.getString(R.string.mary_settings_model_context_manual)
                                } else if (latestDraft.context == previousContext) {
                                    latestOnChange(latestDraft.withModel(model, capacity))
                                    modelStatus = context.getString(
                                        R.string.mary_settings_model_context_applied,
                                        capacity
                                    )
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            modelError = true
                            modelStatus = modelRequestDiagnostic(error, config, "model context")
                        } finally {
                            loadingModels = false; modelsJob = null
                        }
                    }
                },
            )
            modelStatus?.let { if (modelError) MarySettingsDiagnostic(it) else MarySettingsHint(it) }
            result?.let { if (resultError) MarySettingsDiagnostic(it) else MarySettingsHint(it) }
            BasicComponent(
                title = stringResource(R.string.mary_settings_advanced_model),
                onClick = { advanced = !advanced },
                endActions = {
                    Icon(
                        if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        null
                    )
                },
            )
            if (advanced) {
                ReasoningConfiguration(draft, onChange)
                MarySettingsField(
                    draft.temperature,
                    { onChange(draft.copy(temperature = it)) },
                    R.string.mary_behaviour_temperature
                )
                MarySettingsField(
                    draft.output,
                    { onChange(draft.copy(output = it)) },
                    R.string.mary_behaviour_max_tokens
                )
                MarySettingsField(
                    draft.context,
                    { onChange(draft.copy(context = it)) },
                    R.string.mary_provider_context
                )
            }
        }
    }
}

@Composable
private fun ReasoningConfiguration(draft: ModelDraft, onChange: (ModelDraft) -> Unit) {
    val config = draft.connectionConfig()
    val override =
        config.reasoningOverride?.takeIf { it.validFor(config) && it.source == CapabilitySource.MANUAL }
    val choice = when (override?.status) {
        ReasoningStatus.UNSUPPORTED -> 1; ReasoningStatus.SUPPORTED -> 2; else -> 0
    }
    MarySettingsChoicePreference(
        title = stringResource(R.string.mary_capability_mode),
        items = listOf(
            R.string.mary_capability_auto,
            R.string.mary_capability_unsupported,
            R.string.mary_capability_manual
        )
            .map { DropdownItem(title = stringResource(it)) },
        selectedIndex = choice,
        onSelectedIndexChange = { index ->
            onChange(
                draft.withReasoning(
                    when (index) {
                        1 -> ReasoningStatus.UNSUPPORTED; 2 -> ReasoningStatus.SUPPORTED; else -> null
                    }
                )
            )
        },
    )
    val support = ReasoningPolicy.support(config)
    if (config.reasoningMetadata?.takeIf { it.validFor(config) }?.let {
            it.status == ReasoningStatus.UNKNOWN && it.supportsReasoning == true
        } == true) MarySettingsHint(stringResource(R.string.mary_capability_supported_unknown_levels))
    if (support.status == ReasoningStatus.UNSUPPORTED) MarySettingsHint(stringResource(R.string.mary_capability_status_unsupported))
    else if (support.levels.isNotEmpty()) MarySettingsHint(
        stringResource(
            R.string.mary_capability_status_levels,
            support.levels.joinToString { it.name.lowercase() })
    )
    MarySettingsHint(
        stringResource(
            when (support.source) {
                CapabilitySource.MANUAL -> R.string.mary_capability_source_manual
                CapabilitySource.SERVICE_METADATA -> R.string.mary_capability_source_service
                CapabilitySource.OFFLINE -> R.string.mary_capability_source_offline
                CapabilitySource.UNKNOWN -> R.string.mary_capability_source_unknown
            }
        )
    )
    if (choice == 2 && override != null) {
        val mappings = if (config.protocol == Protocol.ANTHROPIC)
            listOf(
                ReasoningMapping.DEEPSEEK_THINKING,
                ReasoningMapping.CLAUDE_BUDGET,
                ReasoningMapping.CLAUDE_ADAPTIVE
            )
        else listOf(
            ReasoningMapping.OPENAI_EFFORT,
            ReasoningMapping.DEEPSEEK_THINKING,
            ReasoningMapping.OPENROUTER_REASONING
        )
        MarySettingsChoicePreference(
            title = stringResource(R.string.mary_capability_mapping),
            items = mappings.map { mapping ->
                DropdownItem(
                    title = stringResource(
                        when (mapping) {
                            ReasoningMapping.OPENAI_EFFORT -> R.string.mary_capability_openai
                            ReasoningMapping.OPENROUTER_REASONING -> R.string.mary_capability_openrouter
                            ReasoningMapping.DEEPSEEK_THINKING -> R.string.mary_capability_deepseek
                            ReasoningMapping.CLAUDE_BUDGET -> R.string.mary_capability_claude_budget
                            ReasoningMapping.CLAUDE_ADAPTIVE -> R.string.mary_capability_claude_adaptive
                        }
                    )
                )
            },
            selectedIndex = mappings.indexOf(override.mapping).coerceAtLeast(0),
            onSelectedIndexChange = {
                onChange(
                    draft.withReasoning(
                        ReasoningStatus.SUPPORTED,
                        override.levels,
                        mappings[it]
                    )
                )
            },
        )
        requireNotNull(override.mapping).allowedLevels.forEach { level ->
            val checked = level in override.levels
            SwitchPreference(
                title = level.name.lowercase(), checked = checked,
                onCheckedChange = { selected ->
                    val levels =
                        if (selected) (override.levels + level).distinct() else override.levels - level
                    if (levels.isNotEmpty()) onChange(
                        draft.withReasoning(
                            ReasoningStatus.SUPPORTED,
                            levels,
                            requireNotNull(override.mapping)
                        )
                    )
                })
        }
    }
    MarySettingsHint(stringResource(R.string.mary_capability_scope_hint))
}

/** Same ArrowPreference + root OverlayListPopup used by EnumSingleSelectConfigInput. */
@Composable
internal fun MarySettingsChoicePreference(
    title: String,
    items: List<DropdownItem>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
) {
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val selected = items.getOrNull(selectedIndex)?.text.orEmpty()
    Box {
        ArrowPreference(
            title = title,
            summary = listOfNotNull(
                summary?.takeIf { it.isNotBlank() },
                selected.takeIf { it.isNotBlank() }).joinToString("\n"),
            enabled = enabled,
            holdDownState = expanded,
            onClick = { expanded = true },
        )
        OverlayListPopup(
            show = expanded,
            popupModifier = Modifier,
            popupPositionProvider = ListPopupDefaults.DropdownPositionProvider,
            alignment = PopupPositionProvider.Align.End,
            enableWindowDim = true,
            onDismissRequest = { expanded = false },
            maxHeight = null,
            minWidth = 220.dp,
            renderInRootScaffold = true,
        ) {
            ListPopupColumn {
                items.forEachIndexed { index, item ->
                    DropdownImpl(
                        item = item,
                        optionSize = items.size,
                        isSelected = selectedIndex == index,
                        index = index,
                        dropdownColors = DropdownDefaults.dropdownColors(),
                        onSelectedIndexChange = {
                            expanded = false
                            scope.launch {
                                withFrameNanos { }
                                onSelectedIndexChange(index)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun MarySettingsSectionTitle(title: Int) {
    Text(
        stringResource(title),
        modifier = Modifier
            .testTag("mary-model-section-$title")
            .padding(start = 16.dp, top = 8.dp),
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
}

/** Preference rows stay compact; editing uses the same native dialog as app-card settings. */
@Composable
internal fun MarySettingsField(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    password: Boolean = false,
    summary: String? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(value) }
    val fieldTitle = stringResource(label)
    ArrowPreference(
        title = fieldTitle,
        summary = if (password && value.isNotEmpty()) "••••••" else summary
            ?: value.ifBlank { stringResource(R.string.mary_settings_value_default) },
        holdDownState = editing,
        onClick = { input = value; editing = true },
    )
    OverlayDialog(show = editing, title = fieldTitle, onDismissRequest = { editing = false }) {
        DialogFormColumn {
            TextField(
                value = input, onValueChange = { input = it }, label = fieldTitle,
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColorsPrimary(),
                onClick = {
                    onChange(input)
                    editing = false
                }) { Text(stringResource(R.string.rear_widget_confirm)) }
            Button(modifier = Modifier.fillMaxWidth(), onClick = { editing = false }) {
                Text(stringResource(R.string.rear_widget_cancel))
            }
        }
    }
}

@Composable
internal fun MarySettingsHint(text: String) {
    Text(
        text,
        modifier = Modifier
            .padding(horizontal = 16.dp)
            .padding(bottom = 14.dp),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
    )
}

@Composable
internal fun MarySettingsDiagnostic(text: String) {
    SelectionContainer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 14.dp)
    ) {
        Text(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
            style = MiuixTheme.textStyles.body2.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp
            ),
            color = MiuixTheme.colorScheme.error
        )
    }
}

/** Never pass the raw throwable to Log: native HTTP errors may include credentials. */
private fun modelRequestDiagnostic(
    error: Throwable,
    config: ProviderConfig,
    operation: String
): String =
    ProviderRequestDiagnostics.format(error, config, operation).also {
        android.util.Log.e("MaryModelRequest", it)
    }
