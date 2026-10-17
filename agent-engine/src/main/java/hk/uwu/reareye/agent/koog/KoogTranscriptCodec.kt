package hk.uwu.reareye.agent.koog

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.NativeMessageEncoding
import hk.uwu.reareye.agent.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The native payload is authoritative; the legacy fields are a checked display projection. */
object KoogTranscriptCodec {
    private const val VERSION = 1
    private const val LEGACY = "reareye.legacy.v1"
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun toNative(messages: List<ChatMessage>): List<Message> = messages.map { message ->
        val encoding = message.nativeEncoding
        if (encoding == null) legacyToNative(message) else {
            require(encoding.version == VERSION) { "Unsupported native message encoding: ${encoding.version}" }
            val native = try {
                json.decodeFromJsonElement(Message.serializer(), encoding.message)
            } catch (error: Exception) {
                throw IllegalArgumentException("Invalid native Koog message payload", error)
            }
            require(project(native) == message.copy(nativeEncoding = null)) {
                "ChatMessage projection differs from its authoritative native payload; rebuild the native message explicitly"
            }
            native
        }
    }

    fun fromNative(messages: List<Message>): List<ChatMessage> = messages.map { native ->
        project(native).copy(
            nativeEncoding = NativeMessageEncoding(
                version = VERSION,
                message = json.encodeToJsonElement(Message.serializer(), native).jsonObject,
            )
        )
    }

    /** Validate only persisted native messages; legacy messages remain readable without execution. */
    fun validate(messages: List<ChatMessage>) {
        toNative(messages.filter { it.nativeEncoding != null })
    }

    private fun project(message: Message): ChatMessage {
        val legacy = message.metaInfo.metadata?.get(LEGACY)?.jsonObject
        val protocols = legacy?.get("protocolItems")?.let {
            json.decodeFromJsonElement<List<JsonObject>>(it)
        }.orEmpty()
        val name = legacy?.get("name")?.jsonPrimitive?.contentOrNull
        val text = message.parts.filterIsInstance<MessagePart.Text>()
        val nullContent = legacy?.get("nullContent")?.jsonPrimitive?.booleanOrNull == true
        val content =
            if (text.isEmpty() || nullContent) null else text.joinToString("\n") { it.text }
        return when (message) {
            is Message.System -> ChatMessage(
                MessageRole.SYSTEM,
                content,
                name = name,
                protocolItems = protocols
            )

            is Message.Assistant -> ChatMessage(
                MessageRole.ASSISTANT, content,
                toolCalls = message.parts.filterIsInstance<MessagePart.Tool.Call>().map {
                    ToolCall(it.id.orEmpty(), it.tool, it.args)
                },
                name = name, protocolItems = protocols,
            )

            is Message.User -> {
                val result = message.parts.singleOrNull() as? MessagePart.Tool.Result
                if (result == null) ChatMessage(
                    MessageRole.USER,
                    content,
                    name = name,
                    protocolItems = protocols
                )
                else ChatMessage(
                    MessageRole.TOOL,
                    if (nullContent) null else result.output,
                    toolCallId = result.id,
                    name = if (legacy != null) name else result.tool,
                    protocolItems = protocols
                )
            }
        }
    }

    private fun legacyToNative(message: ChatMessage): Message {
        require(message.role == MessageRole.ASSISTANT || message.toolCalls.isEmpty()) {
            "Tool calls require an assistant message"
        }
        require(message.role == MessageRole.TOOL || message.toolCallId == null) {
            "A tool result ID requires a tool message"
        }
        val metadata = buildJsonObject {
            put(LEGACY, buildJsonObject {
                put("name", message.name?.let(::JsonPrimitive) ?: JsonNull)
                put("nullContent", message.content == null)
                put("protocolItems", json.encodeToJsonElement(message.protocolItems))
            })
        }
        val request = RequestMetaInfo.Empty.copy(metadata = metadata)
        val response = ResponseMetaInfo.Empty.copy(metadata = metadata)
        return when (message.role) {
            MessageRole.SYSTEM -> {
                require(message.protocolItems.isEmpty()) { "Protocol reasoning is only valid in assistant messages" }
                Message.System(message.content.orEmpty(), request)
            }

            MessageRole.USER -> {
                require(message.protocolItems.isEmpty()) { "Protocol reasoning is only valid in assistant messages" }
                Message.User(message.content.orEmpty(), request)
            }

            MessageRole.TOOL -> {
                require(message.protocolItems.isEmpty()) { "Protocol reasoning is only valid in assistant messages" }
                Message.User(
                    MessagePart.Tool.Result(
                        id = message.toolCallId, tool = message.name.orEmpty(),
                        output = message.content.orEmpty()
                    ), request
                )
            }

            MessageRole.ASSISTANT -> Message.Assistant(buildList {
                message.protocolItems.forEach { add(legacyReasoning(it)) }
                message.content?.let { add(MessagePart.Text(it)) }
                message.toolCalls.forEach {
                    add(
                        MessagePart.Tool.Call(
                            it.id,
                            it.name,
                            it.argumentsJson
                        )
                    )
                }
            }, response)
        }
    }

    private fun legacyReasoning(item: JsonObject): MessagePart.Reasoning =
        when (item["type"]?.jsonPrimitive?.content) {
            "thinking" -> MessagePart.Reasoning(
                content = listOf(item["thinking"]?.jsonPrimitive?.content.orEmpty()),
                encrypted = item["signature"]?.jsonPrimitive?.contentOrNull
            )

            "redacted_thinking" -> MessagePart.Reasoning(
                content = listOf(""),
                encrypted = "reareye:anthropic:redacted:" + requireNotNull(item["data"]?.jsonPrimitive?.contentOrNull) {
                    "Redacted thinking requires opaque data"
                })

            "reasoning" -> MessagePart.Reasoning(
                content = item["content"]?.jsonArray?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    .orEmpty(),
                summary = item["summary"]?.jsonArray?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull },
                encrypted = item["encrypted_content"]?.jsonPrimitive?.contentOrNull,
                id = item["id"]?.jsonPrimitive?.contentOrNull,
            )

            else -> throw IllegalArgumentException("Unsupported legacy protocol item: ${item["type"]}")
        }
}
