// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass.engine

// 着色器共用：形状的有符号距离（里面为负）、法线，以及 iOS 黑环用的软阶跃多项式。
// 形状和裁剪用的同一条 Kyant 连续圆角曲线：一个角（从下边中点到右边中点）取 CORNER_POINTS 个点组成折线，四个角对称
internal const val GLASS_COMMON_AGSL = """
uniform float2 uSize;
uniform float uPad;
uniform float2 uCorner[16];
uniform float2 uCornerN[16];  // 每个点的法线（相邻两段的平均），段内插值，折射不会一棱一棱的
// 几块玻璃融成一块时（菜单从按钮组里脱出来、按钮组）：图层按所有块的外包框排，主形状中心偏开 uShift；
// 其余每块是圆角矩形，中心（相对主形状中心）和半宽高在 uMergeRect[i]，半宽为 0 = 没有；uMerge[i] = (它的圆角, 和前面并起来时光滑并集的半径)
uniform float2 uShift;
uniform float4 uMergeRect[6];
uniform float2 uMerge[6];

// 有符号距离（里面为负），[n] 带回外法线。弯的那段在 uCorner[1]..uCorner[14] 之间，两头是直边。
// 先算到两条直边的距离；到弯处的距离不会小于到「弯处所在方框」的距离，方框更远时就不用跑循环（大部分像素走这条）
float cornerDist(float2 q, out float2 n) {
    float2 h = float2(uCorner[15].x, uCorner[0].y);
    float x0 = uCorner[1].x;
    float y1 = uCorner[14].y;
    float2 c1 = float2(min(q.x, x0), h.y);
    float2 c2 = float2(h.x, min(q.y, y1));
    float d1 = length(q - c1);
    float d2 = length(q - c2);
    float de = min(d1, d2);
    float2 dq = max(max(float2(x0, y1) - q, q - h), 0.0);
    if (length(dq) >= de) {
        bool inside = q.x < h.x && q.y < h.y;
        float2 c = d1 < d2 ? c1 : c2;
        float2 g = q - c;
        float l = length(g);
        n = l > 1e-4 ? g / l * (inside ? -1.0 : 1.0) : (d1 < d2 ? float2(0.0, 1.0) : float2(1.0, 0.0));
        return inside ? -de : de;
    }
    float best = 1e9;
    float2 bestN = float2(0.0, 1.0);
    bool inside = true;
    for (int i = 0; i < 15; i++) {
        float2 a = uCorner[i];
        float2 e = uCorner[i + 1] - a;
        float2 v = q - a;
        float t = clamp(dot(v, e) / max(dot(e, e), 1e-6), 0.0, 1.0);
        float2 c = a + e * t;
        float dd = dot(q - c, q - c);
        if (dd < best) {
            best = dd;
            bestN = mix(uCornerN[i], uCornerN[i + 1], t);
        }
        if (e.x * v.y - e.y * v.x > 0.0) inside = false;
    }
    n = normalize(bestN);
    return inside ? -sqrt(best) : sqrt(best);
}

// 另一块（圆角矩形，半宽高 [h]、圆角 [rr]）的有符号距离和外法线
float mergeDist(float2 p, float2 h, float rr, out float2 n) {
    float r = min(rr, min(h.x, h.y));
    float2 s = float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    float2 q = abs(p) - h + r;
    if (q.x > 0.0 && q.y > 0.0) {
        float l = length(q);
        n = l > 1e-4 ? q / l * s : float2(0.0, s.y);
        return l - r;
    }
    n = q.x > q.y ? float2(s.x, 0.0) : float2(0.0, s.y);
    return max(q.x, q.y) - r;
}

// 已有的形状（距离 [d]、法线 [n]）和另一块 [rect] 取并集；[params].y > 0 时是二次的光滑并集（两边距离差在它以内的地方连成一片），法线按同样的权重混
float mergeShape(float2 p, float d, inout float2 n, float4 rect, float2 params) {
    float2 n2;
    float d2 = mergeDist(p - rect.xy, rect.zw, params.x, n2);
    float k = params.y;
    if (k <= 1e-3) {
        if (d2 < d) n = n2;
        return min(d, d2);
    }
    float t = clamp(0.5 + 0.5 * (d2 - d) / k, 0.0, 1.0);
    float2 m = mix(n2, n, t);
    float l = length(m);
    if (l > 1e-4) n = m / l;
    return mix(d2, d, t) - k * t * (1.0 - t);
}

// Distance and normal come from the same nearest segment. Keep them together so
// refraction/lighting do not traverse the identical curve a second time.
float shapeAt(float2 p, out float2 n) {
    float d = cornerDist(abs(p), n);
    n *= float2(p.x < 0.0 ? -1.0 : 1.0, p.y < 0.0 ? -1.0 : 1.0);
    // 融进来的几块从下标 0 起连着放：第一块是空的就没有（大部分玻璃），不跑循环
    if (uMergeRect[0].z > 0.0) {
        for (int i = 0; i < 6; i++) {
            if (uMergeRect[i].z > 0.0) d = mergeShape(p, d, n, uMergeRect[i], uMerge[i]);
        }
    }
    return d;
}

float sdShape(float2 p) {
    float2 n;
    return shapeAt(p, n);
}

float2 normalAt(float2 p) {
    float2 n;
    shapeAt(p, n);
    return n;
}

float profile(float depth, float invHeight) {
    float t = clamp(depth * invHeight, 0.0, 1.0);
    return 1.0 - clamp(sqrt(t * (2.0 - t)), 0.0, 1.0);
}

// 抖动：±1 个色阶的三角分布噪点（两份均匀噪点相加），每个通道各自取。面色是在小图层里模糊再放大的，
// 大片平缓的渐变存成 8 位后一阶一阶的，放大后台阶更宽；加在最后输出前把台阶打散（miuix 的 NoiseDither 同理）
float3 dither(float2 p) {
    float3 a = fract(sin(float3(dot(p, float2(12.9898, 78.233)), dot(p, float2(39.346, 11.135)), dot(p, float2(73.156, 52.235)))) * 43758.5453);
    float3 b = fract(sin(float3(dot(p, float2(93.989, 67.345)), dot(p, float2(27.163, 61.734)), dot(p, float2(45.332, 23.871)))) * 24634.6345);
    return (a + b - 1.0) / 255.0;
}

float softStep(float x) {
    float z = 4.0 * clamp(x / 4.0 + 0.5, 0.0, 1.0) - 2.0;
    float z2 = z * z;
    return 0.5 + z * (-0.560546875 + z2 * (0.168212890625 + z2 * (-0.034454345703125 + z2 * 0.0029544830322265625)));
}
"""

