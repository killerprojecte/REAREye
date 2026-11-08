// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic.parts

import android.os.Build
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import ui.feice.mochi.effect.glow.MochiGlowState
import ui.feice.mochi.effect.glow.drawGlow
import ui.feice.mochi.effect.glow.insetPath
import ui.feice.mochi.effect.hdr.hdrLinearBrush
import ui.feice.mochi.effect.hdr.hdrSolidBrush
import ui.feice.mochi.effect.shadow.drawMochiShadow
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.theme.MochiGraphics
import ui.feice.mochi.theme.MochiHdr
import ui.feice.mochi.theme.MochiShadows
import ui.feice.mochi.theme.lerp

/** 开关 / 滑块的 G2 平滑度（和其他组件一样的连续曲率胶囊） */
internal const val ControlSmoothing = 1f

/** 控件零件（拇指、分段选中块）默认阴影色的浓度：22% 黑，乘上 [MochiShadows.contact] 的系数 */
internal const val ControlShadowAlpha = 0.22f

/** 用 G2 连续曲率画一个胶囊（rect 的短边就是直径） */
internal fun DrawScope.drawG2Capsule(cache: G2PathCache, rect: Rect, color: Color, alpha: Float = 1f) {
    if (rect.width <= 0f || rect.height <= 0f) return
    drawPath(cache.get(rect, minOf(rect.width, rect.height) / 2f, ControlSmoothing), color, alpha = alpha)
}

/**
 * 开关 / 滑块的胶囊拇指（iOS 26 的样子，不做玻璃）：白色胶囊 + 柔和投影，按下时投影更深更软，叠按压高光。
 * @param lift 0..1，按下的程度（投影在 [MochiShadows.contact] 和 [MochiShadows.contactPressed] 之间变）
 * @param glowOrigin 这个 DrawScope 原点在 glow 坐标系里的位置
 */
internal fun DrawScope.drawPillThumb(
    rect: Rect,
    color: Color,
    shadow: Color,
    lift: Float,
    shadows: MochiShadows,
    glow: MochiGlowState?,
    path: G2PathCache,
    glowOrigin: Offset = Offset.Zero,
) {
    val g2 = path.get(rect, minOf(rect.width, rect.height) / 2f, ControlSmoothing)
    if (MochiGraphics.softShadows && shadow.alpha > 0f) {
        // 阴影按圆角矩形画（拖动时拇指每帧变形，按路径画会每帧生成一张不回收的模糊遮罩），本体按连续曲率的胶囊画在上面。
        // 半径大、颜色淡（iOS 拇指那种散开的影子），不是紧贴边缘的一圈灰
        drawMochiShadow(
            rect,
            minOf(rect.width, rect.height) / 2f,
            lerp(shadows.contact, shadows.contactPressed, lift).resolve(this, false),
            shadow,
        )
        drawPath(g2, color)
    } else {
        // API 28 以下硬件加速不支持形状的 setShadowLayer：用几圈往外扩、越来越淡的胶囊叠出一层软投影
        if (shadow.alpha > 0f) {
            val blur = (2.5f + 5f * lift).dp.toPx()
            val dy = (1f + 2f * lift).dp.toPx()
            val a = shadow.alpha * (0.6f + 0.4f * lift)
            for (k in FallbackShadowRings downTo 1) {
                val grow = blur * k / FallbackShadowRings
                val r = rect.inflate(grow).translate(0f, dy)
                val radius = minOf(r.width, r.height) / 2f
                drawRoundRect(
                    shadow.copy(alpha = 1f),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(radius),
                    alpha = a / FallbackShadowRings * (1f - (k - 1f) / FallbackShadowRings),
                )
            }
        }
        drawPath(g2, color)
    }
    drawThumbSpecular(g2, rect, color, glow?.intensity ?: 0f)
    if (glow != null && glow.intensity > 0.01f) {
        drawGlow(glow, Outline.Generic(g2), glow.center - glowOrigin)
    }
}

/**
 * HDR 下按住圆钮：整颗圆钮覆盖一层按原色提亮的本色（到峰值的一半；不用 Modulate，HDR 画布上乘法会压暗变灰）。不画亮边。
 * 只在按住期间出现（跟着按压程度淡入淡出），任何一处都不超过 [MochiHdr] 的峰值。SDR 下不画。
 */
private fun DrawScope.drawThumbSpecular(g2: Path, rect: Rect, color: Color, press: Float) {
    val peak = MochiHdr.boost
    if (peak <= 1.001f || press <= 0.01f || Build.VERSION.SDK_INT < 29) return
    val k = press.coerceIn(0f, 1f)
    drawPath(g2, hdrSolidBrush(color.copy(alpha = 1f), 1f + (peak - 1f) * 0.5f), alpha = k * color.alpha)
}

/** 低版本软投影叠几圈 */
private const val FallbackShadowRings = 4
