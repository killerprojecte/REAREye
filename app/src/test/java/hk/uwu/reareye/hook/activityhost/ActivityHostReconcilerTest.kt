package hk.uwu.reareye.hook.activityhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/** Tests the reconciler wired into ActivityHostBroker.Session, using a genuinely deferred queue. */
class ActivityHostReconcilerTest {
    private class Harness {
        val queue = ArrayDeque<() -> Unit>()
        val events = mutableListOf<String>()
        val nativeQueue = ArrayDeque<() -> Unit>()
        val snapshots = mutableListOf<ActivityHostAppliedSnapshot<String, String>>()
        val duringApply = mutableListOf<ActivityHostAppliedSnapshot<String, String>?>()
        var deferNative = false
        var failPhase: String? = null
        val state = ActivityHostReconciler<String, String>(
            post = { queue.addLast(it) },
            attach = { events += "attach:$it"; if (failPhase == "surface_attach") error("attach") },
            detach = { events += "detach" },
            release = { events += "release:$it" },
            applyLayout = {
                events += "layout:$it"
                duringApply += stateSnapshot()
                if (failPhase == "bounds_reconcile") error("bounds")
            },
            applyVisibility = { events += "visible:$it" },
            readyToLaunch = { events += "launch" },
            failure = { phase, _ -> events += "failure:$phase"; stateCancel() },
            afterApply = { if (deferNative) nativeQueue.addLast(it) else it() },
            settled = { stateSnapshot()?.let { snapshots += it } },
        )

        private fun stateCancel() {
            state.cancel()
        }

        private fun stateSnapshot(): ActivityHostAppliedSnapshot<String, String>? =
            state.appliedSnapshot()

        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
        }

