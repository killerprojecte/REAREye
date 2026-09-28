package hk.uwu.reareye.hook.scopes.subscreencenter

import hk.uwu.reareye.hook.scopes.Scope
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.ForceAppWidgetEnabledHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.MusicControlWhitelistModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.PersonalAssistantBackScreenDeviceHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearWallpaperHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.SubScreenBackHomeWhitelistModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.UnlimitedSubscreenAppListHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.VideoLoopModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.VideoProgressResumeModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.VideoVolumeHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.lyrics.LyriconHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.ExtraTimeTipHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.RearWidgetHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.SystemUiNotificationBridgeHook
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class SubscreenCenterScope : RoxyHooker(), Scope {
    override fun PackageScope.onHook() {
        if (!isRearDevice) {
            YLog.debug("This device does not support a rear screen; skipping SubscreenCenter hooks")
            return
        }

        val hookers = listOf(
            ForceAppWidgetEnabledHook(),
            MusicControlWhitelistModule(),
            SubScreenBackHomeWhitelistModule(),
            UnlimitedSubscreenAppListHook(),
            VideoLoopModule(),
            VideoProgressResumeModule(),
            RearWallpaperHook(),
            RearWidgetHook(),
            SystemUiNotificationBridgeHook(),
            LyriconHook(),
            VideoVolumeHook(),
            ExtraTimeTipHook(),
            PersonalAssistantBackScreenDeviceHook(),
        )
        hookers.forEach(::loadHooker)
    }
}
