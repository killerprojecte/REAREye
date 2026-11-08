// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import android.annotation.SuppressLint
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import ui.feice.mochi.utils.smoothstep

/**
 * 标记一个可以「打开成页面」的元素（卡片、图标）。`navigator.push(route, from = key)` 时，
 * 新页面从这个元素的位置、圆角、底色开始长成整屏，关闭时缩回来。
 * 页面打开着或正在转场时，元素自己隐藏——视觉上是它「变成了」页面（和 iOS 打开应用时图标消失一样）。
 *
 * 只在 [MochiNavHost] 里面生效。
 * @param cornerRadius 元素的圆角（转场起点和终点的圆角）
 * @param color 元素的底色（转场开头用它盖住页面，再淡成页面本身）
 */
public fun Modifier.mochiNavSource(key: String, cornerRadius: Dp, color: Color): Modifier =
    this then NavSourceElement(key, cornerRadius, color)

/**
 * 共享元素标记（保留兼容）。转场现在是「卡片的底变形 + 页面内容淡入淡出」，元素不再单独飞，
 * 标了也只是照常绘制；两边排版不同的元素飞起来会错位，iOS 打开卡片也不这么做。
 */
@Deprecated("转场改成容器变形，不再需要共享元素；直接去掉这个修饰即可")
@Suppress("UNUSED_PARAMETER")
public fun Modifier.mochiSharedElement(key: String): Modifier = this

private data class NavSourceElement(val key: String, val radius: Dp, val color: Color) : ModifierNodeElement<NavSourceNode>() {
    override fun create(): NavSourceNode = NavSourceNode(key, radius, color)

    override fun update(node: NavSourceNode) {
        node.update(key, radius, color)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiNavSource"
        properties["key"] = key
        properties["cornerRadius"] = radius
        properties["color"] = color
    }
}

private class NavSourceNode(key: String, private var radius: Dp, private var color: Color) :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    LayoutAwareModifierNode,
    DrawModifierNode {
    private val handle = SourceHandle(key)
    private var nav: MochiNavigator? = null

    // 导航器和所在的页对一个节点来说一辈子不变（换页会换节点），挂上时读一次就够
    @SuppressLint("SuspiciousCompositionLocalModifierRead")
    override fun onAttach() {
        nav = currentValueOf(LocalMochiNavigator)
        handle.entry = currentValueOf(LocalMochiNavEntry)
        handle.shown = currentValueOf(LocalMochiPeerPageShown)
        handle.layer = requireGraphicsContext().createGraphicsLayer()
        sync()
        nav?.registerSource(handle)
    }

    override fun onDetach() {
        nav?.unregisterSource(handle)
        nav = null
        handle.coords = null
        handle.layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        handle.layer = null
    }

    fun update(key: String, radius: Dp, color: Color) {
        this.radius = radius
        this.color = color
        if (key != handle.key) {
            nav?.unregisterSource(handle)
            handle.key = key
            nav?.registerSource(handle)
        }
        if (isAttached) sync()
    }

    private fun sync() {
        handle.radius = with(requireDensity()) { radius.toPx() }
        handle.color = color
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        handle.coords = coordinates
    }

    override fun ContentDrawScope.draw() {
        val layer = handle.layer
        if (layer == null) {
            drawContent()
            return
        }
        // 录下来：转场时页面容器把它按容器大小缩放着画进去（和容器一起长大、一起缩回）
        val active = nav?.isSourceActive(handle.key) == true
        // 转场中这张卡片画进离屏纹理：容器每帧换缩放时只缩放这张图，卡片上的文字不随缩放每帧重新光栅化。
        // 平时不开（几十张卡片各占一张纹理太费显存）
        layer.compositingStrategy = if (active) CompositingStrategy.Offscreen else CompositingStrategy.Auto
        layer.record { this@draw.drawContent() }
        handle.size = size
        if (!active) drawLayer(layer)
    }
}

/**
 * 标记页面里浮在内容上面的遮挡物（顶栏、底栏、工具栏、滚动边缘渐隐）。从这一页的卡片「打开应用」式展开时，
 * 卡片原本有一部分压在栏下面：变形中的容器不会一出发就把栏一下盖住，而是栏先留在容器上面、随打开进度淡掉，
 * 缩回来源时原路淡回来，落地时卡片仍在栏下面。
 *
 * 库里的 [MochiTitleBar]、[mochiScrollEdgeFade]、MochiTabBar、MochiBottomToolbar 已经自带；自己画的浮动栏加在最外层。
 * 不在 [MochiNavHost] 里时没有效果。
 */
public fun Modifier.mochiNavOccluder(): Modifier = this then OccluderElement

private object OccluderElement : ModifierNodeElement<OccluderNode>() {
    override fun create(): OccluderNode = OccluderNode()

    override fun update(node: OccluderNode) = Unit

    override fun hashCode(): Int = 0x0cc1

    override fun equals(other: Any?): Boolean = other === this

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiNavOccluder"
    }
}

private class OccluderNode :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    LayoutAwareModifierNode,
    DrawModifierNode {
    private val handle = OccluderHandle()
    private var nav: MochiNavigator? = null

    // 导航器和所在的页对一个节点来说一辈子不变（换页会换节点），挂上时读一次就够
    @SuppressLint("SuspiciousCompositionLocalModifierRead")
    override fun onAttach() {
        val n = currentValueOf(LocalMochiNavigator) ?: return
        handle.entry = currentValueOf(LocalMochiNavEntry) ?: return
        handle.shown = currentValueOf(LocalMochiPeerPageShown)
        nav = n
        handle.layer = requireGraphicsContext().createGraphicsLayer()
        n.registerOccluder(handle)
    }

    override fun onDetach() {
        nav?.unregisterOccluder(handle)
        nav = null
        handle.layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        handle.layer = null
        handle.coords = null
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        handle.coords = coordinates
    }

    override fun ContentDrawScope.draw() {
        val layer = handle.layer
        if (layer == null) {
            drawContent()
            return
        }
        // During a source morph the host owns this exact layer. Drawing it here
        // too doubles translucent surfaces and the shadow, including carried bars.
        layer.record { this@draw.drawContent() }
        val entry = handle.entry
        if (entry == null || nav?.isCarriedUnderSource(entry) != true) drawLayer(layer)
    }
}

