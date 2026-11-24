package hk.uwu.reareye.agent.provider

import ai.koog.http.client.KoogHttpClientException
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.loop.AgentEvent
import hk.uwu.reareye.agent.loop.AgentLoop
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.MissingFieldException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
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
    private fun collect(
        body: String,
        throttle: Boolean = false,
        official: Boolean = false,
        status: Int = 200,
        apiKey: String = ""
    ): List<ChatEvent> = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder().code(status)
                    .setHeader("Content-Type", if (status == 200) "text/event-stream" else "application/json")
                    .apply { if (throttle) throttleBody(1, 1, TimeUnit.MILLISECONDS) }
                    .body(body).build()
            )
            val client = OpenAiCompatibleProvider.defaultClient().newBuilder().apply {
                if (official) addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url("/v1/chat/completions")).build())
                }
            }.build()
            val baseUrl = if (official) "https://API.OPENAI.COM/v1" else server.url("/v1").toString()
            OpenAiCompatibleProvider(client, { ProviderConfig(baseUrl, apiKey, model = "test") })
                .chat(ChatRequest("test", listOf(ChatMessage(MessageRole.USER, "hi")))).toList()
        }
    }

    private fun text(body: String, throttle: Boolean = false) =
        collect(body, throttle).filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }

    // Use the wire payloads verbatim: nativeFixture would add the fields under test.
    private fun rawSse(vararg data: String) = data.joinToString("") { "data: $it\n\n" }

    @Test
    fun `gateway SSE accepts sparse chat chunk metadata on text finish and usage`() {
        for (usageChoices in listOf("[]", "null")) {
            val events = collect(rawSse(
                """{"error":null,"choices":[{"index":0,"delta":{"content":"先"}}]}""",
                """{"id":"chat_gateway","model":"served-model","created":123,"choices":[{"index":0,"delta":{"content":"后"}}]}""",
                """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                """{"choices":$usageChoices,"usage":{"prompt_tokens":20,"completion_tokens":3,"total_tokens":23,"prompt_cache_hit_tokens":10}}""",
                "[DONE]"
            ))
            assertEquals("先后", events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text })
            assertEquals(listOf(ChatEvent.Usage(20, 3, 10), ChatEvent.Finished("stop")), events.takeLast(2))
        }
    }

    @Test
    fun `gateway SSE reports in-band errors even when they also look like completion chunks`() {
        val fakeKey = "local-only-fake-key"
        for (errorFrame in listOf(
            """{"error":{"message":"upstream quota exceeded","code":"quota_exceeded","api_key":"$fakeKey"}}""",
            """{"error":{"message":"upstream quota exceeded","code":"quota_exceeded"},"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            """{"type":"error","message":"upstream quota exceeded","code":"quota_exceeded"}""",
            """{"object":"error","message":"upstream quota exceeded","code":"quota_exceeded"}"""
        )) {
            val error = assertThrows(AgentError.Protocol::class.java) {
                collect(rawSse("""{"choices":[{"index":0,"delta":{"content":"partial"}}]}""", errorFrame, "[DONE]"), apiKey = fakeKey)
            }
            val detail = error.message.orEmpty()
            assertTrue(detail, detail.contains("upstream quota exceeded"))
            assertTrue(detail, detail.contains("quota_exceeded"))
            assertFalse(detail, detail.contains("MissingFieldException"))
            assertFalse(detail, detail.contains(fakeKey))
            val native = providerCauses(error).filterIsInstance<KoogHttpClientException>().single()
            assertEquals(errorFrame, native.errorBody)
            assertEquals(null, native.statusCode)
        }
    }

    @Test
    fun `gateway SSE preserves actual non-success HTTP status and response body`() {
        val body = """{"error":{"message":"rate limited","code":"rate_limit"}}"""
        val error = assertThrows(AgentError.Http::class.java) { collect(body, status = 429) }
        assertEquals(429, error.statusCode)
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("rate limited"))
        assertEquals(body, providerCauses(error).filterIsInstance<KoogHttpClientException>().single().errorBody)
    }

    @Test
    fun `gateway SSE refuses explicit invalid metadata and does not synthesize core fields`() {
        val validChoice = """"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]"""
        for ((field, value) in listOf(
            "id" to "null", "id" to "123", "model" to "null", "model" to "true",
            "created" to "null", "created" to "\"123\"", "created" to "1.5",
            "object" to "null", "object" to "[]"
        )) {
            val prior = """{"id":"actual-id","model":"actual-model","created":123,"object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"partial"}}]}"""
            val error = assertThrows("$field=$value", AgentError.Protocol::class.java) {
                collect(rawSse(prior, """{$validChoice,"$field":$value}""", "[DONE]"))
            }
            assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("$field must be"))
        }
        for (frame in listOf(
            """{}""", """{"choices":{}}""", """{"choices":[]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
            """{"choices":[{"index":0,"finish_reason":"stop"}]}""",
            """{"choices":[{"index":0,"delta":null,"finish_reason":"stop"}]}""",
            """{"choices":[{"index":0,"delta":{},"finish_reason":123}]}""",
            """{"type":"response.completed",$validChoice}""",
            """{"object":"chat.completion",$validChoice}"""
        )) {
            assertThrows(frame, AgentError.Protocol::class.java) { collect(rawSse(frame, "[DONE]")) }
        }
    }

    @Test
    fun `gateway SSE keeps completion checks for sparse replies and tool calls`() {
        val textFrame = """{"choices":[{"index":0,"delta":{"content":"partial"}}]}"""
        val toolStart = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{"}}]}}]}"""
        val toolArguments = """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"}"}}]}}]}"""
        val toolFinish = """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""
        val error = assertThrows(AgentError.Protocol::class.java) { collect(rawSse(textFrame)) }
        assertTrue(error.message.orEmpty(), error.message.orEmpty().contains("without a protocol completion marker"))
        assertThrows(AgentError.Protocol::class.java) { collect(rawSse(textFrame) + "data: {\"choices\":") }
        val partialTool = assertThrows(AgentError.Protocol::class.java) { collect(rawSse(toolStart, "[DONE]")) }
        assertTrue(partialTool.message.orEmpty(), partialTool.message.orEmpty().contains("Tool stream ended without an explicit finish reason"))
        val events = collect(rawSse(toolStart, toolArguments, toolFinish, "[DONE]"))
        val calls = events.filterIsInstance<ChatEvent.ToolCallDelta>()
        assertEquals("call_1", calls.first().id)
        assertEquals("{}", calls.mapNotNull { it.argumentsDelta }.joinToString(""))
        assertEquals(ChatEvent.Finished("tool_calls"), events.last())
    }

    @Test
    fun `native graph surfaces a redacted upstream SSE error instead of executing buffered tools`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val fakeKey = "local-graph-test-key"
            val config = ProviderConfig(server.url("/v1").toString(), fakeKey, model = "test")
            val errorFrame = """{"error":{"message":"upstream quota exceeded","code":"quota_exceeded","api_key":"$fakeKey"}}"""
            server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/event-stream").body(rawSse(
                """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"read","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
                errorFrame,
                "[DONE]"
            )).build())
            var executions = 0
            val tools = object : ToolEnvironment {
                override fun availableTools() = listOf(ToolSpec("read", "read it", risk = ToolRisk.READ_ONLY))
                override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                    executions++
                    return ToolResult.error("Must not run on a failed stream")
                }
            }
            val native = nativeConnection(OpenAiCompatibleProvider.defaultClient(), config).connection
            val events = native.use {
                withTimeout(10_000) {
                    AgentLoop(it, tools, AgentConfig(provider = config, systemPrompt = ""))
                        .run(listOf(ChatMessage(MessageRole.USER, "hi"))).toList()
                }
            }
            val failure = events.filterIsInstance<AgentEvent.Failed>().single().error
            val detail = failure.message.orEmpty()
            assertTrue(detail, failure is AgentError.Protocol)
            assertTrue(detail, detail.contains("upstream quota exceeded") && detail.contains("quota_exceeded"))
            assertFalse(detail, detail.contains(fakeKey))
            assertEquals(0, executions)
            assertTrue(events.none { it is AgentEvent.TurnFinished })
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun `official OpenAI Chat does not synthesize gateway chunk fields`() {
        val sparse = """{"choices":[{"index":0,"delta":{"content":"test"},"finish_reason":"stop"}]}"""
        val error = assertThrows(AgentError.Protocol::class.java) {
            collect(rawSse(sparse, "[DONE]"), official = true)
        }
        assertTrue(providerCauses(error).any { it is MissingFieldException })
        assertEquals(ChatEvent.Finished("stop"), collect(rawSse(nativeFixture(sparse), "[DONE]"), official = true).last())
    }

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
