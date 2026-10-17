package hk.uwu.reareye.agent.provider

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.model.AgentError
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicBoolean

/** A turn-scoped native connection. The supplied OkHttp client remains owned by the caller. */
class KoogConnection(
    val executor: PromptExecutor,
    val model: LLModel,
    val params: LLMParams,
    private val closeResources: () -> Unit = { executor.close() },
) : AutoCloseable {
    private val closed = AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) closeResources()
    }
}

internal class NativeConnection(
    val connection: KoogConnection,
    val client: LLMClient,
    val transport: KoogHttpClient,
) {
    suspend fun contextWindow(model: String): Int? {
        val payload = transport.get(
            "models/${
                java.net.URLEncoder.encode(model, "UTF-8").replace("+", "%20")
            }", JsonObject::class
        )
        return payload["max_input_tokens"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
    }
}

internal fun nativeConnection(client: OkHttpClient, config: ProviderConfig): NativeConnection {
    if (config.baseUrl.isBlank()) throw AgentError.NotConfigured("还没有配置模型服务地址。")
    // Ktor 3.3.3 shuts down derived dispatchers AND evicts their pools on close.
    // Inherit caller interceptors/proxy/TLS, but isolate its owned pool from the application pool.
    val calls = TrackedCalls()
    val trackedClient = client.newBuilder().addInterceptor { chain ->
        val request = chain.request()
        calls.attach(chain.call())
        chain.proceed(request)
    }.build()
    val ktor = HttpClient(OkHttp) {
        engine {
            preconfigured = trackedClient
            config { connectionPool(okhttp3.ConnectionPool()); dispatcher(calls.dispatcher) }
        }
    }
    val factory = KtorKoogHttpClient.Factory(ktor)
    val provider =
        if (config.protocol == Protocol.ANTHROPIC) LLMProvider.Anthropic else LLMProvider.OpenAI
    val capabilities = mutableListOf<LLMCapability>(
        LLMCapability.Completion,
        LLMCapability.Tools,
        LLMCapability.Thinking
    )
    if (config.protocol != Protocol.ANTHROPIC) capabilities += if (config.protocol == Protocol.RESPONSES)
        LLMCapability.OpenAIEndpoint.Responses else LLMCapability.OpenAIEndpoint.Completions
    val model = LLModel(
        provider, config.model, capabilities, contextLength = config.contextWindowTokens.toLong(),
        maxOutputTokens = config.maxOutputTokens?.toLong()
    )
    val timeout = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(
        requestTimeoutMillis = client.callTimeoutMillis.toLong().takeIf { it > 0 }
            ?: Long.MAX_VALUE,
        connectTimeoutMillis = client.connectTimeoutMillis.toLong().takeIf { it > 0 }
            ?: Long.MAX_VALUE,
        socketTimeoutMillis = client.readTimeoutMillis.toLong().takeIf { it > 0 } ?: Long.MAX_VALUE,
    )
    var transport: KoogHttpClient? = null
    val capturingFactory = object : KoogHttpClient.Factory by factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: kotlinx.serialization.json.Json
        ): KoogHttpClient =
            ProtocolCompatibilityTransport(
                factory.create(
                clientName,
                baseUrl,
                headers.filter { (key, value) ->
                    value.isNotBlank() && !(config.apiKey.isBlank() && key.equals(
                        "Authorization",
                        ignoreCase = true
                    ))
                },
                queryParameters,
                requestTimeoutMillis,
                connectTimeoutMillis,
                socketTimeoutMillis,
                json
            ),
                config.protocol,
                calls,
                runCatching { java.net.URI(config.baseUrl).host == "api.openai.com" }.getOrDefault(
                    false
                ),
                config
            )
                .also { transport = it }
    }
    val llm: LLMClient = try {
        if (config.protocol == Protocol.ANTHROPIC) {
            val settings = AnthropicClientSettings(
                modelVersionsMap = mapOf(model to config.model),
                baseUrl = config.baseUrl.trimEnd('/') + "/",
                messagesPath = "messages",
                modelsPath = "models",
                timeoutConfig = timeout
            )
            PatchedAnthropicClient(config.apiKey, settings, capturingFactory)
        } else {
            PatchedOpenAIClient(
                config.apiKey, OpenAIClientSettings(
                    baseUrl = config.baseUrl.trimEnd('/') + "/",
                    chatCompletionsPath = "chat/completions",
                    responsesAPIPath = "responses",
                    modelsPath = "models",
                    timeoutConfig = timeout
                ), capturingFactory
            )
        }
    } catch (error: Throwable) {
        ktor.close(); throw error
    }
    val params: LLMParams = try {
        when (config.protocol) {
            Protocol.RESPONSES -> OpenAIResponsesParams(
                temperature = config.temperature, maxTokens = config.maxOutputTokens,
                store = false, additionalProperties = mapOf(
                    "include" to kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.JsonPrimitive("reasoning.encrypted_content")
                        )
                    )
                )
            )

            Protocol.CHAT_COMPLETIONS -> OpenAIChatParams(
                temperature = config.temperature,
                maxTokens = config.maxOutputTokens
            )

            Protocol.ANTHROPIC -> LLMParams(
                temperature = config.temperature,
                maxTokens = config.maxOutputTokens ?: 4096
            )
        }
    } catch (error: Throwable) {
        calls.close()
        try {
            llm.close()
        } finally {
            ktor.close()
        }
        throw error
    }
    val executor = MultiLLMPromptExecutor(llm)
    val connection = KoogConnection(executor, model, params) {
        try {
            calls.close(); executor.close()
        } finally {
            ktor.close()
        }
    }
    return NativeConnection(connection, llm, requireNotNull(transport))
}
