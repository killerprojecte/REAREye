@file:OptIn(DexKitExperimentalApi::class)

package hk.uwu.reareye.hook.utils

import android.content.Context
import android.os.Process
import com.highcapable.yukihookapi.hook.log.YLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val DEX_KIT_APP_TAG_SEPARATOR = "@"

internal data class DexKitMethodInjectionPoint(
    val className: String,
    val methodName: String,
)

internal data class DexKitCacheSession(
    val bridge: DexKitCacheBridge.RecyclableBridge,
    val sourceLastModified: Long,
    val sourceSha256: String,
)

private data class DexKitSourceIdentity(
    val lastModified: Long,
    val sha256: String,
)

private data class DexKitSourceFingerprint(
    val normalizedPath: String,
    val length: Long,
    val lastModified: Long,
)

private class DexKitSourceIdentityMemo(
    private val digestProvider: (File) -> String = File::sha256OrEmpty,
) {
    private val identities = ConcurrentHashMap<DexKitSourceFingerprint, DexKitSourceIdentity>()

    fun resolve(sourceFile: File): DexKitSourceIdentity {
        val normalizedPath = runCatching { sourceFile.canonicalPath }
            .getOrElse { sourceFile.absolutePath }
        val fingerprint = DexKitSourceFingerprint(
            normalizedPath = normalizedPath,
            length = sourceFile.length().coerceAtLeast(0L),
            lastModified = sourceFile.lastModified().coerceAtLeast(0L),
        )
        return identities.computeIfAbsent(fingerprint) {
            DexKitSourceIdentity(
                lastModified = fingerprint.lastModified,
                sha256 = digestProvider(sourceFile),
            )
        }
    }
}

private val dexKitSourceIdentityMemo = DexKitSourceIdentityMemo()

internal fun createDexKitCacheBridge(
    packageName: String,
    packageVersionCode: Long,
    sourceDir: String,
    dataDir: String,
): DexKitCacheBridge.RecyclableBridge = createDexKitCacheSession(
    context = null,
    packageName = packageName,
    packageVersionCode = packageVersionCode,
    sourceDir = sourceDir,
    dataDir = dataDir,
).bridge

internal fun createDexKitCacheSession(
    context: Context?,
    packageName: String,
    packageVersionCode: Long,
    sourceDir: String,
    dataDir: String,
): DexKitCacheSession {
    val sourceFile = File(sourceDir)
    val sourceIdentity = dexKitSourceIdentityMemo.resolve(sourceFile)
    val sourceLastModified = sourceIdentity.lastModified
    val sourceSha256 = sourceIdentity.sha256
    val appTag = buildDexKitAppTag(
        packageName = packageName,
        packageVersionCode = packageVersionCode,
        sourceLastModified = sourceLastModified,
        sourceSha256 = sourceSha256,
    )
    val create = {
        DexKitCacheBridge.create(
            appTag = appTag,
            path = sourceDir,
        )
    }
    val bridge = try {
        create()
    } catch (_: Exception) {
        YLog.info("[RearWidget] Init DexKit cache appTag=$appTag")
        val cache = createDexKitCache(
            context = context,
            packageName = packageName,
            dataDir = dataDir,
        )
        DexKitCacheBridge.init(cache)
        if (cache === MMKVCache) {
            MMKVCache.syncHostIdentity(packageName, appTag)
        }
        create()
    }
    return DexKitCacheSession(
        bridge = bridge,
        sourceLastModified = sourceLastModified,
        sourceSha256 = sourceSha256,
    )
}

private fun createDexKitCache(
    context: Context?,
    packageName: String,
    dataDir: String,
): DexKitCacheBridge.Cache {
    val targetDir = buildDexKitMMKVDir(dataDir).absolutePath
    val uid = Process.myUid()
    val userId = uid / 100000
    YLog.info(
        "[RearWidget] DexKit cache context=${context?.javaClass?.name ?: "unavailable"} " +
                "contextPackage=${context?.packageName ?: "unavailable"} package=$packageName userId=$userId uid=$uid " +
                "target=$targetDir"
    )
    return runCatching {
        MMKVCache.ensureInitialized(dataDir)
        YLog.info("[RearWidget] DexKit cache mode=mmkv target=$targetDir")
        MMKVCache
    }.getOrElse { error ->
        YLog.warn(
            "[RearWidget] DexKit cache unavailable package=$packageName userId=$userId uid=$uid " +
                    "target=$targetDir exception=${error.javaClass.name} fallback=memory"
        )
        YLog.warn(error)
        MemoryCache
    }
}

internal inline fun resolveDexKitMethodInjectionPoint(
    bridge: DexKitCacheBridge.RecyclableBridge,
    cacheKey: String,
    crossinline finder: DexKitBridge.() -> MethodData?,
): DexKitMethodInjectionPoint? {
    return bridge.getMethodDirectOrNull(cacheKey) {
        finder()
    }?.let { DexKitMethodInjectionPoint(it.className, it.name) }
}

internal inline fun resolveDexKitMethodValue(
    bridge: DexKitCacheBridge.RecyclableBridge,
    cacheKey: String,
    noinline selector: (DexMethod) -> String = { it.name },
    crossinline finder: DexKitBridge.() -> MethodData?,
): String? {
    return bridge.getMethodDirectOrNull(cacheKey) {
        finder()
    }?.let(selector)
}

internal inline fun resolveDexKitClassValue(
    bridge: DexKitCacheBridge.RecyclableBridge,
    cacheKey: String,
    noinline selector: (DexClass) -> String = { it.className },
    crossinline finder: DexKitBridge.() -> ClassData?,
): String? {
    return bridge.getClassDirectOrNull(cacheKey) {
        finder()
    }?.let(selector)
}

internal inline fun resolveDexKitFieldValue(
    bridge: DexKitCacheBridge.RecyclableBridge,
    cacheKey: String,
    noinline selector: (DexField) -> String = { it.name },
    crossinline finder: DexKitBridge.() -> FieldData?,
): String? {
    return bridge.getFieldDirectOrNull(cacheKey) {
        finder()
    }?.let(selector)
}

private fun buildDexKitAppTag(
    packageName: String,
    packageVersionCode: Long,
    sourceLastModified: Long = 0L,
    sourceSha256: String = "",
): String {
    val digestTag = sourceSha256
        .trim()
        .lowercase()
        .take(16)
        .ifEmpty { "no-digest" }
    return listOf(
        packageName,
        packageVersionCode.toString(),
        sourceLastModified.toString(),
        digestTag,
    ).joinToString(DEX_KIT_APP_TAG_SEPARATOR)
}

private fun File.sha256OrEmpty(): String = runCatching {
    val digest = MessageDigest.getInstance("SHA-256")
    inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
}.getOrDefault("")
