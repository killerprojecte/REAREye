package hk.uwu.reareye.ui.screen

import android.widget.Toast
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hk.uwu.mixui.components.CircularProgressIndicator
import hk.uwu.mixui.components.GlassAnchoredMenu
import hk.uwu.mixui.components.GlassMenuOption
import hk.uwu.mixui.components.GlassTabs
import hk.uwu.mixui.components.GlassTopBarScaffold
import hk.uwu.mixui.components.Icon
import hk.uwu.mixui.components.TopBarIconButton
import hk.uwu.reareye.R
import hk.uwu.reareye.agent.ModelDescriptor
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ProviderPreset
import hk.uwu.reareye.agent.catalog.MaryReasoningCatalog
import hk.uwu.reareye.agent.catalog.ReasoningCatalogIndex
import hk.uwu.reareye.agent.data.AgentSettingsStore
import hk.uwu.reareye.agent.data.LEGACY_APPROVAL_PROVIDER_ID
import hk.uwu.reareye.agent.data.LEGACY_DIALOGUE_PROVIDER_ID
import hk.uwu.reareye.agent.data.MaryModelCapability
import hk.uwu.reareye.agent.data.MaryModelConfig
import hk.uwu.reareye.agent.data.MaryModelValueSource
import hk.uwu.reareye.agent.data.MaryProviderConfig
import hk.uwu.reareye.agent.data.MarySettings
import hk.uwu.reareye.agent.data.newMaryRegistryId
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.provider.ChatRequest
import hk.uwu.reareye.agent.provider.ProviderFactory
import hk.uwu.reareye.agent.provider.ProviderRequestDiagnostics
import hk.uwu.reareye.ui.components.MarySettingsHint
import hk.uwu.reareye.ui.components.ModelDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal enum class MarySettingsTab(val label: Int, val tag: String) {
    AGENT(R.string.mary_phase_tab_agent, "mary-settings-agent"),
    MODELS(R.string.mary_phase_tab_models, "mary-settings-models"),
    PROVIDERS(R.string.mary_phase_tab_providers, "mary-settings-providers"),
}

internal data class MaryProviderDraft(
    val id: String,
    val title: Int,
    val draft: ModelDraft,
    val unreadable: Boolean = false,
    val displayName: String = "",
    val modelCount: Int? = null,
) {
    fun build() = MaryProviderConfig(id, displayName.trim(), draft.provider.baseUrl.trim(), draft.provider.protocol, modelCount)
}

