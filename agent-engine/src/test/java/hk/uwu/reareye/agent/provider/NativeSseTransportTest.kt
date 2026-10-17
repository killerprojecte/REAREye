package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Real Koog/Ktor/OkHttp transport regressions, not a copied SSE parser. */
class NativeSseTransportTest {
    private fun chunk(text: String) = nativeFixture(buildJsonObject {
        putJsonArray("choices") {
            add(buildJsonObject {
                putJsonObject("delta") {
                    put(
                        "content",
                        text
                    )
                }
            })
        }
    }.toString())

    private val finish get() = "data: ${nativeFixture("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""")}\n\n"
    private fun event(text: String) = "data: ${chunk(text)}\n\n"
    private fun collect(body: String, throttle: Boolean = false): List<ChatEvent> = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().setHeader("Content-Type", "text/event-stream").apply {
                    if (throttle) throttleBody(1, 1, TimeUnit.MILLISECONDS)
                }.body(body).build()
            )
            OpenAiCompatibleProvider(
                OpenAiCompatibleProvider.defaultClient(),
                { ProviderConfig(server.url("/v1").toString(), model = "test") })
                .chat(ChatRequest("test", listOf(ChatMessage(MessageRole.USER, "hi")))).toList()
        }
    }

    private fun text(body: String, throttle: Boolean = false) =
        collect(body, throttle).filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    @Test
    fun `native SSE dispatches separate blank-line terminated events`() {
        assertEquals("ab", text(event("a") + event("b") + finish))
    }

    @Test
    fun `native SSE handles CRLF line endings`() {
        assertEquals("ab", text((event("a") + event("b") + finish).replace("\n", "\r\n")))
    }

    @Test
    fun `native SSE accepts data colon spacing without changing content spaces`() {
        assertEquals(" a", text("data:  ${chunk(" a")}\n\n" + finish))
        assertEquals("b", text("data:${chunk("b")}\n\n" + finish))
    }

    @Test
    fun `native SSE ignores comments and non-data fields`() {
        assertEquals(
            "payload",
            text(": keep-alive\nevent: message\nid: 7\n" + event("payload") + finish)
        )
    }

    @Test
    fun `native SSE supports multiline data in valid JSON`() {
        val data = chunk("multiline").replace("\"choices\":", "\n\"choices\":")
        assertEquals(
            "multiline",
            text(data.lines().joinToString("") { "data: $it\n" } + "\n" + finish))
    }

    @Test
    fun `native SSE ignores blank events and comment heartbeats`() {
        assertEquals("a", text("\n\n: heartbeat\n\n" + event("a") + finish))
    }

    @Test
    fun `native SSE EOF without final marker rejects text and empty replies`() {
        for (body in listOf(event("partial"), "")) {
            try {
                collect(body); fail("EOF must not masquerade as successful completion")
            } catch (_: AgentError.Protocol) {
            }
        }
    }

    @Test
    fun `native SSE preserves UTF8 split into single byte network fragments`() {
        assertEquals("你好\uD83D\uDE42", text(event("你好\uD83D\uDE42") + finish, throttle = true))
    }

    @Test
    fun `native SSE preserves a long payload`() {
        val payload = "x".repeat(50_000)
        assertEquals(payload, text(event(payload) + finish))
    }

    @Test
    fun `native SSE done sentinel completes text but cannot authorize partial tools`() {
        val events = collect(event("ok") + "data: [DONE]\n\n")
        assertEquals(ChatEvent.Finished("stop"), events.last())
        val tool =
            nativeFixture("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c","function":{"name":"read","arguments":"{"}}]}}]}""")
        try {
            collect("data: $tool\n\ndata: [DONE]\n\n"); fail("Partial tools need explicit valid completion")
        } catch (_: AgentError.Protocol) {
        }
    }

    @Test
    fun `native SSE processes many events in one network body`() {
        val expected = (0 until 200).joinToString("") { "e$it" }
        assertEquals(expected, text((0 until 200).joinToString("") { event("e$it") } + finish))
    }

    @Test
    fun `native SSE rejects malformed JSON and truncated final events`() {
        for (body in listOf(
            "data: not-json\n\n",
            "data: []\n\n",
            "data: null\n\n",
            "data: {\"choices\":"
        )) {
            try {
                collect(body); fail("Malformed/truncated payload must fail")
            } catch (_: AgentError.Protocol) {
            }
        }
    }
}
