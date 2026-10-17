/*
 * SPDX-License-Identifier: Apache-2.0
 * Compatibility with JetBrains Koog 1.3.0 (Apache License 2.0).
 * Source: JetBrains/koog tag 1.3.0, prompt-executor-{anthropic,openai}-client.
 * Native clients retain ownership of request encoding, DTO decoding, and SSE transport.
 * This isolated shim fixes missing signature/redacted/encrypted-only streaming and usage fields.
 * Remove once the corresponding upstream fixes are available in a pinned release.
 */
package hk.uwu.reareye.agent.provider

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import hk.uwu.reareye.agent.Protocol
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

internal const val REDACTED_PREFIX = "reareye:anthropic:redacted:"

/** Only default omitted metadata; native Koog decoding still validates every supplied value. */
internal fun responsesMetadataDefaults(response: JsonObject): JsonObject {
    if (response.containsKey("parallel_tool_calls") && response.containsKey("text")) return response
    val fields = response.toMutableMap()
    fields.putIfAbsent("parallel_tool_calls", JsonPrimitive(true))
    fields.putIfAbsent("text", JsonObject(emptyMap()))
    return JsonObject(fields)
}

/** A connection owns one dispatcher and serializes its native operations, never the caller's Call pool.
 * Failed/cancelled connections are poisoned before releasing their lease, so no waiting request can
 * be accidentally included in cancelAll, and late interceptor attachment is cancelled immediately. */
internal class TrackedCalls : AutoCloseable {
    val dispatcher = okhttp3.Dispatcher()
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    private val attached = mutableSetOf<okhttp3.Call>()
    suspend fun <T> request(block: suspend () -> T): T = mutex.withLock {
        check(!closed.get()) { "Connection is closed; create a new native connection" }
        try {
            block().also { synchronized(attached) { attached.clear() } }
        } catch (error: Throwable) {
            close(); throw error
        }
    }

    fun attach(call: okhttp3.Call) {
        val cancel = synchronized(attached) {
            if (closed.get()) true else {
                attached += call; false
            }
        }
        if (cancel) call.cancel()
    }

    override fun close() {
        // OkHttp removes asynchronous Calls from Dispatcher at onResponse, before an SSE body is
        // consumed. Keep our references until the native operation consumes/closes the whole body.
        val calls =
            synchronized(attached) { closed.set(true); attached.toList().also { attached.clear() } }
        calls.forEach { it.cancel() }
        dispatcher.cancelAll()
    }
}

