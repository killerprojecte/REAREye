package hk.uwu.reareye.agent.catalog

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream

object MaryReasoningCatalog {
    private val repository = ReasoningCatalogRepository(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        sources = listOf(ModelsDevReasoningCatalog),
        http = OkHttpReasoningCatalogHttp(),
    )
    val index: StateFlow<ReasoningCatalogIndex> = repository.index
    internal val models = repository.models

    fun start(context: Context) {
        val application = context.applicationContext
        repository.start(
            builtIn = {
                application.assets.open("mary-model-presets.json").use {
                    ModelsDevModelCatalog.parse(it.readCatalogBytes(MAX_CATALOG_SNAPSHOT_BYTES).inputStream())
                }
            },
        ) { FileReasoningCatalogCache(File(application.noBackupFilesDir, "mary-reasoning-catalog")) }
    }
}

internal class ReasoningCatalogRepository(
    private val scope: CoroutineScope,
    sources: List<ReasoningCatalogSource>,
    private val http: ReasoningCatalogHttp,
) {
    private val sources = sources.toList()
    private var job: Job? = null
    private val mutableIndex = MutableStateFlow(ReasoningCatalogIndex.Empty)
    val index = mutableIndex.asStateFlow()
    private val mutableModels = MutableStateFlow(MaryModelCatalogSnapshot())
    val models = mutableModels.asStateFlow()

    init {
        require(this.sources.map { it.id }.distinct().size == this.sources.size)
        require(this.sources.all { source ->
            val uri = source.feedUri
            isCatalogSourceId(source.id) && source.displayName.isNotBlank() &&
                uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null
        })
    }

    @Synchronized
    fun start(
        builtIn: () -> ModelsDevModelIndex = { ModelsDevModelIndex.Empty },
        cache: () -> ReasoningCatalogCache,
    ): Job {
        job?.let { return it }
        return scope.launch {
            val presets = sourceResult(builtIn) ?: ModelsDevModelIndex.Empty
            mutableModels.value = MaryModelCatalogSnapshot(builtIn = presets)
            synchronize(cache(), presets)
        }.also { job = it }
    }

    private suspend fun synchronize(cache: ReasoningCatalogCache, builtIn: ModelsDevModelIndex) {
        val committed = LinkedHashMap<String, Snapshot>()
        for (source in sources) {
            val previous = sourceResult { read(cache, source) } ?: continue
            committed[source.id] = previous
        }
        fun publish() {
            mutableIndex.value = ReasoningCatalogIndex.from(sources.mapNotNull { committed[it.id]?.reasoning })
            mutableModels.value = MaryModelCatalogSnapshot(committed[ModelsDevReasoningCatalog.id]?.models, builtIn)
        }
        publish()

        // Read previous values before launching, since other sources can commit concurrently.
        val previousBySource = committed.toMap()
        coroutineScope {
            for (source in sources) launch {
                val snapshot = sourceResult { refresh(cache, source, previousBySource[source.id]) } ?: return@launch
                synchronized(committed) {
                    committed[source.id] = snapshot
                    publish()
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun read(cache: ReasoningCatalogCache, source: ReasoningCatalogSource): Snapshot? {
        val bytes = cache.read(source.id) ?: return null
        require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES)
        val previous = Json.decodeFromStream<CachedCatalog>(bytes.inputStream())
        require(previous.version in 1..CACHE_VERSION && previous.sourceId == source.id)
        val reasoning = ReasoningCatalogSnapshot.from(source, previous.records)
        val publicModels = if (source === ModelsDevReasoningCatalog) {
            previous.models?.let { groups -> sourceResult { ModelsDevModelIndex.from(groups) } }
        } else null
        // A v1 (reasoning-only) cache must not cause a 304 before public metadata has been fetched.
        val validator = entityTag(previous.etag).takeIf { source !== ModelsDevReasoningCatalog || publicModels != null }
        return Snapshot(reasoning, publicModels, validator)
    }

    private fun refresh(cache: ReasoningCatalogCache, source: ReasoningCatalogSource, previous: Snapshot?): Snapshot? {
        val result = http.fetch(source.feedUri, previous?.etag) as? CatalogHttpResult.Updated ?: return null
        val reasoning = sourceResult { ReasoningCatalogSnapshot.from(source, source.parse(result.body.inputStream())) }
        val hasPublicModels = source === ModelsDevReasoningCatalog
        val publicModels = if (hasPublicModels) sourceResult { ModelsDevModelCatalog.parse(result.body.inputStream()) } else null
        require(reasoning != null || publicModels != null)
        val nextReasoning = reasoning ?: previous?.reasoning
        val nextModels = publicModels ?: previous?.models
        val etag = entityTag(result.etag)
        // If the feed outgrows the existing compact-cache bound, keep the last usable model
        // data (or the presets) without preventing a valid reasoning update from being persisted.
        for (candidate in listOf(nextModels, previous?.models, null).distinct()) {
            val saved = CachedCatalog(
                CACHE_VERSION, source.id, etag.takeIf { candidate === nextModels },
                nextReasoning?.records.orEmpty(),
                if (hasPublicModels) candidate?.records.orEmpty() else null,
            )
            val bytes = Json.encodeToString(saved).encodeToByteArray()
            if (bytes.size > MAX_CATALOG_SNAPSHOT_BYTES) continue
            cache.write(source.id, bytes)
            return Snapshot(nextReasoning, candidate, saved.etag)
        }
        return null
    }

    private data class Snapshot(
        val reasoning: ReasoningCatalogSnapshot?,
        val models: ModelsDevModelIndex?,
        val etag: String?,
    )

    @Serializable
    private data class CachedCatalog(
        val version: Int,
        val sourceId: String,
        val etag: String?,
        val records: List<ReasoningCatalogRecord>,
        val models: List<ModelsDevModelGroup>? = null,
    )

    companion object {
        private const val CACHE_VERSION = 2
    }
}

private inline fun <T> sourceResult(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
