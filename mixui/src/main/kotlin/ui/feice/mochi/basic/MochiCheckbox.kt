// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.rememberMochiHaptics

/** 勾选圈的配色 */
@Immutable
public data class MochiCheckboxColors(
    /** 勾上后的实心圆 */
    val checked: Color,
    /** 圆里的对勾 */
    val checkmark: Color,
    /** 没勾时的空心圈 */
    val ring: Color,
)

public object MochiCheckboxDefaults {
    /** 勾选圈直径（和列表编辑模式的勾选圈一样） */
    public val Size: Dp = 24.dp

    @Composable
    public fun colors(
        checked: Color = MochiTheme.colorScheme.primary,
        checkmark: Color = MochiTheme.colorScheme.onPrimary,
        ring: Color = MochiTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
    ): MochiCheckboxColors = MochiCheckboxColors(checked, checkmark, ring)
}

/**
 * iOS 式圆形勾选圈：没勾是一圈细线；勾上时实心圆从小长大，对勾一笔画出来，轻震一下。
 *
 * [onCheckedChange] 为 null 时只负责显示——整行可点的场景（「不再提示」这类）把点击交给行本身，
 * 让文字和勾选圈一起成为点击区域。
 */
@Composable
public fun MochiCheckbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MochiCheckboxColors = MochiCheckboxDefaults.colors(),
) {
    val haptics = rememberMochiHaptics()
    val progress by animateFloatAsState(if (checked) 1f else 0f, spring(dampingRatio = 1f, stiffness = 700f), label = "check")
    MochiCheckCircle(
        progress,
        colors,
        modifier
            .then(
                if (onCheckedChange == null) {
                    Modifier
                } else {
                    Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox, interactionSource = null, indication = null) {
                        haptics.confirm()
                        onCheckedChange(it)
                    }
                },
            )
            .alpha(if (enabled) 1f else MochiDefaults.DisabledAlpha),
    )
}

/** 勾选圈的画法：[progress] 0 = 空心圈，1 = 实心圆加对勾。列表编辑模式和 [MochiCheckbox] 共用 */
@Composable
internal fun MochiCheckCircle(progress: Float, colors: MochiCheckboxColors, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    val seg = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(modifier.size(MochiCheckboxDefaults.Size)) {
        val r = size.minDimension / 2f
        if (progress < 1f) drawCircle(colors.ring, r - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        if (progress > 0f) {
            drawCircle(colors.checked, r * (0.6f + 0.4f * progress), alpha = progress.coerceIn(0f, 1f))
            path.reset()
            path.moveTo(size.width * 0.28f, size.height * 0.52f)
            path.lineTo(size.width * 0.44f, size.height * 0.67f)
            path.lineTo(size.width * 0.73f, size.height * 0.36f)
            measure.setPath(path, false)
            seg.reset()
            measure.getSegment(0f, measure.length * progress.coerceIn(0f, 1f), seg, true)
            drawPath(seg, colors.checkmark, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
