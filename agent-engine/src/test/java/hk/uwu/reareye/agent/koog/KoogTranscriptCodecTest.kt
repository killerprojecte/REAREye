package hk.uwu.reareye.agent.koog

import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.NativeMessageEncoding
import hk.uwu.reareye.agent.model.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class KoogTranscriptCodecTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun allNativePartsMetadataAndOpaqueProtocolFieldsSurvivePersistence() {
        val image = MessagePart.Attachment(
            AttachmentSource.Image(
                AttachmentContent.Binary.Base64("aW1hZ2U="),
                "png"
            )
        )
        val metadata = buildJsonObject {
            put("signature", "signed-thinking")
            put("redacted_thinking", buildJsonObject { put("data", "opaque-redacted") })
        }
        val raw = buildJsonObject { put("encrypted_content", "opaque-responses-reasoning") }
        val messages: List<Message> = listOf(
            Message.System(
                listOf(MessagePart.Text("system"), MessagePart.Text("prefix")),
                RequestMetaInfo.Empty,
                "sys-id"
            ),
            Message.User(
                listOf(MessagePart.Text("question"), image),
                RequestMetaInfo.Empty.copy(metadata = metadata),
                "user-id"
            ),
            Message.Assistant(
                listOf(
                    MessagePart.Reasoning(
                        listOf("thinking"),
                        listOf("summary"),
                        "ciphertext",
                        "reasoning-id"
                    ),
                    MessagePart.Reasoning(emptyList(), encrypted = "redacted-data"),
                    MessagePart.Text("answer"), image,
                    MessagePart.Tool.Call("call-1", "tool", "{\"key\":1}"),
                    MessagePart.Tool.Call(null, "other", "{}"),
                ),
                ResponseMetaInfo.Empty.copy(
                    inputTokensCount = 31,
                    outputTokensCount = 7,
                    metadata = metadata
                ),
                finishReason = "tool_calls",
                rawResponse = raw,
                id = "assistant-id"
            ),
            Message.User(
                listOf(
                    MessagePart.Tool.Result(
                        "call-1",
                        "tool",
                        listOf(MessagePart.Text("result"), image),
                        isError = true
                    ),
                    MessagePart.Tool.Result(null, "other", "other result")
                ), RequestMetaInfo.Empty
            ),
        )
        val projected = KoogTranscriptCodec.fromNative(messages)
        val restored = json.decodeFromString<List<ChatMessage>>(json.encodeToString(projected))
        assertEquals(messages, KoogTranscriptCodec.toNative(restored))
        assertEquals(projected, restored)
        assertEquals(2, restored[2].toolCalls.size)
        assertEquals(MessageRole.USER, restored.last().role)
    }

    @Test
    fun individualToolResultProjectionKeepsItsNativeErrorAndAttachment() {
        val result = Message.User(
            MessagePart.Tool.Result("id", "tool", "failure", isError = true),
            RequestMetaInfo.Empty
        )
        val chat = KoogTranscriptCodec.fromNative(listOf(result)).single()
        assertEquals(MessageRole.TOOL, chat.role)
        assertEquals("id", chat.toolCallId)
        assertEquals(listOf(result), KoogTranscriptCodec.toNative(listOf(chat)))
    }

    @Test
    fun staleProjectionCannotSilentlyOverwriteNativeData() {
        val original = KoogTranscriptCodec.fromNative(
            listOf(
                Message.Assistant(
                    listOf(MessagePart.Text("answer"), MessagePart.Tool.Call("id", "tool", "{}")),
                    ResponseMetaInfo.Empty
                )
            )
        ).single()
        listOf(
            original.copy(content = "edited"), original.copy(role = MessageRole.USER),
            original.copy(toolCalls = emptyList()), original.copy(name = "changed"),
            original.copy(protocolItems = listOf(buildJsonObject { put("type", "thinking") }))
        ).forEach {
            expectInvalid { KoogTranscriptCodec.toNative(listOf(it)) }
        }
        // Explicitly discarding the encoding is allowed; this is never done implicitly by the codec.
        assertEquals(
            "edited",
            KoogTranscriptCodec.toNative(
                listOf(
                    original.copy(
                        content = "edited",
                        nativeEncoding = null
                    )
                )
            ).single().textContent()
        )
    }

    @Test
    fun malformedUnsupportedAndUnknownNativePayloadsFailExplicitly() {
        val valid =
            KoogTranscriptCodec.fromNative(listOf(Message.User("hello", RequestMetaInfo.Empty)))
                .single()
        expectInvalid {
            KoogTranscriptCodec.toNative(
                listOf(
                    valid.copy(
                        nativeEncoding = NativeMessageEncoding(
                            message = buildJsonObject {})
                    )
                )
            )
        }
        expectInvalid {
            KoogTranscriptCodec.toNative(
                listOf(
                    valid.copy(
                        nativeEncoding = valid.nativeEncoding!!.copy(
                            version = 99
                        )
                    )
                )
            )
        }
        val unknown =
            JsonObject(valid.nativeEncoding!!.message + ("future" to JsonPrimitive("must not vanish")))
        expectInvalid {
            KoogTranscriptCodec.toNative(
                listOf(
                    valid.copy(
                        nativeEncoding = NativeMessageEncoding(
                            message = unknown
                        )
                    )
                )
            )
        }
    }

    @Test
    fun legacyMessagesAndOpaqueReasoningMigrateWithoutLosingFields() {
        val items = listOf(
            json.parseToJsonElement("""{"type":"thinking","thinking":"reason","signature":"signed"}""").jsonObject,
            json.parseToJsonElement("""{"type":"redacted_thinking","data":"redacted"}""").jsonObject,
            json.parseToJsonElement("""{"type":"reasoning","id":"rs_1","encrypted_content":"encrypted","summary":[{"type":"summary_text","text":"summary"}]}""").jsonObject,
        )
        val legacy = listOf(
            ChatMessage(MessageRole.SYSTEM, "system"), ChatMessage(MessageRole.USER, "user"),
            ChatMessage(
                MessageRole.ASSISTANT,
                toolCalls = listOf(ToolCall("id", "tool", "{}")),
                protocolItems = items
            ),
            ChatMessage(MessageRole.TOOL, "result", toolCallId = "id")
        )
        val restored = KoogTranscriptCodec.fromNative(KoogTranscriptCodec.toNative(legacy))
        assertEquals(legacy, restored.map { it.copy(nativeEncoding = null) })
        val native = KoogTranscriptCodec.toNative(restored)
        assertEquals(
            "reareye:anthropic:redacted:redacted",
            (native[2].parts[1] as MessagePart.Reasoning).encrypted
        )
        assertEquals("encrypted", (native[2].parts[2] as MessagePart.Reasoning).encrypted)
        assertEquals(
            native,
            KoogTranscriptCodec.toNative(json.decodeFromString(json.encodeToString(restored)))
        )
    }

    private fun expectInvalid(block: () -> Unit) {
        try {
            block(); fail("Expected invalid native encoding")
        } catch (_: IllegalArgumentException) {
        }
    }
}