        fun drainNative() {
            while (nativeQueue.isNotEmpty()) nativeQueue.removeFirst().invoke()
        }
    }

    @Test
    fun queuedSurfaceOneSurvivesGeometryTwoAndUsesLatestBounds() {
        val h = Harness()
        h.state.carrierReady()
        h.state.initialized()
        h.state.surface("surface1", "bounds1", 1)
        h.state.layout("bounds2", 2)
        assertTrue(h.events.isEmpty())
        assertEquals(1, h.queue.size)
        h.drain()
        assertEquals(
            listOf("attach:surface1", "layout:bounds2", "visible:true", "launch"),
            h.events
        )
        assertEquals(2L, h.state.layoutSequence)
    }

    @Test
    fun surfaceAndInitializationBeforeCarrierReplayLatestHiddenState() {
        val h = Harness()
        h.state.surface("surface1", "bounds1", 1)
        h.state.initialized()
        h.state.visible(false)
        h.drain()
        assertTrue(h.events.isEmpty())
        h.state.carrierReady()
        h.drain()
        assertEquals(
            listOf("attach:surface1", "layout:bounds1", "visible:false", "launch"),
            h.events
        )
    }

    @Test
    fun newerLayoutArrivingBeforeSurfaceDoesNotDiscardSurface() {
        val h = Harness()
        h.state.layout("bounds2", 2)
        assertTrue(h.state.surface("surface1", "bounds1", 1))
        h.state.carrierReady()
        h.drain()
        assertEquals(listOf("attach:surface1", "layout:bounds2", "visible:true"), h.events)
    }

    @Test
    fun taskCreationReplaysBoundsAndVisibilityWithoutReattaching() {
        val h = Harness()
        h.state.surface("surface1", "bounds1", 1)
        h.state.carrierReady()
        h.state.visible(false)
        h.drain()
        h.events.clear()
        h.state.taskCreated()
        h.state.layout("bounds2", 2)
        h.state.visible(true)
        h.state.visible(false)
        h.drain()
        assertEquals(listOf("layout:bounds2", "visible:false"), h.events)
    }

    @Test
    fun queuedReplacementReleasesUnattachedOldSurfaceOnlyAfterAttach() {
        val h = Harness()
        h.state.surface("surface1", "bounds1", 1)
        h.state.surface("surface2", "bounds2", 2)
        h.state.carrierReady()
        h.drain()
        assertEquals(
            listOf(
                "attach:surface2",
                "release:surface1",
                "layout:bounds2",
                "visible:true"
            ), h.events
        )
        h.events.clear()
        h.state.surface("surface3", "bounds3", 3)
        h.drain()
        assertEquals(
            listOf(
                "detach",
                "attach:surface3",
                "release:surface2",
                "layout:bounds3",
                "visible:true"
            ), h.events
        )
    }

    @Test
    fun cancelStopsPendingAttachAndCloseReleasesEachReferenceOnce() {
        val h = Harness()
        h.state.carrierReady()
        h.state.surface("surface1", "bounds1", 1)
        h.state.surface("surface2", "bounds2", 2)
        h.state.cancel()
        h.drain()
        assertTrue(h.events.isEmpty())
        h.state.close()
        h.state.close()
        h.state.initialized()
        h.state.taskCreated()
        h.state.visible(false)
        h.drain()
        assertEquals(listOf("release:surface1", "release:surface2"), h.events)
        assertFalse(h.state.surface("late", "bounds3", 3))
        assertEquals("release:late", h.events.last())
    }

    @Test
    fun staleSurfaceAndGeometryCannotReplaceNewerState() {
        val h = Harness()
        h.state.surface("new", "bounds3", 3)
        assertFalse(h.state.surface("stale", "bounds1", 1))
        assertFalse(h.state.layout("bounds2", 2))
        h.state.carrierReady()
        h.drain()
        assertEquals(
            listOf("release:stale", "attach:new", "layout:bounds3", "visible:true"),
            h.events
        )
    }

    @Test
    fun initializedAfterCarrierAndSurfaceTriggersLaunchRetry() {
        val h = Harness()
        h.state.carrierReady()
        h.state.surface("surface1", "bounds1", 1)
        h.drain()
        assertFalse(h.events.contains("launch"))
        h.events.clear()
        h.state.initialized()
        h.drain()
        assertEquals(listOf("launch"), h.events)
        h.events.clear()
        h.state.retryLaunch()
        h.drain()
        assertEquals(listOf("launch"), h.events)
    }

    @Test
    fun attachmentFailureIsExplicitAndDoesNotLaunch() {
        val h = Harness()
        h.failPhase = "surface_attach"
        h.state.carrierReady()
        h.state.initialized()
        h.state.surface("surface1", "bounds1", 1)
        h.drain()
        assertEquals(listOf("attach:surface1", "failure:surface_attach"), h.events)
        h.state.close()
        assertEquals("release:surface1", h.events.last())
    }

    @Test
    fun pendingLayoutCannotAcknowledgeUsingNewSequenceWithOldAppliedBounds() {
        val h = Harness()
        h.state.carrierReady()
        h.state.surface("surface1", "bounds1", 1)
        h.drain()
        val first = h.state.appliedSnapshot()!!
        assertEquals(1L, first.sequence)
        h.state.layout("bounds2", 2)
        assertNull(h.state.appliedSnapshot())
        var acknowledged = false
        h.state.withAppliedSnapshot { acknowledged = true }
        assertFalse(acknowledged)
        assertEquals("bounds1", first.geometry) // An earlier published snapshot stays immutable.
        h.drain()
        assertEquals(2L, h.state.appliedSnapshot()!!.sequence)
        assertEquals("bounds2", h.state.appliedSnapshot()!!.geometry)
        assertTrue(h.duringApply.all { it == null })
    }

    @Test
    fun sameBoundsSurfaceReplacementInvalidatesAckUntilNewSurfaceApplied() {
        val h = Harness()
        h.state.carrierReady()
        h.state.surface("surface1", "sameBounds", 1)
        h.state.layout("sameBounds", 3)
        h.drain()
        val first = h.state.appliedSnapshot()!!
        h.state.surface("surface2", "sameBounds", 2) // Geometry sequence remains 3.
        assertNull(h.state.appliedSnapshot())
        h.drain()
        val second = h.state.appliedSnapshot()!!
        assertEquals(first.sequence, second.sequence)
        assertEquals(first.geometry, second.geometry)
        assertEquals("surface2", second.surface)
        assertTrue(second.surfaceEpoch > first.surfaceEpoch)
    }

    @Test
    fun nativeBarrierMustCompleteBeforeSnapshotCanAcknowledge() {
        val h = Harness()
        h.deferNative = true
        h.state.carrierReady()
        h.state.surface("surface1", "bounds1", 1)
        h.drain()
        assertNull(h.state.appliedSnapshot())
        assertTrue(h.snapshots.isEmpty())
        h.drainNative()
        assertEquals(1L, h.state.appliedSnapshot()!!.sequence)
        assertEquals(1, h.snapshots.size)
    }

    @Test
    fun lateOldBarrierCannotPublishAfterSameBoundsReplacementOrClose() {
        val h = Harness()
        h.deferNative = true
        h.state.carrierReady()
        h.state.surface("surface1", "same", 1)
        h.drain()
        h.state.surface("surface2", "same", 2)
        h.nativeQueue.removeFirst().invoke()
        assertNull(h.state.appliedSnapshot())
        h.drain()
        h.drainNative()
        assertEquals("surface2", h.state.appliedSnapshot()!!.surface)
        assertEquals(1, h.snapshots.size)
        h.state.taskCreated()
        assertNull(h.state.appliedSnapshot())
        h.drain()
        h.state.cancel()
        h.drainNative()
        assertNull(h.state.appliedSnapshot())
        assertEquals(1, h.snapshots.size)
        h.state.close()
        assertNull(h.state.appliedSnapshot())
    }

    @Test
    fun boundsFailureReportsBoundsPhase() {
        val h = Harness()
        h.failPhase = "bounds_reconcile"
        h.state.carrierReady()
        h.state.initialized()
        h.state.surface("surface1", "bounds1", 1)
        h.drain()
        assertEquals(
            listOf("attach:surface1", "layout:bounds1", "failure:bounds_reconcile"),
            h.events
        )
    }
}