internal const val GLASS_COLOR_AGSL = """
float3 matrix34(float3 c, float4 r0, float4 r1, float4 r2) {
    return float3(dot(r0.xyz, c) + r0.w, dot(r1.xyz, c) + r1.w, dot(r2.xyz, c) + r2.w);
}

// 面色之前先提亮内容：逐通道 c^(1/(1+k))，暗处提得多、白不变；k = 0 原样
"""

// 黑环和定向高光：玻璃里面（收尾着色器）和轮廓外面（外轮廓层）共用。按 glass_background 的原始门控写，距离和 fwidth 以像素计（fwidth = 1）
internal const val GLASS_EDGE_AGSL = """
uniform float4 uRing;        // 宽、软化半径、强度、mask
uniform float2 uRingOffset;
uniform float2 uHlDir;
uniform float4 uHl;          // 高度、spread（余弦阈值）、amount（着色器值）、偏移
uniform float uHlBias;
uniform float uHlStroke;     // 1：细描边模式
uniform float4 uHlM0;        // 高光颜色矩阵（3×4）
uniform float4 uHlM1;
uniform float4 uHlM2;

float ringAlpha(float2 p, float cov) {
    if (uRing.z <= 0.0) return 0.0;
    float dr = sdShape(p - uRingOffset);
    float rad = max(uRing.y, 1e-6);
    float band = clamp(softStep(0.7071 * dr / rad) - softStep(0.7071 * (dr + uRing.x) / rad), 0.0, 1.0);
    return uRing.z * band * mix(1.0, cov, uRing.w);
}

float response(float e, float z) {
    float v = e * clamp((z - uHl.y) / max(1.0 - uHl.y, 1e-6), 0.0, 1.0);
    return v / max(1.0 + uHl.z * (1.0 - v), 1e-6);
}

float3 shade(float3 c, float h) {
    float k = uHlBias * h;
    c = c * (1.0 + k * (3.0 - 2.0 * c));
    float3 v = float3(dot(uHlM0.xyz, c) + uHlM0.w, dot(uHlM1.xyz, c) + uHlM1.w, dot(uHlM2.xyz, c) + uHlM2.w);
    return mix(c, clamp(v, 0.0, 1.0), h);
}

float highlightWeight(float d, float cov, float2 n) {
    float h = uHl.x;
    if (h <= 0.0) return 0.0;
    float q = d + uHl.w;
    bool stroke = uHlStroke > 0.5;
    if (q >= 0.5) return 0.0;
    if (stroke ? cov >= 1.0 : h + 0.5 <= -q) return 0.0;
    float a = 1.0;
    float g = 1.0 - cov;
    if (!stroke) {
        float t = 1.0 - clamp(-q / h, 0.0, 1.0);
        a = mix(t != 0.0 ? 1.0 : 0.0, t, 0.75);
        g = clamp(q + h + 0.5, 0.0, 1.0);
    }
    float e = a * g * clamp(-q + 0.5, 0.0, 1.0);
    float z = dot(uHlDir, n);
    return response(e, z) + response(e, -z);
}
"""

