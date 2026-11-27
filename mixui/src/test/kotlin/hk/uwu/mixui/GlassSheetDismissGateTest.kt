package hk.uwu.mixui

import hk.uwu.mixui.components.GlassSheetDismissGate
import org.junit.Assert.*
import org.junit.Test

class GlassSheetDismissGateTest {
    @Test fun vetoDoesNotConsumeLaterAuthorizedDismissal() {
        val gate = GlassSheetDismissGate()
        assertFalse(gate.request(show = true, allowed = false))
        assertTrue(gate.request(show = true, allowed = true))
    }
    @Test fun repeatedEventsAndHiddenSheetCannotRequestTwice() {
        val gate = GlassSheetDismissGate()
        assertFalse(gate.request(show = false, allowed = true))
        assertTrue(gate.request(show = true, allowed = true))
        assertFalse(gate.request(show = true, allowed = true))
        gate.reset()
        assertTrue(gate.request(show = true, allowed = true))
    }
}
