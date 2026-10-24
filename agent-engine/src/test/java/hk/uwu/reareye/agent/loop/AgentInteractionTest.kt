package hk.uwu.reareye.agent.loop

import hk.uwu.reareye.agent.AgentConfig
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.context.ContextBudgetDecision
import hk.uwu.reareye.agent.context.ContextBudgetRequest
import hk.uwu.reareye.agent.interaction.ApprovalDecision
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.interaction.AskAnswer
import hk.uwu.reareye.agent.interaction.AskOption
import hk.uwu.reareye.agent.interaction.AskRequest
import hk.uwu.reareye.agent.interaction.AskToolEnvironment
import hk.uwu.reareye.agent.interaction.InteractionPurpose
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.EmptyToolEnvironment
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.agent.provider.ChatEvent
import hk.uwu.reareye.agent.provider.ChatRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AgentInteractionTest {
    private val history = listOf(ChatMessage(MessageRole.USER, "go"))
    private val config =
        AgentConfig(provider = ProviderConfig(model = "test"), systemPrompt = "stable")

    private class Tools(
        val risk: ToolRisk = ToolRisk.READ_ONLY,
        val action: suspend () -> Unit = {}
    ) : ToolEnvironment {
        var executions = 0
        override fun availableTools() = listOf(ToolSpec("test", "test tool", risk = risk))
        override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
            executions++
            action()
            return ToolResult.ok("done")
        }
    }

    private class Provider(private val batches: List<List<ChatEvent>>) : FixtureKoogExecutor() {
        val requests = mutableListOf<ChatRequest>()
        override fun fixture(request: ChatRequest) = flow {
            val index = requests.size
            requests += request
            batches.getOrElse(index) { listOf(ChatEvent.Finished("stop")) }.forEach { emit(it) }
        }
    }

    private fun call(
        name: String = "test",
        args: String = "{}",
        id: String = "one",
        index: Int = 0
    ) =
        ChatEvent.ToolCallDelta(index, id, name, args)

    private fun provider(vararg calls: ChatEvent) =
        Provider(listOf(calls.toList() + ChatEvent.Finished("tool_calls")))

    @Test
    fun `incomplete and truncated calls never run`() = runTest {
        for (finish in listOf(null, "length", "content_filter")) {
            val tools = Tools()
            val batch = listOf(call()) + listOfNotNull(finish?.let { ChatEvent.Finished(it) })
            val events = AgentLoop(Provider(listOf(batch)), tools, config).run(history).toList()
            assertEquals(0, tools.executions)
            assertTrue(events.any { it is AgentEvent.Failed })
        }
    }

    @Test
    fun `approval matrix respects destructive and explicit denials`() = runTest {
        for (mode in ApprovalMode.entries) for (risk in ToolRisk.entries) {
            val tools = Tools(risk)
            var humans = 0
            var models = 0
            val diagnostics = mutableListOf<String>()
            AgentLoop(
                provider(call()).connection(),
                tools,
                config,
                approve = { humans++; true },
                approvalMode = mode,
                autoApprove = { models++; ApprovalDecision.ALLOW },
                approvalDiagnostic = diagnostics::add,
            ).run(history).toList()
            val reason = when (mode) {
                ApprovalMode.ALLOW_ALL -> "ALLOW_ALL"
                ApprovalMode.ASK -> "ASK"
                ApprovalMode.AUTO -> if (risk == ToolRisk.DESTRUCTIVE) "AUTO_DESTRUCTIVE_FORCED_HUMAN" else "AUTO_REVIEWER_RESULT"
            }
            val decision =
                if (mode == ApprovalMode.ASK || mode == ApprovalMode.AUTO && risk == ToolRisk.DESTRUCTIVE) "ASK_USER" else "ALLOW"
            assertTrue(diagnostics.any { it.contains("reason=$reason decision=$decision") })
            assertTrue(diagnostics.all { it.contains("mode=$mode risk=$risk autoApproveReadOnly=${config.autoApproveReadOnly} tool=test callHash=") })
            assertTrue(diagnostics.all { Regex("callHash=[0-9a-f]{12} ").containsMatchIn(it) })
            if (decision == "ASK_USER") {
                assertTrue(diagnostics.any { it.contains("reason=HUMAN_WAIT decision=ASK_USER") })
                assertTrue(diagnostics.any { it.contains("reason=HUMAN_RESULT decision=ALLOW") })
            }
            assertEquals(1, tools.executions)
            assertEquals(
                if (mode == ApprovalMode.ASK || mode == ApprovalMode.AUTO && risk == ToolRisk.DESTRUCTIVE) 1 else 0,
                humans
            )
            assertEquals(
                if (mode == ApprovalMode.AUTO && risk != ToolRisk.DESTRUCTIVE) 1 else 0,
                models
            )
        }
        val denied = Tools()
        AgentLoop(
            provider(call()), denied, config, approve = { error("must not ask") },
            approvalMode = ApprovalMode.AUTO, autoApprove = { ApprovalDecision.DENY }).run(history)
            .toList()
        assertEquals(0, denied.executions)
    }

    @Test
    fun `AUTO asks a human when evaluator fails or requests fallback`() = runTest {
        for (throwError in listOf(false, true)) {
            var human = 0
            val tools = Tools()
            AgentLoop(
                provider(call()).connection(), tools, config, approvalMode = ApprovalMode.AUTO,
                autoApprove = { if (throwError) error("review unavailable") else ApprovalDecision.ASK_USER },
                approve = { human++; true },
            ).run(history).toList()
            assertEquals(1, human)
            assertEquals(1, tools.executions)
        }
    }

    @Test
    fun `legacy diagnostic matrix preserves readonly setting and human decision`() = runTest {
        for (risk in ToolRisk.entries) for (readonly in listOf(false, true)) for (allowed in listOf(
            false,
            true
        )) {
            val tools = Tools(risk)
            val diagnostics = mutableListOf<String>()
            var humans = 0
            val automatic = risk == ToolRisk.READ_ONLY && readonly
            AgentLoop(
                provider(call()).connection(),
                tools,
                config.copy(autoApproveReadOnly = readonly),
                approve = { humans++; allowed },
                autoApprove = { error("legacy must not use reviewer") },
                approvalDiagnostic = diagnostics::add,
            ).run(history).toList()
            assertEquals(if (automatic || allowed) 1 else 0, tools.executions)
            assertEquals(if (automatic) 0 else 1, humans)
            assertTrue(diagnostics.all { it.contains("mode=null risk=$risk autoApproveReadOnly=$readonly") })
            assertTrue(diagnostics.any { it.contains("reason=${if (automatic) "LEGACY_READONLY" else "LEGACY_HUMAN"} decision=${if (automatic) "ALLOW" else "ASK_USER"}") })
            if (!automatic) assertTrue(diagnostics.any { it.contains("reason=HUMAN_RESULT decision=${if (allowed) "ALLOW" else "DENY"}") })
        }
    }

    @Test
    fun `AUTO readonly still reviews and exceptions log no sensitive payload`() = runTest {
        val secret = "secret-api-key-raw-payload"
        for (decision in ApprovalDecision.entries) for (reviewFails in listOf(false, true)) {
            val tools = Tools(ToolRisk.READ_ONLY)
            val diagnostics = mutableListOf<String>()
            var reviews = 0
            var humans = 0
            val events = AgentLoop(
                provider(
                    call(
                        args = """{"token":"$secret"}""",
                        id = "raw-id-$secret"
                    )
                ).connection(),
                tools,
                config.copy(autoApproveReadOnly = true),
                approvalMode = ApprovalMode.AUTO,
                autoApprove = { reviews++; if (reviewFails) throw IllegalStateException(secret) else decision },
                approve = { humans++; throw IllegalArgumentException(secret) },
                approvalDiagnostic = diagnostics::add,
            ).run(listOf(ChatMessage(MessageRole.USER, "prompt-$secret"))).toList()
            val fallback = reviewFails || decision == ApprovalDecision.ASK_USER
            assertEquals(1, reviews)
            assertEquals(if (fallback) 1 else 0, humans)
            assertEquals(
                if (!reviewFails && decision == ApprovalDecision.ALLOW) 1 else 0,
                tools.executions
            )
            assertFalse(events.any { it is AgentEvent.Failed })
            assertTrue(diagnostics.none {
                it.contains(secret) || it.contains("raw-id") || it.contains(
                    "token"
                ) || it.contains("prompt-")
            })
            if (reviewFails) assertTrue(diagnostics.any { it.contains("reason=AUTO_CALLBACK_EXCEPTION decision=ASK_USER exceptionClass=IllegalStateException") })
            else assertTrue(diagnostics.any { it.contains("reason=AUTO_REVIEWER_RESULT decision=$decision") })
            if (fallback) assertTrue(diagnostics.any { it.contains("reason=HUMAN_EXCEPTION decision=DENY exceptionClass=IllegalArgumentException") })
        }
    }

    @Test
    fun `diagnostic failures cannot change approval or tool execution`() = runTest {
        for (mode in listOf(null) + ApprovalMode.entries) for (allow in listOf(false, true)) {
            val tools = Tools(ToolRisk.MUTATING)
            AgentLoop(
                provider(call()).connection(),
                tools,
                config,
                approvalMode = mode,
                autoApprove = { if (allow) ApprovalDecision.ALLOW else ApprovalDecision.DENY },
                approve = { allow },
                approvalDiagnostic = { throw CancellationException("sink failure") },
            ).run(history).toList()
            assertEquals(if (allow || mode == ApprovalMode.ALLOW_ALL) 1 else 0, tools.executions)
        }
    }

    @Test
    fun `approval diagnostic cancellation preserves original exception`() = runTest {
        for (reviewer in listOf(false, true)) {
            val cancelled = CancellationException("sensitive cancellation payload")
            val diagnostics = mutableListOf<String>()
            val tools = Tools(ToolRisk.MUTATING)
            try {
                AgentLoop(
                    provider(call()).connection(), tools, config,
                    approvalMode = if (reviewer) ApprovalMode.AUTO else ApprovalMode.ASK,
                    autoApprove = { throw cancelled }, approve = { throw cancelled },
                    approvalDiagnostic = { diagnostics += it; throw IllegalStateException("sink failure") },
                ).run(history).toList()
                fail("Cancellation was swallowed")
            } catch (actual: CancellationException) {
                assertSame(cancelled, actual)
            }
            assertEquals(0, tools.executions)
            assertTrue(diagnostics.any { it.contains("reason=${if (reviewer) "AUTO_CANCELLED" else "HUMAN_CANCELLED"}") })
            assertTrue(diagnostics.none { it.contains("sensitive") || it.contains("sink failure") })
        }
    }

    @Test
    fun `budget replacement preserves stable system prefix`() = runTest {
        val provider = Provider(listOf(listOf(ChatEvent.Finished("stop"))))
        val replacement = listOf(ChatMessage(MessageRole.USER, "English summary"))
        AgentLoop(
            provider,
            Tools(),
            config,
            checkBudget = { ContextBudgetDecision.Continue(replacement) }).run(history).toList()
        assertEquals(
            listOf(ChatMessage(MessageRole.SYSTEM, "stable")) + replacement,
            provider.requests.single().messages
        )
    }

    @Test
    fun `approval cancellation is propagated`() = runTest {
        val tools = Tools(ToolRisk.MUTATING)
        try {
            AgentLoop(
                provider(call()).connection(),
                tools,
                config,
                approve = { throw CancellationException("cancel") }).run(history).toList()
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
        }
        assertEquals(0, tools.executions)
    }

    @Test
    fun `Ask bypasses approval and tool timeout and allows custom answer`() = runTest {
        val tools = Tools()
        val diagnostics = mutableListOf<String>()
        var asked = false
        val events = AgentLoop(
            provider(
                call(
                    "Ask",
                    """{"question":"Which?","option_ids":["a"],"option_labels":["A"]}"""
                )
            ).connection(),
            tools,
            config.copy(toolTimeoutMs = 1),
            approve = { error("Ask must bypass approval") },
            approvalMode = ApprovalMode.ASK,
            approvalDiagnostic = diagnostics::add,
            ask = { request ->
                asked = true
                assertTrue(request.allowCustom)
                assertEquals(InteractionPurpose.QUESTION, request.purpose)
                delay(100)
                AskAnswer(customText = "other")
            },
        ).run(history).toList()
        assertTrue(asked)
        assertTrue(diagnostics.isEmpty())
        assertEquals(0, tools.executions)
        assertFalse(events.filterIsInstance<AgentEvent.ToolResultEvent>().single().result.isError)
    }

    @Test
    fun `Ask calls retain distinct tool associations and unchanged answer results`() = runTest {
        val requests = mutableListOf<AskRequest>()
        val tools = Tools()
        val events = AgentLoop(
            provider(
                call(
                    "Ask",
                    """{"question":"First?","option_ids":["a"],"option_labels":["A"]}""",
                    id = "ask-first"
                ),
                call("Ask", """{"question":"Second?"}""", id = "ask-second", index = 1),
            ),
            tools, config.copy(toolTimeoutMs = 1),
            approve = { error("Ask must bypass approval") }, approvalMode = ApprovalMode.ASK,
            ask = { request ->
                requests += request
                delay(100)
                if (request.toolCallId == "ask-first") AskAnswer(listOf("a")) else AskAnswer(
                    customText = "second answer"
                )
            },
        ).run(history).toList()

        assertEquals(listOf("ask-first", "ask-second"), requests.map { it.toolCallId })
        assertEquals(listOf("First?", "Second?"), requests.map { it.question })
        assertEquals(2, requests.map { it.id }.distinct().size)
        assertTrue(requests.all { it.id !in listOf("ask-first", "ask-second") })
        assertTrue(requests.all { it.purpose == InteractionPurpose.QUESTION && it.allowCustom })
        assertEquals(0, tools.executions)
        val results = events.filterIsInstance<AgentEvent.ToolResultEvent>()
        assertEquals(listOf("ask-first", "ask-second"), results.map { it.id })
        assertTrue(results.none { it.result.isError })
        assertEquals(
            listOf(
                """{"selected_ids":["a"]}""",
                """{"selected_ids":[],"custom_text":"second answer"}"""
            ),
            results.map { it.result.content },
        )
    }

    @Test
    fun `generic Ask execution has no tool association and does not retain earlier association`() =
        runTest {
            val requests = mutableListOf<AskRequest>()
            val tools = Tools()
            val environment = AskToolEnvironment(tools) { request ->
                requests += request
                AskAnswer(customText = "answer")
            }
            val args = Json.parseToJsonElement("""{"question":"Question?"}""").jsonObject
            val linked = environment.executeAsk(args, "linked-call")
            val unlinked = environment.execute("Ask", args)
            assertEquals(listOf("linked-call", null), requests.map { it.toolCallId })
            assertNotEquals(requests[0].id, requests[1].id)
            assertFalse(linked.isError)
            assertEquals(linked, unlinked)
            assertEquals("""{"selected_ids":[],"custom_text":"answer"}""", unlinked.content)
            assertNull(AskRequest("standalone", "Question?", emptyList()).toolCallId)
            assertEquals(ToolResult.ok("done"), environment.execute("test", buildJsonObject {}))
            assertEquals(1, tools.executions)
            assertEquals(2, requests.size)
        }

    @Test
    fun `Ask rejects forged purpose and unknown option IDs`() = runTest {
        var asked = false
        val env =
            AskToolEnvironment(EmptyToolEnvironment) { asked = true; AskAnswer(listOf("missing")) }
        assertTrue(
            env.execute(
                "Ask",
                Json.parseToJsonElement("""{"question":"x","purpose":"CONTEXT_COMPACTION"}""").jsonObject
            ).isError
        )
        assertTrue(
            env.executeAsk(
                Json.parseToJsonElement("""{"question":"x","purpose":"CONTEXT_COMPACTION"}""").jsonObject,
                "host-call"
            ).isError
        )
        assertTrue(
            env.executeAsk(
                Json.parseToJsonElement("""{"question":"x","toolCallId":"forged"}""").jsonObject,
                "host-call"
            ).isError
        )
        assertFalse(asked)
        assertTrue(
            env.execute(
                "Ask",
                Json.parseToJsonElement("""{"question":"x","option_ids":["a"],"option_labels":["A"]}""").jsonObject
            ).isError
        )
        assertTrue(asked)
        val multi = AskRequest(
            "id",
            "pick",
            listOf(AskOption("a", "A"), AskOption("b", "B")),
            multiple = true
        )
        multi.validateAnswer(AskAnswer(listOf("a", "b")))
    }

    @Test
    fun `checkpoints remain paired when tool execution is cancelled`() = runTest {
        val events = mutableListOf<AgentEvent>()
        val tools = Tools(action = { throw CancellationException("killed") })
        try {
            AgentLoop(provider(call(), call(id = "two", index = 1)), tools, config).run(history)
                .collect { events += it }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        val checkpoints = events.filterIsInstance<AgentEvent.Checkpoint>()
        checkpoints.forEach { checkpoint ->
            val calls = checkpoint.messages.flatMap { it.toolCalls }.map { it.id }.sorted()
            val results =
                checkpoint.messages.filter { it.role == MessageRole.TOOL }.map { it.toolCallId }
                    .sortedBy { it }
            assertEquals(calls, results)
        }
        val last = checkpoints.last().messages
        assertTrue(last.first { it.toolCallId == "one" }.content!!.contains("read was interrupted"))
        assertFalse(last.first { it.toolCallId == "one" }.content!!.contains("UNKNOWN"))
        assertTrue(last.first { it.toolCallId == "two" }.content!!.contains("not executed"))
        assertEquals(1, tools.executions)
    }

    @Test
    fun `budget callback runs before every request and can stop normally`() = runTest {
        val provider = provider(call())
        var checks = 0
        val events = AgentLoop(provider, Tools(), config, checkBudget = { request ->
            checks++
            assertEquals("stable", request.systemPrompt)
            if (checks == 1) ContextBudgetDecision.Continue(request.messages) else ContextBudgetDecision.Stop(
                "new conversation"
            )
        }).run(history).toList()
        assertEquals(2, checks)
        assertEquals(1, provider.requests.size)
        assertEquals(
            listOf(1),
            events.filterIsInstance<AgentEvent.TurnStarted>().map { it.iteration })
        assertTrue(events.any { it is AgentEvent.Notice })
        assertTrue(events.any { it is AgentEvent.TurnFinished })
        assertFalse(events.any { it is AgentEvent.Failed })
    }

    @Test
    fun `budget includes system tools history protocol data and output margin`() {
        val base = ContextBudgetRequest(history, "sys", emptyList())
        assertEquals(128000, base.contextWindowTokens)
        assertTrue(base.copy(systemPrompt = "x".repeat(300)).estimatedInputTokens > base.estimatedInputTokens)
        assertTrue(base.copy(tools = Tools().availableTools()).estimatedInputTokens > base.estimatedInputTokens)
        assertTrue(base.copy(outputReserveTokens = 128000).needsCompaction)
        assertTrue(base.copy(contextWindowTokens = 10).needsCompaction)
        assertFalse(base.needsCompaction)
    }

    @Test
    fun `every actual request starts a new usage and reasoning scope`() = runTest {
        val firstUsage = ChatEvent.Usage(inputTokens = 20, cachedInputTokens = 10)
        val secondUsage = ChatEvent.Usage(inputTokens = 30)
        val provider = Provider(
            listOf(
                listOf(firstUsage, call(), ChatEvent.Finished("tool_calls")),
                listOf(secondUsage, ChatEvent.TextDelta("ok"), ChatEvent.Finished("stop")),
            )
        )
        val events = AgentLoop(provider, Tools(), config).run(history).toList()
        assertEquals(
            listOf(1, 2),
            events.filterIsInstance<AgentEvent.TurnStarted>().map { it.iteration })
        val usageScopes = events.filter { it is AgentEvent.TurnStarted || it is AgentEvent.Usage }
        assertTrue(usageScopes[0] is AgentEvent.TurnStarted)
        assertEquals(firstUsage, (usageScopes[1] as AgentEvent.Usage).usage)
        assertTrue(usageScopes[2] is AgentEvent.TurnStarted)
        assertEquals(secondUsage, (usageScopes[3] as AgentEvent.Usage).usage)
    }

    @Test
    fun `native protocol items and usage are retained`() = runTest {
        val item = buildJsonObject { put("type", "reasoning"); put("id", "native") }
        val usage = ChatEvent.Usage(20, 3, 10)
        val events = AgentLoop(
            Provider(
                listOf(
                    listOf(
                        ChatEvent.ProtocolItem(item),
                        usage,
                        ChatEvent.TextDelta("ok"),
                        ChatEvent.Finished("stop")
                    )
                )
            ), Tools(), config
        ).run(history).toList()
        assertEquals(usage, events.filterIsInstance<AgentEvent.Usage>().single().usage)
        val native = hk.uwu.reareye.agent.koog.KoogTranscriptCodec.toNative(
            events.filterIsInstance<AgentEvent.TurnFinished>().single().messages.takeLast(1),
        ).single()
        assertEquals(
            "native",
            native.parts.filterIsInstance<ai.koog.prompt.message.MessagePart.Reasoning>()
                .single().id
        )
    }
}
