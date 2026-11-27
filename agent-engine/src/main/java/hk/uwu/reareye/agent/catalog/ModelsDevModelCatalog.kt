package hk.uwu.reareye.agent.catalog

import hk.uwu.reareye.agent.Protocol
import java.io.InputStream
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream

/** Provider-specific chat metadata. It never supplies adjustable reasoning controls. */
object ModelsDevModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalSerializationApi::class)
    fun parse(body: InputStream): ModelsDevModelIndex {
        val feed = json.decodeFromStream<Map<String, ProviderPayload>>(body)
        val records = feed.flatMap { (providerId, provider) ->
            if (!providerIdPattern.matches(providerId) || provider.id.stringValue() != providerId) return@flatMap emptyList()
            val byRoute = LinkedHashMap<List<ModelsDevModelRoute>, MutableList<ModelsDevModel>>()
            for ((modelId, payload) in provider.models.orEmpty()) {
                val model = payload.model(modelId) ?: continue
                val routes = modelRoutes(providerId, provider, payload.provider)
                byRoute.getOrPut(routes) { mutableListOf() }.add(model)
            }
            byRoute.map { (routes, models) -> ModelsDevModelGroup(providerId, models, routes) }
        }
        require(records.isNotEmpty()) { "Models.dev contains no usable public chat models" }
        return ModelsDevModelIndex.from(records)
    }

    private fun modelRoutes(providerId: String, provider: ProviderPayload, modelProvider: JsonObject?): List<ModelsDevModelRoute> {
        val modelApi = modelProvider?.get("api").stringValue()
        val modelNpm = modelProvider?.get("npm").stringValue()
        val shape = modelProvider?.get("shape").stringValue()
        pinnedRoutes[providerId]?.let { routes ->
            // The local endpoint policy wins over public claims for direct providers. An explicitly
            // different per-model endpoint/shape must not be displayed on that direct route.
            val endpoint = modelApi?.let(::catalogEndpoint)
            val protocol = modelProtocol(modelNpm, shape)
            if ((modelNpm != null || shape != null) && protocol == null) return emptyList()
            return routes.filter { (url, wire) ->
                (modelApi == null || endpoint == url) && (protocol == null || protocol == wire)
            }
        }
        val endpoint = (modelApi ?: provider.api.stringValue())?.let(::catalogEndpoint) ?: return emptyList()
        val protocol = modelProtocol(modelNpm ?: provider.npm.stringValue(), shape) ?: return emptyList()
        return listOf(ModelsDevModelRoute(endpoint, protocol))
    }

    private fun modelProtocol(npm: String?, shape: String?): Protocol? = when (npm) {
        "@ai-sdk/anthropic" -> Protocol.ANTHROPIC
        "@ai-sdk/openai-compatible", "@openrouter/ai-sdk-provider", "@ai-sdk/groq", "@ai-sdk/mistral",
        "@ai-sdk/cerebras", "@ai-sdk/deepinfra", "@ai-sdk/togetherai", "@ai-sdk/perplexity" ->
            if (shape == "responses") Protocol.RESPONSES else Protocol.CHAT_COMPLETIONS
        "@ai-sdk/openai", "@ai-sdk/xai" ->
            if (shape == "completions") Protocol.CHAT_COMPLETIONS else Protocol.RESPONSES
        null -> when (shape) {
            "completions" -> Protocol.CHAT_COMPLETIONS
            "responses" -> Protocol.RESPONSES
            else -> null
        }
        else -> null
    }

    @Serializable
    private data class ProviderPayload(
        val id: JsonElement? = null,
        val npm: JsonElement? = null,
        val api: JsonElement? = null,
        val models: Map<String, ModelPayload>? = null,
    )

    @Serializable
    private data class ModelPayload(
        val id: JsonElement? = null,
        val name: JsonElement? = null,
        val type: JsonElement? = null,
        val modalities: JsonElement? = null,
        val limit: JsonElement? = null,
        @SerialName("tool_call") val toolCall: JsonElement? = null,
        val provider: JsonObject? = null,
    ) {
        fun model(modelId: String): ModelsDevModel? {
            if (!isCatalogModelId(modelId) || id.stringValue() != modelId || (type != null && type.stringValue() != "chat")) return null
            val name = name.stringValue()?.trim()?.takeIf(::validModelName) ?: return null
            val input = ((modalities as? JsonObject)?.get("input") as? JsonArray).orEmpty().mapNotNull { it.stringValue() }
            val limits = limit as? JsonObject
            val flags = ModelsDevModelCapability.entries.fold(0) { mask, capability ->
                val supported = when (capability) {
                    ModelsDevModelCapability.TEXT -> "text" in input
                    ModelsDevModelCapability.IMAGE -> "image" in input
                    ModelsDevModelCapability.AUDIO -> "audio" in input
                    ModelsDevModelCapability.TOOLS -> (toolCall as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
                }
                if (supported) mask or capability.flag else mask
            }
            return ModelsDevModel(modelId, name, limits?.get("context").positiveInt(), limits?.get("output").positiveInt(), flags)
        }
    }
}

@Serializable
data class ModelsDevModel(
    @SerialName("i") val id: String,
    @SerialName("n") val displayName: String,
    @SerialName("c") val context: Int? = null,
    @SerialName("o") val maxOutput: Int? = null,
    @SerialName("f") private val flags: Int = 0,
) {
    fun supports(capability: ModelsDevModelCapability): Boolean = (flags and capability.flag) != 0
}

