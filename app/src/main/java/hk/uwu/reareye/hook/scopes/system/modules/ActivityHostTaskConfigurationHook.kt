package hk.uwu.reareye.hook.scopes.system.modules

import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.activityhost.ActivityHostContract
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationRegistry
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeBootstrapRegistry
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.internal.activityhost.IActivityHostTaskRegistry
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

/** Cookie scoped final Activity configuration override, NOT a Task requested-override ABI. */
class ActivityHostTaskConfigurationHook : RoxyHooker() {
    private val ledger =
        ActivityHostTaskConfigurationRegistry<IBinder, IBinder>(ActivityHostContract.MAX_SESSIONS)
    private val deaths = HashMap<IBinder, IBinder.DeathRecipient>()
    @Volatile
    private var context: Context? = null
    @Volatile
    private var active = true
    @Volatile
    private var configurationAvailable = false
    private var serverLoader: ClassLoader? = null
    private val warned = HashSet<String>()

    private fun warnOnce(reason: String) = synchronized(warned) {
        if (warned.add(reason)) YLog.warn("ActivityHost task configuration unavailable: $reason")
    }

    private fun authorizedUid(): Int {
        check(active) { "Task registry closed" }
        val context = context ?: error("Task registry context unavailable")
        val uid = Binder.getCallingUid()
        val packages = context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty()
        val expected =
            context.packageManager.getApplicationInfo(ActivityHostContract.SYSTEM_UI_PACKAGE, 0).uid
        if (uid != expected || ActivityHostContract.SYSTEM_UI_PACKAGE !in packages) {
            throw SecurityException("Task registry caller denied")
        }
        return uid
    }

