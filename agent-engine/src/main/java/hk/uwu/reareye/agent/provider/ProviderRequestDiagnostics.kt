package hk.uwu.reareye.agent.provider

import ai.koog.http.client.KoogHttpClientException
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.model.AgentError
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64
import java.util.Collections
import java.util.IdentityHashMap

internal fun providerCauses(error: Throwable): List<Throwable> {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    val result = mutableListOf<Throwable>()
    var current: Throwable? = error
    while (current != null && result.size < 16 && seen.add(current)) {
        result += current
        current = current.cause
    }
    return result
}

/** Bounded, credential-safe text for display/copy. Never logs the unredacted exception. */
object ProviderRequestDiagnostics {
    private const val LIMIT = 16 * 1024
    private const val REDACTED = "[REDACTED]"
    private val secretName =
        "(?:authorization|proxy[-_]?authorization|x[-_]?api[-_]?key|api[-_]?key|access[-_]?token|refresh[-_]?token|token|secret|key|password)"
    private val assignment =
        Regex("(?i)([\\\"']?" + secretName + "[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s&,;\\\\}\\]]+)")
    private val bearer = Regex("(?i)\\b(?:Bearer|Basic)\\s+[^\\s\\\"',;<>}\\]]+")
    private val url = Regex("(?i)https?://[^\\s\\\"'<>]+")

    fun format(error: Throwable, config: ProviderConfig, operation: String): String {
        val secrets = mutableSetOf<String>()
        if (config.apiKey.isNotEmpty()) secrets += config.apiKey
        runCatching { URI(config.baseUrl) }.getOrNull()?.let { endpoint ->
            endpoint.rawUserInfo?.let { secrets += it; secrets += decode(it) }
            endpoint.rawQuery?.split('&')?.forEach { pair ->
                val name = decode(pair.substringBefore('='))
                if (Regex("(?i)^$secretName$").matches(name)) {
                    val value = pair.substringAfter('=', "")
                    if (value.isNotEmpty()) {
                        secrets += value; secrets += decode(value)
                    }
                }
            }
        }
        val variants = secrets.filter { it.isNotEmpty() }.flatMap { value ->
            listOf(
                value,
                kotlinx.serialization.json.JsonPrimitive(value).toString().removeSurrounding("\""),
                value.replace("/", "\\/"),
                URLEncoder.encode(value, "UTF-8"),
                URLEncoder.encode(value, "UTF-8").replace("+", "%20"),
                Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)),
                Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(value.toByteArray(Charsets.UTF_8))
            )
        }.distinct().sortedByDescending { it.length }

        fun clean(value: String): String {
            var safe = value
            variants.forEach { secret -> safe = safe.replace(secret, REDACTED, ignoreCase = true) }
            safe = url.replace(safe) { match -> safeEndpoint(match.value) }
            variants.forEach { secret -> safe = safe.replace(secret, REDACTED, ignoreCase = true) }
            safe = bearer.replace(safe, "Bearer $REDACTED")
            safe = assignment.replace(safe) { it.groupValues[1] + REDACTED }
            return safe.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "?")
        }

        val causes = providerCauses(error)
        val text = buildString {
            appendLine("Operation: $operation")
            appendLine("Protocol: ${config.protocol}")
            appendLine("Endpoint: ${safeEndpoint(config.baseUrl)}")
            appendLine("Model: ${config.model.ifBlank { "(not selected)" }}")
            if (operation.startsWith("GET /"))
                appendLine(
                    "Request: ${safeEndpoint(config.baseUrl).trimEnd('/')}${
                        operation.removePrefix(
                            "GET "
                        )
                    }"
                )
            causes.filterIsInstance<KoogHttpClientException>().mapNotNull { it.statusCode }
                .distinct()
                .forEach { appendLine("HTTP status: $it") }
            causes.forEachIndexed { index, cause ->
                var message = cause.message.orEmpty()
                // The adapter may already have formatted this wrapper for direct display. Avoid
                // nesting that entire report when UI formats it again; retain the friendly prefix.
                if (cause is AgentError && cause.cause != null &&
                    (message.startsWith("Operation:") || message.contains("\nOperation:")) && message.contains(
                        "\nProtocol:"
                    )
                ) {
                    message.lineSequence().firstOrNull { it.startsWith("Request:") }
                        ?.let { appendLine(it) }
                    message = message.substringBefore("Operation:").trim()
                        .ifEmpty { "(native cause follows)" }
                }
                if (cause is KoogHttpClientException) cause.errorBody?.takeIf { it.isNotEmpty() }
                    ?.let {
                        message = message.replace(it, "(see response body below)")
                    }
                appendLine("Cause[$index]: ${cause.javaClass.name}: $message")
                if (cause is AgentError.Http) appendLine("HTTP status: ${cause.statusCode}")
            }
            causes.filterIsInstance<KoogHttpClientException>().mapNotNull { it.errorBody }
                .distinct()
                .forEach { appendLine("Response body: $it") }
        }
        val safe = clean(text)
        return if (safe.length <= LIMIT) safe else safe.take(LIMIT - 32) + "\n… [diagnostics truncated]"
    }

    private fun decode(value: String): String = runCatching {
        // Percent encoding only: a literal '+' in an exception or model ID must survive.
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }.getOrDefault(value)

    private fun safeEndpoint(value: String): String = runCatching {
        val uri = URI(value)
        require(uri.scheme in listOf("http", "https") && uri.rawAuthority != null)
        // Drop all query and fragment data, not just known secret parameter names.
        "${uri.scheme}://${uri.rawAuthority.substringAfterLast('@')}${uri.rawPath.orEmpty()}"
    }.getOrDefault("(invalid endpoint)")
}
