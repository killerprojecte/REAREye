// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 内容高度变化时平滑改变容器高度，宽度即时跟随布局。首次测量直接使用目标高度。
 * 背景放在此修饰之前，padding / height 等目标尺寸放在之后；过渡中按 [shape] 裁切，静止时不额外裁切。
 * 已有手势或逐帧高度动画时设 [enabled] 为 false，让已有动画独占高度，避免二次滞后。
 */
public fun Modifier.mochiAnimateHeight(
    shape: Shape = RectangleShape,
    enabled: Boolean = true,
    animationSpec: AnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 380f, visibilityThreshold = 0.5f),
): Modifier = this then AnimateHeightElement(shape, enabled, animationSpec)

private data class AnimateHeightElement(val shape: Shape, val enabled: Boolean, val spec: AnimationSpec<Float>) :
    ModifierNodeElement<AnimateHeightNode>() {
    override fun create(): AnimateHeightNode = AnimateHeightNode(shape, enabled, spec)

    override fun update(node: AnimateHeightNode) {
        node.shape = shape
        node.enabled = enabled
        node.spec = spec
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiAnimateHeight"
        properties["enabled"] = enabled
        properties["shape"] = shape
    }
}

private class AnimateHeightNode(var shape: Shape, var enabled: Boolean, var spec: AnimationSpec<Float>) :
    Modifier.Node(),
    LayoutModifierNode {
    private var animation: Animatable<Float, AnimationVector1D>? = null
    private var target = -1
    private var job: Job? = null

    override fun onDetach() {
        job?.cancel()
        job = null
        animation = null
        target = -1
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        if (isLookingAhead) return layout(p.width, p.height) { p.place(0, 0) }
        var a = animation
        if (a == null || !enabled) {
            if (a == null || target != p.height || job?.isActive == true) {
                job?.cancel()
                job = null
                a = Animatable(p.height.toFloat())
                animation = a
            }
            target = p.height
        } else if (target != p.height) {
            target = p.height
            job?.cancel()
            job = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) { a.animateTo(p.height.toFloat(), spec) }
        }
        val height = constraints.constrainHeight(a!!.value.roundToInt().coerceAtLeast(0))
        val resizing = enabled && (a.isRunning || abs(a.value - target) > 0.5f)
        return layout(p.width, height) {
            p.placeWithLayer(0, 0) {
                clip = resizing
                // 裁切跟随可见高度而非子项的最终高度；静止后释放裁切，按压和高光不会被硬边截住。
                this.shape = HeightClipShape(this@AnimateHeightNode.shape, height.toFloat())
            }
        }
    }
}

private data class HeightClipShape(val surface: Shape, val height: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        surface.createOutline(Size(size.width, height), layoutDirection, density)
}