private class ProtocolState : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProtocolState>
    data class Thinking(
        val index: Int,
        val text: StringBuilder = StringBuilder(),
        val signature: StringBuilder = StringBuilder()
    )

    val thinking = linkedMapOf<Int, Thinking>()
    val blockTypes = mutableMapOf<Int, String>()
    val ready = ArrayDeque<StreamFrame>()
    var emitReady: (suspend (StreamFrame) -> Unit)? = null
    val responseTextDeltas = mutableMapOf<Int, StringBuilder>()
    val responseCalls = java.util.concurrent.ConcurrentHashMap<Int, Pair<String, String>>()
    val encryptedOnly = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    val usage = linkedMapOf<String, JsonElement>()
    var finishReason: String? = null
    var stopped = false
    var ended = false
    var chatTools = false
    var responsesMetadataUsage = false

    fun meta(original: ResponseMetaInfo): ResponseMetaInfo {
        fun count(key: String) = (usage[key] as? JsonPrimitive)?.intOrNull
        val anthropic =
            usage.containsKey("cache_read_input_tokens") || usage.containsKey("cache_creation_input_tokens")
        val input = if (anthropic) count("input_tokens")?.let {
            it + (count("cache_read_input_tokens") ?: 0) + (count("cache_creation_input_tokens")
                ?: 0)
        }
            ?: original.inputTokensCount else original.inputTokensCount
            ?: if (responsesMetadataUsage) count("input_tokens") else null
        val output = original.outputTokensCount
            ?: if (responsesMetadataUsage) count("output_tokens") else null
        return original.copy(
            inputTokensCount = input,
            outputTokensCount = output,
            totalTokensCount = input?.plus(output ?: 0) ?: original.totalTokensCount,
            metadata = JsonObject(
                original.metadata.orEmpty() + if (usage.isEmpty()) emptyMap() else mapOf(
                    "usage" to JsonObject(usage)
                )
            )
        )
    }

    fun observe(raw: String, protocol: Protocol): String? {
        val parsed = Json.parseToJsonElement(raw).jsonObject
        // Koog 1.3 requires these non-null response metadata fields even on lifecycle events.
        // Its native JSON uses SnakeCase. Only absent keys are defaulted: explicit null/wrong
        // types and missing core response fields must still fail in the native DTO decoder.
        // OpenAITextConfig's format/verbosity are both nullable with null defaults, so {} is
        // the native empty configuration, not output text and not an invented format/schema.
        val response =
            if (protocol == Protocol.RESPONSES) parsed["response"] as? JsonObject else null
        val compatibleResponse =
            if (parsed["type"]?.jsonPrimitive?.contentOrNull == "response.metadata") response
            else response?.let(::responsesMetadataDefaults)
        val item = if (compatibleResponse !== response) JsonObject(
            parsed + ("response" to requireNotNull(compatibleResponse))
        ) else parsed
        val normalized = if (item === parsed) raw else item.toString()
        val type = item["type"]?.jsonPrimitive?.contentOrNull
        fun rememberUsage(value: JsonElement?) {
            (value as? JsonObject)?.forEach { (k, v) -> usage[k] = v }
        }
        if (protocol == Protocol.ANTHROPIC) {
            val index = item["index"]?.jsonPrimitive?.intOrNull
            rememberUsage(item["usage"])
            rememberUsage((item["message"] as? JsonObject)?.get("usage"))
            when (type) {
                "content_block_start" -> {
                    val block =
                        requireNotNull(item["content_block"] as? JsonObject) { "Content block is missing" }
                    val blockType = block["type"]?.jsonPrimitive?.content
                    if (index != null && blockType != null) blockTypes[index] = blockType
                    when (blockType) {
                        "thinking" -> {
                            val state = Thinking(requireNotNull(index))
                            state.text.append(block["thinking"]?.jsonPrimitive?.content.orEmpty())
                            state.signature.append(block["signature"]?.jsonPrimitive?.content.orEmpty())
                            thinking[index] = state
                            if (state.text.isNotEmpty()) ready += StreamFrame.ReasoningDelta(
                                text = state.text.toString(),
                                index = index
                            )
                            // Koog 1.3 cannot end adjacent reasoning blocks at content_block_stop.
                            // Own only these missing reasoning frames; leave all text/tool DTOs native.
                            return "{\"type\":\"ping\"}"
                        }

                        "redacted_thinking" -> {
                            val data = requireNotNull(block["data"]?.jsonPrimitive?.content)
                            thinking[requireNotNull(index)] =
                                Thinking(index, signature = StringBuilder(REDACTED_PREFIX + data))
                            // No redacted DTO exists in 1.3; preserve its opaque payload in our native
                            // ReasoningComplete and pass a recognized no-op DTO through upstream.
                            return "{\"type\":\"ping\"}"
                        }
                    }
                }

                "content_block_delta" -> {
                    val delta = item["delta"]?.jsonObject
                    when (delta?.get("type")?.jsonPrimitive?.content) {
                        "thinking_delta" -> {
                            val text = requireNotNull(delta["thinking"]?.jsonPrimitive?.content)
                            requireNotNull(thinking[index]) { "Thinking delta without a block start" }.text.append(
                                text
                            )
                            ready += StreamFrame.ReasoningDelta(text = text, index = index)
                            return "{\"type\":\"ping\"}"
                        }

                        "signature_delta" -> {
                            requireNotNull(thinking[index]) { "Signature delta without a thinking block" }.signature.append(
                                requireNotNull(delta["signature"]?.jsonPrimitive?.content)
                            )
                            return "{\"type\":\"ping\"}"
                        }
                    }
                }

                "content_block_stop" -> {
                    val blockType = blockTypes.remove(index)
                    thinking.remove(index)?.let { block ->
                        require(block.signature.isNotEmpty()) { "Anthropic thinking block has no signature" }
                        ready += StreamFrame.ReasoningComplete(
                            id = null, content = listOf(block.text.toString()),
                            encrypted = block.signature.toString(), index = block.index
                        )
                    }
                    // Native 1.3 only flushes a block stop when delta.type is present, although the
                    // official event has no delta. Supply its known type to flush the native builder
                    // before another text/tool block (including a shim-owned thinking) starts.
                    val deltaType = when (blockType) {
                        "text" -> "text_delta"; "tool_use" -> "input_json_delta"; else -> null
                    }
                    if (deltaType != null) return JsonObject(item + ("delta" to buildJsonObject {
                        put(
                            "type",
                            deltaType
                        )
                    })).toString()
                }

                "message_delta" -> finishReason =
                    item["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull

                "message_stop" -> stopped = true
            }
        } else if (protocol == Protocol.RESPONSES) {
            val response = item["response"] as? JsonObject
            rememberUsage(response?.get("usage"))
            if (type == "response.metadata") {
                // Some compatible servers add a metadata-only event unknown to Koog 1.3.
                // Do not ignore arbitrary extension semantics or let metadata end the stream.
                val identityKeys = setOf(
                    "response_id",
                    "request_id",
                    "id",
                    "object",
                    "model",
                    "service_tier",
                    "system_fingerprint"
                )
                val responseKeys = identityKeys + setOf("created_at", "metadata", "usage")
                val envelopeKeys = responseKeys + setOf("type", "sequence_number", "response")
                require(item.keys.all { it in envelopeKeys }) { "Unsupported Responses metadata event: $raw" }
                require(item["response"] == null || response != null) { "Responses metadata response must be an object" }
                require(response == null || response.keys.all { it in responseKeys }) { "Responses metadata contains non-metadata response fields: $raw" }
                for (container in listOfNotNull(item, response)) {
                    for (key in identityKeys) {
                        val value = container[key]
                        require(value == null || value == JsonNull || (value is JsonPrimitive && value.isString)) { "Responses metadata $key must be a string" }
                    }
                    for (key in listOf("created_at", "sequence_number")) {
                        val value = container[key]
                        require(value == null || (value is JsonPrimitive && !value.isString && value.longOrNull != null)) { "Responses metadata $key must be an integer" }
                    }
                    require(container["metadata"] == null || container["metadata"] == JsonNull || container["metadata"] is JsonObject) { "Responses metadata must be an object" }
                    require(container["usage"] == null || container["usage"] == JsonNull || container["usage"] is JsonObject) { "Responses metadata usage must be an object" }
                    (container["usage"] as? JsonObject)?.let { metadataUsage ->
                        for (key in listOf("input_tokens", "output_tokens", "total_tokens")) {
                            val value = metadataUsage[key] ?: continue
                            require(
                                value is JsonPrimitive && !value.isString && (value.intOrNull
                                    ?: -1) >= 0
                            ) { "Responses metadata usage $key must be a nonnegative integer" }
                        }
                    }
                }
                rememberUsage(item["usage"])
                responsesMetadataUsage = true
                return null
            }
            if (type == "response.output_item.added") {
                val output = item["item"] as? JsonObject
                if (output?.get("type")?.jsonPrimitive?.content == "function_call") {
                    val index = requireNotNull(item["output_index"]?.jsonPrimitive?.intOrNull)
                    val id = requireNotNull(output["call_id"]?.jsonPrimitive?.contentOrNull)
                    val name = requireNotNull(output["name"]?.jsonPrimitive?.contentOrNull)
                    responseCalls[index] = id to name
                    ready += StreamFrame.ToolCallDelta(id, name, null, index)
                }
            }
            if (type == "response.output_text.delta") {
                item["output_index"]?.jsonPrimitive?.intOrNull?.let { index ->
                    responseTextDeltas.getOrPut(index) { StringBuilder() }
                        .append(item["delta"]?.jsonPrimitive?.content.orEmpty())
                }
            }
            if (type == "response.output_item.done") {
                val output = item["item"] as? JsonObject
                if (output?.get("type")?.jsonPrimitive?.content == "message") {
                    val index = requireNotNull(item["output_index"]?.jsonPrimitive?.intOrNull)
                    val text =
                        (output["content"] as? JsonArray).orEmpty().joinToString("") { part ->
                            val content = part.jsonObject
                            when (content["type"]?.jsonPrimitive?.content) {
                                "output_text" -> content["text"]?.jsonPrimitive?.content.orEmpty()
                                "refusal" -> content["refusal"]?.jsonPrimitive?.content.orEmpty()
                                else -> error("Unsupported Responses output content: $content")
                            }
                        }
                    if (responseTextDeltas[index].isNullOrEmpty() && text.isNotEmpty()) ready += StreamFrame.TextDelta(
                        text,
                        index
                    )
                    ready += StreamFrame.TextComplete(text, index)
                }
                if (output?.get("type")?.jsonPrimitive?.content == "reasoning" &&
                    output["encrypted_content"]?.jsonPrimitive?.contentOrNull != null &&
                    (output["summary"] as? JsonArray).isNullOrEmpty() &&
                    (output["content"] as? JsonArray).isNullOrEmpty()
                ) {
                    item["output_index"]?.jsonPrimitive?.intOrNull?.let { encryptedOnly += it }
                    // Koog wrongly discards encrypted-only reasoning; a placeholder lets its native mapper through.
                    return JsonObject(item + ("item" to JsonObject(output + ("content" to buildJsonArray {
                        add(buildJsonObject { put("type", "reasoning_text"); put("text", "") })
                    })))).toString()
                }
            }
            if (type in listOf("response.completed", "response.incomplete")) {
                finishReason = if (type == "response.incomplete")
                    response?.get("incomplete_details")?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull
                        ?: "incomplete"
                else "completed"
                stopped = true
                if (type == "response.incomplete") return JsonObject(
                    item + ("type" to JsonPrimitive(
                        "response.completed"
                    ))
                ).toString()
            }
            if (type == "response.failed" || type == "error") error("Responses stream failed: $raw")
        } else {
            rememberUsage(item["usage"])
            // Common compatible endpoints send null choices in their final usage-only frame.
            val choices = item["choices"] as? JsonArray
            choices?.forEach { element ->
                val choice = element.jsonObject
                choice["finish_reason"]?.jsonPrimitive?.contentOrNull?.let { finishReason = it }
                if ((choice["delta"] as? JsonObject)?.get("tool_calls") is JsonArray) chatTools =
                    true
            }
            val updated = choices?.map { element ->
                val choice = element.jsonObject
                val delta = choice["delta"] as? JsonObject
                if (delta != null && delta["reasoning_content"] == null) {
                    val reasoning = delta["reasoning"] ?: delta["thinking"]
                    if (reasoning != null) JsonObject(choice + ("delta" to JsonObject(delta + ("reasoning_content" to reasoning)))) else choice
                } else choice
            }
            if (item["choices"] is JsonNull) return JsonObject(
                item + ("choices" to JsonArray(
                    emptyList()
                ))
            ).toString()
            if (updated != null) return JsonObject(item + ("choices" to JsonArray(updated))).toString()
        }
        return normalized
    }
}

