package hk.uwu.reareye.agent

import hk.uwu.reareye.agent.data.MaryWebSearchEngine
import hk.uwu.reareye.agent.model.EmptyToolEnvironment
import hk.uwu.reareye.agent.model.ToolEnvironment
import hk.uwu.reareye.agent.model.ToolResult
import hk.uwu.reareye.agent.model.ToolRisk
import hk.uwu.reareye.agent.model.ToolSpec
import hk.uwu.reareye.agent.tools.MaryWebAddressPolicy
import hk.uwu.reareye.agent.tools.MaryWebFailure
import hk.uwu.reareye.agent.tools.MaryWebHttp
import hk.uwu.reareye.agent.tools.MaryWebToolEnvironment
import hk.uwu.reareye.agent.tools.allowsBingSearchRedirect
import hk.uwu.reareye.agent.tools.isPublicMaryWebAddress
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MaryWebToolEnvironmentTest {
    @Test
    fun `web tools are opt-in and delegate unrelated names even after the switch changes`() = runBlocking {
        var enabled = false
        val delegate = object : ToolEnvironment {
            override fun availableTools() = listOf(ToolSpec("local", "local"), ToolSpec("web_search", "ignore"))
            override suspend fun execute(toolName: String, args: JsonObject) = ToolResult.ok(toolName)
        }
        val environment = MaryWebToolEnvironment(delegate, { enabled }, MaryWebSearchEngine.BING)
        assertEquals(listOf("local"), environment.availableTools().map { it.name })
        assertTrue(environment.execute("web_search", searchArgs()).isError)
        assertTrue(environment.execute("web_fetch", fetchArgs("https://example.org/")).isError)
        assertEquals(ToolResult.ok("local"), environment.execute("local", JsonObject(emptyMap())))
        enabled = true
        val specs = environment.availableTools()
        assertEquals(listOf("local", "web_search", "web_fetch"), specs.map { it.name })
        assertTrue(specs.drop(1).all { it.risk == ToolRisk.READ_ONLY })
        val han = Regex("\\p{IsHan}")
        assertTrue(specs.drop(1).all { spec ->
            !han.containsMatchIn(spec.description) && spec.params.none { han.containsMatchIn(it.description) }
        })
        enabled = false
        assertTrue(environment.execute("web_search", searchArgs()).isError)
    }

    @Test
    fun `Bing falls back to RSS and preserves public links and cleaned snippets`() = withServer { server, origin ->
        server.enqueue(htmlSearch("<ol id='b_results'></ol>"))
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/xml; charset=utf-8").body("""
            <rss version="2.0"><channel>
              <item><title>First &amp; co</title><link>https://example.org/a?x=1&amp;y=2</link>
                <description><![CDATA[<b>Actual</b> summary<script>ignored</script>]]></description></item>
              <item><title>Duplicate</title><link>https://example.org/a?x=1&amp;y=2#duplicate</link></item>
              <item><title>First private URL</title><link>http://127.0.0.2/internal</link></item>
              <item><title>Second 中文</title><link>https://example.net/b</link></item>
            </channel></rss>
        """.trimIndent()).build())
        val result = environment(origin).execute("web_search", searchArgs("First & 中文", 2)).payload()
        assertEquals("bing", result.string("source"))
        assertEquals("First & 中文", result.string("query"))
        val entries = result.getValue("results").jsonArray.map { it.jsonObject }
        assertEquals(listOf("First & co", "Second 中文"), entries.map { it.string("title") })
        assertEquals("Actual summary", entries.first().string("snippet"))
        assertEquals("https://example.org/a?x=1&y=2", entries.first().string("url"))
        val first = server.takeRequest()
        assertEquals("/search?q=First%20%26%20%E4%B8%AD%E6%96%87&mkt=zh-CN", first.target)
        assertEquals("zh-CN,zh;q=0.9,en;q=0.6", first.headers["Accept-Language"])
        val rss = server.takeRequest()
        assertEquals("/search?q=First%20%26%20%E4%B8%AD%E6%96%87&format=rss", rss.target)
        assertNull(rss.headers["Authorization"])
        assertNull(rss.headers["Cookie"])
    }

    @Test
    fun `specific model results outrank brand navigation and spread across publishers`() = withServer { server, origin ->
        val target = "https://reviews.example.org/phones/18?sku=18&size=pro"
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(target.encodeToByteArray())
        server.enqueue(htmlSearch("""
            <ol id="b_results">
              <li class="b_algo"><h2><a href="https://mi.com/">Xiaomi official website</a></h2></li>
              <li class="b_ad"><h2><a href="https://ads.example.net/">Xiaomi 18 Pro Max Ad</a></h2></li>
              <li class="b_algo"><h2><a href="https://mi.com/18-pro-max">Xiaomi 18 Pro Max</a></h2></li>
              <li class="b_algo"><h2><a href="https://global.mi.com/18-pro-max">Xiaomi 18 Pro Max Global</a></h2></li>
              <li class="b_algo"><h2><a href="https://www.bing.com/ck/a?u=a1$encoded">Xiaomi 18 Pro Max review</a></h2>
                <div class="b_caption"><p><span class="algoSlug_icon">Translate</span>Hands-on review</p></div></li>
              <li class="b_algo"><h3><a href="https://compare.example.net/specs">小米 18 Pro Max 参数</a></h3></li>
            </ol>
        """.trimIndent()))
        val payload = environment(origin).execute("web_search", searchArgs("Xiaomi 18 Pro Max", 3)).payload()
        val results = payload.getValue("results").jsonArray.map { it.jsonObject }
        assertEquals(listOf("https://mi.com/18-pro-max", target, "https://compare.example.net/specs"), results.map { it.string("url") })
        assertEquals("Hands-on review", results[1].string("snippet"))
        val request = server.takeRequest()
        assertEquals("/search?q=Xiaomi%2018%20Pro%20Max", request.target)
        assertEquals("en-US,en;q=0.9", request.headers["Accept-Language"])
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Bing accepts a full-length CJK query without exceeding the transport URL cap`() = withServer { server, origin ->
        val query = "中".repeat(200)
        server.enqueue(htmlSearch("<ol id='b_results'><li class='b_algo'><h2><a href='https://example.org/'>$query</a></h2></li></ol>"))
        val environment = environment(origin)
        assertEquals(query, environment.execute("web_search", searchArgs(query, 1)).payload().string("query"))
        assertTrue(environment.execute("web_search", searchArgs(query + "中")).isError)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `Bing never follows cross-origin redirects in either representation`() = withServer { server, origin ->
        repeat(2) {
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "//another.example.net/search").build())
        }
        assertTrue(environment(origin).execute("web_search", searchArgs()).isError)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `Bing rejects an invalid RSS response when the HTML request fails`() = withServer { server, origin ->
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/xml").body("<html>Not a feed</html>").build())
        assertTrue(environment(origin).execute("web_search", searchArgs()).isError)
    }

    @Test
    fun `Bing accepts its official HTTPS regional redirect and rejects other origins`() {
        val initial = "https://www.bing.com/search?q=weather&format=rss".toHttpUrl()
        assertTrue(allowsBingSearchRedirect(initial, "https://cn.bing.com/search?q=weather&format=rss".toHttpUrl()))
        assertFalse(allowsBingSearchRedirect(initial, "https://www.bing.com.example.net/search".toHttpUrl()))
        assertFalse(allowsBingSearchRedirect(initial, "https://subdomain.bing.com/search".toHttpUrl()))
        assertFalse(allowsBingSearchRedirect(initial, "http://cn.bing.com/search".toHttpUrl()))
    }

    @Test
    fun `fetch checks redirects carries no credentials and returns only static visible text`() = withServer { server, origin ->
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/article#heading")
            .setHeader("Set-Cookie", "test_session=secret; Path=/").build())
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/html; charset=utf-8").body("""
            <html><head><title>页面 &amp; title</title><style>hidden head</style></head>
            <body><h1>你好</h1><script>alert('hidden script')</script><p>Plain &amp; visible.</p>
            <template>hidden template</template><div hidden>hidden div</div><iframe src="/embedded">hidden iframe</iframe>
            <img src="/image"><noscript>hidden noscript</noscript></body></html>
        """.trimIndent()).build())
        val result = environment(origin).execute("web_fetch", fetchArgs(origin.resolve("/start").toString())).payload()
        assertEquals(origin.resolve("/article").toString(), result.string("url"))
        assertEquals("text/html", result.string("contentType"))
        assertEquals("页面 & title", result.string("title"))
        assertEquals("你好 Plain & visible.", result.string("text"))
        assertEquals("false", result.string("truncated"))
        val initial = server.takeRequest()
        val redirected = server.takeRequest()
        assertEquals("/start", initial.target)
        assertEquals("/article", redirected.target)
        for (request in listOf(initial, redirected)) {
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Proxy-Authorization"])
            assertNull(request.headers["Cookie"])
            assertNull(request.headers["Referer"])
            assertNull(request.headers["X-Goog-Api-Key"])
        }
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `fetch rejects blocked redirect and resolves even same host again before following`() = withServer { server, origin ->
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "http://127.0.0.2/sensitive").build())
        assertTrue(environment(origin).execute("web_fetch", fetchArgs(origin.toString())).isError)
        assertEquals(1, server.requestCount)

        val lookups = AtomicInteger()
        val dns = Dns { hostname ->
            assertEquals("server.example.net.", hostname)
            listOf(if (lookups.incrementAndGet() == 1) mockAddress else InetAddress.getByName("192.168.0.3"))
        }
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/rebound").build())
        val result = environment(origin, dns).execute("web_fetch", fetchArgs(origin.toString()))
        assertTrue(result.isError)
        assertEquals(2, lookups.get())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `fetch limits total redirect hops and does not retry a 503`() = withServer { server, origin ->
        val environment = environment(origin)
        repeat(4) { server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/next").build()) }
        assertTrue(environment.execute("web_fetch", fetchArgs(origin.toString())).isError)
        assertEquals(4, server.requestCount)
        server.enqueue(MockResponse.Builder().code(503).setHeader("Retry-After", "0").build())
        assertTrue(environment.execute("web_fetch", fetchArgs(origin.toString())).isError)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `fetch refuses binary and caps decoded body and valid JSON output`() = withServer { server, origin ->
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "image/png").body("not text").build())
        val environment = environment(origin)
        assertTrue(environment.execute("web_fetch", fetchArgs(origin.toString())).isError)

        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(700 * 1024) { 'a'.code.toByte() }) }
        }.toByteArray()
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/plain").setHeader("Content-Encoding", "gzip")
            .body(Buffer().write(compressed)).build())
        val result = environment.execute("web_fetch", fetchArgs(origin.toString()))
        val payload = result.payload()
        assertEquals("a".repeat(8_000), payload.string("text"))
        assertEquals("true", payload.string("truncated"))
        assertTrue(result.content.length < 24_000)
    }

    @Test
    fun `fetch reports a partial HTTP response as truncated`() = withServer { server, origin ->
        server.enqueue(MockResponse.Builder().code(206).setHeader("Content-Type", "text/plain")
            .setHeader("Content-Range", "bytes 0-4/6").body("first").build())
        val result = environment(origin).execute("web_fetch", fetchArgs(origin.toString())).payload()
        assertEquals("first", result.string("text"))
        assertEquals("true", result.string("truncated"))
    }

    @Test
    fun `fetch cancellation propagates during remote body read`() = withServer { server, origin ->
        server.enqueue(MockResponse.Builder().setHeader("Content-Type", "text/plain").body("delayed")
            .bodyDelay(4, TimeUnit.SECONDS).build())
        kotlinx.coroutines.coroutineScope {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { environment(origin).execute("web_fetch", fetchArgs(origin.toString())) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            withTimeout(1_000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
        }
    }

    @Test
    fun `public address policy rejects local network reserved addresses and malformed URLs`() {
        val blocked = listOf(
            "0.1.2.3", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.5.4", "192.168.1.3",
            "192.0.0.7", "192.0.2.1", "192.88.99.5", "198.18.0.3", "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1",
            "::", "::1", "::ffff:127.0.0.1", "64:ff9b::a9fe:a9fe", "2001::123", "2001:db8::123", "2002:7f00:1::", "3fff::123", "fc00::1", "fe80::1", "ff00::1",
        )
        for (address in blocked) assertFalse(address, isPublicMaryWebAddress(InetAddress.getByName(address)))
        for (address in listOf("8.8.8.8", "1.1.1.1", "172.32.0.1", "2606:4700::1111", "2001:4860:4860::8888")) {
            assertTrue(address, isPublicMaryWebAddress(InetAddress.getByName(address)))
        }
        val policy = MaryWebAddressPolicy(Dns { throw AssertionError("URL validation must not resolve hostnames") })
        for (url in listOf("file:///tmp/a", "http:////example.net", "example.net", "https://user@example.net", "https://@example.net",
            "https://localhost.", "https://host.internal/", "http://127.1", "http://127.0.0.01/", "http://2130706433/",
            "http://[::ffff:127.0.0.1]/", "https://example.net:8443", "https://example.net\\@public.org")) {
            assertThrows(url, MaryWebFailure::class.java) { policy.url(url) }
        }
        assertEquals("https://example.net/a", policy.url("https://example.net/a#section").toString())
    }

    @Test
    fun `DNS refuses mixed public and local answers without returning any address to client`() {
        val policy = MaryWebAddressPolicy(Dns { hostname ->
            assertEquals("example.net.", hostname)
            listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.2"))
        })
        assertThrows(MaryWebFailure::class.java) { policy.dns.lookup("example.net") }
    }

    @Test
    fun `fixed Bing HTTPS endpoints can use VPN DNS without relaxing web fetch`() {
        val fakeAddress = InetAddress.getByName("198.18.0.3")
        val resolver = Dns { listOf(fakeAddress) }
        val fetch = MaryWebAddressPolicy(resolver)
        val search = MaryWebAddressPolicy(resolver, fixedHttpsHosts = setOf("www.bing.com", "cn.bing.com"))
        for (host in listOf("www.bing.com", "cn.bing.com")) {
            val target = search.url("https://$host/search")
            assertEquals(listOf(fakeAddress), search.dns.lookup(host))
            search.verifyConnectedAddress(target, fakeAddress)
            assertThrows(MaryWebFailure::class.java) { fetch.dns.lookup(host) }
            assertThrows(MaryWebFailure::class.java) { fetch.verifyConnectedAddress(target, fakeAddress) }
        }
        for (url in listOf("http://www.bing.com/", "https://www.bing.com:8443/", "https://www.bing.com.example.net/",
            "https://127.0.0.1/", "https://example.net/")) {
            assertThrows(url, MaryWebFailure::class.java) { search.url(url) }
        }
    }

    private fun withServer(block: suspend (MockWebServer, HttpUrl) -> Unit) {
        MockWebServer().use { server ->
            server.start(mockAddress, 0)
            val origin = server.url("/").newBuilder().host("server.example.net").build()
            runBlocking { block(server, origin) }
        }
    }

    private fun environment(
        origin: HttpUrl,
        dns: Dns = Dns { listOf(mockAddress) },
        engine: MaryWebSearchEngine = MaryWebSearchEngine.BING,
    ) = MaryWebToolEnvironment(
        EmptyToolEnvironment, { true }, engine,
        MaryWebHttp(MaryWebAddressPolicy(dns, { it == mockAddress }, standardPortsOnly = false)),
        checkNotNull(origin.resolve("/search")),
    )

    private fun htmlSearch(body: String) = MockResponse.Builder()
        .setHeader("Content-Type", "text/html; charset=utf-8").body(body).build()

    private fun searchArgs(query: String = "weather", limit: Int? = null) = buildJsonObject {
        put("query", query)
        limit?.let { put("limit", it) }
    }

    private fun fetchArgs(url: String) = buildJsonObject { put("url", url) }
    private fun ToolResult.payload(): JsonObject {
        assertFalse(content, isError)
        return Json.parseToJsonElement(content).jsonObject
    }
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private companion object {
        val mockAddress: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}
