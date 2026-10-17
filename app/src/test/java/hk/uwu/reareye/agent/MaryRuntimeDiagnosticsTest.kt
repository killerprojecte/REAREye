package hk.uwu.reareye.agent

import android.util.Log
import hk.uwu.reareye.agent.data.AgentSessionStore
import hk.uwu.reareye.agent.data.AgentSettingsSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MaryRuntimeDiagnosticsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun runtimeLog() = ShadowLog.getLogsForTag("MaryRuntime").joinToString("\n") { it.msg }

    private fun failure(message: String) = IllegalArgumentException(message).apply {
        stackTrace =
            arrayOf(StackTraceElement("example.NativeFailure", "decode", "NativeFailure.kt", 42))
    }

    @Test
    fun logsTypesCauseSuppressedAndSourceLinesWithoutSensitiveMessages() {
        ShadowLog.clear()
        val root = IllegalStateException(
            "Bearer secret-key user prompt tool arguments response body",
            failure("api_key=password")
        )
        root.addSuppressed(UnsupportedOperationException("private-response"))
        logMaryRuntime(MaryRuntimePhase.ENGINE_FAILED, root, Protocol.RESPONSES)
        val text = runtimeLog()
        assertTrue(text.contains("phase=ENGINE_FAILED protocol=RESPONSES"))
        assertTrue(text.contains("type=java.lang.IllegalStateException"))
        assertTrue(text.contains("cause[1] type=java.lang.IllegalArgumentException"))
        assertTrue(text.contains("suppressed[1:0] type=java.lang.UnsupportedOperationException"))
        assertTrue(text.contains("example.NativeFailure.decode(NativeFailure.kt:42)"))
        listOf(
            "secret-key",
            "user prompt",
            "tool arguments",
            "response body",
            "password",
            "private-response"
        ).forEach {
            assertFalse(text, text.contains(it))
        }
        assertTrue(
            ShadowLog.getLogsForTag("MaryRuntime")
                .all { it.type == Log.ERROR && it.throwable == null })
    }

    @Test
    fun onlyExactGenericParameterCountMessageIsAllowed() {
        ShadowLog.clear()
        val allowed = "Class declares 0 type parameters, but 1 were provided"
        logMaryRuntime(MaryRuntimePhase.CONNECTION_INIT, failure(allowed))
        assertTrue(runtimeLog().contains("message=$allowed"))
        ShadowLog.clear()
        listOf(
            "$allowed\nBearer secret",
            "$allowed secret",
            "prefix $allowed",
            "Class declares secret type parameters, but 1 were provided"
        ).forEach {
            logMaryRuntime(MaryRuntimePhase.CONNECTION_INIT, failure(it))
        }
        assertFalse(runtimeLog().contains("message="))
        assertFalse(runtimeLog().contains("secret"))
    }

    @Test
    fun cancellationAndFailingLogSinkDoNotAffectExecution() {
        ShadowLog.clear()
        logMaryRuntime(MaryRuntimePhase.ENGINE_FAILED, CancellationException("secret"))
        assertTrue(ShadowLog.getLogsForTag("MaryRuntime").isEmpty())
        logMaryRuntime(
            MaryRuntimePhase.ENGINE_FAILED,
            failure("secret"),
            sink = { error("sink unavailable") })
    }

    @Test
    fun throwableCyclesAndLargeGraphsAreBounded() {
        val first = failure("first")
        val second = failure("second")
        first.initCause(second)
        second.initCause(first)
        val lines = mutableListOf<String>()
        logMaryRuntime(MaryRuntimePhase.ENGINE_FAILED, first, sink = lines::add)
        assertTrue(lines.any { it.contains("cycle/shared reference") })
        val large = failure("large")
        repeat(100) { large.addSuppressed(failure("secret-$it")) }
        lines.clear()
        logMaryRuntime(MaryRuntimePhase.ENGINE_FAILED, large, sink = lines::add)
        assertTrue(lines.size < 100)
        assertTrue(lines.any { it.contains("truncated") })
    }

    @Test
    fun connectionInitializationFailureIsLoggedAndStillShown() = runBlocking {
        ShadowLog.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val error = failure("Class declares 0 type parameters, but 1 were provided")
            val controller = controller(scope) { throw error }
            ready(controller)
            controller.send("private user prompt")
            ready(controller)
            assertTrue(runtimeLog().contains("phase=CONNECTION_INIT protocol=CHAT_COMPLETIONS"))
            assertTrue(runtimeLog().contains("NativeFailure.kt:42"))
            assertFalse(runtimeLog().contains("private user prompt"))
            assertTrue(
                controller.state.value.entries.filterIsInstance<ChatEntry.Notice>()
                    .any { it.text == error.message })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun engineFailedEventLogsOriginalCauseWithoutItsMessage() = runBlocking {
        ShadowLog.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val original = failure("secret native response")
            val controller = controller(scope) {
                maryTestConnection { _, _ ->
                    throw IllegalStateException(
                        "private wrapper body",
                        original
                    )
                }
            }
            ready(controller)
            controller.send("private user prompt")
            ready(controller)
            assertTrue(runtimeLog(), runtimeLog().contains("phase=ENGINE_FAILED"))
            assertTrue(runtimeLog().contains("type=java.lang.IllegalArgumentException"))
            assertTrue(runtimeLog().contains("NativeFailure.kt:42"))
            assertFalse(runtimeLog().contains("secret native response"))
            assertFalse(runtimeLog().contains("private wrapper body"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelledControllerTurnProducesNoRuntimeError() = runBlocking {
        ShadowLog.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var started = false
            val controller = controller(scope) {
                maryTestConnection { _, _ -> started = true; awaitCancellation() }
            }
            ready(controller)
            controller.send("cancel this")
            withTimeout(10_000) { while (!started) delay(10) }
            controller.newSession()
            ready(controller)
            assertTrue(runtimeLog(), ShadowLog.getLogsForTag("MaryRuntime").isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun connectionCleanupFailureIsLoggedWithoutInterruptingTurnCompletion() = runBlocking {
        ShadowLog.clear()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val controller = controller(scope) {
                maryTestConnection(onClose = { throw failure("private close detail") }) { _, _ ->
                    emitText(
                        "done"
                    )
                }
            }
            ready(controller)
            controller.send("hello")
            ready(controller)
            assertTrue(runtimeLog().contains("phase=CONNECTION_CLOSE"))
            assertFalse(runtimeLog().contains("private close detail"))
            assertTrue(
                controller.state.value.entries.filterIsInstance<ChatEntry.AssistantMessage>()
                    .any { it.text == "done" })
        } finally {
            scope.cancel()
        }
    }

    private fun controller(
        scope: CoroutineScope,
        connect: (ProviderConfig) -> hk.uwu.reareye.agent.provider.KoogConnection
    ): MaryController {
        val settings = object : AgentSettingsSource {
            override fun isReady() = true
            override fun load() = AgentConfig(provider = ProviderConfig(model = "fake"))
            override fun save(config: AgentConfig) = Unit
            override fun saveApiKey(key: String) = true
            override fun loadApiKey() = ""
            override fun isApiKeyUnreadable() = false
        }
        return MaryController(
            RuntimeEnvironment.getApplication(), scope, settings = settings,
            store = AgentSessionStore(temporary.root), connectionOverride = connect,
            resolveText = { "Context decision" },
        )
    }

    private suspend fun ready(controller: MaryController) {
        withTimeout(10_000) {
            while (controller.state.value.running || controller.state.value.configuring) delay(
                10
            )
        }
    }
}