internal class ProtocolCompatibilityTransport(
    private val delegate: KoogHttpClient,
    private val protocol: Protocol,
    private val calls: TrackedCalls,
    private val nativeOpenAI: Boolean,
    private val providerConfig: hk.uwu.reareye.agent.ProviderConfig? = null,
) : KoogHttpClient by delegate {
    private fun request(value: Any): Any {
        val compatible = compatibilityRequest(value)
        val config = providerConfig ?: return compatible
        if (compatible !is String) return compatible
        return hk.uwu.reareye.agent.ReasoningPolicy.apply(
            config,
            Json.parseToJsonElement(compatible).jsonObject
        ).toString()
    }

    private fun compatibilityRequest(value: Any): Any {
        if (protocol == Protocol.RESPONSES && value is String) {
            val fields = Json.parseToJsonElement(value).jsonObject
            val tools = fields["tools"] as? JsonArray ?: return value
            // Preserve optional ToolSpec parameters: Responses otherwise enables server-side strict
            // normalization when Koog's native Function DTO omits its nullable strict property.
            return JsonObject(fields + ("tools" to JsonArray(tools.map { element ->
                val tool = element.jsonObject
                if (tool["type"]?.jsonPrimitive?.content == "function" && !tool.containsKey("strict"))
                    JsonObject(tool + ("strict" to JsonPrimitive(false))) else tool
            }))).toString()
        }
        if (protocol == Protocol.CHAT_COMPLETIONS && !nativeOpenAI && value is String) {
            val fields = Json.parseToJsonElement(value).jsonObject.toMutableMap()
            fields.remove("max_completion_tokens")?.let { fields["max_tokens"] = it }
            fields.remove("stream_options")
            return JsonObject(fields).toString()
        }
        if (protocol != Protocol.ANTHROPIC || value !is String || !value.contains(REDACTED_PREFIX)) return value
        fun patch(element: JsonElement): JsonElement = when (element) {
            is JsonArray -> JsonArray(element.map(::patch))
            is JsonObject -> if (element["type"]?.jsonPrimitive?.contentOrNull == "thinking" &&
                element["signature"]?.jsonPrimitive?.contentOrNull?.startsWith(REDACTED_PREFIX) == true
            )
                buildJsonObject {
                    put("type", "redacted_thinking"); put(
                    "data",
                    element.getValue("signature").jsonPrimitive.content.removePrefix(REDACTED_PREFIX)
                )
                }
            else JsonObject(element.mapValues { patch(it.value) })

            else -> element
        }
        return patch(Json.parseToJsonElement(value)).toString()
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): Flow<O> = flow {
        val state = currentCoroutineContext()[ProtocolState]

        @Suppress("UNCHECKED_CAST")
        val body = request(requestBody) as T
        calls.request {
            // Decode outside Ktor's SSE session: otherwise its error mapper can mask a DTO failure
            // with a secondary Content-Length mismatch after the SSE body was already consumed.
            delegate.sse(path, body, requestBodyType, { data ->
                if (data?.trim() == "[DONE]") state?.stopped = true
                dataFilter(data)
            }, { it }, { it }, parameters, headers)
                .buffer(kotlinx.coroutines.channels.Channel.BUFFERED)
                .collect { data ->
                    val normalized =
                        if (state != null) state.observe(data, protocol) ?: return@collect else data
                    state?.emitReady?.let { emitFrame ->
                        while (state.ready.isNotEmpty()) emitFrame(
                            state.ready.removeFirst()
                        )
                    }
                    val decoded = decodeStreamingResponse(normalized)
                    processStreamingChunk(decoded)?.let { emit(it) }
                }
        }
    }

    override suspend fun <R : Any> get(
        path: String, responseType: KClass<R>,
        parameters: Map<String, String>, headers: Map<String, String>
    ): R = calls.request {
        delegate.get(path, responseType, parameters, headers)
    }

    override fun <T : Any> lines(
        path: String, requestBody: T, requestBodyType: KClass<T>,
        parameters: Map<String, String>, headers: Map<String, String>
    ): Flow<String> = flow {
        calls.request {
            delegate.lines(path, requestBody, requestBodyType, parameters, headers)
                .collect { emit(it) }
        }
    }

    override suspend fun <T : Any, R : Any> post(
        path: String, requestBody: T, requestBodyType: KClass<T>,
        responseType: KClass<R>, parameters: Map<String, String>, headers: Map<String, String>
    ): R = calls.request {
        @Suppress("UNCHECKED_CAST")
        delegate.post(
            path,
            request(requestBody) as T,
            requestBodyType,
            responseType,
            parameters,
            headers
        )
    }
}

