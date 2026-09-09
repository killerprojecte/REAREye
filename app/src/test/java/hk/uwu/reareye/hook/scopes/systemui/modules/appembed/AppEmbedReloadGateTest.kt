package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEmbedReloadGateTest {
    @Test
    fun prepareAndRollbackRouteWork() {
        val gate = AppEmbedReloadGate()

        assertEquals(AppEmbedReloadGate.Route.RUN_NOW, gate.route())
        assertTrue(gate.prepare())
        assertEquals(AppEmbedReloadGate.Route.DEFER, gate.route())

        assertTrue(gate.rollback())
        assertEquals(AppEmbedReloadGate.Route.RUN_NOW, gate.route())
    }

    @Test
    fun closeIsPermanentAndCannotResumePreparedWork() {
        val gate = AppEmbedReloadGate()
        assertTrue(gate.prepare())

        gate.close()

        assertEquals(AppEmbedReloadGate.Route.DISCARD, gate.route())
        assertFalse(gate.rollback())
        assertFalse(gate.prepare())
    }

    @Test
    fun releaseBeforeRollbackKeepsDeferredCreationInactive() {
        val gate = AppEmbedReloadGate()
        val ledger = AppEmbedCapabilitySessionLedger(capabilityNamespace = 41L)
        val sessionId = ledger.issueSessionId()
        assertTrue(gate.prepare())
        assertEquals(AppEmbedReloadGate.Route.DEFER, gate.route())

        ledger.markTerminal(sessionId)
        assertTrue(gate.rollback())

        assertFalse(ledger.isActiveSession(sessionId))
    }
}
