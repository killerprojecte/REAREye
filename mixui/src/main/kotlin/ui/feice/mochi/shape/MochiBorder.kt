// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.shape

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp

/**
 * 沿形状轮廓描边，圆角处平滑（抗锯齿）。连续圆角（[MochiRoundedRectangle]、[MochiCapsule]、MochiShapes）描边都用它，不用 `Modifier.border`：
 * Compose 的 border 遇到任意路径轮廓时，把内侧用 clipPath 挖掉，而 Android 上 clipPath 不做抗锯齿，
 * 圆角那一圈会出现锯齿和台阶。这里把同一个形状缩进半个线宽再生成一次轮廓，直接画描边，
 * 线的中心线和外轮廓同心（半径同样缩小半个线宽），外缘正好贴着形状边缘。
 *
 * @param color 颜色；带透明度动画时传当前值即可，完全透明时不画
 */
public fun Modifier.mochiBorder(width: Dp, color: Color, shape: Shape): Modifier = drawWithCache {
    val w = width.toPx().coerceAtLeast(0f)
    val inner = Size((size.width - w).coerceAtLeast(0f), (size.height - w).coerceAtLeast(0f))
    val outline = shape.createOutline(inner, layoutDirection, this)
    val stroke = Stroke(w)
    onDrawWithContent {
        drawContent()
        if (w > 0f && color.alpha > 0f) {
            translate(w / 2f, w / 2f) { drawOutline(outline, color, style = stroke) }
        }
    }
}
