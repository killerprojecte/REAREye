@file:Suppress("DEPRECATION")

package hk.uwu.reareye.lyrics

import com.hchen.superlyricapi.ISuperLyricManager
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.Receiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LegacySuperLyricBackendTest {
    private class FakeManager : ISuperLyricManager.Stub() {
        var listener: ISuperLyricReceiver? = null
        var latest: SuperLyricData? = null
        var duringLatest: (() -> Unit)? = null
        var duringRegister: (() -> Unit)? = null
        var unregisterCount = 0
        override fun registerReceiver(receiver: ISuperLyricReceiver?) {
            listener = receiver
            duringRegister?.invoke()
        }
        override fun unregisterReceiver(receiver: ISuperLyricReceiver?) {
            unregisterCount++
            if (listener === receiver) listener = null
        }
        override fun isReceiverRegistered(receiver: ISuperLyricReceiver?) = listener === receiver
        override fun getLatestLyric(): SuperLyricData? {
            val captured = latest
            duringLatest?.invoke()
            return captured
        }
        override fun getServerApiVersion() = 36
        override fun registerPublisher() = error("Receiver must not register a publisher")
        override fun unregisterPublisher() = error("Receiver must not unregister a publisher")
        override fun isPublisherRegistered() = false
        override fun sendLyric(data: SuperLyricData?) = error("Receiver must not publish")
        override fun sendStop(data: SuperLyricData?) = error("Receiver must not publish")
        override fun setSystemPlayStateListenerEnabled(enabled: Boolean) = Unit
    }

    private fun full(id: String = "song") = SuperLyricData().setLyricId(id)
        .setAllLyrics(arrayOf(
            SuperLyricLine("one", 100L, 200L),
            SuperLyricLine("two", 200L, 300L).setTranslation("translated two"),
        )).setCurrentLyricIndex(0)

    @Test fun registrationPreservesSingleLineAndTargetedCleanupIsIdempotent() {
        val manager = FakeManager()
        val lyrics = mutableListOf<SuperLyricSnapshot>()
        val backend = LegacySuperLyricBackend(manager)
        manager.duringRegister = {
            manager.listener!!.onLyric("player", SuperLyricData()
                .setLyric(SuperLyricLine("line")).setTranslation(SuperLyricLine("translation")))
        }
        assertEquals(Receiver.RegisterResult.OK, backend.register(lyrics::add, {}))
        assertEquals(SuperLyricSnapshot(SuperLyricSession("player", null), "line", "translation"),
            lyrics.single())
        assertTrue(backend.unregister())
        assertNull(manager.listener)
        assertTrue(backend.unregister())
        assertEquals(1, manager.unregisterCount)
    }

    @Test fun stopPayloadDeliveredThroughOnLyricRoutesToStopAndClearsDocument() {
        val manager = FakeManager()
        val lyrics = mutableListOf<SuperLyricSnapshot>()
        val stops = mutableListOf<SuperLyricSession>()
        LegacySuperLyricBackend(manager).register(lyrics::add, stops::add)
        val listener = manager.listener!!
        listener.onLyric("player", full())
        assertEquals("one", lyrics.single().text)
        listener.onLyric("player", SuperLyricData().setLyricId("song"))
        assertEquals(listOf(SuperLyricSession("player", "song")), stops)
        assertEquals(1, lyrics.size)
        listener.onLyric("player", SuperLyricData().setLyricId("song").setCurrentLyricIndex(0))
        assertNull(lyrics.last().text)
    }

    @Test fun fullDocumentResolvesPositionAndIndexDeltasWithoutCrossingSession() {
        val manager = FakeManager()
        val lyrics = mutableListOf<SuperLyricSnapshot>()
        LegacySuperLyricBackend(manager).register(lyrics::add, {})
        val listener = manager.listener!!
        listener.onLyric("player", full())
        listener.onLyric("player", SuperLyricData().setLyricId("song").setPosition(250L))
        assertEquals("two", lyrics.last().text)
        assertEquals("translated two", lyrics.last().translation)
        listener.onLyric("player", SuperLyricData().setLyricId("song").setPosition(50L))
        assertNull(lyrics.last().text)
        listener.onLyric("player", SuperLyricData().setLyricId("song").setCurrentLyricIndex(0))
        assertEquals("one", lyrics.last().text)
        listener.onLyric("other", SuperLyricData().setLyricId("song").setPosition(250L))
        assertNull(lyrics.last().text)
        listener.onLyric("player", full())
        listener.onLyric("player", SuperLyricData().setLyricId("other").setPosition(250L))
        assertNull(lyrics.last().text)
    }

    @Test fun indexOnlyReplayRecoversOnlyMatchingLatestDocument() {
        val manager = FakeManager().apply { latest = full() }
        val lyrics = mutableListOf<SuperLyricSnapshot>()
        LegacySuperLyricBackend(manager).register(lyrics::add, {})
        manager.listener!!.onLyric("player", SuperLyricData().setLyricId("song").setCurrentLyricIndex(1))
        assertEquals("two", lyrics.last().text)
        manager.listener!!.onLyric("player", SuperLyricData().setLyricId("other").setCurrentLyricIndex(1))
        assertNull(lyrics.last().text)
    }

    @Test fun reusedIdAfterPublisherSwitchCannotRelabelUnattributedLatestDocument() {
        val manager = FakeManager().apply { latest = full() }
        val lyrics = mutableListOf<SuperLyricSnapshot>()
        LegacySuperLyricBackend(manager).register(lyrics::add, {})
        val listener = manager.listener!!
        listener.onLyric("player A", full())
        val delta = SuperLyricData().setLyricId("song").setCurrentLyricIndex(1)
        listener.onLyric("player B", delta)
        assertNull(lyrics.last().text)
        listener.onLyric("player B", delta)
        assertNull(lyrics.last().text)
        listener.onLyric("player B", SuperLyricData().setLyricId("song")
            .setLyric(SuperLyricLine("B direct")))
        assertEquals("B direct", lyrics.last().text)
        listener.onLyric("player B", delta)
        assertNull(lyrics.last().text)
        val bFull = SuperLyricData().setLyricId("song").setAllLyrics(arrayOf(
            SuperLyricLine("B first"), SuperLyricLine("B second"),
        )).setCurrentLyricIndex(0)
        listener.onLyric("player B", bFull)
        listener.onLyric("player B", delta)
        assertEquals("B second", lyrics.last().text)
        assertEquals("player B", lyrics.last().session.publisher)
    }

    @Test fun distinctTrackIdAllowsLatestRecoveryAfterAmbiguousPublisherSwitch() {
        val manager = FakeManager().apply { latest = full() }
        val lyrics = mutableListOf<SuperLyricSnapshot>()
        LegacySuperLyricBackend(manager).register(lyrics::add, {})
        val listener = manager.listener!!
        listener.onLyric("player A", full())
        listener.onLyric("player B", SuperLyricData().setLyricId("song").setCurrentLyricIndex(1))
        assertNull(lyrics.last().text)
        manager.latest = full("new song")
        listener.onLyric("player B", SuperLyricData().setLyricId("new song").setCurrentLyricIndex(1))
        assertEquals("two", lyrics.last().text)
    }

    @Test fun slowLatestCannotOverwriteNewerCallback() {
        val manager = FakeManager().apply { latest = full() }
        val lyrics = mutableListOf<SuperLyricSnapshot>()
        val backend = LegacySuperLyricBackend(manager)
        backend.register(lyrics::add, {})
        manager.duringLatest = { manager.listener!!.onLyric("player", full("new song")) }
        assertNull(backend.latest())
        assertEquals("new song", lyrics.single().session.lyricId)
        manager.duringLatest = null
        manager.listener!!.onLyric("player", SuperLyricData().setLyricId("new song").setCurrentLyricIndex(1))
        assertEquals("two", lyrics.last().text)
    }

    @Test fun slowIndexRecoveryCannotResurrectStoppedSession() {
        val manager = FakeManager().apply { latest = full() }
        val lyrics = mutableListOf<SuperLyricSnapshot>()
        val stops = mutableListOf<SuperLyricSession>()
        LegacySuperLyricBackend(manager).register(lyrics::add, stops::add)
        manager.duringLatest = {
            manager.listener!!.onStop("player", SuperLyricData().setLyricId("song"))
        }
        manager.listener!!.onLyric("player", SuperLyricData().setLyricId("song").setCurrentLyricIndex(1))
        assertTrue(lyrics.isEmpty())
        assertEquals(listOf(SuperLyricSession("player", "song")), stops)
    }
}
