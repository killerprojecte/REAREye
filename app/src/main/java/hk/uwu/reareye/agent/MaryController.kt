package hk.uwu.reareye.agent

import android.content.Context
import hk.uwu.reareye.agent.data.AgentAuditLog
import hk.uwu.reareye.agent.data.AgentSession
import hk.uwu.reareye.agent.data.AgentSessionStore
import hk.uwu.reareye.agent.data.AgentSessionSummary
import hk.uwu.reareye.agent.data.AgentSettingsSource
import hk.uwu.reareye.agent.data.AgentSettingsStore
import hk.uwu.reareye.agent.data.AuditEntry
import hk.uwu.reareye.agent.loop.AgentEvent
import hk.uwu.reareye.agent.loop.AgentLoop
import hk.uwu.reareye.agent.loop.ToolCallRequest
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.EmptyToolEnvironment
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.provider.OpenAiCompatibleProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/** One row of the conversation as the UI needs to render it. */
sealed interface ChatEntry {
    val id: String

    data class UserMessage(override val id: String, val text: String) : ChatEntry

    data class AssistantMessage(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
    ) : ChatEntry

    data class ToolCall(
        override val id: String,
        val name: String,
        val argumentsJson: String,
        val result: ToolResult? = null,
        val running: Boolean = false,
    ) : ChatEntry

    data class Notice(override val id: String, val text: String, val isError: Boolean) : ChatEntry
}

/** A pending mutating-tool approval the UI must answer. */
data class PendingApproval(
    val request: ToolCallRequest,
    val decision: CompletableDeferred<Boolean>,
)

data class AgentUiState(
    val entries: List<ChatEntry> = emptyList(),
    val running: Boolean = false,
    val pendingApproval: PendingApproval? = null,
    val status: String? = null,
)

/**
 * Owns a single Mary conversation: drives [AgentLoop], mirrors its events into renderable
 * [ChatEntry] rows, records the audit trail and persists the transcript.
 *
 * The persisted source of truth is [session]'s message list — the entry list is a view of it plus
 * whatever is streaming right now.
 */
