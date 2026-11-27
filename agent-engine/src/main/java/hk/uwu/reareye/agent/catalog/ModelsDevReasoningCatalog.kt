package hk.uwu.reareye.agent.catalog

import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningMapping
import java.io.InputStream
import java.net.URI
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromStream

object ModelsDevReasoningCatalog : ReasoningCatalogSource {
    override val id = "models-dev-v1"
    override val displayName = "Models.dev"
    override val feedUri = URI("https://models.dev/api.json")

    private val json = Json { ignoreUnknownKeys = true }
    private val effortByWire = ReasoningEffort.entries.associateBy { it.name.lowercase() }
    private val openAi = Provider("openai", ReasoningMapping.OPENAI_EFFORT, Protocol.RESPONSES,
        listOf("https://api.openai.com/v1"))
    private val deepSeek = Provider("deepseek", ReasoningMapping.DEEPSEEK_THINKING, Protocol.CHAT_COMPLETIONS,
        listOf("https://api.deepseek.com", "https://api.deepseek.com/v1"))
    private val openRouter = Provider("openrouter", ReasoningMapping.OPENROUTER_REASONING, Protocol.CHAT_COMPLETIONS,
        listOf("https://openrouter.ai/api/v1"))
    private val providers = listOf(openAi, deepSeek, openRouter).associateBy { it.id }

    @OptIn(ExperimentalSerializationApi::class)
    override fun parse(body: InputStream): List<ReasoningCatalogRecord> {
        // Streaming deserialization skips all other providers without retaining their models.
        val feed = json.decodeFromStream<Feed>(body)
        val selected = listOf(openAi to feed.openai, deepSeek to feed.deepseek, openRouter to feed.openrouter)
            .mapNotNull { (provider, data) -> data?.let { provider to it } }
        require(selected.isNotEmpty()) { "Missing Models.dev provider entries" }
        val records = selected.flatMap { (provider, data) ->
            val models = data["models"] as? JsonObject ?: error("Missing Models.dev models object")
            models.mapNotNull { (modelId, raw) -> parseModel(provider, modelId, raw as? JsonObject) }
        }
        require(records.isNotEmpty()) { "Models.dev contains no usable reasoning options" }
        return records
    }

    private fun parseModel(provider: Provider, modelId: String, model: JsonObject?): ReasoningCatalogRecord? {
        if (!isCatalogModelId(modelId) || model?.get("id").stringValue() != modelId) return null
        val options = model?.get("reasoning_options") as? JsonArray ?: return null
        val settings = options.filterIsInstance<JsonObject>()
        val effort = settings.singleOrNull { it["type"].stringValue() == "effort" } ?: return null
        val values = effort["values"] as? JsonArray ?: return null
        val levels = values.map { effortByWire[it.stringValue()] ?: return null }
        val toggle = provider == deepSeek && settings.any { it["type"].stringValue() == "toggle" }
        return ReasoningCatalogRecord(provider.id, modelId, levels, toggle).takeIf { provider.accepts(it) }
    }

    override fun routes(record: ReasoningCatalogRecord): List<ReasoningCatalogRoute> {
        val provider = providers[record.provider]?.takeIf { it.accepts(record) } ?: return emptyList()
        val levels = if (record.toggle) (record.efforts + ReasoningEffort.NONE).distinct() else record.efforts
        return provider.baseUrls.map { ReasoningCatalogRoute(it, provider.protocol, provider.mapping, levels) }
    }

    @Serializable
    private data class Feed(
        val openai: JsonObject? = null,
        val deepseek: JsonObject? = null,
        val openrouter: JsonObject? = null,
    )

    private data class Provider(
        val id: String,
        val mapping: ReasoningMapping,
        val protocol: Protocol,
        val baseUrls: List<String>,
    ) {
        fun accepts(record: ReasoningCatalogRecord): Boolean =
            record.efforts.isNotEmpty() && record.efforts.distinct().size == record.efforts.size &&
                record.efforts.all { it in mapping.allowedLevels } &&
                (!record.toggle || (mapping == ReasoningMapping.DEEPSEEK_THINKING &&
                    record.efforts.any { it != ReasoningEffort.NONE }))
    }

    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
