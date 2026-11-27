package hk.uwu.reareye.agent.catalog

import hk.uwu.reareye.agent.CapabilityScope
import hk.uwu.reareye.agent.CapabilitySource
import hk.uwu.reareye.agent.Protocol
import hk.uwu.reareye.agent.ProviderConfig
import hk.uwu.reareye.agent.ReasoningCapability
import hk.uwu.reareye.agent.ReasoningEffort
import hk.uwu.reareye.agent.ReasoningMapping
import hk.uwu.reareye.agent.ReasoningStatus
import java.io.InputStream
import java.net.URI
import java.util.Collections
import kotlinx.serialization.Serializable

/** A public feed adapter; [routes] is local policy and must never be populated from feed URLs. */
interface ReasoningCatalogSource {
    val id: String
    val displayName: String
    val feedUri: URI

    fun parse(body: InputStream): List<ReasoningCatalogRecord>
    fun routes(record: ReasoningCatalogRecord): List<ReasoningCatalogRoute>
}

/** Compact evidence retained in the cache and reinterpreted by the adapter on each load. */
@Serializable
data class ReasoningCatalogRecord(
    val provider: String,
    val modelId: String,
    val efforts: List<ReasoningEffort>,
    val toggle: Boolean = false,
)

data class ReasoningCatalogRoute(
    val baseUrl: String,
    val protocol: Protocol,
    val mapping: ReasoningMapping,
    val levels: List<ReasoningEffort>,
)

internal fun catalogEndpoint(raw: String): String? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    val host = uri.host?.lowercase() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) ||
        uri.rawQuery != null || uri.rawFragment != null ||
        !(uri.rawAuthority.equals(host, ignoreCase = true) ||
            uri.rawAuthority.equals("$host:443", ignoreCase = true))
    ) return null
    return "https://$host${uri.rawPath.removeSuffix("/")}"
}

internal fun isCatalogModelId(value: String): Boolean =
    value.length in 1..512 && value.none { it.isWhitespace() || it.isISOControl() }

internal data class CatalogKey(val baseUrl: String, val protocol: Protocol, val modelId: String)

internal data class CatalogCapability(
    val mapping: ReasoningMapping,
    val levels: List<ReasoningEffort>,
    val sourceName: String,
)

/** A validated source snapshot, including only known adjustable controls. */
class ReasoningCatalogSnapshot private constructor(
    val records: List<ReasoningCatalogRecord>,
    internal val entries: Map<CatalogKey, CatalogCapability>,
) {
    companion object {
        fun from(source: ReasoningCatalogSource, records: List<ReasoningCatalogRecord>): ReasoningCatalogSnapshot {
            val normalized = ArrayList<ReasoningCatalogRecord>(records.size)
            val index = LinkedHashMap<CatalogKey, CatalogCapability>()
            for (record in records.sortedWith(compareBy({ it.provider }, { it.modelId }))) {
                require(isCatalogModelId(record.modelId))
                val efforts = immutableList(record.efforts.sortedBy { it.ordinal })
                require(efforts.isNotEmpty() && efforts.distinct().size == efforts.size)
                val normalizedRecord = record.copy(efforts = efforts)
                val routes = source.routes(normalizedRecord)
                require(routes.isNotEmpty())
                for (route in routes) {
                    val baseUrl = requireNotNull(catalogEndpoint(route.baseUrl))
                    val levels = immutableList(route.levels.sortedBy { it.ordinal })
                    val config = ProviderConfig(baseUrl = baseUrl, protocol = route.protocol, model = record.modelId)
                    require(supportedCapability(config, route.mapping, levels, source.displayName).validFor(config))
                    val key = CatalogKey(baseUrl, route.protocol, record.modelId)
                    require(index.put(key, CatalogCapability(route.mapping, levels, source.displayName)) == null)
                }
                normalized.add(normalizedRecord)
            }
            return ReasoningCatalogSnapshot(immutableList(normalized), Collections.unmodifiableMap(index))
        }
    }
}

/** Exact local lookup. No user connection information is part of the public feed or its cache. */
class ReasoningCatalogIndex private constructor(private val entries: Map<CatalogKey, CatalogCapability>) {
    fun capabilityFor(config: ProviderConfig): ReasoningCapability? {
        if (entries.isEmpty()) return null
        val baseUrl = catalogEndpoint(config.baseUrl.trim()) ?: return null
        val entry = entries[CatalogKey(baseUrl, config.protocol, config.model.trim())] ?: return null
        return supportedCapability(config, entry.mapping, entry.levels, entry.sourceName)
    }

    companion object {
        val Empty = ReasoningCatalogIndex(emptyMap())

        /** Earlier configured sources take precedence for the same exact route and model. */
        fun from(snapshots: Iterable<ReasoningCatalogSnapshot>): ReasoningCatalogIndex {
            val entries = LinkedHashMap<CatalogKey, CatalogCapability>()
            for (snapshot in snapshots) {
                for ((key, value) in snapshot.entries) entries.putIfAbsent(key, value)
            }
            return if (entries.isEmpty()) Empty else ReasoningCatalogIndex(entries)
        }
    }
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))

private fun supportedCapability(
    config: ProviderConfig,
    mapping: ReasoningMapping,
    levels: List<ReasoningEffort>,
    sourceName: String,
) = ReasoningCapability(
    status = ReasoningStatus.SUPPORTED,
    levels = levels,
    mapping = mapping,
    scope = CapabilityScope.from(config),
    source = CapabilitySource.CATALOG_METADATA,
    supportsReasoning = true,
    catalogSourceName = sourceName,
)
