// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // 公开的玻璃阴影强度 token，和 LocalMochiMaterials 一样是主题级的

package ui.feice.mochi.effect.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.effect.glass.engine.GlassBody
import ui.feice.mochi.effect.glass.engine.GlassGlowState
import ui.feice.mochi.effect.glass.engine.GlassMerge
import ui.feice.mochi.effect.glass.engine.outlineReach
import ui.feice.mochi.effect.glass.engine.rememberGlassGlow
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiGlass
import ui.feice.mochi.theme.MochiGlassRecipe
import ui.feice.mochi.theme.MochiTheme

/**
 * 这里的玻璃外阴影的强度（0–1，乘在配方的 shadowOpacity 上），画的时候读，不引起重组。
 * 阴影是给「浮在内容上面」的玻璃用的：背后只有纯色底、没有内容时去掉。库里已经接好的地方：
 * 顶栏（[ui.feice.mochi.navigation.MochiTitleBar]）跟着背景渐隐进度，内容停在顶部时没有阴影、往上滚进栏下面时渐渐出来；
 * 面板标题行、大标题下的搜索栏是 0。应用里自己浮在内容上的栏（比如背后可能滚空的底栏）可以按同样的进度提供
 */
public val LocalMochiGlassShadow: ProvidableCompositionLocal<() -> Float> = compositionLocalOf { NoShadowFade }

private val NoShadowFade: () -> Float = { 1f }

/** 不画外阴影，见 [LocalMochiGlassShadow] */
public val MochiNoGlassShadow: () -> Float = { 0f }

/**
 * 当前主题的玻璃（栏和控件）画出自己边界多远：外阴影、黑环都在玻璃外面。不是玻璃时是 0。
 * 把玻璃包进模糊替换、半透明这类按自身范围开离屏的过渡时传给它（[ui.feice.mochi.effect.content.MochiBlurTransition.overflow]、
 * [ui.feice.mochi.effect.content.mochiBlur]），过渡中阴影不会被切成直角矩形
 */
@Composable
public fun mochiGlassOverflow(): Dp {
    val materials = MochiTheme.materials
    val dark = MochiTheme.colorScheme.isDark
    return listOfNotNull(materials.bar, materials.controlGlass)
        .maxOfOrNull { (if (dark) it.dark else it.light).outlineReach() } ?: 0.dp
}

/**
 * 一块玻璃表面（按钮、搜索框、菜单、警告框这类），里面放 [content]。画法和玻璃标签栏同一套（[GlassBody]），
 * 配方跟主题深浅取 [MochiGlass.light] / [MochiGlass.dark]。[cornerRadius] 为 null 时是胶囊。
 * 给了 [interactionSource] 时，按下从手指处亮起 iOS 的光斑：下面的玻璃过一组提饱和矩阵后按光斑形状混进来，
 * 提亮的是玻璃本来的颜色，不叠白（数值读自 iOS 的 _UIFlexInteractionLittleGlowView）。
 * [material] 或 [backdrop] 为 null 时画成 [MochiGlass.fallback] 纯色；没有材质时和栏按钮（[MochiButtonDefaults.barColors]）同色，
 * 和同一排的圆按钮、按钮组放在一起是同一种纯色。
 * 玻璃不能放在 [backdrop] 的来源里面（会取样到自己），见 [MochiBackdrop]。
 */
@Composable
public fun MochiGlassSurface(
    modifier: Modifier = Modifier,
    material: MochiGlass? = MochiTheme.materials.controlGlass,
    backdrop: MochiBackdrop? = LocalMochiBackdrop.current,
    cornerRadius: Dp? = null,
    interactionSource: InteractionSource? = null,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    val fill = rememberGlassFill(material, backdrop?.source)
    if (fill == null) {
        val shape = remember(cornerRadius) { cornerRadius?.let { MochiRoundedRectangle(it) } ?: MochiCapsule }
        Box(
            modifier.background(material?.fallback ?: MochiButtonDefaults.barColors().container, shape),
            contentAlignment,
            content = content,
        )
        return
    }
    val glow = if (fill.material.blurOnly) null else rememberGlassGlow(interactionSource)
    Box(modifier, contentAlignment) {
        GlassSurface(fill, cornerRadius, glow, Modifier.matchParentSize())
        content()
    }
}

/** 一处玻璃要的东西：材质和取样的背景；任何一个没有就画纯色 */
@Stable
internal class GlassFill(val material: MochiGlass, val source: GlassSource)

@Composable
internal fun rememberGlassFill(material: MochiGlass?, source: GlassSource?): GlassFill? =
    if (material == null || source == null) null else remember(material, source) { GlassFill(material, source) }

/**
 * 按 [fill] 画玻璃本体；[scaleX] / [scaleY] 是绕中心的按压形变（背景不跟着放大），平移挂在 [modifier] 的图层上。
 * [cornerRadiusPx] 每帧读的圆角（见 [GlassBody]）；[adjust] 每帧改配方（菜单开合时的透镜）；[merge] 两块融成一块（见 [GlassBody]）
 */
@Composable
internal fun GlassSurface(
    fill: GlassFill,
    cornerRadius: Dp?,
    glow: GlassGlowState?,
    modifier: Modifier = Modifier,
    scaleX: () -> Float = { 1f },
    scaleY: () -> Float = { 1f },
    alpha: () -> Float = { 1f },
    cornerRadiusPx: (() -> Float)? = null,
    adjust: ((MochiGlassRecipe) -> MochiGlassRecipe)? = null,
    merge: (() -> GlassMerge?)? = null,
) {
    val recipe = if (MochiTheme.colorScheme.isDark) fill.material.dark else fill.material.light
    GlassBody(
        backdrop = fill.source.layer,
        recipe = if (adjust == null) ({ recipe }) else ({ adjust(recipe) }),
        modifier = modifier,
        scaleX = scaleX,
        scaleY = scaleY,
        faceBackdrop = fill.source.face,
        alpha = alpha,
        cornerRadius = cornerRadius,
        glow = if (fill.material.blurOnly) null else glow,
        cornerRadiusPx = cornerRadiusPx,
        merge = merge,
    )
}

/**
 * 不拦手势地看着这块区域里的手指，点亮 [glow]：按下处淡入，挪出触摸阈值后跟手并放大，抬起或被滚动接管时散开。
 * 用在按钮组这种由子项各自处理点击的容器上
 */
internal fun Modifier.glassGlowInput(glow: GlassGlowState?): Modifier = if (glow == null) {
    this
} else {
    pointerInput(glow) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            glow.press(down.position)
            val slop = viewConfiguration.touchSlop
            var moved = false
            try {
                while (true) {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    if (!moved && (c.position - down.position).getDistance() > slop) moved = true
                    if (moved) glow.drag(c.position)
                }
            } finally {
                glow.release()
            }
        }
    }
}
