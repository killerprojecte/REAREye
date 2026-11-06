@file:Suppress("DEPRECATION")

package hk.uwu.reareye.lyrics

import com.hchen.superlyricapi.LyricListener
import com.hchen.superlyricapi.LyricView
import com.hchen.superlyricapi.Receiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.model.Line
import com.hchen.superlyricapi.model.Lyric
import com.hchen.superlyricapi.model.PlaybackState
import com.hchen.superlyricapi.model.Stop
import org.junit.Assert.*
import org.junit.Test

class SuperLyricConnectionTest {
    private val session = SuperLyricSession("player", "song")
    private val snapshot = SuperLyricSnapshot(session, "original", "translation")

    private class FakeBackend : SuperLyricConnection.Backend {
        lateinit var lyric: (SuperLyricSnapshot) -> Unit
        lateinit var stop: (SuperLyricSession) -> Unit
        var result = Receiver.RegisterResult.OK
        var duringRegister: (() -> Unit)? = null
        var duringLatest: (() -> Unit)? = null
        var replay: SuperLyricSnapshot? = null
        var cleanup = true
        var cleanupCount = 0
        var registerCount = 0
        override fun register(onLyric: (SuperLyricSnapshot) -> Unit, onStop: (SuperLyricSession) -> Unit): Receiver.RegisterResult {
            lyric = onLyric
            stop = onStop
            registerCount++
            duringRegister?.invoke()
            return result
        }
        override fun latest(): SuperLyricSnapshot? {
            duringLatest?.invoke()
            return replay
        }
        override fun unregister(): Boolean { cleanupCount++; return cleanup }
    }

    @Test fun synchronousReplayIsAcceptedAndNotReplayedTwice() {
        val backend = FakeBackend()
        val received = mutableListOf<SuperLyricSnapshot>()
        val connection = SuperLyricConnection(received::add, {}, { backend })
        backend.duringRegister = {
            assertTrue(connection.isActive)
            backend.lyric(snapshot)
            assertTrue(received.isEmpty())
        }
        backend.replay = snapshot
        assertEquals(Receiver.RegisterResult.OK, connection.register())
        assertEquals(listOf(snapshot), received)
        connection.register()
        assertEquals(1, backend.registerCount)
    }

    @Test fun missingServiceIsInactiveAndCloseIsIdempotent() {
        val connection = SuperLyricConnection({}, {}, { null })
        assertEquals(Receiver.RegisterResult.SERVICE_UNAVAILABLE, connection.register())
        assertFalse(connection.isActive)
        assertTrue(connection.unregister())
        assertTrue(connection.unregister())
    }

    @Test fun registrationRejectionCleansUpAndSuppressesLateCallbacks() {
        val backend = FakeBackend().apply { result = Receiver.RegisterResult.REJECTED_QUOTA }
        val received = mutableListOf<SuperLyricSnapshot>()
        val connection = SuperLyricConnection(received::add, {}, { backend })
        assertEquals(Receiver.RegisterResult.REJECTED_QUOTA, connection.register())
        backend.lyric(snapshot)
        assertFalse(connection.isActive)
        assertTrue(received.isEmpty())
        assertEquals(1, backend.cleanupCount)
    }

    @Test fun registerExceptionStillAttemptsCleanup() {
        val backend = FakeBackend().apply { duringRegister = { throw IllegalStateException("binder failed") } }
        val connection = SuperLyricConnection({}, {}, { backend })
        assertEquals(Receiver.RegisterResult.SERVICE_UNAVAILABLE, connection.register())
        assertFalse(connection.isActive)
        assertEquals(1, backend.cleanupCount)
    }

    @Test fun closeBeforeRegisterNeverTouchesService() {
        var probes = 0
        val connection = SuperLyricConnection({}, {}, { probes++; FakeBackend() })
        assertTrue(connection.unregister())
        assertEquals(Receiver.RegisterResult.SERVICE_UNAVAILABLE, connection.register())
        assertEquals(0, probes)
    }

