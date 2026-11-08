// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp

/** 侧推页完全推入时左边缘投影最深处的不透明度（随页面露出的宽度线性变淡） */
internal const val SlideShadowAlpha = 0.14f

/** 侧推页左边缘投影的宽度 */
private val SlideShadowWidth = 32.dp

/**
 * 投影浓度随离边距离 t（0 = 页面边，1 = 最外）按 (1 − t)³ 变淡：靠边浓、往外很快变淡，到外沿斜率和弯曲都是 0，
 * 没有折线渐变那种一道折痕和外沿的硬边。渐变按这条曲线取 [ShadowStops] 个点
 */
private const val ShadowStops = 9

private fun shadowFalloff(t: Float): Float = (1f - t).let { it * it * it }

/**
 * 页面左边缘外侧的软投影（侧推页、侧边栏推开的主页面共用）：页面圆角轮廓往外扩一圈 [SlideShadowWidth]，
 * 浓淡只看「离页面轮廓的距离」：直边段是横向渐变，两个圆角段是以圆角圆心为中心的径向渐变，
 * 三段在交界处一样深，阴影顺着圆角包过去，没有硬边。
 * @param edge 页面左边缘的 x；[tl] / [bl] 页面左上、左下圆角半径；[alpha] 最深处的不透明度
 */
internal fun DrawScope.drawSlideEdgeShadow(edge: Float, tl: Float, bl: Float, alpha: Float) {
    if (alpha <= 0f) return
    val w = SlideShadowWidth.toPx()
    val h = size.height
    drawRect(
        Brush.horizontalGradient(
            *Array(ShadowStops) { i ->
                val t = i / (ShadowStops - 1f)
                (1f - t) to Color.Black.copy(alpha = alpha * shadowFalloff(t))
            }.reversedArray(),
            startX = edge - w,
            endX = edge,
        ),
        topLeft = Offset(edge - w, tl),
        size = Size(w, (h - tl - bl).coerceAtLeast(0f)),
    )
    drawShadowCorner(Offset(edge + tl, tl), tl, w, alpha, Rect(edge - w, -w, edge + tl, tl))
    drawShadowCorner(Offset(edge + bl, h - bl), bl, w, alpha, Rect(edge - w, h - bl, edge + bl, h + w))
}

/** 圆角段的阴影：圆心在页面圆角的圆心，半径 r 处最深，往外 w 渐隐到 0 */
private fun DrawScope.drawShadowCorner(c: Offset, r: Float, w: Float, a: Float, area: Rect) {
    val outer = r + w
    drawRect(
        Brush.radialGradient(
            0f to Color.Black.copy(alpha = a),
            *Array(ShadowStops) { i ->
                val t = i / (ShadowStops - 1f)
                (r + w * t) / outer to Color.Black.copy(alpha = a * shadowFalloff(t))
            },
            center = c,
            radius = outer,
        ),
        topLeft = area.topLeft,
        size = area.size,
    )
}
