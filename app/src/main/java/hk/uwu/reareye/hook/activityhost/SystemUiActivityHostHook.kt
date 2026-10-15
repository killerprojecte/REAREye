package hk.uwu.reareye.hook.activityhost

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.SurfaceControl
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeBootstrapRegistry
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.internal.activityhost.IActivityHostBroker
import hk.uwu.reareye.internal.activityhost.IActivityHostCallback
import hk.uwu.reareye.internal.activityhost.IActivityHostSession
import hk.uwu.reareye.internal.activityhost.IActivityHostTaskRegistry
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Privileged broker; XML never receives a TaskOrganizer, task token or trusted-overlay API. */
class SystemUiActivityHostHook : RoxyHooker() {
    private var adapter: ShellActivityHostAdapter? = null
    private var broker: ActivityHostBroker? = null
    private val bootstrap = HookHostBridgeBootstrapRegistry(
        ActivityHostContract.BROKER_ACTION,
        binderProvider = { broker }, logger = { YLog.debug("ActivityHost: $it") })

    override fun onHotReloadPreflight(): Boolean = adapter?.hasHosts() != true
    override fun onHotReloadQuiesce() {
        check(bootstrap.unregister()) { "ActivityHost bootstrap teardown failed" }
        broker?.shutdown()
        broker = null
        adapter = null
    }

    override fun PackageScope.onHook() {
        loadApp(ActivityHostContract.SYSTEM_UI_PACKAGE) {
            runCatching {
                val installedAdapter = ShellActivityHostAdapter(appClassLoader)
                with(installedAdapter) { install() }
                adapter = installedAdapter
                lifecycle {
                    onAttach(replay = true) {
                        val context = hookAppContext ?: this.context
                        if (broker == null) broker = ActivityHostBroker(context, installedAdapter)
                        check(bootstrap.register(context)) { "ActivityHost broker discovery unavailable" }
                    }
                }
                runtime.onClose {
                    bootstrap.unregister()
                    broker?.shutdown()
                    broker = null
                }
                YLog.info("ActivityHost Shell ABI installed; real-device validation still required")
            }.onFailure { YLog.warn("ActivityHost Shell ABI unavailable", it) }
        }
    }
}

