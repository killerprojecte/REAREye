package hk.uwu.reareye.agent

import ai.koog.prompt.message.MessagePart
import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import hk.uwu.reareye.agent.context.ContextBudget
import hk.uwu.reareye.agent.context.ContextBudgetDecision
import hk.uwu.reareye.agent.data.AgentAuditLog
import hk.uwu.reareye.agent.data.AgentSession
import hk.uwu.reareye.agent.data.AgentSessionStore
import hk.uwu.reareye.agent.data.AgentSessionSummary
import hk.uwu.reareye.agent.data.AgentSettingsSource
import hk.uwu.reareye.agent.data.AgentSettingsStore
import hk.uwu.reareye.agent.data.AuditEntry
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.interaction.AskAnswer
import hk.uwu.reareye.agent.interaction.AskOption
import hk.uwu.reareye.agent.interaction.AskRequest
import hk.uwu.reareye.agent.interaction.InteractionPurpose
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.loop.AgentEvent
import hk.uwu.reareye.agent.loop.AgentLoop
import hk.uwu.reareye.agent.loop.ToolCallRequest
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.EmptyToolEnvironment
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.provider.KoogConnection
import hk.uwu.reareye.agent.provider.OpenAiCompatibleProvider
import hk.uwu.reareye.agent.provider.ProviderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

sealed interface ChatEntry {
    val id: String
    data class UserMessage(override val id: String, val text: String) : ChatEntry
    data class AssistantMessage(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
        val reasoning: String = ""
    ) : ChatEntry
    data class ToolCall(
        override val id: String,
        val name: String,
        val argumentsJson: String,
        val result: ToolResult? = null,
        val running: Boolean = false,
        val callId: String = id,
        /** Identity in the pair-complete display transcript; absent for uncommitted stream rows. */
        val transcriptId: String? = null,
    ) : ChatEntry

    /** Host questions are UI rows, never synthetic model tool calls. */
    data class Question(
        override val id: String,
        val request: AskRequest,
        val answer: AskAnswer? = null,
        val interrupted: Boolean = false,
    ) : ChatEntry
    data class Notice(override val id: String, val text: String, val isError: Boolean) : ChatEntry
}

data class PendingApproval(
    val request: ToolCallRequest,
    val decision: CompletableDeferred<Boolean>,
    val id: String = UUID.randomUUID().toString(),
)

data class AgentUiState(
    val entries: List<ChatEntry> = emptyList(),
    val running: Boolean = false,
    val pendingApproval: PendingApproval? = null,
    val status: String? = null,
    val reasoning: String = "",
    val pendingAsk: AskRequest? = null,
    val locked: Boolean = false,
    val usage: ChatEvent.Usage? = null,
    val contextWindowTokens: Int = 128_000,
    val estimatedInputTokens: Int? = null,
    /** Public capability metadata only; credentials are never exposed to the UI. */
    val provider: ProviderConfig = ProviderConfig(),
    /** Configuration IO is in progress; sending is blocked, selections may still be queued. */
    val configuring: Boolean = false,
    val approvalMode: ApprovalMode = ApprovalMode.ASK,
)

/** Call IDs may be reused in later turns; only the newest executing row owns an interaction. */
internal fun pendingInteractionToolRowId(entries: List<ChatEntry>, callId: String?): String? {
    if (callId == null) return null
    return entries.lastOrNull { it is ChatEntry.ToolCall && it.running && it.callId == callId }?.id
}

