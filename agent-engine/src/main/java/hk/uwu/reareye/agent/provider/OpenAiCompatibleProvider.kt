package hk.uwu.reareye.agent.provider

import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.model.AgentError
import hk.uwu.reareye.agent.model.ChatMessage
import hk.uwu.reareye.agent.model.MessageRole
import hk.uwu.reareye.agent.model.ToolCall
import hk.uwu.reareye.agent.model.ToolSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to any endpoint implementing OpenAI's `/chat/completions` streaming API — DeepSeek,
 * OpenAI, OpenRouter, Kimi, Ollama, and the many compatible proxies.
 *
 * The stream is consumed incrementally; nothing is buffered whole. Cancelling collection of the
 * returned [Flow] cancels the underlying HTTP call.
 *
 * [config] is read at call time, so the settings page can swap the endpoint, key or model without
 * rebuilding the provider.
 */
class OpenAiCompatibleProvider(
    private val client: OkHttpClient,
    private val configProvider: () -> ProviderConfig,
    private val json: Json = DEFAULT_JSON,
) : LlmProvider {

    override val id: String = "openai-compatible"

    override fun chat(request: ChatRequest): Flow<ChatEvent> = flow {
        val config = configProvider()
        if (config.baseUrl.isBlank()) {
            throw AgentError.NotConfigured("还没有配置模型服务地址。")
        }

        val httpRequest = Request.Builder()
            .url(config.chatCompletionsUrl())
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
            .header("Accept", "text/event-stream")
            .post(buildRequestBody(request).toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val response = try {
            client.newCall(httpRequest).execute()
        } catch (io: IOException) {
            throw io.toNetworkError(config.baseUrl)
        }

        try {
            response.use { res ->
                if (!res.isSuccessful) throw res.toAgentError(json)
                val body = res.body ?: throw AgentError.Protocol("模型返回了空响应体。")
                val reader = SseReader.of(body.source())
                while (true) {
                    val event = reader.next() ?: break
                    val payload = event.data.trim()
                    if (payload.isEmpty()) continue
                    if (payload == DONE_SENTINEL) break
                    parseChunk(payload, json)?.let { emit(it) }
                }
            }
        } catch (io: IOException) {
            // The stream can also break mid-body (server reset, proxy idle timeout).
            throw io.toNetworkError(config.baseUrl)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(): List<String> = with(Dispatchers.IO) {
        val config = configProvider()
        if (config.baseUrl.isBlank()) return@with emptyList()
        val request = Request.Builder()
            .url(config.modelsUrl())
            .apply { if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}") }
            .get()
            .build()
        try {
            client.newCall(request).execute().use { res ->
                if (!res.isSuccessful) throw res.toAgentError(json)
                parseModelIds(res.body?.string().orEmpty(), json)
            }
        } catch (io: IOException) {
            throw io.toNetworkError(config.baseUrl)
        }
    }

    private fun buildRequestBody(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        put("stream", true)
        putJsonArray("messages") { request.messages.forEach { add(messageJson(it)) } }
        request.temperature?.let { put("temperature", it) }
        request.maxOutputTokens?.let { put("max_tokens", it) }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") { request.tools.forEach { add(toolJson(it)) } }
        }
    }

    private fun messageJson(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", message.role.wire())
        when (message.role) {
            MessageRole.TOOL -> {
                put("tool_call_id", message.toolCallId ?: "")
                put("content", message.content ?: "")
            }

            MessageRole.ASSISTANT -> {
                // An empty content alongside tool_calls is the documented assistant shape.
                put("content", message.content ?: "")
                if (message.toolCalls.isNotEmpty()) {
                    putJsonArray("tool_calls") {
                        message.toolCalls.forEach { call ->
                            add(
                                buildJsonObject {
                                    put("id", call.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", call.name)
                                        put(
                                            "arguments",
                                            call.argumentsJson.ifBlank { ToolCall.BLANK_ARGS },
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }

            else -> {
                put("content", message.content ?: "")
                message.name?.let { put("name", it) }
            }
        }
    }

    private fun toolJson(spec: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", spec.name)
            put("description", spec.description)
            put("parameters", spec.parametersSchema())
        }
    }

    companion object {
        private const val DONE_SENTINEL = "[DONE]"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // Streaming responses are long-lived: no overall call timeout, but fail fast on a dead
            // connection and give up when no bytes arrive for a while.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        private fun MessageRole.wire(): String = when (this) {
            MessageRole.SYSTEM -> "system"
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.TOOL -> "tool"
        }
    }
}
