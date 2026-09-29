package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeClient
import hk.uwu.reareye.internal.notification.INotificationRouteBridgeService
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class NotificationRouteBridgeClient :
    HookHostBridgeClient<INotificationRouteBridgeService>(
        hostPackage = NotificationRouteBridgeContract.HOOK_HOST_PACKAGE,
    ) {
    private data class PendingDispatch(
        val subchannel: String,
        val payload: Bundle,
        val createdAt: Long,
    )

    companion object {
        private const val MAX_PENDING_DISPATCHES = 64
        private const val PENDING_DISPATCH_TTL_MS = 15_000L
        private const val BIND_TIMEOUT_MS = 900L
    }

    override val requestAction: String =
        NotificationRouteBridgeContract.Action.REQUEST_BINDER

    override val serviceLabel: String = "Notification route bridge"

    private val pendingDispatches = ArrayDeque<PendingDispatch>()
    private val queueLock = Any()
    private val bindInFlight = AtomicBoolean(false)
    private val drainInFlight = AtomicBoolean(false)
    private val bridgeExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "REAREye-NotificationBridge").apply { isDaemon = true }
    }

    @Volatile
    private var bindingContext: Context? = null

    override fun asRemoteInterface(binder: IBinder?): INotificationRouteBridgeService? {
        return INotificationRouteBridgeService.Stub.asInterface(binder)
    }

    override fun onRemoteConnected(remote: INotificationRouteBridgeService) {
        scheduleDrainPendingDispatches()
    }

    override fun onRemoteDisconnected(reason: String) {
        bindingContext?.let { context -> bind(context) }
    }

    override fun onUnbound() {
        synchronized(queueLock) {
            pendingDispatches.clear()
        }
        bindingContext = null
        bridgeExecutor.shutdownNow()
    }

    fun bind(
        context: Context,
        onConnected: (() -> Unit)? = null,
        onClosed: ((String) -> Unit)? = null,
        timeoutMs: Long = BIND_TIMEOUT_MS,
    ): Boolean {
        val appContext = context.applicationContext ?: context
        bindingContext = appContext
        if (isConnected()) {
            onConnected?.invoke()
            return true
        }
        scheduleBind(appContext, onConnected, onClosed, timeoutMs)
        return false
    }

    fun dispatch(subchannel: String, payload: Bundle = Bundle()): Boolean {
        val normalizedSubchannel = subchannel.trim()
        if (normalizedSubchannel.isBlank()) return false

        val payloadCopy = Bundle(payload)
        enqueuePendingDispatch(
            subchannel = normalizedSubchannel,
            payload = payloadCopy,
        )
        scheduleDrainPendingDispatches()
        if (!isConnected()) {
            bindingContext?.let { context -> bind(context) }
        }
        return true
    }

    private fun scheduleBind(
        context: Context,
        onConnected: (() -> Unit)?,
        onClosed: ((String) -> Unit)?,
        timeoutMs: Long,
    ) {
        if (!bindInFlight.compareAndSet(false, true)) return
        runCatching {
            bridgeExecutor.execute {
                try {
                    bindToHost(
                        context = context,
                        onConnected = onConnected,
                        onClosed = onClosed,
                        timeoutMs = timeoutMs.coerceAtLeast(1L),
                    )
                } finally {
                    bindInFlight.set(false)
                }
            }
        }.onFailure {
            bindInFlight.set(false)
        }
    }

    private fun scheduleDrainPendingDispatches() {
        if (!drainInFlight.compareAndSet(false, true)) return
        runCatching {
            bridgeExecutor.execute {
                var fullyDrained = false
                try {
                    fullyDrained = drainPendingDispatches()
                } finally {
                    drainInFlight.set(false)
                }
                if (fullyDrained && isConnected() && hasPendingDispatches()) {
                    scheduleDrainPendingDispatches()
                }
            }
        }.onFailure {
            drainInFlight.set(false)
        }
    }

    private fun enqueuePendingDispatch(subchannel: String, payload: Bundle) {
        synchronized(queueLock) {
            pruneExpiredDispatchesLocked()
            pendingDispatches.addLast(
                PendingDispatch(
                    subchannel = subchannel,
                    payload = payload,
                    createdAt = System.currentTimeMillis(),
                )
            )
            while (pendingDispatches.size > MAX_PENDING_DISPATCHES) {
                pendingDispatches.removeFirst()
            }
        }
    }

    private fun drainPendingDispatches(): Boolean {
        while (true) {
            val next = synchronized(queueLock) {
                pruneExpiredDispatchesLocked()
                pendingDispatches.firstOrNull()
            } ?: return true

            val delivered = callRemote { remote ->
                remote.dispatch(next.subchannel, Bundle(next.payload))
            } ?: return false
            if (!delivered) return false

            synchronized(queueLock) {
                if (pendingDispatches.firstOrNull() === next) {
                    pendingDispatches.removeFirst()
                } else {
                    pendingDispatches.remove(next)
                }
            }
        }
    }

    private fun hasPendingDispatches(): Boolean {
        return synchronized(queueLock) {
            pruneExpiredDispatchesLocked()
            pendingDispatches.isNotEmpty()
        }
    }

    private fun pruneExpiredDispatchesLocked() {
        val now = System.currentTimeMillis()
        while (pendingDispatches.isNotEmpty()) {
            val pending = pendingDispatches.firstOrNull() ?: return
            if (now - pending.createdAt <= PENDING_DISPATCH_TTL_MS) {
                return
            }
            pendingDispatches.removeFirst()
        }
    }
}
