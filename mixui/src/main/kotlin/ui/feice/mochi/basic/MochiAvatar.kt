// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import kotlinx.collections.immutable.ImmutableList
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/** 头像的默认值 */
public object MochiAvatarDefaults {
    /** 默认直径 */
    public val Size: Dp = 40.dp

    /** 头像组里相邻头像重叠的比例 */
    public const val Overlap: Float = 0.3f

    /** 框式头像：外框和头像之间的缝（iOS 26 账户按钮：头像放在圆形按钮里，四周留一圈） */
    public val FrameInset: Dp = 3.dp

    /** iOS 通讯录的默认头像：灰色渐变 */
    public val Gradient: Brush = Brush.verticalGradient(listOf(Color(0xFFA5ABB6), Color(0xFF858A94)))
}

/**
 * 头像：圆形，里面放图片等任意内容（[content] 已经裁成圆）。
 */
@Composable
public fun MochiAvatar(modifier: Modifier = Modifier, size: Dp = MochiAvatarDefaults.Size, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center, content = content)
}

/**
 * 框式头像按钮（iOS 26 的账户按钮）：外面是和顶栏按钮同一套的圆形按钮，头像同心地放在里面，四周留 [inset] 一圈。
 * 按下时整个按钮从手指处发光、轻轻形变，和其他按钮一致。所有可点的头像都用它，不要自己画圈。
 *
 * @param content 头像内容（图片、文字头像等），已经裁成圆
 */
@Composable
public fun MochiAvatarButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = MochiButtonDefaults.BarItemSize,
    inset: Dp = MochiAvatarDefaults.FrameInset,
    colors: MochiButtonColors = MochiButtonDefaults.barColors(),
    content: @Composable BoxScope.() -> Unit,
) {
    MochiIconButton(onClick, modifier, enabled, colors = colors, size = size) {
        MochiAvatar(size = size - inset * 2, content = content)
    }
}

/**
 * 不可点的框式头像（列表行首、资料卡）：和 [MochiAvatarButton] 同一个样子，外框用填充色。
 */
@Composable
public fun MochiFramedAvatar(
    modifier: Modifier = Modifier,
    size: Dp = MochiAvatarDefaults.Size,
    inset: Dp = MochiAvatarDefaults.FrameInset,
    frameColor: Color = MochiDefaults.fill(),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.size(size).background(frameColor, CircleShape), contentAlignment = Alignment.Center) {
        MochiAvatar(size = size - inset * 2, content = content)
    }
}

/**
 * 文字头像（iOS 通讯录没有照片时的样子）：灰色渐变底，白色首字母。
 *
 * @param initials 显示的字（一般取姓名的一两个字）
 */
@Composable
public fun MochiAvatar(
    initials: String,
    modifier: Modifier = Modifier,
    size: Dp = MochiAvatarDefaults.Size,
    background: Brush = MochiAvatarDefaults.Gradient,
    contentColor: Color = Color.White,
) {
    MochiAvatar(modifier, size) {
        Box(Modifier.matchParentSize().background(background))
        Text(
            initials,
            style = MochiTheme.textStyles.body.copy(fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold),
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * 头像组（多人协作、群聊的那串头像）：头像从左到右互相压住一截（后面的在下面），每个头像外面描一圈底色，
 * 超过 [max] 个时最后一个位置显示「+N」。
 *
 * @param borderColor 描边颜色，应等于头像组所在的底色
 */
@Composable
public fun MochiAvatarGroup(
    initials: ImmutableList<String>,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    max: Int = 4,
    borderColor: Color = MochiDefaults.groupedContainer(),
) {
    val shown = if (initials.size > max) initials.take(max - 1) else initials
    val extra = initials.size - shown.size
    val ring = 2.dp
    Layout(
        content = {
            shown.forEach { MochiAvatar(it, Modifier.border(ring, borderColor, CircleShape).clip(CircleShape), size = size) }
            if (extra > 0) {
                Box(
                    Modifier
                        .size(size)
                        .border(ring, borderColor, CircleShape)
                        .clip(CircleShape)
                        .background(MochiDefaults.fill()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+$extra",
                        style = MochiTheme.textStyles.footnote.copy(fontWeight = FontWeight.SemiBold),
                        color = MochiTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val ps = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val px = size.roundToPx()
        val step = (px * (1f - MochiAvatarDefaults.Overlap)).roundToInt()
        val w = if (ps.isEmpty()) 0 else step * (ps.size - 1) + px
        layout(w, px) {
            // 前面的压在后面的上面：倒着放，zIndex 由放置顺序决定
            for (i in ps.indices.reversed()) ps[i].place(i * step, 0)
        }
    }
}