internal class ActivityHostBroker(
    private val context: Context,
    private val adapter: ShellActivityHostAdapter,
) : IActivityHostBroker.Stub() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "REAREye-ActivityHost-broker").apply { isDaemon = true }
    }
    private val sessions = mutableSetOf<Session>()
    private val closed = AtomicBoolean()

    private fun enforceHost(): Int {
        check(!closed.get()) { "ActivityHost broker closed" }
        val uid = getCallingUid()
        val expected =
            context.packageManager.getApplicationInfo(ActivityHostContract.HOST_PACKAGE, 0).uid
        if (uid != expected || uid / 100000 != Process.myUid() / 100000) {
            throw SecurityException("ActivityHost caller is not the rear-screen host")
        }
        return uid
    }

    override fun getProtocolVersion(): Int {
        enforceHost(); return ActivityHostContract.VERSION
    }

    override fun openSession(
        displayId: Int,
        generation: Long,
        callback: IActivityHostCallback
    ): IActivityHostSession {
        val uid = enforceHost()
        require(generation > 0) { "Invalid ActivityHost generation" }
        require(displayId == 1) { "ActivityHost only supports the independent rear display" }
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: error("Rear display is unavailable")
        check(
            ActivityHostReflection.invoke(
                display,
                "isTrusted"
            ) == true
        ) { "Rear display is not trusted" }
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Device is locked" }
        check(adapter.available(context)) { "Live Shell TaskViewFactory unavailable" }
        return synchronized(sessions) {
            check(sessions.size < ActivityHostContract.MAX_SESSIONS) { "ActivityHost session quota reached" }
            Session(uid, displayId, generation, callback).also { session ->
                callback.asBinder().linkToDeath(session.death, 0)
                sessions += session
                main.post {
                    if (!session.finished.get()) {
                        session.startTimeout()
                        runCatching {
                            adapter.create(context.createDisplayContext(display), session) { view ->
                                if (session.finished.get()) adapter.release(view, false)
                                else session.carrierReady(view)
                            }
                        }.onFailure { session.dispose("carrier_create_failed:${it.javaClass.simpleName}") }
                    }
                }
            }
        }
    }

    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(sessions) { sessions.toList() }.forEach { it.dispose("broker_shutdown") }
        worker.shutdown()
    }

    internal inner class Session(
        private val ownerUid: Int,
        private val displayId: Int,
        private val generation: Long,
        private val callback: IActivityHostCallback,
    ) : IActivityHostSession.Stub(), ShellActivityHostOwner {
        val finished = AtomicBoolean()
        val death = IBinder.DeathRecipient { dispose("client_died") }
        private val launchGuard = ActivityHostLaunchGuard<IBinder>()
        @Volatile
        override var surface: SurfaceControl? = null
            private set
        override val bounds: Rect
            get() = reconcile.desiredLayout()?.let(::Rect) ?: Rect()
        @Volatile
        private var carrier: Any? = null
        @Volatile
        private var initialized = false
        @Volatile
        private var wantedVisible = true
        @Volatile
        private var component: ComponentName? = null
        @Volatile
        private var dpi = 0
        @Volatile
        private var pendingIntent: PendingIntent? = null
        @Volatile
        private var registry: IActivityHostTaskRegistry? = null
        private val cookie: IBinder?
            get() = launchGuard.cookie()
        @Volatile
        private var ownedTask = false
        @Volatile
        private var configurationConfirmed = false
        private val configurationCheckQueued = AtomicBoolean()
        @Volatile
        private var launchQueued = false
        @Volatile
        private var taskBounds = Rect()
        @Volatile
        private var existingTaskIds: Set<Int> = emptySet()
        private var acknowledgedSequence = 0L
        private var acknowledgedSurfaceEpoch = 0L
        private val reconcile = ActivityHostReconciler<SurfaceControl, Rect>(
            post = { action -> main.post { action() } },
            attach = { value ->
                surface = value; adapter.attachSurface(
                requireNotNull(carrier),
                value
            )
            },
            detach = { carrier?.let(adapter::detachSurface) },
            release = { value -> adapter.retireSurface(carrier, value) },
            applyLayout = { value -> adapter.updateBounds(requireNotNull(carrier), value) },
            applyVisibility = { value -> adapter.setVisible(requireNotNull(carrier), value) },
            readyToLaunch = { maybeLaunch() },
            failure = { phase, error -> dispose("${phase}_failed:${error.javaClass.simpleName}") },
            afterApply = { applied ->
                adapter.afterSurfaceTransactions(
                    requireNotNull(carrier),
                    applied
                )
            },
            settled = { acknowledgeIfAligned() },
        )
        private var timeout: Runnable? = null

        fun startTimeout() {
            if (finished.get()) return
            timeout?.let(main::removeCallbacks)
            val check = object : Runnable {
                override fun run() {
                    if (finished.get()) return
                    val phase = when {
                        carrier == null -> "carrier"
                        surface == null -> "surface"
                        !initialized -> "initialized"
                        component == null -> return // No user-authorized launch request yet.
                        pendingIntent == null -> "launch_preflight"
                        !launchQueued -> "launch_dispatch"
                        !ownedTask -> "task_created"
                        !configurationConfirmed -> "task_configuration"
                        reconcile.appliedSnapshot() == null -> "layout_apply"
                        taskBounds != bounds -> "layout_alignment"
                        else -> return
                    }
                    dispose("timeout:$phase")
                }
            }
            timeout = check
            main.postDelayed(check, ActivityHostContract.STARTUP_PHASE_TIMEOUT_MS)
        }

        private fun enforceOwner() {
            if (getCallingUid() != ownerUid) throw SecurityException("ActivityHost session belongs to another UID")
            check(!finished.get()) { "ActivityHost session closed" }
        }

        private fun validateLayout(screenBounds: Rect, visibleBounds: Rect, sequence: Long) {
            require(sequence > 0) { "Invalid layout sequence" }
            require(
                !screenBounds.isEmpty && (screenBounds.right.toLong() - screenBounds.left.toLong()) in 2..ActivityHostContract.MAX_DIMENSION.toLong() &&
                        (screenBounds.bottom.toLong() - screenBounds.top.toLong()) in 2..ActivityHostContract.MAX_DIMENSION.toLong()
            ) { "Invalid task bounds" }
            require(!visibleBounds.isEmpty && screenBounds.contains(visibleBounds)) { "Invalid visible bounds" }
        }

        override fun setSurface(
            surface: SurfaceControl,
            screenBounds: Rect,
            visibleBounds: Rect,
            sequence: Long
        ) {
            enforceOwner()
            require(surface.isValid) { "Invalid ActivityHost Surface" }
            // A newer geometry message must not invalidate an independently queued surface.
            validateLayout(screenBounds, visibleBounds, sequence)
            reconcile.surface(surface, Rect(screenBounds), sequence)
        }

        override fun updateLayout(screenBounds: Rect, visibleBounds: Rect, sequence: Long) {
            enforceOwner()
            validateLayout(screenBounds, visibleBounds, sequence)
            reconcile.layout(Rect(screenBounds), sequence)
        }

        fun carrierReady(view: Any) {
            if (finished.get()) {
                adapter.release(view, false); return
            }
            carrier = view
            reconcile.carrierReady()
        }

        override fun initialized() {
            if (finished.get()) return
            initialized = true
            reconcile.initialized()
        }

        override fun launch(component: String, dpi: Int) {
            enforceOwner()
            check(this.component == null) { "ActivityHost launch is one-shot; create a new session to change DPI" }
            val target =
                requireNotNull(ComponentName.unflattenFromString(component)) { "Invalid Activity component" }
            require(dpi == 0 || dpi in ActivityHostContract.MIN_DPI..ActivityHostContract.MAX_DPI) { "Invalid DPI" }
            synchronized(this) {
                check(this.component == null) { "Launch already requested" }
                this.component = target
                this.dpi = dpi
            }
            main.post {
                timeout?.let(main::removeCallbacks)
                startTimeout()
            }
            worker.execute {
                runCatching {
                    val info = context.packageManager.getActivityInfo(target, 0)
                    require(info.exported && info.enabled && info.applicationInfo.enabled && info.permission.isNullOrEmpty()) {
                        "ActivityHost requires an enabled exported, unprotected Activity entry"
                    }
                    check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Device is locked" }
                    val running =
                        context.getSystemService(ActivityManager::class.java).getRunningTasks(100)
                    existingTaskIds = running.map { it.taskId }.toSet()
                    check(running.none { it.baseActivity?.packageName == target.packageName || it.topActivity?.packageName == target.packageName }) {
                        "Target application already has a task; ActivityHost will not take ownership of it"
                    }
                    val connected = ActivityHostRegistryClient.connect(context)
                    if (finished.get()) return@execute
                    registry = connected
                    val intent = Intent().setComponent(target)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    val creatorOptions = ActivityOptions.makeBasic().apply {
                        setPendingIntentCreatorBackgroundActivityStartMode(
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        )
                    }
                    val pending = PendingIntent.getActivity(
                        context, System.identityHashCode(this), intent,
                        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        creatorOptions.toBundle()
                    )
                    synchronized(this) {
                        if (finished.get()) {
                            pending.cancel(); return@execute
                        }
                        pendingIntent = pending
                    }
                    reconcile.retryLaunch()
                }.onFailure { dispose("launch_preflight_failed:${it.javaClass.simpleName}") }
            }
        }

        private fun maybeLaunch() {
            val view = carrier ?: return
            val pending = pendingIntent ?: return
            if (!initialized || bounds.isEmpty || launchQueued || finished.get()) return
            launchQueued = true
            val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId).apply {
                setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                )
            }
            runCatching { adapter.launch(view, pending, options) }
                .onFailure { dispose("task_launch_failed") }
        }

        override fun prepareLaunch(options: ActivityOptions) {
            check(!finished.get()) { "Session closed before launch" }
            val launchCookie = ActivityHostReflection.invoke(options, "getLaunchCookie") as? IBinder
                ?: error("Native Shell did not supply a launch cookie")
            val connected = requireNotNull(registry)
            val launchBounds = Rect(
                ActivityHostReflection.invoke(options, "getLaunchBounds") as? Rect
                    ?: error("Native Shell did not supply launch bounds")
            )
            launchGuard.register(
                launchCookie,
                register = {
                    connected.registerLaunch(
                        launchCookie, asBinder(),
                        requireNotNull(component).flattenToString(), displayId, dpi, launchBounds
                    )
                },
                unregister = { connected.unregisterLaunch(it) })
        }

        override fun dispatchLaunch(submit: () -> Unit) {
            launchGuard.dispatch(submit)
        }

        override fun taskChanged(info: ActivityManager.RunningTaskInfo) {
            if (Looper.myLooper() != main.looper) {
                main.post { taskChanged(info) }
                return
            }
            if (finished.get()) return
            val actualDisplayId = runCatching {
                ActivityHostReflection.field(info.javaClass, "displayId").getInt(info)
            }.getOrDefault(-1)
            if (actualDisplayId != displayId || info.taskId in existingTaskIds) {
                dispose("task_ownership_or_display_mismatch"); return
            }
            val firstTask = !ownedTask
            if (firstTask) {
                val cookies = runCatching {
                    ActivityHostReflection.field(info.javaClass, "launchCookies")
                        .get(info) as? List<*>
                }.getOrNull()
                val launchCookie = cookie
                if (launchCookie == null || cookies?.contains(launchCookie) != true) {
                    dispose("unverified_task_cookie"); return
                }
                ownedTask = true
                if (configurationCheckQueued.compareAndSet(false, true)) worker.execute {
                    runCatching {
                        check(registry?.isLaunchConfigured(launchCookie) == true) {
                            "First Activity configuration was not confirmed"
                        }
                    }.onSuccess {
                        main.post {
                            if (finished.get()) return@post
                            configurationConfirmed = true
                            runCatching {
                                callback.onTaskCreated(
                                    generation,
                                    info.taskId,
                                    actualDisplayId
                                )
                            }
                                .onFailure { dispose("client_callback_failed") }
                            acknowledgeIfAligned()
                        }
                    }.onFailure { dispose("task_configuration_not_confirmed") }
                }
            }
            taskBounds = runCatching {
                val configuration = requireNotNull(
                    ActivityHostReflection.field(info.javaClass, "configuration").get(info)
                )
                val window =
                    ActivityHostReflection.field(configuration.javaClass, "windowConfiguration")
                        .get(configuration)
                Rect(ActivityHostReflection.invoke(window, "getBounds") as Rect)
            }.getOrDefault(Rect())
            // Native task creation can reset bounds/visibility; replay the latest desired state.
            if (firstTask) reconcile.taskCreated()
            acknowledgeIfAligned()
        }

        private fun acknowledgeIfAligned() {
            if (finished.get() || !ownedTask || !configurationConfirmed) return
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                dispose("device_locked"); return
            }
            runCatching {
                reconcile.withAppliedSnapshot { snapshot ->
                    if (finished.get() || taskBounds != snapshot.geometry || carrier == null) return@withAppliedSnapshot
                    if (snapshot.sequence <= acknowledgedSequence &&
                        snapshot.surfaceEpoch == acknowledgedSurfaceEpoch
                    ) return@withAppliedSnapshot
                    check(adapter.isSurfaceAttached(requireNotNull(carrier), snapshot.surface)) {
                        "Native TaskView did not retain the applied host surface"
                    }
                    callback.onLayoutApplied(generation, snapshot.sequence, Rect(snapshot.geometry))
                    acknowledgedSequence = snapshot.sequence
                    acknowledgedSurfaceEpoch = snapshot.surfaceEpoch
                    timeout?.let(main::removeCallbacks)
                }
            }.onFailure { dispose("layout_ack_failed:${it.javaClass.simpleName}") }
        }

        override fun taskClosed(reason: String) {
            // Do not take the reconciler monitor from a synchronous native dispatch callback.
            if (Looper.myLooper() == main.looper) dispose(reason)
            else main.post { dispose(reason) }
        }

        override fun setVisible(visible: Boolean) {
            enforceOwner()
            wantedVisible = visible
            reconcile.visible(wantedVisible)
        }

        override fun moveToFullscreen() {
            enforceOwner()
            // The OEM method only queues a transition. Releasing immediately destroys the
            // controller/token used by that transition; no verified detach completion ABI exists.
            throw UnsupportedOperationException("背屏全屏交接尚未验证；请结束嵌入后使用主屏打开")
        }

        override fun close() {
            enforceOwner(); dispose("client_closed")
        }

        fun dispose(reason: String) {
            val cancellation = launchGuard.cancel() ?: return
            finished.set(true)
            reconcile.cancel()
            synchronized(sessions) { sessions.remove(this) }
            runCatching { callback.asBinder().unlinkToDeath(death, 0) }
            runCatching { callback.onClosed(generation, reason) }
            synchronized(this) { pendingIntent?.cancel() }
            timeout?.let(main::removeCallbacks)
            val launchCookie = cancellation.cookie
            if (launchCookie != null && !worker.isShutdown) worker.execute {
                runCatching {
                    registry?.unregisterLaunch(
                        launchCookie
                    )
                }
            }
            main.post {
                carrier?.let { runCatching { adapter.release(it, ownedTask) } }
                reconcile.close()
                carrier = null
                surface = null
            }
        }
    }
}
