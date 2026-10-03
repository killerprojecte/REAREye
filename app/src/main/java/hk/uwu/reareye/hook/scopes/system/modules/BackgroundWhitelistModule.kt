package hk.uwu.reareye.hook.scopes.system.modules

import android.content.Context
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class BackgroundWhitelistModule : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadSystem {
            val ppRef = "com.android.server.am.ProcessPolicy".toClass().resolve()
            ppRef.firstMethod {
                name = "updateDynamicWhiteList"
                returnType = HashMap::class.java
                parameters(Context::class.java, Int::class.java)
            }.hook {
                after {
                    if (hookPrefs.getBoolean(ConfigKeys.HOOK_BACKGROUND_WHITELIST, true)) {
                        val r = result<HashMap<String, Boolean>>()
                        hookPrefs.getStringSet(ConfigKeys.BACKGROUND_WHITELIST_APPS).forEach {
                            r[it] = true
                        }
                        result = r
                        YLog.debug("Injected apps into dynamic whitelist")
                    }
                }
            }

            ppRef.firstMethod {
                name = "systemReady"
                returnType = Void.TYPE
                parameters(Context::class.java)
            }.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                after {
                    if (hookPrefs.getBoolean(ConfigKeys.HOOK_BACKGROUND_WHITELIST, true)) {
                        val method = instance!!.asResolver().firstMethod {
                            name = "updateApplicationLockedState"
                            returnType = Void.TYPE
                            parameters(String::class.java, Int::class.java, Boolean::class.java)
                        }
                        hookPrefs.getStringSet(ConfigKeys.BACKGROUND_LOCK_APPS).forEach {
                            method.invoke(it, -100, true)
                        }
                        YLog.debug("Injected apps into application locked state")
                    }
                }
            }
        }
    }
}
