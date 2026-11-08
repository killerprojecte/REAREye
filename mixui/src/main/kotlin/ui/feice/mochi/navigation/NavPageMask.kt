// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import ui.feice.mochi.shape.addG2RoundRect

/** Screen-space contour of the transformed page; individual sheet corners remain independent. */
internal data class NavPageOutline(val rect: Rect, val tl: Float, val tr: Float, val br: Float, val bl: Float)

/** Reuses the page's existing capture layer; does not install a graphicsLayer/path clip. */
internal class NavPageMask {
    private val outside = Path().apply { fillType = PathFillType.EvenOdd }
    private var previous: NavPageOutline? = null
    private var viewport = Size.Unspecified

    // The caller owns an isolated capture/saveLayer. Clear never touches the lower page.
    fun clearOutside(scope: DrawScope, outline: NavPageOutline) {
        if (outline != previous || scope.size != viewport) {
            previous = outline
            viewport = scope.size
            outside.rewind()
            outside.addRect(Rect(0f, 0f, scope.size.width, scope.size.height))
            outside.addG2RoundRect(outline.rect, outline.tl, outline.tr, outline.br, outline.bl)
        }
        scope.drawPath(outside, Color.Black, blendMode = BlendMode.DstOut)
    }
}

/** Dimming follows progress independently of scale, including the default stationary background. */
internal fun navUnderDim(progress: Float, back: Float): Float =
    (progress.coerceIn(0f, 1f) * (1f - back.coerceIn(0f, 1f)) + 0.5f * back.coerceIn(0f, 1f)).coerceIn(0f, 1f)