internal const val GLASS_RIM_AGSL = """
// 边光：照 RenderBox 的 sdf_diffuse_kf_highlight（CASDFKeyFillHighlightEffect）的形状，主光从 uKf.xy 方向照来、补光从反方向，
// 每一侧一条贴边的亮边，按离边深度衰减（uKfShape.z 在阶跃和线性之间混），再按法线和光方向的夹角衰减（cos 阈值 uKfShape.y），
// 用 w / (1 + a(1 − w)) 收紧。夹角衰减不降到 0：侧边留 uKfSide 那么亮、那么厚。只画在玻璃里面。
// 颜色就是这一处玻璃的颜色提亮：折射那一步已经把边外的颜色渗到了边上，上边带的是上面的颜色、下边是下面的
uniform float4 uKf;          // 主光方向 x、y，主光强度，补光强度
uniform float4 uKfShape;     // 亮边高度（px）、cos 阈值、曲线混合、亮边 amount
uniform float uKfGain;       // 边光颜色增益
uniform float4 uKfM0;        // 边光颜色矩阵（3×4）
uniform float4 uKfM1;
uniform float4 uKfM2;
uniform float uKfSide;       // 侧边（法线和光垂直处）保留的强度和厚度

float kfLobe(float q, float h, float z, float a) {
    float ang = mix(uKfSide, 1.0, clamp((z - uKfShape.y) / max(1.0 - uKfShape.y, 1e-5), 0.0, 1.0));
    h *= ang;
    if (h <= 0.0) return 0.0;
    // 原生是阶跃和线性按曲率混；阶跃换成 1.5px 的软边，贴边一段保持满亮，尽头不留一像素的硬台阶（细边上看着起毛）
    float t = clamp(q / h, 0.0, 1.0);
    float p = mix(1.0 - smoothstep(h - 1.0, h + 0.5, q), 1.0 - t, uKfShape.z);
    float w = p * ang;
    return w / max(1.0 + a * (1.0 - w), 1e-5);
}

// 轮廓上半覆盖的像素按贴边算满：玻璃已经按形状裁过，再乘一次覆盖率最外一圈会发暗、起毛边
float rimWeight(float d, float2 n) {
    if ((uKf.z <= 0.0 && uKf.w <= 0.0) || d > 0.5) return 0.0;
    float q = max(-d, 0.0);
    float z = dot(uKf.xy, n);
    return clamp(uKf.z * kfLobe(q, uKfShape.x, z, uKfShape.w) + uKf.w * kfLobe(q, uKfShape.x, -z, uKfShape.w), 0.0, 1.0);
}

// 按边光颜色矩阵（默认是高光的 vibrantColorMatrix）提亮提艳，uKfGain 放大矩阵的改变（代替真机上边光用的 HDR 余量）
float3 rim(float3 c, float w) {
    if (w <= 0.0) return c;
    float3 v = float3(dot(uKfM0.xyz, c) + uKfM0.w, dot(uKfM1.xyz, c) + uKfM1.w, dot(uKfM2.xyz, c) + uKfM2.w);
    return mix(c, clamp(c + (v - c) * uKfGain, 0.0, 1.0), w);
}
"""

