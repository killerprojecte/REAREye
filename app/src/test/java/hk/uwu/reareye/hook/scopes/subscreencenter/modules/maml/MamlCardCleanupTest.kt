package hk.uwu.reareye.hook.scopes.subscreencenter.modules.maml

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MamlCardCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun failedSavedKeyReadOnUninstantiatedCardPreservesItsOnlyConfig() {
        val config = temporary.newFile("card.xml").apply { writeText("saved key mapping") }
        assertFalse(MamlCardCleanup.deleteConfigIfReleased(false, emptyList()) { config.delete() })
        assertEquals("saved key mapping", config.readText())
    }

    @Test
    fun knownWidgetReleaseFailurePreservesConfigEvenAfterOtherWidgetsWereReleased() {
        val config = temporary.newFile("card.xml")
        assertFalse(MamlCardCleanup.deleteConfigIfReleased(true, listOf(true, false)) { config.delete() })
        assertTrue(config.exists())
    }

    @Test
    fun successfulDiscoveryAndAllReleasesAllowDeletion() {
        val config = temporary.newFile("card.xml")
        assertTrue(MamlCardCleanup.deleteConfigIfReleased(true, listOf(true, true)) { config.delete() })
        assertFalse(config.exists())
    }

    @Test
    fun verifiedEmptyCardNeedsNoWidgetRelease() {
        val config = temporary.newFile("card.xml")
        assertTrue(MamlCardCleanup.deleteConfigIfReleased(true, emptyList()) { config.delete() })
        assertFalse(config.exists())
    }

    @Test
    fun failedDeletionIsNotReportedAsSuccess() {
        val failure = IllegalStateException("filesystem unavailable")
        assertSame(failure, assertThrows(IllegalStateException::class.java) {
            MamlCardCleanup.deleteConfigIfReleased(true, emptyList()) { throw failure }
        })
    }
}
