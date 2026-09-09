package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEmbedLaunchTransitionTrackerTest {
    @Test
    fun taskAppearedBeforeFinishResolvesOneLateAdoption() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()
        val token = Any()
        val taskInfo = Any()
        val leash = Any()
        tracker.observePending(pending, type = 1, controller)
        assertTrue(tracker.observeClaim(token, pending))

        assertNull(tracker.observeTaskAppeared(controller, taskInfo, leash))
        val resolution = tracker.finish(token)

        assertLateAdoption(resolution, controller, taskInfo, leash)
        assertNull(tracker.finish(token))
    }

    @Test
    fun finishBeforeTaskAppearedWaitsAndThenResolvesLateAdoption() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()
        val token = Any()
        val taskInfo = Any()
        val leash = Any()
        tracker.observePending(pending, type = 1, controller)
        assertTrue(tracker.observeClaim(token, pending))

        val awaiting = tracker.finish(token)
        assertTrue(awaiting is AppEmbedLaunchResolution.AwaitingTask)
        assertSame(controller, (awaiting as AppEmbedLaunchResolution.AwaitingTask).controller)

        assertLateAdoption(
            tracker.observeTaskAppeared(controller, taskInfo, leash),
            controller,
            taskInfo,
            leash,
        )
    }

    @Test
    fun nativePrepareReleasesCachedLeashAndNeverRequestsLateAdoption() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()
        val token = Any()
        val cachedLeash = Any()
        val released = ArrayList<Any>()
        tracker.observePending(pending, type = 1, controller)
        tracker.observeClaim(token, pending)
        tracker.observeTaskAppeared(controller, taskInfo = Any(), cachedLeash, released::add)

        assertTrue(tracker.beginNativePrepare(controller))
        assertNull(tracker.completeNativePrepare(controller))
        assertEquals(listOf(cachedLeash), released)
        val resolution = tracker.finish(token)

        assertTrue(resolution is AppEmbedLaunchResolution.NativePrepared)
        assertSame(controller, (resolution as AppEmbedLaunchResolution.NativePrepared).controller)
        assertEquals(listOf(cachedLeash), released)
    }

    @Test
    fun transitionCanFinishBeforeLateNativePrepareCompletes() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()
        val token = Any()
        tracker.observePending(pending, type = 1, controller)
        tracker.observeClaim(token, pending)

        assertTrue(tracker.finish(token) is AppEmbedLaunchResolution.AwaitingTask)
        assertTrue(tracker.beginNativePrepare(controller))
        val resolution = tracker.completeNativePrepare(controller)

        assertTrue(resolution is AppEmbedLaunchResolution.NativePrepared)
        assertSame(controller, (resolution as AppEmbedLaunchResolution.NativePrepared).controller)
        assertNull(tracker.observeTaskAppeared(controller, Any(), Any()))
        assertFalse(tracker.shouldSuppressTaskNotFound(controller))
    }

    @Test
    fun nestedFinishCallbacksResolveClaimedTokenOnlyOnce() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()
        val token = Any()
        tracker.observePending(pending, type = 1, controller)
        tracker.observeClaim(token, pending)

        assertTrue(tracker.finish(token) is AppEmbedLaunchResolution.AwaitingTask)
        assertNull(tracker.finish(token))
        assertNull(tracker.finish(token))
    }

    @Test
    fun pendingControllerSuppressesTaskNotFoundBeforeAsyncClaimArrives() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()
        tracker.observePending(pending, type = 1, controller)

        assertTrue(tracker.shouldSuppressTaskNotFound(controller))
        assertTrue(tracker.observeClaim(Any(), pending))
        assertTrue(tracker.shouldSuppressTaskNotFound(controller))
    }

    @Test
    fun nativePrepareFailureDoesNotMarkControllerPrepared() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()
        val token = Any()
        tracker.observePending(pending, type = 1, controller)
        tracker.observeClaim(token, pending)

        assertTrue(tracker.beginNativePrepare(controller))
        val resolutionAfterThrow = tracker.finish(token)

        assertTrue(resolutionAfterThrow is AppEmbedLaunchResolution.AwaitingTask)
        assertSame(
            controller,
            (resolutionAfterThrow as AppEmbedLaunchResolution.AwaitingTask).controller,
        )
        assertTrue(tracker.shouldSuppressTaskNotFound(controller))
    }

    @Test
    fun failedLateAdoptionReleasesLeashWithoutPretendingNativePrepareCompleted() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()
        val token = Any()
        val leash = Any()
        val released = ArrayList<Any>()
        tracker.observePending(pending, type = 1, controller)
        tracker.observeClaim(token, pending)
        tracker.observeTaskAppeared(controller, Any(), leash, released::add)
        assertTrue(tracker.finish(token) is AppEmbedLaunchResolution.AdoptLateTask)

        tracker.finishLateAdoptionCall(controller, succeeded = false)

        assertEquals(listOf(leash), released)
        assertTrue(tracker.shouldSuppressTaskNotFound(controller))
        assertNull(tracker.finish(token))
        tracker.clear()
        assertEquals(listOf(leash), released)
    }

    @Test
    fun unrelatedIdentitiesCannotChangeOwnedControllerState() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()
        val token = Any()
        val unrelatedLeash = Any()
        val released = ArrayList<Any>()
        tracker.observePending(pending, type = 1, controller)

        assertFalse(tracker.observeClaim(Any(), Any()))
        assertFalse(tracker.beginNativePrepare(Any()))
        assertNull(tracker.finish(Any()))
        assertNull(tracker.observeTaskAppeared(Any(), Any(), unrelatedLeash, released::add))
        assertEquals(listOf(unrelatedLeash), released)
        assertTrue(tracker.shouldSuppressTaskNotFound(controller))

        assertTrue(tracker.observeClaim(token, pending))
        assertTrue(tracker.finish(token) is AppEmbedLaunchResolution.AwaitingTask)
    }

    @Test
    fun forgetAndClearReleaseEveryRetainedLeashExactlyOnceAndDropClaims() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val firstPending = Any()
        val firstController = Any()
        val firstToken = Any()
        val firstLeash = Any()
        val secondPending = Any()
        val secondController = Any()
        val secondToken = Any()
        val secondLeash = Any()
        val released = ArrayList<Any>()
        tracker.observePending(firstPending, type = 1, firstController)
        tracker.observeClaim(firstToken, firstPending)
        tracker.observeTaskAppeared(firstController, Any(), firstLeash, released::add)
        tracker.observePending(secondPending, type = 1, secondController)
        tracker.observeClaim(secondToken, secondPending)
        tracker.observeTaskAppeared(secondController, Any(), secondLeash, released::add)

        tracker.forgetController(firstController)
        assertEquals(listOf(firstLeash), released)
        assertFalse(tracker.shouldSuppressTaskNotFound(firstController))
        assertNull(tracker.finish(firstToken))

        tracker.clear()
        tracker.clear()
        assertEquals(listOf(firstLeash, secondLeash), released)
        assertFalse(tracker.shouldSuppressTaskNotFound(secondController))
        assertNull(tracker.finish(secondToken))
    }

    @Test
    fun nonOpeningPendingTransitionNeverCreatesOwnedState() {
        val tracker = AppEmbedLaunchTransitionTracker(openingType = 1)
        val pending = Any()
        val controller = Any()

        tracker.observePending(pending, type = 2, controller)

        assertFalse(tracker.observeClaim(Any(), pending))
        assertFalse(tracker.shouldSuppressTaskNotFound(controller))
    }

    private fun assertLateAdoption(
        resolution: AppEmbedLaunchResolution?,
        controller: Any,
        taskInfo: Any,
        leash: Any,
    ) {
        assertTrue(resolution is AppEmbedLaunchResolution.AdoptLateTask)
        resolution as AppEmbedLaunchResolution.AdoptLateTask
        assertSame(controller, resolution.controller)
        assertSame(taskInfo, resolution.taskInfo)
        assertSame(leash, resolution.leash)
    }
}
