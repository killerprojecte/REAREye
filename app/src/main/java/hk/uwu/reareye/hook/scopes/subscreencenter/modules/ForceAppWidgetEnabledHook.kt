package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.content.ContentResolver
import android.provider.Settings
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.classOf
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.annotations.DexKitExperimentalApi

@OptIn(DexKitExperimentalApi::class)
class ForceAppWidgetEnabledHook : RoxyHooker() {
    private companion object {
        const val PROPERTY_KEY = "persist.sys.app.widget.enable"
        const val SETTING_KEY = "subscreen_app_widget_enable"
    }

    override fun PackageScope.onHook() {
        loadApp(
            "com.xiaomi.subscreencenter",
            "com.android.thememanager",
            "com.miui.personalassistant"
        ) {
            "android.os.SystemProperties".toClass().resolve().firstMethod {
                name = "getBoolean"
                parameters(classOf<String>(), classOf<Boolean>())
                returnType = classOf<Boolean>()
            }.hook {
                before { if (args[0] == PROPERTY_KEY) result = true }
            }

            val secureSettings = classOf<Settings.Secure>().resolve()
            secureSettings.firstMethod {
                name = "getInt"
                parameters(
                    classOf<ContentResolver>(),
                    classOf<String>(),
                    classOf<Int>(),
                )
                returnType = classOf<Int>()
            }.hook {
                before { if (args[1] == SETTING_KEY) result = 1 }
            }
            secureSettings.firstMethod {
                name = "getInt"
                parameters(classOf<ContentResolver>(), classOf<String>())
                returnType = classOf<Int>()
            }.hook {
                before { if (args[1] == SETTING_KEY) result = 1 }
            }
        }
    }
}