/**
 * 收尾和外轮廓合成一遍（[GlassNode] 的收尾层）：这一层比玻璃大一圈，左边 uAtlas 宽录原样的背景（只有定向高光要读，没有时宽 0），右边录放大回来的面色。
 * 轮廓外：外阴影、黑环压暗，细描边读背景；轮廓里：读面色，黑环、定向高光、边光。
 * 轮廓本身不再用路径裁剪：按距离场算覆盖率，边上一像素里两份按覆盖率混，输出预乘的颜色
 */
internal const val GLASS_FINISH_MERGED_AGSL = GLASS_COMMON_AGSL + GLASS_EDGE_AGSL + GLASS_RIM_AGSL + GLASS_COLOR_AGSL + """
uniform shader content;
uniform float uAtlas;        // 面色在这一层里往右偏多少
uniform float2 uInset;       // 玻璃在这一层里的左上角
uniform float4 uShadow;      // offset x、y、距离偏移、软化
uniform float uShadowOpacity;
uniform float4 uFace0;
uniform float4 uFace1;
uniform float4 uFace2;

half4 main(float2 coord) {
    float2 p = coord - uInset - (uSize * 0.5 + uShift);
    float2 n;
    float d = shapeAt(p, n);
    float cov = clamp(0.5 - d, 0.0, 1.0);
    half4 o = half4(0.0);
    if (d >= -0.5) {
        float s = 0.0;
        if (uShadowOpacity > 0.0) {
            float ds = sdShape(p - uShadow.xy) + uShadow.z;
            s = clamp(uShadowOpacity * softStep(0.7071 * ds / max(uShadow.w, 0.3)), 0.0, 1.0);
        }
        float r = ringAlpha(p, cov);
        float a0 = clamp(s * (1.0 - r) + r, 0.0, 1.0);
        float h = highlightWeight(d, cov, n);
        if (h >= 0.001) {
            half4 v = content.eval(coord);
            float3 bg = v.a > 0.001 ? float3(v.rgb) / float(v.a) : float3(0.0);
            float3 base = bg;
            if (uHlBias != 0.0) {
                float3 face = matrix34(bg, uFace0, uFace1, uFace2);
                base = mix(bg, uHlBias < 0.0 ? min(face, bg) : max(face, bg), h);
            }
            o = half4(half3(clamp(shade(base * (1.0 - a0), h), 0.0, 1.0)), 1.0);
        } else {
            o = half4(0.0, 0.0, 0.0, half(a0 > 0.0 ? clamp(a0 + dither(coord).x, 0.0, 1.0) : 0.0));
        }
    }
    if (cov <= 0.0) return o;
    half4 v = content.eval(coord + float2(uAtlas, 0.0));
    float3 c = v.a > 0.001 ? float3(v.rgb) / float(v.a) : float3(0.0);
    c *= 1.0 - ringAlpha(p, cov);
    c = shade(c, highlightWeight(d, cov, n));
    c = rim(c, rimWeight(d, n)) + dither(coord);
    half4 g = half4(half3(clamp(c, 0.0, 1.0)), 1.0);
    return g * half(cov) + o * half(1.0 - cov);
}
"""

