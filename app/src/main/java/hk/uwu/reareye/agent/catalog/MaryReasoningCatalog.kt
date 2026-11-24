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

    fun start(context: Context) {
        val application = context.applicationContext
        repository.start { FileReasoningCatalogCache(File(application.noBackupFilesDir, "mary-reasoning-catalog")) }
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

    init {
        require(this.sources.map { it.id }.distinct().size == this.sources.size)
        require(this.sources.all { source ->
            val uri = source.feedUri
            isCatalogSourceId(source.id) && source.displayName.isNotBlank() &&
                uri.scheme == "https" && uri.host != null && uri.rawUserInfo == null && uri.rawFragment == null
        })
    }

    @Synchronized
    fun start(cache: () -> ReasoningCatalogCache): Job {
        job?.let { return it }
        return scope.launch { synchronize(cache()) }.also { job = it }
    }

    private suspend fun synchronize(cache: ReasoningCatalogCache) {
        val committed = LinkedHashMap<String, ReasoningCatalogSnapshot>()
        val etags = HashMap<String, String>()
        for (source in sources) {
            val previous = sourceResult { read(cache, source) } ?: continue
            committed[source.id] = previous.first
            previous.second?.let { etags[source.id] = it }
        }
        fun publish() {
            mutableIndex.value = ReasoningCatalogIndex.from(sources.mapNotNull { committed[it.id] })
        }
        publish()

        coroutineScope {
            for (source in sources) launch {
                val snapshot = sourceResult { refresh(cache, source, etags[source.id]) } ?: return@launch
                synchronized(committed) {
                    committed[source.id] = snapshot
                    publish()
                }
            }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun read(cache: ReasoningCatalogCache, source: ReasoningCatalogSource): Pair<ReasoningCatalogSnapshot, String?>? {
        val bytes = cache.read(source.id) ?: return null
        require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES)
        val previous = Json.decodeFromStream<CachedCatalog>(bytes.inputStream())
        require(previous.version == CACHE_VERSION && previous.sourceId == source.id)
        return ReasoningCatalogSnapshot.from(source, previous.records) to entityTag(previous.etag)
    }

    private fun refresh(cache: ReasoningCatalogCache, source: ReasoningCatalogSource, etag: String?): ReasoningCatalogSnapshot? =
        when (val result = http.fetch(source.feedUri, etag)) {
            CatalogHttpResult.Unchanged -> null
            is CatalogHttpResult.Updated -> {
                val snapshot = ReasoningCatalogSnapshot.from(source, source.parse(result.body.inputStream()))
                val saved = CachedCatalog(CACHE_VERSION, source.id, entityTag(result.etag), snapshot.records)
                val bytes = Json.encodeToString(saved).encodeToByteArray()
                require(bytes.size <= MAX_CATALOG_SNAPSHOT_BYTES)
                cache.write(source.id, bytes)
                snapshot
            }
        }

    @Serializable
    private data class CachedCatalog(
        val version: Int,
        val sourceId: String,
        val etag: String?,
        val records: List<ReasoningCatalogRecord>,
    )

    companion object {
        private const val CACHE_VERSION = 1
    }
}

private inline fun <T> sourceResult(block: () -> T): T? = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
