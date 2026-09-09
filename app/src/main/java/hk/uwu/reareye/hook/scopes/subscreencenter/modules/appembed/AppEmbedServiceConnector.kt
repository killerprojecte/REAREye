package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import hk.uwu.reareye.hook.core.YLog
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeContract
import hk.uwu.reareye.internal.appembed.IAppEmbedService
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap

/**
 * Asynchronously obtains and monitors the SystemUI AppEmbed broker.
 *
 * The bootstrap broadcast shares the sender identity so SystemUI can authenticate the initiating
 * package before returning a Binder. All callbacks are serialized onto the supplied main Handler.
 */
internal class AppEmbedServiceConnector(
    context: Context,
    /** Main-thread callback target used by the owning SurfaceView state machine. */
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    /** Invoked whenever a live broker has been installed. */
    private val onConnected: (IAppEmbedService) -> Unit,
    /** Invoked after the installed broker dies or bootstrap times out. */
    private val onDisconnected: (String) -> Unit,
    /** Reads Android-attributed sender identity; injectable only for direct receiver tests. */
    private val readySenderIdentity: (BroadcastReceiver) -> AppEmbedReadySenderIdentity = { receiver ->
        AppEmbedReadySenderIdentity(receiver.sentFromUid, receiver.sentFromPackage)
    },
    /** Resolves the installed SystemUI UID at receipt time; injectable only for direct tests. */
    private val systemUiUidResolver: () -> Int = {
        (context.applicationContext ?: context).packageManager.getPackageUid(
            AppEmbedContract.SYSTEM_UI_PACKAGE,
            0,
        )
    },
) : AutoCloseable {
    private val applicationContext = context.applicationContext ?: context

    /** Generation state shared by cold bootstrap, Binder death and ready-broadcast replacement. */
    private val lifecycle = AppEmbedConnectorLifecycle()

    /** Current live remote interface. */
    private var remote: IAppEmbedService? = null

    /** Current remote binder paired with its death recipient. */
    private var remoteBinder: IBinder? = null

    /** Current binder death recipient. */
    private var deathRecipient: IBinder.DeathRecipient? = null

    /** Strong reference to the callback binder until the reply or timeout arrives. */
    private var bootstrapCallback: IHookHostBridgeBootstrap? = null

    /** Whether this connector successfully installed its exported broker-ready receiver. */
    private var readyReceiverRegistered = false

    /** Receives only explicit replacement announcements authenticated by Android sender identity. */
    private val readyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AppEmbedContract.BROKER_READY_ACTION) return
            val systemUiUid = try {
                systemUiUidResolver()
            } catch (error: Throwable) {
                YLog.error(
                    "[AppEmbedConnector] cannot resolve SystemUI UID for broker-ready",
                    error
                )
                return
            }
            val sender = readySenderIdentity(this)
            if (!AppEmbedReadySenderPolicy.isAuthorized(
                    sender.uid,
                    sender.packageName,
                    systemUiUid,
                )
            ) {
                YLog.warn(
                    "[AppEmbedConnector] rejected broker-ready sender " +
                            "uid=${sender.uid} package=${sender.packageName}"
                )
                return
            }
            runOnMain(::handleBrokerReady)
        }
    }

    init {
        try {
            applicationContext.registerReceiver(
                readyReceiver,
                IntentFilter(AppEmbedContract.BROKER_READY_ACTION),
                Context.RECEIVER_EXPORTED,
            )
            readyReceiverRegistered = true
        } catch (error: Throwable) {
            YLog.error("[AppEmbedConnector] broker-ready receiver registration failed", error)
        }
    }

    /** Returns the currently live service, or null while disconnected. */
    fun serviceOrNull(): IAppEmbedService? = remote

    /** Starts one non-blocking bootstrap request; repeated calls share the in-flight request. */
    fun connect() {
        runOnMain {
            check(!lifecycle.closed) { "AppEmbed connector is closed" }
            if (!readyReceiverRegistered) {
                onDisconnected("ready_receiver_unavailable")
                return@runOnMain
            }
            remote?.let {
                onConnected(it)
                return@runOnMain
            }
            val serial = lifecycle.beginBootstrap() ?: return@runOnMain
            val callback = object : IHookHostBridgeBootstrap.Stub() {
                override fun onBinderReady(binder: IBinder?) {
                    runOnMain { acceptBootstrapReply(serial, binder) }
                }
            }
            bootstrapCallback = callback

            val requestBundle = Bundle().apply {
                putBinder(HookHostBridgeContract.Extras.BINDER, callback.asBinder())
            }
            val intent = Intent(AppEmbedContract.REQUEST_ACTION)
                .setPackage(AppEmbedContract.SYSTEM_UI_PACKAGE)
                .putExtra(HookHostBridgeContract.Extras.BUNDLE, requestBundle)
            val options = BroadcastOptions.makeBasic()
                .setShareIdentityEnabled(true)
                .toBundle()

            try {
                applicationContext.sendBroadcast(intent, null, options)
            } catch (error: Throwable) {
                lifecycle.finishBootstrap(serial)
                bootstrapCallback = null
                YLog.error("[AppEmbedConnector] bootstrap broadcast failed", error)
                onDisconnected("bootstrap_broadcast_failed")
                return@runOnMain
            }

            mainHandler.postDelayed(
                { onBootstrapTimeout(serial) },
                AppEmbedContract.CONNECT_TIMEOUT_MILLIS,
            )
        }
    }

    /** Installs an authenticated broker reply for the current request serial. */
    private fun acceptBootstrapReply(serial: Long, binder: IBinder?) {
        if (!lifecycle.finishBootstrap(serial)) {
            binder?.let { disposeRejectedCapability(it, "late bootstrap reply") }
            return
        }
        bootstrapCallback = null
        if (binder == null) {
            YLog.error("[AppEmbedConnector] SystemUI returned a null broker")
            onDisconnected("null_broker")
            return
        }

        val candidate = IAppEmbedService.Stub.asInterface(binder)
            ?: run {
                YLog.error("[AppEmbedConnector] binder does not implement IAppEmbedService")
                onDisconnected("invalid_broker")
                return
            }
        val recipient = IBinder.DeathRecipient {
            runOnMain { handleRemoteDeath(binder) }
        }
        try {
            binder.linkToDeath(recipient, 0)
        } catch (error: Throwable) {
            YLog.error("[AppEmbedConnector] linkToDeath failed", error)
            disposeRejectedCapability(binder, "linkToDeath failure")
            onDisconnected("link_to_death_failed")
            return
        }

        unlinkCurrentRemote()
        remote = candidate
        remoteBinder = binder
        deathRecipient = recipient
        onConnected(candidate)
    }

    /** Disposes a capability the connector cannot own without blocking its main-thread callbacks. */
    private fun disposeRejectedCapability(binder: IBinder, reason: String) {
        val candidate = IAppEmbedService.Stub.asInterface(binder)
        if (candidate == null) {
            YLog.error("[AppEmbedConnector] cannot dispose invalid capability after $reason")
            return
        }
        val disposalThread = Thread(
            {
                try {
                    candidate.dispose()
                } catch (error: Throwable) {
                    YLog.error(
                        "[AppEmbedConnector] rejected capability disposal failed: $reason",
                        error
                    )
                }
            },
            "REAREye-AppEmbedDispose",
        )
        try {
            disposalThread.start()
        } catch (error: Throwable) {
            YLog.error(
                "[AppEmbedConnector] rejected capability disposal thread failed: $reason",
                error
            )
        }
    }

    /** Fails a request that did not receive a reply within the contract timeout. */
    private fun onBootstrapTimeout(serial: Long) {
        if (!lifecycle.finishBootstrap(serial)) return
        bootstrapCallback = null
        YLog.error("[AppEmbedConnector] SystemUI broker bootstrap timed out")
        onDisconnected("bootstrap_timeout")
    }

    /** Clears a dead current broker and invalidates callbacks created against it. */
    private fun handleRemoteDeath(binder: IBinder) {
        if (lifecycle.closed || remoteBinder !== binder) return
        lifecycle.invalidateForBrokerReplacement()
        unlinkCurrentRemote()
        bootstrapCallback = null
        YLog.error("[AppEmbedConnector] SystemUI broker died")
        onDisconnected("remote_died")
    }

    /** Replaces a still-live old generation after an authenticated new-broker announcement. */
    private fun handleBrokerReady() {
        if (!lifecycle.invalidateForBrokerReplacement()) return
        val replacedBinder = remoteBinder
        bootstrapCallback = null
        unlinkCurrentRemote()
        replacedBinder?.let { binder ->
            disposeRejectedCapability(binder, "broker replacement")
        }
        YLog.info("[AppEmbedConnector] authenticated replacement broker is ready")
        try {
            onDisconnected("broker_replaced")
        } catch (error: Throwable) {
            YLog.error("[AppEmbedConnector] broker replacement state transition failed", error)
        }
        try {
            connect()
        } catch (error: Throwable) {
            YLog.error("[AppEmbedConnector] replacement broker bootstrap failed", error)
        }
    }

    /** Removes the current death recipient before replacing or closing a remote. */
    private fun unlinkCurrentRemote() {
        val binder = remoteBinder
        val recipient = deathRecipient
        remote = null
        remoteBinder = null
        deathRecipient = null
        if (binder != null && recipient != null) {
            try {
                if (!binder.unlinkToDeath(recipient, 0) && binder.isBinderAlive) {
                    YLog.error("[AppEmbedConnector] unlinkToDeath returned false for live binder")
                }
            } catch (error: Throwable) {
                YLog.error("[AppEmbedConnector] unlinkToDeath failed", error)
            }
        }
    }

    /** Runs callbacks on the one thread that owns the client state machine. */
    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == mainHandler.looper) block() else mainHandler.post(block)
    }

    /** Permanently disconnects and invalidates all pending bootstrap replies. */
    override fun close() {
        runOnMain {
            if (!lifecycle.close()) return@runOnMain
            bootstrapCallback = null
            unlinkCurrentRemote()
            if (readyReceiverRegistered) {
                readyReceiverRegistered = false
                try {
                    applicationContext.unregisterReceiver(readyReceiver)
                } catch (error: Throwable) {
                    YLog.error("[AppEmbedConnector] broker-ready receiver unregister failed", error)
                }
            }
        }
    }
}
