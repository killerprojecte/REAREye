package hk.uwu.reareye.hook.scopes.system

import hk.uwu.reareye.generated.AppProperties
import hk.uwu.reareye.hook.scopes.Scope
import hk.uwu.reareye.hook.scopes.system.modules.BackgroundWhitelistModule
import hk.uwu.reareye.hook.scopes.system.modules.CustomBoundsCompatModule
import hk.uwu.reareye.hook.scopes.system.modules.DisableRearScreenCoverHook
import hk.uwu.reareye.hook.scopes.system.modules.DisableSubScreenDoubleTapSleepHook
import hk.uwu.reareye.hook.scopes.system.modules.DisableSubScreenDoubleTapWakeHook
import hk.uwu.reareye.hook.scopes.system.modules.DisableSubScreenHighLoadModeHook
import hk.uwu.reareye.hook.scopes.system.modules.ExternalDisplayLaunchUnlockModule
import hk.uwu.reareye.hook.scopes.system.modules.NativeEnvWriterHook
import hk.uwu.reareye.hook.scopes.system.modules.RearScreenActivityWhitelistModule
import hk.uwu.reareye.hook.scopes.system.modules.misc.GMSUnlockModule
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

class SystemScope : RoxyHooker(), Scope {
    override fun PackageScope.onHook() = buildList<RoxyHooker> {
        add(GMSUnlockModule())
        add(ExternalDisplayLaunchUnlockModule())
        if (AppProperties.IS_PUBLIC_BETA) {
            YLog.debug("Public beta build, skip native cpp hooks")
        } else {
            add(NativeEnvWriterHook())
        }
        if (isRearDevice) {
            add(RearScreenActivityWhitelistModule())
            add(BackgroundWhitelistModule())
            add(DisableRearScreenCoverHook())
            add(DisableSubScreenDoubleTapSleepHook())
            add(DisableSubScreenDoubleTapWakeHook())
            add(DisableSubScreenHighLoadModeHook())
            add(CustomBoundsCompatModule())
        } else {
            YLog.debug("This device does not support a rear screen; skipping system rear-screen hooks")
        }
    }.forEach(::loadHooker)
}
