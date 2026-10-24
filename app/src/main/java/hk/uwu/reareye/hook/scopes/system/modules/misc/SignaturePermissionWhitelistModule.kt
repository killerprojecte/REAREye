package hk.uwu.reareye.hook.scopes.system.modules.misc

import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.WhitelistedCallers
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import java.util.concurrent.ConcurrentHashMap

/**
 * 让白名单应用强制拿到需要 signature 保护级别的权限。
 *
 * HyperOS 上不少能力（语音服务绑定、智能助理外部代理等）用 signature 权限保护，没有平台或
 * 厂商签名的第三方应用即使声明了 `<uses-permission>`，运行时判定仍然是拒绝。这里在
 * system_server 里 Hook 权限判定的入口，对白名单调用方把 signature 权限直接判为已授予。
 *
 * Hook 目标取自本机框架的实现类：`com.android.server.pm.permission.PermissionManagerService`
 * 是 IPermissionManager 的入口，它把请求转发给 access 层
 * `com.android.server.permission.access.permission.PermissionService`，因此只需 Hook 后者即可
 * 覆盖应用侧与服务绑定两条路径：
 *
 * - `checkUidPermission(int uid, String permissionName, String deviceId)`
 *   Context.checkSelfPermission / checkCallingPermission / ActivityManager.checkComponentPermission
 *   （服务绑定即走这条）最终都会落到这里；
 * - `checkPermission(String packageName, String permissionName, String deviceId, int userId)`
 *   PackageManager.checkPermission(权限, 包名) 走这条。
 *
 * 放行需同时满足：调用方属于 [WhitelistedCallers.PACKAGES]；目标权限的 protection 为
 * [PermissionInfo.PROTECTION_SIGNATURE]；开关 [ConfigKeys.MISC_HOOK_SIGNATURE_PERMISSION_GRANT]
 * 打开。普通权限与运行时（dangerous）权限的判定保持原样。
 */
class SignaturePermissionWhitelistModule : RoxyHooker() {

    /** 权限名 → 是否 signature 保护，命中后长期缓存，避免在判定热路径上反复查 PackageManager。 */
    private val signaturePermissionCache = ConcurrentHashMap<String, Boolean>()

    /** uid → 是否白名单应用，避免每次判定都解析 uid 对应的包名。 */
    private val allowedUidCache = ConcurrentHashMap<Int, Boolean>()

    @Volatile
    private var cachedPackageManager: PackageManager? = null

    override fun PackageScope.onHook() {
        loadSystem {
            val scope = this
            val service = runCatching { PERMISSION_SERVICE.toClass().resolve() }.getOrElse {
                YLog.warn("PermissionService not found; skip signature permission whitelist")
                return@loadSystem
            }

            service.firstMethodOrNull {
                name = "checkUidPermission"
                returnType = Int::class.javaPrimitiveType
            }?.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                before {
                    val uid = args(0).value as? Int ?: return@before
                    val permission = args(1).value as? String ?: return@before
                    if (!isAllowedUid(scope, uid)) return@before
                    if (!shouldForceGrant(scope, permission)) return@before
                    result = PackageManager.PERMISSION_GRANTED
                    YLog.debug("Granted signature permission $permission to uid=$uid")
                }
            }

            service.firstMethodOrNull {
                name = "checkPermission"
                returnType = Int::class.javaPrimitiveType
            }?.hook {
                hotReloadPolicy = HotReloadPolicy.KEEP
                before {
                    val packageName = args(0).value as? String ?: return@before
                    val permission = args(1).value as? String ?: return@before
                    if (packageName !in WhitelistedCallers.PACKAGES) return@before
                    if (!shouldForceGrant(scope, permission)) return@before
                    result = PackageManager.PERMISSION_GRANTED
                    YLog.debug("Granted signature permission $permission to $packageName")
                }
            }
        }
    }

    private fun shouldForceGrant(scope: PackageScope, permission: String): Boolean =
        isSignatureProtected(scope, permission) &&
                scope.hookPrefs.getBoolean(ConfigKeys.MISC_HOOK_SIGNATURE_PERMISSION_GRANT, true)

    /** 直接以 uid 作为缓存键：不同用户的 uid 天然不同，无需再拆 appId。 */
    private fun isAllowedUid(scope: PackageScope, uid: Int): Boolean {
        allowedUidCache[uid]?.let { return it }
        val pm = systemPackageManager(scope) ?: return false
        // 查询失败（PackageManager 尚未就绪）时不写缓存，下次判定再试。
        val packages = runCatching { pm.getPackagesForUid(uid) }.getOrNull() ?: return false
        val allowed = packages.any { it in WhitelistedCallers.PACKAGES }
        allowedUidCache[uid] = allowed
        return allowed
    }

    private fun systemPackageManager(scope: PackageScope): PackageManager? =
        cachedPackageManager
            ?: runCatching { scope.hookSystemContext.packageManager }.getOrNull()
                ?.also { cachedPackageManager = it }

    /**
     * 权限是否受 signature 保护。`getProtection()` 取的是 `protectionLevel & 0xF`，因此
     * `signature`、`signature|privileged` 等组合都会命中。权限确实不存在则缓存为否；查询异常
     * 则视为 PackageManager 未就绪，不缓存。
     */
    private fun isSignatureProtected(scope: PackageScope, permission: String): Boolean {
        signaturePermissionCache[permission]?.let { return it }
        val pm = systemPackageManager(scope) ?: return false
        val info = try {
            pm.getPermissionInfo(permission, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            signaturePermissionCache[permission] = false
            return false
        } catch (_: Throwable) {
            return false
        }
        val signature = info.protection == PermissionInfo.PROTECTION_SIGNATURE
        signaturePermissionCache[permission] = signature
        return signature
    }

    private companion object {
        const val PERMISSION_SERVICE =
            "com.android.server.permission.access.permission.PermissionService"
    }
}
