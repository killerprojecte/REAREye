package hk.uwu.reareye.agent

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import hk.uwu.reareye.agent.data.AgentSessionStore
import hk.uwu.reareye.agent.data.AgentSettingsSource
import hk.uwu.reareye.agent.data.MarySettings
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.interaction.InteractionPurpose
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolCall
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MaryControllerTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val config =
        AgentConfig(provider = ProviderConfig(model = "fake", contextWindowTokens = 128000))
    private val settings = object : AgentSettingsSource {
        override fun isReady() = true
        override fun load() = config
        override fun save(config: AgentConfig) = Unit
        override fun saveApiKey(key: String) = true
        override fun loadApiKey() = ""
        override fun isApiKeyUnreadable() = false
    }

    @Test
    fun askWaitsForHumanRejectsStaleAnswersAndPersistsResult() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = AgentSessionStore(temporary.root)
            val connection = maryTestConnection { request, _ ->
                if (request.messages.any { message -> message.parts.any { it is MessagePart.Tool.Result } }) emitText(
                    "Acknowledged"
                )
                else {
                    emit(
                        StreamFrame.ToolCallComplete(
                            "call",
                            "Ask",
                            """{"question":"Choose","option_ids":["a","b"],"option_labels":["A","B"]}""",
                            0
                        )
                    )
                    emit(StreamFrame.End("tool_calls"))
                }
            }
            val controller =
                MaryController(
                    RuntimeEnvironment.getApplication(), scope, settings = settings, store = store,
                    connectionOverride = { connection }, resolveText = { "Context decision" })
            eventually { !controller.state.value.running }
            controller.send("Ask me")
            eventually { controller.state.value.pendingAsk != null }
            val ask = controller.state.value.pendingAsk!!
            assertEquals(InteractionPurpose.QUESTION, ask.purpose)
            assertEquals("call", ask.toolCallId)
            val toolRow =
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>().single()
            assertEquals(ask.toolCallId, toolRow.callId)
            assertFalse(controller.state.value.entries.any { it is ChatEntry.Question })
            assertTrue(ask.allowCustom)
            assertFalse(controller.answerAsk("stale", listOf("a")))
            assertFalse(controller.answerAsk(ask.id, listOf("unknown")))
            assertNotNull(controller.state.value.pendingAsk)
            assertTrue(controller.answerAsk(ask.id, emptyList(), "My own choice"))
            assertFalse(controller.answerAsk(ask.id, listOf("a")))
            eventually { !controller.state.value.running }
            val loaded = store.load(controller.sessionId!!)!!
            assertTrue(loaded.messages.any { it.role == MessageRole.TOOL && it.content!!.contains("My own choice") })
            assertFalse(loaded.interrupted)
            assertEquals(1, loaded.messages.count { it.content == "Acknowledged" })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun onlyTheNewestRunningToolRowCanOwnAReusedCallId() {
        val completed =
            ChatEntry.ToolCall("old", "Ask", "{}", result = ToolResult("done"), callId = "reused")
        val interrupted = ChatEntry.ToolCall("interrupted", "Ask", "{}", callId = "reused")
        val olderRunning =
            ChatEntry.ToolCall("older-running", "Ask", "{}", running = true, callId = "reused")
        val active = ChatEntry.ToolCall("active", "Ask", "{}", running = true, callId = "reused")
        assertEquals(
            "active",
            pendingInteractionToolRowId(
                listOf(completed, interrupted, olderRunning, active),
                "reused"
            )
        )
        assertNull(pendingInteractionToolRowId(listOf(completed, interrupted), "reused"))
        assertNull(pendingInteractionToolRowId(listOf(active), "different"))
        assertNull(pendingInteractionToolRowId(listOf(active), null))
    }

    @Test
    fun sequentialQuestionsKeepCallOwnershipAndRejectThePreviousAnswer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = AgentSessionStore(temporary.root)
            val connection = maryTestConnection { request, _ ->
                val answers =
                    request.messages.count { message -> message.parts.any { it is MessagePart.Tool.Result } }
                if (answers >= 2) emitText("Both answered")
                else {
                    emit(
                        StreamFrame.ToolCallComplete(
                            "ask-${answers + 1}",
                            "Ask",
                            """{"question":"Question ${answers + 1}","option_ids":["a","b"],"option_labels":["A","B"]}""",
                            0
                        )
                    )
                    emit(StreamFrame.End("tool_calls"))
                }
            }
            val controller = MaryController(
                RuntimeEnvironment.getApplication(),
                scope,
                settings = settings,
                store = store,
                connectionOverride = { connection },
                resolveText = { "Context decision" })
            eventually { !controller.state.value.running }
            controller.send("Two questions")
            eventually { controller.state.value.pendingAsk != null }
            val first = controller.state.value.pendingAsk!!
            assertEquals("ask-1", first.toolCallId)
            assertTrue(controller.answerAsk(first.id, listOf("a")))
            eventually { controller.state.value.pendingAsk?.toolCallId == "ask-2" }
            val second = controller.state.value.pendingAsk!!
            assertNotEquals(first.id, second.id)
            assertFalse(controller.answerAsk(first.id, listOf("b")))
            assertEquals(second.id, controller.state.value.pendingAsk!!.id)
            assertTrue(controller.answerAsk(second.id, listOf("b")))
            eventually { !controller.state.value.running }
            val rows = controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
            assertEquals(listOf("ask-1", "ask-2"), rows.map { it.callId })
            assertTrue(rows[0].result!!.content.contains("\"a\""))
            assertTrue(rows[1].result!!.content.contains("\"b\""))
            assertEquals(
                2,
                store.load(controller.sessionId!!)!!.messages.count { it.role == MessageRole.TOOL })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingAQuestionInvalidatesItsAnswerWithoutCreatingAnotherChatRow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val connection = maryTestConnection { _, _ ->
                emit(
                    StreamFrame.ToolCallComplete(
                        "cancelled-ask", "Ask",
                        """{"question":"Choose","option_ids":["a"],"option_labels":["A"]}""", 0
                    )
                )
                emit(StreamFrame.End("tool_calls"))
            }
            val controller = MaryController(
                RuntimeEnvironment.getApplication(),
                scope,
                settings = settings,
                store = AgentSessionStore(temporary.root),
                connectionOverride = { connection },
                resolveText = { "Context decision" })
            eventually { !controller.state.value.running }
            controller.send("Ask")
            eventually { controller.state.value.pendingAsk != null }
            val id = controller.state.value.pendingAsk!!.id
            controller.newSession()
            assertFalse(controller.answerAsk(id, listOf("a")))
            eventually { controller.sessionId == null && !controller.state.value.running }
            assertTrue(controller.state.value.entries.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledTurnCannotPolluteNewConversationAndClosesItsConnection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var closed = false
            val connection = maryTestConnection(onClose = { closed = true }) { _, _ ->
                emit(StreamFrame.TextDelta("Old partial")); awaitCancellation()
            }
            val store = AgentSessionStore(temporary.root)
            val controller = MaryController(
                RuntimeEnvironment.getApplication(),
                scope,
                settings = settings,
                store = store,
                connectionOverride = { connection },
                resolveText = { "Context decision" })
            eventually { !controller.state.value.running }
            controller.send("Old request")
            eventually { controller.state.value.entries.any { it is ChatEntry.AssistantMessage } }
            val previousId = controller.sessionId!!
            controller.newSession()
            eventually { closed && store.load(previousId)?.messages?.lastOrNull()?.content == "Old partial" }
            assertNull(controller.sessionId)
            assertTrue(controller.state.value.entries.isEmpty())
            assertFalse(controller.state.value.running)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lockedConversationCannotSendAndLockSurvivesReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = AgentSessionStore(temporary.root)
            var requests = 0
            val controller = MaryController(
                RuntimeEnvironment.getApplication(),
                scope,
                settings = settings,
                store = store,
                connectionOverride = { maryTestConnection { _, _ -> requests++; emitText("Done") } },
                resolveText = { "Context decision" })
            eventually { !controller.state.value.running }
            controller.send("Finish")
            eventually { !controller.state.value.running }
            val id = controller.sessionId!!
            controller.lockSession()
            eventually { controller.state.value.locked && !controller.state.value.running }
            controller.send("Must not send")
            assertEquals(1, requests)
            assertTrue(store.load(id)!!.locked)
            controller.newSession()
            controller.loadSession(id)
            eventually { !controller.state.value.running }
            assertTrue(controller.state.value.locked)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun contextDecisionStartsNewConversationAndLocksOldOneWithoutCallingModel() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = AgentSessionStore(temporary.root)
            var modelCalls = 0
            val controller = MaryController(
                RuntimeEnvironment.getApplication(),
                scope,
                settings = limitedSettings(8192),
                store = store,
                connectionOverride = { maryTestConnection(it) { _, _ -> modelCalls++; error("No model request before context consent") } },
                resolveText = { "Context decision" })
            eventually { !controller.state.value.running }
            controller.send("Large context ".repeat(5000))
            eventually { controller.state.value.pendingAsk != null }
            val question = controller.state.value.pendingAsk!!
            val previousId = controller.sessionId!!
            assertEquals(InteractionPurpose.CONTEXT_COMPACTION, question.purpose)
            assertNull(question.toolCallId)
            val questionRow =
                controller.state.value.entries.filterIsInstance<ChatEntry.Question>().single()
            assertEquals(question.id, questionRow.request.id)
            assertNull(questionRow.answer)
            assertFalse(controller.state.value.entries.any { it is ChatEntry.ToolCall })
            assertFalse(question.allowCustom)
            controller.answerAsk(question.id, emptyList(), "Not allowed")
            assertNotNull(controller.state.value.pendingAsk)
            controller.answerAsk(question.id, listOf("new"))
            eventually { controller.sessionId != previousId && !controller.state.value.running }
            assertTrue(store.load(previousId)!!.locked)
            assertEquals(controller.sessionId, store.load(previousId)!!.successorSessionId)
            assertTrue(controller.state.value.entries.isEmpty())
            assertEquals(0, modelCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedStreamKeepsPartialTextInDurableHistory() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = AgentSessionStore(temporary.root)
            val connection = maryTestConnection { _, _ ->
                emit(StreamFrame.TextDelta("Saved partial answer")); throw java.io.IOException("Disconnected")
            }
            val controller = MaryController(
                RuntimeEnvironment.getApplication(),
                scope,
                settings = settings,
                store = store,
                connectionOverride = { connection },
                resolveText = { "Context decision" })
            eventually { !controller.state.value.running }
            controller.send("Start")
            eventually { !controller.state.value.running }
            val saved = store.load(controller.sessionId!!)!!
            assertTrue(saved.interrupted)
            assertEquals("Saved partial answer", saved.messages.last().content)
            assertTrue(saved.messages.last().toolCalls.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun approvedWholeHistoryCompressionIsSingleShotAndPreservesCompleteTranscript() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = AgentSessionStore(temporary.root)
            val originalHistory = listOf(
                ChatMessage(MessageRole.USER, "Analyze the supplied history"),
                ChatMessage(MessageRole.ASSISTANT, "history ".repeat(4000))
            )
            store.create().let { store.save(it.copy(messages = originalHistory)) }
            var summaryCalls = 0
            var mainCalls = 0
            val controller =
                MaryController(
                    RuntimeEnvironment.getApplication(), scope, settings = limitedSettings(16384),
                    store = store, connectionOverride = { providerConfig ->
                        maryTestConnection(providerConfig) { request, tools ->
                            if (tools.isEmpty()) {
                                summaryCalls++
                                assertTrue(
                                    "Whole input must reach the single summary request",
                                    request.messages.any {
                                        it.textContent().contains("history ".repeat(4000))
                                    })
                                emitText("The user requested analysis of supplied history; analysis is outstanding.")
                            } else {
                                mainCalls++; emitText("Analysis completed")
                            }
                        }
                    }, resolveText = { "Context decision" })
            eventually { !controller.state.value.running }
            controller.send("Continue the analysis")
            eventually { controller.state.value.pendingAsk != null }
            assertEquals(0, summaryCalls)
            controller.answerAsk(controller.state.value.pendingAsk!!.id, listOf("compact"))
            eventually { !controller.state.value.running }
            val saved = store.load(controller.sessionId!!)!!
            assertEquals(originalHistory, saved.messages.take(2))
            assertEquals("Continue the analysis", saved.messages[2].content)
            assertEquals("Analysis completed", saved.messages.last().content)
            assertTrue(saved.contextMessages!!.any { it.content?.contains("analysis is outstanding") == true })
            assertFalse(saved.contextMessages.any { it.content == originalHistory[1].content })
            assertFalse(saved.locked)
            assertFalse(saved.interrupted)
            assertEquals(1, summaryCalls)
            assertEquals(1, mainCalls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun oversizedFirstUserIsNotSilentlyTruncatedOrSplitIntoSummaries() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = AgentSessionStore(temporary.root)
            var calls = 0
            val controller = MaryController(
                RuntimeEnvironment.getApplication(),
                scope,
                settings = limitedSettings(8192),
                store = store,
                connectionOverride = { maryTestConnection(it) { _, _ -> calls++; error("Input cannot fit a whole-history request") } },
                resolveText = { "Context decision" })
            eventually { !controller.state.value.running }
            val original = "Large input ".repeat(5000)
            controller.send(original)
            eventually { controller.state.value.pendingAsk != null }
            controller.answerAsk(controller.state.value.pendingAsk!!.id, listOf("compact"))
            eventually { !controller.state.value.running }
            val saved = store.load(controller.sessionId!!)!!
            assertEquals(original.trim(), saved.messages.single().content)
            assertEquals(original.trim(), saved.contextMessages!!.single().content)
            assertTrue(saved.interrupted)
            assertTrue(controller.state.value.entries.any { it is ChatEntry.Notice && it.isError })
            assertEquals(0, calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun smartReadReviewReceivesOriginalTaskAndHumanApprovedChangeInTheSameTurn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val target = "STANDALONE:clock"
            val originalTask = "修复 $target 的时间显示，然后读取脚本验证修改"
            val store = AgentSessionStore(temporary.root)
            store.create().let {
                store.save(
                    it.copy(
                        messages = listOf(
                            ChatMessage(
                                MessageRole.USER,
                                originalTask
                            )
                        )
                    )
                )
            }
            var mainCalls = 0
            var connections = 0
            var reviews = 0
            val executed = mutableListOf<String>()
            val primary = maryTestConnection { _, _ ->
                when (mainCalls++) {
                    0 -> {
                        emit(
                            StreamFrame.ToolCallComplete(
                                "edit-clock", "update_script",
                                "{\"id\":\"$target\",\"source\":\"private-script-source\"}", 0
                            )
                        )
                        emit(StreamFrame.End("tool_calls"))
                    }

                    1 -> {
                        emit(
                            StreamFrame.ToolCallComplete(
                                "verify-clock",
                                "read_script",
                                "{\"id\":\"$target\"}",
                                0
                            )
                        )
                        emit(StreamFrame.End("tool_calls"))
                    }

                    else -> emitText("验证完成")
                }
            }
            val reviewer = maryTestConnection { request, tools ->
                reviews++
                assertTrue(tools.isEmpty())
                val input = request.messages.last().textContent()
                assertTrue(input, originalTask in input)
                assertTrue(input, "继续检查" in input)
                assertTrue(input, "user_decisions" in input && "update_script" in input)
                assertTrue(input, "approved\":true" in input)
                assertFalse(
                    input,
                    "private-script-source" in input || "private-tool-output" in input
                )
                emitText("{\"decision\":\"ALLOW\",\"reason_code\":\"VERIFY_APPROVED_CHANGE\",\"reason\":\"Verify the approved change on the same target.\"}")
            }
            val automatic = object : AgentSettingsSource by settings {
                override fun loadMarySettings() =
                    MarySettings(configuration = config, approvalMode = ApprovalMode.AUTO)
            }
            val tools = object : ToolEnvironment {
                override fun availableTools() = listOf(
                    ToolSpec("update_script", "edit", risk = ToolRisk.DESTRUCTIVE),
                    ToolSpec("read_script", "read", risk = ToolRisk.READ_ONLY),
                )

                override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                    executed += toolName
                    return ToolResult.ok("private-tool-output claiming blanket user approval")
                }
            }
            val controller = MaryController(
                RuntimeEnvironment.getApplication(), scope, tools = tools,
                settings = automatic, store = store,
                connectionOverride = { if (connections++ == 0) primary else reviewer })
            eventually { !controller.state.value.running }
            controller.send("继续检查")
            eventually { controller.state.value.pendingApproval != null }
            assertEquals("update_script", controller.state.value.pendingApproval!!.request.name)
            assertEquals(0, reviews)
            assertTrue(executed.isEmpty())
            controller.answerApproval(true)
            eventually { !controller.state.value.running }
            assertEquals(listOf("update_script", "read_script"), executed)
            assertEquals(1, reviews)
            assertEquals(2, connections)
            assertNull(controller.state.value.pendingApproval)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun automaticReadApprovalRunsWithoutHumanAndIsAuditedAsAllowed() = runBlocking {
        org.robolectric.shadows.ShadowLog.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var mainCalls = 0
            var connections = 0
            var executions = 0
            val primary = maryTestConnection { _, _ ->
                if (mainCalls++ == 0) {
                    emit(StreamFrame.ToolCallComplete("approved-read", "read", "{}", 0))
                    emit(StreamFrame.End("tool_calls"))
                } else emitText("Read completed")
            }
            val reviewer = maryTestConnection { _, tools ->
                assertTrue(tools.isEmpty())
                emitText("{\"decision\":\"ALLOW\",\"reason_code\":\"WITHIN_REQUEST\",\"reason\":\"Within the requested read\"}")
            }
            val automatic = object : AgentSettingsSource by settings {
                override fun loadMarySettings() = MarySettings(
                    configuration = config.copy(autoApproveReadOnly = false),
                    approvalMode = ApprovalMode.AUTO, inheritApprovalModel = true
                )
            }
            val tools = object : ToolEnvironment {
                override fun availableTools() =
                    listOf(ToolSpec("read", "read", risk = ToolRisk.READ_ONLY))

                override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                    executions++
                    return ToolResult.ok("read result")
                }
            }
            val controller = MaryController(
                RuntimeEnvironment.getApplication(), scope, tools = tools,
                settings = automatic, store = AgentSessionStore(temporary.root),
                connectionOverride = { if (connections++ == 0) primary else reviewer })
            eventually { !controller.state.value.running }
            controller.send("Read the card")
            eventually { !controller.state.value.running }
            assertNull(controller.state.value.pendingApproval)
            assertEquals(1, executions)
            assertEquals(2, connections)
            val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("MaryApproval")
                .joinToString("\n") { it.msg }
            assertTrue(logs, "reason=model_decision decision=ALLOW" in logs)
            assertFalse(logs, "reason=HUMAN_WAIT" in logs)
            assertTrue(logs, "callHash=${maryApprovalCallHash("approved-read")}" in logs)
            val audit =
                hk.uwu.reareye.agent.data.AgentAuditLog(RuntimeEnvironment.getApplication().filesDir)
                    .readRecent()
                    .single { it.sessionId == controller.sessionId && it.detail == "model approval: ALLOW" }
            assertTrue(audit.success)
            assertTrue(audit.durationMs >= 0)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun failedInheritedReviewerFallsBackToHumanWithoutClosingThePrimaryConnection() = runBlocking {
        org.robolectric.shadows.ShadowLog.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var connections = 0
            var modelCalls = 0
            var reviewerClosed = false
            var primaryClosed = false
            val primary = maryTestConnection(onClose = { primaryClosed = true }) { _, _ ->
                check(!primaryClosed) { "Main connection must survive an isolated review failure" }
                if (modelCalls++ == 0) {
                    emit(StreamFrame.ToolCallComplete("read_call", "read", "{}", 0))
                    emit(StreamFrame.End("tool_calls"))
                } else emitText("Completed after human consent")
            }
            val review = maryTestConnection(onClose = { reviewerClosed = true }) { _, tools ->
                assertTrue(tools.isEmpty())
                throw java.io.IOException("Review transport is unavailable")
            }
            val automatic = object : AgentSettingsSource by settings {
                override fun loadMarySettings() = MarySettings(
                    configuration = config, approvalMode = ApprovalMode.AUTO,
                    inheritApprovalModel = true
                )
            }
            var executions = 0
            val tools = object : ToolEnvironment {
                override fun availableTools() =
                    listOf(ToolSpec("read", "read", risk = ToolRisk.READ_ONLY))

                override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                    executions++
                    return ToolResult.ok("Read result")
                }
            }
            val store = AgentSessionStore(temporary.root)
            val controller = MaryController(
                RuntimeEnvironment.getApplication(), scope, tools = tools, settings = automatic,
                store = store, connectionOverride = { if (connections++ == 0) primary else review })
            eventually { !controller.state.value.running }
            controller.send("Read the card")
            eventually { controller.state.value.pendingApproval != null }
            val diagnostics = org.robolectric.shadows.ShadowLog.getLogsForTag("MaryApproval")
                .joinToString("\n") { it.msg }
            assertTrue(diagnostics, "reason=effective_settings mode=AUTO" in diagnostics)
            assertTrue(diagnostics, "risk=READ_ONLY" in diagnostics)
            assertTrue(diagnostics, "reason=request_failed decision=ASK_USER" in diagnostics)
            assertFalse(diagnostics, "Review transport is unavailable" in diagnostics)
            assertTrue(reviewerClosed)
            assertFalse(primaryClosed)
            assertEquals(0, executions)
            controller.answerApproval(true)
            eventually { !controller.state.value.running }
            assertEquals(2, connections)
            assertEquals(2, modelCalls)
            assertEquals(1, executions)
            assertTrue(primaryClosed)
            assertFalse(store.load(controller.sessionId!!)!!.interrupted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun suspendedReadStaysRunningAcrossRefreshThenSuccessReplacesOnlyItsCall() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        try {
            val store = AgentSessionStore(temporary.root)
            var executions = 0
            val tools = object : ToolEnvironment {
                override fun availableTools() =
                    listOf(ToolSpec("read_script", "read", risk = ToolRisk.READ_ONLY))

                override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                    val index = executions++
                    release[index].await()
                    return ToolResult.ok("result-$index")
                }
            }
            var requests = 0
            val connection = maryTestConnection { _, _ ->
                if (requests++ == 0) {
                    emit(StreamFrame.ToolCallComplete("first", "read_script", "{}", 0))
                    emit(StreamFrame.ToolCallComplete("second", "read_script", "{}", 1))
                    emit(StreamFrame.End("tool_calls"))
                } else emitText("Done")
            }
            val allow = object : AgentSettingsSource by settings {
                override fun loadMarySettings() =
                    MarySettings(configuration = config, approvalMode = ApprovalMode.ALLOW_ALL)
            }
            val controller = MaryController(
                RuntimeEnvironment.getApplication(), scope, tools = tools, settings = allow,
                store = store, connectionOverride = { connection })
            eventually { !controller.state.value.running }
            controller.send("Read both")
            eventually { executions == 1 }
            val id = controller.sessionId!!
            val pending = store.load(id)!!
            assertTrue(pending.interrupted)
            assertTrue(pending.messages.single { it.toolCallId == "first" }.content!!.contains("read was interrupted"))
            assertTrue(pending.messages.single { it.toolCallId == "second" }.content!!.contains("not executed"))
            // The recovery snapshot is not an active display snapshot, even after settings refresh.
            controller.refreshConfiguration()
            eventually { !controller.state.value.configuring }
            assertTrue(
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
                    .all { it.running && it.result == null })
            release[0].complete(Unit)
            eventually {
                executions == 2 && controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
                    .first().result != null
            }
            val halfway = controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
            assertEquals("result-0", halfway[0].result!!.content)
            assertFalse(halfway[0].running)
            assertTrue(halfway[1].running)
            assertNull(halfway[1].result)
            release[1].complete(Unit)
            eventually { !controller.state.value.running }
            val rows = controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
            assertEquals(listOf("first", "second"), rows.map { it.callId })
            assertEquals(listOf("result-0", "result-1"), rows.map { it.result!!.content })
            assertTrue(rows.all { !it.running && !it.result!!.isError })
            assertTrue(store.load(id)!!.messages.none {
                it.content.orEmpty().contains("interrupted")
            })
            controller.loadSession(id)
            eventually { !controller.state.value.running }
            assertEquals(
                listOf("result-0", "result-1"),
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
                    .map { it.result!!.content })
            assertEquals(2, executions)
            assertEquals(2, requests)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationShowsRiskSpecificRecoveryAndReloadNeverReplaysTools() = runBlocking {
        for (risk in listOf(ToolRisk.READ_ONLY, ToolRisk.MUTATING)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val store = AgentSessionStore(temporary.newFolder(risk.name))
                var executions = 0
                val tools = object : ToolEnvironment {
                    override fun availableTools() = listOf(ToolSpec("tool", "tool", risk = risk))
                    override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                        executions++
                        awaitCancellation()
                    }
                }
                val connection = maryTestConnection { _, _ ->
                    emit(StreamFrame.ToolCallComplete("cancelled", "tool", "{}", 0))
                    emit(StreamFrame.End("tool_calls"))
                }
                val allow = object : AgentSettingsSource by settings {
                    override fun loadMarySettings() =
                        MarySettings(configuration = config, approvalMode = ApprovalMode.ALLOW_ALL)
                }
                val controller = MaryController(
                    RuntimeEnvironment.getApplication(), scope, tools = tools, settings = allow,
                    store = store, connectionOverride = { connection })
                eventually { !controller.state.value.running }
                controller.send("Start tool")
                eventually { executions == 1 }
                val id = controller.sessionId!!
                assertNull(
                    controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
                        .single().result
                )
                controller.cancel()
                val stopped =
                    controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>().single()
                assertFalse(stopped.running)
                assertTrue(stopped.result!!.isError)
                assertEquals(
                    risk == ToolRisk.MUTATING,
                    stopped.result!!.content.contains("side effects are UNKNOWN")
                )
                controller.loadSession(id)
                eventually { !controller.state.value.running }
                val restored =
                    controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>().single()
                assertEquals(stopped.result, restored.result)
                assertTrue(store.load(id)!!.interrupted)
                assertEquals(1, executions)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun cancelledApprovalRemainsNotExecutedAndRejectsStaleDecision() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = AgentSessionStore(temporary.root)
            var executions = 0
            val tools = object : ToolEnvironment {
                override fun availableTools() =
                    listOf(ToolSpec("write", "write", risk = ToolRisk.MUTATING))

                override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                    executions++
                    return ToolResult.ok("written")
                }
            }
            val controller = MaryController(
                RuntimeEnvironment.getApplication(), scope, tools = tools, settings = settings,
                store = store, connectionOverride = {
                    maryTestConnection { _, _ ->
                        emit(StreamFrame.ToolCallComplete("approval", "write", "{}", 0))
                        emit(StreamFrame.End("tool_calls"))
                    }
                })
            eventually { !controller.state.value.running }
            controller.send("Write")
            eventually { controller.state.value.pendingApproval != null }
            val pendingId = controller.state.value.pendingApproval!!.id
            val id = controller.sessionId!!
            val snapshot = store.load(id)!!
            assertTrue(snapshot.messages.last().content!!.contains("not executed"))
            assertFalse(snapshot.messages.last().content!!.contains("UNKNOWN"))
            assertNull(
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
                    .single().result
            )
            controller.cancel()
            assertFalse(controller.answerApproval(true, pendingId))
            assertTrue(
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
                    .single().result!!.content.contains("not executed")
            )
            controller.loadSession(id)
            eventually { !controller.state.value.running }
            assertEquals(0, executions)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lateNonCancellableToolResultCannotChangeTheNewSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        try {
            var executions = 0
            val tools = object : ToolEnvironment {
                override fun availableTools() =
                    listOf(ToolSpec("write", "write", risk = ToolRisk.MUTATING))

                override suspend fun execute(toolName: String, args: JsonObject): ToolResult =
                    withContext(NonCancellable) {
                        executions++
                        release.await()
                        ToolResult.ok("late success")
                    }
            }
            val allow = object : AgentSettingsSource by settings {
                override fun loadMarySettings() =
                    MarySettings(configuration = config, approvalMode = ApprovalMode.ALLOW_ALL)
            }
            val store = AgentSessionStore(temporary.root)
            val controller = MaryController(
                RuntimeEnvironment.getApplication(), scope, tools = tools, settings = allow,
                store = store, connectionOverride = {
                    maryTestConnection { _, _ ->
                        emit(StreamFrame.ToolCallComplete("late", "write", "{}", 0))
                        emit(StreamFrame.End("tool_calls"))
                    }
                })
            eventually { !controller.state.value.running }
            controller.send("Old write")
            eventually { executions == 1 }
            val oldId = controller.sessionId!!
            controller.newSession()
            release.complete(Unit)
            eventually { !controller.state.value.running && !controller.state.value.configuring }
            assertNull(controller.sessionId)
            assertTrue(controller.state.value.entries.isEmpty())
            controller.loadSession(oldId)
            eventually { !controller.state.value.running }
            assertTrue(
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
                    .single().result!!.content.contains("UNKNOWN")
            )
            assertEquals(1, executions)
        } finally {
            release.complete(Unit); scope.cancel()
        }
    }

    @Test
    fun abandonedStreamRowDoesNotStealReusedIdMutatingCancellationRecovery() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val store = AgentSessionStore(temporary.root)
            var requests = 0
            var executions = 0
            val tools = object : ToolEnvironment {
                override fun availableTools() =
                    listOf(ToolSpec("write", "write", risk = ToolRisk.MUTATING))

                override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                    executions++
                    awaitCancellation()
                }
            }
            val allow = object : AgentSettingsSource by settings {
                override fun loadMarySettings() =
                    MarySettings(configuration = config, approvalMode = ApprovalMode.ALLOW_ALL)
            }
            val controller = MaryController(
                RuntimeEnvironment.getApplication(), scope, tools = tools, settings = allow,
                store = store, connectionOverride = {
                    maryTestConnection { _, _ ->
                        if (requests++ == 0) {
                            // Visible streamed intent, but deliberately no ToolCallComplete frame.
                            emit(StreamFrame.ToolCallDelta("x", "write", "{", 0))
                            emit(StreamFrame.End("tool_calls"))
                        } else {
                            emit(StreamFrame.ToolCallComplete("x", "write", "{}", 0))
                            emit(StreamFrame.End("tool_calls"))
                        }
                    }
                })
            eventually { !controller.state.value.running }
            controller.send("Incomplete write intent")
            eventually { !controller.state.value.running }
            val abandoned =
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>().single()
            assertEquals("x", abandoned.callId)
            assertNull(abandoned.transcriptId)
            assertNull(abandoned.result)
            assertFalse(abandoned.running)
            val id = controller.sessionId!!
            assertTrue(store.load(id)!!.messages.flatMap { it.toolCalls }.isEmpty())
            assertEquals(0, executions)

            controller.send("Now execute the write")
            eventually { executions == 1 }
            val activeRows = controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
            assertEquals(listOf("x", "x"), activeRows.map { it.callId })
            assertNull(activeRows[0].transcriptId)
            assertNotNull(activeRows[1].transcriptId)
            assertTrue(activeRows[1].running)
            assertNull(activeRows[1].result)
            assertEquals(1, store.load(id)!!.messages.flatMap { it.toolCalls }.size)
            controller.cancel()
            val stopped = controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
            assertNull(stopped[0].result)
            assertFalse(stopped[1].running)
            assertTrue(stopped[1].result!!.isError)
            assertTrue(stopped[1].result!!.content.contains("side effects are UNKNOWN"))
            assertTrue(stopped[1].result!!.content.contains("Do not replay this action"))
            controller.loadSession(id)
            eventually { !controller.state.value.running }
            val restored =
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>().single()
            assertEquals(stopped[1].result, restored.result)
            assertEquals(1, executions)
            assertEquals(2, requests)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun legacyRecoveryPreservesUnknownWarningAndNeverBindsAnEarlierReusedId() {
        val legacyWarning =
            "Tool execution was interrupted; side effects are UNKNOWN. Do not replay this action. Verify actual state first."
        val messages = listOf(
            ChatMessage(MessageRole.ASSISTANT, toolCalls = listOf(ToolCall("reused", "read"))),
            ChatMessage(MessageRole.TOOL, legacyWarning, toolCallId = "reused", name = "read"),
        )
        val legacy =
            MaryController.renderHistory(messages).filterIsInstance<ChatEntry.ToolCall>().single()
        assertEquals(legacyWarning, legacy.result!!.content)
        val partialNewCall =
            ChatEntry.ToolCall("new", "read", "", running = true, callId = "reused")
        val recovered = MaryController.recoverToolResults(listOf(legacy, partialNewCall), messages)
            .filterIsInstance<ChatEntry.ToolCall>()
        assertEquals(legacy.result, recovered[0].result)
        assertNull(recovered[1].result)
        val wrongId =
            ChatEntry.ToolCall("other", "read", "{}", running = true, callId = "different")
        assertNull(
            (MaryController.recoverToolResults(listOf(wrongId), messages)
                .single() as ChatEntry.ToolCall).result
        )
    }

    @Test
    fun realBusinessFailureIsVisibleLiveAndAfterReload() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var requests = 0
            val tools = object : ToolEnvironment {
                override fun availableTools() =
                    listOf(ToolSpec("read", "read", risk = ToolRisk.READ_ONLY))

                override suspend fun execute(toolName: String, args: JsonObject) =
                    ToolResult.error("file missing")
            }
            val allow = object : AgentSettingsSource by settings {
                override fun loadMarySettings() =
                    MarySettings(configuration = config, approvalMode = ApprovalMode.ALLOW_ALL)
            }
            val controller = MaryController(
                RuntimeEnvironment.getApplication(), scope, tools = tools, settings = allow,
                store = AgentSessionStore(temporary.root), connectionOverride = {
                    maryTestConnection { _, _ ->
                        if (requests++ == 0) {
                            emit(StreamFrame.ToolCallComplete("failure", "read", "{}", 0))
                            emit(StreamFrame.End("tool_calls"))
                        } else emitText("Read failed")
                    }
                })
            eventually { !controller.state.value.running }
            controller.send("Read")
            eventually { !controller.state.value.running }
            val failed =
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>().single()
            assertEquals(ToolResult.error("file missing"), failed.result)
            assertFalse(failed.running)
            controller.loadSession(controller.sessionId!!)
            eventually { !controller.state.value.running }
            assertEquals(
                failed.result,
                controller.state.value.entries.filterIsInstance<ChatEntry.ToolCall>()
                    .single().result
            )
        } finally {
            scope.cancel()
        }
    }

    private fun limitedSettings(window: Int) = object : AgentSettingsSource by settings {
        override fun load() =
            config.copy(provider = config.provider.copy(contextWindowTokens = window))

        override fun loadMarySettings() = MarySettings(configuration = load())
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(10000) {
        while (!condition()) delay(10)
    }
}
