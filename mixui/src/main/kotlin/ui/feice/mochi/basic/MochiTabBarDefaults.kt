// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.glass.GlassRole
import ui.feice.mochi.effect.glass.glassOn
import ui.feice.mochi.theme.LocalMochiControlPalette
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile

/**
 * 标签栏的一项。
 * @param icon 图标，参数是当前该用的颜色（选中块经过时在两种颜色间连续过渡）
 * @param selectedIcon 选中时的图标（通常是实心版，iOS 的习惯）；选中块经过时和 [icon] 交叉淡入淡出。null = 一直用 [icon]
 * @param badge 图标右上角的角标：null = 没有，"" = 一个小红点，其他 = 红色胶囊里的文字（通常是数字）
 */
@Immutable
public class MochiTabItem(
    public val label: String,
    public val selectedIcon: (@Composable (tint: Color) -> Unit)? = null,
    public val badge: String? = null,
    public val icon: @Composable (tint: Color) -> Unit,
)

/** 标签栏配色 */
@Immutable
public data class MochiTabBarColors(
    /** 胶囊底色 */
    val container: Color,
    /** 选中块 */
    val indicator: Color,
    /** 选中项的图标和文字 */
    val selectedContent: Color,
    /** 其他项的图标和文字 */
    val content: Color,
    /** 胶囊的投影色 */
    val shadow: Color,
    /** 角标底色和文字 */
    val badge: Color = Color(0xFFFF3B30),
    val onBadge: Color = Color.White,
)

/**
 * 标签栏的手感。
 * @param height 胶囊高度
 * @param itemWidth 每一项的理想宽度（项少时整条变窄居中，像 iOS 26）
 * @param gel 选中块移动时的果冻拉伸强度，0 = 关
 * @param fuseRadius 选中块和拖在它后面那滴「残影」之间融球颈的半径，0 = 不要残影
 * @param pressScale 按住时选中块放大到多少
 * @param hapticProfile 按住滑过标签时的触感
 * @param showLabels 是否显示标签文字；false = 只有图标（更矮、每项更窄，见 [MochiTabBarDefaults.iconOnlyBehavior]）
 * @param selectedLabelOnly 只有选中项显示标签：有文字的布局里，没选中的图标居中，选中时往上让位、标签淡入；
 *   只有图标的布局里，选中项的标签横排在图标右边，那一格跟着变宽（选中块滑动时宽度连续变化）
 */
@Immutable
public data class MochiTabBarBehavior(
    val height: Dp = 64.dp,
    val itemWidth: Dp = 92.dp,
    val gel: Float = 0.6f,
    val fuseRadius: Dp = 22.dp,
    val pressScale: Float = 1.12f,
    val hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
    val showLabels: Boolean = true,
    val selectedLabelOnly: Boolean = false,
)

/**
 * 标签栏右边独立的搜索圆钮（iOS 26 的搜索标签）。
 * 点它 [onActiveChange] (true)：标签栏收成只显示当前标签的小圆，圆钮展开成搜索框并聚焦；
 * 点左边的小圆或按返回 [onActiveChange] (false) 原路缩回。
 * @param active 是否正在搜索（由调用方保存）
 * @param icon 圆钮里的图标，参数是该用的颜色；null = 库自带的放大镜
 */
@Immutable
public class MochiTabBarSearch(
    public val active: Boolean,
    public val onActiveChange: (Boolean) -> Unit,
    public val query: String,
    public val onQueryChange: (String) -> Unit,
    public val placeholder: String = "搜索",
    public val onSearch: (String) -> Unit = {},
    public val icon: (@Composable (tint: Color) -> Unit)? = null,
)

