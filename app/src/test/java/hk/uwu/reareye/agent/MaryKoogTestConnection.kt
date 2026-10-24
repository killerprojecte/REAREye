package hk.uwu.reareye.agent

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
import hk.uwu.reareye.agent.provider.KoogConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/** Test-only native executor: production never converts a legacy provider back into Koog. */
internal fun maryTestConnection(
    config: ProviderConfig = ProviderConfig(model = "fake"),
    onClose: () -> Unit = {},
    script: suspend FlowCollector<StreamFrame>.(Prompt, List<ToolDescriptor>) -> Unit,
): KoogConnection {
    val executor = object : PromptExecutor() {
        override fun executeStreaming(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Flow<StreamFrame> =
            flow { script(prompt, tools) }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            val parts = linkedMapOf<Int, MessagePart.ResponsePart>()
            var ending: StreamFrame.End? = null
            executeStreaming(prompt, model, tools).collect { frame ->
                check(ending == null) { "Frames after end" }
                val index = (frame as? StreamFrame.DeltaFrame)?.index
                    ?: (frame as? StreamFrame.CompleteFrame)?.index ?: 0
                when (frame) {
                    is StreamFrame.TextDelta -> parts[index] =
                        MessagePart.Text((parts[index] as? MessagePart.Text)?.text.orEmpty() + frame.text)

                    is StreamFrame.TextComplete -> parts[index] = MessagePart.Text(frame.text)
                    is StreamFrame.ToolCallComplete -> parts[index] =
                        MessagePart.Tool.Call(frame.id, frame.name, frame.content)

                    is StreamFrame.ReasoningComplete -> parts[index] = MessagePart.Reasoning(
                        frame.content,
                        frame.summary,
                        frame.encrypted,
                        frame.id
                    )

                    is StreamFrame.End -> ending = frame
                    else -> Unit
                }
            }
            val end = checkNotNull(ending) { "Model stream ended before completion" }
            return Message.Assistant(parts.values.toList(), end.metaInfo, end.finishReason)
        }

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("Unexpected moderation")

        override fun close() = onClose()
    }
    return KoogConnection(
        executor,
        LLModel(
            LLMProvider.OpenAI,
            config.model,
            listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.Temperature),
            contextLength = config.contextWindowTokens.toLong(),
            maxOutputTokens = (config.maxOutputTokens ?: 4096).toLong()
        ),
        LLMParams(maxTokens = config.maxOutputTokens),
    )
}

internal suspend fun FlowCollector<StreamFrame>.emitText(text: String, finish: String = "stop") {
    emit(StreamFrame.TextDelta(text))
    emit(StreamFrame.TextComplete(text))
    emit(StreamFrame.End(finish, ResponseMetaInfo.Empty))
}
