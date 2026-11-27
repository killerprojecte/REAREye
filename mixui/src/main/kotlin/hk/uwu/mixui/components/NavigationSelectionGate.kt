package hk.uwu.mixui.components

/** Pointer intent is independent of vendor press/selection animations. */
internal class NavigationSelectionGate {
    enum class Intent { Idle, Tap, AwaitingHorizontal, Drag, Secondary, Cancelled }
    data class Result(val selectedIndex: Int?, val resetNative: Boolean)
    var intent = Intent.Idle
        private set
    private var pending: Int? = null

    fun begin() {
        intent = Intent.Tap
        pending = null
    }

    /** Keyboard and accessibility callbacks are immediate only outside a pointer sequence. */
    fun select(index: Int): Int? = when (intent) {
        Intent.Idle -> index
        Intent.Tap, Intent.Drag -> { pending = index; null }
        Intent.AwaitingHorizontal, Intent.Secondary, Intent.Cancelled -> null
    }

    fun move(beyondSlop: Boolean, outside: Boolean, multiplePointers: Boolean, horizontalDrag: Boolean = false) {
        val navigating = intent == Intent.Tap || intent == Intent.AwaitingHorizontal || intent == Intent.Drag
        if (multiplePointers || (navigating && outside)) cancel()
        else if ((intent == Intent.Tap || intent == Intent.AwaitingHorizontal) && beyondSlop) {
            if (horizontalDrag) intent = Intent.Drag
            else {
                // A curved thumb path can cross vertical slop before turning along the bar.
                // Drop click/hold ownership now, but let an in-bounds horizontal move take
                // over later. This is not Cancelled: bounds, extra pointers and owner/system
                // cancellation remain terminal and can never be recovered here.
                intent = Intent.AwaitingHorizontal
                pending = null
            }
        }
    }

    fun openSecondary(): Boolean = if (intent == Intent.Tap) {
        intent = Intent.Secondary
        pending = null
        true
    } else false

    /** Only the adapter may reclaim a held menu for an in-bar, other-tab drag.
     * Hard cancellation is terminal; this never revives Cancelled or Idle.
     */
    fun resumeNavigationDrag(): Boolean = if (intent == Intent.Secondary) {
        intent = Intent.Drag
        pending = null
        true
    } else false

    fun cancel() {
        if (intent != Intent.Idle) intent = Intent.Cancelled
        pending = null
    }

    /** Must run after release Main, or on disposal. Rejected native state is never reusable. */
    fun finish(): Result {
        val index = pending.takeIf { (intent == Intent.Tap || intent == Intent.Drag) }
        val result = Result(index, resetNative = intent != Intent.Idle && index == null)
        intent = Intent.Idle
        pending = null
        return result
    }
}
