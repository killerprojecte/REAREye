package hk.uwu.reareye.hook.activityhost

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Screen coordinates; invalid/oversized rectangles are rejected by resolve, not clamped. */
data class ActivityHostRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Long get() = right.toLong() - left.toLong()
    val height: Long get() = bottom.toLong() - top.toLong()
}

data class ActivityHostLayout(
    val taskBounds: ActivityHostRect,
    val visibleBounds: ActivityHostRect,
    /** Full task surface in outer-local pixels (clipping does not resize the task). */
    val surfaceBounds: ActivityHostRect,
    val leftNavigationBounds: ActivityHostRect,
    val rightNavigationBounds: ActivityHostRect,
)

object ActivityHostGeometry {
    /** Bounds use content-parent-local MAML units. Null means unrepresentable or invisible. */
    fun resolve(
        outer: ActivityHostRect,
        clip: ActivityHostRect,
        mamlScale: Float,
        hostDensity: Float,
        gutterDp: Float,
        bounds: List<Float>? = null,
    ): ActivityHostLayout? {
        if (!validRect(outer) || !validRect(clip)) return null
        if (!mamlScale.isFinite() || mamlScale <= 0f ||
            !hostDensity.isFinite() || hostDensity <= 0f ||
            !gutterDp.isFinite() || gutterDp < 0f
        ) return null
        // Positive gutters reserve whole pixels; zero explicitly disables navigation strips.
        val gutter = ceil(gutterDp.toDouble() * hostDensity.toDouble())
        if (!gutter.isFinite() || gutter < 0.0 || gutter * 2.0 >= outer.width.toDouble()) return null
        val contentLeft = outer.left.toLong() + gutter.toLong()
        val contentRight = outer.right.toLong() - gutter.toLong()
        val contentWidth = contentRight - contentLeft
        val contentHeight = outer.height
        val task = if (bounds == null) {
            rect(contentLeft, outer.top.toLong(), contentRight, outer.bottom.toLong())
        } else {
            if (bounds.size != 4 || bounds.any { !it.isFinite() }) return null
            val scale = mamlScale.toDouble()
            val left = bounds[0].toDouble() * scale
            val top = bounds[1].toDouble() * scale
            val right = bounds[2].toDouble() * scale
            val bottom = bounds[3].toDouble() * scale
            // Validate before rounding: even a subpixel escape is an error.
            if (left < 0.0 || top < 0.0 || right <= left || bottom <= top ||
                right > contentWidth.toDouble() || bottom > contentHeight.toDouble()
            ) return null
            rect(
                contentLeft + floor(left).toLong(), outer.top.toLong() + floor(top).toLong(),
                contentLeft + ceil(right).toLong(), outer.top.toLong() + ceil(bottom).toLong()
            )
        } ?: return null
        if (task.width < 2L || task.height < 2L) return null
        val visible = ActivityHostRect(
            max(task.left, clip.left), max(task.top, clip.top),
            min(task.right, clip.right), min(task.bottom, clip.bottom)
        )
        if (!validRect(visible)) return null
        val surface = rect(
            task.left.toLong() - outer.left, task.top.toLong() - outer.top,
            task.right.toLong() - outer.left, task.bottom.toLong() - outer.top
        ) ?: return null
        return ActivityHostLayout(
            task, visible, surface,
            ActivityHostRect(outer.left, outer.top, contentLeft.toInt(), outer.bottom),
            ActivityHostRect(contentRight.toInt(), outer.top, outer.right, outer.bottom)
        )
    }

    private fun validRect(rect: ActivityHostRect): Boolean =
        rect.width in 1L..Int.MAX_VALUE.toLong() && rect.height in 1L..Int.MAX_VALUE.toLong()

    private fun rect(left: Long, top: Long, right: Long, bottom: Long): ActivityHostRect? {
        if (listOf(
                left,
                top,
                right,
                bottom
            ).any { it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }
        ) return null
        return ActivityHostRect(
            left.toInt(),
            top.toInt(),
            right.toInt(),
            bottom.toInt()
        ).takeIf(::validRect)
    }
}
