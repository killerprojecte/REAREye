// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.hdr

import android.graphics.RuntimeShader
import android.os.Build
import android.view.RoundedCorner
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.anim.MochiSprings
import ui.feice.mochi.theme.HDR_LIFT_AGSL
import ui.feice.mochi.theme.MochiGraphics
import ui.feice.mochi.theme.MochiHdr

/**
 * Apple Intelligence / Siri 式的屏幕边缘流光：沿屏幕圆角一圈彩色的光，颜色和粗细缓缓流动。
 * [active] 打开时光从边缘长出来，关掉时收回去。放在全屏的最上层（比如页面外面套一个 Box），不拦截触摸。
 *
 * @param palette 颜色，沿一圈依次过渡（预设见 [MochiHdrPalette]）
 * @param width 柔光从屏幕边往里伸多深
 * @param line 贴边那条亮线的粗细
 * @param speed 流动速度（1 = 默认）
 * @param intensity 整体不透明度 / 亮度，0..1
 *
 * 光是盖在内容上的（不是相加），浅色界面上也看得到原本的颜色；[MochiHdr] 打开时颜色在线性光里按原色相提亮，
 * 贴边亮线最亮、正好到峰值，任何通道都不超过峰值，不会发白。
 * API 33+ 用一个 AGSL shader 单次画完，没有离屏模糊；更低版本是一圈不流动的扫掠渐变描边。
 * 只在显示时让帧时钟跑，完全收回后不再刷新。圆角取自屏幕本身（API 31+），拿不到就按直角。
 */
