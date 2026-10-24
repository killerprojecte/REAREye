package hk.uwu.reareye.agent.context

import hk.uwu.reareye.agent.interaction.AskCallback
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.ToolSpec

/** The system prompt and tool definitions stay separate so compression never rewrites the prefix. */
data class ContextBudgetRequest(
    val messages: List<ChatMessage>,
    val systemPrompt: String,
    val tools: List<ToolSpec>,
    val contextWindowTokens: Int = 128_000,
    val outputReserveTokens: Int = 4096,
    val ask: AskCallback? = null,
) {
    val estimatedInputTokens: Int get() = ContextBudget.estimateInput(systemPrompt, tools, messages)
    val needsCompaction: Boolean
        get() = estimatedInputTokens.toLong() + outputReserveTokens.coerceAtLeast(
            0
        ) + contextWindowTokens.coerceAtLeast(0) / 10L >= contextWindowTokens
}

sealed interface ContextBudgetDecision {
    data class Continue(val messages: List<ChatMessage>) : ContextBudgetDecision
    data class Stop(val reason: String) : ContextBudgetDecision

    /** Request a single native-model summary of the entire active context. */
    data object Compress : ContextBudgetDecision
}
typealias ContextBudgetCallback = suspend (ContextBudgetRequest) -> ContextBudgetDecision

object ContextBudget {

    fun estimateInput(
        systemPrompt: String,
        tools: List<ToolSpec>,
        messages: List<ChatMessage>
    ): Int {
        var total = TokenBudgetEstimator.estimateTokens(systemPrompt).toLong() + 16
        tools.forEach {
            total += TokenBudgetEstimator.estimateTokens(it.name + it.description + it.parametersSchema())
                .toLong() + 16
        }
        KoogTranscriptCodec.validate(messages)
        messages.forEach { message ->
            message.nativeEncoding?.let {
                // Include attachments, signed/encrypted reasoning, metadata and tool parts, not only display text.
                total += TokenBudgetEstimator.estimateTokens(it.message.toString()).toLong() + 12
                return@forEach
            }
            total += 12 + TokenBudgetEstimator.estimateTokens(message.content.orEmpty()).toLong()
            total += TokenBudgetEstimator.estimateTokens(message.name.orEmpty() + message.toolCallId.orEmpty())
            message.toolCalls.forEach {
                total += TokenBudgetEstimator.estimateTokens(it.id + it.name + it.argumentsJson)
                    .toLong() + 12
            }
            message.protocolItems.forEach {
                total += TokenBudgetEstimator.estimateTokens(it.toString()).toLong() + 8
            }
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
