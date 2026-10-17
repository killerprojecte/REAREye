package hk.uwu.reareye.agent

import ai.koog.prompt.message.MessagePart
import hk.uwu.reareye.agent.data.AgentSessionStore
import hk.uwu.reareye.agent.data.AgentSettingsSource
import hk.uwu.reareye.agent.data.MarySettings
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.model.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/** Actual SSE -> native Koog graph -> controller -> persisted session, without executor fakes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MaryControllerProtocolTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun responsesTextSurvivesReloadAndReachesTheNextNativeRequest() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(response("First native answer"))
            server.enqueue(response("Second native answer"))
            val config = AgentConfig(
                provider = ProviderConfig(
                    baseUrl = server.url("/proxy/v1").toString(),
                    model = "test", apiKey = "test-key", protocol = Protocol.RESPONSES
                )
            )
            val settings = object : AgentSettingsSource {
                override fun isReady() = true
                override fun load() = config
                override fun loadMarySettings() = MarySettings(configuration = config)
                override fun save(config: AgentConfig) = Unit
                override fun saveApiKey(key: String) = true
                override fun loadApiKey() = "test-key"
                override fun isApiKeyUnreadable() = false
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val store = AgentSessionStore(temporary.root)
                val controller = MaryController(
                    RuntimeEnvironment.getApplication(),
                    scope,
                    settings = settings,
                    store = store
                )
                eventually { !controller.state.value.running }
                controller.send("First request")
                eventually { !controller.state.value.running }
                val saved = store.load(controller.sessionId!!)!!
                assertFalse(controller.state.value.entries.toString(), saved.interrupted)
                assertEquals("First native answer", saved.messages.last().content)
                assertNotNull(saved.messages.last().nativeEncoding)
                assertEquals("First native answer", saved.contextMessages!!.last().content)
                val first = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertEquals("/proxy/v1/responses", first.url.encodedPath)
                assertEquals("Bearer test-key", first.headers["Authorization"])
                controller.loadSession(saved.id)
                eventually { !controller.state.value.running }
                assertEquals(
                    "First native answer",
                    (controller.state.value.entries.last() as ChatEntry.AssistantMessage).text
                )
                controller.send("Continue")
                eventually { !controller.state.value.running }
                val second = server.takeRequest(5, TimeUnit.SECONDS)!!
                assertTrue(second.body!!.utf8().contains("First native answer"))
                val final = store.load(saved.id)!!
                assertFalse(controller.state.value.entries.toString(), final.interrupted)
                assertEquals(2, final.messages.count { it.role == MessageRole.ASSISTANT })
                assertEquals("Second native answer", final.messages.last().content)
                assertEquals(2, server.requestCount)
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun anthropicSignedThinkingSurvivesAskToolAndDurableRecovery() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            fun sse(vararg data: String) =
                MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
                    .body(data.joinToString("") { "data: $it\n\n" }).build()

            val start =
                """{"type":"message_start","message":{"id":"msg_a","type":"message","role":"assistant","model":"test","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":100,"output_tokens":0}}}"""
            server.enqueue(
                sse(
                    start,
                    """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Ask the user"}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"signed-"}}""",
                    """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"state"}}""",
                    """{"type":"content_block_stop","index":0}""",
                    """{"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"ask_1","name":"Ask","input":{}}}""",
                    """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"question\":\"Choose\",\"option_ids\":[\"a\",\"b\"],\"option_labels\":[\"A\",\"B\"]}"}}""",
                    """{"type":"content_block_stop","index":1}""",
                    """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":20}}""",
                    """{"type":"message_stop"}"""
                )
            )
            server.enqueue(
                sse(
                    start,
                    """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":"Acknowledged"}}""",
                    """{"type":"content_block_stop","index":0}""",
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
                    """{"type":"message_stop"}"""
                )
            )
            val config = AgentConfig(
                provider = ProviderConfig(
                    baseUrl = server.url("/v1").toString(),
                    model = "test", apiKey = "test-key", protocol = Protocol.ANTHROPIC
                )
            )
            val settings = object : AgentSettingsSource {
                override fun isReady() = true
                override fun load() = config
                override fun loadMarySettings() = MarySettings(configuration = config)
                override fun save(config: AgentConfig) = Unit
                override fun saveApiKey(key: String) = true
                override fun loadApiKey() = "test-key"
                override fun isApiKeyUnreadable() = false
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                val store = AgentSessionStore(temporary.root)
                val controller = MaryController(
                    RuntimeEnvironment.getApplication(),
                    scope,
                    settings = settings,
                    store = store
                )
                eventually { !controller.state.value.running }
                controller.send("Ask before proceeding")
                eventually { controller.state.value.pendingAsk != null || !controller.state.value.running }
                assertTrue(
                    controller.state.value.entries.toString(),
                    controller.state.value.pendingAsk != null
                )
                controller.answerAsk(controller.state.value.pendingAsk!!.id, listOf("a"))
                eventually { !controller.state.value.running }
                val saved = store.load(controller.sessionId!!)!!
                assertFalse(controller.state.value.entries.toString(), saved.interrupted)
                assertEquals("Acknowledged", saved.messages.last().content)
                val native = KoogTranscriptCodec.toNative(saved.messages)
                val reasoning =
                    native.flatMap { it.parts }.filterIsInstance<MessagePart.Reasoning>().single()
                assertEquals("signed-state", reasoning.encrypted)
                assertEquals(listOf("Ask the user"), reasoning.content)
                assertEquals(
                    1,
                    native.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().size
                )
                server.takeRequest(5, TimeUnit.SECONDS)!!
                val continuation = server.takeRequest(5, TimeUnit.SECONDS)!!.body!!.utf8()
                assertTrue(continuation.contains("signed-state"))
                assertTrue(continuation.contains("tool_result"))
                assertTrue(continuation.contains("ask_1"))
            } finally {
                scope.cancel()
            }
        }
    }

    private fun response(text: String): MockResponse {
        val message =
            """{"type":"message","id":"msg_test","role":"assistant","status":"completed","content":[{"type":"output_text","text":"$text","annotations":[]}]}"""
        val data = listOf(
            """{"type":"response.output_text.delta","sequence_number":1,"output_index":0,"item_id":"msg_test","content_index":0,"delta":"$text"}""",
            """{"type":"response.output_item.done","sequence_number":2,"output_index":0,"item":$message}""",
            """{"type":"response.completed","sequence_number":3,"response":{"id":"resp_test","created_at":1,"model":"test","status":"completed","output":[$message],"parallel_tool_calls":false,"text":{"format":{"type":"text"}},"usage":{"input_tokens":100,"output_tokens":5,"total_tokens":105,"input_tokens_details":{"cached_tokens":80},"output_tokens_details":{"reasoning_tokens":0}}}}""",
        )
        return MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
            .body(data.joinToString("") { "data: $it\n\n" }).build()
    }

    private suspend fun eventually(condition: () -> Boolean) = withTimeout(15_000) {
        while (!condition()) delay(10)
    }
}
