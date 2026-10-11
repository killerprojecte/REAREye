package hk.uwu.reareye.agent.loop

import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolCall
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.provider.ChatRequest
import hk.uwu.reareye.agent.provider.LlmProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** A tool call the loop intends to run, surfaced to the UI before execution. */
data class ToolCallRequest(
    val id: String,
    val name: String,
    val argumentsJson: String,
    val risk: ToolRisk,
)

/** What the UI observes while a turn runs. */
sealed interface AgentEvent {
    data class TurnStarted(val iteration: Int) : AgentEvent

    data class TextDelta(val text: String) : AgentEvent

    data class ToolCallStarted(val id: String, val name: String) : AgentEvent

    /** Argument fragments as they stream in, useful for a live "calling…" hint. */
    data class ToolCallArguments(val id: String, val fragment: String) : AgentEvent

    data class ToolCallFinished(val request: ToolCallRequest) : AgentEvent

    data class ToolResultEvent(
        val id: String,
        val name: String,
        val result: ToolResult,
    ) : AgentEvent

    /** The turn produced its final answer; [messages] is the history to persist. */
    data class TurnFinished(
        val messages: List<ChatMessage>,
        val iterations: Int,
    ) : AgentEvent

    data class Failed(val error: AgentError) : AgentEvent
}

/** Asks the user whether a mutating tool may run. Return false to deny. */
typealias ApprovalCallback = suspend (ToolCallRequest) -> Boolean

