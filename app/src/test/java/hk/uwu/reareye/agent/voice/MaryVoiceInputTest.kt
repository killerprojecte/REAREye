package hk.uwu.reareye.agent.voice

import com.mispeech.hyperos.asr.SpeechAsrListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dictation state machine, driven through fakes: streaming partials, the finish request that
 * yields the punctuated final transcript, and every way that wait can end.
 */
class MaryVoiceInputTest {

    private class FakeSession : MaryVoiceSession {
        var listener: SpeechAsrListener? = null
        var starts = 0
        var stops = 0
        var releases = 0

        override fun start(listener: SpeechAsrListener) {
            starts++
            this.listener = listener
        }

        override fun stop() {
            stops++
        }

        override fun release() {
            releases++
        }

        fun partial(text: String) = listener?.onPartialResult(text) ?: Unit
        fun finalResult(text: String) = listener?.onFinalResult(text, null) ?: Unit
        fun finished() = listener?.onFinished() ?: Unit
        fun failed(token: String, message: String? = null) =
            listener?.onError(5, message, token) ?: Unit
    }

    private class FakeScheduler : MaryVoiceScheduler {
        private var next = 0
        private val pending = LinkedHashMap<Int, () -> Unit>()

        var lastDelay: Long = -1
        val armed: Boolean get() = pending.isNotEmpty()

        override fun postDelayed(delayMillis: Long, action: () -> Unit): Any {
            lastDelay = delayMillis
            val token = next++
            pending[token] = action
            return token
        }

        override fun cancel(token: Any) {
            pending.remove(token as Int)
        }

        fun fire() {
            val actions = pending.values.toList()
            pending.clear()
            actions.forEach { it() }
        }
    }

    private val session = FakeSession()
    private val scheduler = FakeScheduler()
    private val voice = MaryVoiceInput(session, scheduler)
    private var draft = ""

    private fun begin(initial: String = "") {
        draft = initial
        voice.start(initial) { draft = it }
    }

    @Test
    fun partialResultsStreamTheWholeDraft() {
        begin()
        session.partial("你好")
        assertEquals("你好", draft)
        session.partial("你好世界")
        assertEquals("你好世界", draft)
    }

    @Test
    fun interruptionAsksForTheFinalTranscriptAndKeepsTheSessionLive() {
        begin()
        session.partial("你好世界")
        voice.interrupt()
        assertEquals(1, session.stops)
        // Still active, so the composer cannot send a draft whose final text is still in flight.
        assertEquals(MaryVoicePhase.FINALIZING, voice.phase)
    }

    @Test
    fun theFinalTranscriptCarriesPunctuationThePartialsLack() {
        begin()
        session.partial("你好世界") // streaming text, no punctuation
        voice.interrupt()
        session.finalResult("你好，世界！") // finalised text, punctuated
        session.finished()
        assertEquals("你好，世界！", draft)
        assertEquals(MaryVoicePhase.IDLE, voice.phase)
    }

    @Test
    fun latePartialsCannotOverwriteTheDraftWhileTheFinalLands() {
        begin()
        session.partial("你好世界")
        voice.interrupt()
        session.partial("你好世界啊")
        assertEquals("你好世界", draft)
        session.finalResult("你好，世界。")
        session.finished()
        assertEquals("你好，世界。", draft)
    }

    @Test
    fun theFinalTranscriptIsAppendedToADraftThatAlreadyExisted() {
        begin("草稿：")
        session.partial("你好")
        assertEquals("草稿：你好", draft)
        voice.interrupt()
        session.finalResult("你好！")
        session.finished()
        assertEquals("草稿：你好！", draft)
    }

    @Test
    fun aWatchdogEndsTheWaitWhenTheServiceNeverAnswers() {
        begin()
        session.partial("你好")
        voice.interrupt()
        assertTrue(scheduler.armed)
        assertTrue(scheduler.lastDelay > 0)

        scheduler.fire() // the service never answered

        assertEquals(MaryVoicePhase.IDLE, voice.phase)
        assertEquals("你好", draft) // the partial text is kept rather than lost
        assertFalse(scheduler.armed)
    }

    @Test
    fun anAnswerArrivingAfterTheWatchdogIsIgnored() {
        begin()
        session.partial("你好")
        voice.interrupt()
        scheduler.fire()

        session.finalResult("你好！")
        session.finished()

        assertEquals("你好", draft)
        assertEquals(MaryVoicePhase.IDLE, voice.phase)
    }

    @Test
    fun aSuccessfulFinishDisarmsTheWatchdog() {
        begin()
        voice.interrupt()
        session.finalResult("你好！")
        session.finished()
        assertFalse("a finished session must not leave a pending watchdog", scheduler.armed)
    }

    @Test
    fun finishedAndErrorBothCloseTheSession() {
        begin()
        voice.interrupt()
        session.finished()
        assertEquals(MaryVoicePhase.IDLE, voice.phase)

        begin()
        voice.interrupt()
        session.failed("NO_SPEECH")
        assertEquals("NO_SPEECH", voice.error)
        assertEquals(MaryVoicePhase.IDLE, voice.phase)
        assertFalse(scheduler.armed)
    }

    @Test
    fun errorPrefersTheServiceMessageOverTheToken() {
        begin()
        session.failed("AUDIO", message = "音频采集异常")
        assertEquals("音频采集异常", voice.error)
    }

    @Test
    fun interruptIsInertWithoutASessionAndRepeatsStopOnlyOnce() {
        voice.interrupt()
        assertEquals(0, session.stops)

        begin()
        voice.interrupt()
        voice.interrupt()
        assertEquals(1, session.stops)
    }

    @Test
    fun startingWhileLiveIsIgnored() {
        begin()
        voice.start("again") { }
        assertEquals(1, session.starts)
    }

    @Test
    fun releaseTearsTheSessionDownAndDisarmsTheWatchdog() {
        begin()
        voice.interrupt()
        voice.release()
        assertEquals(1, session.releases)
        assertEquals(MaryVoicePhase.IDLE, voice.phase)
        assertFalse(scheduler.armed)
    }

    @Test
    fun clearErrorResetsTheSurfacedMessage() {
        begin()
        session.failed("TIMEOUT")
        assertEquals("TIMEOUT", voice.error)
        voice.clearError()
        assertNull(voice.error)
    }

    @Test
    fun startingAgainClearsAStaleError() {
        begin()
        session.failed("TIMEOUT")
        begin()
        assertNull(voice.error)
    }
}