    @Test fun uncertainCleanupIsRetainedForRetryButCallbacksAreSuppressed() {
        val backend = FakeBackend()
        val received = mutableListOf<SuperLyricSnapshot>()
        val connection = SuperLyricConnection(received::add, {}, { backend })
        connection.register()
        backend.cleanup = false
        assertFalse(connection.unregister())
        backend.lyric(snapshot)
        assertTrue(received.isEmpty())
        backend.cleanup = true
        assertTrue(connection.unregister())
        assertTrue(connection.unregister())
        assertEquals(2, backend.cleanupCount)
    }

    @Test fun closingDuringSynchronousReplayCleansUpAfterRegistrationCompletes() {
        val backend = FakeBackend()
        val received = mutableListOf<SuperLyricSnapshot>()
        val connection = SuperLyricConnection(received::add, {}, { backend })
        backend.duringRegister = {
            backend.lyric(snapshot)
            assertFalse(connection.unregister())
        }
        connection.register()
        assertFalse(connection.isActive)
        assertEquals(1, backend.cleanupCount)
    }

    @Test fun rejectedRegistrationDropsSynchronousLyricsAndStops() {
        val backend = FakeBackend().apply { result = Receiver.RegisterResult.REJECTED }
        val received = mutableListOf<SuperLyricSnapshot>()
        val stops = mutableListOf<SuperLyricSession>()
        backend.duringRegister = { backend.lyric(snapshot); backend.stop(session) }
        SuperLyricConnection(received::add, stops::add, { backend }).register()
        assertTrue(received.isEmpty())
        assertTrue(stops.isEmpty())
    }

    @Test fun thrownRegistrationDropsSynchronousLyrics() {
        val backend = FakeBackend()
        val received = mutableListOf<SuperLyricSnapshot>()
        backend.duringRegister = { backend.lyric(snapshot); error("binder failed") }
        SuperLyricConnection(received::add, {}, { backend }).register()
        assertTrue(received.isEmpty())
    }

    @Test fun bufferedEventsAndReentrantCallbacksKeepArrivalOrder() {
        val backend = FakeBackend()
        val events = mutableListOf<String>()
        val connection = SuperLyricConnection({
            events.add(it.text.orEmpty())
            if (it.text == "original") backend.lyric(snapshot.copy(text = "reentrant"))
        }, { events.add("stop") }, { backend })
        backend.duringRegister = { backend.lyric(snapshot); backend.stop(session) }
        connection.register()
        assertEquals(listOf("original", "stop", "reentrant"), events)
    }

    @Test fun latestIsReplayedForAnAdditionalListener() {
        val backend = FakeBackend().apply { replay = snapshot }
        val received = mutableListOf<SuperLyricSnapshot>()
        SuperLyricConnection(received::add, {}, { backend }).register()
        assertEquals(listOf(snapshot), received)
    }

    @Test fun newerStopPreventsStaleInitialReplay() {
        val backend = FakeBackend().apply { replay = snapshot }
        val received = mutableListOf<SuperLyricSnapshot>()
        val stops = mutableListOf<SuperLyricSession>()
        backend.duringLatest = { backend.stop(session) }
        SuperLyricConnection(received::add, stops::add, { backend }).register()
        assertTrue(received.isEmpty())
        assertEquals(listOf(session), stops)
    }

    @Test fun newerLyricPreventsStaleInitialReplay() {
        val backend = FakeBackend().apply { replay = snapshot }
        val newer = snapshot.copy(text = "new")
        val received = mutableListOf<SuperLyricSnapshot>()
        backend.duringLatest = { backend.lyric(newer) }
        SuperLyricConnection(received::add, {}, { backend }).register()
        assertEquals(listOf(newer), received)
    }

