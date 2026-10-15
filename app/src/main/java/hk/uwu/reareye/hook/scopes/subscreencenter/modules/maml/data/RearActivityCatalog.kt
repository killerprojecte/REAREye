package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml.data

import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Only the supplied host-user PackageManager is used: no profile or widget enumeration. */
interface RearActivityCatalogProvider {
    fun applications(): List<RearActivityApp>
    fun activities(packageName: String): List<RearActivityEntry>

    /** Real application icon, not Activity icon or widget preview; called only for page rows. */
    fun icon(packageName: String, size: Int): Bitmap?
}

data class RearActivityApp(
    val packageName: String,
    val appName: String,
    val enabled: Boolean = true,
    val isSystemApp: Boolean = false,
)

data class RearActivityEntry(
    val component: ComponentName,
    val activityName: String,
    val appEnabled: Boolean = true,
    val enabled: Boolean = true,
    val exported: Boolean = true,
    val permission: String? = null,
    val targetActivity: String? = null,
) {
    val selectable: Boolean
        get() = appEnabled && enabled && exported && permission.isNullOrBlank() && targetActivity == null
    val activityClassName: String
        get() = component.className.let { name ->
            if (name.startsWith('.')) component.packageName + name else name
        }
}

internal class RearSystemActivityCatalog(private val context: Context) :
    RearActivityCatalogProvider {
    private val pm = context.packageManager
    private fun enabled(manifest: Boolean, setting: Int): Boolean = when (setting) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifest
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        else -> false
    }

    private fun appEnabled(info: android.content.pm.ApplicationInfo) =
        enabled(info.enabled, pm.getApplicationEnabledSetting(info.packageName))

    @Suppress("DEPRECATION")
    override fun applications(): List<RearActivityApp> =
        pm.getInstalledApplications(0).mapNotNull { info ->
            runCatching {
                RearActivityApp(
                    info.packageName,
                    info.loadLabel(pm).toString(),
                    appEnabled(info),
                    isSystemApp = info.flags and
                            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                )
            }.getOrNull()
        }

    @Suppress("DEPRECATION")
    override fun activities(packageName: String): List<RearActivityEntry> {
        if (packageName.isBlank()) return emptyList()
        val info = pm.getPackageInfo(
            packageName,
            PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
        )
        return info.activities.orEmpty().mapNotNull { activity ->
            runCatching { entry(activity) }.getOrNull()
        }
    }

    private fun entry(info: ActivityInfo): RearActivityEntry {
        val component = ComponentName(info.packageName, info.name)
        return RearActivityEntry(
            component, info.loadLabel(pm).toString(), appEnabled(info.applicationInfo),
            enabled(info.enabled, pm.getComponentEnabledSetting(component)), info.exported,
            info.permission, info.targetActivity
        )
    }

    override fun icon(packageName: String, size: Int): Bitmap? = runCatching {
        require(size in 1..RearActivityPage.MAX_ICON_SIZE)
        val drawable = pm.getApplicationIcon(packageName).mutate()
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(it))
        }
    }.getOrNull()
}

