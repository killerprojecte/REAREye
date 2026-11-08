// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions

/**
 * 图标「弹一下」（SF Symbols 的 bounce）：[trigger] 每变成一个新的、非 null / 非 false 的值，内容先快速压扁一点，
 * 再带回弹地弹回原样。第一次组合不弹。用在点按后的图标、刚被选中的标签图标上。
 *
 * @param depth 压下去的程度（0.2 = 缩到 0.8）
 */
public fun Modifier.mochiBounce(trigger: Any?, depth: Float = 0.2f): Modifier = this then BounceElement(trigger, depth)

private class BounceElement(val trigger: Any?, val depth: Float) : ModifierNodeElement<BounceNode>() {
    override fun create(): BounceNode = BounceNode(trigger, depth)

    override fun update(node: BounceNode) {
        node.depth = depth
        node.onTrigger(trigger)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiBounce"
        properties["trigger"] = trigger
    }

    override fun equals(other: Any?): Boolean = other is BounceElement && other.trigger == trigger && other.depth == depth

    override fun hashCode(): Int = trigger.hashCode() * 31 + depth.hashCode()
}

private class BounceNode(private var last: Any?, var depth: Float) :
    Modifier.Node(),
    LayoutModifierNode {
    private val scale = Animatable(1f)
    private var job: Job? = null

    fun onTrigger(t: Any?) {
        if (t == last) return
        last = t
        if (t == null || t == false || !isAttached) return
        job?.cancel()
        job = coroutineScope.launch {
            // 压下去很快，弹回来带一点过冲
            scale.animateTo(1f - depth, tween(70))
            scale.animateTo(1f, MochiMotions.responseSpring(0.38f, 0.45f))
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        return layout(p.width, p.height) {
            p.placeWithLayer(0, 0) {
                val s = scale.value
                scaleX = s
                scaleY = s
            }
        }
    }
}

/**
 * 跟着一个连续进度（比如滚动位置）显出 / 隐去：[visible] 0 → 1 时从透明、模糊、偏下 [rise] 清晰起来。
 * 折叠大标题、顶栏小标题这类和滚动绑定的显隐用它，不用计时器动画——拖到一半停住就停在一半。
 * [visible] 在摆放 / 绘制阶段读，不重组；模糊结果缓存着（见 [mochiBlur]），跟着滚动移动、淡化时不重新模糊
 */
public fun Modifier.mochiBlurReveal(visible: () -> Float, blur: Dp = 4.dp, rise: Dp = 0.dp): Modifier = graphicsLayer {
    val v = visible().coerceIn(0f, 1f)
    mochiBlurAlpha(v, mochiRevealBlur(v, blur))
    translationY = (1f - v) * rise.toPx()
}.mochiBlur { mochiRevealBlur(visible(), blur) }

/** [mochiBlurReveal] 这一刻的模糊半径：几乎看不见（< 2%）时不模糊 */
internal fun mochiRevealBlur(visible: Float, blur: Dp): Dp {
    val v = visible.coerceIn(0f, 1f)
    return if (v < 0.02f) 0.dp else blur * (1f - v)
}

/**
 * [mochiBlurReveal] 的 graphicsLayer 版本，和别的图层属性写在同一个块里时用。
 * 模糊直接设在这一层上：这一层每帧移动、缩放时系统会每帧重新模糊；能用 Modifier 版本时优先用它
 */
public fun GraphicsLayerScope.mochiBlurReveal(visible: Float, blur: Dp = 4.dp, rise: Dp = 0.dp) {
    val v = visible.coerceIn(0f, 1f)
    alpha = v
    translationY = (1f - v) * rise.toPx()
    renderEffect = if (v < 0.02f) null else mochiCachedBlur((1f - v) * blur.toPx())
}
