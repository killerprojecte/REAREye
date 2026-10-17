package hk.uwu.reareye.agent.provider

import ai.koog.prompt.Prompt
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningPolicy
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class ReasoningPolicyTest {
    private fun support(model: String, protocol: Protocol = Protocol.CHAT_COMPLETIONS) =
        ReasoningPolicy.support(ProviderConfig(model = model, protocol = protocol))

    @Test
    fun `capabilities are exact ordered and preserve fixed or unknown models`() {
        for (model in listOf(
            "gpt-4o",
            "custom",
            "gpt-5-chat-latest",
            "gpt-5-unknown",
            "deepseek-chat",
            "deepseek-reasoner",
            "deepseek-v9",
            "provider/gpt-5"
        )) {
            assertTrue(model, support(model).levels.isEmpty())
            assertNull(model, support(model).effectiveEffort(ReasoningEffort.MEDIUM))
        }
        assertEquals(
            listOf(
                ReasoningEffort.MINIMAL,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH
            ), support("openai/gpt-5-2025-08-07").levels
        )
        assertEquals(ReasoningEffort.HIGH, support("gpt-5").effectiveEffort(ReasoningEffort.MAX))
        assertEquals(
            ReasoningEffort.MINIMAL,
            support("gpt-5").effectiveEffort(ReasoningEffort.NONE)
        )
        assertEquals(
            listOf(
                ReasoningEffort.NONE,
                ReasoningEffort.LOW,
                ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH
            ), support("gpt-5.1").levels
        )
        assertTrue(support("gpt-5.2").levels.contains(ReasoningEffort.XHIGH))
        assertFalse(support("gpt-5.2").levels.contains(ReasoningEffort.MAX))
        for (model in listOf("deepseek-flash", "deepseek-v4-pro", "deepseek/deepseek-v4-flash")) {
            val policy = support(model)
            assertEquals(
                listOf(
                    ReasoningEffort.NONE,
                    ReasoningEffort.LOW,
                    ReasoningEffort.HIGH,
                    ReasoningEffort.MAX
                ), policy.levels
            )
            assertEquals(ReasoningEffort.HIGH, policy.effectiveEffort(ReasoningEffort.MEDIUM))
            assertEquals(ReasoningEffort.HIGH, policy.effectiveEffort(ReasoningEffort.XHIGH))
            assertEquals(ReasoningEffort.LOW, policy.effectiveEffort(ReasoningEffort.MINIMAL))
        }
        assertTrue(support("claude-opus-4-9", Protocol.ANTHROPIC).levels.isEmpty())
        assertTrue(support("claude-opus-4-6").levels.isEmpty())
        assertFalse(
            support(
                "claude-opus-4-5",
                Protocol.ANTHROPIC
            ).levels.contains(ReasoningEffort.MAX)
        )
        assertTrue(
            support("anthropic/claude-opus-4-8", Protocol.ANTHROPIC).levels.contains(
                ReasoningEffort.XHIGH
            )
        )
        for (model in listOf("claude-opus-4-1-20250805", "claude-sonnet-4-5", "claude-haiku-4-5")) {
            assertEquals(
                listOf(ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
                support(model, Protocol.ANTHROPIC).levels
            )
        }
    }

    @Test
    fun `current explicit model aliases expose reasoning without guessing unknown suffixes`() {
        val openaiLevels = listOf(
            ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
            ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX
        )
        for (model in listOf("gpt-6-luna", "openai/gpt-6-luna", "gpt-6-luna-2026-10-15")) {
            assertEquals(model, openaiLevels, support(model, Protocol.RESPONSES).levels)
        }
        val deepseekLevels = listOf(
            ReasoningEffort.NONE,
            ReasoningEffort.LOW,
            ReasoningEffort.HIGH,
            ReasoningEffort.MAX
        )
        for (protocol in Protocol.entries) {
            for (model in listOf("deepseek-v4.1-flash", "deepseek/deepseek-v4.1-flash")) {
                assertEquals(model, deepseekLevels, support(model, protocol).levels)
                assertEquals(
                    ReasoningEffort.HIGH,
                    support(model, protocol).effectiveEffort(ReasoningEffort.MEDIUM)
                )
            }
        }
        for (model in listOf("gpt-6-unknown", "gpt-6-luna-unknown", "deepseek-v4.1-unknown")) {
            assertTrue(model, support(model, Protocol.RESPONSES).levels.isEmpty())
        }
    }

    @Test
    fun `new model selections reach native Responses request encoding`() = runBlocking {
        for (model in listOf("gpt-6-luna", "deepseek-v4.1-flash")) {
            val body = request(
                ProviderConfig(
                    model = model, protocol = Protocol.RESPONSES,
                    reasoningEffort = ReasoningEffort.MAX, temperature = 0.7
                )
            )
            assertEquals(model, body["model"]!!.jsonPrimitive.content)
            assertEquals("max", body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("temperature"))
            assertFalse(body.containsKey("reasoning_effort"))
        }
    }

    @Test
    fun `old config snapshots decode with medium without migration`() {
        val config =
            Json.decodeFromString<ProviderConfig>("""{"model":"deepseek-flash","maxOutputTokens":256}""")
        assertEquals(ReasoningEffort.MEDIUM, config.reasoningEffort)
        assertEquals(
            ReasoningEffort.HIGH,
            ReasoningPolicy.support(config).effectiveEffort(config.reasoningEffort)
        )
        assertEquals(
            ReasoningEffort.MAX,
            Json.decodeFromString<ProviderConfig>(Json.encodeToString(config.copy(reasoningEffort = ReasoningEffort.MAX))).reasoningEffort
        )
    }

    private suspend fun request(
        config: ProviderConfig,
        actualMax: Int? = config.maxOutputTokens
    ): JsonObject {
        MockWebServer().use { server ->
            server.start()
            val events = when (config.protocol) {
                Protocol.CHAT_COMPLETIONS -> listOf(
                    """{"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}""",
                    "[DONE]"
                )

                Protocol.RESPONSES -> listOf("""{"type":"response.completed","response":{"usage":{"input_tokens":1,"output_tokens":1}}}""")
                Protocol.ANTHROPIC -> listOf(
                    """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}""",
                    """{"type":"message_stop"}"""
                )
            }
            server.enqueue(
                MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
                .body(events.joinToString("") { "data: ${nativeFixture(it)}\n\n" }).build()
            )
            ProviderFactory.connect(
                OpenAiCompatibleProvider.defaultClient(),
                config.copy(baseUrl = server.url("/v1").toString())
            ).use { connection ->
                val prompt = Prompt(
                    listOf(Message.User("hi", RequestMetaInfo.Empty)),
                    "reasoning-test",
                    connection.params.copy(maxTokens = actualMax)
                )
                connection.executor.executeStreaming(prompt, connection.model).toList()
            }
            return Json.parseToJsonElement(
                requireNotNull(
                    server.takeRequest(
                        5,
                        TimeUnit.SECONDS
                    )
                ).body!!.utf8()
            ).jsonObject
        }
    }

    @Test
    fun `OpenAI native encoded chat and responses contain protocol-specific effort`() =
        runBlocking {
            for (protocol in listOf(Protocol.CHAT_COMPLETIONS, Protocol.RESPONSES)) {
                val body = request(
                    ProviderConfig(
                        model = "gpt-5.2",
                        protocol = protocol,
                        reasoningEffort = ReasoningEffort.XHIGH,
                        temperature = 0.7
                    )
                )
                assertFalse(body.containsKey("temperature"))
                if (protocol == Protocol.RESPONSES) {
                    assertEquals(
                        "xhigh",
                        body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content
                    )
                    assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
                    assertTrue(body["include"]!!.jsonArray.any { it.jsonPrimitive.content == "reasoning.encrypted_content" })
                    assertFalse(body.containsKey("reasoning_effort"))
                } else {
                    assertEquals("xhigh", body["reasoning_effort"]!!.jsonPrimitive.content)
                    assertFalse(body.containsKey("reasoning"))
                }
            }
        }

    @Test
    fun `DeepSeek default medium maps actual high and none disables each protocol`() = runBlocking {
        for (protocol in Protocol.entries) {
            for (effort in listOf(
                ReasoningEffort.MEDIUM,
                ReasoningEffort.NONE,
                ReasoningEffort.MAX
            )) {
                val body = request(
                    ProviderConfig(
                        model = "deepseek-flash",
                        protocol = protocol,
                        reasoningEffort = effort,
                        temperature = 0.6
                    )
                )
                val wire = when (effort) {
                    ReasoningEffort.MEDIUM -> "high"; ReasoningEffort.NONE -> "none"; else -> "max"
                }
                if (protocol == Protocol.RESPONSES) assertEquals(
                    wire,
                    body["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content
                )
                else {
                    assertEquals(
                        if (effort == ReasoningEffort.NONE) "disabled" else "enabled",
                        body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content
                    )
                    if (effort != ReasoningEffort.NONE) {
                        val encoded =
                            if (protocol == Protocol.ANTHROPIC) body["output_config"]!!.jsonObject["effort"] else body["reasoning_effort"]
                        assertEquals(wire, encoded!!.jsonPrimitive.content)
                    } else assertFalse(body.containsKey("reasoning_effort"))
                }
                if (effort != ReasoningEffort.NONE) assertFalse(body.containsKey("temperature"))
            }
        }
    }

    @Test
    fun `Claude adaptive models never send a manual budget`() = runBlocking {
        for (model in listOf(
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            "claude-opus-4-7",
            "claude-opus-4-8"
        )) {
            val body = request(
                ProviderConfig(
                    model = model,
                    protocol = Protocol.ANTHROPIC,
                    temperature = 0.5,
                    reasoningEffort = ReasoningEffort.MAX
                )
            )
            assertEquals(buildJsonObject { put("type", "adaptive") }, body["thinking"])
            assertEquals(
                "max",
                body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content
            )
            assertFalse(body.containsKey("temperature"))
        }
    }

    @Test
    fun `Claude manual budgets use actual request output ceiling including approval overrides`() =
        runBlocking {
            for (model in listOf(
                "claude-opus-4-1",
                "claude-sonnet-4-5",
                "claude-haiku-4-5",
                "claude-opus-4-5"
            )) {
                val config = ProviderConfig(
                    model = model,
                    protocol = Protocol.ANTHROPIC,
                    maxOutputTokens = 10000,
                    reasoningEffort = ReasoningEffort.HIGH,
                    temperature = 0.6
                )
                for (maximum in listOf(256, 512, 1024, 1025, 2048, 10000)) {
                    val body = request(config, maximum)
                    if (maximum <= 1024) assertFalse(body.containsKey("thinking"))
                    else {
                        val thinking = body["thinking"]!!.jsonObject
                        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
                        assertTrue(thinking["budget_tokens"]!!.jsonPrimitive.int in 1024 until maximum)
                        assertFalse(body.containsKey("temperature"))
                    }
                    if (model == "claude-opus-4-5") assertEquals(
                        "high",
                        body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content
                    )
                    else assertFalse(body.containsKey("output_config"))
                }
            }
        }

    @Test
    fun `ordinary unknown and fixed models get no reasoning configuration`() = runBlocking {
        for ((model, protocol) in listOf(
            "gpt-4o" to Protocol.RESPONSES,
            "deepseek-reasoner" to Protocol.CHAT_COMPLETIONS,
            "deepseek-chat" to Protocol.CHAT_COMPLETIONS,
            "custom" to Protocol.ANTHROPIC,
            "claude-opus-9" to Protocol.ANTHROPIC
        )) {
            val body = request(ProviderConfig(model = model, protocol = protocol))
            assertFalse(body.containsKey("thinking"))
            assertFalse(body.containsKey("reasoning"))
            assertFalse(body.containsKey("reasoning_effort"))
            assertFalse(body.containsKey("output_config"))
        }
    }
}
