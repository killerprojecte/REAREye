// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.basic.Text
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.effect.content.mochiBlurReveal
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.GlassSource
import ui.feice.mochi.effect.glass.LocalGlassSources
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.LocalMochiGlassShadow
import ui.feice.mochi.effect.glass.MochiBackdrop
import ui.feice.mochi.effect.glass.glassOn
import ui.feice.mochi.effect.glass.source
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/** Shared defaults for compact titles and the collapsed state of large-title pages. */
public object MochiTitleBarDefaults {
    /** Title-bar height excluding the status-bar inset. */
    public val Height: Dp = 56.dp
}

/**
 * 紧凑标题栏，也是大标题折叠后的顶栏。页面已有「配色」「主题色板」这类卡片外分组小标题时默认只使用此顶栏，
 * 不再在正文重复放置独立大标题。内容预留状态栏和 [barHeight]，不要再给本组件添加状态栏 padding。
 *
 * @param titleProgress 标题可见进度，默认始终显示；目录页可在绘制阶段读取外部滚动状态，保留可展开大标题
 * @param backgroundProgress 背景 / 滚动边缘渐隐进度，默认跟随标题。标题一直显示（默认的 titleProgress）时，
 *   要接内容的滚动状态：`MochiScrollEdgeDefaults.progress(listState)`。否则内容停在顶部时渐隐也全浓，
 *   往下延的那段渐隐会盖住第一行内容
 * @param navigationIcon 左侧库顶栏按钮；侧推页登记稳定 navId，MochiBackButton 自动登记
 * @param actions 右侧库顶栏按钮；与侧推对应页面共用稳定 navId 和图标 contentKey
 * @param backdrop 栏下面内容的录制（`rememberMochiBarBackdrop()`，内容加 `mochiBackdropSource`）：给了，主题的栏按钮是玻璃时
 *   按钮取样它画成玻璃；null 沿用外面提供的（没有就是纯色）
 */
@Composable
public fun MochiTitleBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    background: Color = MochiDefaults.groupedBackground(),
    barHeight: Dp = MochiTitleBarDefaults.Height,
    titleProgress: () -> Float = { 1f },
    backgroundProgress: () -> Float = titleProgress,
    backdrop: MochiBackdrop? = null,
) {
    if (backdrop != null) {
        CompositionLocalProvider(LocalMochiBackdrop provides backdrop) {
            MochiTitleBar(title, modifier, subtitle, navigationIcon, actions, background, barHeight, titleProgress, backgroundProgress)
        }
        return
    }
    // 文字跟着栏后面的亮度换深 / 浅（iOS 栏上的文字也是按背后亮度取色）：深色主题里滚到大片亮色内容下面时，
    // 渐进模糊偏白，白字会看不清
    val tone = remember { MochiScrollEdgeTone() }
    val textColor = MochiScrollEdgeDefaults.textColor(tone, MochiTheme.colorScheme.onSurface)
    val subColor = MochiScrollEdgeDefaults.textColor(tone, MochiTheme.colorScheme.onSurfaceVariant, secondary = true)
    // 栏里的玻璃（按钮、搜索框）取样「栏下面实际看到的样子」：背景来源（已经渐进模糊过）再叠上这一层渐隐底色。
    // 渐隐单独画在一层里录下来，不和按钮在同一层（玻璃不能取样到自己）
    val under = LocalMochiBackdrop.current?.takeIf { glassOn(GlassRole.Control) }?.source
    val fadeLayer = if (under != null) rememberLayerBackdrop() else null
    val barSource = if (under != null && fadeLayer != null) {
        val layer = rememberCombinedBackdrop(under.layer, fadeLayer)
        val face = rememberCombinedBackdrop(under.face, fadeLayer)
        remember(layer, face) { GlassSource(layer, face) }
    } else {
        null
    }
    val edgeFade = Modifier.mochiScrollEdgeFade(background, alpha = backgroundProgress, tone = tone)
    Box(modifier.fillMaxWidth().then(if (fadeLayer == null) edgeFade else Modifier)) {
        if (fadeLayer != null) {
            Box(Modifier.matchParentSize().layerBackdrop(fadeLayer).then(edgeFade))
        }
        // 栏里玻璃的外阴影跟着背景渐隐走：内容停在顶部、栏后面只有纯色底时没有阴影，内容滚进栏下面时渐渐出来
        CompositionLocalProvider(
            LocalGlassSources provides LocalGlassSources.current.let { it.copy(bar = barSource ?: it.bar) },
            LocalMochiGlassShadow provides backgroundProgress,
        ) {
            TitleBarRow(title, subtitle, navigationIcon, actions, barHeight, titleProgress, textColor, subColor)
        }
    }
}

@Composable
private fun TitleBarRow(
    title: String,
    subtitle: String?,
    navigationIcon: (@Composable () -> Unit)?,
    actions: (@Composable RowScope.() -> Unit)?,
    barHeight: Dp,
    titleProgress: () -> Float,
    textColor: Color,
    subColor: Color,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(barHeight)
            .padding(horizontal = MochiButtonDefaults.BarHorizontalPadding),
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) { navigationIcon?.invoke() }
        Column(
            Modifier
                .align(Alignment.Center)
                .padding(horizontal = 64.dp)
                .mochiBlurReveal(titleProgress, rise = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 标题换了（切换对话、改名）原地模糊替换，不瞬切
            MochiBlurText(
                title,
                style = MochiTheme.textStyles.navTitle,
                color = textColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                contentAlignment = Alignment.Center,
            )
            if (subtitle != null) {
                MochiBlurText(
                    subtitle,
                    style = MochiTheme.textStyles.footnote.copy(fontSize = 12.sp, lineHeight = 14.sp),
                    color = subColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    contentAlignment = Alignment.Center,
                )
            }
        }
        if (actions != null) {
            Row(
                Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}
