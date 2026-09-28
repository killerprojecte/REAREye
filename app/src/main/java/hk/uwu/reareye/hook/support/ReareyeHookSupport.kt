package hk.uwu.reareye.hook.support

import android.content.Context
import android.content.pm.ApplicationInfo
import hk.uwu.roxyhook.HookParam
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.prefs.Preferences
import hk.uwu.roxyhook.prefs.booleanPreference
import hk.uwu.roxyhook.prefs.floatPreference
import hk.uwu.roxyhook.prefs.intPreference
import hk.uwu.roxyhook.prefs.longPreference
import hk.uwu.roxyhook.prefs.stringPreference
import hk.uwu.roxyhook.prefs.stringSetPreference
import java.io.File
import hk.uwu.roxyhook.android.appInfo as roxyAppInfo
import hk.uwu.roxyhook.android.lifecycle.appContext as roxyAppContext
import hk.uwu.roxyhook.android.systemContext as roxySystemContext

/** Hook-time helpers do not retain a PackageScope on hooker instances. */
val PackageScope.hookAppInfo: ApplicationInfo
    get() = roxyAppInfo ?: ApplicationInfo().apply {
        packageName = this@hookAppInfo.packageName
        processName = this@hookAppInfo.processName
    }
val PackageScope.hookAppContext: Context? get() = roxyAppContext
val PackageScope.hookSystemContext: Context get() = resolveSystemContext(this)
val PackageScope.hookPrefs: HookPrefs
    get() = ReareyePrefs(this, runCatching { prefs(REMOTE_PREFS_GROUP) }.getOrNull())

class ReareyePrefs internal constructor(
    private val scope: PackageScope,
    private val delegate: Preferences?,
) : HookPrefs {
    override fun getString(key: String, defaultValue: String): String =
        delegate?.get(stringPreference(key, defaultValue)) ?: defaultValue

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
        delegate?.get(stringSetPreference(key, defaultValue)) ?: defaultValue

    override fun getInt(key: String, defaultValue: Int): Int =
        delegate?.get(intPreference(key, defaultValue)) ?: defaultValue

    override fun getLong(key: String, defaultValue: Long): Long =
        delegate?.get(longPreference(key, defaultValue)) ?: defaultValue

    override fun getFloat(key: String, defaultValue: Float): Float =
        delegate?.get(floatPreference(key, defaultValue)) ?: defaultValue

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        delegate?.get(booleanPreference(key, defaultValue)) ?: defaultValue

    override fun contains(key: String): Boolean = delegate?.contains(key) == true
    override fun all(): Map<String, Any?> = emptyMap()
    override fun isRemoteReady(): Boolean = delegate != null
    override fun copyRemoteFileTo(name: String, destination: File): Boolean = runCatching {
        val safeName = RemoteFileName.requireValid(name)
        require(!destination.isDirectory) { "Remote file destination is a directory" }
        destination.parentFile?.mkdirs()
        scope.runtime.platform.openRemoteFile(safeName).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        true
    }.onFailure { YLog.error("Unable to copy remote file: $name", it) }.getOrDefault(false)

    override fun edit(): HookPrefsEditor =
        error("Roxy remote preferences are read-only in hook processes")
}

val HookParam.instanceClass: Class<*> get() = instance?.javaClass ?: member.declaringClass

val PackageScope.isRearDevice: Boolean
    get() = runCatching {
        val type = Class.forName("android.os.SystemProperties", false, appClassLoader)
            .getDeclaredMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
        type.isAccessible = true
        type.invoke(null, "persist.sys.multi_display_type", 1) as Int == 6
    }.getOrDefault(false)

private fun resolveSystemContext(scope: PackageScope): Context {
    if (scope.isSystemServer) return scope.roxySystemContext
    scope.hookAppContext?.let { return it }
    return runCatching {
        val threadClass = Class.forName("android.app.ActivityThread", false, scope.appClassLoader)
        val thread = threadClass.getMethod("currentActivityThread").invoke(null)
        threadClass.getMethod("getSystemContext").invoke(thread) as Context
    }.getOrElse { error("Unable to resolve system context for ${scope.packageName}") }
}

