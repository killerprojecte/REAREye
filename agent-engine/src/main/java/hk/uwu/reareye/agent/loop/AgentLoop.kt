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

    /** A fragment of the model's reasoning, for a collapsible "thinking" view. */
    data class ReasoningDelta(val text: String) : AgentEvent

    /**
     * Something the user should be told about that is not a failure, e.g. the reply was cut off by
     * the output-token ceiling.
     *
     * Distinct from [Failed]: the turn produced usable output, it is just incomplete.
     */
    data class Notice(val text: String) : AgentEvent

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
        // Per-tool consecutive failure count, keyed by tool name only — see the note at the
        // increment site for why the arguments must not be part of this key.
        val consecutiveFailures = mutableMapOf<String, Int>()
        var iterations = 0

        emit(AgentEvent.TurnStarted(iteration = 0))

        while (true) {
            // Wall-clock ceiling first: a model that keeps selecting *different* tools can outrun
            // any step count, so the clock is the ceiling that actually bounds the turn.
            //
            // Two limits worth knowing: this reads the real clock (so tests can only exercise the
            // already-expired case), and it is evaluated only at iteration boundaries — a single
            // long stream can therefore run past the budget before it is noticed. Bounding that
            // would mean aborting a stream mid-flight, which costs the partial answer.
            if (System.currentTimeMillis() - startedAtMs > config.turnBudgetMs) {
                val minutes = (config.turnBudgetMs / 60_000).coerceAtLeast(1)
                emit(
                    AgentEvent.Failed(
                        AgentError.Protocol(
                            "本轮已经跑了 $minutes 分钟，达到时间上限，已停止。" +
                                "生成较长的内容（比如一个完整的组件 HTML）本来就很慢，" +
                                "如果任务还没做完，可以在设置里把时间上限调大后重试；" +
                                "已经完成的部分不受影响。",
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
            val reasoning = StringBuilder()
            val accumulators = sortedMapOf<Int, ToolCallAccumulator>()
            var streamFinished = false
            var finishReason: String? = null

            try {
                provider.chat(request).collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> {
                            text.append(event.text)
                            emit(AgentEvent.TextDelta(event.text))
                        }

                        is ChatEvent.ReasoningDelta -> {
                            reasoning.append(event.text)
                            emit(AgentEvent.ReasoningDelta(event.text))
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

                        is ChatEvent.Finished -> {
                            streamFinished = true
                            finishReason = event.finishReason
                        }
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
                // `length` means the model was cut off by the output-token ceiling, or by its own
                // context limit. Without this the reply simply stops mid-sentence with no
                // explanation, which reads as a bug in the app.
                if (finishReason.equals("length", ignoreCase = true)) {
                    emit(
                        AgentEvent.Notice(
                            "这条回复因达到输出长度上限被截断，内容可能不完整。" +
                                "可以在设置里调大「最大输出 token」，或让我接着上面继续说。",
                        ),
                    )
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

                // Count failures per tool, ignoring the arguments. A generate-fix-resubmit loop
                // always has different arguments, so the signature-based guard below cannot catch
                // it; this is what stops it before it burns the whole turn.
                val failures = if (result.isError) {
                    (consecutiveFailures[call.name] ?: 0) + 1
                } else {
                    0
                }
                consecutiveFailures[call.name] = failures

                if (failures >= config.maxConsecutiveToolFailures) {
                    emit(
                        AgentEvent.Failed(
                            AgentError.Protocol(
                                "工具 ${call.name} 已经连续失败 $failures 次，停止本轮以免继续浪费。" +
                                    "最后一次的错误信息在上面，请如实告诉用户发生了什么，" +
                                    "不要声称操作成功。",
                            ),
                        ),
                    )
                    return@flow
                }
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
        // A tool may raise its own ceiling: one global value cannot serve both a short status read
        // and a tool whose result embeds a whole generated document.
        return result.capped(spec.maxResultChars ?: config.maxToolResultChars)
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
         * Rough token count: ASCII is billed at about a third of a token per character, everything
         * else at roughly one token each. Deliberately cheap and dependency-free — it only has to
         * be right enough to decide when a result needs trimming.
         *
         * This is why the budget is not measured in characters: 8,000 characters is ~2,700 tokens of
         * English but ~8,000 tokens of Chinese, so a character threshold is twice as strict for one
         * language as the other.
         */
        fun estimateTokens(text: String): Int {
            var ascii = 0L
            var nonAscii = 0L
            for (char in text) {
                if (char.code <= 0x7F) ascii++ else nonAscii++
            }
            return (nonAscii + (ascii + 2L) / 3L).toInt()
        }

        /**
         * Tokens held back so the marker cannot push the result back over budget.
         *
         * The marker renders to roughly 24 tokens. For a degenerate budget this may overshoot; the
         * marker is metadata rather than content, and dropping it would leave the model with
         * silently missing data.
         */
        private const val MARKER_RESERVE_TOKENS = 32

        private const val TRUNCATION_MARKER = "\n…（已省略 %d 个字符以适配长度上限）…\n"

        /**
         * Keep one oversized tool result from flooding the context: keep both ends, because the
         * conclusion of a command — the error, the exit status, the final summary — usually lives
         * at the tail, while the head carries the setup.
         *
         * Modelled on how RikkaHub does it (`truncateToolResult`): the budget is measured in
         * estimated **tokens**, the marker is paid for out of the same budget, and a budget too
         * small to split keeps only the head rather than letting the two slices overlap.
         *
         * What is deliberately not copied is the spill: RikkaHub writes the full text under
         * `/tool_outputs/` and hands the model a path, but that only works because its agent has a
         * shell. Mary has no root and cannot read files, so a path would be an instruction she
         * cannot follow — worse than simply reporting that the result was too large.
         *
         * `limitChars <= 0` disables the cap entirely.
         */
        private fun ToolResult.capped(limitChars: Int): ToolResult {
            if (limitChars <= 0) return this
            val totalTokens = estimateTokens(content)
            // The setting is in characters to stay legible, but the comparison is in tokens so the
            // same threshold means the same thing in Chinese and in English.
            val budgetTokens = estimateTokens("x".repeat(limitChars))
            if (totalTokens <= budgetTokens) return this

            val budget = (budgetTokens - MARKER_RESERVE_TOKENS).coerceAtLeast(1)
            // At least one token to the head, otherwise a degenerate budget silently drops the
            // beginning of the result and keeps only the marker plus the tail.
            val headBudget = ((budget * 0.6).toInt()).coerceIn(1, budget)
            val tailBudget = budget - headBudget

            val headEnd = advanceByTokens(content, from = 0, to = content.length, budget = headBudget, forward = true)
            val tailStart = advanceByTokens(content, from = content.length, to = 0, budget = tailBudget, forward = false)

            val rendered = if (tailBudget <= 0 || tailStart <= headEnd) {
                // Too small to split, or the slices would overlap: head only.
                val head = content.substring(0, headEnd).trimEnd()
                head + TRUNCATION_MARKER.format(content.length - head.length)
            } else {
                val head = content.substring(0, headEnd)
                val tail = content.substring(tailStart)
                head + TRUNCATION_MARKER.format(tailStart - headEnd) + tail
            }
            return copy(content = rendered)
        }

        /**
         * Walks [text] between [from] (inclusive) and [to] (exclusive), advancing while the running
         * token estimate stays within [budget], and returns the index reached.
         *
         * Forward from 0 yields the end of the head slice; backward from `length` yields the start
         * of the tail slice.
         *
         * A boundary that would land on the low half of a surrogate pair is pulled one step further
         * out, so a cut can never leave an unpaired surrogate in the text (emoji are pairs).
         */
        private fun advanceByTokens(
            text: String,
            from: Int,
            to: Int,
            budget: Int,
            forward: Boolean,
        ): Int {
            if (budget <= 0) return from
            var ascii = 0L
            var nonAscii = 0L
            var index = from
            while (index != to) {
                val char = text[if (forward) index else index - 1]
                if (char.code <= 0x7F) ascii++ else nonAscii++
                val tokens = nonAscii + (ascii + 2L) / 3L
                if (tokens > budget) break
                index += if (forward) 1 else -1
            }
            // Never return an index that would split a surrogate pair.
            //
            // A split happens when the character *after* the boundary is a low surrogate whose high
            // half sits *before* it: that pair straddles the cut. Withdraw by one so the pair stays
            // together on the far side. Both directions use that same test, because both are
            // choosing a cut position rather than a direction.
            return if (index > 0 && index < text.length &&
                Character.isLowSurrogate(text[index]) &&
                Character.isHighSurrogate(text[index - 1])
            ) {
                index - 1
            } else {
                index
            }
        }
    }
}
