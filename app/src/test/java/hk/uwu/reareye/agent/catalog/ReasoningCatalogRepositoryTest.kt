package hk.uwu.reareye.agent.catalog

import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningEffort.HIGH
import hk.uwu.reareye.agent.ReasoningEffort.LOW
import hk.uwu.reareye.agent.data.MaryModelCapability
import hk.uwu.reareye.agent.data.MaryModelValueSource
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ReasoningCatalogRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private val source = ModelsDevReasoningCatalog
    private val secondary = object : ReasoningCatalogSource by ModelsDevReasoningCatalog {
        override val id = "secondary-v1"
        override val displayName = "Secondary"
        override val feedUri = URI("https://secondary.example/api.json")
    }

    @Test
    fun `the previously successful compact snapshot is served before an etag request`() = runBlocking {
        val directory = temporary.newFolder()
        val cache = FileReasoningCatalogCache(directory)
        val known = repository(ReasoningCatalogHttp { _, validator ->
            assertNull(validator)
            json("old", "low", "\"v1\"")
        })
        known.start { cache }.join()
        assertEquals(listOf(LOW), known.index.value.capabilityFor(config("old"))?.levels)
        val model = known.models.value.models(config("old").baseUrl, Protocol.RESPONSES).single()
        assertEquals("Public old", model.displayName)
        assertEquals(100_000, model.context)
        assertEquals(5000, model.output)
        assertEquals(setOf(MaryModelCapability.TEXT, MaryModelCapability.IMAGE, MaryModelCapability.TOOLS), model.capabilities)
        assertEquals(MaryModelValueSource.CATALOG, model.contextSource)
        assertEquals(MaryModelValueSource.CATALOG, model.outputSource)
        assertNull(model.details.reasoning)
        val bytes = cache.read(source.id)!!
        assertFalse(bytes.decodeToString().contains("not kept in snapshot"))
        assertEquals(listOf("${source.id}.json"), directory.list()?.toList())

        var requests = 0
        lateinit var restarted: ReasoningCatalogRepository
        restarted = repository(ReasoningCatalogHttp { uri, validator ->
            requests++
            assertEquals(source.feedUri, uri)
            assertEquals("\"v1\"", validator)
            assertEquals(listOf(LOW), restarted.index.value.capabilityFor(config("old"))?.levels)
            assertEquals(model, restarted.models.value.models(config("old").baseUrl, Protocol.RESPONSES).single())
            CatalogHttpResult.Unchanged
        })
        val first = restarted.start { cache }
        assertSame(first, restarted.start { error("new cache should not be constructed") })
        first.join()
        assertSame(first, restarted.start { error("process restart requires a new repository") })
        assertEquals(1, requests)
        assertArrayEquals(bytes, cache.read(source.id))
    }

    @Test
    fun `network parsing and persistence failures retain successful cache and its validator`() = runBlocking {
        val cache = MemoryCache()
        repository(ReasoningCatalogHttp { _, _ -> json("old", "low", "\"v1\"") }).start { cache }.join()
        val bytes = cache.read(source.id)!!
        val failures = listOf<ReasoningCatalogHttp>(
            ReasoningCatalogHttp { _, _ -> throw IOException("network or overstated response length") },
            ReasoningCatalogHttp { _, _ -> CatalogHttpResult.Updated("{".encodeToByteArray(), "\"invalid\"") },
            ReasoningCatalogHttp { _, _ -> json("new", "high", "\"uncommitted\"") },
        )
        cache.failWrite = true
        for (failure in failures) {
            val repository = repository(ReasoningCatalogHttp { uri, validator ->
                assertEquals("\"v1\"", validator)
                failure.fetch(uri, validator)
            })
            repository.start { cache }.join()
            assertEquals(listOf(LOW), repository.index.value.capabilityFor(config("old"))?.levels)
            assertNull(repository.index.value.capabilityFor(config("new")))
            assertArrayEquals(bytes, cache.read(source.id))
        }
    }

    @Test
    fun `sources load before either request and synchronize in parallel despite one failing`() = runBlocking {
        val cache = MemoryCache()
        repository(ReasoningCatalogHttp { _, _ -> json("first-cached", "low", "\"first\"") })
            .start { cache }.join()
        repository(ReasoningCatalogHttp { _, _ -> json("second-cached", "low", "\"second\"") }, listOf(secondary))
            .start { cache }.join()
        val arrived = CountDownLatch(2)
        lateinit var repository: ReasoningCatalogRepository
        repository = repository(ReasoningCatalogHttp { uri, etag ->
            val first = uri == source.feedUri
            assertEquals(if (first) "\"first\"" else "\"second\"", etag)
            assertEquals("Models.dev", repository.index.value.capabilityFor(config("first-cached"))?.catalogSourceName)
            assertEquals("Secondary", repository.index.value.capabilityFor(config("second-cached"))?.catalogSourceName)
            arrived.countDown()
            assertTrue("Catalog requests must run independently", arrived.await(5, TimeUnit.SECONDS))
            if (first) throw IOException("failed source")
            json("second-new", "high", "\"new\"")
        }, listOf(source, secondary), CoroutineScope(coroutineContext + Dispatchers.IO))
        withTimeout(7_000) { repository.start { cache }.join() }
        assertEquals(0L, arrived.count)
        assertEquals(listOf(LOW), repository.index.value.capabilityFor(config("first-cached"))?.levels)
        assertNull(repository.index.value.capabilityFor(config("second-cached")))
        assertEquals(listOf(HIGH), repository.index.value.capabilityFor(config("second-new"))?.levels)
        assertTrue(cache.read(source.id)!!.decodeToString().contains("first-cached"))
        assertTrue(cache.read(secondary.id)!!.decodeToString().contains("second-new"))
    }

    @Test
    fun `unknown source records never reuse validators from an invalid snapshot`() = runBlocking {
        val cache = MemoryCache()
        cache.write(source.id, """{"version":1,"sourceId":"models-dev-v1","etag":"\"foreign\"","records":[
            {"provider":"proxy","modelId":"test","efforts":["LOW"]}
        ]}""".encodeToByteArray())
        lateinit var repository: ReasoningCatalogRepository
        repository = repository(ReasoningCatalogHttp { _, validator ->
            assertNull(validator)
            assertSame(ReasoningCatalogIndex.Empty, repository.index.value)
            json("safe", "high", null)
        })
        repository.start { cache }.join()
        assertEquals(listOf(HIGH), repository.index.value.capabilityFor(config("safe"))?.levels)
    }

    @Test
    fun `legacy reasoning cache works offline and forces public metadata to download once`() = runBlocking {
        val cache = MemoryCache()
        cache.write(source.id, """{"version":1,"sourceId":"models-dev-v1","etag":"\"legacy\"","records":[
            {"provider":"openai","modelId":"old","efforts":["LOW"]}
        ]}""".encodeToByteArray())
        val presets = ModelsDevModelCatalog.parse(
            """{"openai":{"id":"openai","models":{"built-in":{"id":"built-in","name":"Preset"}}}}""".byteInputStream(),
        )
        lateinit var repository: ReasoningCatalogRepository
        repository = repository(ReasoningCatalogHttp { uri, validator ->
            assertEquals(source.feedUri, uri)
            assertNull(validator)
            assertEquals(listOf(LOW), repository.index.value.capabilityFor(config("old"))?.levels)
            assertEquals("built-in", repository.models.value.models(config("old").baseUrl, Protocol.RESPONSES).single().details.id)
            json("fresh", "high", "\"v2\"")
        })
        repository.start(builtIn = { presets }) { cache }.join()
        assertEquals("fresh", repository.models.value.models(config("old").baseUrl, Protocol.RESPONSES).single().details.id)
        assertEquals(listOf(HIGH), repository.index.value.capabilityFor(config("fresh"))?.levels)
        assertTrue(repository.models.value.models("https://proxy.test/v1", Protocol.RESPONSES).isEmpty())
    }

    @Test
    fun `metadata-only updates do not discard the last validated reasoning controls`() = runBlocking {
        val cache = MemoryCache()
        repository(ReasoningCatalogHttp { _, _ -> json("old", "low", "\"v1\"") }).start { cache }.join()
        val repository = repository(ReasoningCatalogHttp { _, validator ->
            assertEquals("\"v1\"", validator)
            CatalogHttpResult.Updated(
                """{"openai":{"id":"openai","models":{"new":{"id":"new","name":"New Model","reasoning":true}}}}"""
                    .encodeToByteArray(), "\"v2\"",
            )
        })
        repository.start { cache }.join()
        assertEquals(listOf(LOW), repository.index.value.capabilityFor(config("old"))?.levels)
        assertNull(repository.index.value.capabilityFor(config("new")))
        assertEquals("New Model", repository.models.value.models(config("new").baseUrl, Protocol.RESPONSES).single().displayName)
    }

    private fun CoroutineScope.repository(
        http: ReasoningCatalogHttp,
        sources: List<ReasoningCatalogSource> = listOf(source),
        scope: CoroutineScope = this,
    ) = ReasoningCatalogRepository(scope, sources, http)

    private fun config(id: String) = ProviderConfig("https://api.openai.com/v1", model = id, protocol = Protocol.RESPONSES)

    private fun json(id: String, effort: String, etag: String?) = CatalogHttpResult.Updated(
        """{"openai":{"id":"openai","models":{"$id":{"id":"$id","name":"Public $id",
            "description":"not kept in snapshot","limit":{"context":100000,"output":5000},
            "modalities":{"input":["text","image"]},"tool_call":true,"reasoning_options":[
            {"type":"effort","values":["$effort"]}
        ]}}}}""".encodeToByteArray(), etag,
    )

    private class MemoryCache : ReasoningCatalogCache {
        private val snapshots = ConcurrentHashMap<String, ByteArray>()
        var failWrite = false
        override fun read(sourceId: String): ByteArray? = snapshots[sourceId]
        override fun write(sourceId: String, data: ByteArray) {
            if (failWrite) throw IOException("cache is read-only")
            snapshots[sourceId] = data
        }
    }
}
