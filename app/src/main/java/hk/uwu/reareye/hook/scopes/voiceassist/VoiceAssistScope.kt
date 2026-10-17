package hk.uwu.reareye.hook.scopes.voiceassist

import hk.uwu.reareye.hook.scopes.Scope
import hk.uwu.reareye.hook.scopes.voiceassist.modules.CallerVerifierWhitelistHook
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

/** 语音助手（com.miui.voiceassist）进程内的 Hook。 */
class VoiceAssistScope : RoxyHooker(), Scope {
    override fun PackageScope.onHook() {
        listOf(
            CallerVerifierWhitelistHook(),
        ).forEach(::loadHooker)
    }
}
