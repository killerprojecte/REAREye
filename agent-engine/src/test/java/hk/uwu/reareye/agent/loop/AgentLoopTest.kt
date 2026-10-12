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
import org.junit.Assert.assertFalse
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

    /**
     * A reply stopped by the output-token ceiling arrives as a perfectly normal completion
     * (`finish_reason=length`, every delta present), so without an explicit check the text just
     * stops mid-sentence and the user is told nothing at all.
     */
    @Test
    fun `a reply cut off by the token ceiling is reported, not silently accepted`() = runTest {
        val provider = ScriptedProvider(
            listOf(
                listOf(
                    ChatEvent.TextDelta("这是一段被截断的回"),
                    ChatEvent.Finished("length"),
                ),
            ),
        )
        val loop = AgentLoop(provider = provider, tools = RecordingTools(listOf(readTool)), config = config())

        val events = loop.run(history()).toList()

        val notice = events.filterIsInstance<AgentEvent.Notice>().single()
        assertTrue(notice.text.contains("截断"))
        // The partial answer is still kept: it is useful, just incomplete.
        assertTrue(events.filterIsInstance<AgentEvent.TurnFinished>().isNotEmpty())
    }

    @Test
    fun `a normal stop produces no truncation notice`() = runTest {
        val provider = ScriptedProvider(listOf(textTurn("完整回答")))
        val loop = AgentLoop(provider = provider, tools = RecordingTools(listOf(readTool)), config = config())

        val events = loop.run(history()).toList()

        assertTrue(events.none { it is AgentEvent.Notice })
        assertTrue(events.filterIsInstance<AgentEvent.TurnFinished>().isNotEmpty())
    }

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
    fun `a cap of zero disables truncation entirely`() = runTest {
        val huge = "x".repeat(50_000)
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "list_cards", "{}"), textTurn("ok")),
        )
        val loop = AgentLoop(
            provider = provider,
            tools = RecordingTools(listOf(readTool), ToolResult.ok(huge)),
            config = config().copy(maxToolResultChars = 0),
        )

        loop.run(history()).toList()

        val toolMessage = provider.requests[1].messages.first { it.role == MessageRole.TOOL }
        assertEquals(huge, toolMessage.content)
    }

    /**
     * The budget is compared in tokens, so the same character cap must bite at roughly the same
     * token volume in either language. A pure character comparison would treat 8,000 characters of
     * Chinese as equivalent to 8,000 characters of English, when the former is about three times as
     * many tokens.
     */
    @Test
    fun `the cap is applied in tokens, not characters`() = runTest {
        // Each language gets the same character budget; Chinese should be trimmed far more.
        suspend fun retained(text: String): Int {
            val provider = ScriptedProvider(
                listOf(toolTurn("call_1", "list_cards", "{}"), textTurn("ok")),
            )
            val loop = AgentLoop(
                provider = provider,
                tools = RecordingTools(listOf(readTool), ToolResult.ok(text)),
                config = config().copy(maxToolResultChars = 3_000),
            )
            loop.run(history()).toList()
            return provider.requests[1].messages
                .first { it.role == MessageRole.TOOL }.content!!.length
        }

        val ascii = "a".repeat(30_000)
        val chinese = "字".repeat(30_000)
        val keptAscii = retained(ascii)
        val keptChinese = retained(chinese)

        // 3,000 characters is ~1,000 tokens of ASCII but ~3,000 tokens of Chinese, so the ASCII
        // result should retain markedly more characters than the Chinese one.
        assertTrue(
            "ascii kept $keptAscii, chinese kept $keptChinese",
            keptAscii > keptChinese * 2,
        )
    }

    /**
     * When the budget is too small to split, keeping both ends would make the slices overlap and
     * duplicate text. RikkaHub keeps the head only in that case; this mirrors it.
     */
    @Test
    fun `a budget too small to split keeps only the head`() = runTest {
        val body = "abcdefghij".repeat(500)
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "list_cards", "{}"), textTurn("ok")),
        )
        val loop = AgentLoop(
            provider = provider,
            tools = RecordingTools(listOf(readTool), ToolResult.ok(body)),
            config = config().copy(maxToolResultChars = 1),
        )

        loop.run(history()).toList()

        val content = provider.requests[1].messages.first { it.role == MessageRole.TOOL }.content!!
        assertTrue("expected a marker", content.contains("省略"))
        // With a one-character budget there is room for only a token or two of head, so the
        // assertion is on the shape: a non-empty head, then the marker, and no tail at all.
        assertTrue("expected a non-empty head", content.startsWith("abc"))
        assertTrue("expected no tail", content.length < body.length / 2)
        // The head must not have been duplicated into a tail.
        assertFalse("head text should appear once", content.drop(1).contains("abcdefghij"))
    }

    /**
     * Cutting a surrogate pair in half would put an unpaired surrogate into the prompt.
     */
    @Test
    fun `truncation does not split a surrogate pair`() = runTest {
        // Emoji are surrogate pairs; a naive take() at an odd offset lands between the halves.
        val huge = "🎨".repeat(3_000)
        val provider = ScriptedProvider(
            listOf(toolTurn("call_1", "list_cards", "{}"), textTurn("ok")),
        )
        val loop = AgentLoop(
            provider = provider,
            tools = RecordingTools(listOf(readTool), ToolResult.ok(huge)),
            config = config().copy(maxToolResultChars = 1_001),
        )

        loop.run(history()).toList()

        val content = provider.requests[1].messages.first { it.role == MessageRole.TOOL }.content!!
        for (index in content.indices) {
            val char = content[index]
            if (Character.isHighSurrogate(char)) {
                assertTrue(
                    "unpaired high surrogate at $index",
                    index + 1 < content.length && Character.isLowSurrogate(content[index + 1]),
                )
            }
            if (Character.isLowSurrogate(char)) {
                assertTrue(
                    "unpaired low surrogate at $index",
                    index > 0 && Character.isHighSurrogate(content[index - 1]),
                )
            }
        }
    }

    /**
     * The generate-fix-resubmit shape: every attempt carries *different* arguments, so the
     * signature-based repeat guard can never fire. Without a per-tool counter this loop runs to the
     * step ceiling, and each attempt can be a large document.
     */
    @Test
    fun `a tool that keeps failing with different arguments stops the turn`() = runTest {
        val batches = (1..20).map { index ->
            toolTurn("call_$index", "list_cards", """{"attempt":"$index"}""")
        }
        val provider = ScriptedProvider(batches)
        val tools = RecordingTools(
            listOf(readTool),
            ToolResult.error("""{"error":"widget_validation_failed"}"""),
        )
        val loop = AgentLoop(
            provider = provider,
            tools = tools,
            config = config(maxIterations = 20).copy(maxConsecutiveToolFailures = 3),
        )

        val events = loop.run(history()).toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single().error
        assertTrue(failure.message!!.contains("连续失败"))
        // Three attempts allowed, no more.
        assertEquals(3, provider.calls)
    }

    /** A success resets the counter, so intermittent failures never trip the limit. */
    @Test
    fun `a success resets the consecutive failure count`() = runTest {
        val batches = (1..6).map { index ->
            toolTurn("call_$index", "list_cards", """{"attempt":"$index"}""")
        } + listOf(textTurn("完成"))
        val provider = ScriptedProvider(batches)
        var call = 0
        val tools = object : ToolEnvironment {
            override fun availableTools(): List<ToolSpec> = listOf(readTool)
            override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                call++
                // Alternate: fail, succeed, fail, succeed …
                return if (call % 2 == 1) {
                    ToolResult.error("""{"error":"nope"}""")
                } else {
                    ToolResult.ok("""{"ok":true}""")
                }
            }
        }
        val loop = AgentLoop(
            provider = provider,
            tools = tools,
            // Enough steps to run the whole script, so neither ceiling interferes.
            config = config(maxIterations = 10).copy(maxConsecutiveToolFailures = 2),
        )

        val events = loop.run(history()).toList()

        // Failures never happen twice in a row, so the guard must not fire.
        assertTrue(
            "failure guard must not fire, got " +
                "${events.filterIsInstance<AgentEvent.Failed>().map { it.error.message }}",
            events.none { it is AgentEvent.Failed },
        )
        assertTrue(events.filterIsInstance<AgentEvent.TurnFinished>().isNotEmpty())
        assertEquals(6, call)
    }

    @Test
    fun `the wall-clock budget is checked before spending a model call`() = runTest {
        // Distinct arguments each step, so the repeat guard never fires, and the step ceiling is far
        // away, so only the clock can stop it. The budget must be negative rather than zero:
        // elapsed time starts at 0 and the check is `elapsed > budget`, so 0 would never trip and
        // the loop would silently run to the step ceiling instead.
        //
        // Only the already-expired case is asserted here. The budget reads the real clock, so a
        // test cannot advance it without actually sleeping; the positive-budget path is covered by
        // the step-ceiling and loop-guard tests, which exercise the same iteration boundary.
        val batches = List(50) { index -> toolTurn("call_$index", "list_cards", """{"scope":"$index"}""") }
        val provider = ScriptedProvider(batches)
        val loop = AgentLoop(
            provider = provider,
            tools = RecordingTools(listOf(readTool)),
            config = config(maxIterations = 50).copy(turnBudgetMs = -1L),
        )

        val events = loop.run(history()).toList()

        val failure = events.filterIsInstance<AgentEvent.Failed>().single().error
        assertTrue(failure.message!!.contains("时间上限"))
        // The budget is checked before each model call, so an already-expired budget must not spend
        // one.
        assertEquals(0, provider.calls)
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
