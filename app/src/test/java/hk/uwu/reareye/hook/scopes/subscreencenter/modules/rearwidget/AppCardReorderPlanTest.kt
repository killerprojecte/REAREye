package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host rewrites its whole app-card list, so the order plan is the one place that decides what a
 * request is allowed to do. It is ownership-blind on purpose: an external app card may be
 * repositioned exactly like one registered by REAREye.
 */
class AppCardReorderPlanTest {
    private val owned = listOf("reareye:a", "reareye:b")
    private val external = listOf("com.example.one", "com.example.two")

    @Test
    fun movingARegisteredCardIsAccepted() {
        val plan = appCardReorderPlan(owned + external, listOf("reareye:b", "reareye:a") + external)
        assertNotNull(plan)
        assertEquals(listOf("reareye:b", "reareye:a") + external, plan!!.order)
        assertEquals("reareye:b", plan.movedId)
        assertFalse(plan.unchanged)
    }

    @Test
    fun swappingTwoExternalCardsIsAccepted() {
        // This is the case the previous policy rejected outright.
        val current = owned + external
        val plan = appCardReorderPlan(
            current,
            owned + listOf("com.example.two", "com.example.one"),
        )
        assertNotNull(plan)
        assertEquals(owned + listOf("com.example.two", "com.example.one"), plan!!.order)
        assertEquals("com.example.two", plan.movedId)
    }

    @Test
    fun aListOfOnlyExternalCardsCanBeReordered() {
        // Previously impossible: no REAREye card existed to report as the changed entry.
        val plan = appCardReorderPlan(external, listOf("com.example.two", "com.example.one"))
        assertNotNull(plan)
        assertEquals("com.example.two", plan!!.movedId)
    }

    @Test
    fun anExternalCardCanMovePastARegisteredOne() {
        val plan = appCardReorderPlan(
            listOf("reareye:a", "com.example.one"),
            listOf("com.example.one", "reareye:a"),
        )
        assertNotNull(plan)
        assertEquals("com.example.one", plan!!.movedId)
    }

    @Test
    fun anUnchangedOrderNeedsNoSave() {
        val plan = appCardReorderPlan(owned + external, owned + external)
        assertNotNull(plan)
        assertTrue(plan!!.unchanged)
    }

    @Test
    fun aPartialOrderIsRejectedBecauseItWouldDropEntries() {
        assertNull(appCardReorderPlan(owned + external, owned))
    }

    @Test
    fun aDuplicateEntryIsRejected() {
        assertNull(
            appCardReorderPlan(
                owned + external,
                listOf("reareye:a", "reareye:a") + external
            )
        )
    }

    @Test
    fun anUnknownIdIsRejected() {
        assertNull(
            appCardReorderPlan(
                owned + external,
                listOf("reareye:a", "not.installed") + external
            )
        )
    }

    @Test
    fun aHostListWithDuplicateIdsIsRejected() {
        assertNull(appCardReorderPlan(listOf("dup", "dup"), listOf("dup", "dup")))
    }
}
