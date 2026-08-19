package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.media.MediaMetadata
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitClassValue
import hk.uwu.reareye.hook.utils.resolveDexKitFieldValue
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

@OptIn(DexKitExperimentalApi::class)
class MusicControlWhitelistModule : YukiBaseHooker() {
    companion object {
        private const val SMART_ASSISTANT_CONFIG_CLASS_CACHE_KEY =
            "SSC_MUSIC_WHITELIST_CONFIG_CLASS"
        private const val SMART_ASSISTANT_CONFIG_PRIMARY_MAP_FIELD_CACHE_KEY =
            "SSC_MUSIC_WHITELIST_CONFIG_PRIMARY_MAP_FIELD"
    }

    override fun onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val versionCode = resolveHookPackageVersionCode(
                systemContext,
                appInfo.packageName,
                appInfo.sourceDir,
            )
            val bridge = createDexKitCacheBridge(
                packageName = appInfo.packageName,
                packageVersionCode = versionCode,
                sourceDir = appInfo.sourceDir,
                dataDir = appInfo.dataDir,
            )
            val configClassName = resolveSmartAssistantConfigClassName(bridge)
            val primaryMapFieldName = resolveSmartAssistantConfigPrimaryMapFieldName(
                bridge,
                configClassName,
            )
            val clz = configClassName.toClass().resolve()
            val field = clz.firstField {
                name = primaryMapFieldName
                type = Map::class.java
            }
            val map = buildMap<String, String> {
                @Suppress("UNCHECKED_CAST")
                putAll(field.get() as Map<String, String>)

                // TRUE FIX: Force Apple Music to be permanently recognized as a music app
                put("com.apple.android.music", "music")

                prefs.getStringSet(ConfigKeys.MUSIC_CONTROLS_WHITELIST_APPS).forEach {
                    put(it, "music")
                }
            }
            if (prefs.getBoolean(ConfigKeys.HOOK_MUSIC_CONTROLS_WHITELIST, true)) {
                field.set(map)
                YLog.debug("Hooked SubscreenCenter whitelist ${field.get()}")
            }

            val musicControlListenerClz =
                "com.miui.maml.elements.MusicControlScreenElement$1".toClass().resolve()
            musicControlListenerClz.firstMethod {
                name = "onClientMetadataUpdate"
                returnType = Void.TYPE
                parameters(MediaMetadata::class.java)
            }.hook().after {
                if (!prefs.getBoolean(
                        ConfigKeys.HOOK_MUSIC_CONTROLS_FORCE_UPDATE,
                        false,
                    )
                ) return@after
                val i = instance.asResolver().firstField {
                    name = "this$0"
                }.get() ?: return@after
                val mRoot = i.asResolver().firstField {
                    name = "mRoot"
                    superclass()
                }.get() ?: return@after
                mRoot.asResolver().firstMethod {
                    name = "requestUpdate"
                }.invoke()
                if (prefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
                    YLog.debug("Request render controller to update metadata")
                }
            }
        }
    }

    private fun resolveSmartAssistantConfigClassName(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String {
        return resolveDexKitClassValue(
            bridge = bridge,
            cacheKey = SMART_ASSISTANT_CONFIG_CLASS_CACHE_KEY,
        ) {
            findClass {
                matcher {
                    usingStrings(
                        "com.android.incallui",
                        "com.xiaomi.music",
                        "com.xiaomi.smarthome",
                        "mihomeCamera",
                        "unified.music",
                    )
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve smart assistant config class")
    }

    private fun resolveSmartAssistantConfigPrimaryMapFieldName(
        bridge: DexKitCacheBridge.RecyclableBridge,
        configClassName: String,
    ): String {
        return resolveDexKitFieldValue(
            bridge = bridge,
            cacheKey = SMART_ASSISTANT_CONFIG_PRIMARY_MAP_FIELD_CACHE_KEY,
        ) {
            findField {
                matcher {
                    declaredClass = configClassName
                    type = "java.util.Map"
                    readMethods {
                        add {
                            declaredClass = configClassName
                            paramTypes(String::class.java)
                            returnType = "boolean"
                            usingStrings("unified.music", "music")
                        }
                    }
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve smart assistant primary map field")
    }
}