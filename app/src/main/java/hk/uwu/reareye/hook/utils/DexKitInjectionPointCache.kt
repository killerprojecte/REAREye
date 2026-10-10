@file:OptIn(DexKitExperimentalApi::class)

package hk.uwu.reareye.hook.utils

import hk.uwu.reareye.hook.support.YLog
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexClass
import org.luckypray.dexkit.wrap.DexField
import org.luckypray.dexkit.wrap.DexMethod

private const val DEX_KIT_APP_TAG_SEPARATOR = "@"

internal data class DexKitMethodInjectionPoint(
    val className: String,
    val methodName: String,
)

internal fun createDexKitCacheBridge(
    packageName: String,
    packageVersionCode: Long,
    sourceDir: String,
    dataDir: String,
): DexKitCacheBridge.RecyclableBridge {
    val appTag = buildDexKitAppTag(packageName, packageVersionCode)
    val create = {
        DexKitCacheBridge.create(
            appTag = appTag,
            path = sourceDir,
        )
    }
    return try {
        create()
    } catch (_: Exception) {
        YLog.info("Init DexKit cache")
        val cache = createDexKitCache(dataDir)
        DexKitCacheBridge.init(cache)
        if (cache === MMKVCache) {
            MMKVCache.syncHostVersion(packageName, packageVersionCode)
        }
        create()
    }
}

private fun createDexKitCache(dataDir: String): DexKitCacheBridge.Cache {
    return runCatching {
        MMKVCache.ensureInitialized(dataDir)
        MMKVCache
    }.getOrElse {
        YLog.warn(it)
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

internal fun buildDexKitAppTag(packageName: String, packageVersionCode: Long): String {
    return "$packageName$DEX_KIT_APP_TAG_SEPARATOR$packageVersionCode"
}
