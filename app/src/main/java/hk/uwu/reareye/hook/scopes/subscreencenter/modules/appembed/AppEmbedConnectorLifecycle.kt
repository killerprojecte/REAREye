package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

/** Main-thread generation state that rejects stale bootstrap replies across broker replacement. */
internal class AppEmbedConnectorLifecycle {
    /** Serial captured by the callback belonging to the current bootstrap request. */
    var requestSerial: Long = 0L
        private set

    /** Whether the current serial still accepts one bootstrap reply. */
    var requestInFlight: Boolean = false
        private set

    /** Closed lifecycles reject broker-ready events and future bootstrap requests. */
    var closed: Boolean = false
        private set

    /** Starts one request, or returns null when the current request is still awaiting a reply. */
    fun beginBootstrap(): Long? {
        check(!closed) { "AppEmbed connector is closed" }
        if (requestInFlight) return null
        requestSerial += 1L
        requestInFlight = true
        return requestSerial
    }

    /** Completes only the live serial; false tells the caller to reject and dispose a late reply. */
    fun finishBootstrap(serial: Long): Boolean {
        if (closed || !requestInFlight || serial != requestSerial) return false
        requestInFlight = false
        return true
    }

    /** Invalidates the old request/capability generation before reconnecting to a ready broker. */
    fun invalidateForBrokerReplacement(): Boolean {
        if (closed) return false
        requestSerial += 1L
        requestInFlight = false
        return true
    }

    /** Permanently invalidates every callback and returns false when already closed. */
    fun close(): Boolean {
        if (closed) return false
        closed = true
        requestSerial += 1L
        requestInFlight = false
        return true
    }
}
