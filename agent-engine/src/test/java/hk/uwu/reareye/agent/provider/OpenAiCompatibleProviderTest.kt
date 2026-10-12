package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolCall
import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class OpenAiCompatibleProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun sse(vararg chunks: String): String =
        chunks.joinToString(separator = "") { "data: $it\n\n" } + "data: [DONE]\n\n"

    private fun providerWith(apiKey: String = "sk-test"): OpenAiCompatibleProvider {
        val config = ProviderConfig(
            baseUrl = server.url("/v1").toString(),
            apiKey = apiKey,
            model = "test-model",
        )
        return OpenAiCompatibleProvider(
            client = OpenAiCompatibleProvider.defaultClient(),
            configProvider = { config },
        )
    }

    private fun streamResponse(body: String) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "text/event-stream")
        .body(body)
        .build()

    private fun userMessage() = ChatRequest(
        model = "test-model",
        messages = listOf(ChatMessage(MessageRole.USER, "hi")),
    )

    /** Isolates the chunk decoder from the transport so a failure points at the right layer. */
    @Test
    fun `parses the chunk shapes used by the streaming tests`() {
        val json = OpenAiCompatibleProvider.DEFAULT_JSON
        assertNull(
            parseChunk("""{"choices":[{"delta":{"role":"assistant","content":""}}]}""", json),
        )
        assertEquals(
            ChatEvent.TextDelta("A"),
            parseChunk("""{"choices":[{"delta":{"content":"A"}}]}""", json),
        )
        assertEquals(
            ChatEvent.Finished("stop"),
            parseChunk("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", json),
        )
    }

    @Test
    fun `emits text deltas then finished`() = runTest {
        server.enqueue(
            streamResponse(
                sse(
                    """{"choices":[{"delta":{"role":"assistant","content":""}}]}""",
                    """{"choices":[{"delta":{"content":"AB"}}]}""",
                    """{"choices":[{"delta":{"content":"CD"}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                ),
            ),
        )

        val events = providerWith().chat(userMessage()).toList()

        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        assertEquals("ABCD", text)
        assertEquals("stop", events.filterIsInstance<ChatEvent.Finished>().single().finishReason)
    }

    @Test
    fun `sends model messages and auth header`() = runTest {
        server.enqueue(streamResponse(sse("""{"choices":[{"delta":{"content":"ok"}}]}""")))
        providerWith(apiKey = "sk-secret").chat(userMessage()).toList()

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.url.encodedPath)
        assertEquals("Bearer sk-secret", recorded.headers["Authorization"])

        val body = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("test-model", body["model"]!!.jsonPrimitive.content)
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
        val messages = body["messages"]!!.jsonArray
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    /** Arguments arrive as fragments across chunks and must be concatenated in order. */
    @Test
    fun `accumulates fragmented tool call arguments`() = runTest {
        server.enqueue(
            streamResponse(
                sse(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"create_card","arguments":""}}]}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"title\":"}}]}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"music\"}"}}]}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                ),
            ),
        )

        val deltas = providerWith().chat(userMessage()).toList()
            .filterIsInstance<ChatEvent.ToolCallDelta>()

        assertEquals("call_1", deltas.first().id)
        assertEquals("create_card", deltas.first().nameDelta)
        val args = deltas.mapNotNull { it.argumentsDelta }.joinToString("")
        assertEquals("""{"title":"music"}""", args)
        assertNotNull(Json.parseToJsonElement(args).jsonObject["title"])
    }

    @Test
    fun `keeps parallel tool calls separate by index`() = runTest {
        server.enqueue(
            streamResponse(
                sse(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"t_a","arguments":"{}"}}]}}]}""",
                    """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"t_b","arguments":"{}"}}]}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                ),
            ),
        )

        val deltas = providerWith().chat(userMessage()).toList()
            .filterIsInstance<ChatEvent.ToolCallDelta>()

        assertEquals(listOf(0, 1), deltas.map { it.index })
        assertEquals(listOf("a", "b"), deltas.map { it.id })
    }

    @Test
    fun `serialises tool definitions with a JSON schema`() = runTest {
        server.enqueue(streamResponse(sse("""{"choices":[{"delta":{"content":"ok"}}]}""")))
        val spec = ToolSpec(
            name = "create_card",
            description = "Create a rear-screen card.",
            risk = ToolRisk.MUTATING,
            params = listOf(
                ToolParam("title", "Card title", ToolParam.ParamType.STRING),
                ToolParam("enabled", "Whether enabled", ToolParam.ParamType.BOOLEAN, required = false),
            ),
        )
        providerWith().chat(
            ChatRequest(
                model = "test-model",
                messages = listOf(ChatMessage(MessageRole.USER, "hi")),
                tools = listOf(spec),
            ),
        ).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val function = body["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject
        assertEquals("create_card", function["name"]!!.jsonPrimitive.content)
        val params = function["parameters"]!!.jsonObject
        assertEquals("object", params["type"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            params["properties"]!!.jsonObject["title"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals(
            listOf("title"),
            params["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `maps 401 to an auth error`() = runTest {
        server.enqueue(
            MockResponse.Builder()
                .code(401)
                .setHeader("Content-Type", "application/json")
                .body("""{"error":{"message":"Invalid API key"}}""")
                .build(),
        )
        assertFailsWithAgentError<AgentError.Auth> {
            providerWith().chat(userMessage()).toList()
        }
    }

    @Test
    fun `maps 404 to a hint about the version segment`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("not found").build())
        val error = assertFailsWithAgentError<AgentError.Http> {
            providerWith().chat(userMessage()).toList()
        }
        assertEquals(404, error.statusCode)
        assertTrue(error.message!!.contains("/v1"))
    }

    @Test
    fun `a stream without a finish reason still delivers its text`() = runTest {
        server.enqueue(
            streamResponse("""data: {"choices":[{"delta":{"content":"part"}}]}""" + "\n\n"),
        )
        val events = providerWith().chat(userMessage()).toList()
        assertEquals(
            "part",
            events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text },
        )
        assertTrue(events.none { it is ChatEvent.Finished })
    }

    /**
     * Regression: providers emit trailing usage frames with `"choices": null`. Because JsonNull is
     * an object rather than a Kotlin null, unwrapping it threw and killed the whole stream.
     */
    @Test
    fun `tolerates a null choices field on a trailing usage frame`() {
        val json = OpenAiCompatibleProvider.DEFAULT_JSON
        assertNull(parseChunk("""{"choices":null,"usage":{"total_tokens":5}}""", json))
        assertNull(parseChunk("""{"choices":[]}""", json))
        assertNull(parseChunk("""{"choices":[{"delta":null}]}""", json))
        assertNull(parseChunk("""{"choices":[{"delta":{"content":null}}]}""", json))
        assertNull(parseChunk("""{"choices":[{"delta":{"tool_calls":null}}]}""", json))
        assertNull(parseChunk("""{"error":null}""", json))
        assertNull(parseChunk("""null""", json))
        assertNull(parseChunk("""not json at all""", json))
    }

    @Test
    fun `a stream containing a null choices frame still delivers its text`() = runTest {
        server.enqueue(
            streamResponse(
                sse(
                    """{"choices":[{"delta":{"content":"AB"}}]}""",
                    """{"choices":null,"usage":{"total_tokens":5}}""",
                    """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                ),
            ),
        )

        val events = providerWith().chat(userMessage()).toList()

        assertEquals(
            "AB",
            events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text },
        )
        assertEquals("stop", events.filterIsInstance<ChatEvent.Finished>().single().finishReason)
    }

    @Test
    fun `model listing tolerates an unexpected shape`() {
        val json = OpenAiCompatibleProvider.DEFAULT_JSON
        assertEquals(listOf("a", "b"), parseModelIds("""{"data":[{"id":"b"},{"id":"a"}]}""", json))
        assertTrue(parseModelIds("""{"data":null}""", json).isEmpty())
        assertTrue(parseModelIds("""{"data":[{"noid":1},null]}""", json).isEmpty())
        assertTrue(parseModelIds("""{}""", json).isEmpty())
        assertTrue(parseModelIds("""nonsense""", json).isEmpty())
    }

    /**
     * Reasoning has no standard field name, so all three known spellings must be recognised — and
     * a frame carrying both reasoning and content must not lose the answer.
     */
    @Test
    fun `parses reasoning under each provider spelling`() {
        val json = OpenAiCompatibleProvider.DEFAULT_JSON
        assertEquals(
            ChatEvent.ReasoningDelta("hmm"),
            parseChunk("""{"choices":[{"delta":{"reasoning_content":"hmm"}}]}""", json),
        )
        assertEquals(
            ChatEvent.ReasoningDelta("hmm"),
            parseChunk("""{"choices":[{"delta":{"reasoning":"hmm"}}]}""", json),
        )
        assertEquals(
            ChatEvent.ReasoningDelta("hmm"),
            parseChunk("""{"choices":[{"delta":{"thinking":"hmm"}}]}""", json),
        )
        // Content wins when both appear, so the answer is never dropped for a reasoning fragment.
        assertEquals(
            ChatEvent.TextDelta("answer"),
            parseChunk(
                """{"choices":[{"delta":{"reasoning_content":"hmm","content":"answer"}}]}""",
                json,
            ),
        )
        // An empty reasoning field must not shadow a real content field.
        assertEquals(
            ChatEvent.TextDelta("answer"),
            parseChunk(
                """{"choices":[{"delta":{"reasoning_content":"","content":"answer"}}]}""",
                json,
            ),
        )
    }

    /**
     * A model without reasoning support must behave exactly as before: no reasoning events, and the
     * request body must not gain any thinking parameter — a stray field can make strict gateways
     * reject the call outright.
     */
    @Test
    fun `a non-reasoning model streams plain text and sends no thinking field`() = runTest {
        server.enqueue(
            streamResponse(
                sse(
                    """{"choices":[{"delta":{"content":"普通回答"}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                ),
            ),
        )

        val events = providerWith().chat(userMessage()).toList()

        assertEquals("普通回答", events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is ChatEvent.ReasoningDelta })

        val body = server.takeRequest().body!!.utf8()
        listOf("reasoning", "thinking", "reasoning_content", "reasoning_effort").forEach { field ->
            assertFalse("request body must not mention $field: $body", body.contains(field))
        }
        assertTrue(body.contains(""""model":""""))
    }

    @Test
    fun `streams reasoning then content as separate events`() = runTest {
        server.enqueue(
            streamResponse(
                sse(
                    """{"choices":[{"delta":{"reasoning_content":"让我想想"}}]}""",
                    """{"choices":[{"delta":{"reasoning_content":"…"}}]}""",
                    """{"choices":[{"delta":{"content":"答案是 3"}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                ),
            ),
        )

        val events = providerWith().chat(userMessage()).toList()

        assertEquals(
            "让我想想…",
            events.filterIsInstance<ChatEvent.ReasoningDelta>().joinToString("") { it.text },
        )
        assertEquals(
            "答案是 3",
            events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text },
        )
    }

    /**
     * The outer message ("Software caused connection abort") is useless, so the root cause must
     * reach the user and the address must be shown for a misconfigured endpoint to be obvious.
     */
    @Test
    fun `network errors surface their root cause and the address`() {
        val wrapped = IOException(
            "unexpected end of stream",
            SocketException("Software caused connection abort"),
        )
        val message = wrapped.toNetworkError("https://api.example.com/v1/").message!!

        assertTrue("should name the root cause: $message", message.contains("Software caused connection abort"))
        assertTrue("should identify the exception type: $message", message.contains("SocketException"))
        assertTrue("should show the host: $message", message.contains("https://api.example.com"))
        assertTrue("should not leak the path: $message", !message.contains("/v1"))
    }

    @Test
    fun `unknown host and timeout get their own explanations`() {
        val dns = UnknownHostException("api.example.com").toNetworkError("https://api.example.com/v1")
        assertTrue(dns.message!!.contains("找不到主机"))

        val timeout = SocketTimeoutException("timeout").toNetworkError("https://api.example.com/v1")
        assertTrue(timeout.message!!.contains("超时"))
    }

    @Test
    fun `a mid-stream reset becomes a network error rather than an empty reply`() = runTest {
        // Announce text, then cut the connection without a terminator.
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .body("""data: {"choices":[{"delta":{"content":"半"}}]}""" + "\n\n")
                .build(),
        )
        val events = providerWith().chat(userMessage()).toList()
        assertEquals("半", events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun `blank tool call arguments normalise to an empty object`() {
        assertEquals("{}", ToolCall(id = "x", name = "t").argumentsJson)
        assertEquals(0, ToolCall(id = "x", name = "t", argumentsJson = "").arguments().size)
    }

    private inline fun <reified T : AgentError> assertFailsWithAgentError(block: () -> Unit): T {
        try {
            block()
        } catch (error: AgentError) {
            if (error is T) return error
            fail("expected ${T::class.simpleName} but got ${error::class.simpleName}: ${error.message}")
        }
        fail("expected ${T::class.simpleName} but nothing was thrown")
        throw AssertionError("unreachable")
    }
}