    @Test fun modernCleanupDoesNotRemoveOtherListeners() {
        val listeners = mutableSetOf<LyricListener>()
        val other = object : LyricListener {
            override fun onLyric(view: LyricView) {}
            override fun onStop(stop: Stop) {}
        }
        listeners.add(other)
        val receiver = object : Receiver {
            override fun register(listener: LyricListener): Receiver.RegisterResult {
                listeners.add(listener); return Receiver.RegisterResult.OK
            }
            override fun unregister(listener: LyricListener): Boolean {
                listeners.remove(listener); return listeners.isEmpty()
            }
            override fun isRegistered(listener: LyricListener) = listener in listeners
            override fun unregisterAll(): Boolean = error("Must never remove other listeners")
            override fun latest(): LyricView? = null
            override fun lastStop(): Stop? = null
        }
        val backend = ModernSuperLyricBackend(receiver)
        backend.register({}, {})
        assertTrue(backend.unregister())
        assertEquals(setOf(other), listeners)
    }

    private class MappingReceiver : Receiver {
        lateinit var listener: LyricListener
        override fun register(listener: LyricListener): Receiver.RegisterResult {
            this.listener = listener
            return Receiver.RegisterResult.OK
        }
        override fun unregister(listener: LyricListener) = true
        override fun unregisterAll() = error("Must only remove our listener")
        override fun isRegistered(listener: LyricListener) = false
        override fun latest(): LyricView? = null
        override fun lastStop(): Stop? = null
    }

    @Test fun modernPausedAndUnknownStateStillDeliverTextWithUnknownPublisher() {
        val receiver = MappingReceiver()
        val received = mutableListOf<SuperLyricSnapshot>()
        val stops = mutableListOf<SuperLyricSession>()
        ModernSuperLyricBackend(receiver).register(received::add, stops::add)
        val lyric = Lyric("song", "title", "", "", 0L,
            listOf(Line("original", 0L, 100L, null, "translation", null)))
        for (state in listOf(PlaybackState.PAUSED, null)) {
            receiver.listener.onLyric(LyricView(lyric, 0, 0L, 1f, state, null))
        }
        assertEquals(listOf(snapshot.copy(session = SuperLyricSession(null, "song")),
            snapshot.copy(session = SuperLyricSession(null, "song"))), received)
        assertTrue(stops.isEmpty())
    }

    @Test fun modernInvalidLineDeliversEmptySnapshotInsteadOfStop() {
        val receiver = MappingReceiver()
        val received = mutableListOf<SuperLyricSnapshot>()
        val stops = mutableListOf<SuperLyricSession>()
        ModernSuperLyricBackend(receiver).register(received::add, stops::add)
        val lyric = Lyric("song", "title", "", "", 0L, listOf(Line("line", 0L, 100L)))
        for (index in listOf(-1, 1)) {
            receiver.listener.onLyric(LyricView(lyric, index, 0L, 1f, null, "player"))
        }
        assertEquals(listOf(SuperLyricSnapshot(session, null, null),
            SuperLyricSnapshot(session, null, null)), received)
        assertTrue(stops.isEmpty())
    }

    @Test fun legacySingleLineNeedsNoFullDocumentAndKeepsDataTranslation() {
        val data = SuperLyricData().setLyric(SuperLyricLine("line").setTranslation("inline"))
            .setTranslation(SuperLyricLine("old translation"))
        val actual = LegacyLyricProjection().project("player", data)
        assertEquals("line", actual.text)
        assertEquals("old translation", actual.translation)
    }

    @Test fun legacyFullAndIndexOnlyPacketsUseMatchingDocumentAndInlineTranslation() {
        val projector = LegacyLyricProjection()
        val full = SuperLyricData().setLyricId("song").setAllLyrics(arrayOf(
            SuperLyricLine("one"), SuperLyricLine("two").setTranslation("translated two"),
        )).setCurrentLyricIndex(0)
        assertEquals("one", projector.project("player", full).text)
        val delta = SuperLyricData().setLyricId("song").setCurrentLyricIndex(1)
        assertEquals("two", projector.project("player", delta).text)
        assertEquals("translated two", projector.project("player", delta).translation)
        assertNull(projector.project("other player", delta).text)
    }

