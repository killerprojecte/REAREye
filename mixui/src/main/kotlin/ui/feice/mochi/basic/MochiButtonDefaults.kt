// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.theme.MochiTheme

/**
 * 按钮样式：MD3E 的四种，加上 iOS 26 的两种 clear 玻璃。
 * 样式说了算（和 iOS 26 一样）：[Filled] / [Tonal] / [Outlined] / [Text] 永远是纯色，主题开不开液态玻璃都不变。
 * 要玻璃就写玻璃样式：[ProminentGlass] / [Glass] 是 iOS 的 prominentGlass / glass，[ProminentClearGlass] / [ClearGlass]
 * 是 prominentClearGlass / clearGlass（更通透的 .clear 玻璃）。主题的控件没有玻璃（[ui.feice.mochi.theme.MochiMaterials.controlGlass]
 * 为 null）时，prominent 两种按 [Filled] 画、另外两种按 [Tonal] 画。玻璃是长期 Beta
 */
public enum class MochiButtonStyle {
    Filled,
    Tonal,
    Outlined,
    Text,
    ClearGlass,
    ProminentClearGlass,
    Glass,
    ProminentGlass,
    ;

    /** 是四种玻璃样式之一 */
    public val isGlass: Boolean get() = this == Glass || this == ProminentGlass || this == ClearGlass || this == ProminentClearGlass

    /** 玻璃样式没有玻璃可画时退回的纯色样式；纯色样式就是自己 */
    public val solid: MochiButtonStyle get() = when (this) {
        ProminentGlass, ProminentClearGlass -> Filled
        Glass, ClearGlass -> Tonal
        else -> this
    }
}

/**
 * 按钮配色。
 * @param pressedContainer 按下时叠在底色上的颜色（描边 / 文字按钮没有底色，靠它显出按压）
 * @param glow 按压高光的颜色（alpha 是最大亮度）
 */
@Immutable
public data class MochiButtonColors(
    val container: Color,
    val content: Color,
    val outline: Color,
    val pressedContainer: Color,
    val glow: Color,
    val disabledContainer: Color,
    val disabledContent: Color,
)

/**
 * 按钮的手感。
 * @param pressScale 按下时放大到多少
 * @param follow 跟手偏移的上限（按下后手指在按钮里移动，按钮朝那边偏一点并略微拉长）
 * @param cancelSlop 手指拖出按钮多远后松手不算点击（iOS 的「拖出去取消」）
 */
@Immutable
public data class MochiButtonBehavior(
    val pressScale: Float = 1.08f,
    val follow: Dp = 4.dp,
    val cancelSlop: Dp = 48.dp,
    /** 按下放大时每边最多长出多少（宽按钮按比例放大会大得很明显、撑出卡片）；Unspecified = 不限 */
    val maxGrow: Dp = 6.dp,
    /**
     * 按钮组里和谁融在一起：同一个 fuseGroup 的按钮之间才连融球颈。null = 自动按「底色 + 文字颜色」判断，
     * 一样的才算同一种（「取消」和「删除」、色调和填充这种意思相反 / 样式不同的按钮不融）
     */
    val fuseGroup: Any? = null,
)

/**
 * 按钮组里「按下的按钮和旁边的按钮融在一起」的参数。
 * @param fuseRadius 融球颈的最大半径，0 = 不融
 * @param reach 两个按钮间距小于它时才连上（按下放大后间距会变小）
 * @param attraction 旁边的按钮朝按下的那个最多靠过去多远
 */
@Immutable
public data class MochiButtonGroupBehavior(
    val fuseRadius: Dp = 20.dp,
    val reach: Dp = 20.dp,
    val attraction: Dp = 3.dp,
    /** true = 不管是不是同一种按钮都融在一起（旧行为）；默认只有同一种才融，不同种的邻居只往按下的那个靠一点 */
    val fuseAcrossKinds: Boolean = false,
)

