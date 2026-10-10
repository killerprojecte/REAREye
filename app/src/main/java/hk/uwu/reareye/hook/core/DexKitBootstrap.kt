package hk.uwu.reareye.hook.core

import hk.uwu.reareye.hook.support.YLog

/** Delayed, one-shot initialization of DexKit's native ABI. */
object DexKitBootstrap {
    @Volatile
    private var loaded = false

    @Volatile
    private var attempted = false

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (attempted) return false
        attempted = true
        return runCatching {
            System.loadLibrary("dexkit")
            loaded = true
            YLog.info("DexKit native ABI loaded")
            true
        }.onFailure {
            YLog.error("DexKit native ABI initialization failed", it)
        }.getOrDefault(false)
    }
}
