package hk.uwu.reareye.agent.context

import hk.uwu.reareye.agent.interaction.AskCallback
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
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
    ): Int = estimateComposition(systemPrompt, tools, messages).totalTokens

    /** Same arithmetic as budgeting, with explicit estimate-only provenance and no raw payload. */
    fun estimateComposition(
        systemPrompt: String,
        tools: List<ToolSpec>,
        messages: List<ChatMessage>
    ): ContextComposition {
        val segments = ArrayList<ContextSegment>()
        fun add(category: ContextCategory, tokens: Int, messageIndex: Int? = null) {
            if (tokens > 0) segments += ContextSegment(category, tokens, messageIndex)
        }
        fun text(category: ContextCategory, value: String, messageIndex: Int? = null) =
            add(category, TokenBudgetEstimator.estimateTokens(value), messageIndex)
        text(ContextCategory.SYSTEM, systemPrompt)
        add(ContextCategory.FORMATTING, 16)
        tools.forEach {
            text(ContextCategory.TOOL_DEFINITIONS, it.name + it.description + it.parametersSchema())
            add(ContextCategory.FORMATTING, 16)
        }
        KoogTranscriptCodec.validate(messages)
        messages.forEachIndexed { index, message ->
            message.nativeEncoding?.let {
                // Include attachments, signed/encrypted reasoning and metadata once. Their raw
                // native encoding cannot be reliably split using the display-only projection.
                text(ContextCategory.NATIVE_PROTOCOL, it.message.toString(), index)
                add(ContextCategory.FORMATTING, 12, index)
                return@forEachIndexed
            }
            add(ContextCategory.FORMATTING, 12, index)
            val category = when (message.role) {
                MessageRole.SYSTEM -> ContextCategory.SYSTEM
                MessageRole.USER -> ContextCategory.USER
                MessageRole.ASSISTANT -> ContextCategory.ASSISTANT
                MessageRole.TOOL -> ContextCategory.TOOL_RESULTS
            }
            text(category, message.content.orEmpty(), index)
            text(ContextCategory.FORMATTING, message.name.orEmpty() + message.toolCallId.orEmpty(), index)
            message.toolCalls.forEach {
                text(ContextCategory.TOOL_CALLS, it.id + it.name + it.argumentsJson, index)
                add(ContextCategory.FORMATTING, 12, index)
            }
            message.protocolItems.forEach {
                text(ContextCategory.NATIVE_PROTOCOL, it.toString(), index)
                add(ContextCategory.FORMATTING, 8, index)
            }
        }
        return ContextComposition.fromSegments(segments)
    }
}
