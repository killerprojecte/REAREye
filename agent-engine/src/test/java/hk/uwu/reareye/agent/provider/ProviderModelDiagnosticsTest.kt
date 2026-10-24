package hk.uwu.reareye.agent.provider

import ai.koog.http.client.KoogHttpClientException
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.model.AgentError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProviderModelDiagnosticsTest {
    private fun response(body: String, code: Int = 200) = MockResponse.Builder()
        .code(code).setHeader("Content-Type", "application/json").body(body).build()

    @Test
    fun `native SDK strict model DTO rejects id only`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (protocol in Protocol.entries) {
                server.enqueue(response("""{"data":[{"id":"custom"}]}"""))
                val native = nativeConnection(
                    OpenAiCompatibleProvider.defaultClient(),
                    ProviderConfig(
                        server.url("/v1").toString(),
                        "key",
                        "custom",
                        protocol = protocol
                    )
                )
                try {
                    val error = runCatching { native.client.models() }.exceptionOrNull()
                    assertNotNull("SDK unexpectedly accepted id-only for $protocol", error)
                    assertTrue(providerCauses(error!!).any {
                        it.message.orEmpty()
                            .contains("required", ignoreCase = true) || it.message.orEmpty()
                            .contains("missing", ignoreCase = true)
                    })
                } finally {
                    native.connection.close()
                }
            }
        }
    }

    @Test
    fun `all protocols keep proxy base path auth and exact model ids with unknown metadata`() =
        runBlocking {
            MockWebServer().use { proxy ->
                proxy.start()
                val client = OpenAiCompatibleProvider.defaultClient().newBuilder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxy.port)))
                    .build()
                for (protocol in Protocol.entries) {
                    val extra =
                        if (protocol == Protocol.ANTHROPIC) "" else ",\"has_more\":\"unrelated-metadata\""
                    proxy.enqueue(response("""{"data":[{"id":"owner/model:1","metadata":{"future":true}},{"id":"A Model"},{"id":"A Model"}],"extra":42$extra}"""))
                    val settings = ProviderConfig(
                        "http://model.invalid/proxy/custom/v7",
                        "test-key",
                        "custom",
                        protocol = protocol
                    )
                    assertEquals(
                        listOf("A Model", "owner/model:1"),
                        KoogProviderAdapter(client) { settings }.listModels()
                    )
                    val wire = proxy.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals("/proxy/custom/v7/models", wire.url.encodedPath)
                    assertEquals(
                        if (protocol == Protocol.ANTHROPIC) "test-key" else "Bearer test-key",
                        wire.headers[if (protocol == Protocol.ANTHROPIC) "x-api-key" else "Authorization"]
                    )
                }
            }
        }

    @Test
    fun `HTTP errors missing data invalid ids and malformed JSON are never empty success`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                for (protocol in Protocol.entries) {
                    val config = ProviderConfig(
                        server.url("/proxy/v1").toString(),
                        "test-key",
                        "selected",
                        protocol = protocol
                    )
                    val adapter =
                        KoogProviderAdapter(OpenAiCompatibleProvider.defaultClient()) { config }
                    for (code in listOf(401, 403, 404, 429, 500)) {
                        server.enqueue(
                            response(
                                """{"error":{"message":"service says denied $code"}}""",
                                code
                            )
                        )
                        val error = runCatching { adapter.listModels() }.exceptionOrNull()!!
                        assertTrue(error is AgentError)
                        val native =
                            providerCauses(error).filterIsInstance<KoogHttpClientException>()
                                .single()
                        assertEquals(code, native.statusCode)
                        val diagnostic =
                            ProviderRequestDiagnostics.format(error, config, "GET models")
                        assertTrue(diagnostic.contains("HTTP status: $code"))
                        assertTrue(diagnostic.contains("service says denied $code"))
                        assertTrue(diagnostic.contains("/proxy/v1/models"))
                    }
                    for (body in listOf(
                        "<html>gateway</html>",
                        "{",
                        "{}",
                        """{"data":{}}""",
                        """{"data":[{}]}""",
                        """{"data":[{"id":7}]}"""
                    )) {
                        server.enqueue(response(body))
                        assertNotNull(
                            "$protocol $body",
                            runCatching { adapter.listModels() }.exceptionOrNull()
                        )
                    }
                    server.enqueue(response("""{"data":[]}"""))
                    assertTrue(adapter.listModels().isEmpty())
                }
            }
        }

    @Test
    fun `anthropic pagination is native bounded and rejects repeated cursor`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val config =
                ProviderConfig(server.url("/v1").toString(), "key", protocol = Protocol.ANTHROPIC)
            val adapter = KoogProviderAdapter(OpenAiCompatibleProvider.defaultClient()) { config }
            server.enqueue(response("""{"data":[{"id":"a/b"}],"has_more":true,"last_id":"a/b"}"""))
            server.enqueue(response("""{"data":[{"id":"z"}],"has_more":false}"""))
            assertEquals(listOf("a/b", "z"), adapter.listModels())
            server.takeRequest()
            assertEquals("a/b", server.takeRequest().url.queryParameter("after_id"))
            repeat(2) { server.enqueue(response("""{"data":[{"id":"a"}],"has_more":true,"last_id":"a"}""")) }
            assertTrue(
                ProviderRequestDiagnostics.format(
                    runCatching { adapter.listModels() }.exceptionOrNull()!!,
                    config,
                    "models"
                )
                    .contains("no advancing last_id")
            )
        }
    }

    @Test
    fun `anthropic pagination cannot exceed one hundred pages`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val config = ProviderConfig(server.url("/v1").toString(), protocol = Protocol.ANTHROPIC)
            repeat(100) { page -> server.enqueue(response("""{"data":[{"id":"model-$page"}],"has_more":true,"last_id":"model-$page"}""")) }
            val error = withTimeout(15_000) {
                runCatching {
                    KoogProviderAdapter(OpenAiCompatibleProvider.defaultClient()) { config }.listModels()
                }.exceptionOrNull()!!
            }
            assertTrue(error.message.orEmpty().contains("exceeded 100"))
            assertEquals(100, server.requestCount)
        }
    }

    @Test
    fun `context metadata absence is null but HTTP error is exposed`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val config =
                ProviderConfig(server.url("/v1").toString(), "key", protocol = Protocol.ANTHROPIC)
            val adapter = KoogProviderAdapter(OpenAiCompatibleProvider.defaultClient()) { config }
            server.enqueue(response("""{"id":"custom"}"""))
            assertNull(adapter.modelContextWindow("custom"))
            server.enqueue(response("""{"message":"not allowed"}""", 403))
            val error = runCatching { adapter.modelContextWindow("custom") }.exceptionOrNull()!!
            assertTrue(
                ProviderRequestDiagnostics.format(error, config, "context window")
                    .contains("HTTP status: 403")
            )
        }
    }

    @Test
    fun `all protocols cancel pending model requests without converting to error or empty`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                for (protocol in Protocol.entries) for (bodyWait in listOf(false, true)) {
                    val started = CountDownLatch(1)
                    val canceled = CountDownLatch(1)
                    val client = OpenAiCompatibleProvider.defaultClient().newBuilder()
                        .eventListener(object : EventListener() {
                            override fun requestHeadersEnd(call: Call, request: okhttp3.Request) {
                                if (!bodyWait) started.countDown()
                            }

                            override fun responseHeadersEnd(
                                call: Call,
                                response: okhttp3.Response
                            ) {
                                if (bodyWait) started.countDown()
                            }

                            override fun canceled(call: Call) {
                                canceled.countDown()
                            }
                        }).build()
                    server.enqueue(response("""{"data":[]}""").newBuilder().apply {
                        if (bodyWait) bodyDelay(3, TimeUnit.SECONDS) else headersDelay(
                            3,
                            TimeUnit.SECONDS
                        )
                    }.build())
                    val adapter = KoogProviderAdapter(client) {
                        ProviderConfig(
                            server.url("/v1").toString(),
                            protocol = protocol
                        )
                    }
                    val request = async(Dispatchers.Default) { adapter.listModels() }
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    withTimeout(1500) { request.cancelAndJoin() }
                    assertTrue(request.isCancelled)
                    assertTrue(canceled.await(1, TimeUnit.SECONDS))
                }
            }
        }

    @Test
    fun `diagnostics redact credentials headers URL userinfo and query but retain path status and useful body`() {
        val key = "sk-sensitive+/secret="
        val config = ProviderConfig(
            "https://user:password@example.org/proxy%2Fv1?api_key=querySecret&safe=visible",
            key,
            "custom"
        )
        val encoded = URLEncoder.encode(key, "UTF-8")
        val base64 = Base64.getEncoder().encodeToString(key.toByteArray())
        val body =
            """{"message":"denied", "API_KEY":"other-secret", "x-api-key": "header-secret", "Authorization":"Bearer header-bearer"} $key $encoded $base64 https://other:credential@example.org/a?ToKeN=another-secret Authorization: Bearer abc123 access_token='value-secret' token=unquoted-secret """ + "x".repeat(
                25_000
            )
        val native = KoogHttpClientException(statusCode = 429, errorBody = body)
        val error = native.asAgentError(config)
        val text = ProviderRequestDiagnostics.format(error, config, "GET models")
        for (secret in listOf(
            key,
            encoded,
            base64,
            "password",
            "querySecret",
            "other-secret",
            "header-secret",
            "header-bearer",
            "credential",
            "another-secret",
            "abc123",
            "value-secret",
            "unquoted-secret"
        ))
            assertFalse("leaked $secret", text.contains(secret))
        assertTrue(text.contains("https://example.org/proxy%2Fv1"))
        assertTrue(text.contains("429")); assertTrue(text.contains("denied"))
        assertTrue(text.length in 4096..16384)
        assertSame(native, error.cause)
    }

    @Test
    fun `formatting mapped errors keeps friendly explanation root causes and body only once`() {
        val config = ProviderConfig("https://example.org/proxy/v1", "test-key", "custom")
        val root = java.net.UnknownHostException("root DNS failure")
        val network = root.asAgentError(config)
        val networkText = ProviderRequestDiagnostics.format(network, config, "models")
        assertTrue(networkText.contains("找不到主机"))
        assertTrue(networkText.contains("UnknownHostException"))
        assertTrue(networkText.contains("root DNS failure"))
        val native =
            KoogHttpClientException(statusCode = 500, errorBody = "unique-provider-error-marker")
        val mapped = native.asAgentError(config, "GET /models")
        val text = ProviderRequestDiagnostics.format(mapped, config, "模型列表")
        assertEquals(1, Regex("unique-provider-error-marker").findAll(text).count())
        assertTrue(text.contains("/proxy/v1/models"))
        assertTrue(text.contains("HTTP status: 500"))
    }

    @Test
    fun `diagnostics terminate identity cause cycles`() {
        val a = Exception("a")
        val b = Exception("b")
        a.initCause(b); b.initCause(a)
        assertEquals(2, providerCauses(a).size)
        assertTrue(
            ProviderRequestDiagnostics.format(a, ProviderConfig(), "models").contains("Cause[1]")
        )
    }
}