public object MochiButtonDefaults {
    /** 文字按钮的内边距 */
    public val ContentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp)

    /** 按钮高度 */
    public val Height: Dp = 48.dp

    /** 顶栏按钮（返回、文字按钮、图标按钮）的高度：44dp，和 iOS 的导航栏按钮一样 */
    public val BarItemSize: Dp = 44.dp

    /** 顶栏左右的边距：按钮外边和下面卡片的外边对齐 */
    public val BarHorizontalPadding: Dp = 16.dp

    /** 顶栏文字按钮的内边距 */
    public val BarContentPadding: PaddingValues = PaddingValues(horizontal = 16.dp)

    /** 顶栏按钮的配色：浅灰底 + 正文色（返回、文字、图标按钮共用，摆在一起才协调） */
    @Composable
    public fun barColors(): MochiButtonColors {
        val cs = MochiTheme.colorScheme
        return remember(cs) {
            // 和分组样式的卡片同色（浅色下是白的）：浮在灰底上看得清，像 iOS 26 顶栏的圆按钮
            val container = if (cs.isDark) cs.surfaceContainerHigh else cs.surfaceContainerLowest
            MochiButtonColors(
                container = container,
                content = cs.onSurface,
                outline = Color.Transparent,
                pressedContainer = Color.Transparent,
                glow = Color.White.copy(alpha = if (cs.isDark) 0.16f else 0.45f),
                // 禁用时底色不变、只把图标文字变淡（iOS 栏按钮的做法）：和同一排可用的按钮、搜索框还是同一种底
                disabledContainer = container,
                disabledContent = cs.onSurface.copy(alpha = 0.38f),
            )
        }
    }

    /** 图标按钮的尺寸 */
    public val IconSize: Dp = 48.dp

    @Composable
    public fun colors(style: MochiButtonStyle = MochiButtonStyle.Filled): MochiButtonColors {
        val cs = MochiTheme.colorScheme
        val glowA = if (cs.isDark) 0.16f else 0.45f
        return remember(style, cs) {
            when (style) {
                MochiButtonStyle.Filled, MochiButtonStyle.ProminentGlass, MochiButtonStyle.ProminentClearGlass -> MochiButtonColors(
                    container = cs.primary,
                    content = cs.onPrimary,
                    outline = Color.Transparent,
                    pressedContainer = Color.Transparent,
                    glow = Color.White.copy(alpha = if (cs.isDark) 0.22f else 0.35f),
                    disabledContainer = cs.onSurface.copy(alpha = 0.12f),
                    disabledContent = cs.onSurface.copy(alpha = 0.38f),
                )

                MochiButtonStyle.Tonal, MochiButtonStyle.Glass, MochiButtonStyle.ClearGlass -> MochiButtonColors(
                    container = cs.secondaryContainer,
                    content = cs.onSecondaryContainer,
                    outline = Color.Transparent,
                    pressedContainer = Color.Transparent,
                    glow = Color.White.copy(alpha = glowA),
                    disabledContainer = cs.onSurface.copy(alpha = 0.12f),
                    disabledContent = cs.onSurface.copy(alpha = 0.38f),
                )

                MochiButtonStyle.Outlined -> MochiButtonColors(
                    container = Color.Transparent,
                    content = cs.primary,
                    outline = cs.outlineVariant,
                    pressedContainer = cs.primary.copy(alpha = 0.10f),
                    glow = cs.primary.copy(alpha = 0.18f),
                    disabledContainer = Color.Transparent,
                    disabledContent = cs.onSurface.copy(alpha = 0.38f),
                )

                MochiButtonStyle.Text -> MochiButtonColors(
                    container = Color.Transparent,
                    content = cs.primary,
                    outline = Color.Transparent,
                    pressedContainer = cs.primary.copy(alpha = 0.10f),
                    glow = cs.primary.copy(alpha = 0.18f),
                    disabledContainer = Color.Transparent,
                    disabledContent = cs.onSurface.copy(alpha = 0.38f),
                )
            }
        }
    }

    public fun behavior(
        pressScale: Float = 1.08f,
        follow: Dp = 4.dp,
        cancelSlop: Dp = 48.dp,
        maxGrow: Dp = 6.dp,
        fuseGroup: Any? = null,
    ): MochiButtonBehavior = MochiButtonBehavior(pressScale, follow, cancelSlop, maxGrow, fuseGroup)

    public fun groupBehavior(
        fuseRadius: Dp = 20.dp,
        reach: Dp = 20.dp,
        attraction: Dp = 3.dp,
        fuseAcrossKinds: Boolean = false,
    ): MochiButtonGroupBehavior = MochiButtonGroupBehavior(fuseRadius, reach, attraction, fuseAcrossKinds)
}
