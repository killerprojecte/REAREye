package hk.uwu.reareye.agent.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.mispeech.hyperos.asr.SpeechAsrConfig
import com.mispeech.hyperos.asr.SpeechAsrListener
import com.mispeech.hyperos.asr.SpeechAsrManager

/** Lifecycle of one dictation session, as the composer has to render it. */
internal enum class MaryVoicePhase {
    /** No session; the composer is free to send or start dictating. */
    IDLE,

    /** Recording, streaming partial text into the draft. */
    RECORDING,

    /** Recording stopped; waiting for the punctuated final transcript. Sending is blocked. */
    FINALIZING,
}

/**
 * Bridges the HyperOS speech-assistant ASR service (via MiSpeech) into the Mary composer.
 *
 * The service records and recognises in its own process, so this side only says start/finish and
 * receives live text. Results are pushed through [start]'s callback as the *whole* draft, because
 * recognition keeps rewriting the same utterance as it hears more of it.
 *
 * Ending a session is a two-phase affair. Streaming partials arrive unpunctuated, so "finish" asks
 * the service to end the session normally ([MaryVoiceSession.stop]) and waits for its final
 * transcript, which carries the punctuation. Until that lands (or the watchdog gives up) the
 * session still counts as live, which keeps the composer on the dictation affordance instead of
 * letting a draft be sent before its final text has arrived.
 */
@Stable
internal class MaryVoiceInput(
    private val session: MaryVoiceSession,
    private val scheduler: MaryVoiceScheduler = MainThreadScheduler(),
) {

    var phase by mutableStateOf(MaryVoicePhase.IDLE)
        private set

    /** Last failure message, surfaced once by the UI and then cleared. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Draft that existed when the session began; recognised text is appended to it. */
    private var prefix = ""
    private var emit: (String) -> Unit = {}
    private var watchdog: Any? = null

    private val listener = object : SpeechAsrListener {
        override fun onPartialResult(text: String?) {
            // Once finishing, late partials only flicker; the final transcript is authoritative.
            if (phase != MaryVoicePhase.RECORDING || text.isNullOrEmpty()) return
            emit(prefix + text)
        }

        override fun onFinalResult(text: String?, extraJson: String?) {
            if (phase == MaryVoicePhase.IDLE || text.isNullOrEmpty()) return
            emit(prefix + text)
        }

        override fun onError(code: Int, message: String?, token: String?) {
            // The message reaches the user through [error]; it already carries token/code text.
            error = message?.takeIf { it.isNotBlank() } ?: token ?: code.toString()
            finish()
        }

        override fun onFinished() {
            finish()
        }
    }

    /**
     * Begin a live recognition session. [prefix] is the draft already in the field and [emit]
     * receives each rewrite of the whole draft. Starting while a session is live is a no-op.
     */
    fun start(prefix: String, emit: (String) -> Unit) {
        if (phase != MaryVoicePhase.IDLE) return
        this.prefix = prefix
        this.emit = emit
        error = null
        phase = MaryVoicePhase.RECORDING
        session.start(listener)
    }

    /**
     * Stop recording and ask the service for the final transcript, which unlike the streaming
     * partials carries punctuation. The final text is applied when it arrives; a watchdog ends the
     * wait if the service never answers, so the composer can never stay stuck on the spinner.
     */
    fun interrupt() {
        if (phase != MaryVoicePhase.RECORDING) return
        phase = MaryVoicePhase.FINALIZING
        // Arm the watchdog before stopping, so an answer that lands synchronously still disarms it.
        watchdog = scheduler.postDelayed(FinalizeTimeoutMillis) {
            if (phase == MaryVoicePhase.FINALIZING) finish()
        }
        session.stop()
    }

    fun clearError() {
        error = null
    }

    fun release() {
        cancelWatchdog()
        phase = MaryVoicePhase.IDLE
        session.release()
    }

    private fun finish() {
        cancelWatchdog()
        phase = MaryVoicePhase.IDLE
    }

    private fun cancelWatchdog() {
        watchdog?.let { scheduler.cancel(it) }
        watchdog = null
    }

    private companion object {
        /** How long to wait for the final transcript before keeping the partial text as-is. */
        const val FinalizeTimeoutMillis = 3_000L
    }
}

/** The slice of the ASR bridge [MaryVoiceInput] drives; faked in tests. */
internal interface MaryVoiceSession {
    fun start(listener: SpeechAsrListener)
    fun stop()
    fun release()
}

/** Delayed callbacks, so the finalize watchdog is testable without a Looper. */
internal interface MaryVoiceScheduler {
    /** Run [action] after [delayMillis]; returns a token for [cancel]. */
    fun postDelayed(delayMillis: Long, action: () -> Unit): Any
    fun cancel(token: Any)
}

private class SpeechAsrManagerSession(context: Context) : MaryVoiceSession {
    private val appContext = context.applicationContext
    private val manager = SpeechAsrManager(appContext)
    private val config = SpeechAsrConfig().setFromApp(appContext.packageName)

    override fun start(listener: SpeechAsrListener) {
        manager.start(config, listener)
    }

    override fun stop() {
        manager.stop()
    }

    override fun release() {
        manager.release()
    }
}

private class MainThreadScheduler : MaryVoiceScheduler {
    private val handler = Handler(Looper.getMainLooper())

    override fun postDelayed(delayMillis: Long, action: () -> Unit): Any {
        val runnable = Runnable { action() }
        handler.postDelayed(runnable, delayMillis)
        return runnable
    }

    override fun cancel(token: Any) {
        handler.removeCallbacks(token as Runnable)
    }
}

/** Owns one [MaryVoiceInput] for the lifetime of the composition and releases the service on exit. */
@Composable
internal fun rememberMaryVoiceInput(): MaryVoiceInput {
    val context = LocalContext.current
    val voice = remember(context) { MaryVoiceInput(SpeechAsrManagerSession(context)) }
    DisposableEffect(voice) {
        onDispose { voice.release() }
    }
    return voice
}
