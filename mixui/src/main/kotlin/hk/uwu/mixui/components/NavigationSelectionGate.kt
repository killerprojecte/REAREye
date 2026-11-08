package hk.uwu.mixui.components

/** Pointer intent is independent of vendor press/selection animations. */
internal class NavigationSelectionGate {
    enum class Intent { Idle, Tap, Secondary, Cancelled }
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
        Intent.Tap -> { pending = index; null }
        Intent.Secondary, Intent.Cancelled -> null
    }

    fun move(beyondSlop: Boolean, outside: Boolean, multiplePointers: Boolean) {
        if (multiplePointers || (intent == Intent.Tap && (beyondSlop || outside))) cancel()
    }

    fun openSecondary(): Boolean = if (intent == Intent.Tap) {
        intent = Intent.Secondary
        pending = null
        true
    } else false

    fun cancel() {
        if (intent != Intent.Idle) intent = Intent.Cancelled
        pending = null
    }

    /** Must run after release Main, or on disposal. Rejected native state is never reusable. */
    fun finish(): Result {
        val index = pending.takeIf { intent == Intent.Tap }
        val result = Result(index, resetNative = intent != Intent.Idle && index == null)
        intent = Intent.Idle
        pending = null
        return result
    }
}
