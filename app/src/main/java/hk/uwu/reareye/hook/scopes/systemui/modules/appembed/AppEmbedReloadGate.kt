package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

/**
 * Reversible in-memory gate used while the global hook runtime prepares a hot reload.
 *
 * The broker owns deferred work separately so this class only defines the permitted transitions
 * and whether incoming main-thread work must run, wait, or be discarded after final close.
 */
internal class AppEmbedReloadGate {
    private val lock = Any()
    private var state = State.OPEN

    /** Fences work after the broker has independently established that cleanup is currently safe. */
    fun prepare(): Boolean = synchronized(lock) {
        when (state) {
            State.OPEN -> {
                state = State.PREPARED
                true
            }

            State.PREPARED -> true
            State.CLOSED -> false
        }
    }

    /** Returns the routing decision for newly arrived creation or task-launch work. */
    fun route(): Route = synchronized(lock) {
        when (state) {
            State.OPEN -> Route.RUN_NOW
            State.PREPARED -> Route.DEFER
            State.CLOSED -> Route.DISCARD
        }
    }

    /** Reopens a prepared broker; false means it was never prepared or was already closed. */
    fun rollback(): Boolean = synchronized(lock) {
        if (state != State.PREPARED) return@synchronized false
        state = State.OPEN
        true
    }

    /** Permanently prevents old-generation work from being resumed. */
    fun close() {
        synchronized(lock) { state = State.CLOSED }
    }

    enum class Route {
        /** Execute immediately in the active generation. */
        RUN_NOW,

        /** Retain in generation-owned memory until commit or rollback is decided. */
        DEFER,

        /** Reject or drop work because this broker generation has closed. */
        DISCARD,
    }

    private enum class State {
        OPEN,
        PREPARED,
        CLOSED,
    }
}
