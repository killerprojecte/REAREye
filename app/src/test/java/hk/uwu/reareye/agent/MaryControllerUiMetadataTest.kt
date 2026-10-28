package hk.uwu.reareye.agent

import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import android.content.res.Resources
import hk.uwu.reareye.agent.context.ContextBudget
import hk.uwu.reareye.agent.data.AgentSessionStore
import hk.uwu.reareye.agent.data.AgentSettingsSource
import hk.uwu.reareye.agent.data.MarySettings
import hk.uwu.reareye.agent.interaction.ApprovalMode
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.agent.provider.KoogConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MaryControllerUiMetadataTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private class Settings : AgentSettingsSource {
        @Volatile
        var value = MarySettings(
            configuration = AgentConfig(
                provider = ProviderConfig(
                    model = "gpt-5.2",
                    apiKey = "secret",
                    contextWindowTokens = 64000
                )
            ),
            approvalMode = ApprovalMode.ASK,
            inheritApprovalModel = false,
            approvalProvider = ProviderConfig(model = "reviewer", apiKey = "review-secret"),
        )
        @Volatile
        var failure = false
        @Volatile
        var writes = 0
        @Volatile
        var saveFailure = false
        var beforeLoad: () -> Unit = {}
        var beforeSave: () -> Unit = {}
        override fun isReady() = true
        override fun load(): AgentConfig {
            check(!failure) { "Unreadable configuration" }
            return value.configuration
        }

        override fun loadMarySettings(): MarySettings {
            beforeLoad(); load(); return value
        }

        override fun save(config: AgentConfig) {
            updateMarySettings { it.copy(configuration = config) }
        }

        override fun updateMarySettings(update: (MarySettings) -> MarySettings): MarySettings =
            synchronized(this) {
                val next = update(loadMarySettings())
                beforeSave()
                check(!saveFailure) { "Save failed" }
                value = next
                writes++
                next
            }

        override fun saveApiKey(key: String): Boolean =
            error("Effort selection must not rewrite keys")

        override fun loadApiKey() = "secret"
        override fun isApiKeyUnreadable() = false
    }

    private fun controller(
        scope: CoroutineScope, settings: Settings, store: AgentSessionStore,
        connection: (ProviderConfig) -> KoogConnection = { config ->
            maryTestConnection(config) { _, _ ->
                emitText(
                    "Done"
                )
            }
        },
    ) = MaryController(
        RuntimeEnvironment.getApplication(), scope, settings = settings, store = store,
        connectionOverride = connection, resolveText = { "Context decision" })

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(10000) {
        while (!condition()) delay(10)
    }

    private suspend fun ready(controller: MaryController) = eventually {
        !controller.state.value.running && !controller.state.value.configuring
    }

    // SDK 28 uses system Resources, not the app's UI locale (matching the production prefix).
    private fun languageInstruction(): String {
        val tags = Resources.getSystem().configuration.locales.toLanguageTags().ifBlank { "en" }
        return "\n\nDevice system preferred languages (BCP-47, not the app UI language): $tags. " +
                "Reply in the first device system language by default, including its writing system and regional variant. " +
                "If the user explicitly requests another output language, follow that request."
    }

    private fun assertComposition(state: AgentUiState) {
        val composition = requireNotNull(state.contextComposition)
        assertEquals(composition.totalTokens.toLong(), composition.segments.sumOf { it.tokens.toLong() })
        assertEquals(composition.totalTokens.toLong(), composition.categories.sumOf { it.tokens.toLong() })
        assertEquals(composition.totalTokens + state.contextBudgetCorrectionTokens, state.estimatedInputTokens)
    }

    @Test
    fun restoresConfiguredWindowAndEstimatesCompressedContextWithoutUiNotices() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings()
            val store = AgentSessionStore(temporary.root)
            val compact = listOf(ChatMessage(MessageRole.USER, "Summary"))
            val restored = store.create().copy(
                messages = listOf(ChatMessage(MessageRole.USER, "Long history ".repeat(1000))),
                contextMessages = compact, interrupted = true,
            )
            store.save(restored)
            val controller = controller(scope, settings, store)
            ready(controller)
            assertEquals(restored.id, controller.sessionId)
            assertEquals(64000, controller.state.value.contextWindowTokens)
            assertComposition(controller.state.value)
            assertEquals("", controller.state.value.provider.apiKey)
            assertEquals("gpt-5.2", controller.state.value.provider.model)
            assertTrue(controller.state.value.entries.any { it is ChatEntry.Notice })
            assertEquals(
                ContextBudget.estimateInput(
                    settings.load().systemPrompt + languageInstruction(),
                    emptyList(),
                    compact
                ),
                controller.state.value.estimatedInputTokens
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun sessionChangesClearServerUsageAndPreserveConfiguredMetadata() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings()
            val store = AgentSessionStore(temporary.root)
            val other = store.create()
            val controller = controller(scope, settings, store) { config ->
                maryTestConnection(config) { _, _ ->
                    emit(StreamFrame.TextDelta("Done"))
                    emit(StreamFrame.TextComplete("Done"))
                    emit(
                        StreamFrame.End(
                            "stop",
                            ResponseMetaInfo.Empty.copy(
                                inputTokensCount = 123,
                                outputTokensCount = 4
                            )
                        )
                    )
                }
            }
            ready(controller)
            controller.send("First")
            ready(controller)
            assertEquals(123, controller.state.value.usage?.inputTokens)
            controller.loadSession(other.id)
            ready(controller)
            assertNull(controller.state.value.usage)
            assertEquals(64000, controller.state.value.contextWindowTokens)
            assertComposition(controller.state.value)
            assertEquals("gpt-5.2", controller.state.value.provider.model)
            controller.newSession()
            ready(controller)
            assertNull(controller.state.value.usage)
            assertNotNull(controller.state.value.estimatedInputTokens)
            assertComposition(controller.state.value)
            assertEquals(64000, controller.state.value.contextWindowTokens)
            assertComposition(controller.state.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun effortSavePreservesApprovalAndCredentialsAndNextTurnUsesSelection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings()
            val before = settings.value
            var used: ProviderConfig? = null
            val controller =
                controller(scope, settings, AgentSessionStore(temporary.root)) { config ->
                    used = config
                    maryTestConnection(config) { _, _ -> emitText("Done") }
                }
            ready(controller)
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            ready(controller)
            assertEquals(1, settings.writes)
            assertEquals(
                before.copy(
                    configuration = before.configuration.copy(
                        provider = before.configuration.provider.copy(reasoningEffort = ReasoningEffort.HIGH)
                    )
                ), settings.value
            )
            assertEquals("", controller.state.value.provider.apiKey)
            controller.send("Use the selected level")
            ready(controller)
            assertEquals(ReasoningEffort.HIGH, used?.reasoningEffort)
            assertEquals("secret", used?.apiKey)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun configuringQueuesRapidChangesAndBlocksSendUntilLastDurableSave() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val settings = Settings()
            var requests = 0
            val controller =
                controller(scope, settings, AgentSessionStore(temporary.root)) { config ->
                    requests++
                    maryTestConnection(config) { _, _ -> emitText("Done") }
                }
            ready(controller)
            settings.beforeSave = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(controller.state.value.configuring)
            assertNull(controller.state.value.contextComposition)
            assertNull(controller.state.value.estimatedInputTokens)
            assertNull(controller.state.value.contextComposition)
            assertEquals(0, controller.state.value.contextBudgetCorrectionTokens)
            controller.setReasoningEffort(ReasoningEffort.LOW)
            controller.send("Too early")
            assertEquals(0, requests)
            release.countDown()
            ready(controller)
            assertEquals(ReasoningEffort.LOW, settings.load().provider.reasoningEffort)
            assertEquals(ReasoningEffort.LOW, controller.state.value.provider.reasoningEffort)
            assertEquals(2, settings.writes)
            controller.send("Now ready")
            ready(controller)
            assertEquals(1, requests)
        } finally {
            release.countDown(); scope.cancel()
        }
    }

    @Test
    fun refreshPendingStillQueuesEveryEffortAndApprovalWithoutLosingFields() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val settings = Settings()
            val before = settings.value
            val controller = controller(scope, settings, AgentSessionStore(temporary.root))
            ready(controller)
            settings.beforeLoad = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            controller.refreshConfiguration()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            controller.setApprovalMode(ApprovalMode.AUTO)
            controller.setReasoningEffort(ReasoningEffort.LOW)
            controller.setApprovalMode(ApprovalMode.ASK)
            release.countDown()
            ready(controller)
            assertEquals(4, settings.writes)
            assertEquals(
                before.copy(
                    configuration = before.configuration.copy(
                        provider = before.configuration.provider.copy(reasoningEffort = ReasoningEffort.LOW)
                    )
                ), settings.value
            )
            assertEquals(ReasoningEffort.LOW, controller.state.value.provider.reasoningEffort)
            assertEquals(ApprovalMode.ASK, controller.state.value.approvalMode)
            assertFalse(controller.state.value.entries.any { it is ChatEntry.Notice && it.isError })
        } finally {
            release.countDown(); scope.cancel()
        }
    }

    @Test
    fun failedSelectionsKeepDurableValuesAndCanBeRetried() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings()
            val controller = controller(scope, settings, AgentSessionStore(temporary.root))
            ready(controller)
            controller.setReasoningEffort(ReasoningEffort.LOW)
            ready(controller)
            settings.saveFailure = true
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            controller.setApprovalMode(ApprovalMode.AUTO)
            ready(controller)
            assertEquals(ReasoningEffort.LOW, settings.value.configuration.provider.reasoningEffort)
            assertEquals(ReasoningEffort.LOW, controller.state.value.provider.reasoningEffort)
            assertEquals(ApprovalMode.ASK, controller.state.value.approvalMode)
            assertTrue(controller.state.value.entries.any { it is ChatEntry.Notice && it.isError })
            settings.saveFailure = false
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            controller.setApprovalMode(ApprovalMode.AUTO)
            ready(controller)
            assertEquals(
                ReasoningEffort.HIGH,
                settings.value.configuration.provider.reasoningEffort
            )
            assertEquals(ApprovalMode.AUTO, settings.value.approvalMode)
            assertEquals(ApprovalMode.AUTO, controller.state.value.approvalMode)
            val restored = this@MaryControllerUiMetadataTest.controller(
                scope,
                settings,
                AgentSessionStore(temporary.newFolder())
            )
            ready(restored)
            assertEquals(ApprovalMode.AUTO, restored.state.value.approvalMode)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun unknownUnsupportedRunningAndLockedSelectionsAreIgnored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings()
            val store = AgentSessionStore(temporary.root)
            val controller = controller(scope, settings, store) { config ->
                maryTestConnection(config) { _, _ ->
                    emit(StreamFrame.TextDelta("Waiting"))
                    awaitCancellation()
                }
            }
            ready(controller)
            controller.setReasoningEffort(ReasoningEffort.MAX) // gpt-5.2 does not offer MAX.
            assertEquals(0, settings.writes)
            controller.send("Wait")
            eventually { controller.state.value.entries.any { it is ChatEntry.AssistantMessage } }
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            controller.setApprovalMode(ApprovalMode.AUTO)
            assertEquals(ApprovalMode.ASK, controller.state.value.approvalMode)
            assertEquals(0, settings.writes)
            controller.lockSession()
            ready(controller)
            assertTrue(controller.state.value.locked)
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            controller.setApprovalMode(ApprovalMode.AUTO)
            assertEquals(ApprovalMode.ASK, settings.value.approvalMode)
            assertEquals(0, settings.writes)
            controller.newSession()
            ready(controller)
            settings.value = settings.value.copy(
                configuration = settings.load()
                    .copy(provider = ProviderConfig(model = "custom-unknown"))
            )
            controller.refreshConfiguration()
            ready(controller)
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            assertEquals(0, settings.writes)
            assertFalse(controller.state.value.configuring)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pendingHumanApprovalCannotChangeTheTurnPolicyOrPersistANewPolicy() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings()
            val tools = object : ToolEnvironment {
                override fun availableTools() =
                    listOf(ToolSpec("write", "write", risk = ToolRisk.MUTATING))

                override suspend fun execute(toolName: String, args: JsonObject) =
                    ToolResult.ok("written")
            }
            var calls = 0
            val controller = MaryController(
                RuntimeEnvironment.getApplication(),
                scope,
                tools = tools,
                settings = settings,
                store = AgentSessionStore(temporary.root),
                connectionOverride = { config ->
                    maryTestConnection(config) { _, _ ->
                        if (calls++ == 0) {
                            emit(StreamFrame.ToolCallComplete("write_call", "write", "{}", 0))
                            emit(StreamFrame.End("tool_calls"))
                        } else emitText("Done")
                    }
                },
                resolveText = { "Context decision" })
            ready(controller)
            controller.send("Write")
            eventually { controller.state.value.pendingApproval != null }
            val pending = controller.state.value.pendingApproval
            controller.setApprovalMode(ApprovalMode.AUTO)
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            assertEquals(0, settings.writes)
            assertEquals(ApprovalMode.ASK, settings.value.approvalMode)
            assertEquals(ApprovalMode.ASK, controller.state.value.approvalMode)
            assertSame(pending, controller.state.value.pendingApproval)
            controller.answerApproval(true)
            ready(controller)
            assertEquals(ApprovalMode.ASK, settings.value.approvalMode)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun configurationFailureDoesNotCrashAndCanRecoverOnRefresh() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings().also { it.failure = true }
            val controller = controller(scope, settings, AgentSessionStore(temporary.root))
            ready(controller)
            assertNull(controller.state.value.estimatedInputTokens)
            assertNull(controller.state.value.contextComposition)
            assertEquals(0, controller.state.value.contextBudgetCorrectionTokens)
            assertEquals("", controller.state.value.provider.apiKey)
            controller.setReasoningEffort(ReasoningEffort.HIGH)
            assertEquals(0, settings.writes)
            settings.failure = false
            controller.refreshConfiguration()
            ready(controller)
            assertNotNull(controller.state.value.estimatedInputTokens)
            assertComposition(controller.state.value)
            settings.failure = true
            controller.refreshConfiguration()
            ready(controller)
            assertNull(controller.state.value.estimatedInputTokens)
            assertNull(controller.state.value.contextComposition)
            assertEquals(0, controller.state.value.contextBudgetCorrectionTokens)
            assertFalse(controller.state.value.configuring)
        } finally {
            scope.cancel()
        }
    }
    @Test
    fun modelRefreshDuringTurnNeverPublishesOldRequestUnderNewCapacity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings()
            val finish = kotlinx.coroutines.CompletableDeferred<Unit>()
            val controller = controller(scope, settings, AgentSessionStore(temporary.root)) { config ->
                maryTestConnection(config) { _, _ ->
                    emit(StreamFrame.TextDelta("Waiting"))
                    finish.await()
                    emit(StreamFrame.TextComplete("Waiting"))
                    emit(StreamFrame.End("stop", ResponseMetaInfo.Empty.copy(inputTokensCount = 999)))
                }
            }
            ready(controller)
            controller.send("Original model")
            eventually { controller.state.value.entries.any { it is ChatEntry.AssistantMessage } }
            assertComposition(controller.state.value)
            settings.value = settings.value.copy(configuration = settings.load().copy(
                provider = settings.load().provider.copy(model = "changed-model", contextWindowTokens = 128000)))
            controller.refreshConfiguration()
            eventually { !controller.state.value.configuring }
            assertEquals("changed-model", controller.state.value.provider.model)
            assertEquals(128000, controller.state.value.contextWindowTokens)
            assertNull(controller.state.value.contextComposition)
            assertNull(controller.state.value.usage)
            finish.complete(Unit)
            ready(controller)
            assertNull(controller.state.value.usage)
            assertComposition(controller.state.value)
            assertEquals(0, controller.state.value.contextBudgetCorrectionTokens)
        } finally { scope.cancel() }
    }

    @Test
    fun cancelRebuildsCommittedCompositionWithoutOldRunCalibration() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings()
            val controller = controller(scope, settings, AgentSessionStore(temporary.root)) { config ->
                maryTestConnection(config) { _, _ ->
                    emit(StreamFrame.TextDelta("Unfinished"))
                    awaitCancellation()
                }
            }
            ready(controller)
            controller.send("Cancel this request")
            eventually { controller.state.value.entries.any { it is ChatEntry.AssistantMessage } }
            assertComposition(controller.state.value)
            controller.cancel()
            ready(controller)
            assertComposition(controller.state.value)
            assertEquals(0, controller.state.value.contextBudgetCorrectionTokens)
            controller.newSession()
            ready(controller)
            assertComposition(controller.state.value)
            assertNull(controller.state.value.usage)
        } finally { scope.cancel() }
    }

    @Test
    fun apiCalibrationRemainsSeparateFromPayloadSlicesOnNextRequest() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val settings = Settings().also { it.value = it.value.copy(approvalMode = ApprovalMode.ALLOW_ALL) }
            lateinit var controller: MaryController
            var firstProviderInput: Int? = null
            val tools = object : ToolEnvironment {
                override fun availableTools() = listOf(ToolSpec("read", "Read a fixture", risk = ToolRisk.READ_ONLY))
                override suspend fun execute(toolName: String, args: JsonObject): ToolResult {
                    // Hold the tool loop until the UI collector has published the first usage.
                    eventually { controller.state.value.usage?.inputTokens == 12000 }
                    firstProviderInput = controller.state.value.usage?.inputTokens
                    return ToolResult.ok("Fixture")
                }
            }
            var calls = 0
            controller = MaryController(RuntimeEnvironment.getApplication(), scope,
                tools = tools, settings = settings, store = AgentSessionStore(temporary.root),
                connectionOverride = { config -> maryTestConnection(config) { _, _ ->
                    if (calls++ == 0) {
                        emit(StreamFrame.ToolCallComplete("read_call", "read", "{}", 0))
                        emit(StreamFrame.End("tool_calls", ResponseMetaInfo.Empty.copy(inputTokensCount = 12000)))
                    } else {
                        emit(StreamFrame.TextDelta("Waiting"))
                        awaitCancellation()
                    }
                } }, resolveText = { "Context decision" })
            ready(controller)
            controller.send("Read fixture")
            eventually { calls >= 2 && controller.state.value.entries.any { it is ChatEntry.AssistantMessage } }
            val state = controller.state.value
            assertComposition(state)
            assertTrue(state.contextBudgetCorrectionTokens > 0)
            assertEquals(12000, firstProviderInput)
            // Existing per-request TurnStarted clears API counters, not the safety correction.
            assertNull(state.usage)
            assertTrue(requireNotNull(state.contextComposition).totalTokens < requireNotNull(state.estimatedInputTokens))
            controller.cancel()
            ready(controller)
            assertComposition(controller.state.value)
            assertEquals(0, controller.state.value.contextBudgetCorrectionTokens)
        } finally { scope.cancel() }
    }

    @Test
    fun modelPublishClearsBudgetRewrittenWhileSettingsLoadWasPending() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val entered = CountDownLatch(1)
        val releaseSettings = CountDownLatch(1)
        try {
            val settings = Settings().also { it.value = it.value.copy(approvalMode = ApprovalMode.ALLOW_ALL) }
            val releaseFirstResponse = kotlinx.coroutines.CompletableDeferred<Unit>()
            val tools = object : ToolEnvironment {
                override fun availableTools() = listOf(ToolSpec("read", "Fixture", risk = ToolRisk.READ_ONLY))
                override suspend fun execute(toolName: String, args: JsonObject) = ToolResult.ok("Result")
            }
            var calls = 0
            val controller = MaryController(RuntimeEnvironment.getApplication(), scope,
                tools = tools, settings = settings, store = AgentSessionStore(temporary.root),
                connectionOverride = { config -> maryTestConnection(config) { _, _ ->
                    if (calls++ == 0) {
                        releaseFirstResponse.await()
                        emit(StreamFrame.ToolCallComplete("read_call", "read", "{}", 0))
                        emit(StreamFrame.End("tool_calls"))
                    } else {
                        emit(StreamFrame.TextDelta("Waiting"))
                        awaitCancellation()
                    }
                } }, resolveText = { "Context decision" })
            ready(controller)
            controller.send("Original request")
            eventually { calls == 1 }
            settings.value = settings.value.copy(configuration = settings.load().copy(
                provider = settings.load().provider.copy(model = "new-model", contextWindowTokens = 128000)))
            settings.beforeLoad = { entered.countDown(); check(releaseSettings.await(10, TimeUnit.SECONDS)) }
            controller.refreshConfiguration()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertNull(controller.state.value.estimatedInputTokens)
            releaseFirstResponse.complete(Unit)
            eventually { calls >= 2 }
            // The old request can check its next tool-loop budget while configuration IO waits.
            assertTrue(controller.state.value.configuring)
            assertNotNull(controller.state.value.estimatedInputTokens)
            releaseSettings.countDown()
            eventually { !controller.state.value.configuring }
            assertEquals("new-model", controller.state.value.provider.model)
            assertNull(controller.state.value.estimatedInputTokens)
            assertNull(controller.state.value.contextComposition)
            assertEquals(0, controller.state.value.contextBudgetCorrectionTokens)
            controller.cancel()
            ready(controller)
            assertComposition(controller.state.value)
        } finally { releaseSettings.countDown(); scope.cancel() }
    }

}
