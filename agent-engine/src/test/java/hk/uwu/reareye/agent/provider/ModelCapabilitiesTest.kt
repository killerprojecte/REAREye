package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.CapabilityScope
import hk.uwu.reareye.agent.CapabilitySource
import hk.uwu.reareye.agent.ModelCapabilityParser
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningCapability
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningMapping
import hk.uwu.reareye.agent.ReasoningPolicy
import hk.uwu.reareye.agent.ReasoningStatus
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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

class ModelCapabilitiesTest {
    private val config = ProviderConfig("https://example.invalid/v1", "private-test-key", "custom")
    private fun parse(extension: String, settings: ProviderConfig = config) =
        ModelCapabilityParser.parse(
            Json.parseToJsonElement("""{"id":"${settings.model}",$extension}""").jsonObject,
            settings,
        ).reasoning

    private fun manual(
        settings: ProviderConfig = config,
        mapping: ReasoningMapping = ReasoningMapping.OPENAI_EFFORT,
        levels: List<ReasoningEffort> = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH)
    ) = ReasoningCapability(
        ReasoningStatus.SUPPORTED,
        levels,
        mapping,
        CapabilityScope.from(settings),
        CapabilitySource.MANUAL,
    )

    @Test
    fun `ordinary model metadata and coarse boolean never invent levels`() {
        for (extra in listOf(
            "\"reasoning\":true", "\"supported_parameters\":[\"reasoning\"]",
            "\"reasoning_capabilities\":{\"supported\":true}",
            "\"reasoning_capabilities\":{\"supported\":true,\"levels\":[\"low\"]}"
        )) {
            val capability = parse(extra)!!
            assertEquals(ReasoningStatus.UNKNOWN, capability.status)
            assertTrue(capability.levels.isEmpty()); assertNull(capability.mapping)
            assertEquals(true, capability.supportsReasoning)
        }
        assertEquals(ReasoningStatus.UNKNOWN, ReasoningPolicy.support(config).status)
    }

    @Test
    fun `explicit unsupported overrides known offline capability but manual wins`() {
        val known = config.copy(model = "gpt-5")
        val unsupported = parse("\"reasoning_capabilities\":{\"supported\":false}", known)!!
        val automatic = known.copy(reasoningMetadata = unsupported)
        assertEquals(ReasoningStatus.UNSUPPORTED, ReasoningPolicy.support(automatic).status)
        assertTrue(ReasoningPolicy.support(automatic).levels.isEmpty())
        val explicit = automatic.copy(reasoningOverride = manual(known))
        assertEquals(CapabilitySource.MANUAL, ReasoningPolicy.support(explicit).source)
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            ReasoningPolicy.support(explicit).levels
        )
        assertEquals(
            ReasoningStatus.SUPPORTED,
            ReasoningPolicy.support(automatic.copy(model = "gpt-5.1")).status
        )
    }

    @Test
    fun `positive extension validates complete exact levels mapping and protocol`() {
        val prefix = "\"reasoning_capabilities\":"
        val valid =
            parse(prefix + """{"supported":true,"levels":["high","low"],"mapping":"openai_effort"}""")!!
        assertEquals(listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), valid.levels)
        for (extension in listOf(
            """{"supported":"true","levels":["low"],"mapping":"openai_effort"}""",
            """{"supported":true,"levels":[],"mapping":"openai_effort"}""",
            """{"supported":true,"levels":["low","invalid"],"mapping":"openai_effort"}""",
            """{"supported":true,"levels":["low","low"],"mapping":"openai_effort"}""",
            """{"supported":true,"levels":[1],"mapping":"openai_effort"}""",
            """{"supported":true,"levels":["low"],"mapping":"arbitrary_field"}""",
            """{"supported":true,"levels":["low"],"mapping":"claude_budget"}""",
        )) {
            val capability = parse(prefix + extension)
            assertTrue(
                extension,
                capability == null || capability.status == ReasoningStatus.UNKNOWN
            )
        }
        assertFalse(manual(levels = emptyList()).validFor(config))
    }

    @Test
    fun `documented gateway efforts use unified wire and distinguish explicit null from omission`() {
        val metadata =
            parse(""""reasoning":{"supported_efforts":["none","high"],"mandatory":false},"supported_parameters":["reasoning"]""")!!
        assertEquals(ReasoningMapping.OPENROUTER_REASONING, metadata.mapping)
        assertEquals(listOf(ReasoningEffort.NONE, ReasoningEffort.HIGH), metadata.levels)
        val wire =
            ReasoningPolicy.apply(config.copy(reasoningMetadata = metadata), buildJsonObject {})
        assertEquals("high", wire["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertFalse(wire.containsKey("reasoning_effort"))
        val allEfforts =
            parse(""""reasoning":{"supported_efforts":null},"supported_parameters":["reasoning"]""")!!
        assertEquals(ReasoningEffort.entries.toList(), allEfforts.levels)
        val mandatoryAll = parse(""""reasoning":{"supported_efforts":null,"mandatory":true}""")!!
        assertEquals(
            ReasoningEffort.entries.filter { it != ReasoningEffort.NONE },
            mandatoryAll.levels
        )
        val omitted = parse(""""reasoning":{},"supported_parameters":["reasoning"]""")!!
        assertEquals(ReasoningStatus.UNKNOWN, omitted.status)
        assertTrue(omitted.levels.isEmpty())
        val malformed =
            parse(""""reasoning":{"supported_efforts":["low"],"mandatory":"true"},"supported_parameters":["reasoning"]""")!!
        assertEquals(ReasoningStatus.UNKNOWN, malformed.status)
        val contradictory =
            parse(""""reasoning":{"supported_efforts":["none","high"],"mandatory":true}""")!!
        assertEquals(ReasoningStatus.UNKNOWN, contradictory.status)
        val known = config.copy(model = "gpt-5")
        val hint = parse(""""supported_parameters":["reasoning"]""", known)!!
        assertEquals(
            CapabilitySource.OFFLINE,
            ReasoningPolicy.support(known.copy(reasoningMetadata = hint)).source
        )
    }

    @Test
    fun `mapping rejects impossible manual and metadata level combinations`() {
        val anthropic = config.copy(protocol = Protocol.ANTHROPIC)
        for (mapping in listOf(ReasoningMapping.CLAUDE_BUDGET, ReasoningMapping.CLAUDE_ADAPTIVE)) {
            for (effort in listOf(ReasoningEffort.NONE, ReasoningEffort.MINIMAL)) {
                assertFalse(manual(anthropic, mapping, listOf(effort)).validFor(anthropic))
                val metadata = parse(
                    """"reasoning_capabilities":{"supported":true,"levels":["${effort.name.lowercase()}"],"mapping":"${mapping.name.lowercase()}"}""",
                    anthropic
                )!!
                assertEquals(ReasoningStatus.UNKNOWN, metadata.status)
            }
        }
        assertFalse(
            manual(
                anthropic,
                ReasoningMapping.CLAUDE_BUDGET,
                listOf(ReasoningEffort.MAX)
            ).validFor(anthropic)
        )
        assertFalse(
            manual(
                mapping = ReasoningMapping.DEEPSEEK_THINKING,
                levels = listOf(ReasoningEffort.MEDIUM)
            ).validFor(config)
        )
    }

    @Test
    fun `service metadata takes priority over offline and manual over service`() {
        val known = config.copy(model = "gpt-5")
        val metadata = parse(
            """"reasoning_capabilities":{"supported":true,"levels":["high"],"mapping":"openai_effort"}""",
            known
        )!!
        val settings = known.copy(reasoningMetadata = metadata)
        assertEquals(listOf(ReasoningEffort.HIGH), ReasoningPolicy.support(settings).levels)
        assertEquals(CapabilitySource.SERVICE_METADATA, ReasoningPolicy.support(settings).source)
        val unsupported = ReasoningCapability(
            ReasoningStatus.UNSUPPORTED,
            scope = CapabilityScope.from(known),
            source = CapabilitySource.MANUAL
        )
        assertEquals(
            ReasoningStatus.UNSUPPORTED,
            ReasoningPolicy.support(settings.copy(reasoningOverride = unsupported)).status
        )
    }

    @Test
    fun `endpoint protocol model and credential changes invalidate manual and metadata`() {
        val cap = manual()
        val settings = config.copy(
            reasoningOverride = cap,
            reasoningMetadata = cap.copy(source = CapabilitySource.SERVICE_METADATA)
        )
        assertEquals(CapabilitySource.MANUAL, ReasoningPolicy.support(settings).source)
        for (changed in listOf(
            settings.copy(baseUrl = "https://other.invalid/v1"),
            settings.copy(protocol = Protocol.RESPONSES), settings.copy(model = "other"),
            settings.copy(apiKey = "other-key"), settings.copy(apiKey = "")
        )) {
            assertEquals(ReasoningStatus.UNKNOWN, ReasoningPolicy.support(changed).status)
        }
        assertEquals(
            CapabilitySource.MANUAL,
            ReasoningPolicy.support(settings.copy(baseUrl = config.baseUrl + "/")).source
        )
        val public = CapabilityScope.publicView(settings)
        assertEquals("", public.apiKey)
        assertEquals(CapabilitySource.MANUAL, ReasoningPolicy.support(public).source)
        assertEquals(
            ReasoningStatus.UNKNOWN,
            ReasoningPolicy.support(public.copy(apiKey = "new-key")).status
        )
        assertEquals(
            ReasoningStatus.UNKNOWN,
            ReasoningPolicy.support(public.copy(credentialScopeTag = null)).status
        )
        assertFalse(Json.encodeToString(cap).contains(config.apiKey))
        val secretUrl =
            config.copy(baseUrl = "https://username:password@example.invalid/v1?api_key=secret")
        val serialized = Json.encodeToString(manual(secretUrl))
        for (secret in listOf(
            "username",
            "password",
            "api_key",
            "secret"
        )) assertFalse(serialized.contains(secret))
    }

    @Test
    fun `unknown models manual mapping really changes all protocol wires`() {
        for (protocol in Protocol.entries) {
            val settings = config.copy(protocol = protocol, reasoningEffort = ReasoningEffort.HIGH)
            val mappings = if (protocol == Protocol.ANTHROPIC)
                listOf(
                    ReasoningMapping.CLAUDE_BUDGET,
                    ReasoningMapping.CLAUDE_ADAPTIVE,
                    ReasoningMapping.DEEPSEEK_THINKING
                )
            else listOf(
                ReasoningMapping.OPENAI_EFFORT,
                ReasoningMapping.DEEPSEEK_THINKING,
                ReasoningMapping.OPENROUTER_REASONING
            )
            for (mapping in mappings) {
                val selected = settings.copy(reasoningOverride = manual(settings, mapping))
                val wire = ReasoningPolicy.apply(
                    selected,
                    buildJsonObject { put("max_tokens", 8192); put("temperature", 0.4) })
                assertFalse("$protocol $mapping", wire.containsKey("temperature"))
                if (mapping == ReasoningMapping.CLAUDE_BUDGET) {
                    assertEquals(
                        8191,
                        wire["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int
                    )
                } else if (protocol == Protocol.RESPONSES || mapping == ReasoningMapping.OPENROUTER_REASONING) {
                    assertEquals(
                        "high",
                        wire["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content
                    )
                } else if (protocol == Protocol.ANTHROPIC) {
                    assertEquals(
                        "high",
                        wire["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content
                    )
                } else assertEquals("high", wire["reasoning_effort"]!!.jsonPrimitive.content)
            }
        }
        val exact = config.copy(
            reasoningEffort = ReasoningEffort.MEDIUM,
            reasoningOverride = manual(
                mapping = ReasoningMapping.DEEPSEEK_THINKING,
                levels = listOf(ReasoningEffort.HIGH)
            )
        )
        assertEquals(
            ReasoningEffort.HIGH,
            ReasoningPolicy.support(exact).effectiveEffort(ReasoningEffort.MEDIUM)
        )
        assertTrue(
            ReasoningPolicy.support(exact)
                .effectiveEffort(ReasoningEffort.MEDIUM) in ReasoningPolicy.support(exact).levels
        )
    }

    @Test
    fun `manual unknown model settings survive native SDK request encoding`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (protocol in Protocol.entries) {
                val mappings = if (protocol == Protocol.ANTHROPIC)
                    listOf(
                        ReasoningMapping.CLAUDE_BUDGET,
                        ReasoningMapping.CLAUDE_ADAPTIVE,
                        ReasoningMapping.DEEPSEEK_THINKING
                    )
                else listOf(
                    ReasoningMapping.OPENAI_EFFORT,
                    ReasoningMapping.DEEPSEEK_THINKING,
                    ReasoningMapping.OPENROUTER_REASONING
                )
                for (mapping in mappings) {
                    val settings = config.copy(
                        baseUrl = server.url("/v1").toString(),
                        protocol = protocol,
                        reasoningEffort = ReasoningEffort.HIGH,
                        maxOutputTokens = 8192,
                        temperature = 0.7
                    )
                    val selected = settings.copy(reasoningOverride = manual(settings, mapping))
                    val events = when (protocol) {
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
                        MockResponse.Builder()
                        .setHeader("Content-Type", "text/event-stream")
                        .body(events.joinToString("") { "data: ${nativeFixture(it)}\n\n" }).build()
                    )
                    ProviderFactory.create { selected }.chat(
                        ChatRequest(
                            selected.model,
                            listOf(ChatMessage(MessageRole.USER, "hi"))
                        )
                    ).toList()
                    val wire =
                        Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
                    assertEquals("custom", wire["model"]!!.jsonPrimitive.content)
                    assertFalse(wire.containsKey("temperature"))
                    when {
                        mapping == ReasoningMapping.CLAUDE_BUDGET -> assertEquals(
                            8191,
                            wire["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int
                        )

                        protocol == Protocol.RESPONSES || mapping == ReasoningMapping.OPENROUTER_REASONING -> assertEquals(
                            "high",
                            wire["reasoning"]!!.jsonObject["effort"]!!.jsonPrimitive.content
                        )

                        protocol == Protocol.ANTHROPIC -> assertEquals(
                            "high",
                            wire["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content
                        )

                        else -> assertEquals(
                            "high",
                            wire["reasoning_effort"]!!.jsonPrimitive.content
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `old config snapshots deserialize with automatic unknown defaults`() {
        val old =
            Json.decodeFromString<ProviderConfig>("""{"baseUrl":"https://example.invalid/v1","model":"custom"}""")
        assertNull(old.reasoningOverride); assertNull(old.reasoningMetadata); assertNull(old.credentialScopeTag)
        assertEquals(ReasoningEffort.MEDIUM, old.reasoningEffort)
        assertEquals(ReasoningStatus.UNKNOWN, ReasoningPolicy.support(old).status)
    }

    @Test
    fun `factory details preserve metadata while legacy listing stays sorted distinct`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                for (protocol in Protocol.entries) {
                    val mapping =
                        if (protocol == Protocol.ANTHROPIC) "claude_adaptive" else "openai_effort"
                    val body =
                        """{"data":[{"id":"z","reasoning_capabilities":{"supported":true,"levels":["low","high"],"mapping":"$mapping"}},{"id":"a","reasoning_capabilities":{"supported":false}},{"id":"a"},{"id":"basic","reasoning":true,"supported_parameters":["reasoning"]}]}"""
                    val selected = config.copy(
                        baseUrl = server.url("/proxy/v1").toString(),
                        protocol = protocol
                    )
                    val provider = ProviderFactory.create { selected }
                    server.enqueue(
                        MockResponse.Builder().setHeader("Content-Type", "application/json")
                            .body(body).build()
                    )
                    val details = provider.listModelDetails()
                    assertEquals(listOf("a", "basic", "z"), details.map { it.id })
                    assertEquals(ReasoningStatus.UNSUPPORTED, details[0].reasoning!!.status)
                    assertEquals(ReasoningStatus.UNKNOWN, details[1].reasoning!!.status)
                    assertEquals(true, details[1].reasoning!!.supportsReasoning)
                    assertTrue(details[2].reasoning!!.validFor(selected.copy(model = "z")))
                    server.enqueue(
                        MockResponse.Builder().setHeader("Content-Type", "application/json")
                            .body(body).build()
                    )
                    assertEquals(details.map { it.id }, provider.listModels())
                    repeat(2) {
                        assertEquals(
                            "/proxy/v1/models",
                            server.takeRequest().url.encodedPath
                        )
                    }
                }
            }
        }
}
