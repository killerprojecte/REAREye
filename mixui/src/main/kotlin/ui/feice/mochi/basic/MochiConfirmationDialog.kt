// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.anim.MochiMotion
import ui.feice.mochi.theme.MochiTheme

/** 操作确认的尺寸 */
public object MochiConfirmationDialogDefaults {
    /** 从按钮弹出时的宽度 */
    public val PopoverWidth: Dp = 270.dp
}

/**
 * 操作确认（iOS 的 confirmationDialog），没有来源按钮时的样子。iOS 26 去掉了从底部滑上来的操作列表，
 * 改成和警告框同一种居中对话框：标题、说明在上，操作按钮是胶囊，两个时并排、更多时竖排（取消在最下面）。
 * 这里直接就是 [MochiDialog]（同一套出现动画、按钮按压发光、预见式返回），不另画一套。
 *
 * 和警告框不同的是：点对话框外面等同于取消。有来源按钮时用 [MochiConfirmationDialogBox]，从按钮弹出。
 *
 * [MochiDialogActionRole.Destructive] 红字，[MochiDialogActionRole.Cancel] 排在最后；点了先执行 onClick 再调 [onDismissRequest]。
 */
@Composable
public fun MochiConfirmationDialog(
    visible: Boolean,
    onDismissRequest: () -> Unit,
    actions: ImmutableList<MochiDialogAction>,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    colors: MochiDialogColors = MochiDialogDefaults.colors(),
) {
    // 只有说明没有标题时，说明当标题显示（对话框总要有一行醒目的字）
    MochiDialog(
        visible = visible,
        onDismissRequest = onDismissRequest,
        title = title ?: message.orEmpty(),
        actions = actions,
        modifier = modifier,
        message = if (title != null) message else null,
        dismissOnOutsideTap = true,
        colors = colors,
    )
}

/**
 * 有来源按钮的操作确认：iOS 26 的做法是从按钮弹出。按钮和面板就是一个 [MochiDropdown]（同一套实现：按下放大发光、
 * 按钮颜色融进面板、按住直接滑选、滑出面板被橡皮筋拉住），[title] / [message] 显示在选项上面；
 * 取消操作不显示（点外面就是取消）。选中一项时先执行它的 onClick，菜单随后收起。
 */
@Composable
public fun MochiConfirmationDialogBox(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    actions: ImmutableList<MochiDialogAction>,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    enabled: Boolean = true,
    colors: MochiDropdownColors = MochiDropdownDefaults.dropdownColors(),
    behavior: MochiDropdownBehavior = MochiDropdownDefaults.behavior(align = MochiAlign.Center),
    physics: MochiDropdownPhysics = MochiDropdownDefaults.physics(),
    motion: MochiMotion = MochiTheme.motionScheme.menu,
    buttonPadding: PaddingValues = MochiDropdownDefaults.ButtonPadding,
    buttonMinHeight: Dp = MochiDropdownDefaults.ButtonMinHeight,
    buttonContent: @Composable RowScope.() -> Unit,
) {
    val main = remember(actions) { actions.filter { it.role != MochiDialogActionRole.Cancel } }
    val items = remember(main) {
        main.map { MochiItem(it.label, destructive = it.role == MochiDialogActionRole.Destructive) }.toImmutableList()
    }
    val header = listOfNotNull(title, message).joinToString("\n").ifEmpty { null }
    DropdownCore(
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        items = items,
        onItemClick = { i -> main.getOrNull(i)?.takeIf { it.enabled }?.onClick?.invoke() },
        modifier = modifier,
        enabled = enabled,
        selectedIndex = -1,
        colors = colors,
        behavior = behavior,
        physics = physics,
        motion = motion,
        menuWidth = MochiConfirmationDialogDefaults.PopoverWidth,
        buttonPadding = buttonPadding,
        buttonMinWidth = 0.dp,
        buttonMinHeight = buttonMinHeight,
        onClick = null,
        header = header,
        buttonContent = buttonContent,
    )
}
