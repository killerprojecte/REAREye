// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import kotlin.math.abs
import kotlin.math.floor

/**
 * 页面指示器的几何。[position] 是带小数的当前页（翻到一半 = 2.5）。
 * 页数多于 [maxVisible] 时只露出一个窗口，窗口跟着 position 平滑移动；窗口两端（不是真正首末页）的点缩小，
 * 提示「后面还有」。
 */
internal object PageControlGeometry {
    /** 第 i 个点的宽度：离当前页越近越接近 [activeWidth]，翻页时宽度在相邻两点之间连续转移 */
    fun dotWidth(i: Int, position: Float, dot: Float, activeWidth: Float): Float {
        val t = (1f - abs(i - position)).coerceIn(0f, 1f)
        return dot + (activeWidth - dot) * t
    }

    /** 窗口起点（第几个点，带小数） */
    fun windowStart(position: Float, count: Int, maxVisible: Int): Float {
        if (count <= maxVisible) return 0f
        return (position - (maxVisible - 1) / 2f).coerceIn(0f, (count - maxVisible).toFloat())
    }

    /** 第 i 个点的缩放：窗口内 1，窗口边上的缩到 [EdgeScale]，窗口外 0（真正的首末页不缩） */
    fun dotScale(i: Int, position: Float, count: Int, maxVisible: Int): Float {
        if (count <= maxVisible) return 1f
        val start = windowStart(position, count, maxVisible)
        val rel = i - start
        val edge = minOf(rel, (maxVisible - 1) - rel)
        val atRealEnd = (i == 0 && rel <= 0.5f) || (i == count - 1 && (maxVisible - 1) - rel <= 0.5f)
        return when {
            edge >= 1f -> 1f
            edge >= 0f -> if (atRealEnd) 1f else EdgeScale + (1f - EdgeScale) * edge
            edge > -1f -> EdgeScale * (1f + edge)
            else -> 0f
        }
    }

    /** 拖动选页：手指的横坐标（相对指示器中心，px）换成第几页 */
    fun indexAt(xFromCenter: Float, position: Float, count: Int, maxVisible: Int, pitch: Float): Int {
        if (count <= 0 || pitch <= 0f) return 0
        val visible = minOf(count, maxVisible)
        val start = windowStart(position, count, maxVisible)
        val rel = xFromCenter / pitch + (visible - 1) / 2f
        return floor(start + rel + 0.5f).toInt().coerceIn(0, count - 1)
    }

    const val EdgeScale = 0.55f
}
