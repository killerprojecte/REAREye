package hk.uwu.reareye.hook.hostbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private class HookHostBridgeRequestState {
    class Attempt(
        val latch: CountDownLatch = CountDownLatch(1),
    )

    data class Lease(
        val attempt: Attempt,
        val started: Boolean,
    )

    private val lock = Any()
    private var current: Attempt? = null

    fun acquire(startRequest: (Attempt) -> Boolean): Lease = synchronized(lock) {
        current?.let { return@synchronized Lease(it, started = false) }
        Attempt().also { attempt ->
            current = attempt
            if (!startRequest(attempt)) {
                current = null
                attempt.latch.countDown()
            }
        }.let { Lease(it, started = true) }
    }

    fun abandon(attempt: Attempt) {
        synchronized(lock) {
            if (current === attempt) {
                current = null
            }
        }
    }

    fun fail(attempt: Attempt) {
        synchronized(lock) {
            attempt.latch.countDown()
            if (current === attempt) {
                current = null
            }
        }
    }

    fun completeConnection(attempt: Attempt? = null) {
        synchronized(lock) {
            attempt?.latch?.countDown()
            current?.let { pending ->
                if (pending !== attempt) pending.latch.countDown()
            }
            current = null
        }
    }
}

abstract class HookHostBridgeClient<Remote : IInterface>(
    private val hostPackage: String,
) {
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remote: Remote? = null

    @Volatile
    private var remoteBinder: IBinder? = null

    @Volatile
    private var remoteDeathRecipient: IBinder.DeathRecipient? = null

    private val requestState = HookHostBridgeRequestState()

    @Volatile
    private var closedListener: ((String) -> Unit)? = null

    protected abstract val requestAction: String
    protected abstract val serviceLabel: String

    protected abstract fun asRemoteInterface(binder: IBinder?): Remote?

    protected open fun onBeforeRequest(forceSync: Boolean) {
    }

    protected open fun onRemoteConnected(remote: Remote) {
    }

    protected open fun onRemoteDisconnected(reason: String) {
    }

    fun isConnected(): Boolean = remote != null

    fun unbind() {
        clearRemote(notifyClosed = false)
    }

    protected fun bindToHost(
        context: Context,
        onConnected: (() -> Unit)? = null,
        onClosed: ((String) -> Unit)? = null,
        timeoutMs: Long = 1200L,
        retryTimeoutMs: Long = timeoutMs,
        retryWithForceSync: Boolean = false,
    ): Boolean {
        appContext = context.applicationContext
        if (onClosed != null) {
            closedListener = onClosed
        }

        remote?.let {
            onConnected?.invoke()
            return true
        }

        val connected = requestBridge(forceSync = false, timeoutMs = timeoutMs) ||
                (retryWithForceSync && requestBridge(forceSync = true, timeoutMs = retryTimeoutMs))
        if (connected) {
            onConnected?.invoke()
        }
        return connected
    }

    protected fun requireRemote(): Remote {
        return remote ?: error("$serviceLabel is not connected")
    }

    protected fun callRemote(block: (Remote) -> Boolean): Boolean? {
        val service = remote ?: return null
        return runCatching {
            block(service)
        }.onFailure {
            clearRemote(
                notifyClosed = true,
                reason = HookHostBridgeContract.Reason.REMOTE_DIED,
            )
        }.getOrNull()
    }

    protected fun requestRebind(forceSync: Boolean = false): Boolean {
        return requestBridge(forceSync = forceSync, timeoutMs = 0L)
    }

    protected fun currentContext(): Context? = appContext

    private fun requestBridge(forceSync: Boolean, timeoutMs: Long): Boolean {
        remote?.let { return true }

        val lease = requestState.acquire { pending ->
            synchronized(lock) {
                if (remote != null) return@synchronized false
                val context = appContext
                val ok = if (context == null) {
                    false
                } else {
                    runCatching {
                        onBeforeRequest(forceSync)
                        val callback = object : IHookHostBridgeBootstrap.Stub() {
                            override fun onBinderReady(binder: IBinder?) {
                                installRemote(asRemoteInterface(binder), pending)
                            }
                        }
                        val bundle = Bundle().apply {
                            putBinder(
                                HookHostBridgeContract.Extras.BINDER,
                                callback.asBinder(),
                            )
                        }
                        val intent = Intent(requestAction)
                            .setPackage(hostPackage)
                            .putExtra(HookHostBridgeContract.Extras.BUNDLE, bundle)
                            .putExtra(HookHostBridgeContract.Extras.FORCE_SYNC, forceSync)
                        context.sendBroadcast(intent)
                        true
                    }.getOrDefault(false)
                }
                ok
            }
        }
        val attempt = lease.attempt

        if (timeoutMs <= 0L) {
            if (lease.started) requestState.abandon(attempt)
            return remote != null
        }

        val ok = runCatching { attempt.latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false) && remote != null
        requestState.abandon(attempt)
        return ok
    }

    private fun installRemote(
        candidate: Remote?,
        attempt: HookHostBridgeRequestState.Attempt,
    ) {
        if (candidate == null) {
            requestState.fail(attempt)
            return
        }

        val binder = candidate.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            clearRemote(
                notifyClosed = true,
                reason = HookHostBridgeContract.Reason.REMOTE_DIED,
            )
        }

        val installed = synchronized(lock) {
            releaseRemoteLocked()
            val linked = runCatching {
                binder.linkToDeath(deathRecipient, 0)
                true
            }.getOrDefault(false)
            if (!linked) {
                false
            } else {
                remote = candidate
                remoteBinder = binder
                remoteDeathRecipient = deathRecipient
                true
            }
        }

        if (installed) {
            requestState.completeConnection(attempt)
            onRemoteConnected(candidate)
        } else {
            requestState.fail(attempt)
        }
    }

    private fun clearRemote(
        notifyClosed: Boolean,
        reason: String = HookHostBridgeContract.Reason.REMOTE_CLOSED,
    ) {
        val hadRemote = synchronized(lock) {
            val existed = remote != null
            releaseRemoteLocked()
            existed
        }
        requestState.completeConnection()

        if (hadRemote) {
            onRemoteDisconnected(reason)
            if (notifyClosed) {
                closedListener?.invoke(reason)
            }
        }
    }

    private fun releaseRemoteLocked() {
        val binder = remoteBinder
        val deathRecipient = remoteDeathRecipient
        if (binder != null && deathRecipient != null) {
            runCatching { binder.unlinkToDeath(deathRecipient, 0) }
        }
        remote = null
        remoteBinder = null
        remoteDeathRecipient = null
    }
}
