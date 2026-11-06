package hk.uwu.reareye.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuperLyricSnapshotTest {
    private val session = SuperLyricSession("player", "track")

    @Test
    fun originalModeOnlyShowsFirstLine() {
        assertEquals("original", snapshot("original\nembedded", "translated").displayText(false))
    }

    @Test
    fun translationPrefersExplicitTranslation() {
        assertEquals("translated", snapshot("original\nembedded", "translated").displayText(true))
    }

    @Test
    fun missingOrBlankTranslationFallsBackToEmbeddedSecondLine() {
        for (translation in listOf(null, "", "  ")) {
            assertEquals("embedded", snapshot("original\nembedded", translation).displayText(true))
        }
    }

    @Test
    fun translationFallsBackToOriginalWithoutEmbeddedTranslation() {
        assertEquals("original", snapshot("original").displayText(true))
    }

    @Test
    fun emptyEmbeddedTranslationDoesNotHideOriginal() {
        assertEquals("original\n", snapshot("original\n").displayText(true))
    }

    @Test
    fun nullAndBlankLinesClearTextInBothModes() {
        for (text in listOf(null, "", "  ", "\n")) {
            assertEquals("", snapshot(text).displayText(false))
            assertEquals("", snapshot(text).displayText(true))
        }
    }

    @Test
    fun carriageReturnLineEndingsAreNormalized() {
        val snapshot = snapshot("original\r\nembedded")
        assertEquals("original", snapshot.displayText(false))
        assertEquals("embedded", snapshot.displayText(true))
    }

    @Test
    fun unknownPublisherStillHasSessionOwnership() {
        val snapshot = SuperLyricSnapshot(SuperLyricSession(null, "track"), null, null)
        assertEquals("track", snapshot.session.lyricId)
        assertTrue(snapshot.session.acceptsStop(SuperLyricSession(null, "track")))
    }

    @Test
    fun matchingStopReleasesSession() {
        assertTrue(session.acceptsStop(SuperLyricSession("player", "track")))
    }

    @Test
    fun lateStopFromPreviousTrackDoesNotReleaseNewTrack() {
        assertFalse(session.acceptsStop(SuperLyricSession("player", "previous")))
    }

    @Test
    fun sameTrackIdFromDifferentPublisherDoesNotReleaseSession() {
        assertFalse(session.acceptsStop(SuperLyricSession("other-player", "track")))
    }

    @Test
    fun missingStopIdStillUsesPublisher() {
        assertTrue(session.acceptsStop(SuperLyricSession("player", null)))
        assertFalse(session.acceptsStop(SuperLyricSession("other-player", null)))
    }

    @Test
    fun missingStopPublisherStillUsesTrackId() {
        assertTrue(session.acceptsStop(SuperLyricSession(null, "track")))
        assertFalse(session.acceptsStop(SuperLyricSession(null, "previous")))
    }

    @Test
    fun unidentifiedStopAppliesToCurrentOwnedSession() {
        assertTrue(session.acceptsStop(SuperLyricSession(null, null)))
        assertTrue(session.acceptsStop(SuperLyricSession("", " ")))
    }

    private fun snapshot(text: String?, translation: String? = null) =
        SuperLyricSnapshot(session, text, translation)
}
