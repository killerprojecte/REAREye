package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceControl
import hk.uwu.reareye.hook.activityhost.ActivityHostContract
import hk.uwu.reareye.internal.activityhost.IActivityHostBroker
import hk.uwu.reareye.internal.activityhost.IActivityHostCallback
import hk.uwu.reareye.internal.activityhost.IActivityHostSession
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One explicit click = one generation. No retry or sensitive launch replay. */
internal class ActivityHostClient(private val context: Context, private val events: Events) {
    interface Events {
        fun onOpened(generation: Long)
        fun onLayoutApplied(generation: Long, sequence: Long, bounds: Rect)
        fun onTaskCreated(generation: Long, taskId: Int, displayId: Int)
        fun onClosed(generation: Long, reason: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val serial = Executors.newSingleThreadExecutor { task ->
        Thread(
            task,
            "RearActivityHost"
        ).apply { isDaemon = true }
    }
    private val epoch = AtomicLong()
    @Volatile
    private var disposed = false
    @Volatile
    private var session: IActivityHostSession? = null
    private var pendingLaunch: Pair<String, Int>? =
        null // executor-owned; consumed once after setSurface
    private var brokerBinder: IBinder? = null
    private var death: IBinder.DeathRecipient? = null
    private var accepted = false

    @Volatile
    private var taskObservedGeneration = 0L

    fun open(displayId: Int, component: String, dpi: Int): Long {
        check(!disposed)
        val token = epoch.incrementAndGet()
        serial.execute {
            release()
            if (!current(token)) return@execute
            accepted = false
            try {
                val expectedUid = context.packageManager.getApplicationInfo(
                    ActivityHostContract.SYSTEM_UI_PACKAGE,
                    0
                ).uid
                val bootstrap = object : IHookHostBridgeBootstrap.Stub() {
                    override fun onBinderReady(binder: IBinder?) {
                        // Capture before hopping to the executor; Binder identity does not cross threads.
                        if (getCallingUid() != expectedUid || binder == null || !current(
                                token
                            )
                        ) return
                        serial.execute {
                            if (!current(token) || accepted) return@execute
                            accepted = true
                            try {
                                val broker = IActivityHostBroker.Stub.asInterface(binder)
                                check(broker.protocolVersion == ActivityHostContract.VERSION) { "ActivityHost协议版本不兼容" }
                                val watcher =
                                    IBinder.DeathRecipient { fail(token, "SystemUI连接已断开") }
                                binder.linkToDeath(watcher, 0)
                                brokerBinder = binder
                                death = watcher
                                val callback = object : IActivityHostCallback.Stub() {
                                    private fun trusted(generation: Long) =
                                        getCallingUid() == expectedUid && generation == token && current(
                                            token
                                        )

                                    override fun onLayoutApplied(
                                        generation: Long,
                                        sequence: Long,
                                        bounds: Rect?
                                    ) {
                                        if (trusted(generation) && bounds != null) {
                                            val copy = Rect(bounds)
                                            post(token) {
                                                events.onLayoutApplied(
                                                    token,
                                                    sequence,
                                                    copy
                                                )
                                            }
                                        }
                                    }

                                    override fun onTaskCreated(
                                        generation: Long,
                                        taskId: Int,
                                        displayId: Int
                                    ) {
                                        if (trusted(generation)) post(token) {
                                            taskObservedGeneration = token
                                            events.onTaskCreated(
                                                token,
                                                taskId,
                                                displayId
                                            )
                                        }
                                    }

                                    override fun onClosed(generation: Long, reason: String?) {
                                        if (trusted(generation)) fail(token, reason ?: "任务已关闭")
                                    }
                                }
                                val opened = broker.openSession(displayId, token, callback)
                                    ?: error("SystemUI拒绝ActivityHost会话")
                                if (!current(token)) {
                                    opened.close(); release(); return@execute
                                }
                                session = opened
                                // The component/density is the immutable snapshot captured by this click.
                                pendingLaunch = component to dpi
                                post(token) { events.onOpened(token) }
                                main.postDelayed({
                                    if (current(token) && taskObservedGeneration != token) {
                                        fail(
                                            token,
                                            "ActivityHost任务未出现：Surface初始化或应用启动超时"
                                        )
                                    }
                                    // Broker allows 15s for the carrier, then a fresh 15s
                                    // for authorized launch/configuration. Leave callback slack;
                                    // the broker's phase-specific error should arrive first.
                                }, ActivityHostContract.CLIENT_TASK_TIMEOUT_MS)
                            } catch (error: Exception) {
                                fail(token, error.message ?: "ActivityHost ABI不可用")
                            }
                        }
                    }
                }
                context.sendBroadcast(
                    Intent(ActivityHostContract.BROKER_ACTION)
                        .setPackage(ActivityHostContract.SYSTEM_UI_PACKAGE)
                        .putExtra(
                            "bundle",
                            Bundle().apply { putBinder("binder", bootstrap.asBinder()) })
                )
                main.postDelayed({
                    if (current(token) && session == null) fail(
                        token,
                        "SystemUI ActivityHost响应超时"
                    )
                }, 5000L)
            } catch (error: Exception) {
                fail(token, error.message ?: "ActivityHost发现失败")
            }
        }
        return token
    }

    private data class Layout(
        val token: Long,
        val surface: SurfaceControl?,
        val bounds: Rect,
        val visible: Rect,
        val sequence: Long
    )

    private val layoutLock = Any()
    private var pendingLayout: Layout? = null
    private var layoutQueued = false
    fun layout(token: Long, surface: SurfaceControl?, bounds: Rect, visible: Rect, sequence: Long) {
        if (!current(token)) return
        synchronized(layoutLock) {
            // Retain a not-yet-installed surface while replacing only the latest geometry.
            val carrier = surface ?: pendingLayout?.takeIf { it.token == token }?.surface
            pendingLayout = Layout(token, carrier, Rect(bounds), Rect(visible), sequence)
            if (layoutQueued) return
            layoutQueued = true
        }
        serial.execute {
            while (true) {
                val next = synchronized(layoutLock) {
                    val value = pendingLayout
                    pendingLayout = null
                    if (value == null) layoutQueued = false
                    value
                } ?: break
                if (!current(next.token)) continue
                try {
                    session?.let { remote ->
                        if (next.surface != null) {
                            remote.setSurface(
                                next.surface,
                                next.bounds,
                                next.visible,
                                next.sequence
                            )
                            // A synchronous Binder call can return after close invalidated this generation.
                            if (!current(next.token)) {
                                pendingLaunch = null
                                return@let
                            }
                            val launch = pendingLaunch
                            pendingLaunch = null
                            if (launch != null && current(next.token)) remote.launch(
                                launch.first,
                                launch.second
                            )
                        } else remote.updateLayout(next.bounds, next.visible, next.sequence)
                    }
                } catch (error: Exception) {
                    fail(next.token, error.message ?: "ActivityHost布局失败")
                }
            }
        }
    }

    fun visible(token: Long, visible: Boolean) = command(token) { it.setVisible(visible) }
    fun fullscreen(token: Long) = command(token) { it.moveToFullscreen() }
    private fun command(token: Long, block: (IActivityHostSession) -> Unit) {
        if (disposed) return
        serial.execute {
            if (!current(token)) return@execute
            try {
                session?.let(block)
            } catch (error: Exception) {
                fail(token, error.message ?: "ActivityHost调用失败")
            }
        }
    }

    fun close() {
        epoch.incrementAndGet() // close stale callback/death races before the queued release
        if (!disposed) serial.execute { release() }
    }

    fun dispose() {
        if (disposed) return
        close()
        disposed = true
        serial.shutdown()
    }

    private fun current(token: Long) = !disposed && epoch.get() == token
    private fun post(token: Long, block: () -> Unit) {
        main.post { if (current(token)) block() }
    }

    private fun fail(token: Long, reason: String) {
        main.post {
            if (!current(token)) return@post
            close()
            events.onClosed(token, reason)
        }
    }

    private fun release() {
        val old = session
        session = null
        pendingLaunch = null
        runCatching { old?.close() }
        death?.let { watcher -> runCatching { brokerBinder?.unlinkToDeath(watcher, 0) } }
        brokerBinder = null
        death = null
    }
}

/** Only exact current-generation/current-layout acknowledgement enables task input. */
internal class ActivityHostInputGate {
    var generation = 0L
    var sequence = 0L
    private var expected: Rect? = null
    private var acknowledged = false
    fun submit(generation: Long, sequence: Long, bounds: Rect) {
        this.generation = generation; this.sequence = sequence
        expected = Rect(bounds); acknowledged = false
    }

    fun acknowledge(generation: Long, sequence: Long, bounds: Rect): Boolean {
        if (this.generation != generation || this.sequence != sequence || expected != bounds) return false
        acknowledged = true
        return true
    }

    fun allows(active: Boolean, stable: Boolean, navigating: Boolean): Boolean =
        acknowledged && active && stable && !navigating

    fun revoke() {
        acknowledged = false
    }
}
