package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.model.AgentError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Response
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Type-checked JSON navigation helpers.
 *
 * `JsonNull` is a real object rather than a Kotlin null, so `element["key"]?.jsonObject` still
 * throws when a provider sends `"key": null`. Casting instead of unwrapping keeps every accessor
 * total: providers really do emit `"choices": null` on trailing usage frames.
 */
private fun Any?.asObject(): JsonObject? = this as? JsonObject

private fun Any?.asArray(): JsonArray? = this as? JsonArray

/** JsonNull is a JsonPrimitive, and its `contentOrNull` is null, so this is total. */
private fun Any?.asPrimitive(): JsonPrimitive? = this as? JsonPrimitive

private fun Any?.asString(): String? = asPrimitive()?.contentOrNull

private fun Any?.asInt(): Int? = asPrimitive()?.intOrNull

/**
 * Translate one OpenAI-compatible stream chunk into a [ChatEvent].
 *
 * Shape handled: `{"choices":[{"delta":{"content":…,"tool_calls":[{"index":…,"id":…,
 * "function":{"name":…,"arguments":…}}]},"finish_reason":…}],"usage":…}`
 *
 * Returns null for anything that carries nothing actionable — a keep-alive, a role-only first
 * delta, a malformed payload, or a trailing usage frame with a null `choices`.
 *
 * A tool call's `arguments` arrive as fragments and are passed through verbatim; the accumulator,
 * not this parser, is responsible for joining them.
 */
internal fun parseChunk(payload: String, json: Json): ChatEvent? {
    val root = runCatching { json.parseToJsonElement(payload) }.getOrNull().asObject() ?: return null
    val choice = root["choices"].asArray()?.firstOrNull().asObject() ?: return null

    choice["delta"].asObject()?.let { delta ->
        delta["content"].asString()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return ChatEvent.TextDelta(it) }

        val calls = delta["tool_calls"].asArray()
        if (!calls.isNullOrEmpty()) {
            val first = calls.firstOrNull().asObject() ?: return null
            val function = first["function"].asObject()
            return ChatEvent.ToolCallDelta(
                index = first["index"].asInt() ?: 0,
                id = first["id"].asString(),
                nameDelta = function?.get("name").asString(),
                argumentsDelta = function?.get("arguments").asString(),
            )
        }
    }

    choice["finish_reason"].asString()?.let { return ChatEvent.Finished(it) }
    return null
}

/** Map a non-successful HTTP response onto a user-presentable [AgentError]. */
internal fun Response.toAgentError(json: Json): AgentError {
    val detail = runCatching {
        val text = body?.string().orEmpty()
        val message = json.parseToJsonElement(text).asObject()?.get("error").asObject()?.get("message").asString()
        message ?: text.take(400).takeIf { it.isNotBlank() }
    }.getOrNull()

    val suffix = detail?.let { "：$it" }.orEmpty()
    return when (code) {
        401, 403 -> AgentError.Auth("模型服务拒绝了这个 API Key（HTTP $code）$suffix")
        404 -> AgentError.Http(code, "接口地址不存在（HTTP 404）$suffix 请检查服务地址是否包含 /v1。")
        429 -> AgentError.Http(code, "请求过于频繁或额度用尽（HTTP 429）$suffix")
        else -> AgentError.Http(code, "模型服务返回 HTTP $code$suffix")
    }
}

/** Extract model ids from a `/models` response, tolerating nulls and unexpected shapes. */
internal fun parseModelIds(payload: String, json: Json): List<String> {
    val root = runCatching { json.parseToJsonElement(payload) }.getOrNull().asObject() ?: return emptyList()
    return root["data"].asArray()
        ?.mapNotNull { it.asObject()?.get("id").asString() }
        ?.sorted()
        .orEmpty()
}

/**
 * Turn a transport failure into something actionable.
 *
 * The outermost exception is usually a generic wrapper whose message ("Software caused connection
 * abort", "unexpected end of stream") says nothing about the cause, so walk to the root and name
 * the actual condition. OkHttp wraps TLS and HTTP/2 failures several layers deep.
 */
internal fun Throwable.toNetworkError(baseUrl: String): AgentError {
    val root = rootCause()
    val type = root.javaClass.simpleName
    val detail = root.message?.takeIf { it.isNotBlank() } ?: type

    val friendly = when (root) {
        is UnknownHostException ->
            "找不到主机 ${root.message.orEmpty()}。请检查服务地址拼写和网络。"

        is ConnectException ->
            "无法建立连接（${root.message ?: "connection refused"}）。请确认地址和端口可达。"

        is NoRouteToHostException ->
            "网络不可达。请检查当前网络。"

        is SocketTimeoutException ->
            "连接超时。服务没有在限定时间内响应。"

        is SSLException ->
            "TLS 握手失败：$type: $detail"

        is SocketException ->
            "连接被中断：$type: $detail"

        is EOFException, is InterruptedIOException ->
            "连接被提前关闭：$type: $detail"

        else -> "$type: $detail"
    }

    return AgentError.Network("$friendly（地址 ${trimForDisplay(baseUrl)}）", this)
}

private fun Throwable.rootCause(): Throwable {
    var current: Throwable = this
    var guard = 0
    while (guard++ < 12) {
        val next = current.cause
        if (next == null || next === current) break
        current = next
    }
    return current
}

/** Show only scheme, host and port so a bad path is obvious without leaking the whole URL. */
private fun trimForDisplay(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    return runCatching {
        val scheme = trimmed.substringBefore("://", "")
        val rest = trimmed.substringAfter("://", trimmed)
        val authority = rest.substringBefore('/')
        if (scheme.isEmpty()) authority else "$scheme://$authority"
    }.getOrDefault(trimmed.take(80))
}
