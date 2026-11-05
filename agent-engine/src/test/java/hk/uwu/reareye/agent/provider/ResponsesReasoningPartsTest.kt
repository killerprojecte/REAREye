package hk.uwu.reareye.agent.provider

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Wire-level regressions: fixtures pass through the real Koog SSE decoder and native mapper. */
class ResponsesReasoningPartsTest {
    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.close()
    }

    private fun config() = ProviderConfig(
        server.url("/v1").toString(), "key", "test", protocol = Protocol.RESPONSES
    )

    private fun connection() =
        nativeConnection(OpenAiCompatibleProvider.defaultClient(), config()).connection

    private fun prompt(connection: KoogConnection) = Prompt(
        listOf(Message.User("hi", RequestMetaInfo.Empty)), "reasoning-parts", connection.params
    )

    private fun enqueue(events: List<String>) {
        server.enqueue(
            MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
                .body(events.joinToString("") { "data: ${nativeFixture(it)}\n\n" }).build()
        )
    }

    private fun body() = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject

    private fun part(
        phase: String,
        text: String,
        output: Int = 0,
        content: Int = 0,
        id: String = "rs_$output",
    ) = buildJsonObject {
        put("type", "response.content_part.$phase")
        put("item_id", id)
        put("output_index", output)
        put("content_index", content)
        put("part", buildJsonObject {
            put("type", "reasoning_text")
            put("text", text)
        })
    }.toString()

    private fun delta(
        text: String,
        output: Int = 0,
        content: Int = 0,
        id: String = "rs_$output",
    ) = buildJsonObject {
        put("type", "response.reasoning_text.delta")
        put("item_id", id)
        put("output_index", output)
        put("content_index", content)
        put("delta", text)
    }.toString()

    private fun reasoning(
        phase: String = "done",
        texts: List<String>? = null,
        output: Int = 0,
        id: String = "rs_$output",
        summary: List<String> = emptyList(),
        encrypted: String? = null,
    ) = buildJsonObject {
        put("type", "response.output_item.$phase")
        put("output_index", output)
        put("item", buildJsonObject {
            put("type", "reasoning")
            put("id", id)
            put("status", if (phase == "added") "in_progress" else "completed")
            put("summary", JsonArray(summary.map {
                buildJsonObject { put("type", "summary_text"); put("text", it) }
            }))
            if (texts != null) put("content", JsonArray(texts.map {
                buildJsonObject { put("type", "reasoning_text"); put("text", it) }
            }))
            if (encrypted != null) put("encrypted_content", encrypted)
        })
    }.toString()

    private fun completed() =
        """{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":4,"input_tokens_details":{"cached_tokens":3}}}}"""

    private fun answer(output: Int = 1) =
        """{"type":"response.output_item.done","output_index":$output,"item":{"type":"message","id":"msg_$output","role":"assistant","status":"completed","content":[{"type":"output_text","text":"answer","annotations":[]}]}}"""

    private suspend fun collect(events: List<String>): List<StreamFrame> {
        enqueue(events)
        return connection().use { connection ->
            withTimeout(10_000) {
                connection.executor.executeStreaming(prompt(connection), connection.model).toList()
            }
        }
    }

    private suspend fun assertRejected(events: List<String>) {
        val error = runCatching { collect(events) }.exceptionOrNull()
        assertNotNull("Invalid reasoning stream was accepted", error)
        assertFalse("A timeout is not proof of protocol validation",
            generateSequence(error) { it.cause }.any { it is TimeoutCancellationException })
    }

    @Test
    fun `snapshots and repeated deltas produce one complete without duplicating visible text`() = runBlocking {
        data class Case(
            val initial: String,
            val deltas: List<String>,
            val snapshot: String,
            val finalTexts: List<String>?,
            val snapshots: Boolean = true,
        )
        val cases = listOf(
            Case("", listOf("ha", "ha"), "haha", listOf("haha")),
            Case("pre", listOf("fix"), "prefix", listOf("prefix")),
            Case("", emptyList(), "snapshot only", null),
            Case("initial snapshot", emptyList(), "initial snapshot", emptyList()),
            Case("", listOf("partial"), "partial suffix", listOf("partial suffix")),
            Case("start", listOf(" middle"), "start middle", listOf("start middle end")),
            Case("", emptyList(), "", listOf("final only"), snapshots = false),
        )
        for (case in cases) {
            val frames = collect(buildList {
                if (case.snapshots) {
                    add(reasoning(phase = "added"))
                    add(part("added", case.initial))
                }
                case.deltas.forEach { add(delta(it)) }
                if (case.snapshots) add(part("done", case.snapshot))
                add(reasoning(texts = case.finalTexts))
                add(answer())
                add(completed())
            })
            val expected = case.finalTexts?.takeIf { it.isNotEmpty() }?.joinToString("") ?: case.snapshot
            assertEquals(expected, frames.filterIsInstance<StreamFrame.ReasoningDelta>()
                .joinToString("") { it.text.orEmpty() })
            val complete = frames.filterIsInstance<StreamFrame.ReasoningComplete>().single()
            assertEquals(listOf(expected), complete.content)
            assertEquals("rs_0", complete.id)
            assertEquals(0, complete.index)
            assertEquals("answer", frames.filterIsInstance<StreamFrame.TextDelta>().single().text)
            assertEquals("answer", frames.filterIsInstance<StreamFrame.TextComplete>().single().text)
            assertTrue(frames.last() is StreamFrame.End)
            assertEquals(1, frames.filterIsInstance<StreamFrame.End>().size)
        }
    }

    @Test
    fun `done snapshot without an added event still retains its text`() = runBlocking {
        val frames = collect(listOf(part("done", "done only"), reasoning(), completed()))
        assertEquals("done only", frames.filterIsInstance<StreamFrame.ReasoningDelta>()
            .joinToString("") { it.text.orEmpty() })
        assertEquals(listOf("done only"), frames.filterIsInstance<StreamFrame.ReasoningComplete>()
            .single().content)
    }

    @Test
    fun `final item fills an unfinished partial text and native done snapshots do not repeat it`() = runBlocking {
        val frames = collect(listOf(
            part("added", "start"), delta(" middle"), reasoning(texts = listOf("start middle end")), completed(),
        ))
        assertEquals("start middle end", frames.filterIsInstance<StreamFrame.ReasoningDelta>()
            .joinToString("") { it.text.orEmpty() })
        assertEquals(listOf("start middle end"), frames.filterIsInstance<StreamFrame.ReasoningComplete>()
            .single().content)

        val nativeDone = buildJsonObject {
            put("type", "response.reasoning_text.done")
            put("item_id", "rs_0")
            put("output_index", 0)
            put("content_index", 0)
            put("text", "haha")
        }.toString()
        val completedFrames = collect(listOf(
            delta("ha"), delta("ha"), nativeDone, part("done", "haha"),
            part("done", "haha"), reasoning(texts = listOf("haha")), completed(),
        ))
        assertEquals("haha", completedFrames.filterIsInstance<StreamFrame.ReasoningDelta>()
            .joinToString("") { it.text.orEmpty() })
        assertEquals(listOf("haha"), completedFrames.filterIsInstance<StreamFrame.ReasoningComplete>()
            .single().content)
    }

    @Test
    fun `ordinary output text boundaries keep native behavior`() = runBlocking {
        val boundaries = listOf("added", "done").map { phase ->
            buildJsonObject {
                put("type", "response.content_part.$phase")
                put("item_id", "msg_1")
                put("output_index", 1)
                put("content_index", 0)
                put("part", buildJsonObject {
                    put("type", "output_text")
                    put("text", if (phase == "added") "" else "answer")
                    put("annotations", JsonArray(emptyList()))
                })
            }.toString()
        }
        val frames = collect(boundaries + answer() + completed())
        assertEquals("answer", frames.filterIsInstance<StreamFrame.TextDelta>().single().text)
        assertEquals("answer", frames.filterIsInstance<StreamFrame.TextComplete>().single().text)
        assertTrue(frames.none { it is StreamFrame.ReasoningDelta || it is StreamFrame.ReasoningComplete })
    }

    @Test
    fun `multiple items and content indices retain separate ordered parts`() = runBlocking {
        val frames = collect(listOf(
            reasoning(phase = "added", output = 0),
            reasoning(phase = "added", output = 1),
            part("added", "first", output = 0, content = 0),
            part("added", "other", output = 1, content = 0),
            delta("!", output = 1),
            part("done", "other!", output = 1),
            part("done", "first", output = 0, content = 0),
            part("added", "", output = 0, content = 1),
            delta("second", output = 0, content = 1),
            part("done", "second", output = 0, content = 1),
            reasoning(texts = listOf("other!"), output = 1),
            reasoning(output = 0),
            completed(),
        ))
        val complete = frames.filterIsInstance<StreamFrame.ReasoningComplete>()
        assertEquals(listOf(0, 1), complete.map { it.index })
        assertEquals(listOf("first", "second"), complete[0].content)
        assertEquals(listOf("other!"), complete[1].content)
        for ((index, expected) in listOf(0 to "firstsecond", 1 to "other!")) {
            assertEquals(expected, frames.filterIsInstance<StreamFrame.ReasoningDelta>()
                .filter { it.index == index }.joinToString("") { it.text.orEmpty() })
        }
    }

    @Test
    fun `native execute keeps boundary snapshots without streaming consumers`() = runBlocking {
        enqueue(listOf(part("added", "saved"), part("done", "saved"), reasoning(), answer(), completed()))
        connection().use { connection ->
            val assistant = withTimeout(10_000) {
                connection.executor.execute(prompt(connection), connection.model)
            }
            assertEquals(listOf("saved"), assistant.parts.filterIsInstance<MessagePart.Reasoning>()
                .single().content)
            assertEquals("answer", assistant.parts.filterIsInstance<MessagePart.Text>().single().text)
        }
    }

    @Test
    fun `native history preserves text summary encrypted state tools and usage`() = runBlocking {
        enqueue(listOf(
            part("added", "think "), delta("twice"), part("done", "think twice"),
            reasoning(summary = listOf("short summary"), encrypted = "cipher"),
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"{}"}""",
            """{"type":"response.output_item.done","output_index":1,"item":{"type":"function_call","id":"fc_1","call_id":"call_1","name":"read","arguments":"{}"}}""",
            answer(2), completed(),
        ))
        connection().use { connection ->
            val prompt = prompt(connection)
            val frames = withTimeout(10_000) {
                connection.executor.executeStreaming(prompt, connection.model).toList()
            }
            val complete = frames.filterIsInstance<StreamFrame.ReasoningComplete>().single()
            assertEquals(listOf("think twice"), complete.content)
            assertEquals(listOf("short summary"), complete.summary)
            assertEquals("cipher", complete.encrypted)
            assertEquals("rs_0", complete.id)
            assertEquals("call_1", frames.filterIsInstance<StreamFrame.ToolCallComplete>().single().id)
            val end = frames.filterIsInstance<StreamFrame.End>().single()
            assertEquals(10, end.metaInfo.inputTokensCount)
            assertEquals(4, end.metaInfo.outputTokensCount)
            assertEquals(14, end.metaInfo.totalTokensCount)
            assertEquals(3, end.metaInfo.metadata!!["usage"]!!.jsonObject["input_tokens_details"]!!
                .jsonObject["cached_tokens"]!!.jsonPrimitive.int)
            body()

            // The main agent persists the full native message, rather than the auxiliary projection.
            val assistant = frames.toMessageResponse()
            val restored = KoogTranscriptCodec.toNative(KoogTranscriptCodec.fromNative(listOf(assistant))).single()
            enqueue(listOf(completed()))
            withTimeout(10_000) {
                connection.executor.executeStreaming(prompt.copy(messages = prompt.messages + restored +
                        Message.User(MessagePart.Tool.Result(id = "call_1", tool = "read", output = "ok"), RequestMetaInfo.Empty) +
                        Message.User("next", RequestMetaInfo.Empty)), connection.model).toList()
            }
            val input = body()["input"]!!.jsonArray.map { it.jsonObject }
            val replay = input.single { it["type"]?.jsonPrimitive?.content == "reasoning" }
            assertEquals("rs_0", replay["id"]!!.jsonPrimitive.content)
            assertEquals("cipher", replay["encrypted_content"]!!.jsonPrimitive.content)
            assertEquals(listOf("think twice"), replay["content"]!!.jsonArray
                .map { it.jsonObject["text"]!!.jsonPrimitive.content })
            assertEquals(listOf("short summary"), replay["summary"]!!.jsonArray
                .map { it.jsonObject["text"]!!.jsonPrimitive.content })
            assertEquals("call_1", input.single { it["type"]?.jsonPrimitive?.content == "function_call" }["call_id"]!!
                .jsonPrimitive.content)
            assertEquals("call_1", input.single { it["type"]?.jsonPrimitive?.content == "function_call_output" }["call_id"]!!
                .jsonPrimitive.content)
        }
    }

    @Test
    fun `auxiliary provider preserves boundary and final-only reasoning in visible output and replay`() = runBlocking {
        for (finalTexts in listOf(null, listOf("aux reasoning"))) {
            val provider = OpenAiResponsesProvider(OpenAiCompatibleProvider.defaultClient(), config())
            val boundaries = if (finalTexts == null)
                listOf(part("added", "aux "), delta("reasoning"), part("done", "aux reasoning"))
            else emptyList()
            enqueue(boundaries + listOf(
                reasoning(texts = finalTexts, summary = listOf("aux summary"), encrypted = "aux cipher"),
                answer(), completed(),
            ))
            val user = ChatMessage(MessageRole.USER, "hi")
            val events = withTimeout(10_000) {
                provider.chat(ChatRequest("test", listOf(user))).toList()
            }
            assertEquals("aux reasoning", events.filterIsInstance<ChatEvent.ReasoningDelta>()
                .joinToString("") { it.text })
            val item = events.filterIsInstance<ChatEvent.ProtocolItem>().single().item
            assertEquals(listOf("aux reasoning"), item["content"]!!.jsonArray
                .map { it.jsonObject["text"]!!.jsonPrimitive.content })
            assertEquals("aux cipher", item["encrypted_content"]!!.jsonPrimitive.content)
            assertEquals(ChatEvent.Usage(10, 4, 3), events.filterIsInstance<ChatEvent.Usage>().single())
            assertTrue(events.last() is ChatEvent.Finished)
            body()

            enqueue(listOf(completed()))
            withTimeout(10_000) {
                provider.chat(ChatRequest("test", listOf(user,
                    ChatMessage(MessageRole.ASSISTANT, "answer", protocolItems = listOf(item)),
                    ChatMessage(MessageRole.USER, "next")))).toList()
            }
            val replay = body()["input"]!!.jsonArray.map { it.jsonObject }
                .single { it["type"]?.jsonPrimitive?.content == "reasoning" }
            assertEquals(item["content"], replay["content"])
            assertEquals(item["summary"], replay["summary"])
            assertEquals(item["encrypted_content"], replay["encrypted_content"])
            assertEquals(item["id"], replay["id"])
        }
    }

    @Test
    fun `conflicting snapshots or final content fail instead of silently replacing reasoning`() = runBlocking {
        val streams = listOf(
            listOf(part("added", "prefix"), delta(" more"), part("done", "different"), reasoning(texts = listOf("different")), completed()),
            listOf(part("added", "saved"), part("done", "saved"), reasoning(texts = listOf("conflict")), completed()),
            listOf(part("done", "saved"), delta(" extra"), reasoning(texts = listOf("saved extra")), completed()),
            listOf(part("added", "saved"), part("done", "saved"), reasoning(id = "other_id", texts = listOf("saved")), completed()),
        )
        for (events in streams) assertRejected(events)
    }

    @Test
    fun `unknown content types events and malformed known parts retain strict failures`() = runBlocking {
        val original = Json.parseToJsonElement(part("added", "saved")).jsonObject
        val invalid = listOf(
            JsonObject(original + ("part" to buildJsonObject { put("type", "future_reasoning"); put("text", "saved") })),
            JsonObject(original + ("part" to buildJsonObject { put("type", "reasoning_text"); put("text", 123) })),
            JsonObject(original + ("part" to buildJsonObject { put("type", "reasoning_text"); put("text", JsonNull) })),
            JsonObject(original + ("part" to buildJsonObject { put("type", "reasoning_text") })),
            JsonObject(original - "item_id"),
            JsonObject(original - "output_index"),
            JsonObject(original + ("output_index" to JsonPrimitive(-1))),
            JsonObject(original + ("content_index" to JsonPrimitive("zero"))),
            JsonObject(original + ("sequence_number" to JsonNull)),
            JsonObject(original + ("type" to JsonPrimitive("response.future_event"))),
        )
        for (event in invalid) assertRejected(listOf(event.toString(), reasoning(texts = listOf("saved")), completed()))
    }

    @Test
    fun `boundary completion cannot replace a final output item or stream completion`() = runBlocking {
        assertRejected(listOf(part("added", "saved"), part("done", "saved"), completed()))
        assertRejected(listOf(part("added", "saved"), part("done", "saved"), reasoning()))
        assertRejected(listOf(part("added", "saved")))
    }
    @Test
    fun `real agent graph persists snapshot reasoning and replays it on the next turn`() = runBlocking {
        val tools = object : hk.uwu.reareye.agent.model.ToolEnvironment {
            override fun availableTools() = emptyList<hk.uwu.reareye.agent.model.ToolSpec>()
            override suspend fun execute(toolName: String, args: JsonObject): hk.uwu.reareye.agent.model.ToolResult =
                error("No tools should be called")
        }
        val agentConfig = hk.uwu.reareye.agent.AgentConfig(provider = config(), systemPrompt = "")
        enqueue(listOf(part("added", "graph "), part("done", "graph snapshot"),
            reasoning(summary = listOf("graph summary"), encrypted = "graph cipher"), answer(), completed()))
        val events = connection().use { connection ->
            withTimeout(10_000) {
                hk.uwu.reareye.agent.loop.AgentLoop(connection, tools, agentConfig)
                    .run(listOf(ChatMessage(MessageRole.USER, "hi"))).toList()
            }
        }
        val final = events.filterIsInstance<hk.uwu.reareye.agent.loop.AgentEvent.TurnFinished>().single()
        assertEquals("graph snapshot", events.filterIsInstance<hk.uwu.reareye.agent.loop.AgentEvent.ReasoningDelta>()
            .joinToString("") { it.text })
        val restored = KoogTranscriptCodec.toNative(final.fullTranscript).filterIsInstance<Message.Assistant>()
            .single().parts.filterIsInstance<MessagePart.Reasoning>().single()
        assertEquals(listOf("graph snapshot"), restored.content)
        assertEquals(listOf("graph summary"), restored.summary)
        assertEquals("graph cipher", restored.encrypted)
        body()
        enqueue(listOf(answer(0), completed()))
        connection().use { connection ->
            withTimeout(10_000) {
                hk.uwu.reareye.agent.loop.AgentLoop(connection, tools, agentConfig)
                    .run(final.fullTranscript + ChatMessage(MessageRole.USER, "next")).toList()
            }
        }
        val replay = body()["input"]!!.jsonArray.map { it.jsonObject }
            .single { it["type"]?.jsonPrimitive?.content == "reasoning" }
        assertEquals("graph snapshot", replay["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("graph cipher", replay["encrypted_content"]!!.jsonPrimitive.content)
    }

}