internal data class RearActivityPage(
    val packageName: String,
    val offset: Int,
    val limit: Int,
    val iconSize: Int
) {
    companion object {
        const val MAX_LIMIT = 7
        const val MAX_ICON_SIZE = 96
        fun parameters(includePackage: Boolean) = buildList {
            if (includePackage) add(MamlDataParameter("package", MamlDataType.STRING, ""))
            add(MamlDataParameter("offset", MamlDataType.NUMBER, 0, 0.0, Int.MAX_VALUE.toDouble()))
            add(MamlDataParameter("limit", MamlDataType.NUMBER, 3, 1.0, MAX_LIMIT.toDouble()))
            add(
                MamlDataParameter(
                    "iconSize",
                    MamlDataType.NUMBER,
                    64,
                    1.0,
                    MAX_ICON_SIZE.toDouble()
                )
            )
        }

        fun read(request: MamlDataRequest, includePackage: Boolean): RearActivityPage {
            val values = parameters(includePackage).associate { parameter ->
                parameter.name to parameter.coerce(
                    request.values[parameter.name] ?: parameter.defaultValue
                )
            }
            return RearActivityPage(
                values["package"] as? String ?: "", (values["offset"] as Double).toInt(),
                (values["limit"] as Double).toInt(), (values["iconSize"] as Double).toInt()
            )
        }

        fun metadata(page: RearActivityPage, total: Int, count: Int): Map<String, Any?> = mapOf(
            "total" to total.toDouble(), "offset" to page.offset.toDouble(),
            "hasMore" to if (page.offset.toLong() + count < total) 1.0 else 0.0,
            "requestLimit" to page.limit.toDouble(), "requestIconSize" to page.iconSize.toDouble()
        )

        val fields = listOf("total", "offset", "hasMore", "requestLimit", "requestIconSize")
            .map { MamlDataField(it, MamlDataType.NUMBER) }

        fun apps(entries: List<RearActivityApp>) = entries.filter { it.enabled }
            .distinctBy { it.packageName }
            .sortedWith(compareBy<RearActivityApp> { it.isSystemApp }
                .thenBy { it.appName.lowercase(Locale.ROOT) }.thenBy { it.packageName })

        fun activities(entries: List<RearActivityEntry>, packageName: String) = entries
            .filter { it.component.packageName == packageName && it.selectable }
            .distinctBy { it.component.flattenToString() }
            .sortedWith(compareBy<RearActivityEntry> { it.activityName.lowercase(Locale.ROOT) }
                .thenBy { it.component.flattenToString() })
    }
}

