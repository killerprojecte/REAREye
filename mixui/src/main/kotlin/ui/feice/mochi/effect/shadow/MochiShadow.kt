// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.shadow

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import ui.feice.mochi.effect.metaball.MochiMetaballShadow
import ui.feice.mochi.theme.MochiGraphics

/**
 * 给任意形状投一层柔和的双层阴影（和菜单、融球同一套参数 [MochiMetaballShadow]）：
 * 往里收、大半径的淡环境影 + 很软很淡的接触影，边缘是高斯模糊、没有断层，不用 Material 的光源阴影。
 * 环境影的内收（[MochiMetaballShadow.ambientInset]）在这个任意形状版本里按外接矩形等比缩小形状来做。
 *
 * 阴影垫在形状下面（形状本身几乎不画），先画阴影，再画形状本体：本体的抗锯齿边缘直接盖在柔和的阴影上，
 * 不会出现一圈发硬的灰线（挖洞裁切没有抗锯齿，边上会留一圈硬线）。
 *
 * API 28 以下硬件加速画不了任意形状的模糊阴影：退化成按 [path] 外接矩形的胶囊，几圈往外扩、越来越淡地叠出来。
 *
 * 形状每帧在变（动画、拖动）时不要用这个版本：系统按路径内容给每个形状生成一张模糊遮罩纹理，而且不会回收，
 * 形状一直变就一直涨显存。胶囊、圆角矩形用 [drawMochiShadow] 的矩形版本。
 */
public fun DrawScope.drawMochiShadow(path: Path, shadow: MochiMetaballShadow, color: Color) {
    if (shadow.ambientAlpha <= 0f && shadow.keyAlpha <= 0f) return
    if (MochiGraphics.softShadows) {
        val native = path.asAndroidPath()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            fun layer(offset: Float, sigma: Float, alpha: Float, inset: Float) {
                if (alpha <= 0f) return
                val b = path.getBounds()
                val shrink = inset > 0f && b.width > inset * 2f && b.height > inset * 2f
                if (shrink) {
                    canvas.save()
                    canvas.scale((b.width - inset * 2f) / b.width, (b.height - inset * 2f) / b.height, b.center.x, b.center.y)
                }
                // setShadowLayer 的半径按 Skia 的换算 sigma = r · 0.57735 + 0.5 反推
                val radius = max(0.5f, (sigma - 0.5f) / 0.57735f)
                // 形状本身用几乎透明的颜色画（阴影色不是不透明的，所以阴影按阴影色自己的透明度画，不受它影响）
                paint.color = color.copy(alpha = 1f / 255f).toArgb()
                paint.setShadowLayer(radius, 0f, offset, color.copy(alpha = color.alpha * alpha).toArgb())
                canvas.drawPath(native, paint)
                if (shrink) canvas.restore()
            }
            layer(shadow.ambientOffset, shadow.ambientSigma, shadow.ambientAlpha, shadow.ambientInset)
            layer(shadow.keyOffset, shadow.keySigma, shadow.keyAlpha, 0f)
        }
    } else {
        val bounds = path.getBounds()
        fun rings(offset: Float, sigma: Float, alpha: Float) {
            if (alpha <= 0f) return
            val spread = sigma * 2f
            for (k in FallbackRings downTo 1) {
                val r = bounds.inflate(spread * k / FallbackRings).translate(0f, offset)
                drawRoundRect(
                    color.copy(alpha = 1f),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(minOf(r.width, r.height) / 2f),
                    alpha = color.alpha * alpha / FallbackRings * (1f - (k - 1f) / FallbackRings),
                )
            }
        }
        rings(shadow.ambientOffset, shadow.ambientSigma, shadow.ambientAlpha)
        rings(shadow.keyOffset, shadow.keySigma, shadow.keyAlpha)
    }
}

private const val FallbackRings = 4

/**
 * 胶囊 / 圆角矩形（[rect]、圆角 [cornerRadius]）的柔和双层阴影，参数同上。按圆角矩形画：系统对圆角矩形的模糊有解析画法，
 * 只按（模糊半径、圆角）缓存一张很小的纹理，形状每帧变也不会每帧生成新的遮罩。
 * 模糊过的阴影上看不出连续曲率和圆弧圆角的差别，所以连续曲率的胶囊也用它
 */
public fun DrawScope.drawMochiShadow(rect: Rect, cornerRadius: Float, shadow: MochiMetaballShadow, color: Color) {
    if (shadow.ambientAlpha <= 0f && shadow.keyAlpha <= 0f) return
    if (MochiGraphics.softShadows) {
        // 环境影的形状往里收 ambientInset（不收过半），圆角跟着收，保持同心
        val inset = shadow.ambientInset.coerceIn(0f, minOf(rect.width, rect.height) / 2f - 0.5f).coerceAtLeast(0f)
        drawRoundRectShadow(
            rect.deflate(inset),
            (cornerRadius - inset).coerceAtLeast(0f),
            shadow.ambientSigma,
            shadow.ambientOffset,
            color.copy(alpha = color.alpha * shadow.ambientAlpha),
        )
        drawRoundRectShadow(rect, cornerRadius, shadow.keySigma, shadow.keyOffset, color.copy(alpha = color.alpha * shadow.keyAlpha))
    } else {
        drawMochiShadow(Path().apply { addRoundRect(RoundRect(rect, CornerRadius(cornerRadius))) }, shadow, color)
    }
}

