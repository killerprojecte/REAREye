package hk.uwu.reareye.hook.activityhost

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.math.min

/** All hidden/server ABI lives here. Missing ABI fails closed without dumping app identities. */
internal object ActivityHostTaskConfigurationAdapter {
    private val COMPAT_FIELDS =
        listOf("compatScreenWidthDp", "compatScreenHeightDp", "compatSmallestScreenWidthDp")

    fun field(instance: Any, name: String): Any? = runCatching {
        fieldOf(instance.javaClass, name).get(instance)
    }.getOrNull()

    private fun fieldOf(type: Class<*>, name: String): Field {
        var current: Class<*>? = type
        while (current != null) {
            val candidate = current
            runCatching { candidate.getDeclaredField(name) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = candidate.superclass
        }
        throw NoSuchFieldException(name)
    }

    private fun methodOf(type: Class<*>, name: String, vararg types: Class<*>): Method {
        var current: Class<*>? = type
        while (current != null) {
            val candidate = current
            runCatching { candidate.getDeclaredMethod(name, *types) }.getOrNull()?.let {
                it.isAccessible = true
                return it
            }
            current = candidate.superclass
        }
        throw NoSuchMethodException(name)
    }

    fun call(instance: Any, name: String): Any? = runCatching {
        methodOf(instance.javaClass, name).invoke(instance)
    }.getOrNull()

    fun service(loader: ClassLoader?): Any? = runCatching {
        Class.forName("android.os.ServiceManager", false, loader)
            .getDeclaredMethod("getService", String::class.java).invoke(null, "activity_task")
    }.getOrNull()

    fun context(service: Any): Context? = field(service, "mContext") as? Context
    fun task(record: Any): Any? = call(record, "getTask") ?: field(record, "task")
    fun component(record: Any): String? = (field(record, "mActivityComponent") as? ComponentName)
        ?.flattenToString()

    fun displayId(record: Any): Int? = call(record, "getDisplayId") as? Int
    fun resolved(record: Any): Configuration? =
        call(record, "getResolvedOverrideConfiguration") as? Configuration

    fun cookies(record: Any, task: Any): List<IBinder> = buildList {
        (field(record, "mLaunchCookie") as? IBinder)?.let(::add)
        // TaskInfo is populated from ActivityRecord cookies by the server, not Intent extras.
        val info = call(task, "getTaskInfo")
        (info?.let { field(it, "launchCookies") } as? Iterable<*>)
            ?.filterIsInstance<IBinder>()?.let(::addAll)
    }

    fun supports(recordClass: Class<*>): Boolean = runCatching {
        fieldOf(recordClass, "mLaunchCookie")
        fieldOf(recordClass, "mActivityComponent")
        methodOf(recordClass, "getTask")
        methodOf(recordClass, "getDisplayId")
        methodOf(recordClass, "getResolvedOverrideConfiguration")
        val window = fieldOf(Configuration::class.java, "windowConfiguration").type
        methodOf(window, "getAppBounds")
        methodOf(window, "getBounds")
        COMPAT_FIELDS.forEach {
            require(
                fieldOf(
                    Configuration::class.java,
                    it
                ).type == Int::class.javaPrimitiveType
            )
        }
        methodOf(Configuration::class.java, "resetScreenLayout", Int::class.javaPrimitiveType!!)
        methodOf(
            Configuration::class.java, "reduceScreenLayout", Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
        )
        true
    }.getOrDefault(false)

    fun independentRearDisplay(context: Context, id: Int, loader: ClassLoader): Boolean =
        runCatching {
            if (id != 1) return false
            val manager = context.getSystemService(DisplayManager::class.java) ?: return false
            val rear = manager.getDisplay(id) ?: return false
            val front = manager.getDisplay(0) ?: return false
            val type = call(rear, "getType") as? Int ?: return false
            val rearId = call(rear, "getUniqueId") as? String ?: return false
            val frontId = call(front, "getUniqueId") as? String ?: return false
            rear.isValid && type == 1 && rearId != frontId && Class.forName(
                "miui.util.MiuiMultiDisplayTypeInfo", false, loader,
            ).getDeclaredMethod("isIndependentRearDevice").invoke(null) == true
        }.getOrDefault(false)

    /** Recompute resource qualifiers only; retain WM's appBounds/maxBounds/insets and rotation. */
    fun apply(parent: Configuration, resolved: Configuration, dpi: Int): Boolean {
        if (dpi == 0) return true // literal inherit: no private density or dp fields
        val window = field(resolved, "windowConfiguration") ?: return false
        val parentWindow = field(parent, "windowConfiguration") ?: return false
        val app = (call(window, "getAppBounds") as? Rect)?.takeUnless { it.isEmpty }
            ?: (call(parentWindow, "getAppBounds") as? Rect)?.takeUnless { it.isEmpty }
            ?: return false
        val bounds = (call(window, "getBounds") as? Rect)?.takeUnless { it.isEmpty }
            ?: (call(parentWindow, "getBounds") as? Rect)?.takeUnless { it.isEmpty }
            ?: return false
        val safe = Rect(app)
        if (!safe.intersect(bounds) || safe.isEmpty) return false
        val derived = derive(safe.width(), safe.height(), dpi)
        val newLayout = runCatching {
            val base = methodOf(
                Configuration::class.java, "resetScreenLayout",
                Int::class.javaPrimitiveType!!
            ).invoke(
                null,
                resolved.screenLayout.takeIf { it != 0 } ?: parent.screenLayout) as Int
            methodOf(
                Configuration::class.java, "reduceScreenLayout", Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
            )
                .invoke(
                    null, base, max(derived.widthDp, derived.heightDp),
                    min(derived.widthDp, derived.heightDp)
                ) as Int
        }.getOrNull() ?: return false
        // Stage hidden compat qualifiers before touching the live configuration: failure is atomic.
        val candidate = Configuration(resolved)
        val compatValues = listOf(derived.widthDp, derived.heightDp, derived.smallestDp)
        if (runCatching {
                COMPAT_FIELDS.zip(compatValues).forEach { (name, value) ->
                    fieldOf(Configuration::class.java, name).setInt(candidate, value)
                }
            }.isFailure) return false
        candidate.densityDpi = dpi
        candidate.screenWidthDp = derived.widthDp
        candidate.screenHeightDp = derived.heightDp
        candidate.smallestScreenWidthDp = derived.smallestDp
        candidate.orientation = derived.orientation
        candidate.screenLayout = newLayout
        resolved.setTo(candidate)
        return true
    }

    data class Derived(
        val widthDp: Int,
        val heightDp: Int,
        val smallestDp: Int,
        val orientation: Int
    )

    fun derive(width: Int, height: Int, dpi: Int): Derived {
        require(width > 0 && height > 0 && dpi in 120..960)
        val w = max(1, (width.toLong() * 160 / dpi).toInt())
        val h = max(1, (height.toLong() * 160 / dpi).toInt())
        return Derived(
            w, h, min(w, h), if (width <= height)
                Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        )
    }

    /** One release-time recomputation, never a per-frame requested-override dispatch. */
    fun restore(tasks: List<Any>, onFailure: () -> Unit) {
        tasks.forEach { task ->
            val service = field(task, "mAtmService") ?: return@forEach onFailure()
            val handler = field(service, "mH") as? Handler ?: return@forEach onFailure()
            val lock = field(service, "mGlobalLock") ?: return@forEach onFailure()
            handler.post {
                synchronized(lock) {
                    runCatching {
                        val parent = call(task, "getParent") ?: return@runCatching
                        val configuration = call(parent, "getConfiguration") as? Configuration
                            ?: return@runCatching
                        methodOf(
                            task.javaClass,
                            "onConfigurationChanged",
                            Configuration::class.java
                        )
                            .invoke(task, configuration)
                    }.onFailure { onFailure() }
                }
            }
        }
    }
}
