// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.list

import androidx.compose.ui.geometry.Rect
import kotlin.math.min

/** 只在容器内分配操作槽位；弹簧的越界进度不参与尺寸插值，收起后也不会产生负宽度。 */
internal fun swipeActionRect(
    rowWidth: Float,
    rowHeight: Float,
    revealed: Float,
    count: Int,
    index: Int,
    progress: Float,
    inset: Float,
    gap: Float,
    fromEnd: Boolean,
    slotWidth: Float = 76f,
): Rect {
    if (count <= 0 || rowWidth <= 0f || rowHeight <= 0f) return Rect.Zero
    val t = progress.coerceIn(0f, 1f)
    val edge = min(inset, rowHeight / 2f)
    val r = revealed.coerceIn(0f, rowWidth)
    val space = (r - 2f * edge).coerceAtLeast(0f)
    val between = min(gap * (1f - t), space / count)
    val share = (space - between * (count - 1)).coerceAtLeast(0f) / count
    val first = share * (1f + (count - 1) * t)
    val other = share * (1f - t)
    val width = if (index == 0) first else other
    val start = edge + if (index == 0) 0f else first + between + (index - 1) * (other + between)
    val fullHeight = (rowHeight - 2f * edge).coerceAtLeast(0f) * if (index == 0) 1f else (1f - t)
    // 初露出时从小圆点长出；完整打开后贴齐三侧 8dp，大字号的高行也不再留下偏大的上下空隙。
    val open = ((r / (count * slotWidth).coerceAtLeast(1f) - 0.75f) / 0.25f).coerceIn(0f, 1f)
    val dotHeight = min(fullHeight, width)
    val height = dotHeight + (fullHeight - dotHeight) * open
    val left = if (fromEnd) rowWidth - start - width else start
    val top = (rowHeight - height) / 2f
    return Rect(left, top, left + width, top + height)
}
