package hk.uwu.mixui.components

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** Pixel geometry shared by the native MIUIX indicator and its gesture adapter. */
internal data class MiuixGlassTabGeometry(
    val tabCount: Int,
    val innerWidth: Float,
    val gap: Float,
) {
    val slotWidth: Float = if (tabCount > 0) {
        ((innerWidth - gap * (tabCount - 1)) / tabCount).coerceAtLeast(0f)
    } else 0f
    val stride: Float = slotWidth + gap
    val maxOffset: Float = ((tabCount - 1).coerceAtLeast(0) * stride).coerceAtLeast(0f)
    val canDrag: Boolean get() = tabCount > 1 && slotWidth > 0f

    fun offsetFor(index: Int): Float = if (tabCount == 0) 0f else {
        index.coerceIn(0, tabCount - 1) * stride
    }

    fun move(offset: Float, physicalDelta: Float, rtl: Boolean): Float =
        (offset + if (rtl) -physicalDelta else physicalDelta).coerceIn(0f, maxOffset)

    fun target(offset: Float): Int? = if (tabCount <= 0 || slotWidth <= 0f) null else {
        (offset / stride).roundToInt().coerceIn(0, tabCount - 1)
    }
}

/**
 * One pointer session. No selection is produced by move(): forms only change on finish().
 * A vertical winner remains rejected for the rest of the session, even if it later turns.
 */
internal class MiuixGlassTabDrag(
    private val geometry: MiuixGlassTabGeometry,
    private val selectedIndex: Int,
    private val initialOffset: Float,
    private val touchSlop: Float,
    private val rtl: Boolean,
) {
    private var totalX = 0f
    private var totalY = 0f
    private var rejected = !geometry.canDrag
    var previewOffset: Float? = null
        private set
    val isDragging: Boolean get() = previewOffset != null && !rejected
    val isRejected: Boolean get() = rejected

    fun move(deltaX: Float, deltaY: Float) {
        if (rejected) return
        if (isDragging) {
            previewOffset = geometry.move(previewOffset!!, deltaX, rtl)
            return
        }
        totalX += deltaX
        totalY += deltaY
        // Check vertical first so a diagonal tie never steals a sheet's vertical drag.
        if (abs(totalY) > touchSlop && abs(totalY) >= abs(totalX)) {
            cancel()
        } else if (abs(totalX) > touchSlop && abs(totalX) > abs(totalY)) {
            previewOffset = geometry.move(initialOffset, totalX - sign(totalX) * touchSlop, rtl)
        }
    }

    fun finish(): Int? {
        val target = if (isDragging) geometry.target(previewOffset!!) else null
        rejected = true // A second finish cannot submit a second selection.
        return target?.takeIf { it != selectedIndex }
    }

    fun cancel() {
        rejected = true
        previewOffset = null
    }
}
