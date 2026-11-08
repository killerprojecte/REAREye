// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glow

import ui.feice.mochi.theme.HDR_LIFT_AGSL

// 按压高光的 AGSL 片段：融球本体、融球颈、面板内部的便宜 shader 共用同一段代码，算出来的颜色逐像素一致；
// 观感和 Modifier.mochiGlow 的 Compose 画法一致（以手指为中心的柔光，相加叠一层，不画亮边）
internal const val GLOW_UNIFORMS = """
uniform float2 uTouch;       // 手指位置（px）
uniform float uGlow;         // 发光强度 0..1
uniform float uGlowR;        // 发光半径（px）
layout(color) uniform half4 uGlowColor;
uniform float uBoost;        // HDR 亮度倍数（1 = SDR）
"""

internal const val GLOW_AGSL = HDR_LIFT_AGSL + """
float4 glowOver(float4 fill, float2 fc) {
    float4 gc = float4(uGlowColor);
    float r = length(fc - uTouch) / max(uGlowR, 1.0);
    float k = uGlow * gc.a;
    float body = exp(-r * r * r * 1.6) * 0.85 * k;
    // ±0.5/255 的抖动：8 位色下大片平缓的光不会出现一圈圈色阶
    float n = (fract(sin(dot(fc, float2(12.9898, 78.233))) * 43758.5453) - 0.5) / 255.0;
    if (body <= 0.0) return float4(fill.rgb * fill.a, fill.a);
    // 柔光本体相加一层（和 drawGlowBody 一致）；不画贴边的亮边
    fill.rgb = min(fill.rgb + clamp(body * 0.28 + n, 0.0, 1.0) * gc.rgb, 1.0);
    // HDR 光泽（和 drawHdrSheen 一致）：在线性光里按 hdrLift 提亮（只放大彩色部分，浅色底不会发白），手指附近混进去。
    // 不乘 gc.a，深色模式里按压光很淡时也照样亮
    if (uBoost > 1.001) {
        float3 lin = float3(toLinearSrgb(half3(fill.rgb)));
        lin = mix(lin, hdrLift(lin, 1.0 + (uBoost - 1.0) * 0.6), clamp(exp(-r * r * r * 1.6) * uGlow, 0.0, 1.0));
        fill.rgb = float3(fromLinearSrgb(half3(lin)));
    }
    return float4(fill.rgb * fill.a, fill.a);
}
"""