public object MochiTabBarDefaults {
    @Composable
    public fun colors(
        container: Color = MochiTheme.colorScheme.run { if (isDark) surfaceContainerHigh else surfaceContainerLowest },
        indicator: Color = MochiTheme.colorScheme.secondaryContainer,
        // 选中项：外观主题可以在控件配色里指定（Nothing 用红色）；玻璃标签栏的选中块是中性灰，用主题强调色才看得出选中
        selectedContent: Color = LocalMochiControlPalette.current?.tabSelected?.takeIf { it.isSpecified }
            ?: MochiTheme.colorScheme.run { if (glassOn(GlassRole.Bar)) primary else onSecondaryContainer },
        // 玻璃标签栏（iOS）的未选中项用正文色，纯色底栏用次要色
        content: Color = MochiTheme.colorScheme.run { if (glassOn(GlassRole.Bar)) onSurface else onSurfaceVariant },
        shadow: Color = MochiTheme.colorScheme.shadow,
        badge: Color = MochiTheme.colorScheme.error,
        onBadge: Color = MochiTheme.colorScheme.onError,
    ): MochiTabBarColors = remember(container, indicator, selectedContent, content, shadow, badge, onBadge) {
        MochiTabBarColors(container, indicator, selectedContent, content, shadow, badge, onBadge)
    }

    public fun behavior(
        height: Dp = 64.dp,
        itemWidth: Dp = 92.dp,
        gel: Float = 0.6f,
        fuseRadius: Dp = 22.dp,
        pressScale: Float = 1.12f,
        hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
        showLabels: Boolean = true,
        selectedLabelOnly: Boolean = false,
    ): MochiTabBarBehavior =
        MochiTabBarBehavior(height, itemWidth, gel, fuseRadius, pressScale, hapticProfile, showLabels, selectedLabelOnly)

    /** 只有图标的标签栏：52dp 高、每项 60dp，选中块接近圆形；[selectedLabelOnly] = 选中项的标签横排在图标右边 */
    public fun iconOnlyBehavior(
        height: Dp = 52.dp,
        itemWidth: Dp = 60.dp,
        gel: Float = 0.6f,
        fuseRadius: Dp = 18.dp,
        pressScale: Float = 1.12f,
        hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
        selectedLabelOnly: Boolean = false,
    ): MochiTabBarBehavior =
        MochiTabBarBehavior(height, itemWidth, gel, fuseRadius, pressScale, hapticProfile, showLabels = false, selectedLabelOnly)

    /** 默认避开底部导航栏 */
    public val windowInsets: WindowInsets
        @Composable get() = WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)
}

/**
 * 滚动收起（iOS 26）：内容往下滚时标签栏收成只显示当前图标的小胶囊，往上滚或点它就展开。
 * 把 [nestedScrollConnection] 挂到可滚动内容的 `Modifier.nestedScroll` 上，再把它传给 [MochiTabBar]。
 */
@Stable
public class MochiTabBarScrollBehavior internal constructor(private val scope: CoroutineScope, private val threshold: Float) {
    internal val fraction = Animatable(0f)
    private var accumulated = 0f

    /** 是否收起 */
    public var collapsed: Boolean by mutableStateOf(false)
        private set

    /** 收起（带动画） */
    public fun collapse() {
        animateTo(true)
    }

    /** 展开（带动画） */
    public fun expand() {
        animateTo(false)
    }

    private fun animateTo(value: Boolean) {
        accumulated = 0f
        if (collapsed == value) return
        collapsed = value
        scope.launch { fraction.animateTo(if (value) 1f else 0f, MochiMotions.responseSpring(0.42f, 0.82f)) }
    }

    public val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val dy = consumed.y
            if (dy == 0f) return Offset.Zero
            // 方向变了就重新累计；同一方向滚过阈值才切换，轻微抖动不触发
            if (accumulated != 0f && (accumulated > 0f) != (dy > 0f)) accumulated = 0f
            accumulated += dy
            if (accumulated < -threshold) animateTo(true)
            if (accumulated > threshold) animateTo(false)
            return Offset.Zero
        }
    }
}

@Composable
public fun rememberMochiTabBarScrollBehavior(threshold: Dp = 24.dp): MochiTabBarScrollBehavior {
    val scope = rememberCoroutineScope()
    val px = with(LocalDensity.current) { threshold.toPx() }
    return remember(scope, px) { MochiTabBarScrollBehavior(scope, px) }
}
