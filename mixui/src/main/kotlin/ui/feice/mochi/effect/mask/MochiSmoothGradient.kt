// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.mask

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 按 smoothstep 走的渐变：两端的斜率都是 0，起点和终点接得上纯色和透明，
 * 看不到线性渐变在开始、结束处那两条「折线」。库里所有「纯色 → 透明」的渐隐（滚动边缘、底栏、渐变遮罩）都用它。
 *
 * GPU 渐变在色标之间是线性插值，所以这里把 S 曲线拆成 [MochiSmoothGradient.Stops] 段色标逼近。
 */
public object MochiSmoothGradient {
    /** 拆成多少段色标：16 段时每段的折角已经看不出来 */
    public const val Stops: Int = 16

    /** S 曲线：0..1 → 0..1，两端斜率为 0 */
    public fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 从 [from] 到 [to] 的色标（位置 0..1） */
    public fun stops(from: Color, to: Color, count: Int = Stops): Array<Pair<Float, Color>> = Array(count + 1) { i ->
        val t = i / count.toFloat()
        t to lerp(from, to, smoothstep(t))
    }

    /** 竖直方向：[startY] 处是 [from]，[endY] 处是 [to] */
    public fun vertical(from: Color, to: Color, startY: Float, endY: Float): Brush =
        Brush.verticalGradient(*stops(from, to), startY = startY, endY = endY)

    /** 水平方向：[startX] 处是 [from]，[endX] 处是 [to] */
    public fun horizontal(from: Color, to: Color, startX: Float, endX: Float): Brush =
        Brush.horizontalGradient(*stops(from, to), startX = startX, endX = endX)
}
