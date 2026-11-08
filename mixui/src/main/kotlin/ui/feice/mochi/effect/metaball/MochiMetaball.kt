// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.metaball

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import ui.feice.mochi.effect.glow.MochiGlowState
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.shape.g2Corner
import ui.feice.mochi.theme.MochiGraphics
import ui.feice.mochi.utils.smoothstep

/*
 * 形变连接（融球 / metaball）：两块连续曲率圆角矩形 a、b，靠得够近时像两滴水一样用「颈」连起来。
 * - 颈的粗细由 fuse（smooth-min 半径，px）决定，0 = 不连
 * - 颜色从 a 色按「离谁更近」平滑过渡到 b 色；merge = 1 时 a 整块变成 b 色（按钮长成面板的包裹式展开）
 * - 自带两层柔和投影；可以叠加按压高光（亮边沿着融合后的外轮廓走，连接处里面没有亮边）
 * - API 33+ 用 AGSL；以下自动降级成两块纯色形状（没有颈）
 *
 * 性能：只在需要的像素上跑贵的 shader，每一步都不改变画出来的结果——
 * 阴影 / 颈那一层不画 b 的内部（会被不透明的本体盖住）；b 的本体只在靠边一圈跑完整 shader，
 * 内部用没有 SDF 的便宜 shader（那里两个公式结果恒等，见 bodyHole）；Path 按尺寸缓存。
 */

/** 一块圆角矩形（px）。cornerRadius 超过短边一半就是胶囊 */
@Immutable
public class MochiBlob(public val rect: Rect, public val cornerRadius: Float)

/**
 * 一帧的融球几何。
 * @param fuse 融合半径（px），越大颈越粗越黏，0 = 不连
 * @param blend 0..1，颜色过渡开关：0 = 全是 a 色，1 = 按离谁近过渡
 * @param merge 0..1，a 整块被 b 色吞掉（a、b 重合的包裹式展开用）
 */
@Immutable
public class MochiMetaballFrame(
    public val a: MochiBlob,
    public val b: MochiBlob,
    public val fuse: Float,
    public val blend: Float = 1f,
    public val merge: Float = 0f,
) {
    /** shader 实际需要画的区域（含阴影柔尾和融合膨胀），没必要整屏跑 shader */
    internal fun drawBounds(shadow: MochiMetaballShadow): Rect {
        val pad = shadow.ambientSigma * 3f + shadow.ambientOffset + fuse + 2f
        return Rect(
            min(a.rect.left, b.rect.left) - pad,
            min(a.rect.top, b.rect.top) - pad,
            max(a.rect.right, b.rect.right) + pad,
            max(a.rect.bottom, b.rect.bottom) + pad,
        )
    }

    internal val aCoincidesB: Boolean get() = a.rect == b.rect && a.cornerRadius == b.cornerRadius
}

/** 融球颜色 */
@Immutable
public data class MochiMetaballColors(val a: Color, val b: Color, val shadow: Color)

/**
 * 两层投影（px）：大而淡、往里收的环境影 + 小而很淡的接触影。
 * [ambientInset] 把环境影的形状先往里收这么多再模糊：形状边上只剩很淡的一点，浓度往外慢慢散开，看着轻、没有贴边的一圈
 */
@Immutable
public data class MochiMetaballShadow(
    val ambientOffset: Float,
    val ambientSigma: Float,
    val ambientAlpha: Float,
    val keyOffset: Float,
    val keySigma: Float,
    val keyAlpha: Float,
    val ambientInset: Float = 0f,
) {
    public companion object {
        /** 没有阴影 */
        public val None: MochiMetaballShadow = MochiMetaballShadow(0f, 0.5f, 0f, 0f, 0.5f, 0f)

        /**
         * 按「浮起程度」给阴影：越高越淡越散（大尺寸的玻璃投更大更柔的影子）。
         * 组件里优先用主题的档位（`MochiTheme.shadows.floating.resolve(…)`，见 [ui.feice.mochi.theme.MochiShadows]），
         * 这里是默认档位表背后的连续版本：0 = [ui.feice.mochi.theme.MochiShadows.rest]，1 = floating。
         * @param elevation 0..1（比如展开进度）
         * @param visibility 0..1，整体淡入淡出
         * @param dark 深色背景上阴影要更重才看得出
         */
        public fun elevated(density: Density, elevation: Float, dark: Boolean, visibility: Float = 1f): MochiMetaballShadow =
            ui.feice.mochi.theme.elevation(elevation.coerceIn(0f, 1f)).resolve(density, dark, visibility)
    }
}

