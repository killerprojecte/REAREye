package hk.uwu.reareye.hook.scopes.system.modules

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import hk.uwu.reareye.ui.config.ConfigKeys

class RearScreenActivityWhitelistModule : YukiBaseHooker() {

    // We create a list of apps that should NEVER be forced to the rear screen
    private val blacklistedPackages = setOf(
        "com.google.android.projection.gearhead" // Android Auto
    )

    override fun onHook() {
        loadSystem {
            val asiRef = "com.android.server.wm.ActivityStarterImpl".toClass().resolve()

            asiRef.firstMethod {
                name = "isShouldShowOnRearDisplay"
                returnType = Boolean::class.java
            }.hook {
                before {
                    if (!prefs.getBoolean(ConfigKeys.HOOK_ACTIVITIES_WHITELIST, true)) return@before

                    val whitelist = prefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS)

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
                    // Prevent Android Auto from being hijacked
                    try {
                        val arObj = args(0).any()
                        if (arObj != null) {
                            val packageName = arObj.asResolver().firstField {
                                name = "packageName"
                                type = String::class.java
                            }.get<String>()

                            if (blacklistedPackages.contains(packageName)) {
                                return@after // Bail out and let the system handle Android Auto naturally!
                            }
                        }
                    } catch (_: Throwable) {
                        // Safely ignore if arguments differ (using '_' tells Kotlin we intentionally aren't using the error variable)
                    }

                    if (prefs.getBoolean(ConfigKeys.ALLOW_ALL_ACTIVITIES, false)) {
                        resultTrue()
                    }
                }
            }

            asiRef.firstMethod {
                name = "isAllowedToStartOnRearDisplay"
                returnType = Boolean::class.java
            }.hook().after {
                val arObj = args(0).any() ?: return@after
                val packageName = arObj.asResolver().firstField {
                    name = "packageName"
                    type = String::class.java
                }.get<String>()

                // Prevent Android Auto from being hijacked
                if (blacklistedPackages.contains(packageName)) {
                    return@after // Bail out and let the system handle Android Auto naturally!
                }

                if (prefs.getBoolean(ConfigKeys.ALLOW_ALL_ACTIVITIES, false)) {
                    resultTrue()
                    return@after
                }
                if (!prefs.getBoolean(ConfigKeys.HOOK_ACTIVITIES_WHITELIST, true)) return@after

                val whitelist = prefs.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS)
                val inWhitelist = result<Boolean>()

                if (inWhitelist == false) {
                    if (whitelist.contains(packageName)) {
                        resultTrue()
                        YLog.debug("Allow starting $packageName while rear screen is locked")
                    }
                }
            }

            asiRef.firstMethod {
                name = "handlerTransitionFinished"
            }.hook().before {
                if (prefs.getBoolean(ConfigKeys.HOOK_SKIP_LOCK_BACK_HOME, false)) {
                    val arg = args(3)
                    arg.setFalse()
                }
            }
        }
    }
}
