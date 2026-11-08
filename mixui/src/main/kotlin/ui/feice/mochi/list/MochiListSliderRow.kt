// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.dp
import ui.feice.mochi.basic.MochiSlider
import ui.feice.mochi.basic.Text
import ui.feice.mochi.shape.ConcentricBottom
import ui.feice.mochi.shape.ConcentricTop
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile

/**
 * 卡片里的一行滑块（iOS 设置里的音量、亮度）：左边小图标、滑块、右边大图标排在同一行里，行高和普通行一样。
 * 分组里只有这一个滑块时，用分组的小标题（header）说明它调的是什么。
 *
 * 一个分组里有几个滑块（「强度」「偏移」）时给 [title]：标题和实时数值 [valueText] 在上面一行（共用基线，数值靠右、等宽数字），
 * 滑块在下面，[supportingText] 是滑块下面的一行说明；整块是同一行，分隔线、首末留白照常。不要自己用 Column 拼「文字行 + 滑块 + 说明」。
 *
 * @param leading / trailing 两端的图标（比如小喇叭、大喇叭），用次要色画
 * @param title 滑块调的是什么
 * @param valueText 当前值的文字（「45%」「120 ms」），拖动时跟着变；由调用方按单位格式化
 * @param supportingText 滑块下面的说明（「正值提前，负值延后」）
 * @param onValueChangeFinished 松手时调用（拖动中只改界面、松手再保存）
 * @param origin / gel / hapticProfile 见 [MochiSlider]
 */
@Composable
public fun MochiListSliderRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    colors: MochiListColors = MochiListDefaults.colors(),
    origin: Float? = null,
    gel: Float = 0.6f,
    detents: List<Float> = emptyList(),
    title: String? = null,
    valueText: String? = null,
    supportingText: String? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
) {
    val info = remember { ListItemInfo(MochiListDefaults.RowPadding) }
    val grouped = LocalListCardClip.current != null
    if (title != null || supportingText != null) {
        TitledSliderRow(
            value, onValueChange, valueRange, steps, enabled, leading, trailing, colors, origin, gel, detents, title,
            valueText, supportingText, onValueChangeFinished, hapticProfile, info, grouped, modifier,
        )
        return
    }
    info.ownsVerticalInsets = grouped
    Row(
        modifier
            .layoutId(info)
            .fillMaxWidth()
            .defaultMinSize(
                minHeight = if (grouped) {
                    maxOf(
                        MochiListDefaults.RowMinHeight,
                        MochiListDefaults.SectionCorner * 2,
                    )
                } else {
                    MochiListDefaults.RowMinHeight
                },
            )
            .padding(horizontal = MochiListDefaults.RowPadding)
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                val guides = listOf<AlignmentLine>(ConcentricTop, ConcentricBottom).mapNotNull {
                    p[it].takeIf { v -> v != AlignmentLine.Unspecified }?.let { v -> it to v }
                }.toMap()
                layout(p.width, p.height, guides) { p.placeWithLayer(0, 0) { translationY = info.contentCenterOffset() } }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides colors.secondary) {
            leading?.invoke()
            MochiSlider(
                value,
                onValueChange,
                Modifier.weight(1f),
                enabled = enabled,
                valueRange = valueRange,
                steps = steps,
                origin = origin,
                detents = detents,
                onValueChangeFinished = onValueChangeFinished,
                gel = gel,
                hapticProfile = hapticProfile,
            )
            trailing?.invoke()
        }
    }
}

/** 带标题的滑块行：上面「标题 ⋯ 数值」，中间滑块，下面说明；上下留白和普通行一样是 [MochiListDefaults.RowVerticalPadding] */
@Composable
private fun TitledSliderRow(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    leading: (@Composable () -> Unit)?,
    trailing: (@Composable () -> Unit)?,
    colors: MochiListColors,
    origin: Float?,
    gel: Float,
    detents: List<Float>,
    title: String?,
    valueText: String?,
    supportingText: String?,
    onValueChangeFinished: (() -> Unit)?,
    hapticProfile: MochiHapticProfile,
    info: ListItemInfo,
    grouped: Boolean,
    modifier: Modifier = Modifier,
) {
    // 自己带完整的上下留白（文字行 15、说明下 15），卡片不再按锚点补首末白
    info.ownsVerticalInsets = grouped
    val alpha = if (enabled) 1f else MochiDefaults.DisabledAlpha
    val body = MochiTheme.textStyles.body
    Column(
        modifier
            .layoutId(info)
            .fillMaxWidth()
            .padding(horizontal = MochiListDefaults.RowPadding)
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints)
                layout(p.width, p.height) { p.placeWithLayer(0, 0) { translationY = info.contentCenterOffset() } }
            },
    ) {
        Spacer(Modifier.height(MochiListDefaults.RowVerticalPadding))
        if (title != null) {
            Row(Modifier.fillMaxWidth()) {
                ListText(
                    title,
                    style = body,
                    color = colors.content.copy(alpha = alpha),
                    maxLines = 1,
                    modifier = Modifier.weight(1f).alignByBaseline(),
                )
                if (valueText != null) {
                    Text(
                        valueText,
                        style = body.copy(fontFeatureSettings = "tnum"),
                        color = colors.secondary.copy(alpha = alpha),
                        maxLines = 1,
                        modifier = Modifier.padding(start = MainToTailGap).alignByBaseline(),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides colors.secondary) {
                leading?.invoke()
                MochiSlider(
                    value,
                    onValueChange,
                    Modifier.weight(1f),
                    enabled = enabled,
                    valueRange = valueRange,
                    steps = steps,
                    origin = origin,
                    detents = detents,
                    onValueChangeFinished = onValueChangeFinished,
                    gel = gel,
                    hapticProfile = hapticProfile,
                )
                trailing?.invoke()
            }
        }
        if (supportingText != null) {
            ListText(
                supportingText,
                style = MochiTheme.textStyles.footnote,
                color = colors.secondary.copy(alpha = alpha),
                maxLines = 3,
            )
            Spacer(Modifier.height(MochiListDefaults.RowVerticalPadding))
        } else {
            // 滑块本身 44 高、滑块圆钮上下各有一段透明的触摸区，再补一点就和顶上的 15 看起来一样
            Spacer(Modifier.height(SliderBottomPadding))
        }
    }
}

/** 带标题的滑块行没有说明时，滑块下面补的留白（滑块触摸区上下各约 10dp 透明） */
private val SliderBottomPadding = 5.dp
