package hk.uwu.reareye.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.util.Collections
import java.util.IdentityHashMap

/** Fixed labels only: callers cannot accidentally pass user/provider text as a phase. */
internal enum class MaryRuntimePhase {
    INITIAL_HISTORY, CONFIGURATION, CONTEXT_ESTIMATE, DELETE_HISTORY, LOAD_HISTORY, LOCK_HISTORY,
    SEND_SETTINGS, SEND_HISTORY, CONNECTION_INIT, ENGINE_RUN, ENGINE_FAILED, CONNECTION_CLOSE, PARTIAL_SAVE
}

private val genericParameterError =
    Regex("Class declares [0-9]{1,6} type parameters, but [0-9]{1,6} were provided\\.?")

/** Never pass a Throwable to Logcat: its message/toString may contain credentials or response bodies. */
internal fun logMaryRuntime(
    phase: MaryRuntimePhase,
    error: Throwable,
    protocol: Protocol? = null,
    sink: (String) -> Unit = { Log.e("MaryRuntime", it); Unit },
) {
    if (error is CancellationException) return
    // Both formatting and the sink are best effort, including hostile Throwable accessors.
    runCatching {
        val prefix = "phase=${phase.name} protocol=${protocol?.name ?: "unknown"}"
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Pair<String, Throwable>>()
        pending.addLast("root" to error)
        var count = 0
        fun metadata(value: String) = value.take(256).replace(Regex("[\\p{Cntrl}]"), "?")
        while (pending.isNotEmpty() && count < 16) {
            val (relation, current) = pending.removeFirst()
            if (!seen.add(current)) {
                sink("$prefix $relation [cycle/shared reference]")
                continue
            }
            count++
            val safeMessage = current.message?.takeIf { genericParameterError.matches(it) }
            sink(
                "$prefix $relation type=${current.javaClass.name}${
                    safeMessage?.let { " message=$it" }.orEmpty()
                }"
            )
            val frames = current.stackTrace
            frames.take(64).forEach { frame ->
                val location = when {
                    frame.isNativeMethod -> "Native Method"
                    frame.fileName == null -> "Unknown Source"
                    else -> metadata(frame.fileName!!) + if (frame.lineNumber >= 0) ":${frame.lineNumber}" else ""
                }
                sink("$prefix at ${metadata(frame.className)}.${metadata(frame.methodName)}($location)")
            }
            if (frames.size > 64) sink("$prefix [stack truncated]")
            current.cause?.let { pending.addLast("cause[$count]" to it) }
            val suppressed = current.suppressed
            suppressed.take(16).forEachIndexed { index, child ->
                pending.addLast("suppressed[$count:$index]" to child)
            }
            if (suppressed.size > 16) sink("$prefix [suppressed truncated]")
        }
        if (pending.isNotEmpty()) sink("$prefix [throwable graph truncated]")
    }
}
