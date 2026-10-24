package hk.uwu.reareye.ui.screen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptEntryIdsTest {
    @Test
    fun rowsAlreadyInTheTranscriptAreNeverNew() {
        val ids = TranscriptEntryIds()
        ids.remember(sequenceOf("a", "b"))
        assertFalse(ids.isNew("a"))
        assertFalse(ids.isNew("b"))
    }

    @Test
    fun anIdAddedAfterwardsIsNewExactlyOnce() {
        val ids = TranscriptEntryIds()
        ids.remember(sequenceOf("a"))
        // A row appended by a new turn is new, and stays new until it is remembered.
        assertTrue(ids.isNew("b"))
        ids.remember(sequenceOf("a", "b"))
        assertFalse(ids.isNew("b"))
    }

    @Test
    fun rememberingDoesNotReviveAnId() {
        val ids = TranscriptEntryIds()
        ids.remember(sequenceOf("a", "b"))
        // A later transcript state that no longer mentions `a` must not make it new again.
        ids.remember(sequenceOf("b", "c"))
        assertFalse(ids.isNew("a"))
        assertFalse(ids.isNew("b"))
        assertTrue(ids.isNew("d"))
    }
}
