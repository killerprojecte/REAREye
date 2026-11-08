// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.content.MochiCounterText
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.theme.MochiTheme

/** 角标的尺寸和颜色 */
public object MochiBadgeDefaults {
    /** 小红点直径 */
    public val DotSize: Dp = 10.dp

    /** 胶囊高度（也是最小宽度） */
    public val Height: Dp = 18.dp

    /** 角标底色：主题的错误色（iOS 的系统红） */
    @Composable
    public fun color(): Color = MochiTheme.colorScheme.error

    /** 角标文字颜色 */
    @Composable
    public fun contentColor(): Color = MochiTheme.colorScheme.onError
}

/**
 * 角标（iOS 的 badge）：[text] 为 null = 没有，"" = 小红点，其他 = 红色胶囊里的文字（通常是数字）。
 *
 * 出现 / 消失从 0 弹出 / 缩回（缩放原点在左下角，贴着图标右上角长出来）；内容变了带果冻回弹「弹一下」，
 * 数字按计数器滚动（变大往上、变小往下）；宽度随内容连续变化，消失动画期间继续显示最后的内容。
 * 放在图标上用 [MochiBadgedBox]。
 */
@Composable
public fun MochiBadge(
    text: String?,
    modifier: Modifier = Modifier,
    color: Color = MochiBadgeDefaults.color(),
    contentColor: Color = MochiBadgeDefaults.contentColor(),
) {
    val presence = remember { Animatable(if (text != null) 1f else 0f) }
    val bump = remember { Animatable(1f) }
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        if (text != null) {
            val changed = shown != null && shown != text && presence.value > 0.5f
            shown = text
            if (changed) {
                bump.snapTo(1.25f)
                bump.animateTo(1f, MochiMotions.responseSpring(0.32f, 0.45f))
            } else {
                presence.animateTo(1f, MochiMotions.responseSpring(0.34f, 0.55f))
            }
        } else {
            presence.animateTo(0f, MochiMotions.responseSpring(0.25f, 1f))
            shown = null
        }
    }
    val value = shown ?: return
    val dot = value.isEmpty()
    val prev = remember { arrayOf(value) }
    val dir = remember(value) {
        val a = prev[0].toIntOrNull()
        val b = value.toIntOrNull()
        prev[0] = value
        if (a != null && b != null) b.compareTo(a) else 0
    }
    Box(
        modifier
            .graphicsLayer {
                val s = presence.value.coerceAtLeast(0f) * bump.value
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0f, 1f)
            }
            .then(
                if (dot) {
                    Modifier.size(MochiBadgeDefaults.DotSize)
                } else {
                    // 宽度只跟着计数器自己的逐位宽度动画走（一套动画），不再套 animateContentSize：
                    // 两套尺寸动画会互相打架，而且 animateContentSize 会把正在变宽的文字裁掉一截
                    Modifier
                        .height(MochiBadgeDefaults.Height)
                        .widthIn(min = MochiBadgeDefaults.Height)
                },
            )
            .background(color, MochiCapsule),
        contentAlignment = Alignment.Center,
    ) {
        if (!dot) {
            MochiCounterText(
                value,
                direction = dir,
                color = contentColor,
                style = BadgeTextStyle,
                modifier = Modifier.padding(horizontal = 5.dp),
            )
        }
    }
}

/**
 * 把角标挂在 [content]（通常是图标）的右上角：角标中心落在右上角外侧一点（iOS 的位置），不占布局。
 */
@Composable
public fun MochiBadgedBox(
    badge: String?,
    modifier: Modifier = Modifier,
    color: Color = MochiBadgeDefaults.color(),
    contentColor: Color = MochiBadgeDefaults.contentColor(),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        content()
        MochiBadge(badge, badgeAnchor(badge), color, contentColor)
    }
}

/**
 * 图标右上角的摆放（标签栏和 [MochiBadgedBox] 共用）：左边缘钉住，变长时只往右长（iOS），
 * 不会往左盖住图标；对外只报最小胶囊的宽度，不随文字变化挤动布局。
 */
private fun BoxScope.badgeAnchorModifier(dot: Boolean): Modifier = Modifier
    .align(Alignment.TopEnd)
    .offset(x = if (dot) 3.dp else 9.dp, y = if (dot) (-1).dp else (-5).dp)
    .layout { measurable, _ ->
        val p = measurable.measure(Constraints())
        val w = if (dot) p.width else MochiBadgeDefaults.Height.roundToPx()
        layout(w, p.height) { p.place(0, 0) }
    }

@Composable
internal fun BoxScope.badgeAnchor(badge: String?): Modifier {
    // 消失动画期间 badge 已是 null：按最后一次的形状摆放，免得缩回时跳位置
    var lastDot by remember { mutableStateOf(badge?.isEmpty() ?: false) }
    if (badge != null) lastDot = badge.isEmpty()
    return badgeAnchorModifier(lastDot)
}

private val BadgeTextStyle = TextStyle(
    fontSize = 12.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.SemiBold,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)
