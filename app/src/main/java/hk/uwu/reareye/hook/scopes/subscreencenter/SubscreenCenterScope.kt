package hk.uwu.reareye.hook.scopes.subscreencenter

import com.highcapable.yukihookapi.hook.entity.YukiBaseHooker
import com.highcapable.yukihookapi.hook.log.YLog
import hk.uwu.reareye.hook.scopes.Scope
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.MusicControlWhitelistModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearWallpaperHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.SubScreenBackHomeWhitelistModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.VideoLoopModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.VideoProgressResumeModule
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.VideoVolumeHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.lyrics.LyriconHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.ExtraTimeTipHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.RearWidgetHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.SystemUiFocusAuthorizationWhitelistHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.SystemUiNotificationBridgeHook

class SubscreenCenterScope : Scope {
    override val hooks: List<YukiBaseHooker> = buildList {
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
                    SystemUiFocusAuthorizationWhitelistHook(),
                    LyriconHook(),
                    VideoVolumeHook(),
                    ExtraTimeTipHook()
                )
            )
        } else {
            YLog.debug("This device is not support rear screen, skip load some features that this device is not supported")
        }
    }
}