/** 融球渲染器：持有 shader 和 Path 缓存，一处融球一个，用 [rememberMochiMetaballRenderer] 创建 */
@Stable
public class MochiMetaballRenderer internal constructor() {
    // 所有融球共用一套 shader，第一次真的要画融球时才编译（一页几十个按钮，每个都编译 3 个 AGSL 会让打开新页卡一下）。
    // 共用是安全的：设好 uniform 后立刻画，录制时 Android 会给 shader 拍一份当时的 uniform 快照
    internal val shaders: MetaballShaders? get() = SharedMetaballShaders.value
    internal val pathA = G2PathCache()
    internal val pathB = G2PathCache()
    internal val unionPath = Path()
    internal val layerPaint = Paint()
}

private object SharedMetaballShaders {
    val value: MetaballShaders? by lazy(LazyThreadSafetyMode.NONE) { if (Build.VERSION.SDK_INT >= 33) MetaballShaders() else null }
}

@Composable
public fun rememberMochiMetaballRenderer(): MochiMetaballRenderer = remember { MochiMetaballRenderer() }

/**
 * 画一帧融球：阴影 + 颈、a 的本体、b 的本体（b 画在最上面）。
 * @param smoothing 连续曲率圆角的平滑度，0 = 普通圆角
 * @param glow 叠加的按压高光；glowOrigin 是这个 DrawScope 原点在 glow 坐标系里的位置
 */
public fun DrawScope.drawMochiMetaball(
    renderer: MochiMetaballRenderer,
    frame: MochiMetaballFrame,
    colors: MochiMetaballColors,
    shadow: MochiMetaballShadow = MochiMetaballShadow.None,
    smoothing: Float = 1f,
    glow: MochiGlowState? = null,
    glowOrigin: Offset = Offset.Zero,
) {
    val f = frame
    val pathA = renderer.pathA.get(f.a.rect, f.a.cornerRadius, smoothing)
    val pathB = renderer.pathB.get(f.b.rect, f.b.cornerRadius, smoothing)
    val opaque = colors.a.alpha >= 1f && colors.b.alpha >= 1f
    val b = f.drawBounds(shadow)
    val shaders = if (MochiGraphics.shaders) renderer.shaders else null
    if (shaders == null) {
        // API < 33 或图形档位低于「高」：没有融球和渐变，两块纯色
        val canvas = drawContext.canvas
        if (!opaque) canvas.saveLayer(b, renderer.layerPaint)
        try {
            val mode = if (opaque) androidx.compose.ui.graphics.BlendMode.SrcOver else BlendMode.Src
            drawPath(pathA, lerpColor(colors.a, colors.b, f.merge), blendMode = mode)
            drawPath(pathB, lerpColor(colors.a, colors.b, max(f.blend, f.merge)), blendMode = mode)
        } finally {
            if (!opaque) canvas.restore()
        }
        return
    }
    val blendWidth = min(f.fuse, 10.dp.toPx()).coerceAtLeast(5.dp.toPx())
    shaders.update(
        f, smoothing,
        blendWidth = blendWidth,
        shadow = shadow,
        colors = colors,
        touch = (glow?.center ?: Offset.Zero) - glowOrigin,
        glow = glow?.intensity ?: 0f,
        glowRadius = glow?.radius ?: 1f,
        glowColor = glow?.spec?.color ?: Color.Transparent,
    )
    if (!opaque) {
        // Transparent materials cannot hide repeated bodies or their own shadow.
        // Both clockwise contours form one non-zero fill, retaining the exact G2
        // outlines. Src replaces the underpaint inside that union in an isolated
        // layer, then the material is composited onto the page only once.
        val union = renderer.unionPath.apply {
            reset()
            addPath(pathA)
            if (!f.aCoincidesB) addPath(pathB)
        }
        val canvas = drawContext.canvas
        canvas.saveLayer(b, renderer.layerPaint)
        try {
            drawRect(shaders.brush, b.topLeft, b.size)
            drawPath(union, shaders.bodyBrush, blendMode = BlendMode.Src)
        } finally {
            canvas.restore()
        }
        return
    }
    if (opaque) {
        // b 的内部会被不透明的本体整块盖住，这一层（每像素 6 次 SDF）只画四周一圈
        drawRing(shaders.brush, b, f.b.rect.innerHole(f.b.cornerRadius))
    } else {
        drawRect(shaders.brush, b.topLeft, b.size)
    }
    if (!f.aCoincidesB) drawPath(pathA, shaders.bodyBrush)
    val hole = bodyHole(f, smoothing, blendWidth)
    if (hole == Rect.Zero) {
        drawPath(pathB, shaders.bodyBrush)
    } else {
        // 靠边一圈：同一条（凸的）Path 画四次，每次用整像素的矩形裁一条边带。
        // 整像素矩形裁剪在 GPU 上就是 scissor，带外的像素根本不跑 shader；不做 Path 布尔运算（凹 Path 会退化成 CPU 光栅化）
        val r0 = f.b.rect
        val l = floor(r0.left) - 1f
        val t = floor(r0.top) - 1f
        val r = ceil(r0.right) + 1f
        val bt = ceil(r0.bottom) + 1f
        clipRect(l, t, r, hole.top) { drawPath(pathB, shaders.bodyBrush) }
        clipRect(l, hole.bottom, r, bt) { drawPath(pathB, shaders.bodyBrush) }
        clipRect(l, hole.top, hole.left, hole.bottom) { drawPath(pathB, shaders.bodyBrush) }
        clipRect(hole.right, hole.top, r, hole.bottom) { drawPath(pathB, shaders.bodyBrush) }
        drawRect(shaders.fillBrush, hole.topLeft, hole.size)
    }
}

