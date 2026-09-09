package hk.uwu.reareye.hook.scopes.subscreencenter

import hk.uwu.reareye.hook.core.HookModule
import hk.uwu.reareye.hook.core.YLog
import hk.uwu.reareye.hook.scopes.Scope
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.MusicControlWhitelistModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearWallpaperHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.SubScreenBackHomeWhitelistModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.VideoLoopModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.VideoProgressResumeModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.VideoVolumeHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed.AppEmbedElementHook
import hk.uwu.reareye.hook.scopes.systemui.modules.appembed.SystemUiAppEmbedHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.lyrics.LyriconHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.ExtraTimeTipHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.RearWidgetHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.SystemUiNotificationBridgeHook

class SubscreenCenterScope : Scope {
    override val hooks: List<HookModule> = buildList {
        if (isRearDevice) {
            addAll(
                listOf(
                    MusicControlWhitelistModule(),
                    SubScreenBackHomeWhitelistModule(),
                    VideoLoopModule(),
                    VideoProgressResumeModule(),
                    RearWallpaperHook(),
                    RearWidgetHook(),
                    SystemUiNotificationBridgeHook(),
                    LyriconHook(),
                    VideoVolumeHook(),
                    ExtraTimeTipHook(),
                    AppEmbedElementHook(),
                    SystemUiAppEmbedHook()
                )
            )
        } else {
            YLog.debug("This device is not support rear screen, skip load some features that this device is not supported")
        }
    }
}
