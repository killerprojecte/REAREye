package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.media.MediaMetadata
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.SmartAssistantRegistry
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

@OptIn(DexKitExperimentalApi::class)
class MusicControlWhitelistModule : RoxyHooker() {
    companion object {
        private const val TAG = "MusicControlWhitelist"
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val versionCode = resolveHookPackageVersionCode(
                hookSystemContext,
                hookAppInfo.packageName,
                hookAppInfo.sourceDir,
            )
            val bridge = runtime.manage(
                createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = versionCode,
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                )
            )
            val registry = SmartAssistantRegistry(bridge) { className -> className.toClass() }
            val snapshotPoint = registry.snapshotMethod
            snapshotPoint.className.toClass().resolve().firstMethod {
                name = snapshotPoint.methodName
                parameterCount = 0
            }.hook {
                after {
                    if (!hookPrefs.getBoolean(ConfigKeys.HOOK_MUSIC_CONTROLS_WHITELIST, true)) {
                        return@after
                    }
                    val snapshot = result ?: return@after
                    val rawMap = registry.primaryMap(snapshot)
                    // Before Application is ready the host returns its empty registry sentinel.
                    if (rawMap === java.util.Collections.EMPTY_MAP) return@after
                    val map = unwrapMutableMap(rawMap)
                    runCatching {
                        hookPrefs.getStringSet(ConfigKeys.MUSIC_CONTROLS_WHITELIST_APPS)
                            .forEach { app ->
                                map[app] = "music"
                            }
                    }.onFailure { YLog.error("[$TAG] Cannot update app registry", it) }
                        .getOrThrow()
                    YLog.debug("Hooked SubscreenCenter whitelist $map")
                }
            }

            val musicControlListenerClz =
                "com.miui.maml.elements.MusicControlScreenElement$1".toClass().resolve()
            musicControlListenerClz.firstMethod {
                name = "onClientMetadataUpdate"
                returnType = Void.TYPE
                parameters(MediaMetadata::class.java)
            }.hook {
                after {
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_MUSIC_CONTROLS_FORCE_UPDATE,
                            false,
                        )
                    ) return@after
                    val i = instance!!.asResolver().firstField {
                        name = "this$0"
                    }.get() ?: return@after
                    val mRoot = i.asResolver().firstField {
                        name = "mRoot"
                        superclass()
                    }.get() ?: return@after
                    mRoot.asResolver().firstMethod {
                        name = "requestUpdate"
                    }.invoke()
                    if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
                        YLog.debug("Request render controller to update metadata")
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun PackageScope.unwrapMutableMap(any: Any): MutableMap<Any, Any?> {
        var current: Any = any
        repeat(32) {
            val map = current as? MutableMap<Any?, Any?>
            if (map != null) {
                try {
                    map.putAll(emptyMap())
                    return map as MutableMap<Any, Any?>
                } catch (error: UnsupportedOperationException) {
                    YLog.debug(
                        "map wrapper is not writable, resolving backing map " +
                                "class=${current.javaClass.name} err=${error.message}"
                    )
                }
            }

            val backing = current.asResolver().optional(silent = true).firstFieldOrNull {
                superclass()
                typeCondition = { type -> Map::class.java.isAssignableFrom(type) }
                modifiersNot(Modifiers.STATIC)
            }?.get<Any>()
            if (backing == null || backing === current) {
                val message = "Cannot resolve writable map backing field: ${current.javaClass.name}"
                YLog.error("[$TAG] $message")
                error(message)
            }
            current = backing
        }

        val message = "Map wrapper nesting exceeds resolver limit: ${any.javaClass.name}"
        YLog.error("[$TAG] $message")
        error(message)
    }

}
