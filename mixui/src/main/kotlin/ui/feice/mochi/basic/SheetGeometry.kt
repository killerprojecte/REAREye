// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import kotlin.math.roundToInt
import ui.feice.mochi.navigation.MochiScreenCorners

/** 面板表面拥有真实可见高度：上拉超过最高停靠点时也必须一直铺到屏幕底边。 */
internal data class SheetFrame(val x: Int, val y: Int, val width: Int, val height: Int)

/** The shell and internal navigation must use exactly the same visible bottom edge. */
internal fun sheetSurfaceHeight(frame: SheetFrame, fixedHeight: Int, fillsBottom: Boolean): Int =
    if (fillsBottom) frame.height else minOf(frame.height, fixedHeight)

internal fun sheetFrame(windowWidth: Int, windowHeight: Int, top: Float, largeness: Float, inset: Float): SheetFrame {
    val gap = (inset * (1f - largeness.coerceIn(0f, 1f))).roundToInt().coerceIn(0, windowWidth / 2)
    val y = if (top.isNaN()) windowHeight else top.roundToInt().coerceAtMost(windowHeight)
    return SheetFrame(gap, y, (windowWidth - 2 * gap).coerceAtLeast(0), (windowHeight - gap - y).coerceAtLeast(0))
}

/** 顶部圆角有独立上限；移动中的底部保留原来的连续圆角，不提前补齐。 */
internal fun sheetCornerRadii(screen: MochiScreenCorners, gap: Float, topMaximum: Float, fallback: Float): MochiScreenCorners {
    fun top(radius: Float): Float {
        // 48dp 是参考真机 58dp - 10dp 得到的固定上限，不是每台手机都扣掉 10dp。
        // 保留原来至少 32dp 的连续圆角和悬浮内缩，小圆角机型不再被额外削小。
        return (maxOf(radius, fallback) - gap).coerceIn(0f, topMaximum)
    }
    fun bottom(radius: Float): Float = (maxOf(radius, fallback) - gap).coerceAtLeast(0f)
    return MochiScreenCorners(top(screen.topLeft), top(screen.topRight), bottom(screen.bottomRight), bottom(screen.bottomLeft))
}
