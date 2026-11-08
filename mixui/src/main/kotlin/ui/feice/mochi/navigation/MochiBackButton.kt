// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.basic.LocalBarControlGlass
import ui.feice.mochi.basic.MochiAlign
import ui.feice.mochi.basic.MochiBarIconButton
import ui.feice.mochi.basic.MochiButtonColors
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.basic.MochiDropdownBehavior
import ui.feice.mochi.basic.MochiDropdownDefaults
import ui.feice.mochi.basic.MochiIconDropdown
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.MochiOpenMode
import ui.feice.mochi.basic.MochiTrigger
import ui.feice.mochi.basic.barGlass
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiIcon

/**
 * 顶栏左上角的返回按钮（iOS 26 的圆形返回）：44dp 圆 + 描边「‹」，点了返回上一页（带转场）。
 * 给了 [history] 时，长按会像 iOS 一样弹出返回历史：列出栈里更早的页面（最近的在上），点一项直接回到那一页。
 * 历史菜单就是 [MochiIconDropdown]（包裹式：按钮整个长成面板），和下拉菜单演示页是同一套完整效果：
 * 融球、果冻、按压发光、按住滑选、滑过轻震、滑出面板被橡皮筋拉住。
 *
 * 不在 [MochiNavHost] 里（独立的 Activity、自己管页面的旧代码），或者已经在导航栈的第一页时，点了等于按系统返回键：
 * 交给 Activity 的 OnBackPressedDispatcher，由外面登记的返回处理（关面板、退出 Activity）接着处理，按钮不会点了没反应。
 *
 * @param history 把路由映射成菜单里显示的标题；null = 不支持长按
 * @param onClick 默认先退 MochiNavigator 的上一页，退不了再按系统返回处理
 */
@Composable
public fun MochiBackButton(
    modifier: Modifier = Modifier,
    colors: MochiButtonColors = MochiButtonDefaults.barColors(),
    history: ((route: String) -> String)? = null,
    onClick: (() -> Unit)? = null,
) {
    val nav = MochiNavigator.current
    val earlier = if (history != null && nav != null) nav.backStack.dropLast(1) else emptyList()
    val chevron: @Composable () -> Unit = {
        val color = LocalContentColor.current
        MochiIcon("chevron-back", Modifier.size(22.dp), tint = color) { slot ->
            Canvas(slot) {
                val p = Path().apply {
                    // 「‹」两笔的重心落在圆形按钮正中（按视觉重心居中，不按包围盒）
                    moveTo(size.width * 0.66f, size.height * 0.16f)
                    lineTo(size.width * 0.34f, size.height * 0.5f)
                    lineTo(size.width * 0.66f, size.height * 0.84f)
                }
                drawPath(p, color, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
    val dispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val back = {
        when {
            onClick != null -> onClick()
            nav?.pop() == true -> Unit
            else -> dispatcher?.onBackPressed()
        }
    }
    // 一层层往里推时返回按钮留在原位，不跟着页面滑（见 MochiNavBarItem.kt）
    val shared = modifier.navBarShape(BackBarItemId, colors.container, glass = barGlass(colors))
    val sharedChevron: @Composable () -> Unit = {
        Box(Modifier.mochiNavBarItem(BackBarItemId, contentKey = BackBarItemId), contentAlignment = Alignment.Center) { chevron() }
    }
    if (earlier.isEmpty() || history == null || nav == null) {
        MochiBarIconButton(
            onClick = { back() },
            modifier = shared.semantics { contentDescription = "返回" },
            colors = colors,
            content = sharedChevron,
        )
        return
    }
    // 最近的一页在最上面
    val items = remember(earlier, history) { earlier.reversed().map { MochiItem(history(it)) }.toImmutableList() }
    var open by remember { mutableStateOf(false) }
    // 和不带历史时的 MochiBarIconButton 一样，按钮本身画成栏的玻璃
    CompositionLocalProvider(LocalBarControlGlass provides barGlass(colors)) {
        MochiIconDropdown(
            expanded = open,
            onExpandedChange = { open = it },
            items = items,
            onItemClick = { i -> nav.popTo(earlier.size - 1 - i) },
            modifier = shared.semantics { contentDescription = "返回" },
            colors = MochiDropdownDefaults.iconDropdownColors(buttonColor = colors.container, buttonContentColor = colors.content),
            behavior = MochiDropdownBehavior(openMode = MochiOpenMode.Cover, trigger = MochiTrigger.LongPress, align = MochiAlign.Start),
            size = MochiButtonDefaults.BarItemSize,
            onClick = { back() },
            icon = sharedChevron,
        )
    }
}
