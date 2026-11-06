@file:Suppress("DEPRECATION")

package hk.uwu.reareye.lyrics

import android.annotation.SuppressLint
import android.os.DeadObjectException
import android.os.IBinder
import com.hchen.superlyricapi.ISuperLyricManager
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.LyricListener
import com.hchen.superlyricapi.LyricView
import com.hchen.superlyricapi.Receiver
import com.hchen.superlyricapi.SuperLyric
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.model.Stop

/** One lifecycle owner. Closing suppresses callbacks immediately, even if remote cleanup fails. */
internal class SuperLyricConnection(
    private val onLyric: (SuperLyricSnapshot) -> Unit,
    private val onStop: (SuperLyricSession) -> Unit,
    private val backendFactory: () -> Backend? = ::findBackend,
) {
    internal interface Backend {
        fun register(onLyric: (SuperLyricSnapshot) -> Unit, onStop: (SuperLyricSession) -> Unit): Receiver.RegisterResult
        /** True only when this owner's listener is no longer registered. */
        fun unregister(): Boolean
        fun latest(): SuperLyricSnapshot? = null
        fun lastStop(): SuperLyricSession? = null
    }

    private val lock = Any()
    private var backend: Backend? = null
    private var started = false
    private var closed = false
    private var registering = false
    private var eventSequence = 0L
    private val pendingEvents = ArrayDeque<() -> Unit>()
    private var delivering = false
    private var result = Receiver.RegisterResult.SERVICE_UNAVAILABLE
    @Volatile var isActive = false
        private set

    fun register(): Receiver.RegisterResult {
        synchronized(lock) {
            if (closed || started) return result
            started = true
            registering = true
            // Accept synchronous callbacks, but buffer them until registration succeeds.
            isActive = true
        }
        val selected = try { backendFactory() } catch (_: Exception) { null }
        synchronized(lock) { backend = selected }
        val registered = try {
            selected?.register(::deliverLyric, ::deliverStop)
                ?: Receiver.RegisterResult.SERVICE_UNAVAILABLE
        } catch (_: Exception) {
            Receiver.RegisterResult.SERVICE_UNAVAILABLE
        }
        val replay = synchronized(lock) {
            result = registered
            registering = false
            if (registered != Receiver.RegisterResult.OK) {
                closed = true
                isActive = false
            }
            if (closed) pendingEvents.clear() else drainEvents()
            !closed && eventSequence == 0L
        }
        if (!isActive) {
            unregister()
        } else if (replay && selected != null) {
            // Additional listeners on API 4's process-wide receiver do not receive its FULL.
            // Never let a slow pull overwrite a newer lyric or STOP callback.
            val snapshot = try { selected.latest() } catch (_: Exception) { null }
            val stop = if (snapshot == null) {
                try { selected.lastStop() } catch (_: Exception) { null }
            } else null
            synchronized(lock) {
                if (isActive && eventSequence == 0L) {
                    if (snapshot != null) deliverLyric(snapshot)
                    else if (stop != null) deliverStop(stop)
                }
            }
        }
        return registered
    }

    private fun deliverLyric(snapshot: SuperLyricSnapshot) = enqueue { onLyric(snapshot) }

    private fun deliverStop(session: SuperLyricSession) = enqueue { onStop(session) }

    private fun enqueue(event: () -> Unit) = synchronized(lock) {
        if (isActive) {
            eventSequence++
            pendingEvents.addLast(event)
            if (!registering) drainEvents()
        }
    }

    /** Called under lock. Reentrant callbacks join the queue behind older accepted events. */
    private fun drainEvents() {
        if (delivering) return
        delivering = true
        try {
            while (isActive && pendingEvents.isNotEmpty()) pendingEvents.removeFirst().invoke()
        } finally {
            delivering = false
        }
    }

    fun unregister(): Boolean {
        val selected = synchronized(lock) {
            closed = true
            isActive = false
            pendingEvents.clear()
            // Registration may still be adding the listener. Its completion retries cleanup.
            if (registering) return false
            backend ?: return true
        }
        val removed = try { selected.unregister() } catch (_: Exception) { false }
        if (removed) synchronized(lock) {
            if (backend === selected) backend = null
        }
        return removed
    }

    companion object {
        @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
        private fun findBackend(): Backend? {
            val getService = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
            // Select exactly one transport. A v4 rejection must not register us on 3.x too.
            if (getService.invoke(null, "super_lyric.v4") != null) {
                return ModernSuperLyricBackend(SuperLyric.receiver())
            }
            val binder = getService.invoke(null, "super_lyric") as? IBinder ?: return null
            return LegacySuperLyricBackend(ISuperLyricManager.Stub.asInterface(binder))
        }
    }
}

internal class ModernSuperLyricBackend(private val receiver: Receiver) : SuperLyricConnection.Backend {
    private var listener: LyricListener? = null

    override fun register(onLyric: (SuperLyricSnapshot) -> Unit, onStop: (SuperLyricSession) -> Unit): Receiver.RegisterResult {
        val next = object : LyricListener {
            override fun onLyric(view: LyricView) = onLyric(view.toSnapshot())
            override fun onStop(stop: Stop) = onStop(SuperLyricSession(stop.publisher, stop.id))
        }
        listener = next
        return receiver.register(next)
    }

