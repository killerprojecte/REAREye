package hk.uwu.reareye.hook.utils

import com.tencent.mmkv.MMKV
import org.json.JSONArray
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.io.File

private const val DEX_KIT_MMKV_ID = "reareye_dexkit_cache"
private const val DEX_KIT_MMKV_RELATIVE_PATH = "/files/reareye_dexkit_cache"
private const val STRING_KEY_PREFIX = "string:"
private const val LIST_KEY_PREFIX = "list:"
private const val HOST_IDENTITY_KEY_PREFIX = "host_identity:"

@OptIn(DexKitExperimentalApi::class)
internal object MMKVCache : DexKitCacheBridge.Cache {
    @Volatile
    private var mmkv: MMKV? = null

    @Volatile
    private var cacheRootPath: String? = null

    @Suppress("DEPRECATION")
    fun ensureInitialized(dataDir: String) {
        val cacheDir = buildDexKitMMKVDir(dataDir)
        val rootPath = cacheDir.absolutePath
        if (mmkv != null && cacheRootPath == rootPath) {
            return
        }
        synchronized(this) {
            if (mmkv != null && cacheRootPath == rootPath) {
                return
            }
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                error("Failed to create DexKit MMKV directory: $rootPath")
            }
            require(cacheDir.isDirectory) { "DexKit MMKV path is not a directory: $rootPath" }
            MMKV.initialize(rootPath, System::loadLibrary)
            mmkv = requireNotNull(MMKV.mmkvWithID(DEX_KIT_MMKV_ID, MMKV.MULTI_PROCESS_MODE)) {
                "Failed to open DexKit MMKV cache"
            }
            cacheRootPath = rootPath
        }
    }

    override fun getString(key: String, default: String?): String? =
        requireMMKV().decodeString(stringKey(key), default)

    override fun putString(key: String, value: String) {
        requireMMKV().encode(stringKey(key), value)
    }

    override fun getStringList(key: String, default: List<String>?): List<String>? {
        val encoded = requireMMKV().decodeString(listKey(key), null) ?: return default
        return runCatching { decodeStringList(encoded) }
            .onFailure { requireMMKV().removeValueForKey(listKey(key)) }
            .getOrNull() ?: default
    }

    override fun putStringList(key: String, value: List<String>) {
        requireMMKV().encode(listKey(key), JSONArray(value).toString())
    }

    override fun remove(key: String) {
        requireMMKV().removeValueForKey(stringKey(key))
        requireMMKV().removeValueForKey(listKey(key))
    }

    override fun getAllKeys(): Collection<String> =
        requireMMKV().allKeys()
            ?.asSequence()
            ?.mapNotNull(::logicalKeyOrNull)
            ?.toSet()
            ?.toList()
            ?: emptyList()

    override fun clearAll() {
        requireMMKV().clearAll()
    }

    fun syncHostIdentity(
        packageName: String,
        appTag: String,
    ) {
        val mmkv = requireMMKV()
        val key = hostIdentityKey(packageName)
        val cachedAppTag = mmkv.decodeString(key, null)
        if (!cachedAppTag.isNullOrBlank() && cachedAppTag != appTag) {
            DexKitCacheBridge.clearCache(cachedAppTag)
        }
        mmkv.encode(key, appTag)
    }

    private fun requireMMKV(): MMKV =
        requireNotNull(mmkv) { "DexKit MMKV cache is not initialized" }

    private fun decodeStringList(value: String): List<String> {
        val jsonArray = JSONArray(value)
        return List(jsonArray.length()) { index -> jsonArray.getString(index) }
    }
}

internal fun buildDexKitMMKVDir(dataDir: String): File =
    File(dataDir, DEX_KIT_MMKV_RELATIVE_PATH.removePrefix("/"))

private fun stringKey(key: String): String = "$STRING_KEY_PREFIX$key"

private fun listKey(key: String): String = "$LIST_KEY_PREFIX$key"

private fun logicalKeyOrNull(key: String): String? {
    return when {
        key.startsWith(STRING_KEY_PREFIX) -> key.removePrefix(STRING_KEY_PREFIX)
        key.startsWith(LIST_KEY_PREFIX) -> key.removePrefix(LIST_KEY_PREFIX)
        else -> null
    }
}

private fun hostIdentityKey(packageName: String): String = "$HOST_IDENTITY_KEY_PREFIX$packageName"
