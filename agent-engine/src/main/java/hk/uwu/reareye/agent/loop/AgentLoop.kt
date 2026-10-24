package hk.uwu.reareye.agent.loop

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.dsl.builder.node
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.HistoryCompressionStrategy
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.context.ContextBudgetCallback
import hk.uwu.reareye.agent.context.ContextBudgetDecision
import hk.uwu.reareye.agent.context.ContextBudgetRequest
import hk.uwu.reareye.agent.context.TokenBudgetEstimator
import hk.uwu.reareye.agent.interaction.ApprovalDecision
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.interaction.AskCallback
import hk.uwu.reareye.agent.interaction.AskToolEnvironment
import hk.uwu.reareye.agent.interaction.AutoApprovalCallback
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolCall
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.provider.KoogConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** An execution intent shown before consent and persisted before side effects. */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val risk: ToolRisk
)

sealed interface AgentEvent {
    data class TurnStarted(val iteration: Int) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ReasoningDelta(val text: String) : AgentEvent
    data class Notice(val text: String) : AgentEvent
    data class ToolCallStarted(val id: String, val name: String) : AgentEvent
    data class ToolCallArguments(val id: String, val fragment: String) : AgentEvent
    data class ToolCallFinished(val request: ToolCallRequest) : AgentEvent
    data class ToolResultEvent(val id: String, val name: String, val result: ToolResult) :
        AgentEvent

    data class TurnFinished(
        val messages: List<ChatMessage>, val iterations: Int,
        val fullTranscript: List<ChatMessage> = messages,
    ) : AgentEvent

    /**
     * Pair-complete recovery context and independent, uncompressed transcript.
     * Pending results are recovery-only markers, not live tool failures. Active UI must use
     * ToolCall/ToolResult events; render these markers only after execution has stopped.
     */
    data class Checkpoint(
        val messages: List<ChatMessage>, val fullTranscript: List<ChatMessage> = messages,
    ) : AgentEvent

    data class ContextCompressed(val estimatedInputTokens: Int) : AgentEvent
    data class Usage(val usage: ChatEvent.Usage) : AgentEvent
    data class Failed(val error: AgentError) : AgentEvent
}

typealias ApprovalCallback = suspend (ToolCallRequest) -> Boolean

/**
 * Koog owns request/tool progression through a graph, not an application-side model loop.
 * Native message parts remain authoritative; ChatMessage is only a durable/UI projection.
 * The caller owns [connection] and closes it after collection, including cancellation.
 */
