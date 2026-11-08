// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.content

import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy as AlphaStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import ui.feice.mochi.theme.MochiGraphics

/**
 * 给内容加模糊，模糊后的画面缓存成纹理：内容和模糊程度不变时，这一层怎么移动、缩放、淡化都不再重新模糊。
 *
 * 直接在 graphicsLayer 里设 renderEffect 时，系统渲染线程只在「内容、模糊效果、图层自己坐标里的裁剪范围」都没变时
 * 才复用上一帧的模糊结果；图层一移动、一缩放（或者屏幕上别处在局部刷新），裁剪范围就变了，每一帧都要重新模糊一遍。
 * 这里把模糊图层再画进一层离屏图层（四周留出模糊外溢的那圈），模糊只在离屏层重画时算一次，之后每帧只合成这张图。
 *
 * - 半径大时降采样：内容画到 1/2 或 1/4 分辨率上，用等效的小半径模糊，再放大画回来。糊开以后本来就没有细节，
 *   放大看不出区别，GPU 要填的像素少 4～16 倍；半径小（快清楚）时按原分辨率，收尾不发虚
 * - 默认半径按 1.5 倍分档复用同一个模糊效果（见 [mochiCachedBlur]），过渡中每层只重新模糊 8 次左右；
 *   [radius] 在绘制阶段读，变了但落在同一档时不重画
 * - [continuous] = true：半径逐帧连续变化、不分档（整页推进推出这类大面积模糊，分档会看到一级一级地跳）。
 *   半径变了的帧要重新模糊，但靠降采样，大半径时每次只算 1/16 的像素
 * - 半径为 0（或者系统不支持模糊）时不建任何图层，和没加一样
 * - 位移、缩放、透明度、裁剪放在它外面的 graphicsLayer 里：`Modifier.graphicsLayer { alpha = … }.mochiBlur { … }`；
 *   想让模糊外溢的那圈也被裁掉，把裁剪放在它外面，想让外溢的软边留着，把裁剪放在它里面
 * - [overflow]：内容画出自己边界的距离（玻璃的外阴影，见 [ui.feice.mochi.effect.glass.mochiGlassOverflow]）。
 *   模糊图层按这一圈往外多录，模糊时外面的阴影不会被切成直角矩形；默认 0，只录内容自己的范围
 */
public fun Modifier.mochiBlur(continuous: Boolean = false, overflow: Dp = 0.dp, radius: () -> Dp): Modifier =
    // 内容垫一层自己的图层：模糊程度变了要重画这一层时只录一个图层引用，不把整块内容重新录一遍
    this then CachedBlurElement(radius, continuous, overflow) then Modifier.graphicsLayer()

private class CachedBlurElement(val radius: () -> Dp, val continuous: Boolean, val overflow: Dp) : ModifierNodeElement<CachedBlurNode>() {
    override fun create(): CachedBlurNode = CachedBlurNode(radius, continuous, overflow)

    override fun update(node: CachedBlurNode) {
        node.radius = radius
        node.continuous = continuous
        node.overflow = overflow
    }

    override fun hashCode(): Int = (radius.hashCode() * 31 + continuous.hashCode()) * 31 + overflow.hashCode()

    override fun equals(other: Any?): Boolean =
        other is CachedBlurElement && other.radius === radius && other.continuous == continuous && other.overflow == overflow

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiBlur"
        properties["continuous"] = continuous
        properties["overflow"] = overflow
    }
}

/** 这一刻怎么模糊：原分辨率下的半径 [radius]（px）、降采样倍数 [k]、低分辨率上实际用的半径 [low] */
private data class BlurPlan(val radius: Float, val k: Int, val low: Float)

