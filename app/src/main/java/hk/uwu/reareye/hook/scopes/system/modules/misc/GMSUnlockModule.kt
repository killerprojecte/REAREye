package hk.uwu.reareye.hook.scopes.system.modules.misc

import android.content.pm.FeatureInfo
import android.util.ArrayMap
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class GMSUnlockModule : RoxyHooker() {
    private val blacklistServices =
        listOf("cn.google.services", "com.google.android.feature.services_updater")

    override fun PackageScope.onHook() {
        loadSystem {
            val clz = "com.android.server.SystemConfig".toClass().resolve()

            val remove: (Any, Boolean) -> Unit = { instance, log ->
                instance!!.asResolver().firstMethod {
                    name = "removeFeature"
                    returnType = Void.TYPE
                    parameters(String::class.java)
                }.apply {
                    blacklistServices.forEach {
                        invoke(it)
                    }
                    if (log) {
                        @Suppress("UNCHECKED_CAST")
                        val map = instance!!.asResolver().firstMethod {
                            name = "getAvailableFeatures"
                        }.invoke() as ArrayMap<String, FeatureInfo>
                        YLog.debug("Hooked system features $map")
                    } else {
                        YLog.debug("Removed system features")
                    }
                }
            }
            clz.firstConstructor {
                parameterCount = 0
            }.hook {
                after {
                    YLog.debug("Hooking SystemConfig constructor")
                    if (hookPrefs.getBoolean(ConfigKeys.MISC_HOOK_GMS_UNLOCK, false)) {
                        remove(instance!!, true)
                    }
                }
            }

            clz.firstMethod {
                name = "getAvailableFeatures"
            }.hook {
                before {
                    if (hookPrefs.getBoolean(ConfigKeys.MISC_HOOK_GMS_UNLOCK, false)) {
                        remove(instance!!, false)
                        YLog.debug("Features has been patched, remove this hook")
                        removeSelf()
                    }
                }
            }
        }
    }
}
