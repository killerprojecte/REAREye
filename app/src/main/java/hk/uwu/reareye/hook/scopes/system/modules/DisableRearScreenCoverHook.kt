package hk.uwu.reareye.hook.scopes.system.modules

import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class DisableRearScreenCoverHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadSystem {
            val clz = "com.android.server.power.DualScreenCoverManager".toClass().resolve()
            clz.firstMethod {
                name = "showCoverView"
                parameters(Int::class.java)
            }.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                replaceUnit {
                    val displayId = args(0).int()
                    if (displayId == 1 && hookPrefs.getBoolean(
                            ConfigKeys.HOOK_DISABLE_REAR_SCREEN_COVER,
                            false
                        )
                    ) {
                        // 阻止显示cover view
                        YLog.debug("Rejected show cover view on rear screen")
                        return@replaceUnit
                    } else {
                        callOriginal(displayId)
                    }
                }
            }
        }
    }
}
