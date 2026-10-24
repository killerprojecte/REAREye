package hk.uwu.reareye.agent.loop

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.context.ContextBudgetCallback
import hk.uwu.reareye.agent.interaction.ApprovalDecision
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.interaction.AskCallback
import hk.uwu.reareye.agent.interaction.AutoApprovalCallback
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.provider.ChatRequest
import hk.uwu.reareye.agent.provider.KoogConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Native executor fake. Existing regression fixtures retain their compact event notation,
 * but production receives only PromptExecutor/StreamFrame, never a legacy provider adapter.
 */
internal abstract class FixtureKoogExecutor : PromptExecutor() {
    val nativeRequests = mutableListOf<Prompt>()
    open fun fixture(request: ChatRequest): Flow<ChatEvent> = error("No fixture script")

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> = flow {
        nativeRequests += prompt
        val request = ChatRequest(
            model.id,
            KoogTranscriptCodec.fromNative(prompt.messages).map { it.copy(nativeEncoding = null) })
        val text = StringBuilder()
        val reasoning = StringBuilder()

        data class Call(
            var id: String? = null,
            var name: String = "",
            val args: StringBuilder = StringBuilder()
        )

        val calls = sortedMapOf<Int, Call>()
        var usage: ChatEvent.Usage? = null
        val protocol = mutableListOf<JsonObject>()
        fixture(request).collect { event ->
            when (event) {
                is ChatEvent.TextDelta -> {
                    text.append(event.text); emit(StreamFrame.TextDelta(event.text))
                }

                is ChatEvent.ReasoningDelta -> {
                    reasoning.append(event.text); emit(StreamFrame.ReasoningDelta(text = event.text))
                }

                is ChatEvent.ToolCallDelta -> {
                    val call = calls.getOrPut(event.index) { Call() }
                    event.id?.let { call.id = it }
                    event.nameDelta?.let { call.name += it }
                    event.argumentsDelta?.let { call.args.append(it) }
                    emit(
                        StreamFrame.ToolCallDelta(
                            event.id,
                            event.nameDelta,
                            event.argumentsDelta,
                            event.index
                        )
                    )
                }

                is ChatEvent.ProtocolItem -> protocol += event.item
                is ChatEvent.Usage -> usage = event
                is ChatEvent.Finished -> {
                    if (text.isNotEmpty()) emit(StreamFrame.TextComplete(text.toString()))
                    if (reasoning.isNotEmpty()) emit(
                        StreamFrame.ReasoningComplete(
                            null,
                            listOf(reasoning.toString())
                        )
                    )
                    if (protocol.isNotEmpty()) {
                        val native = KoogTranscriptCodec.toNative(
                            listOf(
                                ChatMessage(
                                    MessageRole.ASSISTANT,
                                    protocolItems = protocol
                                )
                            )
                        ).single()
                        native.parts.filterIsInstance<MessagePart.Reasoning>().forEach {
                            emit(
                                StreamFrame.ReasoningComplete(
                                    it.id,
                                    it.content,
                                    it.summary,
                                    it.encrypted
                                )
                            )
                        }
                    }
                    calls.forEach { (index, call) ->
                        emit(
                            StreamFrame.ToolCallComplete(
                                call.id,
                                call.name,
                                call.args.toString(),
                                index
                            )
                        )
                    }
                    val meta = ResponseMetaInfo.Empty.copy(
                        inputTokensCount = usage?.inputTokens,
                        outputTokensCount = usage?.outputTokens,
                        metadata = usage?.let { value ->
                            buildJsonObject {
                                put("usage", buildJsonObject {
                                    value.cachedInputTokens?.let {
                                        put(
                                            "cache_read_input_tokens",
                                            it
                                        )
                                    }
                                    value.cacheCreationInputTokens?.let {
                                        put(
                                            "cache_creation_input_tokens",
                                            it
                                        )
                                    }
                                })
                            }
                        },
                    )
                    emit(StreamFrame.End(event.finishReason, meta))
                }
            }
        }
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant {
        val frames = executeStreaming(prompt, model, tools).toList()
        val end = frames.filterIsInstance<StreamFrame.End>().single()
        val parts = frames.mapNotNull { frame ->
            when (frame) {
                is StreamFrame.TextComplete -> MessagePart.Text(frame.text)
                is StreamFrame.ReasoningComplete -> MessagePart.Reasoning(
                    frame.content,
                    frame.summary,
                    frame.encrypted,
                    frame.id
                )

                is StreamFrame.ToolCallComplete -> MessagePart.Tool.Call(
                    frame.id,
                    frame.name,
                    frame.content
                )

                else -> null
            }
        }
        return Message.Assistant(parts, end.metaInfo, end.finishReason)
    }

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
        error("Unexpected moderation")

    override fun close() = Unit

    fun connection() = KoogConnection(
        this, LLModel(
            LLMProvider.OpenAI, "fixture", listOf(LLMCapability.Completion, LLMCapability.Tools),
            contextLength = 128_000, maxOutputTokens = 4096,
        ), LLMParams()
    )
}

/** Test convenience overload injects the native executor, not an LlmProvider. */
internal fun AgentLoop(
    provider: FixtureKoogExecutor,
    tools: ToolEnvironment,
    config: AgentConfig,
    json: Json = AgentLoop.JSON,
    approve: ApprovalCallback = { false },
    approvalMode: ApprovalMode? = null,
    autoApprove: AutoApprovalCallback = { ApprovalDecision.ASK_USER },
    ask: AskCallback? = null,
    checkBudget: ContextBudgetCallback? = null,
): AgentLoop = AgentLoop(
    provider.connection(),
    tools,
    config,
    json,
    approve,
    approvalMode,
    autoApprove,
    ask,
    checkBudget
)