    override fun unregister(): Boolean {
        val current = listener ?: return true
        receiver.unregister(current)
        // false means that OTHER listeners still own the shared transport, not a failure.
        val removed = !receiver.isRegistered(current)
        if (removed) listener = null
        return removed
    }

    override fun latest() = receiver.latest()?.toSnapshot()
    override fun lastStop() = receiver.lastStop()?.let { SuperLyricSession(it.publisher, it.id) }
}

private fun LyricView.toSnapshot() = SuperLyricSnapshot(
    SuperLyricSession(publisher, lyric.id), line?.text, line?.translation,
)

/** API 4's built-in legacy adapter drops single-line-only 3.x packets, so keep the frozen ABI here. */
internal class LegacySuperLyricBackend(private val manager: ISuperLyricManager) : SuperLyricConnection.Backend {
    private var listener: ISuperLyricReceiver.Stub? = null
    private val lock = Any()
    private val projection = LegacyLyricProjection()
    private var sequence = 0L

    override fun register(onLyric: (SuperLyricSnapshot) -> Unit, onStop: (SuperLyricSession) -> Unit): Receiver.RegisterResult {
        val next = object : ISuperLyricReceiver.Stub() {
            override fun onLyric(publisher: String?, data: SuperLyricData?) {
                if (data == null) return
                if (data.isStopPayload) {
                    onStop(publisher, data)
                    return
                }
                val (version, initial, canRecover) = synchronized(lock) {
                    val snapshot = projection.project(publisher, data)
                    Triple(++sequence, snapshot, projection.canRecoverLatest)
                }
                // A legacy service can replay only an index delta. Recover the matching document.
                val full = if (canRecover && initial.text == null && data.currentLyricIndex >= 0 && data.hasLyricId()) {
                    try { manager.latestLyric } catch (_: Exception) { null }
                } else null
                synchronized(lock) {
                    if (sequence != version) return
                    if (full != null && full.lyricId == data.lyricId && full.hasAllLyrics()) {
                        projection.project(publisher, full)
                    }
                    onLyric(projection.project(publisher, data))
                }
            }

            override fun onStop(publisher: String?, data: SuperLyricData?) {
                synchronized(lock) {
                    ++sequence
                    projection.clear(SuperLyricSession(publisher, data?.lyricId))
                    onStop(SuperLyricSession(publisher, data?.lyricId))
                }
            }
        }
        listener = next
        manager.registerReceiver(next)
        return if (manager.isReceiverRegistered(next)) Receiver.RegisterResult.OK else Receiver.RegisterResult.REJECTED
    }

    override fun unregister(): Boolean {
        val current = listener ?: return true
        val binder = manager.asBinder()
        val removed = if (!binder.isBinderAlive) {
            true
        } else try {
            manager.unregisterReceiver(current)
            !manager.isReceiverRegistered(current)
        } catch (_: DeadObjectException) {
            // A dead service cannot retain this listener; do not block future registration.
            true
        } catch (failure: Exception) {
            if (!binder.isBinderAlive) true else throw failure
        }
        if (removed) listener = null
        return removed
    }

    override fun latest(): SuperLyricSnapshot? {
        val version = synchronized(lock) { sequence }
        val data = manager.latestLyric ?: return null
        return synchronized(lock) {
            if (sequence != version || data.isStopPayload) null else projection.project(null, data)
        }
    }
}

/** Cache full legacy documents only for an identified session; never reuse anonymous track data. */
internal class LegacyLyricProjection {
    private var session: SuperLyricSession? = null
    private var lines: Array<out SuperLyricLine?>? = null
    private var ambiguousLyricId: String? = null

    // latestLyric carries no publisher. A reused ID after a publisher switch cannot identify it.
    val canRecoverLatest: Boolean
        get() = !session?.lyricId.isNullOrBlank() && session?.lyricId != ambiguousLyricId

    fun project(publisher: String?, data: SuperLyricData): SuperLyricSnapshot {
        val incoming = SuperLyricSession(publisher, data.lyricId)
        val previous = session
        if (!incoming.lyricId.isNullOrBlank() && incoming.lyricId != ambiguousLyricId) ambiguousLyricId = null
        if (!incoming.lyricId.isNullOrBlank() && incoming.lyricId == previous?.lyricId &&
            !incoming.publisher.isNullOrBlank() && !previous?.publisher.isNullOrBlank() &&
            incoming.publisher != previous?.publisher) {
            ambiguousLyricId = incoming.lyricId
        }
        if (incoming != session || incoming.lyricId.isNullOrBlank()) lines = null
        session = incoming
        if (data.hasAllLyrics()) {
            lines = data.allLyrics
            if (!incoming.publisher.isNullOrBlank()) ambiguousLyricId = null
        }
        val index = if (data.hasCurrentLyricIndex()) data.currentLyricIndex
            else if (data.hasPosition()) lines?.indexOfLast { it != null && it.startTime <= data.position } ?: -1
            else -1
        val line = data.lyric ?: data.getLyricAt(index) ?: lines?.getOrNull(index)
        return SuperLyricSnapshot(incoming, line?.text, data.translation?.text ?: line?.translation)
    }

    fun clear(stopped: SuperLyricSession) {
        val current = session ?: return
        if (current.acceptsStop(stopped)) {
            session = null
            lines = null
        }
    }
}
