// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.list

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.basic.MochiAlign
import ui.feice.mochi.basic.MochiCheckCircle
import ui.feice.mochi.basic.MochiCheckboxColors
import ui.feice.mochi.basic.MochiDropdown
import ui.feice.mochi.basic.MochiDropdownDefaults
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.opaqueButtonColor
import ui.feice.mochi.basic.parts.MochiGlyphs
import ui.feice.mochi.effect.content.MochiBlurText
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.rememberMochiHaptics

/**
 * 可展开的分组行（iOS 的 DisclosureGroup / 设置里的展开行）：放在 [MochiListCard] / [MochiListSection] 里，
 * 点标题行时行尾的 › 转到朝下，[content] 里的行接在它下面展开（卡片高度平滑变化，分隔线照常画）；再点收起。
 *
 * @param content 展开后的行，直接写 [MochiListRow]，它们和标题行是同一张卡片里的兄弟行
 */
@Composable
public fun MochiListDisclosure(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    colors: MochiListColors = MochiListDefaults.colors(),
    content: @Composable () -> Unit,
) {
    val angle by animateFloatAsState(if (expanded) 90f else 0f, spring(dampingRatio = 0.8f, stiffness = 500f), label = "disclosure")
    MochiListRow(
        title,
        modifier,
        onClick = { onExpandedChange(!expanded) },
        subtitle = subtitle,
        value = value,
        leading = leading,
        trailing = { MochiGlyphs.Chevron(colors.chevron, size = 14.dp, rotation = { angle }) },
        colors = colors,
    )
    if (expanded) content()
}

/** 列表编辑状态：是否在编辑、选中了哪些行（按 key） */
@Stable
public class MochiListEditState internal constructor(editing: Boolean, selected: List<Any>) {
    /** 在编辑：行首出现勾选圈，点行 = 勾选 */
    public var editing: Boolean by mutableStateOf(editing)

    private val keys: SnapshotStateList<Any> = selected.toMutableStateList()

    /** 选中的 key（按勾选顺序） */
    public val selected: List<Any> get() = keys

    public fun isSelected(key: Any): Boolean = key in keys

    public fun toggle(key: Any) {
        if (!keys.remove(key)) keys.add(key)
    }

    public fun selectAll(all: Collection<Any>) {
        keys.clear()
        keys.addAll(all)
    }

    public fun clear() {
        keys.clear()
    }
}

@Composable
public fun rememberMochiListEditState(editing: Boolean = false): MochiListEditState = remember { MochiListEditState(editing, emptyList()) }

/**
 * 可多选的行（iOS 列表的编辑模式）：[state] 进入编辑时，行首从左边滑出圆形勾选圈、整行内容跟着往右让开；
 * 编辑时点行就是勾选（勾的笔画一笔画出来、轻震一下），不在编辑时点行执行 [onClick]。
 * 勾选圈是这一行自己的一部分（[ListRowImpl] 的 prefix）：按下高亮盖住整行（包括勾选圈）、首末行的同心留白和分隔线起点都照常。
 */
@Composable
public fun MochiSelectableRow(
    state: MochiListEditState,
    key: Any,
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    subtitle: String? = null,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
    colors: MochiListColors = MochiListDefaults.colors(),
) {
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)
    val edit by animateFloatAsState(if (state.editing) 1f else 0f, spring(dampingRatio = 0.86f, stiffness = 500f), label = "edit")
    val checked = state.isSelected(key)
    val check by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 1f, stiffness = 700f), label = "check")
    ListRowImpl(
        title,
        modifier,
        onClick = {
            if (state.editing) {
                haptics.tick()
                state.toggle(key)
            } else {
                onClick?.invoke()
            }
        },
        subtitle = subtitle,
        value = value,
        leading = leading,
        colors = colors,
        prefixWidth = if (state.editing) CheckSlot else 0.dp,
        prefix = {
            Box(Modifier.width(CheckSlot * edit).clipToBounds(), contentAlignment = Alignment.CenterStart) {
                MochiCheckCircle(
                    check,
                    MochiCheckboxColors(colors.accent, Color.White, colors.secondary.copy(alpha = 0.45f)),
                    Modifier.graphicsLayer { alpha = edit },
                )
            }
        },
    )
}

/**
 * 菜单式选择行（iOS 的 Picker .menu）：行尾是一个灰色胶囊（当前值 + 上下双箭头），它就是一个 [MochiDropdown]——
 * 按下放大发光、胶囊颜色融进面板、按住直接滑选，和选择列表完全一样；选中项带对勾，选完后胶囊里的值模糊替换。
 * 触发者只有这个胶囊：行本身不再另画一层按下高亮，免得两套反馈叠在一起、高亮位置和真正的按钮对不上。
 */
@Composable
public fun MochiListPickerRow(
    title: String,
    options: ImmutableList<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: MochiListColors = MochiListDefaults.colors(),
    leadingStyle: MochiListLeadingStyle = MochiListLeadingStyle.Tile,
) {
    var open by remember { mutableStateOf(false) }
    val items = remember(options) { options.map { MochiItem(it) }.toImmutableList() }
    MochiListRow(
        title,
        modifier,
        leading = leading,
        leadingStyle = leadingStyle,
        enabled = enabled,
        colors = colors,
        trailing = {
            MochiDropdown(
                expanded = open,
                onExpandedChange = { open = it },
                items = items,
                onItemClick = onSelect,
                enabled = enabled,
                selectedIndex = selectedIndex,
                colors = MochiDropdownDefaults.dropdownColors(
                    // 半透明的灰合成到卡片底色上：融色引擎的按钮色要不透明
                    buttonColor = opaqueButtonColor(MochiDefaults.fill(), MochiDefaults.groupedContainer()),
                    buttonContentColor = colors.content,
                ),
                behavior = MochiDropdownDefaults.behavior(align = MochiAlign.End),
                buttonMinHeight = PickerChipHeight,
                buttonPadding = PaddingValues(start = 12.dp, end = 10.dp),
            ) {
                MochiBlurText(options.getOrElse(selectedIndex) { "" }, style = MochiTheme.textStyles.body, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                MochiGlyphs.UpDown(colors.secondary, size = 13.dp)
            }
        },
    )
}

/** 菜单式选择行的胶囊高度（和紧凑日期胶囊一致） */
private val PickerChipHeight = 34.dp

private val CheckSlot = 40.dp