internal data class MaryModelEntry(
    val id: String,
    val providerId: String,
    val draft: ModelDraft,
    val displayName: String = "",
    val manualDisplayName: Boolean = false,
    val contextSource: MaryModelValueSource = MaryModelValueSource.DEFAULT,
    val outputSource: MaryModelValueSource = MaryModelValueSource.DEFAULT,
    val capabilities: Set<MaryModelCapability> = emptySet(),
    val manualCapabilities: Boolean = false,
) {
    val name: String get() = displayName.trim().ifEmpty {
        hk.uwu.reareye.ui.components.maryModelDisplayName(draft.provider.model.trim())
    }

    fun withIdentity(provider: String = providerId, model: String = draft.provider.model): MaryModelEntry {
        if (provider == providerId && model == draft.provider.model) return this
        return copy(
            providerId = provider,
            draft = draft.withModel(model).copy(
                provider = draft.provider.copy(model = model, reasoningMetadata = null),
                context = if (contextSource == MaryModelValueSource.MANUAL) draft.context else ProviderConfig().contextWindowTokens.toString(),
                output = if (outputSource == MaryModelValueSource.MANUAL) draft.output else "",
            ),
            contextSource = if (contextSource == MaryModelValueSource.MANUAL) contextSource else MaryModelValueSource.DEFAULT,
            outputSource = if (outputSource == MaryModelValueSource.MANUAL) outputSource else MaryModelValueSource.DEFAULT,
            capabilities = if (manualCapabilities) capabilities else emptySet(),
        )
    }

    fun applySuggestion(candidate: MaryModelCandidate, connection: ModelDraft): MaryModelEntry {
        val target = withIdentity(model = candidate.details.id)
        val manualOutput = target.draft.output.toIntOrNull() ?: 0
        val suggestedContext = candidate.context?.takeIf {
            it >= 4096 && target.contextSource != MaryModelValueSource.MANUAL && manualOutput.toLong() + it / 10 < it
        }
        val effectiveContext = suggestedContext ?: target.draft.context.toIntOrNull() ?: 128_000
        val suggestedOutput = candidate.output?.takeIf {
            target.outputSource != MaryModelValueSource.MANUAL && it > 0 && it.toLong() + effectiveContext / 10 < effectiveContext
        }
        return target.copy(
            draft = connection.withModelDetails(candidate.details).copy(
                context = suggestedContext?.toString() ?: target.draft.context,
                output = suggestedOutput?.toString() ?: target.draft.output,
            ).withoutCredentials().copy(keyDraft = "", clearKey = false),
            displayName = if (manualDisplayName) displayName else candidate.displayName.ifBlank { displayName },
            capabilities = if (manualCapabilities || candidate.capabilities.isEmpty()) target.capabilities else candidate.capabilities,
            contextSource = if (suggestedContext != null) candidate.contextSource else target.contextSource,
            outputSource = if (suggestedOutput != null) candidate.outputSource else target.outputSource,
        )
    }

    fun build(): MaryModelConfig = MaryModelConfig(
        id = id, providerId = providerId, modelId = draft.provider.model.trim(), displayName = displayName.trim(),
        manualDisplayName = manualDisplayName, contextWindowTokens = draft.context.toInt(), contextSource = contextSource,
        maxOutputTokens = draft.output.toIntOrNull(), outputSource = outputSource, temperature = draft.temperature.toDoubleOrNull(),
        capabilities = capabilities, manualCapabilities = manualCapabilities, reasoningEffort = draft.provider.reasoningEffort,
        reasoningOverride = draft.provider.reasoningOverride, reasoningMetadata = draft.provider.reasoningMetadata,
    )
}

internal data class MaryModelCandidate(
    val details: ModelDescriptor,
    val displayName: String = "",
    val context: Int? = null,
    val output: Int? = null,
    val capabilities: Set<MaryModelCapability> = emptySet(),
    val contextSource: MaryModelValueSource = MaryModelValueSource.PROVIDER,
    val outputSource: MaryModelValueSource = MaryModelValueSource.PROVIDER,
) {
    fun enrichedBy(metadata: MaryModelCandidate) = copy(
        displayName = displayName.ifBlank { metadata.displayName },
        context = context ?: metadata.context,
        contextSource = if (context != null) contextSource else metadata.contextSource,
        output = output ?: metadata.output,
        outputSource = if (output != null) outputSource else metadata.outputSource,
        capabilities = capabilities.ifEmpty { metadata.capabilities },
    )
}

/** Public metadata matches a connection route, not its credential or display name. */
internal fun interface MarySettingsModelCatalog {
    fun models(baseUrl: String, protocol: Protocol): List<MaryModelCandidate>
}

internal fun mergeMaryModelCandidates(fetched: List<MaryModelCandidate>?, metadata: List<MaryModelCandidate>): List<MaryModelCandidate> {
    if (fetched == null) return metadata
    if (fetched.isEmpty() || metadata.isEmpty()) return fetched
    val byId = metadata.associateBy { it.details.id }
    return fetched.map { candidate -> byId[candidate.details.id]?.let(candidate::enrichedBy) ?: candidate }
}