// Keep the original polygon and interpolated normals. A circular capsule arc
// needs only its angular neighbourhood, rather than testing all fifteen edges.
// Non-capsules and the ill-conditioned arc centre retain the general search.
internal val FAST_GLASS_FINISH_AGSL = GLASS_FINISH_MERGED_AGSL
    .replace("uniform float2 uSize;", "uniform float2 uSize;\nuniform float4 uArc;")
    .replace(
        "    float best = 1e9;",
        """
    float2 arcPoint = q - uArc.xy;
    if (uArc.w > 0.5 && length(arcPoint) > uArc.z * 0.05) {
        float angle = clamp(atan(arcPoint.y, arcPoint.x), 0.0, 1.57079632679);
        int candidate = int(clamp(1.0 + floor((1.57079632679 - angle) * 8.27605704), 1.0, 13.0));
        float bestArc = 1e9;
        float2 arcNormal = float2(0.0, 1.0);
        bool arcInside = true;
        for (int i = 0; i < 15; i++) {
            if (i < candidate - 1 || i > candidate + 1) continue;
            float2 a = uCorner[i];
            float2 e = uCorner[i + 1] - a;
            float2 v = q - a;
            float t = clamp(dot(v, e) / max(dot(e, e), 1e-6), 0.0, 1.0);
            float2 c = a + e * t;
            float dd = dot(q - c, q - c);
            if (dd < bestArc) {
                bestArc = dd;
                arcNormal = mix(uCornerN[i], uCornerN[i + 1], t);
            }
            if (e.x * v.y - e.y * v.x > 0.0) arcInside = false;
        }
        n = normalize(arcNormal);
        return arcInside ? -sqrt(bestArc) : sqrt(bestArc);
    }
    float best = 1e9;
""",
    )

internal const val GLASS_FACE_ATLAS_AGSL = GLASS_COMMON_AGSL + GLASS_COLOR_AGSL + """
uniform shader content;
uniform float uAtlas;
uniform float uAmount;
uniform float uInvHeight;
uniform float4 uOuter;
uniform float uOuterOpacity;
uniform float4 uAberr;
uniform float4 uMix;
uniform float uFillActive;
uniform float3 uLanes; // fill, outside light, edge blur
uniform float3 uBlurCurve;
uniform float4 uAmbient;
uniform float2 uAmbientDir;
uniform float uAmbientEdge;
uniform float uLit;
uniform float4 uFace0;
uniform float4 uFace1;
uniform float4 uFace2;

half4 refractedLane(float2 coord, float2 n, float d, float delta, float lane) {
    coord += float2(uAtlas * lane, 0.0);
    float2 q = coord - n * delta;
    half4 face = content.eval(q);
    if (uAberr.x != 0.0) {
        float w = clamp(1.0 + d * uAberr.y, 0.0, 1.0);
        if (w > 0.0) {
            float2 dir = float2(n.x * uAberr.z - n.y * uAberr.w, n.x * uAberr.w + n.y * uAberr.z) * (uAberr.x * w);
            face.r = content.eval(q + dir).r;
            face.b = content.eval(q - dir).b;
        }
    }
    if (uOuterOpacity > 0.0) {
        float w = uOuterOpacity * clamp((d - uOuter.z) / (uOuter.w - uOuter.z), 0.0, 1.0);
        if (w > 0.0) face = mix(face, content.eval(coord + n * (uOuter.x * profile(-d, uOuter.y))), half(w));
    }
    return face;
}

half4 main(float2 coord) {
    float2 p = coord - uPad - (uSize * 0.5 + uShift);
    float2 n;
    float d = shapeAt(p, n);
    float delta = uAmount * profile(-d, uInvHeight);
    half4 face = refractedLane(coord, n, d, delta, 0.0);
    if (uBlurCurve.z > 0.5) {
        float e = clamp((d - delta - uBlurCurve.x) / (uBlurCurve.y - uBlurCurve.x), 0.0, 1.0);
        face = mix(face, refractedLane(coord, n, d, delta, uLanes.z), half(e));
    }
    float4 mixed = float4(face);
    if (uFillActive > 0.5) {
        float4 fill = float4(content.eval(coord + float2(uAtlas * uLanes.x, 0.0)));
        // Premultiplied DARKEN/LIGHTEN, including transparent source edges.
        float3 f = mixed.rgb * fill.a;
        float3 g = fill.rgb * mixed.a;
        float3 extreme = (uMix.w > 0.5 ? max(f, g) : min(f, g)) + mixed.rgb * (1.0 - fill.a) + fill.rgb * (1.0 - mixed.a);
        float alpha = mixed.a + fill.a + (uMix.y > 0.0 ? mixed.a + fill.a - mixed.a * fill.a : 0.0);
        mixed = float4(mixed.rgb * uMix.x + extreme * uMix.y + fill.rgb * uMix.z, clamp(alpha, 0.0, 1.0));
    }
    if (uLit > 0.5 && d <= 2.0) {
        float depth = max(-d, 0.0);
        float u = 1.0 - clamp(depth / max(uAmbient.x, 1e-3), 0.0, 1.0);
        float a = mix(uAmbient.w, 1.0, smoothstep(0.0, 1.0, abs(dot(n, uAmbientDir))));
        float w = a * max(uAmbient.y * u * u * u * (u * (6.0 * u - 15.0) + 10.0), 1.0 - smoothstep(uAmbientEdge, max(uAmbientEdge * 2.0, uAmbientEdge + 1.0), depth));
        if (w > 0.0) {
            half4 env = content.eval(coord + n * (depth + uAmbient.z) + float2(uAtlas * uLanes.y, 0.0));
            if (env.a >= 0.001) mixed = float4(float3(env.rgb) / float(env.a) * w + mixed.rgb * (1.0 - w), w + mixed.a * (1.0 - w));
        }
    }
    float3 rgb = mixed.a > 0.001 ? mixed.rgb / mixed.a : float3(0.0);
    return half4(half3(matrix34(rgb, uFace0, uFace1, uFace2)), 1.0);
}
"""

