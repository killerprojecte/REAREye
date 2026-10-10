package hk.uwu.reareye.ui.config

import android.content.Context
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.REMOTE_PREFS_GROUP
import hk.uwu.reareye.hook.support.XposedRemoteHookPrefs
import java.io.File

/**
 * 模块 UI 偏好门面。
 *
 * UI 与 Hook 进程通过 [REMOTE_PREFS_GROUP] 共享配置；Hook 进程通过 libxposed
 * RemotePreferences 读取同一组，UI 侧始终使用 service RemotePreferences 作为权威来源。
 */
class PrefsManager(
    val prefs: HookPrefs,
) {
    /** 读取布尔值。 */
    fun getBoolean(key: String, defValue: Boolean): Boolean = prefs.getBoolean(key, defValue)

    /** 写入布尔值。 */
    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    /** 读取字符串集合。 */
    fun getStringSet(key: String, defValue: Set<String> = emptySet()): Set<String> =
        prefs.getStringSet(key, defValue)

    /** 写入字符串集合。 */
    fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }

    /** 读取字符串。 */
    fun getString(key: String, defValue: String = ""): String = prefs.getString(key, defValue)

    /** 写入字符串。 */
    fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    /** 读取整数。 */
    fun getInt(key: String, defValue: Int): Int = prefs.getInt(key, defValue)

    /** 写入整数。 */
    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    /** 读取浮点数。 */
    fun getFloat(key: String, defValue: Float): Float = prefs.getFloat(key, defValue)

    /** 读取动态配置值。 */
    fun getRequirementValue(key: String): Any? {
        val nativePrefs = prefs.native()
        if (!nativePrefs.contains(key)) return null
        return nativePrefs.all()[key]
    }

    /** 判断远程偏好 service 是否已经可靠可读。 */
    fun isRemoteReady(): Boolean = prefs.isRemoteReady()

    /** 写入 RemoteFile；失败由 service 适配记录并返回 false。 */
    fun writeRemoteFile(name: String, bytes: ByteArray): Boolean =
        prefs.writeRemoteFile(name, bytes)

    /** 大文件使用流式 RemoteFile 写入，避免一次性创建完整 ByteArray。 */
    fun writeRemoteFile(name: String, source: File): Boolean =
        prefs.writeRemoteFile(name, source)

    /** 删除 RemoteFile；不存在按幂等成功处理。 */
    fun deleteRemoteFile(name: String): Boolean =
        prefs.deleteRemoteFile(name)

    /** 无快照删除整个 RemotePreferences 组，用于清理旧超大 map。 */
    fun clearRemotePreferences(): Boolean =
        prefs.clearRemotePreferences()

    /** 写入浮点数。 */
    fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    fun getLong(key: String, defValue: Long): Long = prefs.getLong(key, defValue)

    fun putLong(key: String, value: Long) {
        prefs.edit().putLong(key, value).apply()
    }

    fun all() = prefs.all()

    companion object {
        /** 与 HookRuntime 共用的唯一远程偏好组名。 */
        const val DEFAULT_PREFS_NAME = REMOTE_PREFS_GROUP

        /** 返回项目 UI 与 HookRuntime 共用的逻辑偏好组名。 */
        fun defaultPrefsName(packageName: String): String {
            require(packageName == "hk.uwu.reareye") {
                "Unexpected module package name: $packageName"
            }
            return DEFAULT_PREFS_NAME
        }

        /** 从模块 UI Context 创建只使用 libxposed RemotePreferences 的适配。 */
        fun Context.getPrefsManager(): PrefsManager {
            val name = defaultPrefsName(packageName)
            return PrefsManager(XposedRemoteHookPrefs(name))
        }

        /** 从项目内 HookPrefs 创建门面。 */
        fun HookPrefs.getPrefsManager(): PrefsManager = PrefsManager(this)
    }
}
