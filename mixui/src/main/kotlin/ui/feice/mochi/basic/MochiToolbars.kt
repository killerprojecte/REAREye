// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ui.feice.mochi.basic.parts.MochiGlyphs
import ui.feice.mochi.effect.mask.MochiSmoothGradient
import ui.feice.mochi.navigation.mochiNavOccluder
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

/**
 * 底部浮动工具栏（iOS 26 的 bottom toolbar）：贴在屏幕底部、导航栏上方的一排玻璃按钮。
 * 按钮用 [MochiBarIconButton] / [MochiBarButton]，一组相关的按钮放进 [MochiBarGroup] 共用一个胶囊；
 * 组之间放 `Spacer(Modifier.weight(1f))` 推到两边。背后有一段往上渐隐的底色（滚动边缘效果），
 * 内容滚到工具栏下面时被柔和地盖住。
 *
 * @param edgeColor 渐隐的底色，应等于页面底色；Transparent 关掉
 */
@Composable
public fun MochiBottomToolbar(
    modifier: Modifier = Modifier,
    edgeColor: Color = MochiDefaults.groupedBackground(),
    windowInsets: WindowInsets = WindowInsets.navigationBars,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            // 浮在内容上面的栏：从这一页的卡片打开时先盖在变形的容器上再淡掉
            .mochiNavOccluder()
            .drawBehind {
                if (edgeColor.alpha > 0f) {
                    // 上半段渐隐、下半段实色，S 曲线接头，看不到渐变的起止线
                    drawRect(MochiSmoothGradient.vertical(edgeColor.copy(alpha = 0f), edgeColor, 0f, size.height * 0.6f))
                    drawRect(edgeColor, Offset(0f, size.height * 0.6f), Size(size.width, size.height * 0.4f))
                }
            }
            .windowInsetsPadding(windowInsets)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * 键盘工具栏（inputAccessoryView）：只在键盘弹出时出现，贴在键盘上方（自带 imePadding），跟着键盘一起升降。
 * 默认左边是「上一项 / 下一项」一组，右边是「完成」；[content] 可以在中间加别的按钮。
 * 放在页面最外层 Box 的底部（`Modifier.align(Alignment.BottomCenter)`）。
 *
 * 整条没有底色：浮在键盘上方的只有按钮本身（iOS 26），每个按钮带一圈细描边（[outline]），
 * 下面是什么内容都分得清按钮边界。不要在外面再给它加背景。
 *
 * @param onPrevious / onNext 为 null 时那个箭头不可用
 * @param outline 按钮描边颜色；Transparent 关掉
 */
@Composable
public fun MochiKeyboardToolbar(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null,
    doneLabel: String = "完成",
    outline: Color = MochiTheme.colorScheme.outlineVariant,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val shown by animateFloatAsState(if (imeVisible) 1f else 0f, spring(dampingRatio = 1f, stiffness = 900f), label = "kbd")
    if (shown <= 0f && !imeVisible) return
    val bar = MochiButtonDefaults.barColors().copy(outline = outline)
    val done = MochiButtonDefaults.colors(MochiButtonStyle.Filled).copy(outline = outline)
    Row(
        modifier
            .fillMaxWidth()
            .imePadding()
            .graphicsLayer {
                alpha = shown
                translationY = (1f - shown) * 16.dp.toPx()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onPrevious != null || onNext != null) {
            MochiBarGroup(colors = bar) {
                MochiBarGroupItem(
                    onClick = { onPrevious?.invoke() },
                    enabled = onPrevious != null,
                    modifier = Modifier.semantics { contentDescription = "上一项" },
                ) { MochiGlyphs.Chevron(bar.content, size = 18.dp, rotation = { -90f }) }
                MochiBarGroupItem(
                    onClick = { onNext?.invoke() },
                    enabled = onNext != null,
                    modifier = Modifier.semantics { contentDescription = "下一项" },
                ) { MochiGlyphs.Chevron(bar.content, size = 18.dp, rotation = { 90f }) }
            }
        }
        content?.invoke(this)
        Spacer(Modifier.weight(1f))
        MochiBarButton(doneLabel, onClick = onDone, colors = done)
    }
}