internal data class MarySettingsDraft(
    val original: MarySettings,
    val providers: List<MaryProviderDraft>,
    val models: List<MaryModelEntry>,
    val mainId: String? = original.mainModelId,
    val approvalId: String? = original.approvalModelId,
    val subagentId: String? = original.subagentModelId,
    val agentMode: Boolean = original.agentMode,
    val iterations: String = original.configuration.maxIterations.toString(),
    val failures: String = original.configuration.maxConsecutiveToolFailures.toString(),
    val budget: String = (original.configuration.turnBudgetMs / 1000).toString(),
    val resultChars: String = original.configuration.maxToolResultChars.toString(),
    val approvalMode: ApprovalMode = original.approvalMode,
    val inheritApprovalModel: Boolean = original.inheritApprovalModel,
    val webEnabled: Boolean = original.webEnabled,
) {
    fun updateModel(model: MaryModelEntry) = copy(models = models.map { if (it.id == model.id) model else it })
    fun updateProvider(provider: MaryProviderDraft) = copy(providers = providers.map { if (it.id == provider.id) provider else it })
    fun provider(id: String?) = providers.firstOrNull { it.id == id }
    fun model(id: String?) = models.firstOrNull { it.id == id }
    fun removeModel(id: String) = removeModels(setOf(id))

    fun removeProvider(id: String) = removeModels(models.filter { it.providerId == id }.map { it.id }.toSet())
        .copy(providers = providers.filterNot { it.id == id })

    private fun removeModels(ids: Set<String>) = copy(
        models = models.filterNot { it.id in ids },
        mainId = mainId?.takeUnless { it in ids },
        approvalId = approvalId?.takeUnless { it in ids },
        subagentId = subagentId?.takeUnless { it in ids },
    )

    /** Keep model parameters separate from connection settings even while editing. */
    fun combined(model: MaryModelEntry): ModelDraft? = provider(model.providerId)?.draft?.let { connection ->
        model.draft.copy(
            provider = model.draft.provider.copy(
                baseUrl = connection.provider.baseUrl,
                protocol = connection.provider.protocol,
                apiKey = connection.provider.apiKey,
                credentialScopeTag = connection.provider.credentialScopeTag,
            ),
            clearKey = connection.clearKey,
            keyDraft = connection.keyDraft,
        )
    }

    // Older versions allowed values that the current editor cannot create. Preserve untouched
    // records byte-for-value and validate only edits; orphaned references are never allowed.
    private fun savedProvider(draft: MaryProviderDraft) = original.providers.firstOrNull { it.id == draft.id }
        ?.takeIf { draft == it.toDraft(original) }

    private fun savedModel(draft: MaryModelEntry) = original.models.firstOrNull { it.id == draft.id }
        ?.takeIf { draft == it.toDraft(original) }

    fun validModel(entry: MaryModelEntry): Boolean = entry.draft.provider.model.isNotBlank() && combined(entry)?.valid() == true

    fun valid(): Boolean {
        val config = original.configuration
        fun validInt(text: String, saved: Number, range: IntRange) =
            text == saved.toString() || text.toIntOrNull()?.let { it in range } == true
        return providers.all { savedProvider(it) != null || it.draft.connectionValid() } &&
            models.all { provider(it.providerId) != null && (savedModel(it) != null || validModel(it)) } &&
            listOfNotNull(mainId, approvalId, subagentId).all { model(it) != null } &&
            validInt(iterations, config.maxIterations, 1..50) &&
            validInt(failures, config.maxConsecutiveToolFailures, 1..20) &&
            validInt(budget, config.turnBudgetMs / 1000, 30..3600) &&
            validInt(resultChars, config.maxToolResultChars, 0..200000)
    }

    fun build(): MarySettings {
        val config = original.configuration
        return original.copy(
            configuration = config.copy(
                maxIterations = iterations.toInt(), maxConsecutiveToolFailures = failures.toInt(),
                turnBudgetMs = if (budget == (config.turnBudgetMs / 1000).toString()) config.turnBudgetMs else budget.toLong() * 1000,
                maxToolResultChars = resultChars.toInt(),
            ),
            providers = providers.map { savedProvider(it) ?: it.build() },
            models = models.map { savedModel(it) ?: it.build() },
            agentMode = agentMode, mainModelId = mainId, approvalModelId = approvalId, subagentModelId = subagentId,
            approvalMode = approvalMode, inheritApprovalModel = inheritApprovalModel,
            webEnabled = webEnabled,
        )
    }

    fun keyChanges() = buildMap {
        providers.forEach { provider ->
            if (provider.draft.clearKey) put(provider.id, "")
            else if (provider.draft.keyDraft.isNotBlank()) put(provider.id, provider.draft.keyDraft)
        }
    }

    companion object {
        fun from(settings: MarySettings): MarySettingsDraft = MarySettingsDraft(
            original = settings,
            providers = settings.providers.map { it.toDraft(settings) },
            models = settings.models.map { it.toDraft(settings) },
        )
    }
}

