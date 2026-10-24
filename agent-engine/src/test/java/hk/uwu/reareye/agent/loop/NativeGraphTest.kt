package hk.uwu.reareye.agent.loop

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.context.ContextBudgetDecision
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Direct native frames exercise the real graph, independent of legacy regression notation. */
class NativeGraphTest {
    private val config =
        AgentConfig(provider = ProviderConfig(model = "fixture"), systemPrompt = "system")
    private val history = listOf(ChatMessage(MessageRole.USER, "goal"))

    private class NativeExecutor(
        private val batches: List<List<StreamFrame>> = listOf(
            listOf(
                StreamFrame.TextComplete("done"),
                StreamFrame.End("stop")
            )
        ),
        private val summary: suspend (Prompt, List<ToolDescriptor>) -> Message.Assistant = { _, _ ->
            Message.Assistant(
                listOf(MessagePart.Text("Whole history summary")),
                ResponseMetaInfo.Empty,
                "stop"
            )
        },
    ) : FixtureKoogExecutor() {
        val streams = mutableListOf<Prompt>()
        val summaries = mutableListOf<Pair<Prompt, List<ToolDescriptor>>>()
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>) =
            flow {
                val index = streams.size
                streams += prompt
                batches.getOrElse(index) { error("Unexpected model request") }.forEach { emit(it) }
            }

