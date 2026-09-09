package hk.uwu.reareye.hook.scopes.subscreencenter.modules.appembed

/** Delays initial task creation until card geometry stops changing, without retrying failed tasks. */
internal class AppEmbedLaunchLayout<T>(private val settleMillis: Long = 100L) {
    private var candidate: T? = null
    private var changedAt = 0L

    init {
        require(settleMillis > 0L)
    }

    /** Returns the remaining quiet interval for this geometry, using monotonic uptime. */
    fun remainingMillis(layout: T, now: Long): Long {
        if (candidate != layout) {
            candidate = layout
            changedAt = now
        }
        return (settleMillis - (now - changedAt)).coerceAtLeast(0L)
    }

    /** A new activation must establish its own stable geometry. */
    fun reset() {
        candidate = null
    }
}
