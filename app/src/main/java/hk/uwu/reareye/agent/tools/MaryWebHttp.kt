package hk.uwu.reareye.agent.tools

import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.URISyntaxException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okio.Buffer

/** Only carries messages that are safe to place in the model's context. */
internal class MaryWebFailure(message: String) : IOException(message)

internal const val MARY_WEB_MAX_URL_CHARS = 2_048
private const val MAX_REDIRECTS = 3
private const val MAX_BODY_BYTES = 512 * 1024L
private const val OPERATION_TIMEOUT_MS = 18_000L
private val redirectStatus = setOf(301, 302, 303, 307, 308)
private val localDomainEnds = listOf("localhost", "local", "localdomain", "internal", "lan", "home", "home.arpa", "onion", "test", "example", "invalid")

internal class MaryWebAddressPolicy(
    private val systemDns: Dns = Dns.SYSTEM,
    private val allowsAddress: (InetAddress) -> Boolean = ::isPublicMaryWebAddress,
    private val standardPortsOnly: Boolean = true,
    private val fixedHttpsHosts: Set<String> = emptySet(),
) {
    fun url(input: String, previous: HttpUrl? = null): HttpUrl {
        if (input.isEmpty() || input.length > MARY_WEB_MAX_URL_CHARS || '\\' in input) invalidUrl()
        val parsed = try {
            URI(input)
        } catch (_: URISyntaxException) {
            invalidUrl()
        }
        if (parsed.rawAuthority?.contains('@') == true) invalidUrl()
        if (parsed.scheme != null || previous == null) {
            if (parsed.scheme?.lowercase() !in setOf("http", "https") || parsed.rawAuthority.isNullOrEmpty()) invalidUrl()
        } else if (input.startsWith("//") && parsed.rawAuthority.isNullOrEmpty()) {
            invalidUrl()
        }
        val url = if (previous == null) input.toHttpUrlOrNull() else previous.resolve(input)
        val target = url?.newBuilder()?.fragment(null)?.build() ?: invalidUrl()
        validate(target)
        return target
    }

    fun validate(url: HttpUrl) {
        if (url.toString().length > MARY_WEB_MAX_URL_CHARS || url.username.isNotEmpty() || url.password.isNotEmpty()) invalidUrl()
        if (fixedHttpsHosts.isNotEmpty() && !isFixedHttpsEndpoint(url)) invalidUrl()
        val defaultPort = if (url.isHttps) 443 else 80
        if (standardPortsOnly && url.port != defaultPort) {
            throw MaryWebFailure("只允许使用标准端口的公开 HTTP(S) 地址。")
        }
        val host = url.host.trimEnd('.')
        if (host.isEmpty() || url.host.endsWith("..")) invalidUrl()
        when {
            ':' in host -> checkLiteral(host)
            host.all { it == '.' || it in '0'..'9' } -> {
                val octets = host.split('.')
                if (octets.size != 4 || octets.any {
                        it.isEmpty() || it.length > 3 || (it.length > 1 && it[0] == '0') || it.toInt() > 255
                    }) invalidUrl()
                checkLiteral(host)
            }
            '.' !in host || localDomainEnds.any { host == it || host.endsWith(".$it") } -> blockedHost()
        }
    }

    private fun checkLiteral(host: String) {
        // This branch receives only numeric IP literals. No hostname lookup is needed here.
        val address = try {
            InetAddress.getByName(host)
        } catch (_: UnknownHostException) {
            invalidUrl()
        }
        if (!allowsAddress(address)) blockedHost()
    }

    val dns: Dns = Dns { hostname ->
        // Resolve absolute names, not local DNS search suffixes. Return the checked addresses to
        // OkHttp for the actual connection, rather than validating in a separate preliminary lookup.
        val name = if (':' in hostname || hostname.all { it == '.' || it in '0'..'9' }) hostname
        else hostname.trimEnd('.') + "."
        systemDns.lookup(name).also { addresses ->
            if (addresses.isEmpty()) throw UnknownHostException()
            // A fixed HTTPS service can use a VPN's DNS mapping. Its hostname and certificate
            // still have to match; arbitrary page URLs never use this policy.
            if (hostname !in fixedHttpsHosts && addresses.any { !allowsAddress(it) }) blockedHost()
        }
    }

    private fun isFixedHttpsEndpoint(url: HttpUrl) = url.isHttps && url.port == 443 && url.host in fixedHttpsHosts

    fun verifyConnectedAddress(url: HttpUrl, address: InetAddress?) {
        if (address == null || (!isFixedHttpsEndpoint(url) && !allowsAddress(address))) blockedHost()
    }
}

private fun invalidUrl(): Nothing = throw MaryWebFailure("需要有效的公开 HTTP(S) 地址；不能包含账号或密码。")
private fun blockedHost(): Nothing = throw MaryWebFailure("不允许访问本机、内网或保留地址。")

