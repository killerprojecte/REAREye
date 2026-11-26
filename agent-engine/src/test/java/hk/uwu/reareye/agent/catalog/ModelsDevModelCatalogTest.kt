package hk.uwu.reareye.agent.catalog

import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.catalog.ModelsDevModelCapability.AUDIO
import hk.uwu.reareye.agent.catalog.ModelsDevModelCapability.IMAGE
import hk.uwu.reareye.agent.catalog.ModelsDevModelCapability.TEXT
import hk.uwu.reareye.agent.catalog.ModelsDevModelCapability.TOOLS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsDevModelCatalogTest {
    @Test
    fun `keeps provider-specific names limits and input modalities for exact model ids`() {
        val catalog = parse(
            """{
              "openai":{"id":"openai","models":{
                "shared":{"id":"shared","name":"Direct Shared","canonical_model_id":"lab/canonical",
                  "limit":{"context":123456,"input":111,"output":5678},
                  "modalities":{"input":["text","image","video"],"output":["audio"]},"tool_call":true},
                "api-alias":{"id":"shared","name":"Mislabelled"},
                "generates-images":{"id":"generates-images","name":"Image Generator","type":"image"}
              }},
              "openrouter":{"id":"openrouter","models":{
                "shared":{"id":"shared","name":"Gateway Shared","limit":{"context":444444,"output":5555},
                  "modalities":{"input":["audio"],"output":["image"]},"tool_call":false},
                "openai/shared:exact":{"id":"openai/shared:exact","name":"Exact Gateway ID"}
              }}
            }""",
        )
        val direct = catalog.models(" https://API.OPENAI.COM:443/v1/ ", Protocol.RESPONSES).single()
        assertEquals("shared", direct.id)
        assertEquals("Direct Shared", direct.displayName)
        assertEquals(123456, direct.context)
        assertEquals(5678, direct.maxOutput)
        assertEquals(setOf(TEXT, IMAGE, TOOLS), ModelsDevModelCapability.entries.filter(direct::supports).toSet())
        assertEquals(listOf("openai/shared:exact", "shared"), catalog.models("https://openrouter.ai/api/v1", Protocol.CHAT_COMPLETIONS).map { it.id })
        val gateway = catalog.models("openrouter").last()
        assertEquals("Gateway Shared", gateway.displayName)
        assertEquals(444444, gateway.context)
        assertEquals(5555, gateway.maxOutput)
        assertEquals(setOf(AUDIO), ModelsDevModelCapability.entries.filter(gateway::supports).toSet())
        assertTrue(catalog.models("OpenRouter").isEmpty())
        assertTrue(catalog.models("https://openrouter.ai/api/v1", Protocol.RESPONSES).isEmpty())
    }

    @Test
    fun `explicit provider endpoints do not match by host prefix or alias and ambiguous routes are declined`() {
        val model = """"models":{"common":{"id":"common","name":"Model"}}"""
        val catalog = parse(
            """{
              "openai":{"id":"openai","api":"https://other.test/v1",$model},
              "compatible":{"id":"compatible","npm":"@ai-sdk/openai-compatible","api":"https://api.openai.com/v1",
                  "models":{"foreign":{"id":"foreign","name":"Foreign"}}},
              "mismatch":{"id":"compatible","npm":"@ai-sdk/openai-compatible","api":"https://mismatch.test/v1",$model},
              "actual":{"id":"actual","npm":"@ai-sdk/openai-compatible","api":"https://other.test/v1",$model},
              "shared-a":{"id":"shared-a","npm":"@ai-sdk/openai-compatible","api":"https://shared.test/v1",$model},
              "shared-b":{"id":"shared-b","npm":"@ai-sdk/openai-compatible","api":"https://shared.test/v1/",$model},
              "not-mary":{"id":"not-mary","npm":"@ai-sdk/google","api":"https://native.test/v1",$model}
            }""",
        )
        assertEquals(listOf("common"), catalog.models("https://api.openai.com/v1", Protocol.CHAT_COMPLETIONS).map { it.id })
        assertEquals(listOf("common"), catalog.models("https://other.test/v1", Protocol.CHAT_COMPLETIONS).map { it.id })
        assertEquals("Model", catalog.models("shared-b").single().displayName)
        for (url in listOf("https://shared.test/v1", "https://other.test", "https://other.test/v1/tenant",
            "https://other.test.evil.test/v1", "https://user@other.test/v1", "https://other.test/v1?",
            "http://other.test/v1", "https://mismatch.test/v1", "https://native.test/v1")) {
            assertTrue(url, catalog.models(url, Protocol.CHAT_COMPLETIONS).isEmpty())
        }
        assertTrue(catalog.models("https://other.test/v1", Protocol.RESPONSES).isEmpty())
        assertTrue(catalog.models("mismatch").isEmpty())
    }

    @Test
    fun `model endpoint overrides remain scoped and missing or invalid values stay unknown`() {
        val catalog = parse(
            """{"gateway":{"id":"gateway","npm":"@ai-sdk/openai-compatible","api":"https://gateway.test/v1","models":{
                "chat":{"id":"chat","name":"Chat","attachment":true,"limit":{"context":"9000","output":-5},
                    "modalities":{"output":["image","audio"]},"tool_call":"true"},
                "messages":{"id":"messages","name":"Messages","limit":{"context":5000000000,"output":4000.5},
                    "provider":{"npm":"@ai-sdk/anthropic","api":"https://gateway.test/anthropic/v1"}}
              }}}""",
        )
        val default = catalog.models("https://gateway.test/v1", Protocol.CHAT_COMPLETIONS).single()
        assertEquals("chat", default.id)
        assertNull(default.context)
        assertNull(default.maxOutput)
        assertFalse(default.supports(IMAGE))
        assertFalse(default.supports(AUDIO))
        assertFalse(default.supports(TOOLS))
        val messages = catalog.models("https://gateway.test/anthropic/v1", Protocol.ANTHROPIC).single()
        assertEquals("messages", messages.id)
        assertNull(messages.context)
        assertNull(messages.maxOutput)
        assertTrue(catalog.models("https://gateway.test/anthropic/v1", Protocol.CHAT_COMPLETIONS).isEmpty())
    }

    private fun parse(feed: String) = ModelsDevModelCatalog.parse(feed.byteInputStream())
}
