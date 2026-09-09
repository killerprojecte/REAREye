package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppEmbedCapabilitySessionLedgerTest {
    @Test
    fun terminalIssuedSessionRemainsOwnedForRepeatedRelease() {
        val ledger = AppEmbedCapabilitySessionLedger(capabilityNamespace = 7L)
        val rejectedSessionId = ledger.issueSessionId()

        ledger.markTerminal(rejectedSessionId)

        ledger.requireIssuedSession(rejectedSessionId)
        ledger.requireIssuedSession(rejectedSessionId)
        assertFalse(ledger.requireOwnedSessionAndCheckActive(rejectedSessionId))
        assertEquals(emptyList<Long>(), ledger.closeAndTakeActiveSessions())
    }

    @Test
    fun commandAuthorizationDistinguishesActiveTerminalDisposedAndForeignIds() {
        val ledger = AppEmbedCapabilitySessionLedger(capabilityNamespace = 21L)
        val activeId = ledger.issueSessionId()
        assertTrue(ledger.requireOwnedSessionAndCheckActive(activeId))

        ledger.markTerminal(activeId)
        assertFalse(ledger.requireOwnedSessionAndCheckActive(activeId))

        val disposedLedger = AppEmbedCapabilitySessionLedger(capabilityNamespace = 22L)
        val disposedId = disposedLedger.issueSessionId()
        assertEquals(listOf(disposedId), disposedLedger.closeAndTakeActiveSessions())
        assertFalse(disposedLedger.requireOwnedSessionAndCheckActive(disposedId))

        val foreignId = AppEmbedCapabilitySessionLedger(capabilityNamespace = 23L).issueSessionId()
        assertThrows(IllegalArgumentException::class.java) {
            ledger.requireOwnedSessionAndCheckActive(foreignId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ledger.requireOwnedSessionAndCheckActive(activeId + 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            disposedLedger.requireOwnedSessionAndCheckActive(foreignId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            disposedLedger.requireOwnedSessionAndCheckActive(disposedId + 1L)
        }
    }

    @Test
    fun anotherCapabilityCannotUseIssuedOrTerminalSessionIds() {
        val first = AppEmbedCapabilitySessionLedger(capabilityNamespace = 1L)
        val second = AppEmbedCapabilitySessionLedger(capabilityNamespace = 2L)
        val firstId = first.issueSessionId()
        val secondId = second.issueSessionId()
        first.markTerminal(firstId)

        assertThrows(IllegalArgumentException::class.java) {
            second.requireIssuedSession(firstId)
        }
        assertThrows(IllegalArgumentException::class.java) {
            first.requireIssuedSession(secondId)
        }
    }

    @Test
    fun issuanceAndDisposeHaveOnlyTwoAtomicOutcomes() {
        val issuedBeforeDispose = AppEmbedCapabilitySessionLedger(capabilityNamespace = 11L)
        val activeId = issuedBeforeDispose.issueSessionId()
        assertEquals(listOf(activeId), issuedBeforeDispose.closeAndTakeActiveSessions())
        assertNull(issuedBeforeDispose.closeAndTakeActiveSessions())
        issuedBeforeDispose.requireIssuedSession(activeId)
        issuedBeforeDispose.markTerminal(activeId)

        val disposedBeforeIssue = AppEmbedCapabilitySessionLedger(capabilityNamespace = 12L)
        assertEquals(emptyList<Long>(), disposedBeforeIssue.closeAndTakeActiveSessions())
        assertThrows(IllegalStateException::class.java) {
            disposedBeforeIssue.issueSessionId()
        }
    }
}
