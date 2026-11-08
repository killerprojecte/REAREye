// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.theme.MochiTheme

/**
 * 强度调整条的配色（照 Codex 的思考强度滑条）。低值时填充段是 [fill]；越往右，[highBlue] → [highPurple] 的流动渐变越浓。
 */
@Immutable
public data class MochiIntensitySliderColors(
    /** 轨道底色 */
    val track: Color,
    /** 轨道内描边（0.5dp） */
    val trackBorder: Color,
    /** 低值时的填充段 */
    val fill: Color,
    /** 高值流动渐变的蓝端 */
    val highBlue: Color,
    /** 高值流动渐变的紫端，也是到顶时爆散粒子的颜色 */
    val highPurple: Color,
    /** 拇指 */
    val thumb: Color,
    /** 拇指描边（0.5dp） */
    val thumbBorder: Color,
    /** 已经越过的刻度 */
    val tickOn: Color,
    /** 还没越过的刻度 */
    val tickOff: Color,
    /** 两端图标 */
    val icon: Color,
)

public object MochiIntensitySliderDefaults {
    /** 整体高度（拇指上下各留 2dp） */
    public val Height: Dp = 32.dp

    /** 轨道高度 */
    public val TrackHeight: Dp = 24.dp

    /** 拇指直径：比轨道上下各多出 2dp */
    public val ThumbSize: Dp = 28.dp

    /** 拇指中心离轨道两端的距离（0 和 1 时） */
    public val ThumbInset: Dp = 13.dp

    /** 刻度圆点直径 */
    public val TickSize: Dp = 4.dp

    /** 两端图标和轨道的间距 */
    public val IconSpacing: Dp = 12.dp

    /** 从这个值开始，高值的流动渐变、星点开始浮现，到 1 时最浓 */
    public const val HighFrom: Float = 0.5f

    /**
     * 默认配色。轨道、描边、刻度跟主题；低值填充是主题主色；高值的蓝、紫是 Codex 的 chart-blue / chart-purple
     * （紫色深色模式更亮），表达的是强度，不跟主题色走。
     */
    @Composable
    public fun colors(
        track: Color = MochiTheme.colorScheme.onSurface.copy(alpha = 0.1f),
        trackBorder: Color = MochiTheme.colorScheme.onSurface.copy(alpha = 0.06f),
        fill: Color = MochiTheme.colorScheme.primary,
        highBlue: Color = CodexBlue,
        highPurple: Color = if (MochiTheme.colorScheme.isDark) CodexPurpleDark else CodexPurpleLight,
        thumb: Color = Color.White,
        thumbBorder: Color = Color.Black.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.2f else 0.15f),
        tickOn: Color = Color.White.copy(alpha = 0.3f),
        tickOff: Color = MochiTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        icon: Color = MochiTheme.colorScheme.onSurfaceVariant,
    ): MochiIntensitySliderColors = remember(track, trackBorder, fill, highBlue, highPurple, thumb, thumbBorder, tickOn, tickOff, icon) {
        MochiIntensitySliderColors(track, trackBorder, fill, highBlue, highPurple, thumb, thumbBorder, tickOn, tickOff, icon)
    }

    private val CodexBlue = Color(0xFF339CFF)
    private val CodexPurpleLight = Color(0xFF924FF7)
    private val CodexPurpleDark = Color(0xFFAD7BF9)
}
