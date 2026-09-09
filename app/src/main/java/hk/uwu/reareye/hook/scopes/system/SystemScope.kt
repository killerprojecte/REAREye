package hk.uwu.reareye.hook.scopes.system

import hk.uwu.reareye.generated.AppProperties
import hk.uwu.reareye.hook.core.HookModule
import hk.uwu.reareye.hook.core.YLog
import hk.uwu.reareye.hook.scopes.Scope
import hk.uwu.reareye.hook.scopes.system.modules.BackgroundWhitelistModule
import hk.uwu.reareye.hook.scopes.system.modules.AppEmbedInitialLayoutHook
import hk.uwu.reareye.hook.scopes.system.modules.CustomBoundsCompatModule
import hk.uwu.reareye.hook.scopes.system.modules.DisableRearScreenCoverHook
import hk.uwu.reareye.hook.scopes.system.modules.DisableSubScreenDoubleTapSleepHook
import hk.uwu.reareye.hook.scopes.system.modules.DisableSubScreenDoubleTapWakeHook
import hk.uwu.reareye.hook.scopes.system.modules.DisableSubScreenHighLoadModeHook
import hk.uwu.reareye.hook.scopes.system.modules.ExternalDisplayLaunchUnlockModule
import hk.uwu.reareye.hook.scopes.system.modules.NativeEnvWriterHook
import hk.uwu.reareye.hook.scopes.system.modules.RearScreenActivityWhitelistModule
import hk.uwu.reareye.hook.scopes.system.modules.misc.GMSUnlockModule

class SystemScope : Scope {

    override val hooks: List<HookModule> = buildList {
        add(GMSUnlockModule())
        add(ExternalDisplayLaunchUnlockModule())
        if (AppProperties.IS_PUBLIC_BETA) {
            YLog.debug("Public beta build, skip native cpp hooks")
        } else {
            add(NativeEnvWriterHook())
        }
        if (isRearDevice) {
            addAll(
                listOf(
                    AppEmbedInitialLayoutHook(),
                    RearScreenActivityWhitelistModule(),
                    BackgroundWhitelistModule(),
                    DisableRearScreenCoverHook(),
                    DisableSubScreenDoubleTapSleepHook(),
                    DisableSubScreenDoubleTapWakeHook(),
                    DisableSubScreenHighLoadModeHook(),
                    CustomBoundsCompatModule()
                )
            )
        } else {
            YLog.debug("This device is not support rear screen, skip load some features that this device is not supported")
        }
    }
}
