package hk.uwu.reareye.hook.hostbridge

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

    @Volatile
    private var connectLatch: CountDownLatch? = null

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

    /** 旧代主动解绑后的子类清理点；默认无额外资源。 */
    protected open fun onUnbound() {
    }

    /** unlinkToDeath 失败时的可观察日志钩子。 */
    protected open fun onUnlinkToDeathFailure(error: Throwable) {
        YLog.error("$serviceLabel unlinkToDeath failed", error)
    }

    fun isConnected(): Boolean = remote != null

    /** 断开当前 Binder 并清除 Context；返回值用于向模块生命周期反馈失败。 */
    fun unbind(): Boolean {
        if (!clearRemote(notifyClosed = false, force = false)) return false
        return runCatching {
            onUnbound()
            appContext = null
            closedListener = null
            true
        }.getOrDefault(false)
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
                force = true,
            )
        }.getOrNull()
    }

    protected fun requestRebind(forceSync: Boolean = false): Boolean {
        return requestBridge(forceSync = forceSync, timeoutMs = 0L)
    }

    protected fun currentContext(): Context? = appContext

    private fun requestBridge(forceSync: Boolean, timeoutMs: Long): Boolean {
        remote?.let { return true }

        val latch = synchronized(lock) {
            remote?.let { return true }
            connectLatch?.let { return@synchronized it }

            CountDownLatch(1).also { pending ->
                connectLatch = pending
                val context = appContext
                val ok = if (context == null) {
                    false
                } else {
                    runCatching {
                        onBeforeRequest(forceSync)
                        val callback = object : IHookHostBridgeBootstrap.Stub() {
                            override fun onBinderReady(binder: IBinder?) {
                                installRemote(asRemoteInterface(binder))
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

                if (!ok) {
                    connectLatch = null
                    pending.countDown()
                }
            }
        }

        if (timeoutMs <= 0L) {
            return remote != null
        }

        val ok = runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false) && remote != null
        synchronized(lock) {
            if (connectLatch === latch) {
                connectLatch = null
            }
        }
        return ok
    }

    private fun installRemote(candidate: Remote?) {
        if (candidate == null) {
            synchronized(lock) {
                connectLatch?.countDown()
                connectLatch = null
            }
            return
        }

        val binder = candidate.asBinder()
        val deathRecipient = IBinder.DeathRecipient {
            clearRemote(
                notifyClosed = true,
                reason = HookHostBridgeContract.Reason.REMOTE_DIED,
                force = true,
            )
        }

        val installed = synchronized(lock) {
            if (!releaseRemoteLocked(force = false)) {
                connectLatch?.countDown()
                connectLatch = null
                false
            } else {
                val linked = runCatching {
                    binder.linkToDeath(deathRecipient, 0)
                    true
                }.getOrDefault(false)
                if (!linked) {
                    connectLatch?.countDown()
                    connectLatch = null
                    false
                } else {
                    remote = candidate
                    remoteBinder = binder
                    remoteDeathRecipient = deathRecipient
                    connectLatch?.countDown()
                    connectLatch = null
                    true
                }
            }
        }

        if (installed) {
            onRemoteConnected(candidate)
        }
    }

    private fun clearRemote(
        notifyClosed: Boolean,
        reason: String = HookHostBridgeContract.Reason.REMOTE_CLOSED,
        force: Boolean,
    ): Boolean {
        val result = synchronized(lock) {
            val existed = remote != null
            if (!releaseRemoteLocked(force)) return@synchronized false to existed
            connectLatch?.countDown()
            connectLatch = null
            true to existed
        }
        if (!result.first) return false

        if (result.second) {
            onRemoteDisconnected(reason)
            if (notifyClosed) {
                closedListener?.invoke(reason)
            }
        }
        return true
    }

    private fun releaseRemoteLocked(force: Boolean): Boolean {
        val binder = remoteBinder
        val deathRecipient = remoteDeathRecipient
        if (binder != null && deathRecipient != null) {
            val unlinked = runCatching { binder.unlinkToDeath(deathRecipient, 0) }
                .onFailure(::onUnlinkToDeathFailure)
                .getOrDefault(false)
            if (!unlinked) {
                onUnlinkToDeathFailure(
                    IllegalStateException("${serviceLabel} unlinkToDeath returned false")
                )
                if (!force) return false
            }
        }
        remote = null
        remoteBinder = null
        remoteDeathRecipient = null
        return true
    }
}
