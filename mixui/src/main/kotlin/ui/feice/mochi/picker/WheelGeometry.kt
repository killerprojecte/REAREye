// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.picker

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 滚筒的几何：滚动位置 `offset` 是「中间那格的序号 × 格高」（px），往上滚 offset 变大。
 * 每一项按离中心的弧长 delta 摆到半径为 R 的圆柱上：θ = delta / R，屏幕上的位置 y = R·sinθ，
 * 往外转得越多越扁、越淡。|θ| ≥ 90° 的项在圆柱背面，不画。
 */
internal object WheelGeometry {
    /** offset 下居中的那一格（不取模） */
    fun centerIndex(offset: Float, itemPx: Float): Int = (offset / itemPx).roundToInt()

    /** 循环滚筒的序号取模到 0 until count */
    fun wrap(index: Int, count: Int): Int = if (count <= 0) 0 else ((index % count) + count) % count

    /** 非循环时可滚的范围 */
    fun maxOffset(count: Int, itemPx: Float): Float = ((count - 1).coerceAtLeast(0)) * itemPx

    /** 惯性落点吸附到最近一格；非循环时夹在首末项之间 */
    fun snap(target: Float, itemPx: Float, count: Int, cyclic: Boolean): Float {
        val i = (target / itemPx).roundToInt()
        val clamped = if (cyclic) i else i.coerceIn(0, (count - 1).coerceAtLeast(0))
        return clamped * itemPx
    }

    /**
     * 要选中 [index] 时滚到哪：非循环就是 index × 格高；循环时在等价位置里挑离 [current] 最近的，
     * 所以从 59 分到 0 分是往下滚一格，不是倒着转一圈。
     */
    fun offsetFor(index: Int, current: Float, itemPx: Float, count: Int, cyclic: Boolean): Float {
        if (!cyclic || count <= 0) return index * itemPx
        val c = centerIndex(current, itemPx)
        val base = c - wrap(c, count)
        var best = base + index
        for (k in -1..1) {
            val cand = base + k * count + index
            if (abs(cand - c) < abs(best - c)) best = cand
        }
        return best * itemPx
    }

    /** 拉过首末项时的橡皮筋：越界部分按 0.35 跟手并逐渐变硬 */
    fun rubberBand(raw: Float, min: Float, max: Float, extentPx: Float): Float = when {
        raw < min -> min - band(min - raw, extentPx)
        raw > max -> max + band(raw - max, extentPx)
        else -> raw
    }

    private fun band(over: Float, extent: Float): Float {
        if (extent <= 0f) return 0f
        val c = 0.55f
        return (1f - 1f / (over * c / extent + 1f)) * extent
    }

    /** 圆柱半径：让 ±90° 正好落在可见区上下边 */
    fun radius(visibleHeightPx: Float): Float = visibleHeightPx / 2f

    /** 一项的投影。[visible] 为 false 时在圆柱背面 */
    fun transform(deltaPx: Float, radiusPx: Float): WheelItemTransform {
        if (radiusPx <= 0f) return WheelItemTransform(0f, 0f, 0f, 0f, false)
        val theta = deltaPx / radiusPx
        if (abs(theta) >= HalfPi) return WheelItemTransform(0f, 0f, 0f, 0f, false)
        val c = cos(theta)
        return WheelItemTransform(
            y = radiusPx * sin(theta),
            rotationX = -(theta * 180.0 / PI).toFloat(),
            scale = 0.9f + 0.1f * c,
            alpha = (0.25f + 0.75f * c * c).coerceIn(0f, 1f),
            visible = true,
        )
    }

    /** 点在离中心 [yFromCenter] 处：往上 / 往下差几格（按投影反算回弧长） */
    fun tapDelta(yFromCenter: Float, radiusPx: Float, itemPx: Float): Int {
        if (radiusPx <= 0f || itemPx <= 0f) return 0
        val s = (yFromCenter / radiusPx).coerceIn(-0.999f, 0.999f)
        val arc = asin(s) * radiusPx
        return (arc / itemPx).roundToInt()
    }

    private const val HalfPi = (PI / 2).toFloat()
}

internal data class WheelItemTransform(val y: Float, val rotationX: Float, val scale: Float, val alpha: Float, val visible: Boolean)