/**
 * 面色要的几路模糊在一个图层里横着排（每路宽、高是 uLane），横、竖各跑一遍高斯。和系统模糊（Skia）一样：sigma 就是配方值，
 * 半径 3σ，取样夹在本路以内（等于 CLAMP）。以前每路一个图层、各挂一个系统模糊，每路都是单独的渲染节点和离屏，
 * 系统每帧遍历节点树的开销跟着成倍涨
 */
internal const val GLASS_BLUR_AGSL = """
uniform shader content;
uniform float2 uDir;       // (1, 0) 横、(0, 1) 竖
uniform float2 uLane;      // 每路的宽、高
uniform float4 uSigma;     // 各路的 sigma（px），≤ 0.5 不模糊

half4 main(float2 coord) {
    float lane = floor(coord.x / uLane.x);
    float left = lane * uLane.x;
    float s = lane < 0.5 ? uSigma.x : (lane < 1.5 ? uSigma.y : (lane < 2.5 ? uSigma.z : uSigma.w));
    if (s <= 0.5) return content.eval(coord);
    int r = int(min(ceil(s * 3.0), 48.0));
    float k = -0.5 / (s * s);
    float2 lo = float2(left + 0.5, 0.5);
    float2 hi = float2(left + uLane.x - 0.5, uLane.y - 0.5);
    float4 sum = float4(0.0);
    float total = 0.0;
    for (int i = 0; i < 97; i++) {
        if (i > r * 2) break;
        float j = float(i - r);
        float w = exp(j * j * k);
        sum += float4(content.eval(clamp(coord + uDir * j, lo, hi))) * w;
        total += w;
    }
    return half4(sum / total);
}
"""

/** 边光环境色那一路先把玻璃覆盖的地方挖空（轮廓上半像素过渡），模糊后只剩外面的背景；别的路原样 */
internal const val GLASS_MASK_AGSL = GLASS_COMMON_AGSL + """
uniform shader content;
uniform float2 uLane;      // 每路的宽、高
uniform float uMaskLane;

half4 main(float2 coord) {
    half4 v = content.eval(coord);
    float left = uMaskLane * uLane.x;
    if (coord.x < left || coord.x >= left + uLane.x) return v;
    float d = sdShape(coord - float2(left, 0.0) - uPad - (uSize * 0.5 + uShift));
    return v * half(clamp(d + 0.5, 0.0, 1.0));
}
"""
