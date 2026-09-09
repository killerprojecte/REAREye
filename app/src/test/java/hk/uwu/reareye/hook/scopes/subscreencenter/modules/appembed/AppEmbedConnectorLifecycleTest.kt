package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeContract
import hk.uwu.reareye.internal.appembed.IAppEmbedCallback
import hk.uwu.reareye.internal.appembed.IAppEmbedService
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.LEGACY)
class AppEmbedConnectorLifecycleTest {
    @Test
    fun readySenderRequiresExactSystemUiPackageAndValidResolvedUid() {
        assertTrue(
            AppEmbedReadySenderPolicy.isAuthorized(
                sentFromUid = 10_042,
                sentFromPackage = AppEmbedContract.SYSTEM_UI_PACKAGE,
                systemUiUid = 10_042,
            )
        )
        assertFalse(
            AppEmbedReadySenderPolicy.isAuthorized(
                sentFromUid = 10_043,
                sentFromPackage = AppEmbedContract.SYSTEM_UI_PACKAGE,
                systemUiUid = 10_042,
            )
        )
        assertFalse(
            AppEmbedReadySenderPolicy.isAuthorized(
                sentFromUid = 10_042,
                sentFromPackage = "com.example.spoof",
                systemUiUid = 10_042,
            )
        )
        assertFalse(
            AppEmbedReadySenderPolicy.isAuthorized(
                sentFromUid = -1,
                sentFromPackage = AppEmbedContract.SYSTEM_UI_PACKAGE,
                systemUiUid = -1,
            )
        )
    }

    @Test
    fun brokerReadyInvalidatesOldReplyBeforeStartingReplacementBootstrap() {
        val lifecycle = AppEmbedConnectorLifecycle()
        val oldSerial = lifecycle.beginBootstrap()!!

        assertTrue(lifecycle.invalidateForBrokerReplacement())
        assertFalse(lifecycle.finishBootstrap(oldSerial))

        val replacementSerial = lifecycle.beginBootstrap()!!
        assertNotEquals(oldSerial, replacementSerial)
        assertTrue(lifecycle.finishBootstrap(replacementSerial))
    }

    @Test
    fun brokerReadyWhileWaitingForServiceRestartsTheOutstandingBootstrap() {
        val lifecycle = AppEmbedConnectorLifecycle()
        val waitingSerial = lifecycle.beginBootstrap()!!

        assertTrue(lifecycle.requestInFlight)
        assertTrue(lifecycle.invalidateForBrokerReplacement())
        assertFalse(lifecycle.requestInFlight)
        assertFalse(lifecycle.finishBootstrap(waitingSerial))
        assertTrue(lifecycle.beginBootstrap() != null)
    }

    @Test
    fun closedConnectorIgnoresReadyAndCannotReconnect() {
        val lifecycle = AppEmbedConnectorLifecycle()
        lifecycle.beginBootstrap()

        assertTrue(lifecycle.close())
        assertFalse(lifecycle.invalidateForBrokerReplacement())
        assertFalse(lifecycle.requestInFlight)
        assertThrows(IllegalStateException::class.java) { lifecycle.beginBootstrap() }
    }

    @Test
    fun authenticatedReadyDisposesOldCapabilityRejectsOldReplyAndReconnects() {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val connected = ArrayList<IAppEmbedService>()
        val disconnected = ArrayList<String>()
        val connector = AppEmbedServiceConnector(
            context = context,
            mainHandler = Handler(Looper.getMainLooper()),
            onConnected = connected::add,
            onDisconnected = disconnected::add,
            readySenderIdentity = {
                AppEmbedReadySenderIdentity(10_042, AppEmbedContract.SYSTEM_UI_PACKAGE)
            },
            systemUiUidResolver = { 10_042 },
        )

        connector.connect()
        val firstBootstrap = context.bootstrapCallbacks.single()
        val oldCapability = RecordingCapability()
        firstBootstrap.onBinderReady(oldCapability.asBinder())
        assertSame(oldCapability, connected.single())

        context.readyReceiver.onReceive(
            context,
            Intent(AppEmbedContract.BROKER_READY_ACTION),
        )
        assertTrue(oldCapability.disposed.await(1, TimeUnit.SECONDS))
        assertEquals(1, oldCapability.disposeCalls.get())
        assertEquals(listOf("broker_replaced"), disconnected)
        assertEquals(2, context.bootstrapCallbacks.size)

        val staleCapability = RecordingCapability()
        firstBootstrap.onBinderReady(staleCapability.asBinder())
        assertTrue(staleCapability.disposed.await(1, TimeUnit.SECONDS))
        assertEquals(1, staleCapability.disposeCalls.get())
        assertEquals(1, connected.size)

        val replacementCapability = RecordingCapability()
        context.bootstrapCallbacks[1].onBinderReady(replacementCapability.asBinder())
        assertSame(replacementCapability, connected.last())

        connector.close()
        assertEquals(1, context.unregisterCount)
        val bootstrapCountAfterClose = context.bootstrapCallbacks.size
        context.readyReceiver.onReceive(
            context,
            Intent(AppEmbedContract.BROKER_READY_ACTION),
        )
        assertEquals(bootstrapCountAfterClose, context.bootstrapCallbacks.size)
    }

