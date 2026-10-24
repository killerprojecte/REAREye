package hk.uwu.reareye.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Wire roles understood by OpenAI-compatible endpoints. */
@Serializable
enum class MessageRole {
    @SerialName("system")
    SYSTEM,

    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT,

    @SerialName("tool")
    TOOL,
}

/**
 * One entry in the conversation. [toolCalls] is only populated on assistant messages, and
 * [toolCallId] is only set on tool results.
 */
@Serializable
data class ChatMessage(
    val role: MessageRole,
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
    /** Opaque native reasoning items / signed thinking blocks for faithful continuation. */
    val protocolItems: List<JsonObject> = emptyList(),
    /** Authoritative Koog message. Projection edits must explicitly discard/rebuild this encoding. */
    val nativeEncoding: NativeMessageEncoding? = null,
)

/** Versioned, lossless native payload; absent for legacy text/tool messages. */
@Serializable
data class NativeMessageEncoding(
    val version: Int = 1,
    val message: JsonObject,
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    /** Raw JSON object string as produced by the model; may be blank when the call has no args. */
    val argumentsJson: String = "{}",
) {
    fun arguments(): JsonObject =
        runCatching { ARGUMENTS_JSON.parseToJsonElement(argumentsJson).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    companion object {
        private val ARGUMENTS_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        const val BLANK_ARGS = "{}"
    }
}
