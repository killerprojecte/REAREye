// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:multiple-emitters-check") // The pre-Android 13 path returns immediately.

package ui.feice.mochi.effect.glass.engine

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import ui.feice.mochi.effect.glass.LocalMochiGlassShadow
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiGlassRecipe
import ui.feice.mochi.theme.MochiTheme

/**
 * 胶囊形的玻璃本体（标签栏、MochiGlassCapsule、玻璃按钮和面板共用）：取样 [backdrop]，按 [recipe] 画；[scaleX] / [scaleY] 是绕中心的
 * 拉伸（按压、拖动时），背景会反着缩回去，不跟着变形。[onDrawSurface] 画在玻璃上面（没有背景时的纯色底等）。
 * 要淡入淡出用 [alpha]，不要在 [modifier] 上挂透明度：外阴影画在玻璃边界外，按玻璃矩形开的离屏缓冲会把它切成直角。
 * [cornerRadius] 为 null 时是胶囊，否则是这个圆角的连续曲率圆角矩形（弹窗、菜单、面板）。
 * [cornerRadiusPx] 给了就每帧读它（px），盖过 [cornerRadius]：形状在动画里从胶囊变成圆角矩形时用（菜单开合）。
 * [glow] 不为 null 时按下有光斑（见 [GlassGlowState]），坐标是玻璃里的 px。
 * [merge] 给了就每帧读它：节点是几块玻璃的外包框，画成它们的（光滑）并集（菜单从按钮组里脱出来、按钮组），只在 Android 13+ 生效。
 *
 * Android 13+ 整块是一个节点（[glassNode]）：降采样算面色、全分辨率收尾。更低的系统没有着色器，一层画完（只有模糊或什么都不做）
 */
@Composable
internal fun GlassBody(
    backdrop: Backdrop,
    recipe: () -> MochiGlassRecipe,
    modifier: Modifier = Modifier,
    scaleX: () -> Float = { 1f },
    scaleY: () -> Float = { 1f },
    onDrawSurface: (DrawScope.() -> Unit)? = null,
    faceBackdrop: Backdrop = backdrop,
    alpha: () -> Float = { 1f },
    cornerRadius: Dp? = null,
    glow: GlassGlowState? = null,
    cornerRadiusPx: (() -> Float)? = null,
    merge: (() -> GlassMerge?)? = null,
    content: @Composable BoxScope.() -> Unit = {},
) {
    val outline = remember(cornerRadius) { cornerRadius?.let { MochiRoundedRectangle(it) } ?: MochiCapsule }
    val density = LocalDensity.current.density
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Box(
            modifier.drawBackdrop(
                backdrop = backdrop,
                shape = { if (cornerRadiusPx != null) MochiRoundedRectangle((cornerRadiusPx() / density).dp) else outline },
                effects = { legacyGlass(recipe()) },
                highlight = null,
                shadow = null,
                layerBlock = {
                    this.scaleX = scaleX()
                    this.scaleY = scaleY()
                    this.alpha = alpha()
                },
                onDrawSurface = onDrawSurface ?: {},
            ),
            content = content,
        )
        return
    }
    // 外阴影按所在位置的强度缩放（背后没内容时淡掉，见 LocalMochiGlassShadow）
    val shadow = LocalMochiGlassShadow.current
    // 圆角（全分辨率 px）；胶囊按短边的一半。几块融在一起时按主形状算，不按外包框
    val params: (Size) -> GlassParams = { full ->
        val m = merge?.invoke()
        val s = m?.shape?.size ?: full
        val r = (
            cornerRadiusPx?.invoke() ?: cornerRadius?.let {
                it.value * density
            } ?: (s.minDimension / 2f)
            ).coerceAtMost(s.minDimension / 2f)
        val base = recipe()
        val f = shadow().coerceIn(0f, 1f)
        GlassParams(if (f >= 1f || base.shadowOpacity <= 0f) base else base.copy(shadowOpacity = base.shadowOpacity * f), r, m)
    }
    // 光斑要读画好的玻璃：有光斑时把玻璃录成一份来源
    val glassLayer = if (glow != null) rememberLayerBackdrop() else null
    val dark = MochiTheme.colorScheme.isDark
    // 单独一层：兄弟控件重画时不连带重跑这块玻璃
    Box(modifier.graphicsLayer()) {
        Box(
            Modifier
                .matchParentSize()
                .then(if (glassLayer != null) Modifier.layerBackdrop(glassLayer) else Modifier)
                .glassNode(backdrop, faceBackdrop, params, scaleX, scaleY, alpha, onDrawSurface),
        )
        // 光斑那层只在亮着时组合：它按坐标取样玻璃，挂着不动也会在滚动时让整块玻璃重画
        val glowing by remember(glow) { derivedStateOf { glow != null && glow.progress > 0.001f } }
        if (glowing && glow != null && glassLayer != null) {
            GlassGlowLayer(glassLayer, glow, outline, scaleX, scaleY, alpha, dark)
        }
        Box(Modifier.matchParentSize().graphicsLayer { this.alpha = alpha() }, content = content)
    }
}

