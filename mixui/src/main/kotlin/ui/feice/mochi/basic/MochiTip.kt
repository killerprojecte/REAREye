// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.parts.MochiGlyphs
import ui.feice.mochi.effect.content.MochiBlurVisibility
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/**
 * 提示卡（TipKit 的 TipView）：放在列表或页面里，左边图标、标题和说明，右上角关闭。
 * 关掉时整张卡模糊淡出、高度平滑收起（[MochiBlurVisibility]），下面的内容被平滑拉上来。
 *
 * @param visible false 时收起；关闭按钮调 [onDismiss]，由调用方把 visible 改成 false（并记住不再显示）
 */
@Composable
public fun MochiTipCard(
    title: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: (@Composable () -> Unit)? = null,
) {
    MochiBlurVisibility(visible, modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(MochiDefaults.groupedContainer(), MochiRoundedRectangle(TipCorner)),
        ) {
            TipBody(title, message, icon, onDismiss, PaddingValues(16.dp))
        }
    }
}

/**
 * 提示气泡（TipKit 的 popoverTip）：从被介绍的按钮弹出，指着它。按钮和气泡是同一个组件，走菜单引擎
 * （和 [MochiPopoverButton] 同一套）：按下放大发光、按钮颜色融进气泡、融球颈连着按钮，收起时原路缩回按钮——
 * 融球颈就是 iOS 的小尖角，不另画尖角。
 *
 * - [onClick] 不为 null：轻点执行按钮自己的操作（比如收藏），长按才展开提示；为 null 时轻点就展开
 * - 程序主动显示（[visible] = true）时也从按钮长出来；点外面、返回键或右上角关闭都调 `onVisibleChange(false)`
 * - 按钮外观来自 [colors]（默认和图标菜单按钮一样），[size] 是按钮直径
 *
 * @param buttonContent 按钮里的内容（通常是图标）
 */
@Composable
public fun MochiTipPopover(
    title: String,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    icon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    colors: MochiDropdownColors = MochiDropdownDefaults.iconDropdownColors(),
    align: MochiAlign = MochiAlign.Start,
    size: Dp = MochiDropdownDefaults.IconButtonSize,
    buttonContent: @Composable () -> Unit,
) {
    val trigger = if (onClick != null) MochiTrigger.LongPress else MochiTrigger.Press
    val behavior = remember(trigger, align) { MochiDropdownBehavior(trigger = trigger, align = align) }
    DropdownCore(
        expanded = visible,
        onExpandedChange = onVisibleChange,
        items = persistentListOf(),
        onItemClick = {},
        modifier = modifier,
        enabled = enabled,
        selectedIndex = -1,
        colors = colors,
        behavior = behavior,
        physics = MochiDropdownDefaults.physics(),
        motion = MochiTheme.motionScheme.menu,
        menuWidth = MochiPopoverDefaults.Width,
        buttonPadding = PaddingValues(0.dp),
        buttonMinWidth = size,
        buttonMinHeight = size,
        onClick = onClick,
        content = { TipBody(title, message, icon, { onVisibleChange(false) }, PaddingValues(0.dp)) },
        contentPadding = PaddingValues(MochiPopoverDefaults.ContentPadding),
        panelCornerRadius = MochiPopoverDefaults.CornerRadius,
    ) { buttonContent() }
}

@Composable
private fun TipBody(title: String, message: String?, icon: (@Composable () -> Unit)?, onDismiss: () -> Unit, padding: PaddingValues) {
    Row(Modifier.fillMaxWidth().padding(padding), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) {
            CompositionLocalProvider(LocalContentColor provides MochiTheme.colorScheme.primary) {
                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) { icon() }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MochiTheme.textStyles.body.copy(fontWeight = FontWeight.SemiBold), color = MochiTheme.colorScheme.onSurface)
            if (message != null) {
                Text(message, style = MochiTheme.textStyles.subhead, color = MochiTheme.colorScheme.onSurfaceVariant)
            }
        }
        MochiIconButton(
            onClick = onDismiss,
            modifier = Modifier.semantics { contentDescription = "关闭提示" },
            style = MochiButtonStyle.Text,
            size = 28.dp,
        ) { MochiGlyphs.Close(MochiTheme.colorScheme.onSurfaceVariant, size = 11.dp) }
    }
}

private val TipCorner = 22.dp