class MaryController(
    context: Context,
    private val scope: CoroutineScope,
    private val tools: ToolEnvironment = EmptyToolEnvironment,
    private val settings: AgentSettingsSource = AgentSettingsStore(context.applicationContext),
    private val store: AgentSessionStore = AgentSessionStore(context.applicationContext.filesDir),
) {
    private val appContext = context.applicationContext
    private val auditLog = AgentAuditLog(appContext.filesDir)

    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    /**
     * The active conversation. It is null until the first prompt, so merely visiting the Mary tab
     * — or starting a new chat and immediately leaving — never writes an empty conversation.
     */
    private var session: AgentSession? = null
    private var turnJob: Job? = null

    init {
        // Reopen the most recent conversation so the transcript survives leaving the tab.
        val recent = store.list().firstOrNull()?.let { store.load(it.id) }
        session = recent
        _state.value = AgentUiState(entries = renderHistory(recent?.messages.orEmpty()))
    }

    val sessionId: String? get() = session?.id

    /** Conversations on disk, newest first. */
    fun sessions(): List<AgentSessionSummary> = store.list()

    /** Remove one stored conversation, and reset the view if it was the open one. */
    fun deleteSession(id: String) {
        store.delete(id)
        if (session?.id == id) {
            cancel()
            session = null
            _state.value = AgentUiState(entries = emptyList())
        }
    }

    /** Start a new conversation without writing anything until the user actually sends. */
    fun newSession() {
        cancel()
        session = null
        _state.value = AgentUiState(entries = emptyList())
    }

    fun loadSession(id: String) {
        val loaded = store.load(id) ?: return
        cancel()
        session = loaded
        _state.value = AgentUiState(entries = renderHistory(loaded.messages))
    }

    fun cancel() {
        turnJob?.cancel()
        turnJob = null
        _state.update { it.copy(running = false, pendingApproval = null) }
    }

    fun answerApproval(approved: Boolean) {
        val pending = _state.value.pendingApproval ?: return
        _state.update { it.copy(pendingApproval = null) }
        pending.decision.complete(approved)
    }

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _state.value.running) return

        val config = runCatching { settings.load() }.getOrElse { AgentConfig() }
        if (!config.provider.isUsable) {
            appendNotice(
                "还没有配置模型。请先在上方设置里填写服务地址、API Key 和模型名称。",
                isError = true,
            )
            return
        }

        val userMessage = ChatMessage(role = MessageRole.USER, content = prompt)
        // Create the session here, at the first real user message, rather than on screen entry.
        val active = session ?: store.create()
        val history = active.messages + userMessage
        session = active.copy(
            messages = history,
            title = if (active.messages.isEmpty()) {
                AgentSessionStore.deriveTitle(history)
            } else {
                active.title
            },
        ).also { store.save(it) }

        _state.update {
            it.copy(
                entries = it.entries + ChatEntry.UserMessage(newId(), prompt),
                running = true,
                status = null,
            )
        }

        val provider = OpenAiCompatibleProvider(
            client = OpenAiCompatibleProvider.defaultClient(),
            configProvider = { runCatching { settings.load().provider }.getOrElse { config.provider } },
        )
        val autoApproveReadOnly = config.autoApproveReadOnly

        val loop = AgentLoop(
            provider = provider,
            tools = tools,
            config = config,
            approve = { request ->
                // Read-only tools only reach here when the user disabled auto-approval.
                val runningId = "approval-${request.id}"
                val decision = CompletableDeferred<Boolean>()
                _state.update {
                    it.copy(pendingApproval = PendingApproval(request, decision), status = null)
                }
                val approved = decision.await()
                recordAudit(
                    toolName = request.name,
                    arguments = request.argumentsJson,
                    success = approved,
                    detail = if (approved) "approved" else "denied by user",
                    durationMs = 0,
                )
                approved
            },
        )

        turnJob = scope.launch {
            val startedAt = mutableMapOf<String, Long>()
            try {
                loop.run(history).collect { event -> handleEvent(event, startedAt) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                appendNotice(t.message ?: "调用失败。", isError = true)
            } finally {
                _state.update { it.copy(running = false, pendingApproval = null) }
            }
        }
    }

    private suspend fun handleEvent(event: AgentEvent, startedAt: MutableMap<String, Long>) {
        when (event) {
            is AgentEvent.TurnStarted -> _state.update { it.copy(status = null) }

            is AgentEvent.TextDelta -> _state.update { current ->
                val entries = current.entries.toMutableList()
                val last = entries.lastOrNull()
                if (last is ChatEntry.AssistantMessage && last.streaming) {
                    entries[entries.lastIndex] = last.copy(text = last.text + event.text)
                } else {
                    entries += ChatEntry.AssistantMessage(newId(), event.text, streaming = true)
                }
                current.copy(entries = entries)
            }

            is AgentEvent.ToolCallStarted -> _state.update { current ->
                current.copy(
                    entries = current.entries + ChatEntry.ToolCall(
                        id = event.id,
                        name = event.name,
                        argumentsJson = "",
                        running = true,
                    ),
                    status = event.name,
                )
            }

            is AgentEvent.ToolCallArguments -> _state.update { current ->
                current.copy(
                    entries = current.entries.map { entry ->
                        if (entry is ChatEntry.ToolCall && entry.id == event.id) {
                            entry.copy(argumentsJson = entry.argumentsJson + event.fragment)
                        } else {
                            entry
                        }
                    },
                )
            }

            is AgentEvent.ToolCallFinished -> {
                startedAt[event.request.id] = System.currentTimeMillis()
                _state.update { current ->
                    val present = current.entries.any { it is ChatEntry.ToolCall && it.id == event.request.id }
                    val entries = if (present) {
                        current.entries.map { entry ->
                            if (entry is ChatEntry.ToolCall && entry.id == event.request.id) {
                                entry.copy(
                                    name = event.request.name,
                                    argumentsJson = event.request.argumentsJson,
                                )
                            } else {
                                entry
                            }
                        }
                    } else {
                        current.entries + ChatEntry.ToolCall(
                            id = event.request.id,
                            name = event.request.name,
                            argumentsJson = event.request.argumentsJson,
                            running = true,
                        )
                    }
                    current.copy(entries = entries)
                }
            }

            is AgentEvent.ToolResultEvent -> {
                val duration = startedAt.remove(event.id)?.let { System.currentTimeMillis() - it } ?: 0
                recordAudit(
                    toolName = event.name,
                    arguments = "",
                    success = !event.result.isError,
                    detail = event.result.content.take(400),
                    durationMs = duration,
                )
                _state.update { current ->
                    current.copy(
                        entries = current.entries.map { entry ->
                            if (entry is ChatEntry.ToolCall && entry.id == event.id) {
                                entry.copy(result = event.result, running = false)
                            } else {
                                entry
                            }
                        },
                        status = null,
                    )
                }
            }

            is AgentEvent.TurnFinished -> {
                session = session?.copy(messages = event.messages)?.also { store.save(it) }
                _state.update { current ->
                    current.copy(
                        entries = current.entries.map { entry ->
                            if (entry is ChatEntry.AssistantMessage && entry.streaming) {
                                entry.copy(streaming = false)
                            } else {
                                entry
                            }
                        },
                        running = false,
                    )
                }
            }

            is AgentEvent.Failed -> {
                appendNotice(event.error.message ?: "调用失败。", isError = true)
            }
        }
    }

    private suspend fun recordAudit(
        toolName: String,
        arguments: String,
        success: Boolean,
        detail: String?,
        durationMs: Long,
    ) {
        withContext(Dispatchers.IO) {
            auditLog.append(
                AuditEntry(
                    timestamp = System.currentTimeMillis(),
                    sessionId = session?.id.orEmpty(),
                    toolName = toolName,
                    arguments = arguments.take(400),
                    success = success,
                    detail = detail,
                    durationMs = durationMs,
                ),
            )
        }
    }

    private fun appendNotice(text: String, isError: Boolean) {
        _state.update {
            it.copy(
                entries = it.entries + ChatEntry.Notice(newId(), text, isError),
                status = null,
            )
        }
    }

    private fun renderHistory(messages: List<ChatMessage>): List<ChatEntry> = buildList {
        messages.forEach { message ->
            when (message.role) {
                MessageRole.USER ->
                    message.content?.takeIf { it.isNotBlank() }?.let {
                        add(ChatEntry.UserMessage(it, it))
                    }

                MessageRole.ASSISTANT -> {
                    message.content?.takeIf { it.isNotBlank() }?.let {
                        add(ChatEntry.AssistantMessage(message.idKey(), it))
                    }
                    message.toolCalls.forEach { call ->
                        add(
                            ChatEntry.ToolCall(
                                id = call.id,
                                name = call.name,
                                argumentsJson = call.argumentsJson,
                            ),
                        )
                    }
                }

                MessageRole.TOOL -> {
                    val id = message.toolCallId ?: return@forEach
                    val index = indexOfLast { it is ChatEntry.ToolCall && it.id == id }
                    if (index >= 0) {
                        val existing = this[index] as ChatEntry.ToolCall
                        this[index] = existing.copy(
                            result = ToolResult(
                                content = message.content.orEmpty(),
                                isError = false,
                            ),
                        )
                    }
                }

                MessageRole.SYSTEM -> Unit
            }
        }
    }

    private var idCounter = 0
    private fun newId(): String = "e${idCounter++}"

    private fun ChatMessage.idKey(): String = "m${hashCode()}"
}

private fun List<ChatEntry>.indexOfLast(predicate: (ChatEntry) -> Boolean): Int {
    for (i in indices.reversed()) if (predicate(this[i])) return i
    return -1
}
