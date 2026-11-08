// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop

/**
 * 页里的玻璃（标签栏等）在「从来源打开」转场中要看到的东西：正盖上来的那一页（变形中的容器）。
 *
 * 玻璃平时只读自己页面的背景来源；容器展开时，栏（mochiNavOccluder）先留在容器上面再淡掉，
 * 只读页面来源的话玻璃里透出的是被盖住的旧页面内容，叠在容器上就乱了。和页面来源组合起来用：
 * 转场中按容器此刻的变换（包括下层页的缩放）画进玻璃，平时什么都不画。
 */
@Composable
internal fun rememberMochiNavCoverBackdrop(): Backdrop? {
    val nav = LocalMochiNavigator.current ?: return null
    val entry = LocalMochiNavEntry.current ?: return null
    return remember(nav, entry) { NavCoverBackdrop(nav, entry) }
}

private class NavCoverBackdrop(private val nav: MochiNavigator, private val entry: MochiNavEntry) : Backdrop {
    override val isCoordinatesDependent: Boolean = true
    private val matrix = Matrix()

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val c = coordinates?.takeIf { it.isAttached } ?: return
        val top = nav.above(entry) ?: return
        if (top.source == null || !top.inTransition) return
        val layer = top.coverLayer ?: return
        val from = top.coverCoords?.takeIf { it.isAttached } ?: return
        // 容器图层的坐标 → 玻璃的坐标：带上下层页此刻的缩放，不只是平移
        matrix.reset()
        c.transformFrom(from, matrix)
        withTransform({ transform(matrix) }) { drawLayer(layer) }
    }
}

/**
 * 栏被 MochiNavHost 代画、往下滑着的时候（「从来源打开」转场里，见 [mochiHideWhenCovered]），栏的录制停在原位、只是整张往下挪；
 * 栏里读背景的玻璃仍按原位取样，背后的内容就跟着栏一起往下错开一截，外轮廓那一圈和玻璃里会叠出一份错位的页面。
 * 这里按栏此刻滑下去的量把背景反着挪回来。只有这一层每帧重画，下层页的整页缓存不受影响
 */
@Composable
internal fun rememberMochiNavCarriedBackdrop(inner: Backdrop): Backdrop {
    val nav = LocalMochiNavigator.current ?: return inner
    val entry = LocalMochiNavEntry.current ?: return inner
    return remember(nav, entry, inner) { CarriedBackdrop(inner, nav, entry) }
}

private class CarriedBackdrop(private val inner: Backdrop, private val nav: MochiNavigator, private val entry: MochiNavEntry) : Backdrop {
    override val isCoordinatesDependent: Boolean = true

    override fun DrawScope.drawBackdrop(density: Density, coordinates: LayoutCoordinates?, layerBlock: (GraphicsLayerScope.() -> Unit)?) {
        val dy = coordinates?.takeIf { it.isAttached }?.let { nav.carriedShift(entry, it) } ?: 0f
        if (dy == 0f) {
            with(inner) { drawBackdrop(density, coordinates, layerBlock) }
        } else {
            translate(0f, -dy) { with(inner) { drawBackdrop(density, coordinates, layerBlock) } }
        }
    }
}
