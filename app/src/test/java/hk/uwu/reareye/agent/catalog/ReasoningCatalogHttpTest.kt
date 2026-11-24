package hk.uwu.reareye.agent.catalog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.zip.GZIPOutputStream
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ReasoningCatalogHttpTest {
    @Test
    fun `200 then conditional 304 carry no credentials`() = withServer { server, uri ->
        val client = OkHttpReasoningCatalogHttp()
        val etag = "W/\"public-v1\""
        server.enqueue(MockResponse.Builder().code(200).setHeader("ETag", etag).body("{}").build())
        server.enqueue(MockResponse.Builder().code(304).build())
        val updated = client.fetch(uri, null) as CatalogHttpResult.Updated
        assertArrayEquals("{}".encodeToByteArray(), updated.body)
        assertEquals(etag, updated.etag)
        assertSame(CatalogHttpResult.Unchanged, client.fetch(uri, updated.etag))
        val initial = server.takeRequest()
        val conditional = server.takeRequest()
        assertEquals("/api.json", initial.target)
        assertNull(initial.headers["If-None-Match"])
        assertEquals(etag, conditional.headers["If-None-Match"])
        for (request in listOf(initial, conditional)) {
            assertEquals("application/json", request.headers["Accept"])
            assertNull(request.headers["Authorization"])
            assertNull(request.headers["Cookie"])
        }
    }

    @Test
    fun `503 retry-after-zero redirects and failures do not make a follow-up request`() {
        for (code in listOf(503, 302, 307, 408, 401, 500, 206)) withServer { server, uri ->
            server.enqueue(MockResponse.Builder().code(code).setHeader("Retry-After", "0")
                .setHeader("Location", server.url("/redirect").toString()).build())
            server.enqueue(MockResponse.Builder().body("should never be fetched").build())
            assertThrows("status $code", IOException::class.java) { OkHttpReasoningCatalogHttp().fetch(uri, null) }
            assertEquals("status $code", 1, server.requestCount)
        }
    }

    @Test
    fun `body IO failure is not retried`() = withServer { server, uri ->
        server.enqueue(MockResponse.Builder().code(200).body("incomplete")
            .setHeader("Content-Length", "500").setHeader("Connection", "close").build())
        server.enqueue(MockResponse.Builder().body("should never be fetched").build())
        assertThrows(IOException::class.java) { OkHttpReasoningCatalogHttp().fetch(uri, null) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `decompressed data is bounded even if a small body was transferred`() = withServer { server, uri ->
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(ByteArray(513)) }
        }.toByteArray()
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Encoding", "gzip")
            .body(Buffer().write(compressed)).build())
        assertThrows(IOException::class.java) { OkHttpReasoningCatalogHttp(512).fetch(uri, null) }
        assertEquals(1, server.requestCount)
    }

    private fun withServer(block: (MockWebServer, URI) -> Unit) = MockWebServer().use { server ->
        server.start()
        block(server, URI(server.url("/api.json").toString()))
    }
}
