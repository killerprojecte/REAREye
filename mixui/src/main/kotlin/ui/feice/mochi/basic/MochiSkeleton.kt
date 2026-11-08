// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiDefaults

/**
 * 骨架占位（加载中的灰块）：[content] 照常排版、只是先不显示，原位置画一块同样大小的灰色圆角块，
 * 一道柔光从左往右慢慢扫过。[loading] 变成 false 时灰块淡出、内容淡入——两者大小、位置完全一样，
 * 所以加载完成时没有任何东西跳动。配合 [ui.feice.mochi.navigation.rememberMochiNavSettled]：转场结束再开始加载。
 *
 * 文字之类不知道最终宽度的内容，给 content 放一段长度相近的占位文字即可（它不会显示出来，只决定灰块的大小）。
 * @param shape 灰块的形状（默认 8dp 连续曲率圆角；文字行用胶囊更像）
 */
@Composable
public fun MochiSkeleton(
    loading: Boolean,
    modifier: Modifier = Modifier,
    shape: Shape = MochiRoundedRectangle(8.dp),
    color: Color = MochiDefaults.fill(),
    content: @Composable () -> Unit,
) {
    val shown by animateFloatAsState(if (loading) 1f else 0f, tween(260), label = "skeleton")
    val sweep = rememberInfiniteTransition(label = "sweep")
    val x by sweep.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x")
    Box(
        modifier.drawWithContent {
            // 内容在下面淡入，骨架盖在上面淡出
            drawContent()
            if (shown <= 0.001f) return@drawWithContent
            val outline = shape.createOutline(size, layoutDirection, this)
            drawOutline(outline, color, alpha = shown)
            val w = size.width
            val band = Brush.linearGradient(
                listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                start = Offset(w * x - w * 0.3f, 0f),
                end = Offset(w * x + w * 0.3f, size.height),
            )
            drawOutline(outline, band, alpha = shown)
        },
    ) {
        Box(Modifier.graphicsLayer { alpha = 1f - shown }) { content() }
    }
}
