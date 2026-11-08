// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

/** 弹出层最终摆在哪：[upward] = 放不下，翻到锚点上面 */
internal data class PopoverPlacement(val rect: Rect, val upward: Boolean)

/**
 * 按内容尺寸算弹出层的位置（[computeTarget] 的「任意内容」版）：
 * - 默认在锚点下面隔 [gap]；下面放不下、上面更宽裕时翻到上面
 * - [cover] 时从锚点所在位置铺开（iOS 26 按钮「长成」面板），不留间距
 * - 高度最多到状态栏 / 导航栏内侧 [margin]；横向按 [align] 对齐锚点，再夹在屏幕边距里
 */
internal fun popoverPlacement(
    anchor: Rect,
    container: Size,
    width: Float,
    contentHeight: Float,
    gap: Float,
    margin: Float,
    safeTop: Float,
    safeBottom: Float,
    cover: Boolean,
    align: MochiAlign,
): PopoverPlacement {
    val w = min(width, container.width - margin * 2f)
    val floor = container.height - safeBottom - margin
    val ceil = safeTop + margin
    val below = if (cover) floor - anchor.top else floor - anchor.bottom - gap
    val above = if (cover) anchor.bottom - ceil else anchor.top - gap - ceil
    val upward = below < contentHeight && above > below
    val h = min(contentHeight, max(if (upward) above else below, 0f))
    var left = when (align) {
        MochiAlign.Start -> anchor.left
        MochiAlign.Center -> anchor.center.x - w / 2f
        MochiAlign.End -> anchor.right - w
    }
    left = left.coerceIn(margin, max(margin, container.width - margin - w))
    val top = when {
        cover && upward -> anchor.bottom - h
        cover -> anchor.top
        upward -> anchor.top - gap - h
        else -> anchor.bottom + gap
    }
    return PopoverPlacement(Rect(left, top, left + w, top + h), upward)
}

/** 小尖角的横向位置：对准锚点中心，但不进面板圆角 */
internal fun popoverArrowX(anchor: Rect, panel: Rect, cornerRadius: Float, arrowHalfWidth: Float): Float {
    val lo = panel.left + cornerRadius + arrowHalfWidth
    val hi = panel.right - cornerRadius - arrowHalfWidth
    return if (lo > hi) panel.center.x else anchor.center.x.coerceIn(lo, hi)
}
