package hk.uwu.reareye.agent.catalog

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request

internal sealed interface CatalogHttpResult {
    data class Updated(val body: ByteArray, val etag: String?) : CatalogHttpResult
    data object Unchanged : CatalogHttpResult
}

internal fun interface ReasoningCatalogHttp {
    @Throws(IOException::class)
    fun fetch(uri: URI, etag: String?): CatalogHttpResult
}

internal class OkHttpReasoningCatalogHttp(private val maxBytes: Int = 12 * 1024 * 1024) : ReasoningCatalogHttp {
    private class Attempt {
        val started = AtomicBoolean()
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            // No coalesced HTTP/2 connections that could trigger a 421 retry.
            .protocols(listOf(Protocol.HTTP_1_1))
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val attempt = checkNotNull(request.tag(Attempt::class.java))
                if (!attempt.started.compareAndSet(false, true)) throw IOException("Catalog follow-up refused")
                val response = chain.proceed(request)
                // OkHttp can follow up on 503 Retry-After: 0 independently of retryOnConnectionFailure.
                if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
            }
            .build()
    }

    override fun fetch(uri: URI, etag: String?): CatalogHttpResult {
        val request = Request.Builder()
            .url(uri.toString())
            .tag(Attempt::class.java, Attempt())
            .header("Accept", "application/json")
            .apply { entityTag(etag)?.let { header("If-None-Match", it) } }
            .build()
        client.newCall(request).execute().use { response ->
            return when (response.code) {
                304 -> CatalogHttpResult.Unchanged
                200 -> {
                    val body = response.body
                    if (body.contentLength() > maxBytes.toLong()) throw IOException("Catalog response too large")
                    CatalogHttpResult.Updated(
                        body.byteStream().readCatalogBytes(maxBytes),
                        entityTag(response.header("ETag")),
                    )
                }
                else -> throw IOException("Catalog HTTP ${response.code}")
            }
        }
    }
}

private val entityTagPattern = Regex("(?:W/)?\"[!#-~]*\"")

internal fun entityTag(value: String?): String? =
    value?.takeIf { it.length <= 512 && entityTagPattern.matches(it) }

internal fun InputStream.readCatalogBytes(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var remaining = maxBytes
    while (true) {
        val count = read(buffer, 0, if (remaining < buffer.size) remaining + 1 else buffer.size)
        if (count == -1) return output.toByteArray()
        if (count > remaining) throw IOException("Catalog exceeds size limit")
        output.write(buffer, 0, count)
        remaining -= count
    }
}
