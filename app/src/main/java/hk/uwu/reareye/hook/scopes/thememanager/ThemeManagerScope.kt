package hk.uwu.reareye.hook.scopes.thememanager

import hk.uwu.reareye.hook.preset.PresetPackFilesHook
import hk.uwu.reareye.hook.scopes.Scope
import hk.uwu.reareye.hook.scopes.thememanager.modules.AiGeneratedAppDeviceHook
import hk.uwu.reareye.hook.scopes.thememanager.modules.RearWallpaperThemeManagerSyncHook
import hk.uwu.reareye.hook.scopes.thememanager.modules.UnlockTemplateMaximumLimitHook
import hk.uwu.reareye.hook.scopes.thememanager.modules.UnlockVideoRestrictionsHook
import hk.uwu.reareye.hook.scopes.thememanager.modules.UnmuteVideoWallpaperHook
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class ThemeManagerScope : RoxyHooker(), Scope {
    override fun PackageScope.onHook() {
        if (!isRearDevice) {
            YLog.debug("This device does not support a rear screen; skipping ThemeManager hooks")
            return
        }

        listOf(
            UnlockVideoRestrictionsHook(),
            UnlockTemplateMaximumLimitHook(),
            UnmuteVideoWallpaperHook(),
            RearWallpaperThemeManagerSyncHook(),
            AiGeneratedAppDeviceHook(),
            PresetPackFilesHook(),
        ).forEach(::loadHooker)
    }
}
