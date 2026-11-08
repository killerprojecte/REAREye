// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorFilter

/**
 * 活力（vibrancy，iOS 的 UIVibrancyEffect / vibrantColorMatrix）：[content] 只当遮罩用，形状里填的是背后的内容——
 * 先模糊 [blur]，再过一遍 [matrix]——所以文字、图标会带上背后的颜色，同时被矩阵拉开明暗，压在照片或材质上也看得清。
 *
 * [matrix] 是 4×5 的色矩阵（行主序 20 个数，第 5 列是 0..1 的偏移，和 iOS 的 inputColorMatrix 同一个写法）。
 * [matrix] 或 [backdrop] 为 null 时照常画 [content]（用它自己的颜色）。Android 10 以下没有图层混合，也照常画。
 * Android 12 以下没有 RenderEffect：不模糊，矩阵改由图层画笔套上，字照样拉得开明暗。
 */
@Composable
public fun MochiVibrant(
    backdrop: MochiBackdrop?,
    matrix: List<Float>?,
    modifier: Modifier = Modifier,
    blur: Dp = 0.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    if (backdrop == null || matrix == null || Build.VERSION.SDK_INT < 29) {
        Box(modifier, contentAlignment, content = content)
        return
    }
    require(matrix.size == 20) { "matrix 要 4×5 = 20 个数" }
    // Android 的 ColorMatrix 偏移按 0..255 算
    val filter = ColorFilter.colorMatrix(ColorMatrix(FloatArray(20) { i -> if (i % 5 == 4) matrix[i] * 255f else matrix[i] }))
    Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }, contentAlignment) {
        content()
        // 只留内容不透明的地方（SrcIn）：形状是内容的，颜色是背后过了矩阵的
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { blendMode = BlendMode.SrcIn }
                .then(if (Build.VERSION.SDK_INT < 31) Modifier.matrixLayer(filter) else Modifier)
                .drawBackdrop(
                    backdrop = backdrop.layer,
                    shape = { RectangleShape },
                    // 31 以下 Kyant 不跑这里的效果（见上面的 matrixLayer）
                    effects = {
                        if (blur > 0.dp) blur(blur.toPx())
                        colorFilter(filter)
                    },
                    highlight = null,
                    shadow = null,
                ),
        )
    }
}

/** 把下面画的东西整层过一遍 [filter]（saveLayer 的画笔），所有版本都能用 */
private fun Modifier.matrixLayer(filter: ColorFilter): Modifier = drawWithContent {
    val paint = Paint().apply { colorFilter = filter }
    drawIntoCanvas { canvas ->
        canvas.saveLayer(size.toRect(), paint)
        drawContent()
        canvas.restore()
    }
}