        override suspend fun execute(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>
        ): Message.Assistant {
            summaries += prompt to tools
            return summary(prompt, tools)
        }
    }

    private class Tools(private val output: String = "done", private val before: () -> Unit = {}) :
        ToolEnvironment {
        var executions = 0
        override fun availableTools() = listOf(ToolSpec("test", "test", risk = ToolRisk.READ_ONLY))
        override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
            before()
            executions++
            return ToolResult.ok(output)
        }
    }

    private fun tool(id: String = "call") = listOf(
        StreamFrame.ToolCallComplete(id, "test", "{}", 0), StreamFrame.End("tool_calls"),
    )

    @Test
    fun `out of order completion frames follow native index in history and tool execution`() =
        runTest {
            val frames = listOf(
                StreamFrame.ToolCallComplete("second", "test", "{}", 3),
                StreamFrame.TextComplete("answer", 1),
                StreamFrame.ToolCallComplete("first", "test", "{}", 2),
                StreamFrame.ReasoningComplete(
                    "reason",
                    listOf("thinking"),
                    encrypted = "signed",
                    index = 0
                ),
                StreamFrame.End("tool_calls"),
            )
            val executor = NativeExecutor(
                listOf(
                    frames,
                    listOf(StreamFrame.TextComplete("done"), StreamFrame.End("stop"))
                )
            )
            val events = AgentLoop(executor.connection(), Tools(), config).run(history).toList()
            val assistant =
                executor.streams[1].messages.filterIsInstance<Message.Assistant>().single()
            assertTrue(assistant.parts[0] is MessagePart.Reasoning)
            assertEquals(MessagePart.Text("answer"), assistant.parts[1])
            assertEquals(
                listOf("first", "second"),
                assistant.parts.filterIsInstance<MessagePart.Tool.Call>().map { it.id })
            assertEquals(
                listOf("first", "second"),
                events.filterIsInstance<AgentEvent.ToolResultEvent>().map { it.id })
        }

    @Test
    fun `null indexed chat text and reasoning never overwrite indexed tool call`() = runTest {
        val frames = listOf(
            StreamFrame.ToolCallComplete("call", "test", "{}", 0),
            StreamFrame.ReasoningComplete("reason", listOf("thinking"), encrypted = "signed"),
            StreamFrame.TextComplete("answer"),
            StreamFrame.End("tool_calls"),
        )
        val executor = NativeExecutor(
            listOf(
                frames,
                listOf(StreamFrame.TextComplete("done"), StreamFrame.End("stop"))
            )
        )
        AgentLoop(executor.connection(), Tools(), config).run(history).toList()
        val assistant = executor.streams[1].messages.filterIsInstance<Message.Assistant>().single()
        assertEquals(3, assistant.parts.size)
        assertTrue(assistant.parts[0] is MessagePart.Reasoning)
        assertEquals(MessagePart.Text("answer"), assistant.parts[1])
        assertEquals("call", (assistant.parts[2] as MessagePart.Tool.Call).id)
    }

    @Test
    fun `signed and encrypted native reasoning survives subsequent tool request`() = runTest {
        val reasoning = StreamFrame.ReasoningComplete(
            "reasoning-id",
            listOf("internal"),
            listOf("summary"),
            "encrypted-signature",
            0
        )
        val executor = NativeExecutor(
            listOf(
                listOf(reasoning) + tool(),
                listOf(StreamFrame.TextComplete("done"), StreamFrame.End("stop"))
            )
        )
        val events = AgentLoop(executor.connection(), Tools(), config).run(history).toList()
        val restored = executor.streams[1].messages.filterIsInstance<Message.Assistant>().single()
        val part = restored.parts.filterIsInstance<MessagePart.Reasoning>().single()
        assertEquals("encrypted-signature", part.encrypted)
        assertEquals("reasoning-id", part.id)
        val final = events.filterIsInstance<AgentEvent.TurnFinished>().single()
        assertEquals(
            part,
            KoogTranscriptCodec.toNative(final.fullTranscript).filterIsInstance<Message.Assistant>()
                .first().parts.filterIsInstance<MessagePart.Reasoning>().single()
        )
    }

    @Test
    fun `complete only frames reach UI once and delta call IDs remain associated`() = runTest {
        val executor = NativeExecutor(
            listOf(
                listOf(
                    StreamFrame.TextDelta("a", 0),
                    StreamFrame.TextComplete("abc", 0),
                    StreamFrame.ReasoningComplete(
                        "reason",
                        listOf("thinking"),
                        encrypted = "signed",
                        index = 1
                    ),
                    StreamFrame.ToolCallDelta("call", "test", "{", 2),
                    StreamFrame.ToolCallDelta(null, null, "}", 2),
                    StreamFrame.ToolCallComplete("call", "test", "{}", 2),
                    StreamFrame.End("tool_calls"),
                ), listOf(StreamFrame.TextComplete("done"), StreamFrame.End("stop"))
            )
        )
        val events = AgentLoop(executor.connection(), Tools(), config).run(history).toList()
        assertEquals(
            "abcdone",
            events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
        assertEquals("thinking", events.filterIsInstance<AgentEvent.ReasoningDelta>().single().text)
        assertTrue(events.filterIsInstance<AgentEvent.ToolCallArguments>().all { it.id == "call" })
    }

    @Test
    fun `empty text completion keeps the streamed answer instead of aborting the turn`() = runTest {
        val executor = NativeExecutor(
            listOf(
                listOf(
                    StreamFrame.TextDelta("你好", 0),
                    StreamFrame.TextDelta("世界", 0),
                    StreamFrame.TextComplete("", 0),
                    StreamFrame.End("stop"),
                )
            )
        )
        val events = AgentLoop(executor.connection(), Tools(), config).run(history).toList()
        assertEquals(
            "你好世界",
            events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is AgentEvent.Failed })
        val final = events.filterIsInstance<AgentEvent.TurnFinished>().single()
        assertEquals(
            MessagePart.Text("你好世界"),
            KoogTranscriptCodec.toNative(final.fullTranscript)
                .filterIsInstance<Message.Assistant>().single().parts.single()
        )
    }

    @Test
    fun `tail-only text completion neither duplicates nor aborts the streamed answer`() = runTest {
        val executor = NativeExecutor(
            listOf(
                listOf(
                    StreamFrame.TextDelta("你好", 0),
                    StreamFrame.TextDelta("世界", 0),
                    StreamFrame.TextComplete("世界", 0),
                    StreamFrame.End("stop"),
                )
            )
        )
        val events = AgentLoop(executor.connection(), Tools(), config).run(history).toList()
        assertEquals(
            "你好世界",
            events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
        assertTrue(events.none { it is AgentEvent.Failed })
        val final = events.filterIsInstance<AgentEvent.TurnFinished>().single()
        assertEquals(
            MessagePart.Text("你好世界"),
            KoogTranscriptCodec.toNative(final.fullTranscript)
                .filterIsInstance<Message.Assistant>().single().parts.single()
        )
    }

    @Test
    fun `text delta without native completion cannot erase durable assistant content`() = runTest {
        val executor = NativeExecutor(
            listOf(
                listOf(
                    StreamFrame.TextDelta("visible but incomplete", 0),
                    StreamFrame.End("stop")
                )
            )
        )
        val events = AgentLoop(executor.connection(), Tools(), config).run(history).toList()
        assertTrue(events.any { it is AgentEvent.TextDelta })
        assertTrue(events.any { it is AgentEvent.Failed })
        assertTrue(events.none { it is AgentEvent.TurnFinished })
        assertTrue(
            events.filterIsInstance<AgentEvent.Checkpoint>()
                .all { it.messages.none { message -> message.role == MessageRole.ASSISTANT } })
    }

    @Test
    fun `responses output token truncation warns and never executes tools`() = runTest {
        val textOnly = NativeExecutor(
            listOf(
                listOf(
                    StreamFrame.TextComplete("partial"),
                    StreamFrame.End("max_output_tokens")
                )
            )
        )
        val textEvents = AgentLoop(textOnly.connection(), Tools(), config).run(history).toList()
        assertTrue(textEvents.any { it is AgentEvent.Notice })
        assertEquals(
            "partial",
            textEvents.filterIsInstance<AgentEvent.TurnFinished>().single().messages.last().content
        )
        val tools = Tools()
        val truncatedCall = NativeExecutor(
            listOf(
                listOf(
                    StreamFrame.ToolCallComplete("call", "test", "{}", 0),
                    StreamFrame.End("max_output_tokens")
                )
            )
        )
        val toolEvents = AgentLoop(truncatedCall.connection(), tools, config).run(history).toList()
        assertEquals(0, tools.executions)
        assertTrue(toolEvents.any { it is AgentEvent.Failed })
    }

    @Test
    fun `unfinished native call is not executed even with normal End`() = runTest {
        val executor = NativeExecutor(
            listOf(
                listOf(
                    StreamFrame.ToolCallDelta("call", "test", "{", 0),
                    StreamFrame.End("stop")
                )
            )
        )
        val tools = Tools()
        val events = AgentLoop(executor.connection(), tools, config).run(history).toList()
        assertEquals(0, tools.executions)
        assertTrue(events.any { it is AgentEvent.Failed })
    }

    @Test
    fun `intent checkpoint is consumed before side effect and full result is not capped`() =
        runTest {
            val output = "x".repeat(3000)
            val executor = NativeExecutor(
                listOf(
                    tool(),
                    listOf(StreamFrame.TextComplete("done"), StreamFrame.End("stop"))
                )
            )
            var last: AgentEvent.Checkpoint? = null
            val tools = Tools(output) {
                assertTrue(last!!.messages.last().content!!.contains("read was interrupted"))
                assertTrue(last!!.fullTranscript.last().content!!.contains("read was interrupted"))
                assertFalse(last!!.fullTranscript.last().content!!.contains("UNKNOWN"))
            }
            val events = mutableListOf<AgentEvent>()
            AgentLoop(executor.connection(), tools, config.copy(maxToolResultChars = 120)).run(
                history
            ).collect {
                if (it is AgentEvent.Checkpoint) last = it
                events += it
            }
            val done = events.filterIsInstance<AgentEvent.TurnFinished>().single()
            assertTrue(done.messages.single { it.role == MessageRole.TOOL }.content!!.contains("omitted"))
            assertEquals(output, done.fullTranscript.single { it.role == MessageRole.TOOL }.content)
        }

    private val compactHistory
        get() = history + listOf(
            ChatMessage(MessageRole.ASSISTANT, "x".repeat(11000)),
            ChatMessage(MessageRole.USER, "continue"),
        )
    private val compactConfig
        get() = config.copy(
            provider = config.provider.copy(
                contextWindowTokens = 8192,
                maxOutputTokens = 4096
            )
        )

    @Test
    fun `WholeHistory single shot commits only context and restores request params`() = runTest {
        val executor = NativeExecutor()
        val original = compactHistory
        val events = AgentLoop(
            executor.connection(), Tools(), compactConfig,
            checkBudget = { ContextBudgetDecision.Compress },
        ).run(original, original).toList()
        val summary = executor.summaries.single()
        assertTrue(summary.second.isEmpty())
        assertEquals(1024, summary.first.params.maxTokens)
        assertTrue(summary.first.messages.any { message ->
            message.parts.filterIsInstance<MessagePart.Text>().any { it.text == "x".repeat(11000) }
        })
        assertEquals(1, executor.streams.size)
        assertEquals(
            executor.connection().params.maxTokens,
            executor.streams.single().params.maxTokens
        )
        val checkpoint = events.filterIsInstance<AgentEvent.Checkpoint>().first()
        assertEquals(original, checkpoint.fullTranscript)
        assertFalse(checkpoint.messages.any { it.content == "x".repeat(11000) })
        assertTrue(events.any { it is AgentEvent.ContextCompressed })
        assertEquals(
            original,
            events.filterIsInstance<AgentEvent.TurnFinished>()
                .single().fullTranscript.take(original.size)
        )
    }

    @Test
    fun `failed summary cannot replace context or truncate transcript`() = runTest {
        val executor = NativeExecutor(summary = { _, _ -> error("summary failed") })
        val original = compactHistory
        val events = AgentLoop(
            executor.connection(), Tools(), compactConfig,
            checkBudget = { ContextBudgetDecision.Compress },
        ).run(original, original).toList()
        assertTrue(events.any { it is AgentEvent.Failed })
        assertTrue(events.none { it is AgentEvent.Checkpoint || it is AgentEvent.ContextCompressed })
        assertTrue(executor.streams.isEmpty())
        assertEquals(1, executor.summaries.size)
    }

    @Test
    fun `cancelled summary propagates without committing partial compression`() = runTest {
        val executor = NativeExecutor(summary = { _, _ -> throw CancellationException("cancel") })
        val events = mutableListOf<AgentEvent>()
        try {
            AgentLoop(
                executor.connection(),
                Tools(),
                compactConfig,
                checkBudget = { ContextBudgetDecision.Compress })
                .run(compactHistory).collect { events += it }
            fail("Cancellation swallowed")
        } catch (_: CancellationException) {
        }
        assertTrue(events.none { it is AgentEvent.Checkpoint || it is AgentEvent.ContextCompressed })
    }

    @Test
    fun `oversized full history fails without chunk requests or truncation`() = runTest {
        val executor = NativeExecutor()
        val oversized = history + ChatMessage(MessageRole.ASSISTANT, "x".repeat(40000))
        val events = AgentLoop(
            executor.connection(), Tools(), compactConfig,
            checkBudget = { ContextBudgetDecision.Compress },
        ).run(oversized).toList()
        assertTrue(
            events.filterIsInstance<AgentEvent.Failed>().single().error.message!!.contains("未分块")
        )
        assertTrue(executor.summaries.isEmpty())
        assertTrue(executor.streams.isEmpty())
        assertTrue(events.none { it is AgentEvent.Checkpoint })
    }

    @Test
    fun `truncated summary is never accepted`() = runTest {
        val executor = NativeExecutor(summary = { _, _ ->
            Message.Assistant(
                listOf(MessagePart.Text("partial")),
                ResponseMetaInfo.Empty,
                "length"
            )
        })
        val events = AgentLoop(
            executor.connection(), Tools(), compactConfig,
            checkBudget = { ContextBudgetDecision.Compress },
        ).run(compactHistory).toList()
        assertTrue(events.any { it is AgentEvent.Failed })
        assertTrue(events.none { it is AgentEvent.Checkpoint || it is AgentEvent.ContextCompressed })
    }

    @Test
    fun `summary still over budget is not committed`() = runTest {
        val executor = NativeExecutor(summary = { _, _ ->
            Message.Assistant(
                listOf(
                    MessagePart.Text(
                        "x".repeat(20000)
                    )
                ), ResponseMetaInfo.Empty, "stop"
            )
        })
        val events = AgentLoop(
            executor.connection(), Tools(), compactConfig,
            checkBudget = { ContextBudgetDecision.Compress },
        ).run(compactHistory).toList()
        assertTrue(events.any { it is AgentEvent.Failed })
        assertTrue(events.none { it is AgentEvent.Checkpoint || it is AgentEvent.ContextCompressed })
        assertTrue(executor.streams.isEmpty())
    }
}
