package hk.uwu.reareye.hook.scopes.voiceassist.modules

import android.content.Context
import android.os.Binder
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.WhitelistedCallers
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.DexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.DexKitCacheBridge
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.util.concurrent.ConcurrentHashMap

/**
 * 放行语音助手（com.miui.voiceassist）内置 osbot SDK 的调用方校验。
 *
 * 该应用打包了 `com.aios.osbot.security.CallerVerifier`，它的
 * `isCallerAllowed(Context context, String tag)` 只放行两类调用方，其余一律拒绝：
 * 1. 系统应用（`ApplicationInfo.FLAG_SYSTEM`）；
 * 2. 与宿主包 `xz.m.c` 同签名（`PackageManager.checkSignatures(...) == 0`）。
 * 顺便还会拒绝 ADB shell（uid 2000）。因此第三方应用拿不到它受保护的 IPC。
 *
 * 类名与包名都被混淆，所以用 DexKit 按方法签名定位，而不是写死类名：
 * `isCallerAllowed` + `(Context, String) -> boolean`。
 *
 * 只放行 [WhitelistedCallers] 里的包，其他调用方继续走原判定（含 ADB shell 拒绝）。
 */
@OptIn(DexKitExperimentalApi::class)
class CallerVerifierWhitelistHook : RoxyHooker() {

    /** uid → 是否白名单调用方，避免每次校验都做一次 PackageManager 查询。 */
    private val allowedCallerCache = ConcurrentHashMap<Int, Boolean>()

    override fun PackageScope.onHook() {
        loadApp(VOICE_ASSIST_PACKAGE) {
            val versionCode = resolveHookPackageVersionCode(
                hookSystemContext,
                hookAppInfo.packageName,
                hookAppInfo.sourceDir,
            )
            val bridge = runtime.manage(
                createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = versionCode,
                    sourceDir = hookAppInfo.sourceDir,
                    dataDir = hookAppInfo.dataDir,
                )
            )
            val point = resolveCallerVerifierMethod(bridge) ?: run {
                YLog.warn("DexKit failed to resolve CallerVerifier.isCallerAllowed")
                return@loadApp
            }
            val verifierRef = runCatching { point.className.toClass().resolve() }.getOrElse {
                YLog.warn("CallerVerifier class not found: ${point.className}", it)
                return@loadApp
            }

            verifierRef.firstMethod {
                name = point.methodName
                parameterCount = 2
            }.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                before {
                    // 原始实现用的就是第一个参数里的 Context，这里保持一致。
                    val context = args(0).value as? Context ?: return@before
                    val callingUid = Binder.getCallingUid()
                    if (!isWhitelistedCaller(context, callingUid)) return@before
                    result = true
                    YLog.debug("CallerVerifier: allowed whitelisted caller uid=$callingUid")
                }
            }
        }
    }

    private fun isWhitelistedCaller(context: Context, uid: Int): Boolean {
        allowedCallerCache[uid]?.let { return it }
        // 查询失败时不写缓存，下次校验再试。
        val packages = runCatching { context.packageManager.getPackagesForUid(uid) }
            .getOrNull() ?: return false
        val allowed = packages.any { it in WhitelistedCallers.PACKAGES }
        allowedCallerCache[uid] = allowed
        return allowed
    }

    private fun resolveCallerVerifierMethod(
        bridge: DexKitCacheBridge.RecyclableBridge,
    ): DexKitMethodInjectionPoint? {
        return resolveDexKitMethodInjectionPoint(
            bridge = bridge,
            cacheKey = CALLER_VERIFIER_CACHE_KEY,
        ) {
            findMethod {
                matcher {
                    name = "isCallerAllowed"
                    paramCount(2)
                    paramTypes(CONTEXT_TYPE, STRING_TYPE)
                    returnType = "boolean"
                }
            }.singleOrNull()
        }
    }

    private companion object {
        const val VOICE_ASSIST_PACKAGE = "com.miui.voiceassist"
        const val CALLER_VERIFIER_CACHE_KEY = "VA_CALLER_VERIFIER_IS_CALLER_ALLOWED"
        const val CONTEXT_TYPE = "android.content.Context"
        const val STRING_TYPE = "java.lang.String"
    }
}