/**
 * Runs one user turn to completion: model call, tool execution, repeat until the model stops
 * asking for tools or the iteration ceiling is reached.
 *
 * The loop is deliberately transport agnostic — [provider] does the HTTP, [tools] does the
 * Android work, and this class owns only the control flow.
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val tools: ToolEnvironment,
    private val config: AgentConfig,
    private val json: Json = JSON,
    private val approve: ApprovalCallback = { false },
) {

    /**
     * @param history full prior conversation, including the new user message.
     */
    fun run(history: List<ChatMessage>): Flow<AgentEvent> = flow {
        val working = history.toMutableList()
        val startedAtMs = System.currentTimeMillis()
        val repeatCounts = mutableMapOf<String, Int>()
        var iterations = 0

        emit(AgentEvent.TurnStarted(iteration = 0))

        while (true) {
            // Wall-clock ceiling first: a model that keeps selecting *different* tools can outrun
            // any step count, so the clock is the ceiling that actually bounds the turn.
            if (System.currentTimeMillis() - startedAtMs > config.turnBudgetMs) {
                emit(
                    AgentEvent.Failed(
                        AgentError.Protocol(
                            "本轮已超过 ${config.turnBudgetMs / 1000} 秒的时间上限，已停止。",
                        ),
                    ),
                )
                return@flow
            }
            if (iterations >= config.maxIterations) {
                emit(
                    AgentEvent.Failed(
                        AgentError.Protocol(
                            "已达单轮工具调用上限（${config.maxIterations} 次），已停止以免继续消耗额度。",
                        ),
                    ),
                )
                return@flow
            }
            iterations++

            val specs = tools.availableTools()
            val request = ChatRequest(
                model = config.provider.model,
                messages = buildList {
                    if (config.systemPrompt.isNotBlank()) {
                        add(ChatMessage(role = MessageRole.SYSTEM, content = config.systemPrompt))
                    }
                    addAll(working)
                },
                tools = specs,
                temperature = config.provider.temperature,
                maxOutputTokens = config.provider.maxOutputTokens,
            )

            val text = StringBuilder()
            val accumulators = sortedMapOf<Int, ToolCallAccumulator>()
            var streamFinished = false

            try {
                provider.chat(request).collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> {
                            text.append(event.text)
                            emit(AgentEvent.TextDelta(event.text))
                        }

                        is ChatEvent.ToolCallDelta -> {
                            val acc = accumulators.getOrPut(event.index) { ToolCallAccumulator() }
                            val wasUnnamed = acc.id == null
                            acc.apply(event)
                            // Announce the call as soon as we know which tool is being invoked.
                            if (wasUnnamed && acc.id != null) {
                                emit(AgentEvent.ToolCallStarted(acc.id!!, acc.name))
                            } else {
                                event.argumentsDelta?.let {
                                    emit(AgentEvent.ToolCallArguments(acc.id.orEmpty(), it))
                                }
                            }
                        }

                        is ChatEvent.Finished -> streamFinished = true
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: AgentError) {
                emit(AgentEvent.Failed(error))
                return@flow
            } catch (t: Throwable) {
                emit(AgentEvent.Failed(AgentError.Network(t.message ?: "调用模型失败。", t)))
                return@flow
            }

            val calls: List<ToolCall> = accumulators.map { (index, acc) -> acc.build(index) }
            calls.forEach { call ->
                emit(
                    AgentEvent.ToolCallFinished(
                        ToolCallRequest(
                            id = call.id,
                            name = call.name,
                            argumentsJson = call.argumentsJson,
                            risk = riskOf(call.name, specs),
                        ),
                    ),
                )
            }

            working += ChatMessage(
                role = MessageRole.ASSISTANT,
                content = text.toString().ifEmpty { null },
                toolCalls = calls,
            )

            if (calls.isEmpty()) {
                if (!streamFinished) {
                    emit(
                        AgentEvent.Failed(
                            AgentError.Protocol("模型响应在结束前中断，本轮结果可能不完整。"),
                        ),
                    )
                    return@flow
                }
                emit(AgentEvent.TurnFinished(messages = working.toList(), iterations = iterations))
                return@flow
            }

            calls.forEach { call ->
                val result = execute(call, specs)
                emit(AgentEvent.ToolResultEvent(id = call.id, name = call.name, result = result))
                working += ChatMessage(
                    role = MessageRole.TOOL,
                    content = result.content,
                    toolCallId = call.id,
                    name = call.name,
                )
            }

            // A repeated identical call cannot produce new information, so stop rather than let
            // the model burn the whole budget rediscovering the same answer.
            val signature = calls.joinToString("|") { "${it.name}::${it.argumentsJson}" }
            val repeats = (repeatCounts[signature] ?: 0) + 1
            repeatCounts[signature] = repeats
            if (repeats > config.maxRepeatedToolCalls) {
                emit(
                    AgentEvent.Failed(
                        AgentError.Protocol(
                            "模型重复发起了相同的工具调用 $repeats 次，判断为循环，已停止本轮。",
                        ),
                    ),
                )
                return@flow
            }
        }
    }

    private suspend fun execute(call: ToolCall, specs: List<ToolSpec>): ToolResult {
        val spec = specs.firstOrNull { it.name == call.name }
            ?: return ToolResult.error(
                envelope(
                    error = "tool_not_found",
                    detail = "工具 '${call.name}' 不存在。",
                    recovery = "请只使用下面列出的工具，或直接用自然语言回答。",
                    extra = mapOf(
                        "tools_available_this_turn" to
                            specs.joinToString(", ") { it.name }.ifBlank { "(无)" },
                    ),
                ),
            )

        if (spec.risk == ToolRisk.MUTATING || !config.autoApproveReadOnly) {
            val approved = runCatching {
                approve(
                    ToolCallRequest(
                        id = call.id,
                        name = call.name,
                        argumentsJson = call.argumentsJson,
                        risk = spec.risk,
                    ),
                )
            }.getOrElse { false }
            if (!approved) {
                return ToolResult.error(
                    "用户拒绝执行 ${call.name}。请不要重试同一操作，改为说明你的意图或询问用户。",
                )
            }
        }

        // Empty arguments are a valid "no parameters" call, not a parse failure.
        val rawArgs = call.argumentsJson.ifBlank { ToolCall.BLANK_ARGS }
        val args = runCatching { json.parseToJsonElement(rawArgs).jsonObject }
            .getOrElse {
                // Keep the detail short: dumping a truncated argument blob back into the context
                // is expensive and makes the model retry the same oversized call.
                return ToolResult.error(
                    envelope(
                        error = "invalid_tool_args",
                        detail = "参数不是合法 JSON：${rawArgs.take(200)}",
                        recovery = "请用正确的 JSON 参数重新调用，必要时拆成更小的调用。",
                    ),
                )
            }

        val result = try {
            withTimeoutOrNull(config.toolTimeoutMs) {
                tools.execute(spec.name, args)
            } ?: ToolResult.error(
                envelope(
                    error = "tool_timeout",
                    detail = "工具 ${spec.name} 超过 ${config.toolTimeoutMs / 1000} 秒未返回。",
                    recovery = "请换用更小的查询范围，或告知用户该操作超时。",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            ToolResult.error(
                envelope(
                    error = "tool_failed",
                    detail = (t.message ?: t.javaClass.simpleName).take(500),
                    recovery = "请判断这是否是参数问题；不要原样重试。",
                    extra = mapOf("exception" to t.javaClass.simpleName),
                ),
            )
        }
        return result.capped(config.maxToolResultChars)
    }

    /**
     * Unknown tools are treated as mutating so a newly added tool can never run silently.
     */
    private fun riskOf(name: String, specs: List<ToolSpec>): ToolRisk =
        specs.firstOrNull { it.name == name }?.risk ?: ToolRisk.MUTATING

    private class ToolCallAccumulator {
        var id: String? = null
        var name: String = ""
        private val arguments = StringBuilder()

        fun apply(delta: ChatEvent.ToolCallDelta) {
            if (id == null) delta.id?.let { id = it }
            delta.nameDelta?.let { name += it }
            delta.argumentsDelta?.let { arguments.append(it) }
        }

        fun build(index: Int): ToolCall = ToolCall(
            id = id ?: "call_$index",
            name = name.ifBlank { "unknown" },
            argumentsJson = arguments.toString().ifBlank { ToolCall.BLANK_ARGS },
        )
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

        /**
         * Machine-readable failure envelope.
         *
         * Every failure the model can act on carries `error` (stable code), `detail` (what went
         * wrong, short) and `recovery` (what to do instead). Raw exception text and stack traces
         * are deliberately excluded: they cost hundreds of tokens, confuse the model, and surface
         * implementation details that are useless for deciding what to do next.
         */
        private fun envelope(
            error: String,
            detail: String,
            recovery: String? = null,
            extra: Map<String, String> = emptyMap(),
        ): String = buildJsonObject {
            put("error", error)
            put("detail", detail)
            recovery?.let { put("recovery", it) }
            extra.forEach { (key, value) -> put(key, value) }
        }.toString()

        /**
         * Keep one oversized tool result from flooding the context: keep both ends, because the
         * conclusion of a command — the error, the exit status, the final summary — usually lives
         * at the tail, while the head carries the setup.
         */
        private fun ToolResult.capped(limit: Int): ToolResult {
            if (limit <= 0 || content.length <= limit) return this
            val headLength = (limit * 0.6).toInt()
            val tailLength = (limit - headLength).coerceAtLeast(0)
            val head = content.take(headLength)
            val tail = if (tailLength > 0) content.takeLast(tailLength) else ""
            val elided = content.length - head.length - tail.length
            return copy(content = "$head\n…（省略 $elided 个字符）…\n$tail")
        }
    }
}
