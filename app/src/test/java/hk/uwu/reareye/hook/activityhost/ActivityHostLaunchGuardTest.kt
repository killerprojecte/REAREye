package hk.uwu.reareye.hook.activityhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Exercises the exact guard used by prepareLaunch, native startTaskView and Session.dispose. */
class ActivityHostLaunchGuardTest {
    private fun <T> background(action: () -> T): FutureTask<T> =
        FutureTask(java.util.concurrent.Callable { action() }).also {
            Thread(it, "ActivityHostLaunchGuardTest").apply { isDaemon = true }.start()
        }

    @Test
    fun closeDuringBlockingRegistrationUnregistersLateCookieAndPreventsDispatch() {
        val guard = ActivityHostLaunchGuard<String>()
        val enteredRegister = CountDownLatch(1)
        val finishRegister = CountDownLatch(1)
        val unregistered = Collections.synchronizedList(mutableListOf<String>())
        val registration = background {
            runCatching {
                guard.register("cookie", register = {
                    enteredRegister.countDown()
                    check(finishRegister.await(5, TimeUnit.SECONDS))
                }, unregister = { unregistered += it })
            }
        }
        try {
            assertTrue(enteredRegister.await(5, TimeUnit.SECONDS))
            // Cancellation is not blocked behind the registerLaunch Binder call.
            assertNull(guard.cancel()!!.cookie)
            assertNull(guard.cookie())
        } finally {
            finishRegister.countDown()
        }
        val result = registration.get(5, TimeUnit.SECONDS)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf("cookie"), unregistered)
        assertNull(guard.cookie())
        var submitted = false
        assertTrue(runCatching { guard.dispatch { submitted = true } }.isFailure)
        assertFalse(submitted)
    }

    @Test
    fun closeAfterRegistrationOwnsCleanupAndStopsQueuedNativeDispatch() {
        val guard = ActivityHostLaunchGuard<String>()
        val events = mutableListOf<String>()
        guard.register(
            "cookie",
            register = { events += "register" },
            unregister = { events += "unregister:$it" })
        assertEquals("cookie", guard.cookie())
        val cancellation = guard.cancel()!!
        assertEquals("cookie", cancellation.cookie)
        events += "cleanup:${cancellation.cookie}"
        assertNull(guard.cancel()) // Only the winning disposer owns unregister.
        assertTrue(runCatching { guard.dispatch { events += "nativeStart" } }.isFailure)
        assertEquals(listOf("register", "cleanup:cookie"), events)
    }

    @Test
    fun cancellationAndNativeSubmissionHaveOneLinearizableBoundary() {
        val guard = ActivityHostLaunchGuard<String>()
        guard.register("cookie", register = {}, unregister = { fail("Unexpected unregister") })
        val enteredDispatch = CountDownLatch(1)
        val completeDispatch = CountDownLatch(1)
        val requestedCancel = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val dispatch = background {
            guard.dispatch {
                events += "start"
                enteredDispatch.countDown()
                check(completeDispatch.await(5, TimeUnit.SECONDS))
                events += "committed"
            }
        }
        assertTrue(enteredDispatch.await(5, TimeUnit.SECONDS))
        val cancellation = background {
            requestedCancel.countDown()
            guard.cancel().also { events += "cancelled" }
        }
        try {
            assertTrue(requestedCancel.await(5, TimeUnit.SECONDS))
        } finally {
            completeDispatch.countDown()
        }
        dispatch.get(5, TimeUnit.SECONDS)
        assertEquals("cookie", cancellation.get(5, TimeUnit.SECONDS)!!.cookie)
        assertEquals(listOf("start", "committed", "cancelled"), events)
        assertTrue(runCatching { guard.dispatch { fail("Late submission") } }.isFailure)
    }

    @Test
    fun registrationFailureCannotPublishOrDispatchCookie() {
        val guard = ActivityHostLaunchGuard<String>()
        assertTrue(runCatching {
            guard.register(
                "cookie",
                register = { error("Registry unavailable") },
                unregister = { fail("Not registered") })
        }.isFailure)
        assertNull(guard.cookie())
        assertTrue(runCatching { guard.dispatch { fail("No registered cookie") } }.isFailure)
        assertNull(guard.cancel()!!.cookie)
    }
}
