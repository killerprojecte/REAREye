package hk.uwu.reareye.agent.provider

import ai.koog.http.client.KoogHttpClientException
import ai.koog.prompt.Prompt
import ai.koog.prompt.streaming.StreamFrame
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.koog.KoogTranscriptCodec
import hk.uwu.reareye.agent.loop.toKoogDescriptor
import hk.uwu.reareye.agent.model.AgentError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import java.io.IOException

/** UI/auxiliary compatibility surface only. The graph consumes KoogConnection directly. */
internal class KoogProviderAdapter(
    private val client: OkHttpClient,
    private val config: () -> ProviderConfig
) : LlmProvider {
    override val id = "koog-native"
    override fun chat(request: ChatRequest) = flow {
        val settings = config()
        val selected = settings.copy(
            model = request.model, temperature = request.temperature ?: settings.temperature,
            maxOutputTokens = request.maxOutputTokens ?: settings.maxOutputTokens
        )
        val native = nativeConnection(client, selected)
        try {
            val callNames = mutableMapOf<Int, StringBuilder>()
            val callArguments = mutableMapOf<Int, StringBuilder>()
            val callIds = mutableMapOf<Int, String>()
            val prompt = Prompt(
                KoogTranscriptCodec.toNative(request.messages),
                "ui-auxiliary",
                native.connection.params
            )
            native.connection.executor.executeStreaming(
                prompt,
                native.connection.model,
                request.tools.map { it.toKoogDescriptor() })
                .collect { frame ->
                    when (frame) {
                        is StreamFrame.TextDelta -> if (frame.text.isNotEmpty()) emit(
                            ChatEvent.TextDelta(
                                frame.text
                            )
                        )

                        is StreamFrame.ReasoningDelta -> (frame.text
                            ?: frame.summary)?.takeIf { it.isNotEmpty() }
                            ?.let { emit(ChatEvent.ReasoningDelta(it)) }

                        is StreamFrame.ToolCallDelta -> {
                            val index = frame.index ?: 0
                            frame.id?.let { callIds[index] = it }
                            frame.name?.let {
                                callNames.getOrPut(index) { StringBuilder() }.append(it)
                            }
                            frame.content?.let {
                                callArguments.getOrPut(index) { StringBuilder() }.append(it)
                            }
                            emit(
                                ChatEvent.ToolCallDelta(
                                    index,
                                    frame.id,
                                    frame.name,
                                    frame.content
                                )
                            )
                        }

                        is StreamFrame.ToolCallComplete -> {
                            val index = frame.index ?: 0
                            val seenName = callNames[index]?.toString().orEmpty()
                            val seenArgs = callArguments[index]?.toString().orEmpty()
                            require(
                                frame.name.startsWith(seenName) && frame.content.startsWith(
                                    seenArgs
                                )
                            ) { "Tool completion differs from streamed fragments" }
                            val name = frame.name.removePrefix(seenName).takeIf { it.isNotEmpty() }
                            val args =
                                frame.content.removePrefix(seenArgs).takeIf { it.isNotEmpty() }
                            if (name != null || args != null || frame.id != callIds[index])
                                emit(ChatEvent.ToolCallDelta(index, frame.id, name, args))
                        }

                        is StreamFrame.ReasoningComplete -> {
                            val item = buildJsonObject {
                                if (selected.protocol == Protocol.ANTHROPIC) {
                                    if (frame.encrypted?.startsWith(REDACTED_PREFIX) == true) {
                                        put("type", "redacted_thinking"); put(
                                            "data",
                                            requireNotNull(frame.encrypted).removePrefix(
                                                REDACTED_PREFIX
                                            )
                                        )
                                    } else {
                                        put("type", "thinking"); put(
                                            "thinking",
                                            frame.content.joinToString("")
                                        ); put("signature", frame.encrypted.orEmpty())
                                    }
                                } else {
                                    put("type", "reasoning"); frame.id?.let { put("id", it) }
                                    putJsonArray("summary") {
                                        frame.summary.orEmpty().forEach { text ->
                                            add(buildJsonObject {
                                                put("type", "summary_text"); put("text", text)
                                            })
                                        }
                                    }
                                    frame.encrypted?.let { put("encrypted_content", it) }
                                }
                            }
                            emit(ChatEvent.ProtocolItem(item))
                        }

                        is StreamFrame.End -> {
                            val usage = frame.metaInfo.metadata?.get("usage") as? JsonObject
                            fun JsonObject?.count(key: String) =
                                (this?.get(key) as? JsonPrimitive)?.intOrNull

                            val cached = when (selected.protocol) {
                                Protocol.ANTHROPIC -> usage.count("cache_read_input_tokens")
                                Protocol.RESPONSES -> (usage?.get("input_tokens_details") as? JsonObject).count(
                                    "cached_tokens"
                                )

                                else -> (usage?.get("prompt_tokens_details") as? JsonObject).count("cached_tokens")
                                    ?: usage.count("prompt_cache_hit_tokens")
                            }
                            val creation = usage.count("cache_creation_input_tokens")
                            val input =
                                if (selected.protocol == Protocol.ANTHROPIC) usage.count("input_tokens")
                                    ?.let {
                                        it + (cached ?: 0) + (creation ?: 0)
                                    } else frame.metaInfo.inputTokensCount
                            if (input != null || frame.metaInfo.outputTokensCount != null || cached != null || creation != null)
                                emit(
                                    ChatEvent.Usage(
                                        input,
                                        frame.metaInfo.outputTokensCount,
                                        cached,
                                        creation
                                    )
                                )
                            emit(ChatEvent.Finished(frame.finishReason))
                        }

                        else -> Unit
                    }
                }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw error.asAgentError(selected)
        } finally {
            native.connection.close()
        }
    }

    override suspend fun listModels(): List<String> = listModelDetails().map { it.id }

    override suspend fun listModelDetails(): List<hk.uwu.reareye.agent.ModelDescriptor> {
        val settings = config()
        val native = nativeConnection(client, settings)
        return try {
            val models = mutableListOf<hk.uwu.reareye.agent.ModelDescriptor>()
            val cursors = mutableSetOf<String>()
            var after: String? = null
            // Bound pagination, and never follow service-supplied URLs outside the configured base.
            for (page in 1..100) {
                val payload = native.transport.get(
                    "models", JsonObject::class,
                    parameters = after?.let { mapOf("after_id" to it) }.orEmpty()
                )
                require(payload["error"] == null || payload["error"] == JsonNull) { "GET models returned an error payload: ${payload["error"]}" }
                val data = payload["data"] as? JsonArray
                    ?: throw IllegalArgumentException("GET models response must contain a data array")
                val ids = data.mapIndexed { index, item ->
                    val id = (item as? JsonObject)?.get("id") as? JsonPrimitive
                    require(id != null && id.isString && id.content.isNotBlank()) { "GET models data[$index] must contain a nonblank string id" }
                    id.content
                }
                models += data.map {
                    hk.uwu.reareye.agent.ModelCapabilityParser.parse(
                        it as JsonObject,
                        settings
                    )
                }
                val hasMore = payload["has_more"]
                require(settings.protocol != Protocol.ANTHROPIC || hasMore == null || (hasMore is JsonPrimitive && !hasMore.isString && hasMore.booleanOrNull != null)) {
                    "GET models has_more must be boolean"
                }
                if (settings.protocol != Protocol.ANTHROPIC || hasMore?.jsonPrimitive?.booleanOrNull != true)
                    return models.distinctBy { it.id }.sortedBy { it.id }
                val cursor = (payload["last_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                require(ids.isNotEmpty() && !cursor.isNullOrBlank() && cursors.add(cursor)) { "GET models pagination has no advancing last_id" }
                after = cursor
            }
            throw IllegalArgumentException("GET models exceeded 100 pagination pages")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw error.asAgentError(settings, "GET /models")
        } finally {
            native.connection.close()
        }
    }

    override suspend fun modelContextWindow(model: String): Int? {
        val settings = config()
        if (settings.protocol != Protocol.ANTHROPIC) return null
        val native = nativeConnection(client, settings)
        return try {
            native.contextWindow(model)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw error.asAgentError(
                settings,
                "GET /models/${java.net.URLEncoder.encode(model, "UTF-8").replace("+", "%20")}"
            )
        } finally {
            native.connection.close()
        }
    }
}

internal fun Throwable.asAgentError(
    config: ProviderConfig,
    operation: String = "provider request"
): AgentError {
    if (this is AgentError) return this
    val causes = providerCauses(this)
    val http = causes.filterIsInstance<KoogHttpClientException>().firstOrNull()
    val detail = ProviderRequestDiagnostics.format(this, config, operation)
    // Keep the original structured native status/body, including for Auth; never truncate it to 400.
    if (http?.statusCode != null) {
        val code = http.statusCode!!
        return if (code == 401 || code == 403) AgentError.Auth(detail, this) else AgentError.Http(
            code,
            detail,
            this
        )
    }
    if (causes.any { it is IOException }) {
        val friendly = when {
            causes.any { it is java.net.UnknownHostException } -> "找不到主机"
            causes.any { it is java.net.SocketTimeoutException } -> "连接超时"
            causes.any { it is javax.net.ssl.SSLException } -> "TLS 握手失败"
            else -> "模型服务连接失败"
        }
        return AgentError.Network("$friendly\n$detail", this)
    }
    return AgentError.Protocol(detail, this)
}