/** One bounded worker and one replaceable job per source. Session owns publication revision. */
abstract class RearActivityPageDataSource internal constructor(
    context: Context,
    private val injectedProvider: RearActivityCatalogProvider?,
    executor: ExecutorService?,
    private val includePackage: Boolean,
) : MamlDataSource {
    private val app = context.applicationContext ?: error("Application context required")
    private val displayContext =
        runCatching { app.createDisplayContext(context.display) }.getOrDefault(app)
    private var safeContext =
        displayContext.createConfigurationContext(Configuration(context.resources.configuration))
    private val main = Handler(Looper.getMainLooper())
    private val worker = executor ?: ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(1),
        ThreadFactory { Thread(it, "RearActivityCatalog").apply { isDaemon = true } })
    private val ownsWorker = executor == null

    // Never recycle published bitmaps. Entries are dropped on invalidation, pause and close.
    internal val icons = AppWidgetCatalogLru<Pair<String, Int>, Bitmap>(
        32, 1024L * 1024, 30_000,
        { it.allocationByteCount.toLong() })
    @Volatile
    private var epoch = 0L
    private var paused = false
    private var closed = false
    private var job: Job? = null
    private val subscriptions = mutableSetOf<Subscription>()

    private inner class Job : MamlDataHandle {
        val generation = epoch
        val cancelled = AtomicBoolean()
        @Volatile
        var future: Future<*>? = null
        override fun close() {
            cancelled.set(true)
            future?.cancel(true)
            main.removeCallbacksAndMessages(this)
        }

        fun deliver(value: MamlDataResult, callback: (MamlDataResult) -> Unit) {
            if (cancelled.get()) return
            main.postAtTime({
                if (!closed && !paused && !cancelled.get() && generation == epoch && job === this) {
                    job = null
                    cancelled.set(true)
                    callback(value)
                }
            }, this, SystemClock.uptimeMillis())
        }
    }

    internal abstract fun snapshot(
        provider: RearActivityCatalogProvider, page: RearActivityPage,
        icon: (String) -> Bitmap?
    ): MamlDataSnapshot

    override fun query(request: MamlDataRequest, result: (MamlDataResult) -> Unit): MamlDataHandle {
        checkMain()
        if (closed || paused) return MamlDataHandle.NONE
        job?.close()
        if (ownsWorker) (worker as ThreadPoolExecutor).purge()
        val current = Job().also { job = it }
        try {
            val page = RearActivityPage.read(request, includePackage)
            val provider = injectedProvider ?: RearSystemActivityCatalog(safeContext)
            current.future = worker.submit {
                if (current.cancelled.get()) return@submit
                val output = try {
                    MamlDataResult.Success(snapshot(provider, page) { pkg ->
                        if (current.cancelled.get() || epoch != current.generation) null
                        else {
                            val key = pkg to page.iconSize
                            icons.get(key, current.generation) ?: runCatching {
                                provider.icon(
                                    pkg,
                                    page.iconSize
                                )
                            }.getOrNull()?.let { source ->
                                // Enforce budget even for injected providers returning oversized icons.
                                val bitmap =
                                    if (source.width <= page.iconSize && source.height <= page.iconSize) source
                                    else Bitmap.createScaledBitmap(
                                        source,
                                        page.iconSize,
                                        page.iconSize,
                                        true
                                    )
                                if (!current.cancelled.get() && epoch == current.generation)
                                    icons.put(key, bitmap, current.generation)
                                bitmap
                            }
                        }
                    })
                } catch (e: Exception) {
                    MamlDataResult.Failure(e.message ?: e.javaClass.simpleName)
                }
                current.deliver(output, result)
            }
            if (current.cancelled.get()) current.future?.cancel(true)
        } catch (e: Exception) {
            current.deliver(MamlDataResult.Failure(e.message ?: e.javaClass.simpleName), result)
        }
        return current
    }

    private fun invalidate() {
        epoch++
        job?.close(); job = null
        icons.clear()
        safeContext =
            displayContext.createConfigurationContext(Configuration(displayContext.resources.configuration))
    }

    private inner class Subscription(val callback: () -> Unit) : MamlDataHandle {
        private var attached: MamlDataHandle? = null
        private var removed = false
        fun attach() {
            if (!removed && !paused && !closed && attached == null) attached =
                RearActivityCatalogWatcher.subscribe(app) {
                    if (!removed && !paused && !closed) {
                        invalidate(); callback()
                    }
                }
        }

        fun detach() {
            attached?.close(); attached = null
        }

        override fun close() {
            checkMain(); removed = true; detach(); subscriptions.remove(this)
        }
    }

    override fun subscribeInvalidations(invalidate: () -> Unit): MamlDataHandle {
        checkMain()
        if (closed) return MamlDataHandle.NONE
        return Subscription(invalidate).also { subscriptions.add(it); it.attach() }
    }

    override fun onPause() {
        checkMain(); paused = true; invalidate(); subscriptions.forEach { it.detach() }
    }

    override fun onResume() {
        checkMain(); if (closed) return; invalidate(); paused =
            false; subscriptions.forEach { it.attach() }
    }

    override fun close() {
        checkMain(); if (closed) return
        onPause(); closed = true; subscriptions.toList().forEach { it.close() }
        if (ownsWorker) worker.shutdownNow()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper())
}

/** Catalog invalidation only; replacing removal never means permanent selection deletion. */
internal object RearActivityCatalogWatcher {
    fun isFinalRemoval(intent: Intent): Boolean = intent.action == Intent.ACTION_PACKAGE_REMOVED &&
            !intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

    fun subscribe(context: Context, callback: () -> Unit): MamlDataHandle {
        val main = Handler(Looper.getMainLooper())
        var closed = false
        val token = Any()
        fun changed() {
            // Invalidate the source epoch before an already-queued result can publish.
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (!closed) callback()
            } else main.postAtTime({ if (!closed) callback() }, token, SystemClock.uptimeMillis())
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                changed()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(
            receiver,
            filter,
            Context.RECEIVER_NOT_EXPORTED
        )
        else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        val configuration = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                changed()
            }

            override fun onLowMemory() {
                changed()
            }
        }
        try {
            context.registerComponentCallbacks(configuration)
        } catch (e: Exception) {
            context.unregisterReceiver(receiver); throw e
        }
        return MamlDataHandle {
            if (!closed) {
                closed = true; main.removeCallbacksAndMessages(token)
                context.unregisterReceiver(receiver); context.unregisterComponentCallbacks(
                    configuration
                )
            }
        }
    }
}
