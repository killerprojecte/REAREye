package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitFieldValue
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

@OptIn(DexKitExperimentalApi::class)
class SubScreenBackHomeWhitelistModule : RoxyHooker() {
    companion object {
        private const val SUBSCREEN_HOME_TO_FRONT_METHOD_CACHE_KEY =
            "SSC_BACK_HOME_HOME_TO_FRONT_METHOD"
        private const val SUBSCREEN_FOREGROUND_PACKAGE_FIELD_CACHE_KEY =
            "SSC_BACK_HOME_FOREGROUND_PACKAGE_FIELD"
        private const val AOD_REASON = "aod"
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
            val homeToFrontPoint = resolveSubScreenHomeToFrontMethod(bridge)
            val foregroundPackageFieldName = resolveForegroundPackageFieldName(
                bridge = bridge,
                homeToFrontPoint = homeToFrontPoint,
            )

            homeToFrontPoint.className.toClass().resolve().firstMethod {
                name = homeToFrontPoint.methodName
                returnType = Void.TYPE
                parameters(String::class.java)
            }.hook {
                replaceUnit {
                val reason = args(0).cast<String>()
                    val whitelist = hookPrefs.getStringSet(
                    ConfigKeys.SUBSCREEN_LOCK_BACK_HOME_WHITELIST_APPS,
                )
                if (reason != AOD_REASON || whitelist.isEmpty()) {
                    callOriginal(*args)
                    return@replaceUnit
                }

                    val foregroundPackage = instance!!.asResolver().firstField {
                    name = foregroundPackageFieldName
                    type = String::class.java
                }.get<String>()
                    val moreDebug = hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)
                if (moreDebug) {
                    YLog.debug(
                        "Handle subscreen home return reason=$reason package=$foregroundPackage",
                    )
                }
                if (foregroundPackage == null || foregroundPackage !in whitelist) {
                    callOriginal(*args)
                    return@replaceUnit
                }

                if (moreDebug) {
                    YLog.debug(
                        "Skip SubScreen home return reason=$reason package=$foregroundPackage",
                    )
                }
                }
            }
        }
    }

    private fun PackageScope.resolveSubScreenHomeToFrontMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = SUBSCREEN_HOME_TO_FRONT_METHOD_CACHE_KEY,
        ) {
            // SubScreenCenterApp.e(String) pulls SubScreenLauncher to display 1 for AOD.
            findMethod {
                searchPackages("com.xiaomi.subscreencenter")
                matcher {
                    paramTypes(String::class.java)
                    returnType = "void"
                    usingStrings(
                        "Start SubScreen Home reason ",
                        "getHomeToFrontOptions from Aod or turning off",
                    )
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve SubScreen home-to-front method")
    }

    private fun PackageScope.resolveForegroundPackageFieldName(
        bridge: DexKitCacheBridge.RecyclableBridge,
        homeToFrontPoint: DexKitMethodInjectionPoint,
    ): String {
        return resolveDexKitFieldValue(
            bridge = bridge,
            cacheKey = SUBSCREEN_FOREGROUND_PACKAGE_FIELD_CACHE_KEY,
        ) {
            findField {
                searchPackages(homeToFrontPoint.className.substringBeforeLast('.'))
                matcher {
                    declaredClass = homeToFrontPoint.className
                    type = "java.lang.String"
                    readMethods {
                        add {
                            declaredClass = homeToFrontPoint.className
                            name = homeToFrontPoint.methodName
                            paramTypes(String::class.java)
                            returnType = "void"
                            usingStrings("Start SubScreen Home reason ")
                        }
                    }
                }
            }.singleOrNull()
        } ?: error("DexKit failed to resolve SubScreen foreground package field")
    }
}