private fun MaryProviderConfig.toDraft(settings: MarySettings) = MaryProviderDraft(
    id = id,
    title = when (id) {
        LEGACY_DIALOGUE_PROVIDER_ID -> R.string.mary_phase_main_provider
        LEGACY_APPROVAL_PROVIDER_ID -> R.string.mary_phase_approval_provider
        else -> R.string.mary_phase_model_provider
    },
    draft = ModelDraft(requireNotNull(settings.resolveProvider(id))),
    unreadable = settings.isProviderKeyUnreadable(id),
    displayName = displayName, modelCount = modelCount,
)

private fun MaryModelConfig.toDraft(settings: MarySettings) = MaryModelEntry(
    id = id, providerId = providerId,
    draft = ModelDraft(requireNotNull(settings.resolveModel(id))).withoutCredentials(),
    displayName = displayName, manualDisplayName = manualDisplayName,
    contextSource = contextSource, outputSource = outputSource,
    capabilities = capabilities, manualCapabilities = manualCapabilities,
)

private fun ModelDraft.withoutCredentials() = copy(provider = provider.copy(apiKey = "", credentialScopeTag = null))

/** Test/preview can inject discovery without constructing a store, client, or real credential. */
internal interface MarySettingsDiscovery {
    suspend fun candidates(source: MaryProviderDraft): List<MaryModelCandidate>
    suspend fun test(source: MaryProviderDraft, model: ModelDraft)
    suspend fun context(source: MaryProviderDraft, selected: MaryModelCandidate): Int?
}

@Composable
fun MarySettingsScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { AgentSettingsStore(context) }
    var draft by remember(store) { mutableStateOf<MarySettingsDraft?>(null) }
    var loading by remember(store) { mutableStateOf(true) }
    var saving by remember(store) { mutableStateOf(false) }

    LaunchedEffect(store) {
        draft = withContext(Dispatchers.IO) {
            runCatching { if (store.isReady()) MarySettingsDraft.from(store.loadMarySettings()) else null }.getOrNull()
        }
        loading = false
    }
    val discovery = remember(context) {
        object : MarySettingsDiscovery {
            override suspend fun candidates(source: MaryProviderDraft): List<MaryModelCandidate> = withContext(Dispatchers.IO) {
                val config = source.draft.connectionConfig()
                withTimeoutOrNull(30_000) {
                    ProviderFactory.create { config }.listModelDetails()
                        .filter { it.id.isNotBlank() }.distinctBy { it.id }.sortedBy { it.id }
                        .map { MaryModelCandidate(it) }
                } ?: error(context.getString(R.string.mary_settings_models_timeout))
            }

            override suspend fun test(source: MaryProviderDraft, model: ModelDraft) = withContext(Dispatchers.IO) {
                val config = model.build()
                withTimeoutOrNull(45_000) {
                    var finished = false
                    ProviderFactory.create { config }.chat(
                        ChatRequest(
                            model = config.model,
                            messages = listOf(ChatMessage(MessageRole.USER, "Reply with OK only.")),
                            tools = emptyList(), maxOutputTokens = 256,
                        )
                    ).collect { event ->
                        when (event) {
                            is ChatEvent.Finished -> {
                                check(event.finishReason in setOf("stop", "end_turn", "completed")) {
                                    "Chat ended without normal completion: ${event.finishReason}"
                                }
                                finished = true
                            }
                            is ChatEvent.ToolCallDelta -> error("Unexpected tool call in connection test")
                            else -> Unit
                        }
                    }
                    check(finished) { "Stream ended without a Finished event" }
                } ?: error(context.getString(R.string.mary_provider_chat_test_timeout))
            }

            override suspend fun context(source: MaryProviderDraft, selected: MaryModelCandidate): Int? = withContext(Dispatchers.IO) {
                val config = source.draft.connectionConfig()
                withTimeoutOrNull(15_000) {
                    ProviderFactory.create { config }.modelContextWindow(selected.details.id)
                }?.takeIf { it > 0 }
            }
        }
    }

    MarySettingsContent(
        draft = draft, onChange = { draft = it }, discovery = discovery, loading = loading,
        saving = saving, bottomInnerPadding = bottomInnerPadding, onBack = onBack,
        reasoningCatalog = MaryReasoningCatalog.index,
        modelCatalog = MaryReasoningCatalog.models.collectAsStateWithLifecycle().value,
        onSave = save@{ current ->
            if (!current.valid()) return@save
            val next = current.build()
            val keys = current.keyChanges()
            saving = true
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        if (store.saveRegistrySettings(next, keys, expected = current.original)) {
                            MarySettingsDraft.from(store.loadMarySettings())
                        } else null
                    }.getOrNull()
                }
                if (saved != null) draft = saved
                saving = false
                Toast.makeText(context,
                    if (saved != null) R.string.mary_settings_saved else R.string.mary_settings_save_failed,
                    Toast.LENGTH_LONG).show()
            }
        },
    )
}

