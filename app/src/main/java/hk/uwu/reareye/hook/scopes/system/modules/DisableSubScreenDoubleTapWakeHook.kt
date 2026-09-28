package hk.uwu.reareye.hook.scopes.system.modules

import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class DisableSubScreenDoubleTapWakeHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadSystem {
            val dualScreenCoverManagerRef = "com.android.server.power.DualScreenCoverManager"
                .toClass()
                .resolve()

            dualScreenCoverManagerRef.firstMethod {
                name = "isScreenSkippedWakeup"
                parameters(Int::class.java, String::class.java, Int::class.java)
                returnType = Boolean::class.java
            }.hook {
                before {
                val groupId = args(0).int()
                val details = args(1).string()
                val packageName = instance.mainDisplayForegroundPackageName()
                if (groupId == 1 && details == WAKE_REASON_DOUBLE_TAP &&
                    packageName in hookPrefs.getStringSet(
                        ConfigKeys.SUBSCREEN_DOUBLE_TAP_WAKE_DISABLED_APPS,
                    )
                ) {
                    result = true
                    if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
                        YLog.debug("Skip subscreen double tap wake package=$packageName")
                    }
                }
                }
            }
        }
    }

    companion object {
        private const val WAKE_REASON_DOUBLE_TAP = "android.policy:KEY"
    }
}
