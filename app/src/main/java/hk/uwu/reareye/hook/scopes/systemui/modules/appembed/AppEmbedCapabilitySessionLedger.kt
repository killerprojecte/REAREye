package hk.uwu.reareye.hook.scopes.systemui.modules.appembed

/**
 * Tracks the sessions minted by one private AppEmbed capability without retaining terminal IDs.
 *
 * The capability namespace is encoded into every positive session ID. The monotonically increasing
 * local sequence proves that an ID was issued by this capability even after its active session has
 * terminated, which makes client release idempotent without an unbounded tombstone collection.
 */
internal class AppEmbedCapabilitySessionLedger(
    private val capabilityNamespace: Long,
) {
    private val lock = Any()
    private val activeSessionIds = LinkedHashSet<Long>()
    private var issuedSequence = 0L
    private var closed = false

    init {
        require(capabilityNamespace in 1L..MAX_CAPABILITY_NAMESPACE) {
            "AppEmbed capability namespace must fit in 31 positive bits"
        }
    }

    /** Atomically issues and records the next active session, unless disposal already won the race. */
    fun issueSessionId(): Long = synchronized(lock) {
        check(!closed) { "AppEmbed capability is closed" }
        check(issuedSequence < MAX_SESSION_SEQUENCE) {
            "AppEmbed capability exhausted its session ID sequence"
        }
        issuedSequence += 1L
        val sessionId = (capabilityNamespace shl SESSION_SEQUENCE_BITS) or issuedSequence
        check(activeSessionIds.add(sessionId)) { "AppEmbed session ID was issued twice" }
        sessionId
    }

    /**
     * Verifies that an ID belongs to this capability's already-issued range.
     *
     * Terminal IDs deliberately remain valid so repeated release and release after asynchronous
     * server rejection are idempotent. IDs from another capability still fail immediately.
     */
    fun requireIssuedSession(sessionId: Long) {
        synchronized(lock) {
            require(ownsIssuedSessionLocked(sessionId)) {
                "Session $sessionId does not belong to this AppEmbed capability"
            }
        }
    }

    /**
     * Atomically verifies issued ownership and reports whether the owned session remains active.
     *
     * Foreign and never-issued IDs fail immediately. Terminal or disposed owned IDs return false
     * so asynchronous state commands can be discarded without turning a normal race into a Binder
     * exception.
     */
    fun requireOwnedSessionAndCheckActive(sessionId: Long): Boolean = synchronized(lock) {
        require(ownsIssuedSessionLocked(sessionId)) {
            "Session $sessionId does not belong to this AppEmbed capability"
        }
        sessionId in activeSessionIds
    }

    /** Returns whether a previously issued ID still has work routable in this capability. */
    fun isActiveSession(sessionId: Long): Boolean = synchronized(lock) {
        ownsIssuedSessionLocked(sessionId) && sessionId in activeSessionIds
    }

    /** Removes only the active routing entry while preserving encoded issued ownership. */
    fun markTerminal(sessionId: Long) {
        synchronized(lock) {
            require(ownsIssuedSessionLocked(sessionId)) {
                "Terminal session $sessionId does not belong to this AppEmbed capability"
            }
            activeSessionIds.remove(sessionId)
        }
    }

    /** Reports whether capability disposal has permanently prevented further session issuance. */
    fun isClosed(): Boolean = synchronized(lock) { closed }

    /**
     * Atomically closes issuance and transfers all currently active IDs to the disposal caller.
     *
     * A null result means a previous disposal already performed the transfer.
     */
    fun closeAndTakeActiveSessions(): List<Long>? = synchronized(lock) {
        if (closed) return@synchronized null
        closed = true
        activeSessionIds.toList().also { activeSessionIds.clear() }
    }

    private fun ownsIssuedSessionLocked(sessionId: Long): Boolean {
        if (sessionId <= 0L) return false
        val namespace = sessionId ushr SESSION_SEQUENCE_BITS
        val sequence = sessionId and MAX_SESSION_SEQUENCE
        return namespace == capabilityNamespace && sequence in 1L..issuedSequence
    }

    companion object {
        /** Largest namespace that keeps the signed session ID positive. */
        const val MAX_CAPABILITY_NAMESPACE = 0x7fff_ffffL

        private const val SESSION_SEQUENCE_BITS = 32
        private const val MAX_SESSION_SEQUENCE = 0xffff_ffffL
    }
}
