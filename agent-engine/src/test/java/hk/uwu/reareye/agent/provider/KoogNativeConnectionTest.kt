package hk.uwu.reareye.agent.provider

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class KoogNativeConnectionTest {
    private fun response(vararg data: String) =
        MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
            .body(data.joinToString("") { "data: ${nativeFixture(it)}\n\n" }).build()

    // Intentionally bypass nativeFixture: it fills in exactly the two omitted fields under test.
    private fun responsesLifecycle(
        terminal: String = "completed",
        metadata: String = "",
        extension: String? = null,
        terminalUsage: Boolean = true
    ): MockResponse {
        fun lifecycle(type: String, sequence: Int, status: String, extra: String = "") =
            """{"type":"response.$type","sequence_number":$sequence,"response":{"id":"resp_1","created_at":1,"model":"test","output":[],"status":"$status"$metadata$extra}}"""

        val events = listOf(
            lifecycle("created", 0, "in_progress"),
            lifecycle("in_progress", 1, "in_progress"),
            *listOfNotNull(extension).toTypedArray(),
            """{"type":"response.output_text.delta","sequence_number":2,"item_id":"msg_1","output_index":0,"content_index":0,"delta":"answer"}""",
            """{"type":"response.output_item.done","sequence_number":3,"output_index":0,"item":{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"answer","annotations":[]}]}}""",
            lifecycle(
                terminal,
                4,
                terminal,
                (if (terminal == "incomplete") ",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}" else "") +
                        (if (terminalUsage) """, "usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12,"input_tokens_details":{"cached_tokens":3},"output_tokens_details":{"reasoning_tokens":0}}""" else "")
            )
        )
        return MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
            .body(events.joinToString("") { "data: $it\n\n" }).build()
    }

    @Test
    fun `responses omitted metadata works across lifecycle for streaming and execute model test`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                for (execute in listOf(false, true)) for (terminal in listOf(
                    "completed",
                    "incomplete"
                )) {
                    server.enqueue(responsesLifecycle(terminal))
                    val config = ProviderConfig(
                        server.url("/v1").toString(),
                        model = "test",
                        protocol = Protocol.RESPONSES
                    )
                    ProviderFactory.connect(OpenAiCompatibleProvider.defaultClient(), config)
                        .use { connection ->
                            val prompt = Prompt(
                                listOf(
                                    Message.User(
                                        "hi",
                                        ai.koog.prompt.message.RequestMetaInfo.Empty
                                    )
                                ), "test-model", connection.params
                            )
                            val assistant = if (execute) connection.executor.execute(
                                prompt,
                                connection.model
                            ) else {
                                val frames =
                                    connection.executor.executeStreaming(prompt, connection.model)
                                        .toList()
                                val end = frames.filterIsInstance<StreamFrame.End>().single()
                                assertEquals(
                                    if (terminal == "incomplete") "max_output_tokens" else "completed",
                                    end.finishReason
                                )
                                assertEquals(10, end.metaInfo.inputTokensCount)
                                assertEquals(2, end.metaInfo.outputTokensCount)
                                assertEquals(
                                    3,
                                    end.metaInfo.metadata!!["usage"]!!.jsonObject["input_tokens_details"]!!.jsonObject["cached_tokens"]!!.jsonPrimitive.int
                                )
                                frames.toMessageResponse()
                            }
                            assertEquals(
                                "answer",
                                assistant.parts.filterIsInstance<MessagePart.Text>().single().text
                            )
                        }
                    val request = server.takeRequest()
                    assertEquals("/v1/responses", request.url.encodedPath)
                    assertTrue(Json.parseToJsonElement(request.body!!.utf8()).jsonObject["stream"]!!.jsonPrimitive.boolean)
                }
            }
        }

    @Test
    fun `responses explicit metadata survives defaults and native HTTP decoding`() = runBlocking {
        val text =
            """{"format":{"type":"json_schema","name":"answer","schema":{"type":"object"},"strict":false},"verbosity":"low"}"""
        for (parallel in listOf(false, true)) {
            val original =
                Json.parseToJsonElement("""{"parallel_tool_calls":$parallel,"text":$text}""").jsonObject
            assertSame(original, responsesMetadataDefaults(original))
            assertEquals(
                original,
                responsesMetadataDefaults(JsonObject(original - "text")) + ("text" to original.getValue(
                    "text"
                ))
            )
            assertEquals(
                original.getValue("text"),
                responsesMetadataDefaults(JsonObject(original - "parallel_tool_calls"))["text"]
            )
            MockWebServer().use { server ->
                server.start()
                server.enqueue(responsesLifecycle(metadata = """, "parallel_tool_calls":$parallel,"text":$text"""))
                val provider = OpenAiResponsesProvider(
                    OpenAiCompatibleProvider.defaultClient(),
                    ProviderConfig(
                        server.url("/v1").toString(),
                        model = "test",
                        protocol = Protocol.RESPONSES
                    )
                )
                val events = provider.chat(
                    ChatRequest(
                        "test",
                        listOf(
                            hk.uwu.reareye.agent.model.ChatMessage(
                                hk.uwu.reareye.agent.model.MessageRole.USER,
                                "hi"
                            )
                        )
                    )
                ).toList()
                assertEquals(
                    "answer",
                    events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text })
                assertEquals(ChatEvent.Finished("completed"), events.last())
            }
        }
    }

    @Test
    fun `responses metadata extensions preserve native streaming and execute plus usage`() =
        runBlocking {
            val extension =
                """{"type":"response.metadata","sequence_number":2,"response_id":"resp_1","metadata":{"route":"proxy"},"usage":{"provider_tokens":7},"response":{"id":"resp_1","model":"test","usage":{"cached_metadata_tokens":9}}}"""
            MockWebServer().use { server ->
                server.start()
                for (execute in listOf(false, true)) {
                    server.enqueue(responsesLifecycle(extension = extension))
                    ProviderFactory.connect(
                        OpenAiCompatibleProvider.defaultClient(),
                        ProviderConfig(
                            server.url("/v1").toString(),
                            model = "test",
                            protocol = Protocol.RESPONSES
                        )
                    ).use { connection ->
                        val prompt = Prompt(
                            listOf(
                                Message.User(
                                    "hi",
                                    ai.koog.prompt.message.RequestMetaInfo.Empty
                                )
                            ), "metadata", connection.params
                        )
                        val assistant = if (execute) connection.executor.execute(
                            prompt,
                            connection.model
                        ) else {
                            val frames =
                                connection.executor.executeStreaming(prompt, connection.model)
                                    .toList()
                            assertEquals(
                                "completed",
                                frames.filterIsInstance<StreamFrame.End>().single().finishReason
                            )
                            frames.toMessageResponse()
                        }
                        assertEquals(
                            "answer",
                            assistant.parts.filterIsInstance<MessagePart.Text>().single().text
                        )
                        val usage = assistant.metaInfo.metadata!!["usage"]!!.jsonObject
                        assertEquals(7, usage["provider_tokens"]!!.jsonPrimitive.int)
                        assertEquals(9, usage["cached_metadata_tokens"]!!.jsonPrimitive.int)
                        assertEquals(10, usage["input_tokens"]!!.jsonPrimitive.int)
                    }
                }
            }
        }

    @Test
    fun `responses metadata-only usage remains visible when completed omits usage`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (nested in listOf(false, true)) {
                val usage =
                    """"usage":{"input_tokens":10,"output_tokens":2,"total_tokens":12,"input_tokens_details":{"cached_tokens":3}}"""
                val extension =
                    if (nested) """{"type":"response.metadata","response":{$usage}}""" else """{"type":"response.metadata",$usage}"""
                server.enqueue(responsesLifecycle(extension = extension, terminalUsage = false))
                val events = OpenAiResponsesProvider(
                    OpenAiCompatibleProvider.defaultClient(),
                    ProviderConfig(
                        server.url("/v1").toString(),
                        model = "test",
                        protocol = Protocol.RESPONSES
                    )
                )
                    .chat(
                        ChatRequest(
                            "test",
                            listOf(
                                hk.uwu.reareye.agent.model.ChatMessage(
                                    hk.uwu.reareye.agent.model.MessageRole.USER,
                                    "hi"
                                )
                            )
                        )
                    ).toList()
                assertEquals(
                    ChatEvent.Usage(10, 2, 3),
                    events.filterIsInstance<ChatEvent.Usage>().single()
                )
                assertEquals(ChatEvent.Finished("completed"), events.last())
            }
        }
    }

    @Test
    fun `responses metadata cannot conceal content errors unknown events or premature EOF`() =
        runBlocking {
            val extensions = listOf(
                """{"type":"response.metadata","delta":"lost text"}""",
                """{"type":"response.metadata","response":{"output":[{"type":"message","content":[]}]}}""",
                """{"type":"response.metadata","error":{"message":"failed"}}""",
                """{"type":"response.metadata","usage":"wrong"}""",
                """{"type":"response.metadata","usage":{"input_tokens":"wrong"}}""",
                """{"type":"response.metadata","usage":{"output_tokens":-1}}""",
                """{"type":"response.metadata","response":null}""",
                """{"type":"response.unknown_extension","metadata":{}}"""
            )
            MockWebServer().use { server ->
                server.start()
                for (extension in extensions + """{"type":"response.metadata","response_id":"resp_1"}""") {
                    // Last case has valid metadata but deliberately no protocol completion marker.
                    server.enqueue(
                        if (extension in extensions) responsesLifecycle(extension = extension) else
                            MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
                                .body("data: $extension\n\n").build()
                    )
                    ProviderFactory.connect(
                        OpenAiCompatibleProvider.defaultClient(),
                        ProviderConfig(
                            server.url("/v1").toString(),
                            model = "test",
                            protocol = Protocol.RESPONSES
                        )
                    ).use { connection ->
                        try {
                            connection.executor.executeStreaming(
                                Prompt(
                                    listOf(
                                        Message.User(
                                            "hi",
                                            ai.koog.prompt.message.RequestMetaInfo.Empty
                                        )
                                    ), "invalid-metadata", connection.params
                                ), connection.model
                            ).toList()
                            fail("Semantic extension or missing completion must not become success: $extension")
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun `responses metadata defaults do not repair explicit invalid values or missing core fields`() =
        runBlocking {
            val badMetadata = listOf(
                """, "parallel_tool_calls":null""", """, "parallel_tool_calls":{}""",
                """, "text":null""", """, "text":"wrong""",
                """, "text":{"format":{"type":"unknown_format"}}"""
            )
            MockWebServer().use { server ->
                server.start()
                for (metadata in badMetadata) {
                    server.enqueue(responsesLifecycle(metadata = metadata))
                    ProviderFactory.connect(
                        OpenAiCompatibleProvider.defaultClient(),
                        ProviderConfig(
                            server.url("/v1").toString(),
                            model = "test",
                            protocol = Protocol.RESPONSES
                        )
                    ).use { connection ->
                        try {
                            connection.executor.executeStreaming(
                                Prompt(
                                    listOf(
                                        Message.User(
                                            "hi",
                                            ai.koog.prompt.message.RequestMetaInfo.Empty
                                        )
                                    ), "invalid", connection.params
                                ), connection.model
                            ).toList()
                            fail("Invalid explicit metadata must not become a success: $metadata")
                        } catch (error: Exception) {
                            assertTrue(generateSequence(error as Throwable?) { it.cause }.any { it is kotlinx.serialization.SerializationException })
                        }
                    }
                }
                server.enqueue(
                    MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
                        .body(
                            """data: {"type":"response.completed","sequence_number":0,"response":{"status":"completed"}}

"""
                        ).build()
                )
                ProviderFactory.connect(
                    OpenAiCompatibleProvider.defaultClient(),
                    ProviderConfig(
                        server.url("/v1").toString(),
                        model = "test",
                        protocol = Protocol.RESPONSES
                    )
                ).use { connection ->
                    try {
                        connection.executor.executeStreaming(
                            Prompt(
                                listOf(
                                    Message.User(
                                        "hi",
                                        ai.koog.prompt.message.RequestMetaInfo.Empty
                                    )
                                ), "invalid", connection.params
                            ), connection.model
                        ).toList()
                        fail("Missing core fields must still fail")
                    } catch (error: Exception) {
                        assertTrue(generateSequence(error as Throwable?) { it.cause }.any { it is kotlinx.serialization.MissingFieldException })
                    }
                }
            }
        }

    @Test
    fun `native anthropic signed and redacted reasoning survives serialize replay and close`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val client = OpenAiCompatibleProvider.defaultClient()
                val config = ProviderConfig(
                    server.url("/proxy/v2").toString(),
                    "secret",
                    "custom",
                    protocol = Protocol.ANTHROPIC
                )
                server.enqueue(
                    response(
                        """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":0,"cache_read_input_tokens":20,"cache_creation_input_tokens":30}}}""",
                        """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"private"}}""",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig-a"}}""",
                        """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"-b"}}""",
                        """{"type":"content_block_stop","index":0}""",
                        """{"type":"content_block_start","index":1,"content_block":{"type":"redacted_thinking","data":"opaque"}}""",
                        """{"type":"content_block_stop","index":1}""",
                        """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":"answer"}}""",
                        """{"type":"content_block_stop","index":2}""",
                        """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":8}}""",
                        """{"type":"message_stop"}""",
                    )
                )
                val history =
                    listOf(Message.User("hi", ai.koog.prompt.message.RequestMetaInfo.Empty))
                val connection = ProviderFactory.connect(client, config)
                val frames = connection.executor.executeStreaming(
                    Prompt(history, "test", connection.params),
                    connection.model
                ).toList()
                val assistant = frames.toMessageResponse()
                val reasoning = assistant.parts.filterIsInstance<MessagePart.Reasoning>()
                assertEquals(
                    listOf("sig-a-b", REDACTED_PREFIX + "opaque"),
                    reasoning.map { it.encrypted })
                assertEquals(listOf("private"), reasoning.first().content)
                assertEquals(
                    "end_turn",
                    frames.filterIsInstance<StreamFrame.End>().single().finishReason
                )
                val metadata = frames.filterIsInstance<StreamFrame.End>()
                    .single().metaInfo.metadata!!["usage"]!!.jsonObject
                assertEquals(20, metadata["cache_read_input_tokens"]!!.jsonPrimitive.int)
                val first = server.takeRequest()
                assertEquals("/proxy/v2/messages", first.url.encodedPath)
                assertEquals("secret", first.headers["x-api-key"])
                assertEquals("2023-06-01", first.headers["anthropic-version"])
                assertFalse(
                    first.headers.names()
                        .any { it.startsWith("X-Reareye-Internal", ignoreCase = true) })
                server.enqueue(
                    response(
                        """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                        """{"type":"message_stop"}"""
                    )
                )
                connection.executor.executeStreaming(
                    Prompt(
                        history + assistant + Message.User(
                            "continue",
                            ai.koog.prompt.message.RequestMetaInfo.Empty
                        ), "replay", connection.params
                    ), connection.model
                ).toList()
                val body = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
                val parts = body["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray
                assertEquals("sig-a-b", parts[0].jsonObject["signature"]!!.jsonPrimitive.content)
                assertEquals(buildJsonObject {
                    put("type", "redacted_thinking"); put(
                    "data",
                    "opaque"
                )
                }, parts[1])
                connection.close(); connection.close()
                assertFalse(client.dispatcher.executorService.isShutdown)
                server.enqueue(MockResponse.Builder().body("still usable").build())
                client.newCall(okhttp3.Request.Builder().url(server.url("/health")).build())
                    .execute().use { assertTrue(it.isSuccessful) }
            }
        }

    @Test
    fun `native responses retains encrypted-only reasoning and distinguishes incomplete`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val config = ProviderConfig(
                    server.url("/v1").toString(),
                    "key",
                    "test",
                    protocol = Protocol.RESPONSES
                )
                ProviderFactory.connect(OpenAiCompatibleProvider.defaultClient(), config)
                    .use { connection ->
                        server.enqueue(
                            response(
                                """{"type":"response.output_item.done","output_index":0,"item":{"type":"reasoning","id":"rs","summary":[],"encrypted_content":"cipher"}}""",
                                """{"type":"response.output_text.delta","output_index":1,"delta":"answer"}""",
                                """{"type":"response.output_item.done","output_index":1,"item":{"type":"message","id":"msg","role":"assistant","status":"completed","content":[{"type":"output_text","text":"answer","annotations":[]}]}}""",
                                """{"type":"response.output_item.done","output_index":2,"item":{"type":"message","id":"ref","role":"assistant","status":"completed","content":[{"type":"refusal","refusal":"cannot"}]}}""",
                                """{"type":"response.completed","response":{"usage":{"input_tokens":100,"output_tokens":2,"input_tokens_details":{"cached_tokens":80}}}}""",
                            )
                        )
                        val prompt = Prompt(
                            listOf(
                                Message.User(
                                    "hi",
                                    ai.koog.prompt.message.RequestMetaInfo.Empty
                                )
                            ), "first", connection.params
                        )
                        val frames =
                            connection.executor.executeStreaming(prompt, connection.model).toList()
                        val reasoning =
                            frames.filterIsInstance<StreamFrame.ReasoningComplete>().single()
                        assertEquals(
                            "cipher",
                            reasoning.encrypted
                        ); assertTrue(reasoning.content.isEmpty())
                        assertEquals(
                            "completed",
                            frames.filterIsInstance<StreamFrame.End>().single().finishReason
                        )
                        val assistant = frames.toMessageResponse()
                        assertEquals(
                            listOf("answer", "cannot"),
                            assistant.parts.filterIsInstance<MessagePart.Text>().map { it.text })
                        val initial = server.takeRequest()
                        assertEquals("Bearer key", initial.headers["Authorization"])
                        val initialBody = Json.parseToJsonElement(initial.body!!.utf8()).jsonObject
                        assertTrue(initialBody["include"]!!.jsonArray.any { it.jsonPrimitive.content == "reasoning.encrypted_content" })
                        server.enqueue(response("""{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":3,"output_tokens":5}}}"""))
                        val incomplete = connection.executor.executeStreaming(
                            prompt.copy(
                                messages = prompt.messages + assistant + Message.User(
                                    "next",
                                    ai.koog.prompt.message.RequestMetaInfo.Empty
                                )
                            ), connection.model
                        ).toList()
                        assertEquals(
                            "max_output_tokens",
                            incomplete.filterIsInstance<StreamFrame.End>().single().finishReason
                        )
                        val replay =
                            Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
                        val item = replay["input"]!!.jsonArray.map { it.jsonObject }
                            .single { it["type"]?.jsonPrimitive?.content == "reasoning" }
                        assertEquals("cipher", item["encrypted_content"]!!.jsonPrimitive.content)
                        val replayText = replay["input"]!!.jsonArray.map { it.jsonObject }
                            .filter { it["type"]?.jsonPrimitive?.content == "message" }
                            .flatMap { it["content"]!!.jsonArray }
                            .mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.content }
                        assertTrue(replayText.containsAll(listOf("answer", "cannot")))
                        assertEquals(false, replay["store"]!!.jsonPrimitive.boolean)
                    }
            }
        }

    @Test
    fun `anthropic text thinking text or tool blocks preserve boundaries and signed replay order`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                for (lastBlock in listOf("text", "tool")) {
                    val events = mutableListOf(
                        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"prefix"}}""",
                        """{"type":"content_block_stop","index":0}""",
                        """{"type":"content_block_start","index":1,"content_block":{"type":"thinking","thinking":"thought","signature":"signed"}}""",
                        """{"type":"content_block_stop","index":1}""",
                    )
                    if (lastBlock == "text") events += listOf(
                        """{"type":"content_block_start","index":2,"content_block":{"type":"text","text":"suffix"}}""",
                        """{"type":"content_block_stop","index":2}""",
                    ) else events += listOf(
                        """{"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"c","name":"read","input":{}}}""",
                        """{"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{}"}}""",
                        """{"type":"content_block_stop","index":2}""",
                    )
                    events += """{"type":"message_delta","delta":{"stop_reason":"${if (lastBlock == "text") "end_turn" else "tool_use"}"}}"""
                    events += """{"type":"message_stop"}"""
                    server.enqueue(response(*events.toTypedArray()))
                    val config = ProviderConfig(
                        server.url("/v1").toString(),
                        "key",
                        "test",
                        protocol = Protocol.ANTHROPIC
                    )
                    ProviderFactory.connect(OpenAiCompatibleProvider.defaultClient(), config)
                        .use { connection ->
                            val tools = listOf(
                                ai.koog.agents.core.tools.ToolDescriptor(
                                    "read",
                                    "read it",
                                    emptyList(),
                                    emptyList()
                                )
                            )
                            val prompt = Prompt(
                                listOf(
                                    Message.User(
                                        "hi",
                                        ai.koog.prompt.message.RequestMetaInfo.Empty
                                    )
                                ), "interleaved", connection.params
                            )
                            val frames = connection.executor.executeStreaming(
                                prompt,
                                connection.model,
                                tools
                            ).toList()
                            assertEquals(
                                listOf(0, 1, 2),
                                frames.filterIsInstance<StreamFrame.CompleteFrame>()
                                    .map { it.index })
                            val assistant = frames.toMessageResponse()
                            assertTrue(assistant.parts[0] is MessagePart.Text)
                            assertEquals(
                                "signed",
                                (assistant.parts[1] as MessagePart.Reasoning).encrypted
                            )
                            if (lastBlock == "text") assertTrue(assistant.parts[2] is MessagePart.Text)
                            else assertTrue(assistant.parts[2] is MessagePart.Tool.Call)
                            server.takeRequest()
                            server.enqueue(
                                response(
                                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"}}""",
                                    """{"type":"message_stop"}"""
                                )
                            )
                            val next = if (lastBlock == "text") Message.User(
                                "continue",
                                ai.koog.prompt.message.RequestMetaInfo.Empty
                            )
                            else Message.User(
                                MessagePart.Tool.Result(
                                    id = "c",
                                    tool = "read",
                                    output = "result"
                                ), ai.koog.prompt.message.RequestMetaInfo.Empty
                            )
                            connection.executor.executeStreaming(
                                prompt.copy(messages = prompt.messages + assistant + next),
                                connection.model,
                                tools
                            ).toList()
                            val wire =
                                Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
                            val blocks =
                                wire["messages"]!!.jsonArray[1].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
                            assertEquals(
                                listOf(
                                    "text",
                                    "thinking",
                                    if (lastBlock == "text") "text" else "tool_use"
                                ), blocks.map { it["type"]!!.jsonPrimitive.content })
                            assertEquals("signed", blocks[1]["signature"]!!.jsonPrimitive.content)
                            assertEquals("prefix", blocks[0]["text"]!!.jsonPrimitive.content)
                        }
                }
            }
        }

    @Test
    fun `native executor cancellation aborts headers and body waits for all protocols`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                for (protocol in Protocol.entries) for (bodyWait in listOf(false, true)) {
                    val started = CountDownLatch(1)
                    val cancelled = CountDownLatch(1)
                    val client = OpenAiCompatibleProvider.defaultClient().newBuilder()
                        .eventListener(object : EventListener() {
                            override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
                                if (!bodyWait) started.countDown()
                            }

                            override fun responseHeadersEnd(
                                call: Call,
                                response: okhttp3.Response
                            ) {
                                if (bodyWait) started.countDown()
                            }

                            override fun canceled(call: Call) {
                                cancelled.countDown()
                            }
                        }).build()
                    server.enqueue(
                        MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
                            .apply {
                                if (bodyWait) bodyDelay(5, TimeUnit.SECONDS) else headersDelay(
                                    5,
                                    TimeUnit.SECONDS
                                )
                            }.body("data: {}\n\n").build()
                    )
                    ProviderFactory.connect(
                        client,
                        ProviderConfig(
                            server.url("/v1").toString(),
                            model = "test",
                            protocol = protocol
                        )
                    ).use { connection ->
                        val job = launch(Dispatchers.Default) {
                            connection.executor.executeStreaming(
                                Prompt(
                                    listOf(
                                        Message.User(
                                            "hi",
                                            ai.koog.prompt.message.RequestMetaInfo.Empty
                                        )
                                    ), "cancel", connection.params
                                ), connection.model
                            ).toList()
                        }
                        assertTrue(
                            "request did not start: $protocol bodyWait=$bodyWait",
                            started.await(3, TimeUnit.SECONDS)
                        )
                        withTimeout(1500) { job.cancelAndJoin() }
                        assertTrue(
                            "Call was not canceled: $protocol bodyWait=$bodyWait",
                            cancelled.await(1, TimeUnit.SECONDS)
                        )
                    }
                    assertFalse(client.dispatcher.executorService.isShutdown)
                }
            }
        }
}
