package hk.uwu.reareye.agent.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackedCallsTest {
    private fun call() = OkHttpClient().newCall(Request.Builder().url("http://localhost/").build())
    @Test
    fun `cancellation before interceptor attachment cancels late call`() = runBlocking {
        val registry = TrackedCalls()
        val call = call()
        try {
            registry.request<Unit> { throw CancellationException("cancel before engine scheduling") }
        } catch (_: CancellationException) {
        }
        registry.attach(call)
        assertTrue(call.isCanceled())
    }

    @Test
    fun `body-open Call remains cancellable after dispatcher no longer tracks it`() {
        val registry = TrackedCalls()
        val bodyOpenCall = call()
        registry.attach(bodyOpenCall)
        assertTrue(registry.dispatcher.runningCalls().isEmpty())
        registry.close()
        assertTrue(bodyOpenCall.isCanceled())
    }

    @Test
    fun `connection close before HTTP attachment cancels late call`() {
        val registry = TrackedCalls()
        val call = call()
        registry.close(); registry.attach(call)
        assertTrue(call.isCanceled())
    }

    @Test
    fun `cancelled lease rejects waiting requests instead of cancelling their independent Calls`() =
        runBlocking {
            val registry = TrackedCalls()
            val entered = CompletableDeferred<Unit>()
            val borrowedCall = call()
            var waitingAdmitted = false
            var waitingRejected = false
            val first = launch { registry.request { entered.complete(Unit); awaitCancellation() } }
            entered.await()
            val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    registry.request { waitingAdmitted = true }
                } catch (_: IllegalStateException) {
                    waitingRejected = true
                }
            }
            first.cancelAndJoin(); waiting.join()
            assertFalse(waitingAdmitted); assertTrue(waitingRejected)
            assertFalse(borrowedCall.isCanceled())
            val late = call(); registry.attach(late); assertTrue(late.isCanceled())
        }

    @Test
    fun `successful leases serialize concurrent native operations without poisoning the connection`() =
        runBlocking {
            val registry = TrackedCalls()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val order = mutableListOf<Int>()
            val first =
                launch { registry.request { order += 1; entered.complete(Unit); release.await(); order += 2 } }
            entered.await()
            val second =
                launch(start = CoroutineStart.UNDISPATCHED) { registry.request { order += 3 } }
            assertEquals(listOf(1), order)
            release.complete(Unit); first.join(); second.join()
            assertEquals(listOf(1, 2, 3), order); registry.close()
        }
}
