// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import ui.feice.mochi.navigation.MochiScreenCorners
import ui.feice.mochi.shape.MochiContinuity
import ui.feice.mochi.shape.toConvexPath

/** 起点为屏幕圆弧，终点为面板连续圆角；中间沿同一组控制点插值，不裁掉背景内容。 */
internal data class SheetBackdropShape(private val corners: MochiScreenCorners, private val progress: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = Outline.Generic(
        MochiContinuity.createRoundedRectanglePathSegments(
            size.width.toDouble(),
            size.height.toDouble(),
            corners.topLeft.toDouble(),
            corners.topRight.toDouble(),
            corners.bottomRight.toDouble(),
            corners.bottomLeft.toDouble(),
            progress.toDouble(),
        ).toConvexPath(),
    )
}
