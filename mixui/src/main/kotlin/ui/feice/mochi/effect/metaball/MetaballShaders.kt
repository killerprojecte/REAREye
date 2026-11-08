// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.metaball

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max
import ui.feice.mochi.effect.glow.GLOW_AGSL
import ui.feice.mochi.effect.glow.GLOW_UNIFORMS
import ui.feice.mochi.shape.g2Corner
import ui.feice.mochi.theme.MochiHdr

/**
 * 面板内部（离边、离按钮都足够远）用的便宜 shader：没有 SDF，只有融色 + 柔光。
 * 那块区域里完整公式的融色系数恒等于 max(uBlend, uCover)、亮边恒为 0（见 bodyHole），所以结果和完整 shader 一样
 */
internal const val MOCHI_FILL_AGSL = """
layout(color) uniform half4 uBtnColor;
layout(color) uniform half4 uPanColor;
uniform float uW;
""" + GLOW_UNIFORMS + GLOW_AGSL + """
half4 main(float2 fc) {
    return half4(glowOver(mix(float4(uBtnColor), float4(uPanColor), uW), fc));
}
"""

/**
 * 融球 shader。两个 G2 圆角矩形（按钮 + 菜单面板）的 SDF 用 polynomial smooth-min 合并，
 * 合并半径 uK 越大，两块之间的「拉丝」越粗。
 *
 * - 本体（按钮 / 面板）不在这里画，由 Kyant 连续曲率 Path 画；这里只出阴影和中间的融球颈
 * - 颈部颜色：按钮色 → 面板色，按「离谁更近」平滑过渡
 * - 阴影：两层。大而淡的环境光阴影 + 小而实的接触阴影，都按「形状被高斯模糊」算覆盖率
 *   （logistic 近似正态 CDF），边缘没有硬截断；再加 ±0.5/255 的抖动消掉 8bit 色带
 * - 抗锯齿：AGSL 不支持 fwidth/dFdx，坐标就是像素，用 0.5 - d 做 1px AA
 */