private data class SharedElementElement(val key: String) : ModifierNodeElement<SharedElementNode>() {
    override fun create(): SharedElementNode = SharedElementNode(key)

    override fun update(node: SharedElementNode) {
        node.update(key)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiSharedElement"
        properties["key"] = key
    }
}

private class SharedElementNode(key: String) :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    LayoutAwareModifierNode,
    DrawModifierNode {
    private val handle = SharedElementHandle(key)
    private var nav: MochiNavigator? = null

    // 导航器和所在的页对一个节点来说一辈子不变（换页会换节点），挂上时读一次就够
    @SuppressLint("SuspiciousCompositionLocalModifierRead")
    override fun onAttach() {
        val n = currentValueOf(LocalMochiNavigator) ?: return
        nav = n
        handle.entry = currentValueOf(LocalMochiNavEntry)
        handle.shown = currentValueOf(LocalMochiPeerPageShown)
        handle.layer = requireGraphicsContext().createGraphicsLayer()
        n.registerElement(handle)
    }

    override fun onDetach() {
        nav?.unregisterElement(handle)
        nav = null
        handle.layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        handle.layer = null
        handle.coords = null
    }

    fun update(key: String) {
        if (key == handle.key) return
        nav?.unregisterElement(handle)
        handle.key = key
        nav?.registerElement(handle)
    }

    override fun onPlaced(coordinates: LayoutCoordinates) {
        handle.coords = coordinates
    }

    override fun ContentDrawScope.draw() {
        val layer = handle.layer
        val n = nav
        if (layer == null || n == null) {
            drawContent()
            return
        }
        // 先录下来（转场时 MochiNavHost 拿它画到飞行中的位置），不在转场中就原地画
        layer.record { this@draw.drawContent() }
        handle.size = size
        drawLayer(layer)
    }
}

/**
 * 推入新页时收起（标签栏、底部工具栏用）。侧推时照 iOS 的 hidesBottomBarWhenPushed：元素留在下层页里，跟着它往左退、被新页盖住；
 * 从来源打开时元素往下滑出屏幕，返回时原路滑回来。
 * 位移只由转场进度决定（同一条非线性弹簧，再加一道缓动：页面长到六成时已经完全收起），
 * 所以返回手势拖一半、中途掉头都是连续的。元素所在的页必须是 [MochiNavHost] 里的一页。
 */
public fun Modifier.mochiHideWhenCovered(): Modifier = this then HideWhenCoveredElement

private object HideWhenCoveredElement : ModifierNodeElement<HideWhenCoveredNode>() {
    override fun create(): HideWhenCoveredNode = HideWhenCoveredNode()

    override fun update(node: HideWhenCoveredNode) = Unit

    override fun hashCode(): Int = 0x60c0

    override fun equals(other: Any?): Boolean = other === this

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiHideWhenCovered"
    }
}

private class HideWhenCoveredNode :
    Modifier.Node(),
    LayoutModifierNode,
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode,
    DrawModifierNode {
    private val handle = HideHandle()
    private var nav: MochiNavigator? = null

    // 导航器和所在的页对一个节点来说一辈子不变（换页会换节点），挂上时读一次就够
    @SuppressLint("SuspiciousCompositionLocalModifierRead")
    override fun onAttach() {
        nav = currentValueOf(LocalMochiNavigator)
        handle.entry = currentValueOf(LocalMochiNavEntry)
        handle.shown = currentValueOf(LocalMochiPeerPageShown)
        handle.layer = requireGraphicsContext().createGraphicsLayer()
        nav?.registerHidden(handle)
    }

    override fun onDetach() {
        nav?.unregisterHidden(handle)
        nav = null
        handle.coords = null
        handle.layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        handle.layer = null
    }

    // 从来源打开（或缩回）的转场中：这一页由 MochiNavHost 整页缓存着缩放，栏由它代画、照常往下滑；
    // 这里不画也不动，不然栏每帧一动，整页的缓存就作废了
    private fun carried(): Boolean {
        val e = handle.entry ?: return false
        return nav?.isCarriedUnderSource(e) == true
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        handle.height = p.height.toFloat()
        return layout(p.width, p.height) {
            p.placeWithLayer(0, 0) {
                translationY = if (carried()) 0f else nav?.hideOffset(handle) ?: 0f
            }
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        handle.coords = coordinates
        val e = handle.entry ?: return
        if ((nav?.coverOf(e) ?: 0f) > 0f) return
        val root = coordinates.findRootCoordinates()
        // 元素底边到屏幕底边的距离（没盖住时量的），滑出去要多走这一段
        handle.below = (root.size.height - coordinates.positionInRoot().y - coordinates.size.height).coerceAtLeast(0f)
    }

    override fun ContentDrawScope.draw() {
        val layer = handle.layer
        if (layer == null) {
            drawContent()
            return
        }
        // 录下来给 MochiNavHost 代画用；被代画时这里不画
        layer.record { this@draw.drawContent() }
        if (!carried()) drawLayer(layer)
    }
}

/** 收起进度到这里时元素已经完全滑出去 */
internal const val HideDoneAt = 0.6f