    @Test
    fun receiverRegistrationFailureFailsTheFeatureWithoutCrashingTheHost() {
        val context = RecordingContext(
            base = RuntimeEnvironment.getApplication(),
            failReadyRegistration = true,
        )
        val disconnected = ArrayList<String>()
        val connector = AppEmbedServiceConnector(
            context = context,
            mainHandler = Handler(Looper.getMainLooper()),
            onConnected = { error("Unexpected connection") },
            onDisconnected = disconnected::add,
            readySenderIdentity = {
                AppEmbedReadySenderIdentity(10_042, AppEmbedContract.SYSTEM_UI_PACKAGE)
            },
            systemUiUidResolver = { 10_042 },
        )

        connector.connect()

        assertEquals(listOf("ready_receiver_unavailable"), disconnected)
        assertTrue(context.bootstrapCallbacks.isEmpty())
        connector.close()
        assertEquals(0, context.unregisterCount)
    }

    /** Context boundary that records real Connector registration and bootstrap calls. */
    private class RecordingContext(
        base: Context,
        private val failReadyRegistration: Boolean = false,
    ) : ContextWrapper(base) {
        lateinit var readyReceiver: BroadcastReceiver
        val bootstrapCallbacks = ArrayList<IHookHostBridgeBootstrap>()
        var unregisterCount = 0

        override fun getApplicationContext(): Context = this

        override fun registerReceiver(
            receiver: BroadcastReceiver?,
            filter: IntentFilter,
            flags: Int,
        ): Intent? {
            if (failReadyRegistration) {
                throw SecurityException("Ready receiver registration rejected")
            }
            readyReceiver = requireNotNull(receiver)
            return null
        }

        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            assertSame(readyReceiver, receiver)
            unregisterCount += 1
        }

        override fun sendBroadcast(intent: Intent, receiverPermission: String?, options: Bundle?) {
            val callbackBinder = intent
                .getBundleExtra(HookHostBridgeContract.Extras.BUNDLE)
                ?.getBinder(HookHostBridgeContract.Extras.BINDER)
                ?: error("Bootstrap request did not contain a callback Binder")
            bootstrapCallbacks += IHookHostBridgeBootstrap.Stub.asInterface(callbackBinder)
        }
    }

    /** Local Binder capability used to observe asynchronous disposal without reflection. */
    private class RecordingCapability : IAppEmbedService.Stub() {
        val disposed = CountDownLatch(1)
        val disposeCalls = AtomicInteger()

        override fun createSession(
            ownerToken: IBinder,
            hostToken: IBinder,
            displayId: Int,
            widthPx: Int,
            heightPx: Int,
            densityDpi: Int,
            taskBoundsOnScreen: Rect,
            launchIntent: Intent,
            touchable: Boolean,
            callback: IAppEmbedCallback,
        ): Long = error("Not used by connector test")

        override fun resize(
            sessionId: Long,
            widthPx: Int,
            heightPx: Int,
            taskBoundsOnScreen: Rect,
        ) = error("Not used by connector test")

        override fun setVisible(sessionId: Long, visible: Boolean) =
            error("Not used by connector test")

        override fun requestFocus(sessionId: Long) = error("Not used by connector test")

        override fun release(sessionId: Long) = error("Not used by connector test")

        override fun dispose() {
            disposeCalls.incrementAndGet()
            disposed.countDown()
        }
    }
}