internal class PatchedAnthropicClient(
    apiKey: String,
    settings: AnthropicClientSettings,
    factory: KoogHttpClient.Factory
) :
    AnthropicLLMClient(apiKey, settings, factory) {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant =
        executeStreaming(prompt, model, tools).toList().toMessageResponse()

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        val state = ProtocolState()
        return channelFlow {
            val completions =
                java.util.concurrent.ConcurrentLinkedQueue<StreamFrame.CompleteFrame>()
            state.emitReady =
                { if (it is StreamFrame.CompleteFrame) completions.add(it) else send(it) }
            var end: StreamFrame.End? = null
            // Mark only the stable system prefix and combine adjacent native user/result blocks.
            val messages = mutableListOf<Message>()
            prompt.messages.forEach { message ->
                val prepared =
                    if (message is Message.System) message.copy(parts = message.parts.mapIndexed { index, part ->
                        if (index == message.parts.lastIndex) part.copy(cacheControl = ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl.Default) else part
                    }) else message
                val previous = messages.lastOrNull()
                if (previous is Message.User && prepared is Message.User) {
                    messages[messages.lastIndex] =
                        previous.copy(parts = previous.parts + prepared.parts)
                } else messages += prepared
            }
            super.executeStreaming(prompt.copy(messages = messages), model, tools)
                .collect { frame ->
                    when (frame) {
                        is StreamFrame.ReasoningComplete -> Unit // Replace unsigned upstream aggregates with verified blocks.
                        is StreamFrame.CompleteFrame -> completions.add(frame)
                        is StreamFrame.End -> end = frame
                        else -> send(frame)
                    }
                }
            require(state.stopped && state.thinking.isEmpty()) { "Anthropic stream ended before message_stop" }
            val complete = requireNotNull(end) { "Anthropic stream has no final metadata" }
            completions.toList().sortedBy { it.index ?: Int.MAX_VALUE }.forEach { send(it) }
            send(
                complete.copy(
                    finishReason = state.finishReason,
                    metaInfo = state.meta(complete.metaInfo)
                )
            )
        }.flowOn(state)
    }
}