private class CachedBlurNode(radius: () -> Dp, var continuous: Boolean, var overflow: Dp) :
    Modifier.Node(),
    DrawModifierNode {
    // 重组时换成新的 lambda 也不重画：派生状态下次重算时读最新的那个（读的通常是同一批状态）
    var radius: () -> Dp = radius

    // 只在模糊方案变了时才变，绘制读它：同一档里半径怎么变都不重画
    private var plan: State<BlurPlan?>? = null
    private var inner: GraphicsLayer? = null
    private var outer: GraphicsLayer? = null
    private var effect: BlurEffect? = null
    private var effectPlan: BlurPlan? = null

    private fun planState(density: Density): State<BlurPlan?> = plan ?: derivedStateOf {
        val px = with(density) { this@CachedBlurNode.radius().toPx() }
        if (continuous) continuousPlan(px) else steppedPlan(px)
    }.also { plan = it }

    override fun ContentDrawScope.draw() {
        val p = planState(requireDensity()).value
        if (p == null || size.width < 1f || size.height < 1f) {
            release()
            drawContent()
            return
        }
        val ctx = requireGraphicsContext()
        val i = inner ?: ctx.createGraphicsLayer().also { inner = it }
        val e = if (p == effectPlan) {
            effect!!
        } else {
            BlurEffect(p.low, p.low, TileMode.Decal).also {
                effect = it
                effectPlan = p
            }
        }
        val k = p.k
        // 内容画出边界的那一圈（外阴影）也录进来：带模糊的图层按自己的范围离屏，范围外的会被切掉
        val over = ceil(overflow.toPx() / k).toInt().coerceAtLeast(0)
        val shift = over.toFloat() * k
        i.renderEffect = e
        i.record(IntSize(ceil(size.width / k).toInt() + over * 2, ceil(size.height / k).toInt() + over * 2)) {
            translate(over.toFloat(), over.toFloat()) {
                if (k == 1) {
                    this@draw.drawContent()
                } else {
                    scale(1f / k, pivot = Offset.Zero) { this@draw.drawContent() }
                }
            }
        }
        if (continuous) {
            // 连续：半径每帧都在变，本来就要每帧重新模糊，只用这一层离屏（和直接设 renderEffect 一样多）——
            // 再套缓存层只会每帧多一次渲染目标切换（手机 GPU 上这比省下的像素更贵）。模糊的外溢不受这一层大小限制
            releaseCache()
            translate(-shift, -shift) {
                if (k == 1) drawLayer(i) else scale(k.toFloat(), pivot = Offset.Zero) { drawLayer(i) }
            }
            return
        }
        // 分档：大多数帧档位不变、只是在移动 / 缩放 / 淡化。模糊结果画进缓存层（四周留出外溢的那圈），
        // 这些帧只合成这张图，一次离屏都不用；只有换档、内容变了的那一帧才重新模糊
        val o = outer ?: ctx.createGraphicsLayer().also {
            it.compositingStrategy = CompositingStrategy.Offscreen
            outer = it
        }
        val pad = ceil(3f * blurSigma(p.radius) / k).roundToInt() + 1
        val w = ceil(size.width / k).toInt() + over * 2
        val h = ceil(size.height / k).toInt() + over * 2
        o.record(IntSize(w + pad * 2, h + pad * 2)) {
            translate(pad.toFloat(), pad.toFloat()) { drawLayer(i) }
        }
        withTransform({
            translate(-pad.toFloat() * k - shift, -pad.toFloat() * k - shift)
            if (k != 1) scale(k.toFloat(), k.toFloat(), pivot = Offset.Zero)
        }) { drawLayer(o) }
    }

    private fun releaseCache() {
        val o = outer ?: return
        if (isAttached) requireGraphicsContext().releaseGraphicsLayer(o)
        outer = null
    }

    private fun release() {
        val ctx = if (isAttached) requireGraphicsContext() else return
        inner?.let { ctx.releaseGraphicsLayer(it) }
        outer?.let { ctx.releaseGraphicsLayer(it) }
        inner = null
        outer = null
    }

    override fun onDetach() {
        release()
        plan = null
        effect = null
        effectPlan = null
    }
}

/** 分档：半径落到 1.5 倍一档的档位上（和 [mochiCachedBlur] 同一套档位） */
private fun steppedPlan(px: Float): BlurPlan? {
    val e = mochiCachedBlur(px) ?: return null
    val r = mochiCachedBlurRadius(e)
    val k = blurDownsample(r)
    return BlurPlan(r, k, lowRadius(r, k))
}

/** 连续：半径照实算，低分辨率上的半径按 1/4 px 取整（再细肉眼看不出，只会多重画） */
private fun continuousPlan(px: Float): BlurPlan? {
    if (!MochiGraphics.blur) return null
    val r = px * MochiGraphics.blurScale
    if (r < 0.75f) return null
    val k = blurDownsample(r)
    val low = (lowRadius(r, k) * 4f).roundToInt() / 4f
    return BlurPlan(r, k, low)
}

/** 系统把模糊半径换成高斯 sigma 的方式 */
private fun blurSigma(r: Float): Float = 0.57735f * r + 0.5f

/**
 * 降采样 k 倍后的等效半径：最终的模糊 = 低分辨率上的高斯 + 放大回去时双线性插值带来的那一点（相当于 sigma ≈ k / 2.45），
 * 两者平方相加。低分辨率上的 sigma 从目标里扣掉插值那一份，放大后的整体模糊和原分辨率直接模糊一样
 */
private fun lowRadius(r: Float, k: Int): Float {
    if (k == 1) return r
    val sigma = blurSigma(r)
    val interp = k / 2.45f
    val low = sqrt((sigma * sigma - interp * interp).coerceAtLeast(0f)) / k
    return ((low - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
}

/**
 * 降采样倍数：每降一倍要求低分辨率上的 sigma 不小于 1.25（糊到这个程度，放大回去的插值误差在整体模糊里不到 5%），
 * 最多 8 倍。分档时 k 的切换落在换档的那一帧，和换档一起发生、看不出来
 */
private fun blurDownsample(r: Float): Int {
    val sigma = blurSigma(r)
    return when {
        sigma >= 10f -> 8
        sigma >= 5f -> 4
        sigma >= 2.5f -> 2
        else -> 1
    }
}

/**
 * 给 [mochiBlur] 外面那层 graphicsLayer 设透明度：模糊生效时里面只有一张模糊后的图，透明度直接乘在这张图上
 * （ModulateAlpha），不再为透明度单独开一层离屏——手机 GPU 上那一层离屏比模糊本身还贵。
 * 模糊没生效时照常（半透明的内容不会互相透出来）。[blur] 传和 [mochiBlur] 同一个半径
 */
public fun GraphicsLayerScope.mochiBlurAlpha(alpha: Float, blur: Dp, continuous: Boolean = false) {
    this.alpha = alpha
    val px = blur.toPx()
    val active = if (continuous) continuousPlan(px) != null else steppedPlan(px) != null
    compositingStrategy = if (active && alpha < 1f) AlphaStrategy.ModulateAlpha else AlphaStrategy.Auto
}