class AgentLoop(
    private val connection: KoogConnection,
    private val tools: ToolEnvironment,
    private val config: AgentConfig,
    private val json: Json = JSON,
    private val approve: ApprovalCallback = { false },
    private val approvalMode: ApprovalMode? = null,
    private val autoApprove: AutoApprovalCallback = { ApprovalDecision.ASK_USER },
    private val ask: AskCallback? = null,
    private val checkBudget: ContextBudgetCallback? = null,
    private val approvalDiagnostic: (String) -> Unit = {},
) {
    fun run(
        history: List<ChatMessage>,
        fullTranscript: List<ChatMessage> = history
    ): Flow<AgentEvent> = flow {
        val working = history.toMutableList()
        val transcript = fullTranscript.toMutableList()
        val startedAtNs = System.nanoTime()
        var humanWaitNs = 0L
        var iterations = 0
        var stopped = false
        var calls = emptyList<ToolCall>()
        var specs = emptyList<ToolSpec>()
        var resultStart = 0
        var transcriptResultStart = 0
        val repeatCounts = mutableMapOf<String, Int>()
        val consecutiveFailures = mutableMapOf<String, Int>()
        val timedAsk: AskCallback? = ask?.let { callback ->
            { request ->
                request.validate()
                val start = System.nanoTime()
                try {
                    callback(request).also { request.validateAnswer(it) }
                } finally {
                    humanWaitNs += System.nanoTime() - start
                }
            }
        }
        val environment = timedAsk?.let { AskToolEnvironment(tools, it) } ?: tools
        val humanApprove: ApprovalCallback = { request ->
            val start = System.nanoTime()
            try {
                approve(request)
            } finally {
                humanWaitNs += System.nanoTime() - start
            }
        }

        suspend fun checkpoint() =
            emit(AgentEvent.Checkpoint(working.toList(), transcript.toList()))

        suspend fun fail(message: String) {
            stopped = true
            emit(AgentEvent.Failed(AgentError.Protocol(message)))
        }

        suspend fun finish() {
            stopped = true
            emit(AgentEvent.TurnFinished(working.toList(), iterations, transcript.toList()))
        }

        fun budget(messages: List<ChatMessage>) = ContextBudgetRequest(
            messages,
            this@AgentLoop.config.systemPrompt,
            specs,
            this@AgentLoop.config.provider.contextWindowTokens,
            this@AgentLoop.config.provider.maxOutputTokens ?: 4096,
            timedAsk,
        )

        val prefix =
            if (this@AgentLoop.config.systemPrompt.isBlank()) emptyList() else KoogTranscriptCodec.toNative(
                listOf(
                    ChatMessage(
                        role = MessageRole.SYSTEM,
                        content = this@AgentLoop.config.systemPrompt
                    )
                ),
            )

        fun nativeContext() = prefix + KoogTranscriptCodec.toNative(working)
        val initialPrompt = prompt("mary-native", params = connection.params) {
            nativeContext().forEach { message(it) }
        }
        val execution = strategy<Unit, Unit>("mary_native_graph") {
            val prepare by node<Unit, Unit>("budget_and_context") {
                if ((System.nanoTime() - startedAtNs - humanWaitNs) / 1_000_000 > this@AgentLoop.config.turnBudgetMs) {
                    fail("本轮达到时间上限，已停止；已完成的部分不受影响。")
                } else if (iterations >= this@AgentLoop.config.maxIterations) {
                    fail("已达单轮工具调用上限（${this@AgentLoop.config.maxIterations} 次），已停止以免继续消耗额度。")
                } else {
                    specs = environment.availableTools()
                    val request = budget(working.toList())
                    when (val decision = checkBudget?.invoke(request)
                        ?: if (request.needsCompaction) ContextBudgetDecision.Stop("上下文预算不足，请压缩历史或开启新对话。")
                        else ContextBudgetDecision.Continue(working.toList())) {
                        is ContextBudgetDecision.Stop -> {
                            emit(AgentEvent.Notice(decision.reason))
                            finish()
                        }

                        is ContextBudgetDecision.Continue -> {
                            // Validation happens before committing a replacement context.
                            KoogTranscriptCodec.toNative(decision.messages)
                            working.clear()
                            working.addAll(decision.messages)
                            checkpoint()
                        }

                        ContextBudgetDecision.Compress -> {
                            // WholeHistory is one request over the entire prompt. Never split or trim.
                            // A smaller summary reserve keeps compaction usable at the soft threshold.
                            val summaryReserve =
                                minOf(4096, (request.contextWindowTokens / 8).coerceAtLeast(1))
                            if (request.estimatedInputTokens.toLong() + summaryReserve + 512L >= request.contextWindowTokens) {
                                fail("完整历史已无法放入单次压缩请求；未分块、未截断，请开启新对话。")
                            } else {
                                val candidate = llm.writeSession {
                                    val saved = prompt
                                    try {
                                        prompt = initialPrompt.withMessages { nativeContext() }
                                            .withParams(
                                                connection.params.copy(
                                                    maxTokens = summaryReserve,
                                                    toolChoice = null
                                                ),
                                            )
                                        HistoryCompressionStrategy.WholeHistory.compress(
                                            this,
                                            emptyList()
                                        )
                                        val summary =
                                            prompt.messages.lastOrNull() as? Message.Assistant
                                                ?: throw AgentError.Protocol("压缩模型未返回摘要，原上下文未更改。")
                                        if (summary.finishReason?.lowercase() !in TOOL_FINISH_REASONS ||
                                            summary.parts.any { it is MessagePart.Tool.Call } ||
                                            summary.parts.filterIsInstance<MessagePart.Text>()
                                                .all { it.text.isBlank() }
                                        ) {
                                            throw AgentError.Protocol("压缩摘要不完整或包含工具调用，原上下文未更改。")
                                        }
                                        val compressed =
                                            prompt.messages.filterNot { item -> prefix.any { it === item } }
                                        KoogTranscriptCodec.fromNative(compressed)
                                    } finally {
                                        // Compression is a transaction: error/cancel never mutates the active prompt.
                                        prompt = saved
                                    }
                                }
                                val estimate = budget(candidate)
                                if (estimate.needsCompaction) {
                                    fail("整段历史压缩后的上下文仍超过预算，原上下文未更改。")
                                } else {
                                    working.clear()
                                    working.addAll(candidate)
                                    checkpoint()
                                    emit(AgentEvent.ContextCompressed(estimate.estimatedInputTokens))
                                }
                            }
                        }
                    }
                    if (!stopped) {
                        llm.writeSession {
                            prompt = initialPrompt.withMessages { nativeContext() }
                            tools = specs.map { it.toKoogDescriptor() }
                        }
                    }
                }
            }
            // Native session streaming request node; no synthetic user message is appended.
            val requestModel by node<Unit, Flow<StreamFrame>>("native_streaming_request") {
                iterations++
                emit(AgentEvent.TurnStarted(iterations))
                llm.writeSession { requestLLMStreaming() }
            }
            val receive by node<Flow<StreamFrame>, Unit>("collect_native_response") { stream ->
                val frames = mutableListOf<StreamFrame>()
                val announced = mutableSetOf<String>()
                val pending = mutableSetOf<Int?>()
                val callIds = mutableMapOf<Int?, String>()
                val textShown = mutableMapOf<Int?, StringBuilder>()
                val textCompleted = mutableSetOf<Int?>()
                val reasoningShown = mutableMapOf<Int?, StringBuilder>()
                val reasoningCompleted = mutableSetOf<Int?>()
                var end: StreamFrame.End? = null
                stream.collect { frame ->
                    if (end != null) throw AgentError.Protocol("模型结束帧之后仍有数据，未执行工具。")
                    frames += frame
                    when (frame) {
                        is StreamFrame.TextDelta -> {
                            textShown.getOrPut(frame.index) { StringBuilder() }.append(frame.text)
                            emit(AgentEvent.TextDelta(frame.text))
                        }

                        is StreamFrame.TextComplete -> {
                            if (!textCompleted.add(frame.index)) throw AgentError.Protocol("模型返回重复文本完成帧。")
                            val shown = textShown[frame.index]?.toString().orEmpty()
                            // 完成帧只是修补通道：增量已经推到 UI 了，覆盖不了就别动它
                            if (frame.text.startsWith(shown)) {
                                frame.text.removePrefix(shown)
                                    .takeIf { it.isNotEmpty() }
                                    ?.let { emit(AgentEvent.TextDelta(it)) }
                            }
                        }

                        is StreamFrame.ReasoningDelta -> (frame.text ?: frame.summary)?.let {
                            reasoningShown.getOrPut(frame.index) { StringBuilder() }.append(it)
                            emit(AgentEvent.ReasoningDelta(it))
                        }

                        is StreamFrame.ReasoningComplete -> {
                            reasoningCompleted += frame.index
                            val complete =
                                (if (frame.content.isNotEmpty()) frame.content else frame.summary.orEmpty()).joinToString(
                                    ""
                                )
                            val shown = reasoningShown[frame.index]?.toString().orEmpty()
                            if (complete.startsWith(shown)) complete.removePrefix(shown)
                                .takeIf { it.isNotEmpty() }
                                ?.let { emit(AgentEvent.ReasoningDelta(it)) }
                        }

                        is StreamFrame.ToolCallDelta -> {
                            pending += frame.index
                            frame.id?.let { callIds[frame.index] = it }
                            val id = callIds[frame.index].orEmpty()
                            if (id.isNotBlank() && announced.add(id)) emit(
                                AgentEvent.ToolCallStarted(
                                    id,
                                    frame.name.orEmpty()
                                )
                            )
                            frame.content?.let { emit(AgentEvent.ToolCallArguments(id, it)) }
                        }

                        is StreamFrame.ToolCallComplete -> {
                            pending -= frame.index
                            val id = frame.id
                            if (id != null && announced.add(id)) {
                                emit(AgentEvent.ToolCallStarted(id, frame.name))
                            }
                        }

                        is StreamFrame.End -> {
                            end = frame
                            val usage = frame.metaInfo.metadata?.get("usage") as? JsonObject
                            fun JsonObject?.count(key: String) =
                                (this?.get(key) as? JsonPrimitive)?.intOrNull

                            val cached = usage.count("cache_read_input_tokens")
                                ?: (usage?.get("input_tokens_details") as? JsonObject).count("cached_tokens")
                                ?: (usage?.get("prompt_tokens_details") as? JsonObject).count("cached_tokens")
                                ?: usage.count("prompt_cache_hit_tokens")
                            val creation = usage.count("cache_creation_input_tokens")
                            val input =
                                if (this@AgentLoop.config.provider.protocol == Protocol.ANTHROPIC) usage.count(
                                    "input_tokens"
                                )?.let {
                                    it + (cached ?: 0) + (creation ?: 0)
                                }
                                    ?: frame.metaInfo.inputTokensCount else frame.metaInfo.inputTokensCount
                            if (input != null || frame.metaInfo.outputTokensCount != null || cached != null || creation != null) {
                                emit(
                                    AgentEvent.Usage(
                                        ChatEvent.Usage(
                                            input,
                                            frame.metaInfo.outputTokensCount,
                                            cached,
                                            creation
                                        )
                                    )
                                )
                            }
                        }

                        else -> Unit
                    }
                }
                // Responses completion frames may arrive out of output-index order. Keep a
                // list (not an index-keyed map): Chat reasoning/text can both have null index,
                // while its first tool call has index 0. Null-index parts retain stable order
                // before indexed tool parts; equal native indices never overwrite one another.
                val completions = frames.filterIsInstance<StreamFrame.CompleteFrame>()
                    .sortedWith(compareBy { it.index ?: Int.MIN_VALUE })
                val completed = completions.filterIsInstance<StreamFrame.ToolCallComplete>()
                val reason = end?.finishReason?.lowercase()
                if (end == null || pending.isNotEmpty() || textShown.keys.any { it !in textCompleted }
                    || reasoningShown.keys.any { it !in reasoningCompleted }
                    || (completed.isNotEmpty() && reason !in TOOL_FINISH_REASONS)) {
                    fail("模型响应未完整结束或已截断，未执行任何工具。")
                } else if (completed.any { it.id.isNullOrBlank() || it.name.isBlank() }
                    || completed.map { it.id }.distinct().size != completed.size) {
                    fail("模型返回无效或重复工具调用 ID，未执行工具。")
                } else {
                    // Do not use a text projection to rebuild the response: encrypted/signed
                    // reasoning and all completion parts must survive subsequent tool requests.
                    val parts = completions.mapNotNull { frame ->
                        when (frame) {
                            is StreamFrame.TextComplete -> MessagePart.Text(
                                reconcileCompletedText(
                                    frame.text,
                                    textShown[frame.index]?.toString().orEmpty(),
                                )
                            )
                            is StreamFrame.ReasoningComplete -> MessagePart.Reasoning(
                                content = frame.content, summary = frame.summary,
                                encrypted = frame.encrypted, id = frame.id,
                            )

                            is StreamFrame.ToolCallComplete -> MessagePart.Tool.Call(
                                id = frame.id, tool = frame.name, args = frame.content,
                            )

                            else -> null
                        }
                    }
                    if (frames.any { it is StreamFrame.ReasoningDelta } && parts.none { it is MessagePart.Reasoning }) {
                        throw AgentError.Protocol("模型推理内容缺少原生完成帧，未丢弃推理状态或执行工具。")
                    }
                    val response = Message.Assistant(
                        parts = parts,
                        finishReason = end?.finishReason,
                        metaInfo = end!!.metaInfo
                    )
                    calls = completed.map { ToolCall(it.id!!, it.name, it.content) }
                    val projected = KoogTranscriptCodec.fromNative(listOf(response))
                    working.addAll(projected)
                    transcript.addAll(projected)
                    resultStart = working.size
                    transcriptResultStart = transcript.size
                    calls.forEach { call ->
                        emit(
                            AgentEvent.ToolCallFinished(
                                ToolCallRequest(
                                    call.id,
                                    call.name,
                                    call.argumentsJson,
                                    riskOf(call.name, specs)
                                )
                            )
                        )
                        val placeholder = toolMessage(call, ToolResult.error(NOT_EXECUTED))
                        working += placeholder
                        transcript += placeholder
                    }
                    checkpoint()
                    if (calls.isEmpty()) {
                        if (reason in TRUNCATED_FINISH_REASONS) emit(AgentEvent.Notice("这条回复因达到输出长度上限被截断，内容可能不完整。"))
                        finish()
                    } else {
                        val signature =
                            calls.joinToString("|") { "${it.name}::${it.argumentsJson}" }
                        val repeats = (repeatCounts[signature] ?: 0) + 1
                        repeatCounts[signature] = repeats
                        if (repeats > this@AgentLoop.config.maxRepeatedToolCalls) {
                            fail("模型重复发起了相同的工具调用 $repeats 次，判断为循环，已停止本轮。")
                        }
                    }
                }
            }
            val executeTools by node<Unit, Unit>("serial_mary_tools") {
                // Serial execution deliberately retains Mary's actual permission-enforcing environment.
                for ((index, call) in calls.withIndex()) {
                    val raw = execute(call, specs, environment, humanApprove) {
                        // Recovery-only result: persist immediately before invocation, not while
                        // permission is still pending. Live UI consumes tool events, not this marker.
                        val interrupted = if (riskOf(
                                call.name,
                                specs
                            ) == ToolRisk.READ_ONLY
                        ) READ_INTERRUPTED else UNKNOWN_RESULT
                        val unknown = toolMessage(call, ToolResult.error(interrupted))
                        working[resultStart + index] = unknown
                        transcript[transcriptResultStart + index] = unknown
                        checkpoint()
                    }
                    val result =
                        raw.capped(
                            specs.firstOrNull { it.name == call.name }?.maxResultChars
                                ?: this@AgentLoop.config.maxToolResultChars
                        )
                    working[resultStart + index] = toolMessage(call, result)
                    // The display transcript never loses a tool result to a model-context cap.
                    transcript[transcriptResultStart + index] = toolMessage(call, raw)
                    checkpoint()
                    emit(AgentEvent.ToolResultEvent(call.id, call.name, result))
                    val failures =
                        if (result.isError) (consecutiveFailures[call.name] ?: 0) + 1 else 0
                    consecutiveFailures[call.name] = failures
                    if (failures >= this@AgentLoop.config.maxConsecutiveToolFailures) {
                        fail("工具 ${call.name} 已经连续失败 $failures 次，停止本轮以免继续浪费。不要声称操作成功。")
                        break
                    }
                }
            }
            edge(nodeStart forwardTo prepare)
            edge(prepare forwardTo nodeFinish onCondition { stopped })
            edge(prepare forwardTo requestModel onCondition { !stopped })
            edge(requestModel forwardTo receive)
            edge(receive forwardTo nodeFinish onCondition { stopped })
            edge(receive forwardTo executeTools onCondition { !stopped })
            edge(executeTools forwardTo nodeFinish onCondition { stopped })
            edge(executeTools forwardTo prepare onCondition { !stopped })
        }
        try {
            val agent = AIAgent(
                promptExecutor = connection.executor,
                agentConfig = AIAgentConfig(
                    prompt = initialPrompt, model = connection.model,
                    // Koog counts graph steps; Mary's model-call limit is checked by prepare.
                    maxAgentIterations = (this@AgentLoop.config.maxIterations.toLong()
                        .coerceAtLeast(1) * 5 + 10).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                ),
                strategy = execution,
            )
            try {
                agent.run(Unit)
            } finally {
                agent.close()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: AgentError) {
            emit(AgentEvent.Failed(error))
        } catch (error: Exception) {
            emit(AgentEvent.Failed(AgentError.Network(error.message ?: "调用模型失败。", error)))
        }
    }

    private fun toolMessage(call: ToolCall, result: ToolResult): ChatMessage =
        KoogTranscriptCodec.fromNative(
            listOf(
                Message.User(
                    parts = listOf(
                        MessagePart.Tool.Result(
                            id = call.id,
                            tool = call.name,
                            output = result.content,
                            isError = result.isError
                        )
                    ),
                    metaInfo = RequestMetaInfo.Empty,
                )
            ),
        ).single()

    private suspend fun execute(
        call: ToolCall, specs: List<ToolSpec>, environment: ToolEnvironment,
        humanApprove: ApprovalCallback,
        beforeExecution: suspend () -> Unit,
    ): ToolResult {
        val spec = specs.firstOrNull { it.name == call.name } ?: return ToolResult.error(
            envelope(
                "tool_not_found", "Tool '${call.name}' does not exist.",
                "Use only the listed tools, or answer directly.",
                mapOf("tools_available_this_turn" to specs.joinToString(", ") { it.name }
                    .ifBlank { "(none)" }),
            )
        )
        val isAsk = environment is AskToolEnvironment && call.name == AskToolEnvironment.NAME
        if (!isAsk) {
            val request = ToolCallRequest(call.id, call.name, call.argumentsJson, spec.risk)
            diagnoseApproval(request, ApprovalReason.APPROVAL_STARTED)
            var source = ApprovalReason.ASK
            var diagnosticError: Exception? = null
            val decision = when (approvalMode) {
                ApprovalMode.ALLOW_ALL -> {
                    source = ApprovalReason.ALLOW_ALL
                    ApprovalDecision.ALLOW
                }

                ApprovalMode.ASK -> ApprovalDecision.ASK_USER
                ApprovalMode.AUTO -> if (spec.risk == ToolRisk.DESTRUCTIVE) {
                    source = ApprovalReason.AUTO_DESTRUCTIVE_FORCED_HUMAN
                    ApprovalDecision.ASK_USER
                } else try {
                    source = ApprovalReason.AUTO_REVIEWER_RESULT
                    autoApprove(request)
                } catch (cancelled: CancellationException) {
                    diagnoseApproval(request, ApprovalReason.AUTO_CANCELLED, error = cancelled)
                    throw cancelled
                } catch (error: Exception) {
                    source = ApprovalReason.AUTO_CALLBACK_EXCEPTION
                    diagnosticError = error
                    ApprovalDecision.ASK_USER
                }

                null -> if (spec.risk == ToolRisk.READ_ONLY && this@AgentLoop.config.autoApproveReadOnly) {
                    source = ApprovalReason.LEGACY_READONLY
                    ApprovalDecision.ALLOW
                } else {
                    source = ApprovalReason.LEGACY_HUMAN
                    ApprovalDecision.ASK_USER
                }
            }
            diagnoseApproval(request, source, decision, diagnosticError)
            val approved = when (decision) {
                ApprovalDecision.ALLOW -> true
                ApprovalDecision.DENY -> false
                ApprovalDecision.ASK_USER -> {
                    diagnoseApproval(request, ApprovalReason.HUMAN_WAIT, ApprovalDecision.ASK_USER)
                    try {
                        humanApprove(request).also { allowed ->
                            diagnoseApproval(
                                request, ApprovalReason.HUMAN_RESULT,
                                if (allowed) ApprovalDecision.ALLOW else ApprovalDecision.DENY
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        diagnoseApproval(request, ApprovalReason.HUMAN_CANCELLED, error = cancelled)
                        throw cancelled
                    } catch (error: Exception) {
                        diagnoseApproval(
                            request,
                            ApprovalReason.HUMAN_EXCEPTION,
                            ApprovalDecision.DENY,
                            error
                        )
                        false
                    }
                }
            }
            if (!approved) return ToolResult.error("Approval denied for ${call.name}. Do not retry the same action; explain your intent or ask the user.")
        }
        val rawArgs = call.argumentsJson.ifBlank { ToolCall.BLANK_ARGS }
        val args = runCatching { json.parseToJsonElement(rawArgs).jsonObject }.getOrElse {
            return ToolResult.error(
                envelope(
                    "invalid_tool_args",
                    "Arguments are not a valid JSON object: ${rawArgs.take(200)}",
                    "Use valid JSON arguments; split large requests if necessary."
                )
            )
        }
        // Outside the tool-error catch: a failed durable checkpoint must prevent execution.
        beforeExecution()
        val readOnly = spec.risk == ToolRisk.READ_ONLY
        return try {
            if (environment is AskToolEnvironment && isAsk) environment.executeAsk(
                args,
                call.id
            ) else withTimeoutOrNull(this@AgentLoop.config.toolTimeoutMs) {
                environment.execute(spec.name, args)
            } ?: ToolResult.error(
                envelope(
                    "tool_timeout",
                    "Tool ${spec.name} did not return within ${this@AgentLoop.config.toolTimeoutMs / 1000} seconds. " +
                            if (readOnly) "The read did not complete." else "Side effects may be unknown.",
                    if (readOnly) "Report the incomplete read to the user; do not replay automatically."
                    else "Do not replay this action. Verify actual state first; report the timeout to the user.",
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            ToolResult.error(
                envelope(
                    "tool_failed", (error.message ?: error.javaClass.simpleName).take(500),
                    if (readOnly) "The read failed. Check whether the arguments are invalid; do not replay automatically."
                    else "Check whether the arguments are invalid. Side effects may be unknown. Do not replay this action; verify actual state first.",
                    mapOf("exception" to error.javaClass.simpleName),
                )
            )
        }
    }

    private enum class ApprovalReason {
        APPROVAL_STARTED, ALLOW_ALL, ASK, AUTO_DESTRUCTIVE_FORCED_HUMAN,
        AUTO_REVIEWER_RESULT, AUTO_CALLBACK_EXCEPTION, AUTO_CANCELLED,
        LEGACY_READONLY, LEGACY_HUMAN, HUMAN_WAIT, HUMAN_RESULT, HUMAN_EXCEPTION, HUMAN_CANCELLED,
    }

    /** Diagnostics must never participate in approval control flow, even if the sink throws. */
    private fun diagnoseApproval(
        request: ToolCallRequest, reason: ApprovalReason,
        decision: ApprovalDecision? = null, error: Exception? = null,
    ) {
        runCatching {
            val tool = request.name.take(64).replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val callHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(request.id.toByteArray(Charsets.UTF_8)).take(6)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val exceptionClass = error?.javaClass?.simpleName?.take(80)
                ?.replace(Regex("[^A-Za-z0-9_$]"), "_") ?: "NONE"
            approvalDiagnostic(
                "approval mode=${approvalMode?.name ?: "null"} risk=${request.risk.name} " +
                        "autoApproveReadOnly=${config.autoApproveReadOnly} tool=$tool callHash=$callHash " +
                        "reason=${reason.name} decision=${decision?.name ?: "NONE"} exceptionClass=$exceptionClass"
            )
        }
    }

    private fun riskOf(name: String, specs: List<ToolSpec>): ToolRisk =
        specs.firstOrNull { it.name == name }?.risk ?: ToolRisk.MUTATING

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
        private val TOOL_FINISH_REASONS =
            setOf("tool_calls", "function_call", "stop", "end_turn", "tool_use", "completed")
        private val TRUNCATED_FINISH_REASONS =
            setOf("length", "max_tokens", "max_output_tokens", "incomplete")
        private const val NOT_EXECUTED =
            "Tool was not executed because the turn stopped. Do not replay automatically."
        private const val READ_INTERRUPTED =
            "Tool read was interrupted before a result was recorded. The read did not complete; do not replay automatically."
        private const val UNKNOWN_RESULT =
            "Tool execution was interrupted; side effects are UNKNOWN. Do not replay this action. Verify actual state first."
        private const val MARKER_RESERVE_TOKENS = 32
        private const val TRUNCATION_MARKER =
            "\n… (%d characters omitted to fit the result budget) …\n"

        private fun envelope(
            error: String,
            detail: String,
            recovery: String? = null,
            extra: Map<String, String> = emptyMap()
        ): String = buildJsonObject {
            put("error", error)
            put("detail", detail)
            recovery?.let { put("recovery", it) }
            extra.forEach { (key, value) -> put(key, value) }
        }.toString()

        fun estimateTokens(text: String): Int = TokenBudgetEstimator.estimateTokens(text)

        private fun ToolResult.capped(limitChars: Int): ToolResult {
            if (limitChars <= 0) return this
            val budgetTokens = estimateTokens("x".repeat(limitChars))
            if (estimateTokens(content) <= budgetTokens) return this
            val budget = (budgetTokens - MARKER_RESERVE_TOKENS).coerceAtLeast(1)
            val headBudget = (budget * 0.6).toInt().coerceIn(1, budget)
            val tailBudget = budget - headBudget
            val headEnd = advanceByTokens(content, 0, content.length, headBudget, true)
            val tailStart = advanceByTokens(content, content.length, 0, tailBudget, false)
            val rendered = if (tailBudget <= 0 || tailStart <= headEnd) {
                val head = content.substring(0, headEnd).trimEnd()
                head + TRUNCATION_MARKER.format(content.length - head.length)
            } else {
                content.substring(
                    0,
                    headEnd
                ) + TRUNCATION_MARKER.format(tailStart - headEnd) + content.substring(tailStart)
            }
            return copy(content = rendered)
        }

        private fun advanceByTokens(
            text: String,
            from: Int,
            to: Int,
            budget: Int,
            forward: Boolean
        ): Int {
            if (budget <= 0) return from
            var ascii = 0L
            var nonAscii = 0L
            var index = from
            while (index != to) {
                if (text[if (forward) index else index - 1].code <= 0x7F) ascii++ else nonAscii++
                if (nonAscii + (ascii + 2L) / 3L > budget) break
                index += if (forward) 1 else -1
            }
            return if (index > 0 && index < text.length && Character.isLowSurrogate(text[index]) && Character.isHighSurrogate(
                    text[index - 1]
                )
            ) index - 1 else index
        }
    }
}

/**
 * 完成帧只有真正覆盖已流出的文本时才采信；否则用已流出的文本。
 * 否则网关省略/截断收尾 content 时，历史里会留下一条空的 assistant 消息。
 */
private fun reconcileCompletedText(complete: String, streamed: String): String =
    if (complete.isNotEmpty() && complete.startsWith(streamed)) complete else streamed

/** Shared declaration mapping for native executors and their optional UI facade. */
fun ToolSpec.toKoogDescriptor(): ToolDescriptor {
    fun parameter(param: ToolParam) = ToolParameterDescriptor(
        name = param.name, description = param.description,
        type = if (param.enumValues.isNotEmpty()) ToolParameterType.Enum(param.enumValues.toTypedArray()) else when (param.type) {
            ToolParam.ParamType.STRING -> ToolParameterType.String
            ToolParam.ParamType.INTEGER -> ToolParameterType.Integer
            ToolParam.ParamType.NUMBER -> ToolParameterType.Float
            ToolParam.ParamType.BOOLEAN -> ToolParameterType.Boolean
            ToolParam.ParamType.STRING_ARRAY -> ToolParameterType.List(ToolParameterType.String)
        },
    )
    return ToolDescriptor(
        name,
        description,
        params.filter { it.required }.map(::parameter),
        params.filterNot { it.required }.map(::parameter)
    )
}
