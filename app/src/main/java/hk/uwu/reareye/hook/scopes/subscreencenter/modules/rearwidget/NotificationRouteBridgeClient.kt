package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeClient
import hk.uwu.reareye.internal.notification.INotificationRouteBridgeService
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal const val NOTIFICATION_ROUTE_BIND_TIMEOUT_MS = 0L

private enum class NotificationRouteDrainResult {
    EMPTY,
    DISCONNECTED,
}

private fun <T> drainNotificationRouteQueue(
    next: () -> T?,
    deliver: (T) -> Boolean?,
    remove: (T) -> Unit,
): NotificationRouteDrainResult {
    while (true) {
        val pending = next() ?: return NotificationRouteDrainResult.EMPTY
        deliver(pending) ?: return NotificationRouteDrainResult.DISCONNECTED
        remove(pending)
    }
}

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
    }

    override val requestAction: String =
        NotificationRouteBridgeContract.Action.REQUEST_BINDER

    override val serviceLabel: String = "Notification route bridge"

    private val pendingDispatches = ArrayDeque<PendingDispatch>()
    private val queueLock = Any()
    private val drainScheduled = AtomicBoolean(false)
    private val enqueueVersion = AtomicLong(0L)
    private val drainExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "REAREye-NotificationRoute").apply { isDaemon = true }
    }

    override fun asRemoteInterface(binder: IBinder?): INotificationRouteBridgeService? {
        return INotificationRouteBridgeService.Stub.asInterface(binder)
    }

    override fun onRemoteConnected(remote: INotificationRouteBridgeService) {
        scheduleDrain()
    }

    fun bind(
        context: Context,
        onConnected: (() -> Unit)? = null,
        onClosed: ((String) -> Unit)? = null,
        timeoutMs: Long = NOTIFICATION_ROUTE_BIND_TIMEOUT_MS,
    ): Boolean {
        return bindToHost(
            context = context,
            onConnected = onConnected,
            onClosed = onClosed,
            timeoutMs = timeoutMs,
        )
    }

    fun dispatch(subchannel: String, payload: Bundle = Bundle()): Boolean {
        val normalizedSubchannel = subchannel.trim()
        if (normalizedSubchannel.isBlank()) return false

        enqueuePendingDispatch(
            subchannel = normalizedSubchannel,
            payload = Bundle(payload),
        )
        scheduleDrain()
        return true
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
            enqueueVersion.incrementAndGet()
        }
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        drainExecutor.execute {
            val observedVersion = enqueueVersion.get()
            var result = NotificationRouteDrainResult.DISCONNECTED
            try {
                result = drainNotificationRouteQueue(
                    next = {
                        synchronized(queueLock) {
                            pruneExpiredDispatchesLocked()
                            pendingDispatches.firstOrNull()
                        }
                    },
                    deliver = { pending ->
                        callRemote { remote ->
                            remote.dispatch(pending.subchannel, Bundle(pending.payload))
                        }
                    },
                    remove = { pending ->
                        synchronized(queueLock) {
                            if (pendingDispatches.firstOrNull() === pending) {
                                pendingDispatches.removeFirst()
                            } else {
                                pendingDispatches.remove(pending)
                            }
                        }
                    },
                )
                if (result == NotificationRouteDrainResult.DISCONNECTED) {
                    currentContext()?.let {
                        bind(it, timeoutMs = NOTIFICATION_ROUTE_BIND_TIMEOUT_MS)
                    } ?: requestRebind()
                }
            } finally {
                drainScheduled.set(false)
                val pendingWork = synchronized(queueLock) {
                    pruneExpiredDispatchesLocked()
                    pendingDispatches.isNotEmpty()
                }
                val receivedNewWork = enqueueVersion.get() != observedVersion
                if (pendingWork && (isConnected() || receivedNewWork)) {
                    scheduleDrain()
                }
            }
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