@Composable
public fun MochiEdgeGlow(
    active: Boolean,
    modifier: Modifier = Modifier,
    palette: MochiHdrPalette = MochiHdrPalette.Intelligence,
    width: Dp = 24.dp,
    line: Dp = 3.dp,
    speed: Float = 1f,
    intensity: Float = 1f,
) {
    val progress = remember { Animatable(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    val currentSpeed by rememberUpdatedState(speed)
    LaunchedEffect(active) {
        progress.animateTo(if (active) 1f else 0f, MochiSprings.smooth(if (active) 700 else 450, visibilityThreshold = 0.001f))
    }
    val shown by remember { derivedStateOf { progress.value > 0f } }
    val running = active || shown
    if (running) {
        LaunchedEffect(Unit) {
            var last = withFrameMillis { it }
            while (true) {
                withFrameMillis {
                    time += (it - last) / 1000f * currentSpeed
                    last = it
                }
            }
        }
    }
    if (!running) return
    val view = LocalView.current
    val corner = remember(view) {
        if (Build.VERSION.SDK_INT >= 31) {
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: 0f
        } else {
            0f
        }
    }
    val shader = remember { if (Build.VERSION.SDK_INT >= 33) EdgeGlowShader() else null }
    // 换颜色时不瞬切：两套颜色都沿一圈均匀取 8 个点，逐个插值过渡
    val target = remember(palette) { samplePalette(palette) }
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val mix = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        from = List(PaletteSlots) { i -> lerp(from[i], to[i], mix.value) }
        to = target
        mix.snapTo(0f)
        mix.animateTo(1f, MochiSprings.smooth(500))
    }
    Canvas(modifier) {
        val p = progress.value.coerceIn(0f, 1f)
        if (p <= 0f) return@Canvas
        val boost = MochiHdr.boost
        val k = intensity.coerceIn(0f, 1f)
        if (shader != null && MochiGraphics.shaders) {
            val m = mix.value
            val colors = if (m >= 1f) to else List(PaletteSlots) { i -> lerp(from[i], to[i], m) }
            shader.update(size.width, size.height, corner, time, p, width.toPx(), line.toPx(), k, boost, colors)
            drawRect(shader.brush)
        } else {
            val w = line.toPx() * 2f * p
            val loop = to + to.first()
            val stops = Array(loop.size) { i -> i / (loop.size - 1f) to loop[i].copy(alpha = p * k) }
            drawRoundRect(
                hdrSweepBrush(stops, center, 0f, boost),
                topLeft = Offset(w / 2f, w / 2f),
                size = Size(size.width - w, size.height - w),
                cornerRadius = CornerRadius((corner - w / 2f).coerceAtLeast(0f)),
                style = Stroke(w),
            )
        }
    }
}

private const val PaletteSlots = 8

/** 沿一圈均匀取 8 个颜色（sRGB）：不同颜色数的预设之间也能逐个插值 */
private fun samplePalette(palette: MochiHdrPalette): List<Color> {
    val c = palette.colors.map { it.convert(ColorSpaces.Srgb) }
    return List(PaletteSlots) { i ->
        val t = i.toFloat() / PaletteSlots * c.size
        val a = t.toInt() % c.size
        lerp(c[a], c[(a + 1) % c.size], t - t.toInt())
    }
}

@RequiresApi(33)
private class EdgeGlowShader {
    private val shader = RuntimeShader(EDGE_AGSL)
    val brush = ShaderBrush(shader)
    private val packed = FloatArray(32)

    fun update(
        w: Float,
        h: Float,
        corner: Float,
        t: Float,
        p: Float,
        width: Float,
        line: Float,
        intensity: Float,
        boost: Float,
        colors: List<Color>,
    ) {
        shader.setFloatUniform("uSize", w, h)
        shader.setFloatUniform("uR", corner.coerceAtMost(minOf(w, h) / 2f))
        shader.setFloatUniform("uT", t)
        shader.setFloatUniform("uP", p)
        shader.setFloatUniform("uW", width.coerceAtLeast(1f))
        shader.setFloatUniform("uLine", line.coerceAtLeast(0.5f))
        shader.setFloatUniform("uI", intensity)
        shader.setFloatUniform("uBoost", boost)
        shader.setFloatUniform("uN", colors.size.toFloat())
        packed.fill(0f)
        // 传线性光的颜色：shader 在线性光里提亮、混合，最后再转回画布的色彩空间
        colors.forEachIndexed { i, srgb ->
            val c = srgb.convert(ColorSpaces.LinearSrgb)
            packed[i * 4] = c.red
            packed[i * 4 + 1] = c.green
            packed[i * 4 + 2] = c.blue
            packed[i * 4 + 3] = 1f
        }
        shader.setFloatUniform("uC", packed)
    }
}

// 全部在线性光里算：颜色按原色相提亮（hdrLift：只放大彩色部分，浅色的光不会变成白光），任何通道都不超过峰值 uBoost（亮线 = 峰值，柔光 = 峰值的 35%），
// 不混白、不叠加，最亮的地方也不会顶到屏幕上限发白；最后用 fromLinearSrgb 转回画布的色彩空间（HDR 下是扩展范围）。
// 输出预乘，盖在内容上（浅色界面上也保持色相）
private const val EDGE_AGSL = """
uniform float2 uSize;
uniform float uR;      // 屏幕圆角半径（px）
uniform float uT;      // 流动时间（已乘速度）
uniform float uP;      // 显出程度 0..1
uniform float uW;      // 柔光宽度（px）
uniform float uLine;   // 亮线粗细（px）
uniform float uI;      // 整体强度 0..1
uniform float uBoost;  // HDR 峰值亮度（1 = SDR）
uniform float uN;      // 颜色数 1..8
uniform float4 uC[8];  // 线性光颜色
""" + HDR_LIFT_AGSL + """

float3 palette(float h) {
    float t = fract(h) * uN;
    float fi = floor(t);
    float f = smoothstep(0.0, 1.0, t - fi);
    float nx = fi + 1.0 >= uN ? 0.0 : fi + 1.0;
    float3 a = uC[0].rgb;
    float3 b = uC[0].rgb;
    for (int i = 0; i < 8; i++) {
        if (float(i) == fi) a = uC[i].rgb;
        if (float(i) == nx) b = uC[i].rgb;
    }
    return mix(a, b, f);
}

half4 main(float2 p) {
    float2 h = uSize * 0.5;
    float2 q = abs(p - h) - (h - uR);
    float e = -(length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - uR);   // 离屏幕边往里的距离
    float w = uW * uP;
    if (w <= 0.0 || e > w * 5.0) return half4(0.0);
    float2 v = p - h;
    float ang = atan(v.y, v.x) / 6.2831853;
    // 粗细沿着边起伏：两组不同速度的波叠在一起，像 Siri 的光在流动
    float wave = 0.55 + 0.45 * (0.5 + 0.5 * sin(ang * 12.566 - uT * 1.3)) * (0.65 + 0.35 * sin(ang * 31.4 + uT * 2.1));
    float ww = max(w * wave, 0.5);
    float soft = exp(-e / ww);
    float lw = max(uLine * (0.55 + 0.45 * wave) * uP, 0.5);
    float core = exp(-(e * e) / (lw * lw));
    // 亮线和柔光取色错开一点，颜色更有层次
    float3 cLine = palette(ang + uT * 0.07);
    float3 cSoft = palette(ang + uT * 0.07 + 0.1 + 0.04 * sin(uT * 0.7 + ang * 18.85));
    float aLine = core * 0.95;
    float aSoft = soft * 0.72 * (1.0 - aLine);
    float a = aLine + aSoft;
    if (a <= 0.0) return half4(0.0);
    float g = uBoost - 1.0;
    // 加权平均：结果的每个通道 ≤ max(亮线倍数, 柔光倍数) = 峰值
    float3 lin = (hdrLift(cLine, uBoost) * aLine + hdrLift(cSoft, 1.0 + g * 0.35) * aSoft) / a;
    a *= uP * uI;
    return half4(fromLinearSrgb(half3(lin)) * half(a), half(a));
}
"""