internal const val MOCHI_AGSL = """
uniform float2 uBtnCenter;
uniform float2 uBtnHalf;
uniform float2 uBtnCorner;   // x = 角区长度, y = 超椭圆指数 n
uniform float2 uPanCenter;
uniform float2 uPanHalf;
uniform float2 uPanCorner;
uniform float uK;            // smooth-min 半径（px）
uniform float uBlendWidth;   // 颈部颜色过渡带宽（px）
uniform float3 uShadowA;     // 环境阴影：x = 向下偏移, y = sigma, z = 不透明度
uniform float3 uShadowK;     // 接触阴影：同上
uniform float uShadowInset;  // 环境阴影的形状先往里收多少（px）
layout(color) uniform half4 uBtnColor;
layout(color) uniform half4 uPanColor;
layout(color) uniform half4 uShadowColor;
uniform float uBody;         // 1 = 画本体颜色（形状由外面的 Kyant Path 裁），0 = 画阴影 + 融球颈
uniform float uBlend;        // 0..1，颜色过渡开关（开头是纯按钮色）
uniform float uCover;        // 包裹模式：按钮颜色整块过渡成面板色
uniform float uTranslucent;  // translucent surfaces must not show their own underpaint
""" + GLOW_UNIFORMS + """

float sdG2(float2 p, float2 c, float2 h, float2 corner) {
    float e = corner.x;
    float n = corner.y;
    float2 q = abs(p - c) - h + e;
    // 不在圆角区（至少一个轴在直边范围内）时，超椭圆范数退化成直边距离，免掉三次 pow（误差 < 1e-4 px）
    if (min(q.x, q.y) <= 0.0) return max(q.x, q.y) - e + 0.0001;
    float2 m = max(q, 0.0) + 0.0001;
    float cd = pow(pow(m.x, n) + pow(m.y, n), 1.0 / n);
    return cd + min(max(q.x, q.y), 0.0) - e;
}

float smin(float a, float b, float k) {
    float h = max(k - abs(a - b), 0.0) / k;
    return min(a, b) - h * h * k * 0.25;
}

// 外包形状的距离：smin 在两块重叠或挨得很近时会把轮廓往外胀（上下边鼓出去），合并结果不许超出它。
// 外包形状 = 包住两块的矩形，圆角用两块里较小的那个（同样的连续曲率）：两个胶囊并排时它正好是包住两者的大胶囊，
// 两端和各自的圆头完全重合、上下边和各自的直边重合，所以不会切出直边、方角；只有往外胀的那部分被抹平
float hull(float2 p) {
    float2 lo = min(uBtnCenter - uBtnHalf, uPanCenter - uPanHalf);
    float2 hi = max(uBtnCenter + uBtnHalf, uPanCenter + uPanHalf);
    float2 half_ = (hi - lo) * 0.5;
    float2 corner = uBtnCorner.x < uPanCorner.x ? uBtnCorner : uPanCorner;
    corner.x = min(corner.x, min(half_.x, half_.y));
    return sdG2(p, (lo + hi) * 0.5, half_, corner);
}

// 融球的合并：平滑并集，不超出外包形状
float mochiUnion(float a, float b, float2 p) {
    return max(smin(a, b, uK), hull(p));
}

float shape(float2 p) {
    return mochiUnion(sdG2(p, uBtnCenter, uBtnHalf, uBtnCorner), sdG2(p, uPanCenter, uPanHalf, uPanCorner), p);
}

float soft(float d, float sigma) {
    return 1.0 / (1.0 + exp(1.702 * d / sigma));
}
""" + GLOW_AGSL + """
half4 main(float2 fc) {
    float db = sdG2(fc, uBtnCenter, uBtnHalf, uBtnCorner);
    float dp = sdG2(fc, uPanCenter, uPanHalf, uPanCorner);
    float d = mochiUnion(db, dp, fc);

    // 颜色：按「离谁更近」从按钮色平滑过渡到面板色，本体和颈用同一个公式，接缝处颜色连续
    float w = max(smoothstep(-uBlendWidth, uBlendWidth, db - dp) * uBlend, uCover);
    float4 fill = mix(float4(uBtnColor), float4(uPanColor), w);

    if (uBody > 0.5) {
        // 本体：形状边缘由 Path 抗锯齿，这里只管颜色 + 按压发光（柔光 + 沿边一圈细亮边，滤色叠加）。
        // 柔光用 r³ 的超高斯：中间一大片几乎一样亮，到半径附近才快速但平滑地暗下去，不是一个亮点
        return half4(glowOver(fill, fc));
    }

    float4 shc = float4(uShadowColor);
    float sa = soft(shape(fc - float2(0.0, uShadowA.x)) + uShadowInset, uShadowA.y) * uShadowA.z
             + soft(shape(fc - float2(0.0, uShadowK.x)), uShadowK.y) * uShadowK.z;
    float n = fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453) - 0.5;
    sa = clamp(sa * shc.a + n / 255.0, 0.0, 1.0);
    float4 shadow = float4(shc.rgb * sa, sa);
    if (uTranslucent > 0.5) shadow *= 1.0 - clamp(0.5 - d, 0.0, 1.0);

    // 只画融球「颈」：smin 比两块本体多出来的那部分；往本体里多伸 1.5px 藏在本体下面，接缝不露底
    float neck = max(clamp(0.5 - d, 0.0, 1.0) - clamp(-1.0 - min(db, dp), 0.0, 1.0), 0.0);
    if (neck <= 0.0) return half4(shadow);
    // 颈部和本体用同一个发光公式：亮边按「到融合后外轮廓的距离」算，沿着颈的外沿连贯地走过去，
    // 连接处里面离外轮廓远，自然没有亮边；颜色和本体逐像素一致，本体 Path 的抗锯齿边压在颈上也看不出接缝
    float4 fillP = glowOver(fill, fc) * neck;
    return half4(fillP + shadow * (1.0 - fillP.a));
}
"""

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class MetaballShaders {
    // 两个实例：同一帧里一个画阴影 + 颈，一个画本体，uniform 互不覆盖；第三个是面板内部的便宜 shader
    private val under = RuntimeShader(MOCHI_AGSL)
    private val bodyShader = RuntimeShader(MOCHI_AGSL)
    private val fillShader = RuntimeShader(MOCHI_FILL_AGSL)
    val brush = ShaderBrush(under)
    val bodyBrush = ShaderBrush(bodyShader)
    val fillBrush = ShaderBrush(fillShader)

    fun update(
        f: MochiMetaballFrame,
        smoothing: Float,
        blendWidth: Float,
        shadow: MochiMetaballShadow,
        colors: MochiMetaballColors,
        touch: Offset,
        glow: Float,
        glowRadius: Float,
        glowColor: Color,
    ) {
        val boost = MochiHdr.boost
        val a = f.a.rect
        val b = f.b.rect
        val bc = g2Corner(f.a.cornerRadius, a.width / 2f, a.height / 2f, smoothing)
        val pc = g2Corner(f.b.cornerRadius, b.width / 2f, b.height / 2f, smoothing)
        for (sh in arrayOf(under, bodyShader)) {
            sh.setFloatUniform("uBtnCenter", a.center.x, a.center.y)
            sh.setFloatUniform("uBtnHalf", max(a.width / 2f, 0.5f), max(a.height / 2f, 0.5f))
            sh.setFloatUniform("uBtnCorner", bc.extent, bc.exponent)
            sh.setFloatUniform("uPanCenter", b.center.x, b.center.y)
            sh.setFloatUniform("uPanHalf", max(b.width / 2f, 0.5f), max(b.height / 2f, 0.5f))
            sh.setFloatUniform("uPanCorner", pc.extent, pc.exponent)
            sh.setFloatUniform("uK", max(f.fuse, 0.01f))
            sh.setFloatUniform("uBlend", f.blend)
            sh.setFloatUniform("uCover", f.merge)
            sh.setFloatUniform("uTranslucent", if (colors.a.alpha < 1f || colors.b.alpha < 1f) 1f else 0f)
            sh.setFloatUniform("uBlendWidth", max(blendWidth, 0.5f))
            sh.setFloatUniform("uShadowA", shadow.ambientOffset, max(shadow.ambientSigma, 0.5f), shadow.ambientAlpha)
            sh.setFloatUniform("uShadowK", shadow.keyOffset, max(shadow.keySigma, 0.5f), shadow.keyAlpha)
            sh.setFloatUniform("uShadowInset", shadow.ambientInset)
            sh.setFloatUniform("uTouch", touch.x, touch.y)
            sh.setFloatUniform("uGlow", glow.coerceIn(0f, 1f))
            sh.setFloatUniform("uGlowR", max(glowRadius, 1f))
            sh.setColorUniform("uBtnColor", colors.a.toArgb())
            sh.setColorUniform("uPanColor", colors.b.toArgb())
            sh.setColorUniform("uShadowColor", colors.shadow.toArgb())
            sh.setColorUniform("uGlowColor", glowColor.toArgb())
            sh.setFloatUniform("uBoost", boost)
        }
        under.setFloatUniform("uBody", 0f)
        bodyShader.setFloatUniform("uBody", 1f)
        fillShader.setColorUniform("uBtnColor", colors.a.toArgb())
        fillShader.setColorUniform("uPanColor", colors.b.toArgb())
        fillShader.setFloatUniform("uW", max(f.blend, f.merge))
        fillShader.setFloatUniform("uTouch", touch.x, touch.y)
        fillShader.setFloatUniform("uGlow", glow.coerceIn(0f, 1f))
        fillShader.setFloatUniform("uGlowR", max(glowRadius, 1f))
        fillShader.setColorUniform("uGlowColor", glowColor.toArgb())
        fillShader.setFloatUniform("uBoost", boost)
    }
}
