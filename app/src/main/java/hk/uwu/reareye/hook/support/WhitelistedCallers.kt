package hk.uwu.reareye.hook.support

import hk.uwu.reareye.generated.AppProperties

/**
 * 需要额外放行的调用方。
 *
 * 同一个白名单同时用于两处闭锁的解除：
 * - system_server 里 signature 权限的强制授予（[hk.uwu.reareye.hook.scopes.system.modules.misc.SignaturePermissionWhitelistModule]）；
 * - 语音助手里 osbot CallerVerifier 的调用方校验（[hk.uwu.reareye.hook.scopes.voiceassist.modules.CallerVerifierWhitelistHook]）。
 */
object WhitelistedCallers {
    /** REAREye 自身与 HyperOS 语音识别示例应用。 */
    val PACKAGES: Set<String> = setOf(
        AppProperties.PROJECT_APP_PACKAGE_NAME,
        "com.mispeech.hyperos.asr.example",
    )
}
