// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import ui.feice.mochi.effect.content.MochiBlurTransitions
import ui.feice.mochi.effect.content.mochiBlur
import ui.feice.mochi.effect.content.mochiBlurAlpha
import ui.feice.mochi.utils.smoothstep

/**
 * 标签一格的读屏语义：整格是一个「标签页」节点，名字是标签文字。只显示图标、或标签宽度还是 0 时，
 * 文字不可见，读屏和测试都靠这里找到它；整格合成一个节点，选中时不会把图标和文字读两遍
 */
internal fun Modifier.tabSemantics(label: String, selected: Boolean, onClick: () -> Unit): Modifier = clearAndSetSemantics {
    role = Role.Tab
    contentDescription = label
    this.selected = selected
    onClick {
        onClick()
        true
    }
}

/**
 * 横排标签的一格：图标在左、标签在右，整体在 [width] 给的格宽里居中。
 * 标签占的宽度 = [extra] × 选中程度，没选中时图标正好在格子中间；标签跟着选中程度淡入，超出格子的部分裁掉
 */
@Composable
internal fun InlineTabCell(
    width: () -> Float,
    selectedness: () -> Float,
    extra: Float,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
) {
    Layout(
        contents = listOf(icon, {
            Box(
                Modifier.graphicsLayer {
                    mochiBlurAlpha(labelAlpha(selectedness()), labelBlur(selectedness()))
                }.mochiBlur { labelBlur(selectedness()) },
            ) { label() }
        }),
        modifier = modifier.fillMaxHeight().clipToBounds(),
    ) { (iconM, labelM), c ->
        val w = width().roundToInt()
        val h = c.maxHeight
        val ip = iconM.first().measure(Constraints())
        val lp = labelM.first().measure(Constraints())
        layout(w, h) {
            val group = ip.width + extra * selectedness()
            val x = ((w - group) / 2f).roundToInt()
            ip.place(x, (h - ip.height) / 2)
            lp.place(x + ip.width + InlineLabelGap.roundToPx(), (h - lp.height) / 2)
        }
    }
}

/**
 * 只在选中时显示的标签跟着选中程度 [t] 出现 / 消失：模糊淡入淡出（和 [MochiBlurTransitions.Appear] 同样的模糊，
 * 和隐去程度成正比、分档复用模糊效果）。位移由调用方照常给，这里只管透明度和模糊
 */
internal fun labelAlpha(t: Float): Float = smoothstep(0.35f, 1f, t)

internal fun labelBlur(t: Float): Dp {
    if (labelAlpha(t) < 0.02f) return 0.dp
    val hidden = 1f - t.coerceIn(0f, 1f)
    return MochiBlurTransitions.Appear.blur * hidden
}

/** 只在选中时显示标签（竖排）时，没选中的图标往下挪多少才在整格里居中：(2dp 间距 + 12sp 行高) / 2 */
internal val LabelShift = 7.dp

/** 横排标签和图标之间的距离 */
internal val InlineLabelGap = 6.dp

/** 横排在图标右边的标签：比竖排的大一号，和图标同一行 */
internal val InlineLabelStyle = TextStyle(
    fontSize = 13.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight.SemiBold,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

internal val TabLabelStyle = TextStyle(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontWeight = FontWeight.SemiBold,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
