package hk.uwu.reareye.agent

import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.streaming.StreamFrame
import hk.uwu.reareye.agent.data.AgentSessionStore
import hk.uwu.reareye.agent.data.AgentSettingsSource
import hk.uwu.reareye.agent.data.MaryModelConfig
import hk.uwu.reareye.agent.data.MaryProviderConfig
import hk.uwu.reareye.agent.data.MarySettings
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MaryLiveRuntimeTest {
    @get:Rule val folder = TemporaryFolder()

    private val modelA = MaryModelConfig("a", "sourceA", "api-a", contextWindowTokens = 128_000)
    private val modelB = MaryModelConfig("b", "sourceB", "api-b", contextWindowTokens = 64_000)
    private val a = MaryProviderConfig("sourceA", "Provider A", "https://a.example/v1", Protocol.RESPONSES)
    private val b = MaryProviderConfig("sourceB", "Provider B", "https://b.example/v1", Protocol.ANTHROPIC)
    private val fakeDeviceTools = object : ToolEnvironment {
        override fun availableTools() = listOf(ToolSpec("test_device_tool", "Fixture tool"))
        override suspend fun execute(toolName: String, args: JsonObject) = error("Must not execute device tools")
    }
    private fun preferenceSource(agentMode: Boolean, webEnabled: Boolean = true): AgentSettingsSource {
        val initial = MarySettings(agentMode = agentMode, mainModelId = "a", subagentModelId = "b",
            models = listOf(modelA, modelB), providers = listOf(a, b), approvalMode = ApprovalMode.ALLOW_ALL,
            webEnabled = webEnabled)
        return object : AgentSettingsSource {
            override fun loadMarySettings() = initial
            override fun load() = initial.configuration
            override fun save(config: AgentConfig) = Unit
            override fun isReady() = true
            override fun saveApiKey(key: String) = true
            override fun loadApiKey() = ""
            override fun isApiKeyUnreadable() = false
        }
    }

    @Test fun chatModeUsesNoToolsAndSwitchesScopeWithoutReplayingSignedState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AgentSessionStore(folder.root)
        val requests = CopyOnWriteArrayList<Pair<String, ai.koog.prompt.Prompt>>()
        val descriptors = CopyOnWriteArrayList<List<ai.koog.agents.core.tools.ToolDescriptor>>()
        try {
            val controller = MaryController(RuntimeEnvironment.getApplication(), scope, fakeDeviceTools,
                preferenceSource(agentMode = false), store, connectionOverride = { provider ->
                    maryTestConnection(provider) { prompt, tools ->
                        requests += provider.model to prompt
                        descriptors.add(tools)
                        if (provider.model == "api-a") emit(StreamFrame.ReasoningComplete("reason-1", listOf("private"), encrypted = "signature", index = 0))
                        val index = if (provider.model == "api-a") 1 else 0
                        emit(StreamFrame.TextComplete(provider.model, index))
                        emit(StreamFrame.End("stop"))
                    }
                })
            idle(controller)
            assertFalse(controller.state.value.agentMode)
            assertEquals(listOf("Provider A", "Provider B"), controller.state.value.availableModels.map { it.providerName })
            assertTrue(controller.send("first"))
            idle(controller)
            val sessionId = checkNotNull(controller.sessionId)
            controller.selectModel("b")
            idle(controller)
            assertEquals("b", store.load(sessionId)?.selectedModelId)
            assertEquals(64_000, controller.state.value.contextWindowTokens)
            assertTrue(controller.send("second"))
            idle(controller)
            assertEquals(listOf("api-a", "api-b"), requests.map { it.first })
            assertTrue(descriptors.all { it.isEmpty() })
            val second = requests.last().second.messages.flatMap { it.parts }
            assertFalse(second.any { it is MessagePart.Reasoning })
            assertTrue(second.filterIsInstance<MessagePart.Text>().any { it.text == "first" })
            controller.newSession()
            idle(controller)
            assertEquals("a", controller.state.value.activeModelId)
            controller.loadSession(sessionId)
            idle(controller)
            assertEquals("b", controller.state.value.activeModelId)
        } finally { scope.cancel() }
    }

    @Test fun delegateUsesDefaultChildModelAndCannotReuseParentToolsOrConversation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AgentSessionStore(folder.root)
        val childRequests = CopyOnWriteArrayList<ai.koog.prompt.Prompt>()
        val childDescriptors = CopyOnWriteArrayList<List<ai.koog.agents.core.tools.ToolDescriptor>>()
        try {
            val controller = MaryController(RuntimeEnvironment.getApplication(), scope, fakeDeviceTools,
                preferenceSource(agentMode = true), store, connectionOverride = { provider ->
                    maryTestConnection(provider) { prompt, tools ->
                        if (provider.model == "api-b") {
                            childRequests += prompt
                            childDescriptors.add(tools)
                            emitText("Scoped result")
                        } else if (prompt.messages.flatMap { it.parts }.any { it is MessagePart.Tool.Result }) {
                            emitText("Combined result")
                        } else {
                            emit(StreamFrame.ToolCallComplete("child-1", MarySubagentEnvironment.NAME,
                                """{"task":"Check a single claim"}""", 0))
                            emit(StreamFrame.End("tool_calls"))
                        }
                    }
                })
            idle(controller)
            assertTrue(controller.send("Full parent conversation is private to this request"))
            idle(controller)
            val childText = childRequests.single().messages.flatMap { it.parts }.filterIsInstance<MessagePart.Text>().map { it.text }
            assertTrue(childText.contains("Check a single claim"))
            assertTrue(childText.none { it.contains("Full parent conversation") })
            val childToolNames = childDescriptors.single().map { it.name }.toSet()
            assertFalse("test_device_tool" in childToolNames)
            assertFalse(MarySubagentEnvironment.NAME in childToolNames)
            assertTrue(MarySubagentMailbox.NAME in childToolNames)
            assertTrue("workspace_list_files" in childToolNames)
            assertTrue("web_fetch" in childToolNames)
            val saved = store.load(checkNotNull(controller.sessionId))!!.messages
            assertTrue(saved.any { it.content?.contains("Scoped result") == true })
            assertTrue(saved.any { it.content == "Combined result" })
        } finally { scope.cancel() }
    }

    @Test fun watchedBackgroundCompletionWakesParentAfterFirstAnswerWithoutAddingUserTurn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AgentSessionStore(folder.root)
        val release = CompletableDeferred<Unit>()
        try {
            val controller = MaryController(RuntimeEnvironment.getApplication(), scope, fakeDeviceTools,
                preferenceSource(agentMode = true), store, resolveText = { "Subagent %1\$s: %2\$s" }, connectionOverride = { provider ->
                    maryTestConnection(provider) { prompt, _ ->
                        val parts = prompt.messages.flatMap { it.parts }
                        val results = parts.filterIsInstance<MessagePart.Tool.Result>()
                        if (provider.model == "api-b") {
                            release.await()
                            emitText("background finding")
                        } else if (parts.filterIsInstance<MessagePart.Text>().any { it.text.contains("background finding") }) {
                            emitText("resumed after watch")
                        } else if (results.any { it.id == "watch" }) {
                            emitText("first answer")
                        } else if (results.any { it.id == "start" }) {
                            emit(StreamFrame.ToolCallComplete("watch", MarySubagentEnvironment.WATCH,
                                results.first { it.id == "start" }.output, 0))
                            emit(StreamFrame.End("tool_calls"))
                        } else {
                            emit(StreamFrame.ToolCallComplete("start", MarySubagentEnvironment.START,
                                """{"task":"Find one thing"}""", 0))
                            emit(StreamFrame.End("tool_calls"))
                        }
                    }
                })
            idle(controller)
            assertTrue(controller.send("start a background task"))
            idle(controller)
            assertEquals(1, controller.state.value.runningSubagents)
            assertTrue(controller.state.value.entries.any { it is ChatEntry.AssistantMessage && it.text == "first answer" })
            release.complete(Unit)
            withTimeout(12_000) {
                while (controller.state.value.entries.none { it is ChatEntry.AssistantMessage && it.text == "resumed after watch" }) delay(10)
            }
            idle(controller)
            assertEquals(1, controller.state.value.entries.filterIsInstance<ChatEntry.UserMessage>().size)
            val saved = store.load(checkNotNull(controller.sessionId))!!.messages
            assertEquals(1, saved.count { it.name == MARY_RUNTIME_SENDER })
            assertEquals(1, MaryController.renderHistory(saved).filterIsInstance<ChatEntry.UserMessage>().size)
        } finally { scope.cancel() }
    }

    @Test fun stoppingIdleParentCancelsChildAndDisabledWebIsNotAdvertisedToEither() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val store = AgentSessionStore(folder.root)
        val childStarted = CompletableDeferred<Unit>()
        val childStopped = CompletableDeferred<Unit>()
        val toolLists = CopyOnWriteArrayList<List<String>>()
        try {
            val controller = MaryController(RuntimeEnvironment.getApplication(), scope, fakeDeviceTools,
                preferenceSource(agentMode = true, webEnabled = false), store, connectionOverride = { provider ->
                    maryTestConnection(provider) { prompt, tools ->
                        toolLists.add(tools.map { it.name })
                        if (provider.model == "api-b") {
                            childStarted.complete(Unit)
                            try { awaitCancellation() } finally { childStopped.complete(Unit) }
                        } else if (prompt.messages.flatMap { it.parts }.any { it is MessagePart.Tool.Result }) {
                            emitText("parent idle")
                        } else {
                            emit(StreamFrame.ToolCallComplete("child", MarySubagentEnvironment.START,
                                """{"task":"Wait for cancellation"}""", 0))
                            emit(StreamFrame.End("tool_calls"))
                        }
                    }
                })
            idle(controller)
            assertTrue(controller.send("start, do not wait"))
            withTimeout(12_000) { childStarted.await() }
            idle(controller)
            assertEquals(1, controller.state.value.runningSubagents)
            controller.cancel()
            withTimeout(12_000) { childStopped.await() }
            assertEquals(0, controller.state.value.runningSubagents)
            assertFalse(controller.state.value.running)
            assertTrue(toolLists.all { "web_search" !in it && "web_fetch" !in it })
        } finally { scope.cancel() }
    }

    private suspend fun idle(controller: MaryController) = withTimeout(12_000) {
        while (controller.state.value.running || controller.state.value.configuring) delay(10)
    }
}
