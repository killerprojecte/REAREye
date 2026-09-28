package hk.uwu.reareye.hook.scopes.system.modules

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class RearScreenActivityWhitelistModule : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadSystem {
            val asiRef = "com.android.server.wm.ActivityStarterImpl".toClass().resolve()
            val activityInfoClz = "android.content.pm.ActivityInfo".toClass()
            val activityRecordClz = "com.android.server.wm.ActivityRecord".toClass()
            asiRef.optional().firstMethodOrNull {
                name = "isShouldShowOnRearDisplay"
                parameters(activityInfoClz)
                returnType = Boolean::class.java
            }?.hook {
                before {
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_ACTIVITIES_WHITELIST,
                            true
                        )
                    ) return@before
                    val whitelist = hookPrefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS)
                    val field = asiRef.firstField {
                        name = "REAR_SCREEN_METADATA_WHITE_LIST"
                        type = Set::class.java
                    }
                    val set = field.get<HashSet<String>>() ?: return@before
                    set.clear()
                    set.add("com.retroarch")
                    set.addAll(whitelist)
                    YLog.debug("Injected Activities Whitelist")
                }

                after {
                    if (hookPrefs.getBoolean(ConfigKeys.ALLOW_ALL_ACTIVITIES, false)) {
                        result = true
                    }
                }
            } ?: asiRef.optional().firstMethodOrNull {
                name = "isShouldShowOnRearDisplay"
                parameters(activityRecordClz)
                returnType = Boolean::class.java
            }?.hook {
                before {
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_ACTIVITIES_WHITELIST,
                            true
                        )
                    ) return@before
                    val whitelist = hookPrefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS)
                    val field = asiRef.firstField {
                        name = "REAR_SCREEN_METADATA_WHITE_LIST"
                        type = Set::class.java
                    }
                    val set = field.get<HashSet<String>>() ?: return@before
                    set.clear()
                    set.add("com.retroarch")
                    set.addAll(whitelist)
                    YLog.debug("Injected Activities Whitelist")
                }

                after {
                    if (hookPrefs.getBoolean(ConfigKeys.ALLOW_ALL_ACTIVITIES, false)) {
                        result = true
                    }
                }
            }


            asiRef.firstMethod {
                name = "isAllowedToStartOnRearDisplay"
                returnType = Boolean::class.java
            }.hook {
                after {
                    if (hookPrefs.getBoolean(ConfigKeys.ALLOW_ALL_ACTIVITIES, false)) {
                        result = true
                    return@after
                }
                    if (!hookPrefs.getBoolean(
                            ConfigKeys.HOOK_ACTIVITIES_WHITELIST,
                            true
                        )
                    ) return@after
                    val whitelist = hookPrefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS)
                val inWhitelist = result<Boolean>()
                if (inWhitelist == false) {
                    val arObj = args(0).value ?: return@after
                    val packageName = arObj.asResolver().firstField {
                        name = "packageName"
                        type = String::class.java
                    }.get<String>()
                    if (whitelist.contains(packageName)) {
                        result = true
                        YLog.debug("Allow starting $packageName while rear screen is locked")
                    }
                }
                }
            }

            asiRef.firstMethod {
                name = "handlerTransitionFinished"
            }.hook {
                before {
                    if (hookPrefs.getBoolean(ConfigKeys.HOOK_SKIP_LOCK_BACK_HOME, false)) {
                    val arg = args(3)
                        arg.value = false
                    }
                }
            }
        }
    }
}