enum class ModelsDevModelCapability(internal val flag: Int) { TEXT(1), IMAGE(2), AUDIO(4), TOOLS(8) }

/** Compact provider groups retained in the existing size-bounded snapshot. */
@Serializable
data class ModelsDevModelGroup(
    @SerialName("p") val providerId: String,
    @SerialName("m") val models: List<ModelsDevModel>,
    @SerialName("r") val routes: List<ModelsDevModelRoute> = emptyList(),
)

@Serializable
data class ModelsDevModelRoute(val baseUrl: String, val protocol: Protocol)

class ModelsDevModelIndex private constructor(
    val records: List<ModelsDevModelGroup>,
    private val byRoute: Map<ModelsDevModelRoute, List<ModelsDevModel>>,
    private val byProvider: Map<String, List<ModelsDevModel>>,
) {
    fun models(baseUrl: String, protocol: Protocol): List<ModelsDevModel> {
        val endpoint = catalogEndpoint(baseUrl.trim()) ?: return emptyList()
        return byRoute[ModelsDevModelRoute(endpoint, protocol)].orEmpty()
    }

    /** Takes an explicit Models.dev provider key, never a connection display name or model alias. */
    fun models(providerId: String): List<ModelsDevModel> = byProvider[providerId].orEmpty()

    companion object {
        val Empty = ModelsDevModelIndex(emptyList(), emptyMap(), emptyMap())

        fun from(records: List<ModelsDevModelGroup>): ModelsDevModelIndex {
            if (records.isEmpty()) return Empty
            val providers = LinkedHashMap<String, MutableList<ModelsDevModel>>()
            val claims = LinkedHashMap<ModelsDevModelRoute, MutableMap<String, MutableList<ModelsDevModel>>>()
            for (group in records) {
                require(providerIdPattern.matches(group.providerId))
                require(group.models.isNotEmpty() && group.models.all {
                    isCatalogModelId(it.id) && validModelName(it.displayName) &&
                        (it.context == null || it.context > 0) && (it.maxOutput == null || it.maxOutput > 0)
                })
                providers.getOrPut(group.providerId) { mutableListOf() }.addAll(group.models)
                for (route in group.routes) {
                    require(catalogEndpoint(route.baseUrl) == route.baseUrl)
                    val owner = pinnedOwners[route.baseUrl]
                    if (owner != null && (owner != group.providerId || route !in pinnedRoutes.getValue(owner))) continue
                    if (group.providerId in pinnedRoutes && owner != group.providerId) continue
                    claims.getOrPut(route) { LinkedHashMap() }
                        .getOrPut(group.providerId) { mutableListOf() }.addAll(group.models)
                }
            }
            require(providers.values.all { models -> models.distinctBy { it.id }.size == models.size })
            return ModelsDevModelIndex(
                records,
                claims.mapNotNull { (route, owners) -> owners.values.singleOrNull()?.let { route to it.sortedBy { model -> model.id } } }.toMap(),
                providers.mapValues { (_, models) -> models.sortedBy { it.id } },
            )
        }
    }
}

private val providerIdPattern = Regex("[a-z0-9][a-z0-9_-]{0,63}")

private val pinnedRoutes = mapOf(
    "openai" to listOf(
        ModelsDevModelRoute("https://api.openai.com/v1", Protocol.RESPONSES),
        ModelsDevModelRoute("https://api.openai.com/v1", Protocol.CHAT_COMPLETIONS),
    ),
    "anthropic" to listOf(ModelsDevModelRoute("https://api.anthropic.com/v1", Protocol.ANTHROPIC)),
    "deepseek" to listOf(
        ModelsDevModelRoute("https://api.deepseek.com", Protocol.CHAT_COMPLETIONS),
        ModelsDevModelRoute("https://api.deepseek.com/v1", Protocol.CHAT_COMPLETIONS),
    ),
    "openrouter" to listOf(ModelsDevModelRoute("https://openrouter.ai/api/v1", Protocol.CHAT_COMPLETIONS)),
    "groq" to listOf(ModelsDevModelRoute("https://api.groq.com/openai/v1", Protocol.CHAT_COMPLETIONS)),
    "mistral" to listOf(ModelsDevModelRoute("https://api.mistral.ai/v1", Protocol.CHAT_COMPLETIONS)),
    "xai" to listOf(
        ModelsDevModelRoute("https://api.x.ai/v1", Protocol.RESPONSES),
        ModelsDevModelRoute("https://api.x.ai/v1", Protocol.CHAT_COMPLETIONS),
    ),
    "google" to listOf(ModelsDevModelRoute("https://generativelanguage.googleapis.com/v1beta/openai", Protocol.CHAT_COMPLETIONS)),
)

private val pinnedOwners = pinnedRoutes.flatMap { (provider, routes) -> routes.map { it.baseUrl to provider } }.toMap()

private fun validModelName(value: String): Boolean = value.length in 1..256 && value.isNotBlank() && value.none { it.isISOControl() }
private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
private fun JsonElement?.positiveInt(): Int? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.content
    ?.toIntOrNull()?.takeIf { it > 0 }