/**
 * 「靠近就连上」的融合系数 0..1：两块的间距 ≥ restGap 时是 0，贴上时是 1，中间平滑过渡。
 * 乘上最大融合半径就是 [MochiMetaballFrame.fuse]。
 */
public fun proximityFuse(a: Rect, b: Rect, restGap: Float): Float {
    val dx = max(0f, max(a.left - b.right, b.left - a.right))
    val dy = max(0f, max(a.top - b.bottom, b.top - a.bottom))
    return 1f - smoothstep(0f, restGap, sqrt(dx * dx + dy * dy))
}

/**
 * b 里可以用便宜 shader 的整像素矩形，没有就返回 Rect.Zero。
 * 洞里任一像素 p：离 b 的边超过「角区长度 + 亮边宽 + 2px」→ db ≤ −(亮边宽 + 2)，亮边项恒为 0；
 * 离 a 的包围盒（切比雪夫距离）≥ 过渡带宽 + 2 → da − db > 过渡带宽，融色系数恒为 max(blend, merge)
 * （merge ≥ blend 时不论离 a 多近都成立）。所以便宜公式和完整公式在洞里结果一样。
 */
internal fun bodyHole(f: MochiMetaballFrame, smoothing: Float, blendWidth: Float): Rect {
    val p = f.b.rect
    if (p.width <= 0f || p.height <= 0f) return Rect.Zero
    val e = g2Corner(f.b.cornerRadius, p.width / 2f, p.height / 2f, smoothing).extent
    val inset = e + 2f
    if (p.width <= inset * 2f + 2f || p.height <= inset * 2f + 2f) return Rect.Zero
    val h = Rect(ceil(p.left + inset), ceil(p.top + inset), floor(p.right - inset), floor(p.bottom - inset))
    if (h.width <= 0f || h.height <= 0f) return Rect.Zero
    if (f.merge >= f.blend) return h
    val btn = f.a.rect
    val gx = max(btn.left - h.right, h.left - btn.right)
    val gy = max(btn.top - h.bottom, h.top - btn.bottom)
    return if (max(gx, gy) >= max(blendWidth, 0.5f) + 2f) h else Rect.Zero
}

/** 一定被本体盖住的矩形：四边各往里缩「圆角影响范围 + 2px」，圆角区再大也盖得住 */
internal fun Rect.innerHole(radius: Float): Rect {
    val r = radius.coerceIn(0f, min(width, height) / 2f)
    val inset = r * 0.5f + 2f
    return if (width <= inset * 2f || height <= inset * 2f) Rect.Zero else deflate(inset)
}

/**
 * 在 outer 里画一圈（不画 hole），用四个不重叠的矩形，hole 为空就整块画。
 * 边都对齐到整像素：相邻矩形的抗锯齿边不会叠出一条接缝（hole 往里取整，外框往外取整）
 */
internal fun DrawScope.drawRing(brush: Brush, outerRaw: Rect, hole: Rect) {
    val outer = Rect(floor(outerRaw.left), floor(outerRaw.top), ceil(outerRaw.right), ceil(outerRaw.bottom))
    val h = Rect(ceil(hole.left), ceil(hole.top), floor(hole.right), floor(hole.bottom)).intersect(outer)
    if (hole == Rect.Zero || h.width <= 0f || h.height <= 0f) {
        drawRect(brush, outer.topLeft, outer.size)
        return
    }
    if (h.top > outer.top) drawRect(brush, outer.topLeft, Size(outer.width, h.top - outer.top))
    if (outer.bottom > h.bottom) drawRect(brush, Offset(outer.left, h.bottom), Size(outer.width, outer.bottom - h.bottom))
    if (h.left > outer.left) drawRect(brush, Offset(outer.left, h.top), Size(h.left - outer.left, h.height))
    if (outer.right > h.right) drawRect(brush, Offset(h.right, h.top), Size(outer.right - h.right, h.height))
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = lerp(a.red, b.red, t),
    green = lerp(a.green, b.green, t),
    blue = lerp(a.blue, b.blue, t),
    alpha = lerp(a.alpha, b.alpha, t),
)
