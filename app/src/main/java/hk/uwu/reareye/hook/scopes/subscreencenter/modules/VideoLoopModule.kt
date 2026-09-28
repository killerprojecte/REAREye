package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

@OptIn(DexKitExperimentalApi::class)
class VideoLoopModule : RoxyHooker() {
    companion object {
        private const val VIDEO_ELEMENT_GET_LOOPING_CACHE_KEY =
            "SSC_VIDEO_ELEMENT_GET_LOOPING_METHOD"
        private const val FALLBACK_VIDEO_ELEMENT_CLASS = "com.miui.maml.elements.video.VideoElement"
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val versionCode =
                resolveHookPackageVersionCode(
                    hookSystemContext,
                    hookAppInfo.packageName,
                    hookAppInfo.sourceDir
                )
            val bridge = runtime.manage(
                createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                packageVersionCode = versionCode,
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                )
            )
            val point = resolveVideoElementGetLoopingMethod(bridge)
            val videoElRef = point.className.toClass().resolve()
            videoElRef.firstMethod {
                name = point.methodName
            }.hook {
                replaceAny {
                    if (hookPrefs.getBoolean(ConfigKeys.HOOK_VIDEO_LOOPING, false)) {
                    return@replaceAny true
                }
                    return@replaceAny callOriginal()
                }
            }
        }
    }

    private fun PackageScope.resolveVideoElementGetLoopingMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = VIDEO_ELEMENT_GET_LOOPING_CACHE_KEY,
        ) {
            // DexKit source anchor:
            // .tmp-ref/decompiled-jadx/sources/com/miui/maml/elements/video/VideoElement.java:68
            // VideoElement.getLooping() returns the private loop state used during doTick().
            findMethod {
                searchPackages("com.miui.maml.elements.video")
                matcher {
                    name = "getLooping"
                    paramCount(0)
                    returnType = "boolean"
                }
            }.singleOrNull()
        } ?: DexKitMethodInjectionPoint(FALLBACK_VIDEO_ELEMENT_CLASS, "getLooping")
    }
}
