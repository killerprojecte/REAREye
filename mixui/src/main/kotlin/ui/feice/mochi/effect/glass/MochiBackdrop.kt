// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // The backdrop source is window plumbing, like LocalMochiMaterials.

package ui.feice.mochi.effect.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import ui.feice.mochi.effect.glass.engine.GlassBody
import ui.feice.mochi.theme.MochiDefaults

/**
 * 玻璃背后的内容来源。页面内容加 [mochiBackdropSource]，浮在上面的栏（玻璃）通过 [LocalMochiBackdrop] 拿到它：
 *
 * ```kotlin
 * val backdrop = rememberMochiBackdrop()
 * Box {
 *     PageContent(Modifier.mochiBackdropSource(backdrop))
 *     CompositionLocalProvider(LocalMochiBackdrop provides backdrop) {
 *         MochiTabBar(…)
 *     }
 * }
 * ```
 *
 * 玻璃不能画在来源里面（会画到自己身上）：栏和内容是兄弟，栏在后面。
 * 来源有独立的绘制边界：内容变化时才更新录制，兄弟控件的动画不会连带重录整页。
 * 玻璃引用这个图层，所以内容滚动时玻璃跟着变。
 */
@Stable
public class MochiBackdrop internal constructor(internal val layer: LayerBackdrop) {
    /** 面色读的那份：和本体同一份 */
    internal val face: Backdrop get() = layer
}

/**
 * @param background 录进来源的底色，应等于页面底色。玻璃拿到的背景因此是不透明的，
 *   模糊后透明的地方不会露出栏下面没模糊的内容
 */
@Composable
public fun rememberMochiBackdrop(background: Color = MochiDefaults.groupedBackground()): MochiBackdrop {
    val bg = rememberUpdatedState(background)
    val onDraw: ContentDrawScope.() -> Unit = remember {
        {
            drawRect(bg.value)
            drawContent()
        }
    }
    val layer = rememberLayerBackdrop(onDraw = onDraw)
    return remember(layer) { MochiBackdrop(layer) }
}

/** 把这块内容作为玻璃的背景来源，见 [MochiBackdrop]；null 时什么都不做 */
public fun Modifier.mochiBackdropSource(backdrop: MochiBackdrop?): Modifier = if (backdrop == null) {
    this
} else {
    this.graphicsLayer().layerBackdrop(backdrop.layer)
}

/**
 * 顶栏按钮要的背景来源：主题的栏按钮是玻璃（[ui.feice.mochi.theme.MochiMaterials.controlGlass]）时才录，否则 null（不多录一层）。
 * 自己用「懒加载列表 + MochiTitleBar」排页面时，列表加 `Modifier.mochiBackdropSource(它)`、MochiTitleBar 传 `backdrop = 它`；
 * 标题脚手架已经接好
 */
@Composable
public fun rememberMochiBarBackdrop(background: Color = MochiDefaults.groupedBackground()): MochiBackdrop? =
    if (glassOn(GlassRole.Control)) rememberMochiBackdrop(background) else null

/** 浮在内容上的玻璃从这里拿背景来源；null 时玻璃按材质的 fallback 画纯色 */
public val LocalMochiBackdrop: androidx.compose.runtime.ProvidableCompositionLocal<MochiBackdrop?> =
    staticCompositionLocalOf { null }

/** 玻璃取样用的两份背景：本体和降采样面色读的那份（见 [GlassBody]） */
@Stable
internal class GlassSource(val layer: Backdrop, val face: Backdrop)

internal val MochiBackdrop.source: GlassSource get() = GlassSource(layer, layer)

/**
 * 玻璃取样用的几份背景，合在一个 [LocalGlassSources] 里传；谁提供谁只改自己那一项（`copy(bar = …)`）。
 * 页面自己的来源仍是公开的 [LocalMochiBackdrop]（应用接入用它），不在这里。
 *
 * - [window]：窗口层面「浮层底下」的内容。最外层的 MochiNavHost 录整页提供它，每个浮层再叠上自己的内容传给从它里面打开的浮层
 *   （面板里点开的菜单能透出面板）。只给浮层用：页面里的玻璃取样它会取到自己
 * - [page]：标题脚手架、标签页脚手架给顶栏录的正文，也给从正文里打开的浮层当后备取样来源（见 [overlayBackdropSource]）
 * - [overlay]：当前浮层（菜单、警告框、面板）的玻璃取样的那份：打开这个浮层时它底下的内容
 * - [bar]：栏里的玻璃取样的那份：栏自己的底色（滚动边缘渐隐）叠在背景来源上面，见 MochiTitleBar
 */
@Immutable
internal data class GlassSources(
    val window: GlassSource? = null,
    val page: MochiBackdrop? = null,
    val overlay: GlassSource? = null,
    val bar: GlassSource? = null,
)

internal val LocalGlassSources: androidx.compose.runtime.ProvidableCompositionLocal<GlassSources> =
    staticCompositionLocalOf { GlassSources() }

/**
 * 浮层（菜单、警告框、面板）底下的内容：通常是最外层 MochiNavHost 录的整页。导航主机不录整页时
 * （`MochiNavHost(windowGlass = false)`，页面里有 WebView），退回到打开浮层那一处页面的背景来源（[LocalMochiBackdrop]），
 * 页面自己用截图做的来源也能让菜单是玻璃；再没有就用标题脚手架录的正文（[GlassSources.page]），都没有才画纯色
 */
@Composable
internal fun overlayBackdropSource(): GlassSource? {
    val sources = LocalGlassSources.current
    return sources.window ?: LocalMochiBackdrop.current?.source ?: sources.page?.source
}

/** 栏里的按钮、搜索框取样的背景：有栏底色的叠加版就用它，否则用背景来源本身 */
@Composable
internal fun barGlassSource(): GlassSource? = LocalGlassSources.current.bar ?: LocalMochiBackdrop.current?.source