/** Session-scoped event ownership prevents a cancelled turn from mutating another conversation. */
class MaryController(
    context: Context,
    private val scope: CoroutineScope,
    private val tools: ToolEnvironment = EmptyToolEnvironment,
    private val settings: AgentSettingsSource = AgentSettingsStore(context.applicationContext),
    private val store: AgentSessionStore = AgentSessionStore(context.applicationContext.filesDir),
    private val connectionOverride: ((ProviderConfig) -> KoogConnection)? = null,
    private val resolveText: (Int) -> String = context.applicationContext::getString,
) {
    private val appContext = context.applicationContext
    private val auditLog = AgentAuditLog(appContext.filesDir)
    private val client = OpenAiCompatibleProvider.defaultClient()
    private val _state = MutableStateFlow(AgentUiState(running = true))
    val state: StateFlow<AgentUiState> = _state.asStateFlow()
    private var session: AgentSession? = null
    private var turnJob: Job? = null
    private val retiredJobs = mutableListOf<Job>()
    private var generation = 0L
    private var metadataConfig: AgentConfig? = null
    private var configurationJob: Job? = null

    private data class ConfigurationChange(
        val effort: ReasoningEffort? = null,
        val approval: ApprovalMode? = null
    )

    private val configurationLock = Any()
    private val configurationChanges = ArrayDeque<ConfigurationChange>()
    private var askDecision: Pair<String, CompletableDeferred<AskAnswer>>? = null
    val sessionId: String? get() = session?.id

    init {
        refreshConfiguration()
        val owner = generation
        turnJob = scope.launch {
            try {
                val recent = withContext(Dispatchers.IO) {
                    store.list().firstOrNull()?.let { store.load(it.id) }
                }
                configurationJob?.join()
                if (owner == generation) showSession(recent)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logMaryRuntime(MaryRuntimePhase.INITIAL_HISTORY, e)
                if (owner == generation) {
                    showSession(null)
                    appendNotice(e.message ?: "Could not load conversation.", true)
                }
            }
        }
    }

    /** Safe to call from the UI's LaunchedEffect, including while a turn is running. */
    fun refreshConfiguration() = configure(null)

    fun setReasoningEffort(effort: ReasoningEffort) {
        val current = _state.value
        if (current.running || current.locked || current.pendingApproval != null ||
            effort !in ReasoningPolicy.support(current.provider).levels
        ) return
        configure(effort)
    }

    fun setApprovalMode(mode: ApprovalMode) {
        val current = _state.value
        if (current.running || current.locked || current.pendingApproval != null) {
            logMaryApproval(
                "phase=settings reason=mode_change_blocked requested=$mode running=${current.running}" +
                        " locked=${current.locked} pendingApproval=${current.pendingApproval != null}"
            )
            return
        }
        logMaryApproval("phase=settings reason=mode_change_enqueued requested=$mode")
        enqueueConfiguration(ConfigurationChange(approval = mode))
    }

    private fun configure(effort: ReasoningEffort?) =
        enqueueConfiguration(ConfigurationChange(effort = effort))

    /** Refresh, effort and approval writes share one FIFO worker. No pending choice is dropped. */
    private fun enqueueConfiguration(change: ConfigurationChange) {
        synchronized(configurationLock) {
            configurationChanges.addLast(change)
            // Synchronous gate: send cannot capture a snapshot before this write starts.
            _state.update { it.copy(configuring = true) }
            if (configurationJob?.isActive == true) return
            configurationJob =
                scope.launch(start = CoroutineStart.LAZY) { drainConfigurationChanges() }
            configurationJob!!.start()
        }
    }

    private suspend fun drainConfigurationChanges() {
        val workerJob = kotlinx.coroutines.currentCoroutineContext()[Job]
        try {
            while (true) {
                val change = synchronized(configurationLock) {
                    if (configurationChanges.isEmpty()) {
                        configurationJob = null
                        _state.update { it.copy(configuring = false) }
                        return
                    }
                    configurationChanges.removeFirst()
                }
                try {
                    val preferences = withContext(Dispatchers.IO) {
                        if (change.effort == null && change.approval == null) settings.loadMarySettings()
                        else settings.updateMarySettings { latest ->
                            val provider = latest.configuration.provider
                            val config = if (change.effort != null &&
                                change.effort in ReasoningPolicy.support(provider).levels
                            )
                                latest.configuration.copy(provider = provider.copy(reasoningEffort = change.effort))
                            else latest.configuration
                            latest.copy(
                                configuration = config,
                                approvalMode = change.approval ?: latest.approvalMode
                            )
                        }
                    }
                    logMaryApproval(
                        "phase=settings reason=${if (change.approval == null) "loaded" else "mode_change_saved"}" +
                                " mode=${preferences.approvalMode} readOnlySetting=${preferences.configuration.autoApproveReadOnly}" +
                                " inheritReviewModel=${preferences.inheritApprovalModel}"
                    )
                    synchronized(configurationLock) {
                        // Only durable values are published, in FIFO order. A later failed write
                        // therefore leaves the last successful value visible, never a guessed save.
                        val config = preferences.configuration
                        metadataConfig =
                            config.copy(provider = CapabilityScope.publicView(config.provider))
                        _state.update {
                            it.copy(
                                provider = metadataConfig!!.provider,
                                approvalMode = preferences.approvalMode,
                                contextWindowTokens = config.provider.contextWindowTokens
                            )
                        }
                        if (!_state.value.running) refreshContextEstimate()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logMaryRuntime(
                        MaryRuntimePhase.CONFIGURATION,
                        e,
                        metadataConfig?.provider?.protocol
                    )
                    if (change.effort == null && change.approval == null) {
                        metadataConfig = null
                        _state.update {
                            it.copy(
                                provider = ProviderConfig(),
                                estimatedInputTokens = null
                            )
                        }
                    }
                    logMaryApproval(
                        "phase=settings reason=load_or_save_failed requested=${change.approval ?: "unchanged"}" +
                                " exceptionType=${e.javaClass.simpleName}"
                    )
                    // Retain the last durable selection on failed saves; allow retry or queued recovery.
                    appendNotice(e.message ?: "Cannot save Mary settings", true)
                }
            }
        } finally {
            synchronized(configurationLock) {
                // A newer worker may already have started after this one drained.
                if (configurationJob == workerJob) {
                    configurationJob = null
                    configurationChanges.clear()
                    _state.update { it.copy(configuring = false) }
                }
            }
        }
    }

    private fun refreshContextEstimate() {
        val estimate = runCatching {
            val config = metadataConfig?.takeIf { it.provider.isUsable } ?: return@runCatching null
            ContextBudget.estimateInput(
                config.systemPrompt + languageInstruction(),
                tools.availableTools(),
                session?.let { it.contextMessages ?: it.messages }.orEmpty()
            )
        }.onFailure {
            logMaryRuntime(
                MaryRuntimePhase.CONTEXT_ESTIMATE,
                it,
                metadataConfig?.provider?.protocol
            )
        }.getOrNull()
        _state.update { it.copy(estimatedInputTokens = estimate) }
    }

    fun sessions(): List<AgentSessionSummary> = store.list()

    fun deleteSession(id: String) {
        if (session?.id == id) newSession()
        scope.launch {
            try {
                awaitPreviousTurns(); withContext(Dispatchers.IO) { store.delete(id) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logMaryRuntime(MaryRuntimePhase.DELETE_HISTORY, e)
                appendNotice(
                    e.message ?: "Delete failed.",
                    true
                )
            }
        }
    }

    fun newSession() {
        cancel()
        showSession(null)
        refreshConfiguration()
    }

    fun loadSession(id: String) {
        cancel()
        refreshConfiguration()
        val owner = generation
        _state.update { it.copy(running = true) }
        turnJob = scope.launch {
            try {
                awaitPreviousTurns()
                val loaded = withContext(Dispatchers.IO) { store.load(id) }
                configurationJob?.join()
                if (owner == generation) showSession(loaded)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logMaryRuntime(MaryRuntimePhase.LOAD_HISTORY, e)
                if (owner == generation) {
                    _state.update { it.copy(running = false) }; appendNotice(
                        e.message ?: "Load failed.", true
                    )
                }
            }
        }
    }

    fun lockSession() {
        val id = session?.id ?: return
        cancel()
        val owner = generation
        // Optimistic read-only state cannot be undone by cancelling a durable lock write.
        session = session?.copy(locked = true)
        _state.update { it.copy(running = true, locked = true) }
        turnJob = scope.launch {
            try {
                awaitPreviousTurns()
                val locked = withContext(Dispatchers.IO) {
                    val latest = store.load(id) ?: error("Conversation no longer exists")
                    latest.copy(locked = true).also { store.save(it) }
                }
                if (owner == generation) showSession(locked)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logMaryRuntime(MaryRuntimePhase.LOCK_HISTORY, e)
                if (owner == generation) {
                    _state.update { it.copy(running = false) }
                    appendNotice(
                        e.message ?: "Lock failed; reload the conversation to verify its state.",
                        true
                    )
                }
            }
        }
    }

    fun cancel() {
        generation++
        turnJob?.let { it.cancel(); synchronized(retiredJobs) { retiredJobs += it } }
        turnJob = null
        askDecision?.second?.cancel()
        askDecision = null
        _state.value.pendingApproval?.decision?.cancel()
        _state.update { current ->
            current.copy(
                running = false, pendingApproval = null, pendingAsk = null, status = null,
                entries = stopStreaming(
                    recoverToolResults(
                        current.entries,
                        session?.messages.orEmpty()
                    )
                )
            )
        }
    }

    fun answerApproval(approved: Boolean, interactionId: String? = null): Boolean {
        val pending = _state.value.pendingApproval ?: return false
        if (interactionId != null && interactionId != pending.id || !pending.decision.isActive) return false
        _state.update { it.copy(pendingApproval = null) }
        return pending.decision.complete(approved)
    }

    fun answerAsk(id: String, selectedIds: List<String>, customText: String? = null): Boolean {
        val pending = _state.value.pendingAsk ?: return false
        val decision = askDecision ?: return false
        if (pending.id != id || decision.first != id || !decision.second.isActive) return false
        val answer = AskAnswer(selectedIds, customText?.trim()?.takeIf { it.isNotEmpty() })
        if (runCatching { pending.validateAnswer(answer) }.isFailure) return false
        // Clear pending and project the answer before resuming execution, which can emit synchronously.
        _state.update { current ->
            current.copy(pendingAsk = null, entries = current.entries.map { entry ->
                if (entry is ChatEntry.Question && entry.request.id == id) entry.copy(answer = answer) else entry
            })
        }
        return decision.second.complete(answer)
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.running || _state.value.configuring || session?.locked == true) return
        val owner = ++generation
        _state.update { it.copy(running = true, status = null, usage = null) }
        turnJob = scope.launch {
            var completed = false
            var partialSnapshot: AgentSession? = null
            var partialText = ""
            var inputCorrection = 0
            var lastRequestEstimate = 0
            var activeConnection: KoogConnection? = null
            var runtimePhase = MaryRuntimePhase.SEND_SETTINGS
            var runtimeProtocol: Protocol? = null
            try {
                awaitPreviousTurns()
                val preferences = try {
                    withContext(Dispatchers.IO) { settings.loadMarySettings() }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    assertOwner(owner)
                    metadataConfig = null
                    _state.update {
                        it.copy(
                            provider = ProviderConfig(),
                            estimatedInputTokens = null
                        )
                    }
                    throw e
                }
                assertOwner(owner)
                val original = preferences.configuration
                runtimeProtocol = original.provider.protocol
                metadataConfig = original.copy(provider = original.provider.copy(apiKey = ""))
                _state.update {
                    it.copy(
                        provider = metadataConfig!!.provider,
                        contextWindowTokens = original.provider.contextWindowTokens
                    )
                }
                val config =
                    original.copy(systemPrompt = original.systemPrompt + languageInstruction())
                if (!config.provider.isUsable) {
                    appendNotice(
                        "Configure the model URL and model name in Mary settings first.",
                        true
                    )
                    return@launch
                }
                runtimePhase = MaryRuntimePhase.SEND_HISTORY
                val currentId = session?.id
                val active = withContext(Dispatchers.IO) {
                    if (currentId == null) store.create() else store.load(currentId)
                        ?: error("Conversation no longer exists")
                }
                if (owner != generation) return@launch
                if (active.locked) {
                    showSession(active); return@launch
                }
                val user = ChatMessage(MessageRole.USER, prompt)
                val fullTranscript = active.messages + user
                val history = (active.contextMessages ?: active.messages) + user
                session = active.copy(
                    messages = fullTranscript, contextMessages = history, interrupted = true,
                    pendingInteraction = null,
                    title = if (active.messages.isEmpty()) AgentSessionStore.deriveTitle(
                        fullTranscript
                    ) else active.title
                )
                saveCurrent(owner)
                _state.update {
                    it.copy(
                        entries = it.entries + ChatEntry.UserMessage(newId(), prompt),
                        provider = config.provider.copy(apiKey = ""),
                        contextWindowTokens = config.provider.contextWindowTokens
                    )
                }
                runtimePhase = MaryRuntimePhase.CONNECTION_INIT
                val connection = connectionFor(config.provider).also { activeConnection = it }
                runtimePhase = MaryRuntimePhase.ENGINE_RUN
                val reviewConfig =
                    if (preferences.inheritApprovalModel) config.provider else preferences.approvalProvider
                // These are host-observed human answers for this turn, never model/tool claims.
                val reviewUserDecisions = ArrayDeque<MaryApprovalUserDecision>()
                logMaryApproval(
                    "phase=turn reason=effective_settings mode=${preferences.approvalMode}" +
                            " uiMode=${_state.value.approvalMode} readOnlySetting=${config.autoApproveReadOnly}" +
                            " inheritReviewModel=${preferences.inheritApprovalModel} reviewConfigured=${reviewConfig.isUsable}" +
                            " reviewProtocol=${reviewConfig.protocol} reviewContextWindow=${reviewConfig.contextWindowTokens}"
                )
                suspend fun askUser(request: AskRequest): AskAnswer = awaitAsk(owner, request)
                val loop = AgentLoop(
                    connection = connection, tools = tools, config = config,
                    approvalMode = preferences.approvalMode,
                    approvalDiagnostic = ::logMaryApproval,
                    approve = { request ->
                        assertOwner(owner)
                        val pending = PendingApproval(request, CompletableDeferred())
                        persistInteraction(
                            owner,
                            "approval",
                            request.name + " " + request.argumentsJson
                        )
                        _state.update { it.copy(pendingApproval = pending, status = null) }
                        val approved = pending.decision.await()
                        assertOwner(owner)
                        persistInteraction(owner, null, null)
                        reviewUserDecisions.addLast(MaryApprovalUserDecision(request, approved))
                        if (reviewUserDecisions.size > 16) reviewUserDecisions.removeFirst()
                        recordAudit(
                            active.id,
                            request.name,
                            request.argumentsJson,
                            approved,
                            "user approval: $approved",
                            0
                        )
                        approved
                    },
                    autoApprove = { request ->
                        assertOwner(owner)
                        _state.update { it.copy(status = "Reviewing tool permission") }
                        // Inherit settings, not transport ownership: a failed/timed-out review must
                        // not poison the primary graph's native connection before human fallback.
                        val reviewStartedAt = System.nanoTime()
                        if (!reviewConfig.isUsable) {
                            logMaryApproval(
                                "phase=review reason=configuration_unusable callHash=${
                                    maryApprovalCallHash(
                                        request.id
                                    )
                                }"
                            )
                        }
                        val result = connectionFor(reviewConfig).use { reviewer ->
                            MaryModelOperations.review(
                                reviewer, reviewConfig, prompt, request,
                                contextMessages = session?.messages ?: fullTranscript,
                                userDecisions = reviewUserDecisions.toList()
                            )
                        }
                        assertOwner(owner)
                        recordAudit(
                            active.id,
                            request.name,
                            request.argumentsJson,
                            result == hk.uwu.reareye.agent.interaction.ApprovalDecision.ALLOW,
                            "model approval: ${result.name}",
                            (System.nanoTime() - reviewStartedAt) / 1_000_000
                        )
                        result
                    },
                    ask = ::askUser,
                    checkBudget = { budget ->
                        assertOwner(owner)
                        lastRequestEstimate = budget.estimatedInputTokens
                        val calibratedEstimate =
                            (lastRequestEstimate.toLong() + inputCorrection).coerceAtMost(Int.MAX_VALUE.toLong())
                                .toInt()
                        _state.update { it.copy(estimatedInputTokens = calibratedEstimate) }
                        val overBudget =
                            calibratedEstimate.toLong() + budget.outputReserveTokens + budget.contextWindowTokens / 10 >= budget.contextWindowTokens
                        if (!overBudget) ContextBudgetDecision.Continue(budget.messages)
                        else {
                            val request = AskRequest(
                                newId(),
                                resolveText(hk.uwu.reareye.R.string.mary_context_question),
                                listOf(
                                    AskOption(
                                        "compact",
                                        resolveText(hk.uwu.reareye.R.string.mary_context_compact)
                                    ),
                                    AskOption(
                                        "new",
                                        resolveText(hk.uwu.reareye.R.string.mary_context_new)
                                    )
                                ),
                                multiple = false,
                                allowCustom = false,
                                purpose = InteractionPurpose.CONTEXT_COMPACTION
                            )
                            val answer = (budget.ask ?: ::askUser)(request)
                            assertOwner(owner)
                            if (answer.selectedIds.single() == "new") {
                                val previous = checkNotNull(session).copy(
                                    interrupted = false,
                                    pendingInteraction = null
                                )
                                val successor = withContext(Dispatchers.IO) {
                                    store.lockAndCreateSuccessor(previous)
                                }
                                assertOwner(owner)
                                generation++
                                showSession(successor)
                                ContextBudgetDecision.Stop("The previous conversation was locked and a new conversation was opened.")
                            } else {
                                _state.update { it.copy(status = "Compressing context") }
                                ContextBudgetDecision.Compress
                            }
                        }
                    },
                )
                val startedAt = mutableMapOf<String, Long>()
                loop.run(history, fullTranscript).collect { event ->
                    assertOwner(owner)
                    when (event) {
                        is AgentEvent.TextDelta -> {
                            partialText += event.text
                            val current = checkNotNull(session)
                            val partial = ChatMessage(MessageRole.ASSISTANT, partialText)
                            partialSnapshot = current.copy(
                                messages = current.messages + partial,
                                contextMessages = (current.contextMessages
                                    ?: current.messages) + partial,
                                interrupted = true
                            )
                            handleEvent(
                                event,
                                startedAt,
                                active.id,
                                preferences.approvalMode.name,
                                owner
                            )
                        }

                        is AgentEvent.Checkpoint -> {
                            // Pair-complete durability snapshots contain recovery-only tool errors.
                            // Do not render them while this turn owns execution: tool events own live rows.
                            partialText = ""
                            partialSnapshot = null
                            session = session?.copy(
                                messages = event.fullTranscript,
                                contextMessages = event.messages
                            )
                            // Bind only committed calls, without projecting their recovery-only errors.
                            _state.update {
                                it.copy(
                                    entries = bindToolTranscript(
                                        it.entries,
                                        event.fullTranscript
                                    )
                                )
                            }
                            saveCurrent(owner)
                        }

                        is AgentEvent.TurnFinished -> {
                            session = session?.copy(
                                messages = event.fullTranscript,
                                contextMessages = event.messages,
                                interrupted = false,
                                pendingInteraction = null
                            )
                            saveCurrent(owner)
                            completed = true
                            _state.update { it.copy(entries = stopStreaming(it.entries)) }
                        }

                        is AgentEvent.ContextCompressed -> {
                            inputCorrection = 0
                            lastRequestEstimate = event.estimatedInputTokens
                            _state.update { it.copy(estimatedInputTokens = lastRequestEstimate) }
                            appendNotice(
                                "Context compressed; the original conversation remains in history.",
                                false
                            )
                        }

                        is AgentEvent.Usage -> {
                            event.usage.inputTokens?.let { actual ->
                                inputCorrection = (actual - lastRequestEstimate).coerceAtLeast(0)
                            }
                            _state.update { it.copy(usage = mergeUsage(it.usage, event.usage)) }
                        }

                        else -> handleEvent(
                            event,
                            startedAt,
                            active.id,
                            preferences.approvalMode.name,
                            owner
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                logMaryRuntime(runtimePhase, e, runtimeProtocol)
                if (owner == generation) appendNotice(e.message ?: "The request failed.", true)
            } finally {
                // Context ownership is per turn; closing an executor must not close the shared app OkHttp client.
                activeConnection?.let { connection ->
                    runCatching { connection.close() }.onFailure {
                        logMaryRuntime(MaryRuntimePhase.CONNECTION_CLOSE, it, runtimeProtocol)
                    }
                }
                // Keep partial text on cancellation/network failure, never incomplete tool calls.
                // Transitions join this job before touching the same session.
                partialSnapshot?.let { snapshot ->
                    withContext(NonCancellable + Dispatchers.IO) {
                        runCatching { store.save(snapshot) }.onFailure {
                            logMaryRuntime(MaryRuntimePhase.PARTIAL_SAVE, it, runtimeProtocol)
                        }
                    }
                    if (owner == generation) session = snapshot
                }
                if (owner == generation) {
                    askDecision = null
                    refreshContextEstimate()
                    _state.update {
                        it.copy(
                            running = false,
                            pendingApproval = null,
                            pendingAsk = null,
                            status = null,
                            entries = stopStreaming(
                                recoverToolResults(
                                    it.entries,
                                    session?.messages.orEmpty()
                                )
                            )
                        )
                    }
                    if (!completed) session = session?.copy(interrupted = true)
                }
            }
        }
    }

    private suspend fun awaitAsk(owner: Long, request: AskRequest): AskAnswer {
        assertOwner(owner)
        val normalized = request.copy(id = newId())
        normalized.validate()
        val deferred = CompletableDeferred<AskAnswer>()
        askDecision = normalized.id to deferred
        persistInteraction(
            owner,
            "question",
            normalized.question + "\n" + normalized.options.joinToString("\n") { "${it.id}: ${it.label}" })
        _state.update { current ->
            val asked = normalized.toolCallId == null
            val entries = if (asked) flushReasoning(current.entries, current.reasoning) + ChatEntry.Question(
                normalized.id,
                normalized
            )
            else current.entries
            current.copy(
                entries = entries,
                reasoning = if (asked) "" else current.reasoning,
                pendingAsk = normalized,
                status = null
            )
        }
        val answer = deferred.await()
        assertOwner(owner)
        normalized.validateAnswer(answer)
        askDecision = null
        persistInteraction(owner, null, null)
        recordAudit(
            checkNotNull(session).id, "Ask", normalized.purpose.name, true,
            "selected=${answer.selectedIds}; custom=${answer.customText.orEmpty().take(1000)}", 0
        )
        return answer
    }

    private suspend fun persistInteraction(owner: Long, kind: String?, detail: String?) {
        assertOwner(owner)
        val payload = kind?.let {
            buildJsonObject {
                put("kind", it); put(
                "detail",
                detail.orEmpty()
            )
            }.toString()
        }
        session = session?.copy(pendingInteraction = payload)
        saveCurrent(owner)
    }

    private suspend fun awaitPreviousTurns() {
        val previous = synchronized(retiredJobs) { retiredJobs.toList() }
        // Other transitions must see the same barrier until every retired write has completed.
        withContext(NonCancellable) { previous.forEach { it.join() } }
        synchronized(retiredJobs) { retiredJobs.removeAll { it.isCompleted } }
    }

    private suspend fun saveCurrent(owner: Long) {
        assertOwner(owner)
        val snapshot = session ?: return
        withContext(Dispatchers.IO) { store.save(snapshot) }
        assertOwner(owner)
    }

    private fun assertOwner(owner: Long) {
        if (owner != generation) throw CancellationException("This turn no longer owns the conversation")
    }

    private fun showSession(value: AgentSession?) {
        session = value
        val previous = _state.value
        _state.value = AgentUiState(
            entries = renderHistory(value?.messages.orEmpty()), locked = value?.locked == true,
            provider = previous.provider, contextWindowTokens = previous.contextWindowTokens,
            configuring = previous.configuring, approvalMode = previous.approvalMode
        )
        refreshContextEstimate()
        if (value?.interrupted == true) {
            appendNotice(
                "The previous turn was interrupted. Previously executed tools are not retried automatically. Check any uncertain operation before continuing." +
                    value.pendingInteraction?.let { "\nPending interaction: $it" }.orEmpty(),
                true
            )
        }
    }

    private suspend fun handleEvent(
        event: AgentEvent,
        startedAt: MutableMap<String, Long>,
        activeId: String,
        approvalPolicy: String,
        owner: Long
    ) {
        when (event) {
            is AgentEvent.TurnStarted -> _state.update {
                it.copy(
                    status = null,
                    reasoning = "",
                    usage = null
                )
            }

            is AgentEvent.ReasoningDelta -> _state.update { it.copy(reasoning = it.reasoning + event.text) }
            is AgentEvent.Notice -> appendNotice(event.text, false)
            is AgentEvent.TextDelta -> _state.update { current ->
                val entries = current.entries.toMutableList()
                val last = entries.lastOrNull()
                if (last is ChatEntry.AssistantMessage && last.streaming) entries[entries.lastIndex] =
                    last.copy(text = last.text + event.text)
                else entries += ChatEntry.AssistantMessage(
                    newId(),
                    event.text,
                    true,
                    current.reasoning
                )
                current.copy(entries = entries, reasoning = "")
            }
            is AgentEvent.ToolCallStarted -> _state.update { current ->
                current.copy(
                    // A tool call closes the thought that led to it: freeze the buffer into its own
                    // row before the card, or the approval renders above the reasoning that asked
                    // for it.
                    entries = flushReasoning(current.entries, current.reasoning) + ChatEntry.ToolCall(
                        newId(),
                        event.name,
                        "",
                        running = true,
                        callId = event.id
                    ), reasoning = "", status = event.name
                )
            }
            is AgentEvent.ToolCallArguments -> _state.update { current ->
                val index =
                    current.entries.indexOfLast { it is ChatEntry.ToolCall && it.callId == event.id && it.running }
                current.copy(entries = current.entries.mapIndexed { i, entry ->
                    if (i == index && entry is ChatEntry.ToolCall) entry.copy(argumentsJson = entry.argumentsJson + event.fragment) else entry
                })
            }
            is AgentEvent.ToolCallFinished -> {
                startedAt[event.request.id] = System.currentTimeMillis()
                _state.update { current ->
                    val index =
                        current.entries.indexOfLast { it is ChatEntry.ToolCall && it.callId == event.request.id && it.running }
                    val missing = index < 0
                    val entries = if (missing) flushReasoning(current.entries, current.reasoning) +
                            ChatEntry.ToolCall(
                        newId(),
                        event.request.name,
                        event.request.argumentsJson,
                        running = true,
                        callId = event.request.id
                    )
                    else current.entries.mapIndexed { i, entry ->
                        if (i == index && entry is ChatEntry.ToolCall) entry.copy(
                            name = event.request.name,
                            argumentsJson = event.request.argumentsJson
                        ) else entry
                    }
                    current.copy(entries = entries, reasoning = if (missing) "" else current.reasoning)
                }
            }
            is AgentEvent.ToolResultEvent -> {
                val duration = startedAt.remove(event.id)?.let { System.currentTimeMillis() - it } ?: 0
                recordAudit(
                    activeId,
                    event.name,
                    "",
                    !event.result.isError,
                    "approval_policy=$approvalPolicy; " + event.result.content.take(3900),
                    duration
                )
                // Audit I/O can suspend across a session switch; recheck before touching UI.
                assertOwner(owner)
                _state.update { current ->
                    val index =
                        current.entries.indexOfLast { it is ChatEntry.ToolCall && it.callId == event.id && it.running }
                    current.copy(entries = current.entries.mapIndexed { i, entry ->
                        if (i == index && entry is ChatEntry.ToolCall) entry.copy(
                            result = event.result,
                            running = false
                        ) else entry
                    }, status = null)
                }
            }

            is AgentEvent.Failed -> {
                logMaryRuntime(
                    MaryRuntimePhase.ENGINE_FAILED,
                    event.error,
                    metadataConfig?.provider?.protocol
                )
                appendNotice(event.error.message ?: "The request failed.", true)
            }
            else -> Unit
        }
    }

    private suspend fun recordAudit(
        sessionId: String,
        toolName: String,
        arguments: String,
        success: Boolean,
        detail: String?,
        durationMs: Long
    ) {
        withContext(Dispatchers.IO) {
            auditLog.append(
                AuditEntry(
                    System.currentTimeMillis(),
                    sessionId,
                    toolName,
                    arguments.take(400),
                    success,
                    detail,
                    durationMs
                )
            )
        }
    }

    /**
     * A thought that outlives the tool call it justified.
     *
     * Reasoning streams into [AgentUiState.reasoning] and only becomes a transcript row when the
     * first answer token arrives. A turn that goes straight from thinking to a tool call would
     * therefore paint the approval prompt (which belongs to the tool card) above the chain of
     * thought that asked for it. Freezing the buffer in front of that card keeps the transcript in
     * the order it happened: why, then what.
     */
    private fun flushReasoning(
        entries: List<ChatEntry>,
        reasoning: String,
    ): List<ChatEntry> = if (reasoning.isBlank()) entries else entries + ChatEntry.AssistantMessage(
        id = newId(),
        text = "",
        streaming = false,
        reasoning = reasoning,
    )

    private fun appendNotice(text: String, isError: Boolean) {
        _state.update {
            it.copy(
                entries = it.entries + ChatEntry.Notice(newId(), text, isError),
                status = null
            )
        }
    }

    private fun connectionFor(config: ProviderConfig) = connectionOverride?.invoke(config)
        ?: ProviderFactory.connect(client, config)

    private fun languageInstruction(): String {
        val locales = if (Build.VERSION.SDK_INT >= 33) {
            appContext.getSystemService(LocaleManager::class.java)?.systemLocales
        } else null
        val tags = (locales ?: Resources.getSystem().configuration.locales).toLanguageTags()
            .ifBlank { "en" }
        return "\n\nDevice system preferred languages (BCP-47, not the app UI language): $tags. " +
                "Reply in the first device system language by default, including its writing system and regional variant. " +
                "If the user explicitly requests another output language, follow that request."
    }

    companion object {
        internal fun renderHistory(messages: List<ChatMessage>): List<ChatEntry> = buildList {
            messages.forEachIndexed { index, message ->
                val nativeParts = message.nativeEncoding?.let {
                    KoogTranscriptCodec.toNative(listOf(message)).single().parts
                }.orEmpty()
                when (message.role) {
                    MessageRole.USER -> message.content?.takeIf { it.isNotBlank() }
                        ?.let { add(ChatEntry.UserMessage("history-$index", it)) }

                    MessageRole.ASSISTANT -> {
                        val reasoning = nativeParts.filterIsInstance<MessagePart.Reasoning>()
                            .joinToString("\n") { part ->
                                (part.content + part.summary.orEmpty()).filter { it.isNotBlank() }
                                    .joinToString("\n")
                            }
                        message.content?.takeIf { it.isNotBlank() }?.let {
                            add(
                                ChatEntry.AssistantMessage(
                                    "history-$index",
                                    it,
                                    reasoning = reasoning
                                )
                            )
                        }
                        message.toolCalls.forEachIndexed { callIndex, call ->
                            val transcriptId = "history-$index-tool-$callIndex"
                            add(
                                ChatEntry.ToolCall(
                                    transcriptId,
                                    call.name,
                                    call.argumentsJson,
                                    callId = call.id,
                                    transcriptId = transcriptId
                                )
                            )
                        }
                    }

                    MessageRole.TOOL -> {
                        val entryIndex =
                            indexOfLast { it is ChatEntry.ToolCall && it.callId == message.toolCallId }
                        if (entryIndex >= 0) {
                            val entry = this[entryIndex] as ChatEntry.ToolCall
                            val isError = nativeParts.filterIsInstance<MessagePart.Tool.Result>()
                                .singleOrNull()?.isError ?: false
                            this[entryIndex] = entry.copy(
                                result = ToolResult(
                                    message.content.orEmpty(),
                                    isError = isError
                                )
                            )
                        }
                    }

                    MessageRole.SYSTEM -> Unit
                }
            }
        }

        /** Bind complete calls at the checkpoint, never count abandoned streaming-only rows. */
        internal fun bindToolTranscript(
            entries: List<ChatEntry>,
            messages: List<ChatMessage>
        ): List<ChatEntry> {
            val saved = renderHistory(messages).filterIsInstance<ChatEntry.ToolCall>()
            val claimed =
                entries.filterIsInstance<ChatEntry.ToolCall>().mapNotNull { it.transcriptId }
                    .toMutableSet()
            // Newest live row owns an ID; an older, stopped partial row is never eligible.
            return entries.asReversed().map { entry ->
                if (entry is ChatEntry.ToolCall && entry.running && entry.transcriptId == null) {
                    val committed = saved.lastOrNull {
                        it.transcriptId !in claimed && it.callId == entry.callId &&
                                it.name == entry.name && it.argumentsJson == entry.argumentsJson
                    }
                    committed?.transcriptId?.let { claimed += it }
                    entry.copy(transcriptId = committed?.transcriptId)
                } else entry
            }.asReversed()
        }

        /** Only reconcile recovery markers after execution stops, never during active checkpoints. */
        internal fun recoverToolResults(
            entries: List<ChatEntry>,
            messages: List<ChatMessage>
        ): List<ChatEntry> {
            val saved = renderHistory(messages).filterIsInstance<ChatEntry.ToolCall>()
                .associateBy { it.transcriptId }
            return entries.map { entry ->
                if (entry is ChatEntry.ToolCall && entry.running && entry.result == null && entry.transcriptId != null) {
                    val recovered =
                        saved[entry.transcriptId]?.takeIf { it.callId == entry.callId && it.name == entry.name }
                    entry.copy(result = recovered?.result)
                } else entry
            }
        }

        private fun stopStreaming(entries: List<ChatEntry>): List<ChatEntry> = entries.map {
            when (it) {
                is ChatEntry.AssistantMessage -> it.copy(streaming = false)
                is ChatEntry.ToolCall -> it.copy(running = false)
                is ChatEntry.Question -> if (it.answer == null) it.copy(interrupted = true) else it
                else -> it
            }
        }

        private fun mergeUsage(previous: ChatEvent.Usage?, next: ChatEvent.Usage) = ChatEvent.Usage(
            inputTokens = next.inputTokens ?: previous?.inputTokens,
            outputTokens = next.outputTokens ?: previous?.outputTokens,
            cachedInputTokens = next.cachedInputTokens ?: previous?.cachedInputTokens,
            cacheCreationInputTokens = next.cacheCreationInputTokens
                ?: previous?.cacheCreationInputTokens,
        )

        private fun newId(): String = UUID.randomUUID().toString()
    }
}