internal fun isPublicMaryWebAddress(address: InetAddress): Boolean {
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) return false

    val bytes = address.address
    fun octet(index: Int) = bytes[index].toInt() and 0xff
    val a = octet(0)
    val b = octet(1)
    val c = octet(2)
    return when (bytes.size) {
        4 -> when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false // Carrier-grade NAT.
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && (b == 168 || (b == 0 && c in setOf(0, 2)) || (b == 88 && c == 99)) -> false
            a == 198 && (b in 18..19 || (b == 51 && c == 100)) -> false
            a == 203 && b == 0 && c == 113 -> false
            else -> true
        }
        16 -> when {
            a !in 0x20..0x3f -> false // Only global unicast; excludes mapped, NAT64 and private forms.
            a == 0x20 && b == 0x01 && (c <= 0x01 || (c == 0x0d && octet(3) == 0xb8)) -> false
            a == 0x20 && b == 0x02 -> false // 6to4 encapsulates an IPv4 address.
            a == 0x3f && b == 0xff && c < 0x10 -> false
            else -> true
        }
        else -> false
    }
}

internal data class MaryWebPage(
    val url: HttpUrl,
    val contentType: MediaType?,
    val bytes: ByteArray,
    val truncated: Boolean,
)

internal class MaryWebHttp(private val policy: MaryWebAddressPolicy = MaryWebAddressPolicy()) {
    private val client = OkHttpClient.Builder()
        .dns(policy.dns)
        .proxy(Proxy.NO_PROXY)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            policy.verifyConnectedAddress(request.url, chain.connection()?.route()?.socketAddress?.address)
            val response = chain.proceed(request)
            // OkHttp can repeat a request on 503 Retry-After: 0 even when connection retries are off.
            if (response.code == 503) response.newBuilder().removeHeader("Retry-After").build() else response
        }
        .build()

    private sealed interface Attempt {
        data class Redirect(val location: String) : Attempt
        data class Content(val page: MaryWebPage) : Attempt
    }

    fun url(input: String): HttpUrl = policy.url(input)

    suspend fun get(
        initial: HttpUrl,
        accept: String,
        allowRedirect: (HttpUrl) -> Boolean = { true },
        acceptLanguage: String? = null,
        supported: (MediaType?) -> Boolean,
    ): MaryWebPage = withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
        var url = initial
        repeat(MAX_REDIRECTS + 1) { hop ->
            policy.validate(url)
            val request = Request.Builder().url(url).header("Accept", accept).apply {
                if (acceptLanguage != null) header("Accept-Language", acceptLanguage)
            }.build()
            when (val attempt = client.newCall(request).awaitAttempt(supported)) {
                is Attempt.Content -> return@withTimeoutOrNull attempt.page
                is Attempt.Redirect -> {
                    if (hop == MAX_REDIRECTS) throw MaryWebFailure("网页重定向过多。")
                    val next = policy.url(attempt.location, url)
                    if (url.isHttps && !next.isHttps) throw MaryWebFailure("不允许从 HTTPS 重定向到 HTTP。")
                    if (!allowRedirect(next)) throw MaryWebFailure("搜索服务返回了其他来源的重定向。")
                    url = next
                }
            }
        }
        error("Unreachable redirect state")
    } ?: throw MaryWebFailure("联网请求超时。")

    private suspend fun Call.awaitAttempt(supported: (MediaType?) -> Boolean): Attempt =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    // Keep cancellation attached to the call until the body has been consumed.
                    val result = runCatching {
                        response.use {
                            val source = response.body.source()
                            when {
                                response.code in redirectStatus -> Attempt.Redirect(
                                    response.header("Location") ?: throw MaryWebFailure("网页重定向缺少目标地址。"),
                                )
                                !response.isSuccessful -> throw MaryWebFailure("网站返回 HTTP ${response.code}。")
                                else -> {
                                    val contentType = response.body.contentType()
                                    if (!supported(contentType)) throw MaryWebFailure("不支持该响应的内容类型。")
                                    val buffer = Buffer()
                                    while (buffer.size <= MAX_BODY_BYTES && continuation.isActive) {
                                        if (source.read(buffer, minOf(8192L, MAX_BODY_BYTES + 1 - buffer.size)) == -1L) break
                                    }
                                    val truncated = response.code == 206 || buffer.size > MAX_BODY_BYTES
                                    Attempt.Content(MaryWebPage(call.request().url, contentType,
                                        buffer.readByteArray(minOf(buffer.size, MAX_BODY_BYTES)), truncated))
                                }
                            }
                        }
                    }
                    continuation.resumeWith(result)
                }
            })
        }
}
