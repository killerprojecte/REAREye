package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import android.graphics.Rect
import android.view.SurfaceControl
import hk.uwu.reareye.internal.activityhost.IActivityHostSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class ActivityHostClientRaceTest {
    private class BlockingSession : IActivityHostSession.Stub() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val launched = CountDownLatch(1)
        val launches = AtomicInteger()
        override fun setSurface(
            surface: SurfaceControl?,
            bounds: Rect?,
            visible: Rect?,
            sequence: Long
        ) {
            entered.countDown()
            check(proceed.await(5, TimeUnit.SECONDS))
        }

        override fun updateLayout(bounds: Rect?, visible: Rect?, sequence: Long) = Unit
        override fun launch(component: String?, dpi: Int) {
            launches.incrementAndGet(); launched.countDown()
        }

        override fun setVisible(visible: Boolean) = Unit
        override fun moveToFullscreen() = Unit
        override fun close() {
            closed.countDown()
        }
    }

    private val events = object : ActivityHostClient.Events {
        override fun onOpened(generation: Long) = Unit
        override fun onLayoutApplied(generation: Long, sequence: Long, bounds: Rect) = Unit
        override fun onTaskCreated(generation: Long, taskId: Int, displayId: Int) = Unit
        override fun onClosed(generation: Long, reason: String) = Unit
    }

    private fun exercise(closeWhileBlocked: Boolean, disposeWhileBlocked: Boolean = false) {
        val client = ActivityHostClient(RuntimeEnvironment.getApplication(), events)
        val remote = BlockingSession()
        // Seed the post-discovery state so the real layout executor can be tested without SystemUI.
        ActivityHostClient::class.java.getDeclaredField("epoch").apply { isAccessible = true }
            .let { (it.get(client) as AtomicLong).set(1) }
        ActivityHostClient::class.java.getDeclaredField("session").apply { isAccessible = true }
            .set(client, remote)
        ActivityHostClient::class.java.getDeclaredField("pendingLaunch")
            .apply { isAccessible = true }
            .set(client, "example/example.Main" to 320)
        val surface = SurfaceControl.Builder().setName("race-test").setBufferSize(1, 1).build()
        try {
            client.layout(1, surface, Rect(0, 0, 100, 100), Rect(0, 0, 100, 100), 1)
            assertTrue(remote.entered.await(5, TimeUnit.SECONDS))
            if (closeWhileBlocked) {
                if (disposeWhileBlocked) client.dispose() else client.close()
                remote.proceed.countDown()
                assertTrue(remote.closed.await(5, TimeUnit.SECONDS))
                assertEquals(0, remote.launches.get())
            } else {
                remote.proceed.countDown()
                assertTrue(remote.launched.await(5, TimeUnit.SECONDS))
                client.close()
                assertTrue(remote.closed.await(5, TimeUnit.SECONDS))
                assertEquals(1, remote.launches.get())
            }
        } finally {
            remote.proceed.countDown()
            client.dispose()
            surface.release()
        }
    }

    @Test
    fun closeDuringSetSurfaceDoesNotLaunch() = exercise(true)
    @Test
    fun disposeDuringSetSurfaceDoesNotLaunch() = exercise(true, true)
    @Test
    fun currentGenerationStillLaunchesOnce() = exercise(false)

    @Test
    fun clientDeadlineAllowsBothBrokerPhasesAndCallbackDelivery() {
        val contract = hk.uwu.reareye.hook.activityhost.ActivityHostContract
        assertTrue(contract.CLIENT_TASK_TIMEOUT_MS > 2 * contract.STARTUP_PHASE_TIMEOUT_MS)
        assertEquals(
            contract.STARTUP_CALLBACK_SLACK_MS,
            contract.CLIENT_TASK_TIMEOUT_MS - 2 * contract.STARTUP_PHASE_TIMEOUT_MS
        )
    }
}
