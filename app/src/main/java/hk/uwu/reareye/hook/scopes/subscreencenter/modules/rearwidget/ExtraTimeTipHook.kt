package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.os.Bundle
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitMethodValue
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.repository.rearwidget.RearBusinessExtraConfigRepository.getShowTimeTipForBusiness
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

@OptIn(DexKitExperimentalApi::class)
class ExtraTimeTipHook : RoxyHooker() {
    companion object {
        private const val WIDGET_SPEC_CLASS_CACHE_KEY = "SSC_WIDGET_SPEC_CLASS"
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
            val clz = resolveWidgetSpecClass(bridge).toClass().resolve()
            clz.constructor().build().hook {
                before {
                    val moreDebug = hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)
                    val bundle = args.getOrNull(3) as? Bundle
                    if (bundle == null) {
                        if (moreDebug) {
                            YLog.debug("bundle is null ${args.joinToString { it.toString() }}")
                        }
                        return@before
                    }

                    val pm = hookPrefs.getPrefsManager()
                    val business = bundle.getString("business")
                    if (business != null) {
                        if (moreDebug) {
                            YLog.debug("time tip process biz: $business")
                        }
                        val showTimeTip = pm.getShowTimeTipForBusiness(business)
                        if (args.size > 11) {
                            args[11] = showTimeTip
                        }
                        if (moreDebug) {
                            YLog.debug("time tip state biz=$business showTimeTip=$showTimeTip")
                        }
                    } else if (moreDebug) {
                        YLog.debug(
                            "business is null ${
                                bundle.keySet()
                                    ?.joinToString(separator = "\n") { key ->
                                        @Suppress("DEPRECATION")
                                        "$key=${bundle.get(key)}"
                                    }
                            }"
                        )
                    }
                }
            }
        }
    }

    private fun PackageScope.resolveWidgetSpecClass(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): String {
        return resolveDexKitMethodValue(
            bridge = bridge,
            cacheKey = WIDGET_SPEC_CLASS_CACHE_KEY,
            selector = { it.className },
        ) {
            // DexKit source anchor:
            // .tmp-ref/decompiled-jadx/sources/com/bumptech/glide/d.java:138
            // ThemeDataParser creates new m2.a(...) specs from rearScreen/runtime.json.
            findMethod {
                matcher {
                    paramCount(1)
                    returnType = "java.util.List"
                    usingStrings(
                        "/data/system/theme_magic/users/\$user_id/rearScreen/runtime.json",
                        "/system/media/rearscreen/template/default/rearScreen.json",
                    )
                }
            }.singleOrNull()?.invokes?.singleOrNull { method ->
                method.isConstructor && method.paramCount >= 10
            }
        } ?: error("DexKit failed to resolve widget spec class")
    }
}
