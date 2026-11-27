package hk.uwu.reareye.agent.catalog

import hk.uwu.reareye.agent.CapabilityScope
import hk.uwu.reareye.agent.CapabilitySource
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningEffort.HIGH
import hk.uwu.reareye.agent.ReasoningEffort.LOW
import hk.uwu.reareye.agent.ReasoningEffort.MAX
import hk.uwu.reareye.agent.ReasoningEffort.MEDIUM
import hk.uwu.reareye.agent.ReasoningEffort.NONE
import hk.uwu.reareye.agent.ReasoningMapping
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDevReasoningCatalogTest {
    private val source = ModelsDevReasoningCatalog

    @Test
    fun `an exact official route and full id produce scoped wire capabilities`() {
        val index = index(records())
        val openAi = ProviderConfig("https://API.OPENAI.COM:443/v1/", "local-key", "gpt-6-test", protocol = Protocol.RESPONSES)
        val direct = index.capabilityFor(openAi)!!
        assertEquals(listOf(LOW, MAX), direct.levels)
        assertEquals(ReasoningMapping.OPENAI_EFFORT, direct.mapping)
        assertEquals(CapabilitySource.CATALOG_METADATA, direct.source)
        assertEquals("Models.dev", direct.catalogSourceName)
        assertEquals(CapabilityScope.from(openAi), direct.scope)
        assertTrue(direct.validFor(openAi))
        assertEquals(direct.scope, index.capabilityFor(CapabilityScope.publicView(openAi))?.scope)
        assertEquals(direct, index.capabilityFor(openAi.copy(baseUrl = " ${openAi.baseUrl} ", model = " gpt-6-test ")))

        val deepSeek = ProviderConfig("https://api.deepseek.com/v1", model = "deepseek-test")
        assertEquals(listOf(NONE, HIGH), index.capabilityFor(deepSeek)?.levels)
        assertEquals(ReasoningMapping.DEEPSEEK_THINKING, index.capabilityFor(deepSeek)?.mapping)
        assertEquals(listOf(NONE, HIGH), index.capabilityFor(deepSeek.copy(baseUrl = "https://api.deepseek.com/"))?.levels)
        val gateway = ProviderConfig("https://openrouter.ai/api/v1", model = "openai/gpt-6-test:exact")
        assertEquals(listOf(MEDIUM), index.capabilityFor(gateway)?.levels)
        assertEquals(ReasoningMapping.OPENROUTER_REASONING, index.capabilityFor(gateway)?.mapping)
        assertNull(index.capabilityFor(gateway.copy(model = "openai/gpt-6-test")))
        assertNull(index.capabilityFor(gateway.copy(protocol = Protocol.RESPONSES)))
        assertNull(index.capabilityFor(gateway.copy(model = "gpt-6-test")))
    }

    @Test
    fun `does not infer endpoints protocols ids or provider redirects`() {
        val index = index(records())
        val config = ProviderConfig("https://api.openai.com/v1", model = "gpt-6-test", protocol = Protocol.RESPONSES)
        val invalid = listOf(
            "http://api.openai.com/v1", "https://api.openai.com:8443/v1", "https://api.openai.com:0443/v1",
            "https://api.openai.com.evil.test/v1", "https://api.openai.com./v1", "https://evil.test/v1",
            "https://feed-defined.test/v1", "https://name@api.openai.com/v1", "https://api.openai.com/v1?",
            "https://api.openai.com/v1#", "https://api.openai.com/V1", "https://api.openai.com/%761",
            "https://api.openai.com/v1//", "https://api.openai.com/v1/chat/completions",
            "https://api.openai.com/a/../v1", "https://api.openai.com",
        )
        for (baseUrl in invalid) assertNull(baseUrl, index.capabilityFor(config.copy(baseUrl = baseUrl)))
        for (id in listOf("GPT-6-TEST", "gpt-6- test", "openai/gpt-6-test", "gpt-6-test-2026-01-01", "canonical-id")) {
            assertNull(id, index.capabilityFor(config.copy(model = id)))
        }
        assertNull(index.capabilityFor(config.copy(protocol = Protocol.CHAT_COMPLETIONS)))
        assertNull(index.capabilityFor(ProviderConfig("https://compatible.test/v1", model = "gpt-6-test")))
    }

    @Test
    fun `only exact valid effort arrays produce entries and toggle never stands alone`() {
        val records = records()
        assertEquals(setOf("gpt-6-test", "deepseek-test", "deepseek-no-toggle", "openai/gpt-6-test:exact"),
            records.map { it.modelId }.toSet())
        assertEquals(listOf(LOW), index(records).capabilityFor(
            ProviderConfig("https://api.deepseek.com/v1", model = "deepseek-no-toggle"),
        )?.levels)
        for (bad in listOf(
            """{"error":"not a directory"}""",
            """{"openai":{"models":{"example":{"id":"example","reasoning":true}}}}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { source.parse(bad.byteInputStream()) }
        }
    }

    @Test
    fun `snapshots revalidate route effort and toggle and are independent of caller lists`() {
        fun row(provider: String, levels: List<ReasoningEffort>, toggle: Boolean = false) =
            ReasoningCatalogRecord(provider, "exact-model", levels, toggle)
        for (invalid in listOf(row("proxy", listOf(LOW)), row("deepseek", listOf(MEDIUM)),
            row("deepseek", listOf(NONE), toggle = true), row("openai", listOf(LOW), toggle = true),
            row("openai", listOf(LOW, LOW)), row("openai", emptyList()))) {
            assertThrows(IllegalArgumentException::class.java) { index(listOf(invalid)) }
        }
        val levels = mutableListOf(LOW, HIGH)
        val records = mutableListOf(row("openai", levels))
        val index = index(records)
        levels.clear()
        records.clear()
        val cap = index.capabilityFor(ProviderConfig("https://api.openai.com/v1", model = "exact-model", protocol = Protocol.RESPONSES))!!
        assertThrows(UnsupportedOperationException::class.java) { (cap.levels as MutableList).clear() }
        assertEquals(listOf(LOW, HIGH), cap.levels)
    }

    private fun index(records: List<ReasoningCatalogRecord>) =
        ReasoningCatalogIndex.from(listOf(ReasoningCatalogSnapshot.from(source, records)))

    private fun records() = source.parse(
        """
        {
          "openai": {"api":"https://feed-defined.test/v1","models":{
            "gpt-6-test":{"id":"gpt-6-test","canonical_model_id":"canonical-id","reasoning_options":[{"type":"effort","values":["max","low"]}]},
            "toggle-only":{"id":"toggle-only","reasoning":true,"reasoning_options":[{"type":"toggle"}]},
            "budget-only":{"id":"budget-only","reasoning_options":[{"type":"budget_tokens","min":512}]},
            "coarse":{"id":"coarse","reasoning":true},
            "unknown":{"id":"unknown","reasoning_options":[{"type":"effort","values":["low","extreme"]}]},
            "ambiguous":{"id":"ambiguous","reasoning_options":[{"type":"effort","values":["low"]},{"type":"effort","values":["high"]}]},
            "alias":{"id":"gpt-6-test","reasoning_options":[{"type":"effort","values":["low"]}]}
          }},
          "deepseek":{"models":{
            "deepseek-test":{"id":"deepseek-test","reasoning_options":[{"type":"toggle"},{"type":"effort","values":["high"]}]},
            "deepseek-no-toggle":{"id":"deepseek-no-toggle","reasoning_options":[{"type":"effort","values":["low"]}]},
            "deepseek-toggle-only":{"id":"deepseek-toggle-only","reasoning_options":[{"type":"toggle"}]},
            "deepseek-invalid":{"id":"deepseek-invalid","reasoning_options":[{"type":"effort","values":["medium"]}]}
          }},
          "openrouter":{"models":{
            "openai/gpt-6-test:exact":{"id":"openai/gpt-6-test:exact","reasoning_options":[{"type":"toggle"},{"type":"effort","values":["medium"]}]}
          }},
          "compatible":{"api":"https://api.openai.com/v1","models":{"gpt-6-test":{"id":"gpt-6-test","reasoning_options":[{"type":"effort","values":["none"]}]}}}
        }
        """.trimIndent().byteInputStream(),
    )
}
