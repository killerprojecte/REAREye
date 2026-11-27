package hk.uwu.mixui.components

/**
 * Physical scroll distance for the material ramp. The first item's pixel offset is
 * exact. Once that item has been recycled, the measured visible item span supplies
 * an estimate; the native 32/48dp ramps normally saturate before that point.
 * This is never an invented sentinel offset for a boolean "scrolled" flag.
 */
internal fun miuixListScrollDistance(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    averageVisibleItemSpan: Float,
): Float = firstVisibleItemScrollOffset.coerceAtLeast(0).toFloat() +
    firstVisibleItemIndex.coerceAtLeast(0) * averageVisibleItemSpan.coerceAtLeast(0f)

/** Include real title displacement (also on short lists) and native elastic motion. */
internal fun miuixPageVisualOffset(
    listScrollDistance: Float,
    heightOffset: Float,
    overscrollOffset: Float,
): Float = -listScrollDistance.coerceAtLeast(0f) + heightOffset.coerceAtMost(0f) + overscrollOffset

/** A positioning event gate, not an idle-frame expand/collapse synchronizer. */
internal class MiuixPagePositionRestoreGate {
    private var previous: Pair<Int, Int>? = null
    private var pendingProgrammaticPosition = false

    fun observe(
        index: Int,
        offset: Int,
        scrolling: Boolean,
        hasItems: Boolean,
        nativeGesture: Boolean,
        canScrollBackward: Boolean,
    ): Boolean {
        if (!hasItems) return false
        val position = index to offset
        val initialRestore = previous == null && canScrollBackward
        val changed = previous != null && previous != position
        previous = position
        if (nativeGesture) {
            pendingProgrammaticPosition = false
        } else if (changed || initialRestore) {
            pendingProgrammaticPosition = true
        }
        return if (!nativeGesture && !scrolling && pendingProgrammaticPosition) {
            pendingProgrammaticPosition = false
            true
        } else false
    }
}