internal data class MaryProviderResult(
    val connection: ProviderConfig,
    val candidates: List<MaryModelCandidate> = emptyList(),
    val fetchedCount: Int? = null,
    val testing: Boolean = false,
    val fetching: Boolean = false,
    val message: String? = null,
    val error: Boolean = false,
)

private fun ModelDraft.connectionIdentity() = connectionConfig().copy(model = "", temperature = null,
    maxOutputTokens = null, reasoningOverride = null, reasoningMetadata = null)

@Composable
internal fun MarySettingsContent(
    draft: MarySettingsDraft?,
    onChange: (MarySettingsDraft) -> Unit,
    discovery: MarySettingsDiscovery,
    bottomInnerPadding: Dp,
    onBack: () -> Unit,
    onSave: ((MarySettingsDraft) -> Unit)? = null,
    loading: Boolean = false,
    saving: Boolean = false,
    initialTab: MarySettingsTab = MarySettingsTab.AGENT,
    initialCandidates: Map<String, List<MaryModelCandidate>> = emptyMap(),
    reasoningCatalog: StateFlow<ReasoningCatalogIndex>? = null,
    modelCatalog: MarySettingsModelCatalog? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val agentScroll = rememberLazyListState()
    val modelScroll = rememberLazyListState()
    val providerScroll = rememberLazyListState()
    val scroll = when (tab) {
        MarySettingsTab.AGENT -> agentScroll
        MarySettingsTab.MODELS -> modelScroll
        MarySettingsTab.PROVIDERS -> providerScroll
    }
    val scrollCheckpoints = remember { mutableMapOf<MarySettingsTab, Int>() }
    LaunchedEffect(tab) {
        val offset = scrollCheckpoints[tab] ?: return@LaunchedEffect
        // Wait for the newly selected list and its native bar to be measured
        // before restoring the saved offset.
        withFrameNanos { }
        withFrameNanos { }
        scroll.scrollToItem(0, offset)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(scroll, keyboard) {
        scroll.interactionSource.interactions.collect { event ->
            if (event is DragInteraction.Start) keyboard?.hide()
        }
    }
    var modelEditor by remember { mutableStateOf<MaryModelEntry?>(null) }
    var providerEditor by remember { mutableStateOf<MaryProviderDraft?>(null) }
    var editing by remember { mutableStateOf(false) }
    var editorKey by remember { mutableStateOf(Any()) }
    var appliedModelKey by remember { mutableStateOf<Any?>(null) }
    var providerScratchResult by remember { mutableStateOf<MaryProviderResult?>(null) }
    var providerResults by remember {
        mutableStateOf(draft?.providers.orEmpty().mapNotNull { provider ->
            initialCandidates[provider.id]?.let { provider.id to MaryProviderResult(provider.draft.connectionIdentity(), it) }
        }.toMap())
    }
    val latestDraft by rememberUpdatedState(draft)
    val latestOnChange by rememberUpdatedState(onChange)
    val valid = draft?.valid() == true
    val editEnabled = !saving

    fun editModel(model: MaryModelEntry) {
        editorKey = Any()
        providerEditor = null
        modelEditor = model
        editing = true
    }
    fun editProvider(provider: MaryProviderDraft) {
        editorKey = Any()
        modelEditor = null
        providerScratchResult = null
        providerEditor = provider
        editing = true
    }
    fun resultFor(provider: MaryProviderDraft, includeScratch: Boolean = false): MaryProviderResult? {
        val identity = provider.draft.connectionIdentity()
        return providerScratchResult?.takeIf { includeScratch && it.connection == identity }
            ?: providerResults[provider.id]?.takeIf { it.connection == identity }
    }
    fun updateResult(providerId: String, result: MaryProviderResult, session: Any) {
        val open = providerEditor
        if (editing && editorKey === session && open?.id == providerId && open.draft.connectionIdentity() == result.connection) {
            providerScratchResult = result
        } else if (latestDraft?.provider(providerId)?.draft?.connectionIdentity() == result.connection) {
            providerResults = providerResults + (providerId to result)
        }
    }
    fun discover(source: MaryProviderDraft, model: ModelDraft?, fetch: Boolean) {
        val identity = source.draft.connectionIdentity()
        val previous = resultFor(source, includeScratch = true) ?: MaryProviderResult(identity)
        val session = editorKey
        updateResult(source.id, previous.copy(testing = !fetch, fetching = fetch, message = null), session)
        scope.launch {
            val result = try {
                if (fetch) {
                    val candidates = discovery.candidates(source)
                    previous.copy(candidates = candidates, fetchedCount = candidates.size, message = if (candidates.isEmpty()) {
                        context.getString(R.string.mary_settings_models_empty)
                    } else context.resources.getQuantityString(R.plurals.mary_phase_model_count, candidates.size, candidates.size), error = false)
                } else {
                    if (model != null) discovery.test(source, model) else discovery.candidates(source)
                    previous.copy(message = context.getString(if (model != null) R.string.mary_provider_chat_test_ok
                        else R.string.mary_phase_endpoint_reachable), error = false)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                previous.copy(message = ProviderRequestDiagnostics.format(error, identity,
                    if (fetch) "list models" else "test connection"), error = true)
            }
            updateResult(source.id, result, session)
        }
    }
    fun selectCandidate(state: MarySettingsDraft, entry: MaryModelEntry, candidate: MaryModelCandidate) {
        val source = state.provider(entry.providerId) ?: return
        val withConnection = state.combined(entry) ?: return
        val key = editorKey
        val selected = entry.applySuggestion(candidate, withConnection)
        modelEditor = selected
        if (candidate.context != null || selected.contextSource == MaryModelValueSource.MANUAL) return
        scope.launch {
            val capacity = try {
                discovery.context(source, candidate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return@launch
            val current = latestDraft ?: return@launch
            if (current.provider(source.id)?.draft?.connectionIdentity() != source.draft.connectionIdentity()) return@launch
            fun updated(model: MaryModelEntry): MaryModelEntry? = model.takeIf {
                it.id == entry.id && it.providerId == source.id && it.draft.provider.model == candidate.details.id &&
                    it.draft.context == selected.draft.context && it.contextSource != MaryModelValueSource.MANUAL &&
                    capacity >= 4096 && (it.draft.output.toIntOrNull() ?: 0).toLong() + capacity / 10 < capacity
            }?.copy(draft = model.draft.copy(context = capacity.toString()), contextSource = MaryModelValueSource.PROVIDER)
            if (editing && editorKey === key) modelEditor?.let(::updated)?.let { modelEditor = it }
            else if (appliedModelKey === key) current.model(entry.id)?.let(::updated)?.let {
                latestOnChange(current.updateModel(it))
            }
        }
    }

    GlassTopBarScaffold(
        title = stringResource(R.string.mary_settings_title), modifier = Modifier.fillMaxSize(),
        isContentScrolled = scroll.canScrollBackward,
        contentListState = scroll,
        navigationIcon = {
            TopBarIconButton(onClick = onBack, modifier = Modifier.testTag("mary-settings-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.scene_route_navigate_back))
            }
        },
        actions = {
            if (tab != MarySettingsTab.AGENT) {
                val models = tab == MarySettingsTab.MODELS
                TopBarIconButton(
                    modifier = Modifier.testTag(if (models) "mary-settings-model-add" else "mary-settings-provider-add"),
                    enabled = draft != null && editEnabled && (!models || draft.providers.isNotEmpty()),
                    onClick = {
                        val current = latestDraft
                        if (current != null && models) {
                            val provider = current.model(current.mainId)?.providerId ?: current.providers.firstOrNull()?.id
                            if (provider != null) editModel(MaryModelEntry(newMaryRegistryId(), provider, ModelDraft()))
                        } else if (current != null) {
                            editProvider(MaryProviderDraft(newMaryRegistryId(), R.string.mary_phase_model_provider,
                                ModelDraft(ProviderConfig(baseUrl = ""))))
                        }
                    },
                ) {
                    Icon(Icons.Filled.Add, stringResource(if (models) R.string.mary_phase_register_model
                        else R.string.mary_phase_register_provider))
                }
            }
            TopBarIconButton(
                modifier = Modifier.testTag("mary-settings-save"), enabled = valid && editEnabled && onSave != null,
                onClick = { latestDraft?.let { onSave?.invoke(it) } },
            ) {
                if (saving) CircularProgressIndicator(size = 20.dp, strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Check, stringResource(R.string.mary_settings_save))
            }
        },
        pinnedHeader = {
            Box(Modifier.fillMaxWidth().testTag("mary-settings-tabs")) {
                GlassTabs(
                    tabs = MarySettingsTab.entries.map { stringResource(it.label) },
                    selectedTabIndex = tab.ordinal,
                    onTabSelected = { index ->
                        val next = MarySettingsTab.entries[index]
                        if (next != tab) {
                            scrollCheckpoints[tab] = scroll.firstVisibleItemScrollOffset
                            tab = next
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
                )
            }
        },
    ) { pagePadding ->
        // Use the dashboard's list-driven native glass ramp. Keep each form in one
        // item so relocating a field never disposes another field in the same form.
        key(tab) {
            val density = LocalDensity.current
            val imeBottom = with(density) { WindowInsets.ime.getBottom(this).toDp() }
            val bottomObstruction = maxOf(bottomInnerPadding, pagePadding.calculateBottomPadding())
            // Shrink the scroll viewport, not the overlay host. The list can then
            // bring focused fields above the IME without also reserving covered bars.
            LazyColumn(
                modifier = Modifier.fillMaxSize().imePadding().testTag(tab.tag), state = scroll,
                contentPadding = PaddingValues(
                    start = 12.dp, end = 12.dp, top = pagePadding.calculateTopPadding() + 4.dp,
                    bottom = (bottomObstruction - imeBottom).coerceAtLeast(0.dp) + 16.dp,
                ),
            ) {
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (draft == null) {
                            if (loading) Box(Modifier.padding(16.dp)) { CircularProgressIndicator(size = 24.dp) }
                            else MarySettingsHint(stringResource(R.string.mary_settings_not_ready))
                        } else when (tab) {
                            MarySettingsTab.AGENT -> MaryAgentPage(draft, editEnabled, onChange)
                            MarySettingsTab.MODELS -> MaryModelsPage(draft, editEnabled, ::editModel)
                            MarySettingsTab.PROVIDERS -> MaryProvidersPage(draft, editEnabled, ::editProvider, result = { resultFor(it) })
                        }
                        if (draft != null && !valid) MarySettingsHint(stringResource(R.string.mary_settings_invalid))
                    }
                }
            }
        }
        if (draft != null && (modelEditor != null || providerEditor != null)) {
            modelEditor?.let { model ->
                val source = draft.provider(model.providerId)
                val baseUrl = source?.draft?.provider?.baseUrl.orEmpty().trim()
                val protocol = source?.draft?.provider?.protocol
                val metadata = remember(modelCatalog, baseUrl, protocol) {
                    if (protocol == null || baseUrl.isEmpty()) emptyList() else modelCatalog?.models(baseUrl, protocol).orEmpty()
                }
                val fetched = source?.let { resultFor(it) }?.takeIf { it.fetchedCount != null || it.candidates.isNotEmpty() }?.candidates
                val candidates = remember(fetched, metadata) { mergeMaryModelCandidates(fetched, metadata) }
                val currentModelId = model.draft.provider.model.trim()
                val suggestion = candidates.firstOrNull { it.details.id == currentModelId }
                    ?: metadata.firstOrNull { it.details.id == currentModelId }
                MaryModelEditorSheet(
                    show = editing, sessionKey = editorKey, model = model, state = draft,
                    reasoningCatalog = reasoningCatalog?.collectAsStateWithLifecycle()?.value ?: ReasoningCatalogIndex.Empty,
                    candidates = candidates, suggestion = suggestion,
                    onEdit = { modelEditor = it }, onCandidate = { selectCandidate(draft, model, it) },
                    onConfirm = {
                        val current = latestDraft
                        val edited = modelEditor
                        if (current != null && edited != null) {
                            val updated = if (current.model(edited.id) == null) current.copy(
                                models = current.models + edited,
                                mainId = if (current.model(current.mainId)?.draft?.provider?.model.isNullOrBlank()) edited.id else current.mainId,
                            ) else current.updateModel(edited)
                            appliedModelKey = editorKey
                            latestOnChange(updated)
                            editing = false
                        }
                    },
                    onDismiss = { editing = false },
                    onRemove = {
                        latestDraft?.let { current ->
                            latestOnChange(current.removeModel(model.id))
                            editing = false
                        }
                    },
                )
            }
            providerEditor?.let { provider ->
                MaryProviderEditorSheet(
                    show = editing, sessionKey = editorKey, provider = provider,
                    registering = draft.provider(provider.id) == null,
                    onEdit = { change ->
                        val changedConnection = change.draft.connectionIdentity() != provider.draft.connectionIdentity()
                        if (changedConnection) providerScratchResult = null
                        providerEditor = if (changedConnection) change.copy(modelCount = null) else change
                    },
                    onConfirm = {
                        val current = latestDraft
                        val edited = providerEditor
                        if (current != null && edited != null) {
                            val result = providerScratchResult?.takeIf { it.connection == edited.draft.connectionIdentity() &&
                                !it.testing && !it.fetching }
                            if (result != null) providerResults = providerResults + (edited.id to result)
                            val next = edited.copy(modelCount = result?.fetchedCount ?: edited.modelCount,
                                draft = edited.draft.copy(provider = edited.draft.provider.copy(baseUrl = edited.draft.provider.baseUrl.trim())))
                            latestOnChange(if (current.provider(next.id) == null) current.copy(providers = current.providers + next)
                                else current.updateProvider(next))
                            editing = false
                        }
                    },
                    onDismiss = {
                        providerScratchResult = null
                        providerEditor = provider.copy(draft = provider.draft.copy(keyDraft = ""))
                        editing = false
                    },
                    onRemove = {
                        latestDraft?.let { current ->
                            latestOnChange(current.removeProvider(provider.id))
                            providerResults = providerResults - provider.id
                            providerScratchResult = null
                            providerEditor = provider.copy(draft = provider.draft.copy(keyDraft = ""))
                            editing = false
                        }
                    },
                    status = resultFor(provider, includeScratch = true),
                    onTest = {
                        val temporary = if (draft.provider(provider.id) == null) draft.copy(providers = draft.providers + provider)
                            else draft.updateProvider(provider)
                        val preferred = listOfNotNull(temporary.model(temporary.mainId), temporary.model(temporary.approvalId))
                        val model = (preferred + temporary.models).asSequence()
                            .filter { it.providerId == provider.id && it.draft.provider.model.isNotBlank() }
                            .mapNotNull(temporary::combined).firstOrNull { it.valid() }
                        discover(provider, model, fetch = false)
                    },
                    onFetch = { discover(provider, model = null, fetch = true) },
                )
            }
        }
    }
}

/** Isolated legacy popup regression; the settings toolbar no longer exposes presets. */
@Composable
internal fun MaryProviderPresetMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean,
    independentApproval: Boolean,
    onPreset: (forApproval: Boolean, preset: ProviderPreset) -> Unit,
) {
    val targets = if (independentApproval) listOf(false, true) else listOf(false)
    val choices = targets.flatMap { forApproval -> ProviderPreset.entries.map { forApproval to it } }
    GlassAnchoredMenu(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        enabled = enabled,
        options = choices.map { (forApproval, preset) ->
            val target = stringResource(if (forApproval) R.string.mary_settings_ui_approval_draft
                else R.string.mary_settings_ui_dialogue_draft)
            GlassMenuOption(title = if (independentApproval)
                stringResource(R.string.mary_settings_ui_preset_target, preset.label, target)
                else preset.label)
        },
        onOptionClick = { index -> choices[index].let { (forApproval, preset) -> onPreset(forApproval, preset) } },
    ) {
        TopBarIconButton(
            modifier = Modifier.testTag("mary-settings-presets"),
            onClick = { onExpandedChange(true) },
            enabled = enabled,
        ) { Icon(Icons.Outlined.Tune, stringResource(R.string.mary_provider_preset)) }
    }
}
