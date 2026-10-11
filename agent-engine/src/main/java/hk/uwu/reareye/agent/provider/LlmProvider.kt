package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.coroutines.flow.Flow

/** Everything the engine needs for one model call. */
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

/**
 * Streaming events from a single model call.
 *
 * [ToolCallDelta] carries incremental fragments: the name and arguments arrive as text that must
 * be concatenated per [index] before any JSON parsing is attempted. Nothing except the final
 * [Finished] is guaranteed to be complete.
 */
sealed interface ChatEvent {
    data class TextDelta(val text: String) : ChatEvent

    data class ToolCallDelta(
        val index: Int,
        val id: String? = null,
        val nameDelta: String? = null,
        val argumentsDelta: String? = null,
    ) : ChatEvent

    /**
     * @param finishReason provider-supplied reason, e.g. `stop`, `tool_calls`, `length`.
     */
    data class Finished(val finishReason: String?) : ChatEvent
}

/** A model backend. Implementations must be cancellable: cancelling collection aborts the call. */
interface LlmProvider {
    val id: String

    fun chat(request: ChatRequest): Flow<ChatEvent>

    /** Best-effort model listing; returns an empty list when the endpoint does not support it. */
    suspend fun listModels(): List<String> = emptyList()
}