internal class PatchedOpenAIClient(
    apiKey: String,
    settings: OpenAIClientSettings,
    factory: KoogHttpClient.Factory
) :
    OpenAILLMClient(apiKey, settings, factory) {
    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Message.Assistant =
        executeStreaming(prompt, model, tools).toList().toMessageResponse()

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): Flow<StreamFrame> {
        val state = ProtocolState()
        return channelFlow {
            val completions =
                java.util.concurrent.ConcurrentLinkedQueue<StreamFrame.CompleteFrame>()
            state.emitReady =
                { if (it is StreamFrame.CompleteFrame) completions.add(it) else send(it) }
            var end: StreamFrame.End? = null
            super.executeStreaming(prompt, model, tools).collect { frame ->
                when (frame) {
                    is StreamFrame.ReasoningComplete -> completions.add(
                        if (frame.index in state.encryptedOnly) frame.copy(
                            content = emptyList()
                        ) else frame
                    )

                    is StreamFrame.CompleteFrame -> completions.add(frame)
                    is StreamFrame.ToolCallDelta -> send(state.responseCalls[frame.index]?.let {
                        frame.copy(
                            id = it.first
                        )
                    } ?: frame)

                    is StreamFrame.End -> {
                        val responses =
                            model.supports(ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Responses)
                        require(if (responses) state.stopped else state.stopped || state.finishReason != null) {
                            "OpenAI stream ended without a protocol completion marker"
                        }
                        val reason = state.finishReason ?: frame.finishReason
                        ?: if (!state.chatTools && state.stopped) "stop" else null
                        requireNotNull(reason) { "Tool stream ended without an explicit finish reason" }
                        state.ended = true
                        end =
                            frame.copy(finishReason = reason, metaInfo = state.meta(frame.metaInfo))
                    }

                    else -> send(frame)
                }
            }
            require(state.ended) { "OpenAI stream ended before final metadata" }
            completions.toList().sortedBy { it.index ?: Int.MAX_VALUE }.forEach { send(it) }
            send(requireNotNull(end))
        }.flowOn(state)
    }
}