/**
 * 一层圆角矩形的模糊阴影（高斯 [sigma]、往下偏 [dy]、颜色 [color]），见 [drawMochiShadow] 的矩形版本。
 *
 * Android 13+ 用着色器直接算「圆角矩形卷积高斯」的解析近似（erf 近似 + 竖向 4 点积分），一次绘制、不生成任何纹理，
 * 形状每帧变也没有缓存要回收；输出加 ±0.5/255 的抖动（阴影是很淡、跨度很大的渐变，8bit 量化后会一圈圈地出色带）。系统的 setShadowLayer / 模糊遮罩会给每个新形状生成一张模糊纹理并一直留着，
 * 动画中的形状（底栏收起、拇指拖动）会让显存一直涨。更低的系统没有运行时着色器，仍用 setShadowLayer
 */
internal fun DrawScope.drawRoundRectShadow(rect: Rect, cornerRadius: Float, sigma: Float, dy: Float, color: Color) {
    if (color.alpha <= 0f || rect.width <= 0f || rect.height <= 0f) return
    val s = max(sigma, 0.5f)
    val r = cornerRadius.coerceIn(0f, minOf(rect.width, rect.height) / 2f)
    val box = rect.translate(0f, dy)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val shader = roundRectShadowShader().apply {
            setFloatUniform("uRect", box.left, box.top, box.right, box.bottom)
            setFloatUniform("uRadius", r)
            setFloatUniform("uSigma", s)
            val c = color
            setFloatUniform("uColor", c.red * c.alpha, c.green * c.alpha, c.blue * c.alpha, c.alpha)
        }
        val reach = s * 3f
        drawRect(
            ShaderBrush(shader),
            topLeft = Offset(box.left - reach, box.top - reach),
            size = Size(
                box.width + reach * 2f,
                box.height + reach * 2f,
            ),
        )
        return
    }
    // setShadowLayer 的半径按 Skia 的换算 sigma = r · 0.57735 + 0.5 反推
    val radius = max(0.5f, (s - 0.5f) / 0.57735f)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = color.copy(alpha = 1f / 255f).toArgb()
    paint.setShadowLayer(radius, 0f, dy, color.toArgb())
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(rect.left, rect.top, rect.right, rect.bottom, r, r, paint) }
}

// 圆角矩形卷积高斯：横向按圆角处的有效半宽用 erf 解析积分，竖向在 ±3σ 里取 4 点数值积分（Evan Wallace 的近似）
private const val ROUND_RECT_SHADOW_AGSL = """
uniform float4 uRect;
uniform float uRadius;
uniform float uSigma;
uniform half4 uColor;

float gaussian(float x, float sigma) {
    return exp(-(x * x) / (2.0 * sigma * sigma)) / (2.50662827463 * sigma);
}

float2 erf2(float2 x) {
    float2 s = sign(x);
    float2 a = abs(x);
    float2 t = 1.0 + (0.278393 + (0.230389 + 0.078108 * (a * a)) * a) * a;
    t *= t;
    return s - s / (t * t);
}

float shadowX(float x, float y, float sigma, float corner, float2 halfSize) {
    float delta = min(halfSize.y - corner - abs(y), 0.0);
    float curved = halfSize.x - corner + sqrt(max(0.0, corner * corner - delta * delta));
    float2 integral = 0.5 + 0.5 * erf2((x + float2(-curved, curved)) * (0.70710678 / sigma));
    return integral.y - integral.x;
}

half4 main(float2 coord) {
    float2 center = (uRect.xy + uRect.zw) * 0.5;
    float2 halfSize = (uRect.zw - uRect.xy) * 0.5;
    float2 p = coord - center;
    float low = p.y - halfSize.y;
    float high = p.y + halfSize.y;
    float start = clamp(-3.0 * uSigma, low, high);
    float end = clamp(3.0 * uSigma, low, high);
    float step = (end - start) / 4.0;
    float y = start + step * 0.5;
    float v = 0.0;
    for (int i = 0; i < 4; i++) {
        v += shadowX(p.x, p.y - y, uSigma, uRadius, halfSize) * gaussian(y, uSigma) * step;
        y += step;
    }
    // 抖动按预乘后的不透明度加，消掉 8bit 色带
    float n = fract(sin(dot(coord, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    float a = v > 0.0 ? clamp(v * float(uColor.a) + n / 255.0, 0.0, 1.0) : 0.0;
    return uColor.a > 0.0 ? half4(uColor.rgb / uColor.a * half(a), half(a)) : half4(0.0);
}
"""

private var roundRectShader: RuntimeShader? = null

// 只在主线程录制绘制时用；每次 setFloatUniform 后系统给这次绘制单独取一份着色器快照，复用同一个对象没问题
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun roundRectShadowShader(): RuntimeShader = roundRectShader ?: RuntimeShader(ROUND_RECT_SHADOW_AGSL).also { roundRectShader = it }
