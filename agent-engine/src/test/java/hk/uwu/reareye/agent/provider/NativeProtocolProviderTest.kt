package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolCall
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeProtocolProviderTest {
    private lateinit var server: MockWebServer
    @Before
    fun setup() {
        server = MockWebServer(); server.start()
    }

    @After
    fun teardown() {
        server.close()
    }

    private val request = ChatRequest("test", listOf(ChatMessage(MessageRole.USER, "hi")))
    private fun provider(protocol: Protocol): LlmProvider {
        val client = OpenAiCompatibleProvider.defaultClient()
        val config =
            ProviderConfig(server.url("/v1").toString(), "secret", "test", protocol = protocol)
        return when (protocol) {
            Protocol.CHAT_COMPLETIONS -> OpenAiCompatibleProvider(client, { config })
            Protocol.RESPONSES -> OpenAiResponsesProvider(client, config)
            Protocol.ANTHROPIC -> AnthropicMessagesProvider(client, config)
        }
    }

    private fun enqueue(vararg events: String) {
        server.enqueue(
            MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
            .body(events.joinToString("") { "data: ${nativeFixture(it)}\n\n" }).build()
        )
    }

    private fun body() = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    @Test
    fun `chat handles multiple tools text usage and finish in same frame`() = runBlocking {
        enqueue(
            """{"choices":[{"delta":{"content":"ok","tool_calls":[{"index":0,"id":"a","function":{"name":"one","arguments":"{}"}},{"index":1,"id":"b","function":{"name":"two","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":6}}}""",
            "[DONE]"
        )
        val events = provider(Protocol.CHAT_COMPLETIONS).chat(request).toList()
        assertEquals(
            listOf("a", "b"),
            events.filterIsInstance<ChatEvent.ToolCallDelta>().map { it.id })
        assertEquals("ok", events.filterIsInstance<ChatEvent.TextDelta>().single().text)
        assertEquals(ChatEvent.Usage(10, 4, 6), events.filterIsInstance<ChatEvent.Usage>().single())
        assertTrue(events.last() is ChatEvent.Finished)
    }

    @Test
    fun `chat trailing usage precedes the single final event and gateways get no optional flags`() =
        runBlocking {
            enqueue(
                """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                """{"choices":null,"usage":{"prompt_tokens":20,"completion_tokens":3,"prompt_cache_hit_tokens":10}}""",
                "[DONE]",
            )
            val events = provider(Protocol.CHAT_COMPLETIONS).chat(request).toList()
            assertEquals(ChatEvent.Usage(20, 3, 10), events[1])
            assertEquals(ChatEvent.Finished("stop"), events.last())
            assertEquals(1, events.filterIsInstance<ChatEvent.Finished>().size)
            val payload = body()
            assertFalse(payload.containsKey("stream_options"))
            assertFalse(payload.containsKey("cache_control"))
        }

    @Test
    fun `native OpenAI uses reasoning compatible output limit while gateways retain max tokens`() =
        runBlocking {
            enqueue("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]")
            val client =
                OpenAiCompatibleProvider.defaultClient().newBuilder().addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder().url(server.url("/v1/chat/completions")).build()
                    )
                }.build()
            ProviderFactory.create(client) {
                ProviderConfig(
                    "https://api.openai.com/v1",
                    model = "test"
                )
            }
                .chat(request.copy(maxOutputTokens = 4096)).toList()
            val native = body()
            assertEquals(4096, native["max_completion_tokens"]!!.jsonPrimitive.int)
            assertFalse(native.containsKey("max_tokens"))
            assertTrue(native["stream_options"]!!.jsonObject["include_usage"]!!.jsonPrimitive.boolean)
            enqueue("""{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""", "[DONE]")
            provider(Protocol.CHAT_COMPLETIONS).chat(request.copy(maxOutputTokens = 4096)).toList()
            val gateway = body()
            assertEquals(4096, gateway["max_tokens"]!!.jsonPrimitive.int)
            assertFalse(gateway.containsKey("max_completion_tokens"))
        }

    @Test
    fun `responses emits native tools reasoning text usage and completes`() = runBlocking {
        enqueue(
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"{\"x\":"}""",
            """{"type":"response.metadata","response_id":"resp_1","metadata":{"route":"proxy"}}""",
            """{"type":"response.function_call_arguments.delta","output_index":1,"delta":"1}"}""",
            """{"type":"response.output_text.delta","output_index":2,"delta":"Hello"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"encrypted"}}""",
            """{"type":"response.completed","response":{"usage":{"input_tokens":100,"output_tokens":12,"input_tokens_details":{"cached_tokens":80}}}}""",
        )
        val events = provider(Protocol.RESPONSES).chat(request).toList()
        val calls = events.filterIsInstance<ChatEvent.ToolCallDelta>()
        assertEquals("call_1", calls.first().id)
        assertEquals("{\"x\":1}", calls.mapNotNull { it.argumentsDelta }.joinToString(""))
        assertTrue(calls.all { it.index == 1 })
        assertEquals(
            "Hello",
            events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(
            "encrypted",
            events.filterIsInstance<ChatEvent.ProtocolItem>()
                .single().item.string("encrypted_content")
        )
        assertEquals(
            ChatEvent.Usage(100, 12, 80),
            events.filterIsInstance<ChatEvent.Usage>().single()
        )
        val recorded = server.takeRequest()
        assertEquals("/v1/responses", recorded.url.encodedPath)
        assertEquals("Bearer secret", recorded.headers["Authorization"])
        assertTrue(events.last() is ChatEvent.Finished)
    }

    @Test
    fun `responses replays native reasoning calls and results without chat fields`() = runBlocking {
        enqueue("""{"type":"response.completed","response":{}}""")
        val reasoning = buildJsonObject {
            put("type", "reasoning"); put(
            "id",
            "rs"
        ); putJsonArray("summary") {}; put("encrypted_content", "opaque")
        }
        provider(Protocol.RESPONSES).chat(
            ChatRequest(
                "test", listOf(
                    ChatMessage(MessageRole.SYSTEM, "stable"),
                    ChatMessage(MessageRole.USER, "read"),
                    ChatMessage(
                        MessageRole.ASSISTANT,
                        toolCalls = listOf(ToolCall("c", "read", "{}")),
                        protocolItems = listOf(reasoning)
                    ),
                    ChatMessage(MessageRole.TOOL, "result", toolCallId = "c"),
                ), tools = listOf(ToolSpec("read", "read it", risk = ToolRisk.READ_ONLY))
            )
        ).toList()
        val payload = body()
        assertFalse(payload.containsKey("messages"))
        val input = payload["input"]!!.jsonArray.map { it.jsonObject }
        assertEquals(reasoning, input[2])
        assertEquals("function_call", input[3].string("type"))
        assertEquals("function_call_output", input[4].string("type"))
        assertEquals("c", input[4].string("call_id"))
        assertEquals(false, payload["store"]!!.jsonPrimitive.boolean)
        assertEquals(
            false,
            payload["tools"]!!.jsonArray[0].jsonObject["strict"]!!.jsonPrimitive.boolean
        )
    }

    @Test
    fun `anthropic accumulates signed thinking tools and cumulative usage`() = runBlocking {
        enqueue(
            """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":20,"cache_creation_input_tokens":30}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"think"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"c","name":"read","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":8}}""",
            """{"type":"message_stop"}""",
        )
        val events = provider(Protocol.ANTHROPIC).chat(request).toList()
        // Native executor returns final cumulative usage, including both Anthropic cache categories.
        assertEquals(
            listOf(ChatEvent.Usage(60, 8, 20, 30)),
            events.filterIsInstance<ChatEvent.Usage>()
        )
        assertEquals(
            "signed",
            events.filterIsInstance<ChatEvent.ProtocolItem>().single().item.string("signature")
        )
        assertEquals(
            "{}",
            events.filterIsInstance<ChatEvent.ToolCallDelta>().mapNotNull { it.argumentsDelta }
                .joinToString("")
        )
        assertEquals(ChatEvent.Finished("tool_use"), events.last())
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.url.encodedPath)
        assertEquals("secret", recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        assertNull(recorded.headers["Authorization"])
    }

    @Test
    fun `anthropic merges parallel results and caches stable system prefix`() = runBlocking {
        enqueue(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}"""
        )
        val thinking =
            buildJsonObject { put("type", "thinking"); put("thinking", "t"); put("signature", "s") }
        provider(Protocol.ANTHROPIC).chat(
            ChatRequest(
                "test", listOf(
                    ChatMessage(MessageRole.SYSTEM, "stable"),
                    ChatMessage(MessageRole.USER, "read"),
                    ChatMessage(
                        MessageRole.ASSISTANT,
                        toolCalls = listOf(ToolCall("a", "read"), ToolCall("b", "read")),
                        protocolItems = listOf(thinking)
                    ),
                    ChatMessage(MessageRole.TOOL, "one", toolCallId = "a"),
                    ChatMessage(MessageRole.TOOL, "two", toolCallId = "b"),
                )
            )
        ).toList()
        val payload = body()
        assertEquals(4096, payload.number("max_tokens"))
        assertEquals(
            "ephemeral",
            payload["system"]!!.jsonArray[0].jsonObject.obj("cache_control")?.string("type")
        )
        val messages = payload["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertEquals(thinking, messages[1].jsonObject["content"]!!.jsonArray[0])
        val results = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals(listOf("a", "b"), results.map { it.jsonObject.string("tool_use_id") })
    }

    @Test
    fun `native EOF without completion and explicit errors fail`() = runBlocking {
        for ((protocol, payload) in listOf(
            Protocol.RESPONSES to """{"type":"response.output_text.delta","delta":"partial"}""",
            Protocol.ANTHROPIC to """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            Protocol.RESPONSES to """{"type":"response.failed","response":{"error":{"message":"bad"}}}""",
            Protocol.ANTHROPIC to """{"type":"error","error":{"message":"bad"}}""",
            Protocol.CHAT_COMPLETIONS to """{"error":{"message":"bad"}}""",
        )) {
            enqueue(payload)
            try {
                provider(protocol).chat(request)
                    .toList(); fail("Expected protocol failure: $protocol")
            } catch (_: AgentError.Protocol) {
            }
        }
    }

    @Test
    fun `responses incomplete preserves stop reason and usage`() = runBlocking {
        enqueue("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":5,"output_tokens":10}}}""")
        val events = provider(Protocol.RESPONSES).chat(request).toList()
        assertEquals(ChatEvent.Finished("max_output_tokens"), events.last())
        assertEquals(ChatEvent.Usage(5, 10, 0), events.first())
    }

    @Test
    fun `cancelling each protocol aborts blocked HTTP call promptly`() = runBlocking {
        for (protocol in Protocol.entries) for (blockBody in listOf(false, true)) {
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val client = OpenAiCompatibleProvider.defaultClient().newBuilder()
                .eventListener(object : EventListener() {
                    override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
                        if (!blockBody) started.countDown()
                    }

                    override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
                        if (blockBody) started.countDown()
                    }

                    override fun canceled(call: Call) {
                        cancelled.countDown()
                    }
                }).build()
            server.enqueue(
                MockResponse.Builder().setHeader("Content-Type", "text/event-stream").apply {
                    if (blockBody) bodyDelay(5, TimeUnit.SECONDS) else headersDelay(
                        5,
                        TimeUnit.SECONDS
                    )
                }.body("data: {}\n\n").build()
            )
            val provider = ProviderFactory.create(client) {
                ProviderConfig(
                    server.url("/v1").toString(),
                    protocol = protocol
                )
            }
            val job = launch(Dispatchers.Default) { provider.chat(request).toList() }
            assertTrue(
                "request did not start: $protocol bodyWait=$blockBody",
                started.await(3, TimeUnit.SECONDS)
            )
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(
                "Call was not canceled: $protocol bodyWait=$blockBody",
                cancelled.await(1, TimeUnit.SECONDS)
            )
        }
    }

    @Test
    fun `anthropic capacity reads only max input tokens and uses native auth`() = runBlocking {
        server.enqueue(
            MockResponse.Builder().setHeader("Content-Type", "application/json")
                .body("""{"id":"test","max_input_tokens":200000,"max_tokens":8192}""").build()
        )
        assertEquals(200000, provider(Protocol.ANTHROPIC).modelContextWindow("test"))
        val recorded = server.takeRequest()
        assertEquals("/v1/models/test", recorded.url.encodedPath)
        assertEquals("secret", recorded.headers["x-api-key"])
        server.enqueue(
            MockResponse.Builder().setHeader("Content-Type", "application/json")
                .body("""{"id":"old","max_tokens":8192}""").build()
        )
        assertNull(provider(Protocol.ANTHROPIC).modelContextWindow("old"))
        assertNull(provider(Protocol.RESPONSES).modelContextWindow("test"))
        assertNull(provider(Protocol.CHAT_COMPLETIONS).modelContextWindow("test"))
    }

    @Test
    fun `factory routes each protocol to its independent implementation`() {
        val client = OpenAiCompatibleProvider.defaultClient()
        val config = ProviderConfig(server.url("/v1").toString())
        assertTrue(
            ProviderFactory.createForConfig(
                client,
                config.copy(protocol = Protocol.CHAT_COMPLETIONS)
            ) is OpenAiCompatibleProvider
        )
        assertTrue(
            ProviderFactory.createForConfig(
                client,
                config.copy(protocol = Protocol.RESPONSES)
            ) is OpenAiResponsesProvider
        )
        assertTrue(
            ProviderFactory.createForConfig(
                client,
                config.copy(protocol = Protocol.ANTHROPIC)
            ) is AnthropicMessagesProvider
        )
    }

    @Test
    fun `native clients use only their own tool schemas and token limits`() = runBlocking {
        val nativeRequest = request.copy(
            temperature = 0.5,
            maxOutputTokens = 123,
            tools = listOf(ToolSpec("read", "read it", risk = ToolRisk.READ_ONLY)),
        )
        enqueue("""{"type":"response.completed","response":{}}""")
        provider(Protocol.RESPONSES).chat(nativeRequest).toList()
        val responses = body()
        assertEquals(123, responses.number("max_output_tokens"))
        assertEquals(0.5, responses["temperature"]!!.jsonPrimitive.double, 0.0)
        assertFalse(responses.containsKey("max_tokens"))
        assertFalse(responses.containsKey("messages"))
        val responsesTool = responses["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", responsesTool.string("type"))
        assertTrue(responsesTool.containsKey("parameters"))
        assertFalse(responsesTool["strict"]!!.jsonPrimitive.boolean)
        assertFalse(responsesTool.containsKey("input_schema"))

        enqueue(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}"""
        )
        provider(Protocol.ANTHROPIC).chat(nativeRequest).toList()
        val anthropic = body()
        assertEquals(123, anthropic.number("max_tokens"))
        assertEquals(0.5, anthropic["temperature"]!!.jsonPrimitive.double, 0.0)
        assertFalse(anthropic.containsKey("max_output_tokens"))
        assertFalse(anthropic.containsKey("input"))
        assertFalse(anthropic.containsKey("store"))
        val anthropicTool = anthropic["tools"]!!.jsonArray.single().jsonObject
        assertTrue(anthropicTool.containsKey("input_schema"))
        assertFalse(anthropicTool.containsKey("parameters"))
        assertFalse(anthropicTool.containsKey("strict"))
        assertFalse(anthropicTool.containsKey("type"))
    }

    @Test
    fun `native clients reject foreign protocols and malformed streamed payloads`() = runBlocking {
        for (protocol in listOf(Protocol.RESPONSES, Protocol.ANTHROPIC)) {
            val foreign = if (protocol == Protocol.RESPONSES) """{"type":"message_stop"}""" else
                """{"type":"response.completed","response":{}}"""
            for (payload in listOf("not-json", "[]", "null", foreign)) {
                enqueue(payload)
                try {
                    provider(protocol).chat(request)
                        .toList(); fail("Expected invalid native protocol failure")
                } catch (_: AgentError.Protocol) {
                }
            }
        }
    }

    @Test
    fun `independent native providers retain model listing and native authentication`() =
        runBlocking {
            for (protocol in listOf(Protocol.RESPONSES, Protocol.ANTHROPIC)) {
                val models = if (protocol == Protocol.ANTHROPIC)
                    """{"data":[{"id":"test","created_at":"2026-01-01T00:00:00Z","display_name":"Test","type":"model"}],"first_id":"test","last_id":"test","has_more":false}"""
                else """{"object":"list","data":[{"id":"test","object":"model","created":1,"owned_by":"test"}]}"""
                server.enqueue(
                    MockResponse.Builder().setHeader("Content-Type", "application/json")
                        .body(models).build()
                )
                assertEquals(listOf("test"), provider(protocol).listModels())
                val recorded = server.takeRequest()
                assertEquals("/v1/models", recorded.url.encodedPath)
                if (protocol == Protocol.ANTHROPIC) {
                    assertEquals("secret", recorded.headers["x-api-key"])
                    assertEquals("2023-06-01", recorded.headers["anthropic-version"])
                    assertNull(recorded.headers["Authorization"])
                } else {
                    assertEquals("Bearer secret", recorded.headers["Authorization"])
                    assertNull(recorded.headers["x-api-key"])
                }
            }
        }

    @Test
    fun `factory observes protocol configuration changes`() = runBlocking {
        var config = ProviderConfig(server.url("/v1").toString())
        val provider = ProviderFactory.create { config }
        enqueue("[DONE]")
        provider.chat(request).toList()
        assertEquals("/v1/chat/completions", server.takeRequest().url.encodedPath)
        config = config.copy(protocol = Protocol.ANTHROPIC)
        enqueue(
            """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
            """{"type":"message_stop"}"""
        )
        provider.chat(request).toList()
        assertEquals("/v1/messages", server.takeRequest().url.encodedPath)
        config = config.copy(protocol = Protocol.RESPONSES)
        enqueue("""{"type":"response.completed","response":{}}""")
        provider.chat(request).toList()
        assertEquals("/v1/responses", server.takeRequest().url.encodedPath)
    }
}
