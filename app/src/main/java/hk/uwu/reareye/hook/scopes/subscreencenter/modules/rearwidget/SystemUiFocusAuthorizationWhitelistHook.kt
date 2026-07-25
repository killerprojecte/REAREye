package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Context
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import hk.uwu.reareye.ui.config.ConfigKeys
import java.util.concurrent.ConcurrentHashMap

class SystemUiFocusAuthorizationWhitelistHook : YukiBaseHooker() {
    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val PLUGIN_PACKAGE = "miui.systemui.plugin"
        private const val PLUGIN_CONTROLLER_CLASS =
            "com.android.systemui.statusbar.notification.DynamicIslandPluginController"
        private const val PLUGIN_IMPLEMENTATION_CLASS =
            "miui.systemui.notification.NotificationDynamicIslandPluginImpl"
        private const val NOTIFICATION_SETTINGS_CLASS =
            "miui.systemui.notification.NotificationSettingsManager"
        private const val TAG = "REAREye-FocusAuth"
    }

    private val hookedSettingsClasses = ConcurrentHashMap.newKeySet<Class<*>>()

    override fun onHook() {
        loadApp(SYSTEM_UI_PACKAGE) {
            runCatching {
                PLUGIN_CONTROLLER_CLASS.toClass().resolve().firstMethod {
                    name = "onPluginLoaded"
                    parameterCount = 3
                }.hook().after {
                    val plugin = args.getOrNull(0) ?: return@after
                    val pluginContext = args.getOrNull(1) as? Context
                    installWhitelistHook(plugin, pluginContext)
                }
            }.onSuccess {
                YLog.info("[$TAG] plugin observer installed")
            }.onFailure { error ->
                YLog.warn("[$TAG] plugin observer unavailable reason=${error.message}")
                YLog.warn(error)
            }
        }
    }

    private fun installWhitelistHook(plugin: Any, pluginContext: Context?) {
        val pluginPackage = pluginContext?.packageName
        if (pluginPackage != PLUGIN_PACKAGE) {
            YLog.warn("[$TAG] skip unexpected plugin package=$pluginPackage")
            return
        }
        val pluginClass = plugin.javaClass
        if (pluginClass.name != PLUGIN_IMPLEMENTATION_CLASS) {
            YLog.warn("[$TAG] skip unexpected plugin implementation=${pluginClass.name}")
            return
        }
        val classLoader = pluginClass.classLoader
        if (classLoader == null) {
            YLog.warn("[$TAG] whitelist hook unavailable reason=plugin classLoader is null")
            return
        }

        runCatching {
            val settingsClass = Class.forName(
                NOTIFICATION_SETTINGS_CLASS,
                false,
                classLoader,
            )
            val whitelistMethod =
                SystemUiFocusAuthBypassPolicy.findWhitelistMethod(settingsClass)
                    ?: error(
                        "$NOTIFICATION_SETTINGS_CLASS.canPassXMSPermission(String):boolean not found"
                    )
            if (!hookedSettingsClasses.add(settingsClass)) return

            try {
                XposedBridge.hookMethod(
                    whitelistMethod,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val packageName = param.args.getOrNull(0) as? String
                            val enabled = prefs.getBoolean(
                                ConfigKeys.HOOK_DYNAMIC_ISLAND_AUTH_WHITELIST,
                                true,
                            )
                            val selectedPackages = prefs.getStringSet(
                                ConfigKeys.DYNAMIC_ISLAND_AUTH_WHITELIST_APPS,
                            )
                            if (!SystemUiFocusAuthBypassPolicy.shouldBypass(
                                    packageName = packageName,
                                    enabled = enabled,
                                    selectedPackages = selectedPackages,
                                )
                            ) return

                            param.result = true
                            YLog.info(
                                "[$TAG] whitelist bypass package=$packageName " +
                                        "method=${settingsClass.name}.${whitelistMethod.name}"
                            )
                        }
                    },
                )
            } catch (error: Throwable) {
                hookedSettingsClasses.remove(settingsClass)
                throw error
            }

            YLog.info(
                "[$TAG] whitelist hook installed package=$pluginPackage " +
                        "implementation=${pluginClass.name} configKey=" +
                        ConfigKeys.DYNAMIC_ISLAND_AUTH_WHITELIST_APPS
            )
        }.onFailure { error ->
            YLog.warn("[$TAG] whitelist hook unavailable reason=${error.message}")
            YLog.warn(error)
        }
    }
}
