package hk.uwu.reareye.hook.scopes.system.modules

import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class DisableSubScreenHighLoadModeHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadSystem {
            val dualScreenCoverManagerRef = "com.android.server.power.DualScreenCoverManager"
                .toClass()
                .resolve()

            dualScreenCoverManagerRef.firstMethod {
                name = "updateHighLoadSceneMode"
                parameters(Int::class.java, Boolean::class.java)
                returnType = Void.TYPE
            }.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                replaceUnit {
                    val value = args(1).boolean()
                    if (!value) {
                        callOriginal(*args)
                        return@replaceUnit
                    }

                    val packageName = instance.mainDisplayForegroundPackageName()
                    if (packageName in hookPrefs.getStringSet(
                            ConfigKeys.SUBSCREEN_HIGH_LOAD_MODE_DISABLED_APPS,
                        )
                    ) {
                        result = null
                        if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
                            YLog.debug("Skip subscreen high load mode package=$packageName")
                        }
                        return@replaceUnit
                    }
                    callOriginal(*args)
                }
            }
        }
    }
}