/** 光斑层在玻璃四周固定多留的宽度（另加长边的 10%），见 [GlassGlowLayer] */
private val GlowSlack = 12.dp

/**
 * 光斑那一层：把录下来的玻璃（[glass]）过提饱和矩阵，按光斑的径向透明度留下手指附近那一块，裁在玻璃的轮廓里
 * （跟着 [scaleX] / [scaleY] 绕中心缩放），整体不透明度 [GlowAlpha]，放大时按倍数变暗
 */
@Composable
private fun BoxScope.GlassGlowLayer(
    glass: Backdrop,
    glow: GlassGlowState,
    outline: androidx.compose.ui.graphics.Shape,
    scaleX: () -> Float,
    scaleY: () -> Float,
    alpha: () -> Float,
    dark: Boolean,
) {
    val matrix = if (dark) GlowMatrixDark else GlowMatrixLight
    val clip = remember { androidx.compose.ui.graphics.Path() }
    val pad = remember { IntArray(1) }
    Box(
        Modifier
            .matchParentSize()
            // 离屏层按节点矩形裁：按下放大后玻璃长出节点，四周留出放大和拖动形变的余量，否则光斑在原来的边上断开
            .layout { measurable, constraints ->
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val p = (GlowSlack.toPx() + maxOf(w, h) * 0.1f).toInt()
                pad[0] = p
                val placeable = measurable.measure(Constraints.fixed(w + p * 2, h + p * 2))
                layout(w, h) { placeable.place(-p, -p) }
            }
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                this.alpha = (GlowAlpha * glow.progress / glow.scale * alpha()).coerceIn(0f, 1f)
            }
            .drawWithContent {
                if (glow.progress <= 0.001f) return@drawWithContent
                val sx = scaleX()
                val sy = scaleY()
                val o = pad[0].toFloat()
                val c = Offset(size.width / 2f, size.height / 2f)
                val w = (size.width - o * 2f) * sx
                val h = (size.height - o * 2f) * sy
                clip.reset()
                clip.addOutline(outline.createOutline(Size(w, h), layoutDirection, this))
                clip.translate(Offset(c.x - w / 2f, c.y - h / 2f))
                clipPath(clip) { this@drawWithContent.drawContent() }
                val p = glow.center + Offset(o, o)
                drawRect(
                    Brush.radialGradient(
                        *GlowStops,
                        center = Offset(c.x + (p.x - c.x) * sx, c.y + (p.y - c.y) * sy),
                        radius = GlowReach.toPx() * glow.scale,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
            .drawBackdrop(
                backdrop = glass,
                shape = { RectangleShape },
                effects = { if (glow.progress > 0.001f) selection(matrix, 1f) },
                highlight = null,
                shadow = null,
            ),
    )
}