    @Test fun legacyPositionOnlyDeltaResolvesMatchingTimedDocument() {
        val projector = LegacyLyricProjection()
        val full = SuperLyricData().setLyricId("song").setAllLyrics(arrayOf(
            SuperLyricLine("one", 100L, 200L),
            SuperLyricLine("two", 200L, 300L).setTranslation("translated two"),
        )).setCurrentLyricIndex(0)
        projector.project("player", full)
        val delta = SuperLyricData().setLyricId("song").setPosition(250L)
        assertEquals("two", projector.project("player", delta).text)
        assertEquals("translated two", projector.project("player", delta).translation)
        assertNull(projector.project("player", delta.setPosition(50L)).text)
        assertEquals("one", projector.project("player", delta.setPosition(100L)).text)
        assertNull(projector.project("other player", delta).text)
        projector.project("player", full)
        assertNull(projector.project("player", delta.setLyricId("other song")).text)
    }

    @Test fun legacyNullDocumentEntriesDoNotShiftIndicesOrBreakPositionLookup() {
        val projector = LegacyLyricProjection()
        val full = SuperLyricData().setLyricId("song").setAllLyrics(arrayOf<SuperLyricLine?>(
            null, SuperLyricLine("second", 200L, 300L), null,
        )).setCurrentLyricIndex(1)
        assertEquals("second", projector.project("player", full).text)
        val index = SuperLyricData().setLyricId("song").setCurrentLyricIndex(0)
        assertNull(projector.project("player", index).text)
        assertEquals("second", projector.project("player", index.setCurrentLyricIndex(1)).text)
        val position = SuperLyricData().setLyricId("song").setPosition(250L)
        assertEquals("second", projector.project("player", position).text)
        assertNull(projector.project("player", position.setPosition(100L)).text)
    }

    @Test fun legacyExplicitIndexTakesPrecedenceOverPosition() {
        val full = SuperLyricData().setLyricId("song").setAllLyrics(arrayOf(
            SuperLyricLine("one", 100L, 200L), SuperLyricLine("two", 200L, 300L),
        )).setCurrentLyricIndex(0).setPosition(250L)
        assertEquals("one", LegacyLyricProjection().project("player", full).text)
    }

    @Test fun legacyBlankStopIdentifiersClearMatchingDocument() {
        val projector = LegacyLyricProjection()
        val full = SuperLyricData().setLyricId("song")
            .setAllLyrics(arrayOf(SuperLyricLine("line"))).setCurrentLyricIndex(0)
        projector.project("player", full)
        projector.clear(SuperLyricSession("  ", "  "))
        val delta = SuperLyricData().setLyricId("song").setCurrentLyricIndex(0)
        assertNull(projector.project("player", delta).text)
    }

    @Test fun legacyBlankTrackIdCannotReuseDocument() {
        val projector = LegacyLyricProjection()
        val full = SuperLyricData().setLyricId("  ")
            .setAllLyrics(arrayOf(SuperLyricLine("line"))).setCurrentLyricIndex(0)
        assertEquals("line", projector.project("player", full).text)
        val delta = SuperLyricData().setLyricId("  ").setCurrentLyricIndex(0)
        assertNull(projector.project("player", delta).text)
    }

    @Test fun legacyTrackChangeAndMatchingStopInvalidateDocument() {
        val projector = LegacyLyricProjection()
        val full = SuperLyricData().setLyricId("song").setAllLyrics(arrayOf(SuperLyricLine("one")))
            .setCurrentLyricIndex(0)
        projector.project("player", full)
        projector.clear(SuperLyricSession("other", "song"))
        val delta = SuperLyricData().setLyricId("song").setCurrentLyricIndex(0)
        assertEquals("one", projector.project("player", delta).text)
        projector.clear(session)
        assertNull(projector.project("player", delta).text)
        projector.project("player", full)
        assertNull(projector.project("player", delta.setLyricId("next song")).text)
    }
}
