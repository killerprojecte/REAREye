package hk.uwu.reareye.agent.loop

import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolParam
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.provider.ChatRequest
import hk.uwu.reareye.agent.provider.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopTest {

    /** Replays a scripted list of event batches, one batch per model call. */
    private class ScriptedProvider(
        private val batches: List<List<ChatEvent>>,
    ) : LlmProvider {
        override val id = "scripted"
        var calls = 0
            private set
        val requests = mutableListOf<ChatRequest>()

        override fun chat(request: ChatRequest): Flow<ChatEvent> = flow {
            requests += request
            val batch = batches.getOrElse(calls) { emptyList() }
            calls++
            batch.forEach { emit(it) }
        }
    }

    private class RecordingTools(
        private val specs: List<ToolSpec>,
        private val result: ToolResult = ToolResult.ok("""{"ok":true}"""),
    ) : ToolEnvironment {
        val invoked = mutableListOf<Pair<String, JsonObject>>()

        override fun availableTools(): List<ToolSpec> = specs

        override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
            invoked += toolName to args
            return result
        }
    }

    private fun config(maxIterations: Int = 10) = AgentConfig(
        provider = ProviderConfig(baseUrl = "http://localhost/v1", model = "m"),
        maxIterations = maxIterations,
        systemPrompt = "sys",
    )

    private fun history(userText: String = "帮我看看") = listOf(
        ChatMessage(role = MessageRole.USER, content = userText),
    )

    private fun textTurn(text: String) = listOf(
        ChatEvent.TextDelta(text),
        ChatEvent.Finished("stop"),
    )

    private fun toolTurn(id: String, name: String, args: String) = listOf(
        ChatEvent.ToolCallDelta(index = 0, id = id, nameDelta = name, argumentsDelta = args),
        ChatEvent.Finished("tool_calls"),
    )

    private val readTool = ToolSpec(
        name = "list_cards",
        description = "List cards",
        risk = ToolRisk.READ_ONLY,
        params = listOf(ToolParam("scope", "Scope", ToolParam.ParamType.STRING, required = false)),
    )

    private val mutatingTool = ToolSpec(
        name = "create_card",
        description = "Create a card",
        risk = ToolRisk.MUTATING,
    )

    @Test
    fun `a plain text turn finishes immediately without tools`() = runTest {
        val provider = ScriptedProvider(listOf(textTurn("你好")))
        val loop = AgentLoop(provider, RecordingTools(emptyList()), config())

        val events = loop.run(history()).toList()

        assertEquals(1, provider.calls)
        assertEquals("你好", events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
        val done = events.filterIsInstance<AgentEvent.TurnFinished>().single()
        assertEquals(1, done.iterations)
        // History is user + assistant only: the system prompt is prepended per request, not stored.
        assertEquals(2, done.messages.size)
        assertEquals(MessageRole.USER, done.messages.first().role)
        assertEquals(MessageRole.ASSISTANT, done.messages.last().role)
    }

    @Test
    fun `runs a tool then feeds the result back to the model`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                toolTurn("call_1", "list_cards", """{"scope":"all"}"""),
                textTurn("有两张卡片"),
            ),
        )
        val tools = RecordingTools(listOf(readTool))
        val loop = AgentLoop(provider, tools, config())

        val events = loop.run(history()).toList()

        assertEquals(2, provider.calls)
        assertEquals(1, tools.invoked.size)
        assertEquals("list_cards", tools.invoked.single().first)
        assertEquals("all", tools.invoked.single().second["scope"].toString().trim('"'))

        // The second request must carry the assistant tool_call and the tool result.
        val second = provider.requests[1].messages
        val assistant = second.first { it.role == MessageRole.ASSISTANT }
        assertEquals("call_1", assistant.toolCalls.single().id)
        val toolMessage = second.first { it.role == MessageRole.TOOL }
        assertEquals("call_1", toolMessage.toolCallId)

        val done = events.filterIsInstance<AgentEvent.TurnFinished>().single()
        assertEquals(2, done.iterations)
        assertTrue(events.any { it is AgentEvent.ToolResultEvent })
    }

    @Test
    fun `stops at the iteration ceiling instead of looping forever`() = runTest {
        // Every model call asks for another tool call, so only the ceiling can stop it.
        val forever = List(10) { toolTurn("call_$it", "list_cards", "{}") }
        val provider = ScriptedProvider(forever)
        val loop = AgentLoop(provider, RecordingTools(listOf(readTool)), config(maxIterations = 3))

        val events = loop.run(history()).toList()

        assertTrue(events.any { it is AgentEvent.Failed })
        val failure = events.filterIsInstance<AgentEvent.Failed>().single().error
        assertTrue(failure is AgentError.Protocol)
        assertTrue(failure.message!!.contains("3"))
        assertEquals(3, provider.calls)
    }

    @Test
    fun `a denied mutating tool is reported to the model rather than executed`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                toolTurn("call_1", "create_card", """{"title":"x"}"""),
                textTurn("好的，我不创建了"),
            ),
        )
        val tools = RecordingTools(listOf(mutatingTool))
        val loop = AgentLoop(
            provider = provider,
            tools = tools,
            config = config(),
            approve = { false },
        )

        val events = loop.run(history()).toList()

        assertTrue(tools.invoked.isEmpty())
        val denial = events.filterIsInstance<AgentEvent.ToolResultEvent>().single().result
        assertTrue(denial.isError)
        assertTrue(denial.content.contains("用户拒绝"))

        val toolMessage = provider.requests[1].messages.first { it.role == MessageRole.TOOL }
        assertTrue(toolMessage.content!!.contains("用户拒绝"))
    }

    @Test
    fun `an approved mutating tool runs`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                toolTurn("call_1", "create_card", """{"title":"x"}"""),
                textTurn("已创建"),
            ),
        )
        val tools = RecordingTools(listOf(mutatingTool))
        val loop = AgentLoop(provider, tools, config(), approve = { true })

        loop.run(history()).toList()

        assertEquals(1, tools.invoked.size)
    }

    @Test
    fun `read only tools never consult the approval callback`() = runTest {
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "list_cards", "{}"), textTurn("好了")),
        )
        var approvals = 0
        val loop = AgentLoop(
            provider = provider,
            tools = RecordingTools(listOf(readTool)),
            config = config(),
            approve = { approvals++; true },
        )

        loop.run(history()).toList()

        assertEquals(0, approvals)
    }

    @Test
    fun `an unknown tool is refused without touching the environment`() = runTest {
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "not_a_tool", "{}"), textTurn("抱歉")),
        )
        val tools = RecordingTools(listOf(readTool))
        val loop = AgentLoop(provider, tools, config())

        val events = loop.run(history()).toList()

        assertTrue(tools.invoked.isEmpty())
        val result = events.filterIsInstance<AgentEvent.ToolResultEvent>().single().result
        assertTrue(result.isError)
        // The envelope must name the failure in machine-readable form and list what exists.
        assertTrue(result.content.contains("tool_not_found"))
        assertTrue(result.content.contains("list_cards"))
    }

    @Test
    fun `invalid tool arguments become an error result instead of a crash`() = runTest {
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "list_cards", "{not json"), textTurn("重试失败")),
        )
        val tools = RecordingTools(listOf(readTool))
        val loop = AgentLoop(provider, tools, config())

        val events = loop.run(history()).toList()

        assertTrue(tools.invoked.isEmpty())
        val result = events.filterIsInstance<AgentEvent.ToolResultEvent>().single().result
        assertTrue(result.isError)
        assertTrue(events.any { it is AgentEvent.TurnFinished })
    }

    @Test
    fun `a throwing tool is contained`() = runTest {
        val exploding = object : ToolEnvironment {
            override fun availableTools() = listOf(readTool)
            override suspend fun execute(toolName: String, args: JsonObject): ToolResult =
                throw IllegalStateException("boom")
        }
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "list_cards", "{}"), textTurn("工具坏了")),
        )
        val loop = AgentLoop(provider, exploding, config())

        val events = loop.run(history()).toList()

        val result = events.filterIsInstance<AgentEvent.ToolResultEvent>().single().result
        assertTrue(result.isError)
        assertTrue(result.content.contains("boom"))
        assertTrue(events.any { it is AgentEvent.TurnFinished })
    }

    @Test
    fun `a transport failure ends the turn with that error`() = runTest {
        val failing = object : LlmProvider {
            override val id = "failing"
            override fun chat(request: ChatRequest): Flow<ChatEvent> = flow {
                throw AgentError.Auth("bad key")
            }
        }
        val loop = AgentLoop(failing, RecordingTools(emptyList()), config())

        val events = loop.run(history()).toList()

        val error = events.filterIsInstance<AgentEvent.Failed>().single().error
        assertTrue(error is AgentError.Auth)
    }

    @Test
    fun `an oversized tool result is truncated before entering the context`() = runTest {
        val huge = "x".repeat(5_000)
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "list_cards", "{}"), textTurn("ok")),
        )
        val tools = RecordingTools(listOf(readTool), ToolResult.ok(huge))
        val loop = AgentLoop(
            provider = provider,
            tools = tools,
            config = config().copy(maxToolResultChars = 1_000),
        )

        loop.run(history()).toList()

        val toolMessage = provider.requests[1].messages.first { it.role == MessageRole.TOOL }
        assertTrue(toolMessage.content!!.length < huge.length)
        assertTrue(toolMessage.content!!.contains("省略"))
        // Both ends are kept: the tail is where a command's conclusion lives.
        assertTrue("should preserve the head", toolMessage.content!!.startsWith("xxx"))
        assertTrue("should preserve the tail", toolMessage.content!!.endsWith("xxx"))
    }

    @Test
    fun `the wall-clock budget ends a turn that keeps calling different tools`() = runTest {
        // Distinct arguments each step, so the repeat guard never fires and only the clock can stop it.
        val batches = List(50) { index -> toolTurn("call_$index", "list_cards", """{"scope":"$index"}""") }
        val provider = ScriptedProvider(batches)
        val loop = AgentLoop(
            provider = provider,
            tools = RecordingTools(listOf(readTool)),
            config = config(maxIterations = 50).copy(turnBudgetMs = 0L),
        )

        val events = loop.run(history()).toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single().error
        assertTrue(failure.message!!.contains("时间上限"))
        assertEquals(1, provider.calls)
    }

    @Test
    fun `repeating an identical tool call is treated as a loop`() = runTest {
        // Same tool, same arguments, every step: the repeat guard must end the turn well before
        // the step ceiling, because a repeat cannot yield new information.
        val batches = List(20) { toolTurn("call_$it", "list_cards", "{}") }
        val provider = ScriptedProvider(batches)
        val loop = AgentLoop(
            provider = provider,
            tools = RecordingTools(listOf(readTool)),
            config = config(maxIterations = 20).copy(maxRepeatedToolCalls = 2),
        )

        val events = loop.run(history()).toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single().error
        assertTrue(failure.message!!.contains("重复"))
        assertTrue("should stop early, not at the ceiling", provider.calls < 20)
    }

    @Test
    fun `a heavy tool that exceeds its timeout becomes a tool_timeout envelope`() = runTest {
        val slow = object : ToolEnvironment {
            override fun availableTools() = listOf(readTool)
            override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                kotlinx.coroutines.delay(5_000)
                return ToolResult.ok("""{"never":"reached"}""")
            }
        }
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "list_cards", "{}"), textTurn("超时了")),
        )
        val loop = AgentLoop(
            provider = provider,
            tools = slow,
            config = config().copy(toolTimeoutMs = 50L),
        )

        val events = loop.run(history()).toList()

        val result = events.filterIsInstance<AgentEvent.ToolResultEvent>().single().result
        assertTrue(result.isError)
        assertTrue(result.content.contains("tool_timeout"))
        // The turn still completes and the model gets to react.
        assertTrue(events.any { it is AgentEvent.TurnFinished })
    }

    @Test
    fun `the system prompt is prepended to every request`() = runTest {
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "list_cards", "{}"), textTurn("好了")),
        )
        val loop = AgentLoop(provider, RecordingTools(listOf(readTool)), config())

        loop.run(history()).toList()

        provider.requests.forEach { request ->
            assertEquals(MessageRole.SYSTEM, request.messages.first().role)
            assertEquals("sys", request.messages.first().content)
        }
    }

    @Test
    fun `parallel tool calls in one turn are all executed`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                listOf(
                    ChatEvent.ToolCallDelta(0, "a", "list_cards", "{}"),
                    ChatEvent.ToolCallDelta(1, "b", "list_cards", "{}"),
                    ChatEvent.Finished("tool_calls"),
                ),
                textTurn("两个都查完了"),
            ),
        )
        val tools = RecordingTools(listOf(readTool))
        val loop = AgentLoop(provider, tools, config())

        val events = loop.run(history()).toList()

        assertEquals(2, tools.invoked.size)
        assertEquals(2, events.filterIsInstance<AgentEvent.ToolResultEvent>().size)
        val assistant = provider.requests[1].messages.first { it.role == MessageRole.ASSISTANT }
        assertEquals(listOf("a", "b"), assistant.toolCalls.map { it.id })
    }

    @Test
    fun `a truncated stream without a finish reason fails the turn`() = runTest {
        // Text arrives but the model never signals completion and asks for no tools.
        val provider = ScriptedProvider(listOf(listOf(ChatEvent.TextDelta("半句话"))))
        val loop = AgentLoop(provider, RecordingTools(emptyList()), config())

        val events = loop.run(history()).toList()

        assertTrue(events.any { it is AgentEvent.Failed })
        assertTrue(events.none { it is AgentEvent.TurnFinished })
    }

    @Test
    fun `tool call argument fragments are announced and accumulated`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                listOf(
                    ChatEvent.ToolCallDelta(0, "call_1", "list_cards", """{"sc"""),
                    ChatEvent.ToolCallDelta(0, null, null, """ope":"all"}"""),
                    ChatEvent.Finished("tool_calls"),
                ),
                textTurn("好了"),
            ),
        )
        val tools = RecordingTools(listOf(readTool))
        val loop = AgentLoop(provider, tools, config())

        val events = loop.run(history()).toList()

        val started = events.filterIsInstance<AgentEvent.ToolCallStarted>().single()
        assertEquals("list_cards", started.name)
        val finished = events.filterIsInstance<AgentEvent.ToolCallFinished>().single()
        assertEquals("""{"scope":"all"}""", finished.request.argumentsJson)
        assertEquals("all", tools.invoked.single().second["scope"].toString().trim('"'))
    }

    private fun emptyArgs(): JsonObject = buildJsonObject { put("noop", true) }
}
