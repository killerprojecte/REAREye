package hk.uwu.reareye.hook.utils

import com.tencent.mmkv.MMKV
import org.json.JSONArray
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.io.File
import com.highcapable.yukihookapi.hook.log.YLog

private const val DEX_KIT_MMKV_ID = "reareye_dexkit_cache"
private const val DEX_KIT_MMKV_RELATIVE_PATH = "/files/reareye_dexkit_cache"
private const val STRING_KEY_PREFIX = "string:"
private const val LIST_KEY_PREFIX = "list:"
private const val HOST_VERSION_KEY_PREFIX = "host_version:"

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

            // FIXED: Prevent crash if HyperOS blocks folder creation
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                YLog.warn("Failed to create DexKit MMKV directory: $rootPath, skipping cache.")
                return
            }

            if (!cacheDir.isDirectory) {
                YLog.warn("DexKit MMKV path is not a directory: $rootPath, skipping cache.")
                return
            }

            try {
                MMKV.initialize(rootPath, System::loadLibrary)
                mmkv = MMKV.mmkvWithID(DEX_KIT_MMKV_ID, MMKV.MULTI_PROCESS_MODE)
                if (mmkv == null) {
                    YLog.warn("Failed to open DexKit MMKV cache.")
                    return
                }
                cacheRootPath = rootPath
            } catch (e: Throwable) {
                YLog.warn("MMKV initialization failed: ${e.message}")
            }
        }
    }

    // FIXED: Safely handle null MMKV by using the safe call operator (?.) and fallbacks
    override fun getString(key: String, default: String?): String? =
        mmkv?.decodeString(stringKey(key), default) ?: default

    override fun putString(key: String, value: String) {
        mmkv?.encode(stringKey(key), value)
    }

    override fun getStringList(key: String, default: List<String>?): List<String>? {
        val encoded = mmkv?.decodeString(listKey(key), null) ?: return default
        return runCatching { decodeStringList(encoded) }
            .onFailure { mmkv?.removeValueForKey(listKey(key)) }
            .getOrNull() ?: default
    }

    override fun putStringList(key: String, value: List<String>) {
        mmkv?.encode(listKey(key), JSONArray(value).toString())
    }

    override fun remove(key: String) {
        mmkv?.removeValueForKey(stringKey(key))
        mmkv?.removeValueForKey(listKey(key))
    }

    override fun getAllKeys(): Collection<String> =
        mmkv?.allKeys()
            ?.asSequence()
            ?.mapNotNull(::logicalKeyOrNull)
            ?.toSet()
            ?.toList()
            ?: emptyList()

    override fun clearAll() {
        mmkv?.clearAll()
    }

    fun syncHostVersion(
        packageName: String,
        versionCode: Long,
    ) {
        val currentMmkv = mmkv ?: return // Exit safely if null
        val key = hostVersionKey(packageName)
        val cachedVersionCode = currentMmkv.decodeLong(key, Long.MIN_VALUE)
        if (cachedVersionCode != Long.MIN_VALUE && cachedVersionCode != versionCode) {
            DexKitCacheBridge.clearCache(buildDexKitAppTag(packageName, cachedVersionCode))
        }
        currentMmkv.encode(key, versionCode)
    }

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

private fun hostVersionKey(packageName: String): String = "$HOST_VERSION_KEY_PREFIX$packageName"