    private val binder = object : IActivityHostTaskRegistry.Stub() {
        override fun getProtocolVersion(): Int {
            authorizedUid()
            check(configurationAvailable) { "Task configuration ABI unavailable" }
            return ActivityHostContract.VERSION
        }

        override fun registerLaunch(
            cookie: IBinder?, lifetime: IBinder?, component: String?,
            displayId: Int, dpi: Int, bounds: Rect?
        ) {
            val uid = authorizedUid()
            check(configurationAvailable) { "Task configuration ABI unavailable" }
            requireNotNull(cookie); requireNotNull(lifetime); requireNotNull(bounds)
            require(cookie.isBinderAlive && lifetime.isBinderAlive) { "Launch lifetime dead" }
            val name = ComponentName.unflattenFromString(requireNotNull(component))
                ?: throw IllegalArgumentException("Invalid component")
            val context = context ?: error("Task registry unavailable")
            val loader = serverLoader ?: error("Task registry ABI unavailable")
            val identity = Binder.clearCallingIdentity()
            try {
                require(
                    ActivityHostTaskConfigurationAdapter.independentRearDisplay(
                        context,
                        displayId,
                        loader
                    )
                ) {
                    "Independent rear display required"
                }
                @Suppress("DEPRECATION")
                val info = context.packageManager.getActivityInfo(name, 0)
                require(info.enabled && info.applicationInfo.enabled && info.exported && info.targetActivity == null) {
                    "Component unavailable"
                }
                require(
                    ComponentName(
                        info.packageName,
                        info.name
                    ) == name
                ) { "Component resolution mismatch" }
                val display =
                    context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)
                        ?: throw IllegalArgumentException("Display unavailable")
                val size = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(size)
                require(
                    bounds.left >= 0 && bounds.top >= 0 && !bounds.isEmpty &&
                            bounds.right <= size.x && bounds.bottom <= size.y
                ) { "Bounds outside rear display" }
                val spec = ActivityHostTaskConfigurationRegistry.Spec(
                    name.flattenToString(), displayId, dpi,
                    bounds.left, bounds.top, bounds.right, bounds.bottom
                )
                synchronized(deaths) {
                    check(configurationAvailable && active) { "Task configuration ABI unavailable" }
                    var linked = false
                    if (!deaths.containsKey(lifetime)) {
                        val recipient = IBinder.DeathRecipient { releaseLifetime(lifetime) }
                        lifetime.linkToDeath(recipient, 0)
                        deaths[lifetime] = recipient
                        linked = true
                    }
                    try {
                        ledger.register(cookie, lifetime, uid, spec)
                        if (!lifetime.isBinderAlive) {
                            releaseLifetime(lifetime)
                            error("Launch lifetime dead")
                        }
                    } catch (failure: Throwable) {
                        if (linked && !ledger.hasLifetime(lifetime)) unlink(lifetime)
                        throw failure
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }

        override fun isLaunchConfigured(cookie: IBinder?): Boolean {
            val uid = authorizedUid()
            requireNotNull(cookie)
            return synchronized(deaths) {
                check(configurationAvailable && active) { "Task configuration ABI unavailable" }
                ledger.isConfigured(cookie, uid)
            }
        }

        override fun unregisterLaunch(cookie: IBinder?) {
            val uid = authorizedUid()
            requireNotNull(cookie)
            val removed = synchronized(deaths) {
                ledger.unregister(cookie, uid).also {
                    deaths.keys.toList().filterNot(ledger::hasLifetime).forEach(::unlink)
                }
            }
            ActivityHostTaskConfigurationAdapter.restore(removed) { warnOnce("release_recompute") }
        }
    }

    // Bootstrap discovery confers no authorization: every Binder business operation validates UID.
    private val bootstrap = HookHostBridgeBootstrapRegistry(
        ActivityHostContract.REGISTRY_ACTION,
        binderProvider = { binder.takeIf { active && configurationAvailable } },
        logger = { warnOnce("bootstrap") })

    private fun unlink(lifetime: IBinder) {
        deaths.remove(lifetime)?.let { runCatching { lifetime.unlinkToDeath(it, 0) } }
    }

    private fun releaseLifetime(lifetime: IBinder) {
        val removed = synchronized(deaths) {
            ledger.lifetimeDied(lifetime).also { unlink(lifetime) }
        }
        ActivityHostTaskConfigurationAdapter.restore(removed) { warnOnce("death_recompute") }
    }

    private fun invalidateConfiguration() {
        configurationAvailable = false
        warnOnce("runtime_derivation_failed_density_capability_disabled")
        val removed = synchronized(deaths) {
            ledger.clear().also { deaths.keys.toList().forEach(::unlink) }
        }
        ActivityHostTaskConfigurationAdapter.restore(removed) { warnOnce("failure_recompute") }
    }

    private fun attach(service: Any?) {
        if (!active || service == null) return
        val realContext = ActivityHostTaskConfigurationAdapter.context(service) ?: return
        context = realContext
        if (!bootstrap.register(realContext)) warnOnce("receiver_registration")
    }

    private fun spec(record: Any): ActivityHostTaskConfigurationRegistry.Spec? {
        if (!active || !configurationAvailable) return null
        val adapter = ActivityHostTaskConfigurationAdapter
        val task = adapter.task(record) ?: return null
        val component = adapter.component(record) ?: return null
        val display = adapter.displayId(record) ?: return null
        if (display != 1) return null
        return ledger.resolve(task, adapter.cookies(record, task), component, display)
    }

    override fun onHotReloadPreflight(): Boolean =
        ledger.isEmpty() && bootstrap.isRegistrationConsistent()

    override fun onHotReloadQuiesce() {
        active = false
        check(bootstrap.unregister()) { "Task registry receiver unregistration failed" }
        val removed = synchronized(deaths) {
            ledger.clear().also { deaths.keys.toList().forEach(::unlink) }
        }
        ActivityHostTaskConfigurationAdapter.restore(removed) { warnOnce("reload_recompute") }
        if (current === this) current = null
        context = null
    }

    override fun PackageScope.onHook() {
        loadSystem {
            val recordClass =
                runCatching { "com.android.server.wm.ActivityRecord".toClass() }.getOrNull()
                    ?: return@loadSystem warnOnce("activity_record")
            serverLoader = recordClass.classLoader
            if (!ActivityHostTaskConfigurationAdapter.supports(recordClass)) {
                warnOnce("record_configuration_abi")
                return@loadSystem
            }
            runCatching {
                recordClass.resolve().firstMethod {
                    name = "resolveOverrideConfiguration"
                    parameterCount = 1
                }.hook {
                    hotReloadPolicy = HotReloadPolicy.KEEP
                    after {
                        val record = instance ?: return@after
                        val configuration = spec(record) ?: return@after
                        if (configuration.dpi == 0) {
                            ActivityHostTaskConfigurationAdapter.task(record)
                                ?.let(ledger::markConfigured)
                            return@after
                        }
                        val parent = args(0).value as? Configuration
                        val resolved = ActivityHostTaskConfigurationAdapter.resolved(record)
                        val applied = parent != null && resolved != null && runCatching {
                            ActivityHostTaskConfigurationAdapter.apply(
                                parent,
                                resolved,
                                configuration.dpi
                            )
                        }.getOrDefault(false)
                        if (applied) {
                            ActivityHostTaskConfigurationAdapter.task(record)
                                ?.let(ledger::markConfigured)
                        } else {
                            invalidateConfiguration()
                        }
                    }
                }
                configurationAvailable = true
                current = this@ActivityHostTaskConfigurationHook
                // This implementation deliberately does not advertise a Task requested override.
                YLog.debug("ActivityHost density capability: final ActivityRecord override only")
            }.onFailure { warnOnce("configuration_hook") }
            if (!configurationAvailable) return@loadSystem
            val serviceClass =
                runCatching { "com.android.server.wm.ActivityTaskManagerService".toClass() }
                    .getOrNull() ?: return@loadSystem warnOnce("atms")
            listOf("onSystemReady", "systemReady", "initialize", "init").forEach { entry ->
                runCatching {
                    serviceClass.resolve().firstMethod { name = entry }.hook {
                        hotReloadPolicy = HotReloadPolicy.KEEP
                        after { attach(instance) }
                    }
                }
            }
            // Local service Binder is the real ATMS in system_server; supports late load/reload.
            attach(ActivityHostTaskConfigurationAdapter.service(serviceClass.classLoader))
        }
    }

    companion object {
        @Volatile
        private var current: ActivityHostTaskConfigurationHook? = null

        /** For CustomBoundsCompat: skips only a registered task, never a matching package alone. */
        fun isOwnedTask(activityRecord: Any): Boolean = current?.spec(activityRecord) != null
    }
}
