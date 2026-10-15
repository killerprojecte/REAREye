package hk.uwu.reareye.hook.activityhost

/** Immutable identity: dpi cannot change within a generation. */
data class ActivityHostSession(val owner: String, val generation: Long, val dpi: Int)

enum class ActivityHostPhase { IDLE, STARTING, ACTIVE, HIDDEN, FULLSCREEN, FAILED, RELEASED }

data class ActivityHostSnapshot(
    val session: ActivityHostSession?,
    val phase: ActivityHostPhase,
    val sequence: Long,
    /** Opaque identity only. Resource disposal is the caller's responsibility. */
    val surface: Any?,
    val failure: String?,
)

/** Thread-safe pure state gate; it never performs IPC or starts activities itself. */
class ActivityHostState {
    private var generation = 0L
    private var session: ActivityHostSession? = null
    private var phase = ActivityHostPhase.IDLE
    private var sequence = -1L
    private var surface: Any? = null
    private var failure: String? = null

    @Synchronized
    fun snapshot(): ActivityHostSnapshot =
        ActivityHostSnapshot(session, phase, sequence, surface, failure)

    /** Terminal sessions require explicit user intent; lifecycle callbacks cannot revive them. */
    @Synchronized
    fun begin(owner: String, dpi: Int = 0, explicit: Boolean = false): ActivityHostSession? {
        if (owner.isBlank() || !ActivityHostSpec.isValidDpi(dpi) || generation == Long.MAX_VALUE) return null
        if (phase == ActivityHostPhase.STARTING || phase == ActivityHostPhase.ACTIVE) return null
        if (phase != ActivityHostPhase.IDLE && !explicit) return null
        generation += 1L
        return ActivityHostSession(owner, generation, dpi).also {
            session = it
            phase = ActivityHostPhase.STARTING
            sequence = -1L
            surface = null
            failure = null
        }
    }

    @Synchronized
    fun activate(session: ActivityHostSession, sequence: Long): Boolean {
        if (phase != ActivityHostPhase.STARTING || !accept(session, sequence)) return false
        phase = ActivityHostPhase.ACTIVE
        return true
    }

    /** Snapshot the previous surface before replacing it if it needs disposal. */
    @Synchronized
    fun replaceSurface(session: ActivityHostSession, sequence: Long, surface: Any?): Boolean {
        if (!isLive() || !accept(session, sequence)) return false
        this.surface = surface
        return true
    }

    @Synchronized
    fun hide(session: ActivityHostSession, sequence: Long): Boolean =
        stop(session, sequence, ActivityHostPhase.HIDDEN)

    @Synchronized
    fun fullscreen(session: ActivityHostSession, sequence: Long): Boolean =
        stop(session, sequence, ActivityHostPhase.FULLSCREEN)

    @Synchronized
    fun fail(session: ActivityHostSession, sequence: Long, reason: String): Boolean {
        if (!stop(session, sequence, ActivityHostPhase.FAILED)) return false
        failure = reason
        return true
    }

    /** Repeating the accepted release is harmless; older sequences and generations still fail. */
    @Synchronized
    fun release(session: ActivityHostSession, sequence: Long): Boolean {
        if (this.session != session || sequence < 0L) return false
        if (phase == ActivityHostPhase.RELEASED) {
            if (sequence < this.sequence) return false
            this.sequence = sequence
            return true
        }
        if (!accept(session, sequence)) return false
        surface = null
        phase = ActivityHostPhase.RELEASED
        return true
    }

    private fun isLive(): Boolean =
        phase == ActivityHostPhase.STARTING || phase == ActivityHostPhase.ACTIVE

    private fun stop(
        session: ActivityHostSession,
        sequence: Long,
        target: ActivityHostPhase
    ): Boolean {
        if (!isLive() || !accept(session, sequence)) return false
        surface = null
        phase = target
        return true
    }

    private fun accept(session: ActivityHostSession, sequence: Long): Boolean {
        if (this.session != session || sequence < 0L || sequence <= this.sequence) return false
        this.sequence = sequence
        return true
    }
}
