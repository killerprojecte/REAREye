// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.mask

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import ui.feice.mochi.theme.LocalMochiMaterials

/**
 * 滚动边缘效果的渐进模糊（iOS 26 的 scroll edge effect 里顶部那份 variableBlur），加在**滚动容器**上：
 * 容器自己在顶栏下面那一段把内容按遮罩渐进模糊，顶栏只画颜色那一层（[ui.feice.mochi.navigation.mochiScrollEdgeFade]）。
 * 模糊的是内容自己，不需要背景来源，栏也不会画到自己身上。
 *
 * 遮罩、模糊量来自主题的 [ui.feice.mochi.theme.MochiMaterials.scrollEdge]；主题没给（Mochi、Nothing 外观）或 Android 12 及以下时不做任何事。
 * `MochiLargeTitleScaffold`、`MochiCompactTitleScaffold` 已经接好。
 *
 * @param top 顶栏下沿离容器顶边多远（状态栏 + 顶栏高度，容器从屏幕顶开始时）
 * @param alpha 和顶栏的 backgroundProgress 用同一个：内容停在顶部时是 0，栏下面没有内容，不模糊第一行
 */
public fun Modifier.mochiScrollEdgeBlur(top: Dp, alpha: () -> Float = { 1f }): Modifier = this then ScrollEdgeBlurElement(top, alpha)

private data class ScrollEdgeBlurElement(val top: Dp, val alpha: () -> Float) : ModifierNodeElement<ScrollEdgeBlurNode>() {
    override fun create(): ScrollEdgeBlurNode = ScrollEdgeBlurNode(top, alpha)

    override fun update(node: ScrollEdgeBlurNode) {
        node.top = top
        node.alpha = alpha
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiScrollEdgeBlur"
    }
}

private class ScrollEdgeBlurNode(var top: Dp, var alpha: () -> Float) :
    BandBlurNode(),
    CompositionLocalConsumerModifierNode {
    override fun ContentDrawScope.draw() {
        val edge = currentValueOf(LocalMochiMaterials).scrollEdge
        val a = alpha().coerceIn(0f, 1f)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || edge == null || a <= 0f) return plain()
        val side = edge.top
        val sigma = side.blur.toPx() * a
        if (sigma < MinSigma || size.width < 1f || size.height < 1f) return plain()
        val line = top.toPx() - side.inset.toPx()
        val soft = side.softness.toPx().coerceAtLeast(0.5f)
        // 遮罩 1.25/(1 + e^(−1.702z)) − 0.25 在 z < −0.8145 时是 0：模糊只到线下面 0.82 个柔和度为止，再往下画原样
        bands.clear()
        bands.add(0, 0, ceil(size.width).toInt(), min(ceil(size.height).toInt(), ceil(line + soft * 0.82f).toInt() + 1))
        drawBands(sigma) { ox, oy, w, h ->
            setFloatUniform("uMode", 0f, sigma, line, soft)
            setFloatUniform("uFrame", ox, oy, w, h)
        }
    }
}

/**
 * 渐变遮罩的边上也渐进模糊（[mochiFadeMask] 在主题有 scrollEdge 时自动加）：越靠边越糊，和透明度渐隐是同一条边、同一个深浅，
 * 模糊的范围是渐隐长度的两倍——渐隐快看不见的地方模糊已经到头，内容还清楚的地方也先开始轻微发糊。
 * 最大 sigma 借用主题顶部滚动边缘的实测值（iOS 没有单独的「容器内渐隐」可以对照）。
 */
internal data class FadeBlurElement(val edges: MochiFadeEdges, val amount: MochiFadeAmount) : ModifierNodeElement<FadeBlurNode>() {
    override fun create(): FadeBlurNode = FadeBlurNode(edges, amount)

    override fun update(node: FadeBlurNode) {
        node.edges = edges
        node.amount = amount
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiFadeBlur"
    }
}

internal class FadeBlurNode(var edges: MochiFadeEdges, var amount: MochiFadeAmount) :
    BandBlurNode(),
    CompositionLocalConsumerModifierNode {
    override fun ContentDrawScope.draw() {
        val edge = currentValueOf(LocalMochiMaterials).scrollEdge
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || edge == null || size.width < 1f || size.height < 1f) return plain()
        val sigma = edge.top.blur.toPx()
        // amount 读的是滚动状态之类，在这里读：变了只重画这一层
        (amount as? ScrollFadeAmount)?.density = density
        val rtl = currentValueOf(LocalLayoutDirection) == LayoutDirection.Rtl
        val start = if (rtl) MochiFadeEdge.End else MochiFadeEdge.Start
        val end = if (rtl) MochiFadeEdge.Start else MochiFadeEdge.End
        val sx = if (rtl) edges.end else edges.start
        val ex = if (rtl) edges.start else edges.end
        val v = floatArrayOf(
            edges.top.toPx() * BlurReach,
            amount.amount(MochiFadeEdge.Top),
            edges.bottom.toPx() * BlurReach,
            amount.amount(MochiFadeEdge.Bottom),
            sx.toPx() * BlurReach,
            amount.amount(start),
            ex.toPx() * BlurReach,
            amount.amount(end),
        )
        fun len(i: Int) = if (v[i * 2] > 0f && v[i * 2 + 1] > 0.001f) ceil(v[i * 2]).toInt() else 0
        if (sigma < MinSigma || (0 until 4).all { len(it) == 0 }) return plain()
        // 四条边各一块、互不重叠：上下两块占满宽，左右两块夹在中间
        val w = ceil(size.width).toInt()
        val h = ceil(size.height).toInt()
        val t = min(len(0), h)
        val b = min(len(1), h - t)
        val l = min(len(2), w)
        val r = min(len(3), w - l)
        bands.clear()
        bands.add(0, 0, w, t)
        bands.add(0, h - b, w, h)
        bands.add(0, t, l, h - b)
        bands.add(w - r, t, w, h - b)
        drawBands(sigma) { ox, oy, bw, bh ->
            setFloatUniform("uMode", 1f, sigma, 0f, 0f)
            setFloatUniform("uLen", v[0], v[2], v[4], v[6])
            setFloatUniform("uAmt", v[1], v[3], v[5], v[7])
            setFloatUniform("uFrame", ox, oy, bw, bh)
        }
    }
}

/**
 * 渐进模糊的近似：不逐像素变半径，而是「普通高斯模糊 + 渐变遮罩」。内容先录进一层（只是显示列表，不离屏），带子外面原样画；
 * 要发糊的那条带子另开一层（再往里多录一圈模糊取样够得着的内容），在这一层里把「系统自带的高斯模糊（按最大 sigma，一次到位）× 遮罩」
 * 和「清晰的内容 ×（1 − 遮罩）」加起来。遮罩和原来的半径曲线是同一条：半径到头处全糊、半径为 0 处全清晰。
 * 两份按遮罩交叉淡化，不是把模糊盖在清晰上面：透明的地方（卡片之间、文字笔画之间）不会从下面透出清晰的那份。
 *
 * 系统模糊会先缩小再模糊，比 13 个取样、横竖两遍的变半径着色器便宜得多；过渡段看起来是清晰和全糊叠在一起，
 * 而不是「半糊」，在 2dp 这种小 sigma 下肉眼差不多。
 */
internal abstract class BandBlurNode :
    Modifier.Node(),
    DrawModifierNode {
    protected val bands = Bands()
    private var content: GraphicsLayer? = null
    private val layers = arrayOfNulls<GraphicsLayer>(4)
    private val caches = Array(4) { EffectCache() }

    protected fun ContentDrawScope.plain() {
        release()
        drawContent()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    protected fun ContentDrawScope.drawBands(sigma: Float, setup: RuntimeShader.(Float, Float, Float, Float) -> Unit) {
        val ctx = requireGraphicsContext()
        val c = content ?: ctx.createGraphicsLayer().also { content = it }
        val w = ceil(size.width).toInt()
        val h = ceil(size.height).toInt()
        c.record(IntSize(w, h)) { this@drawBands.drawContent() }
        // 一个容器只开一层（几条带子取外接矩形）：每多一层就多两次渲染目标切换，小容器上这比省下的像素还贵
        bands.merge()
        // 带子外面：原样（带子占满整个容器时没有外面）
        val whole = bands.count == 1 && bands.left[0] == 0 && bands.top[0] == 0 && bands.right[0] >= w && bands.bottom[0] >= h
        if (!whole) clipOutBands(0) { drawLayer(c) }
        // 带子里面：往里多录一圈（三倍 sigma，模糊取样都在里面），外圈就是容器边
        val reach = ceil(sigma * 3f).toInt() + 1
        for (i in 0 until bands.count) {
            val bl = bands.left[i]
            val bt = bands.top[i]
            val br = bands.right[i]
            val bb = bands.bottom[i]
            val el = max(0, bl - reach)
            val et = max(0, bt - reach)
            val er = min(w, br + reach)
            val eb = min(h, bb + reach)
            val layer = layers[i] ?: ctx.createGraphicsLayer().also { layers[i] = it }
            val ew = (er - el).toFloat()
            val eh = (eb - et).toFloat()
            layer.renderEffect = caches[i].get(
                floatArrayOf(sigma, el.toFloat(), et.toFloat(), ew, eh, w.toFloat(), h.toFloat(), *bands.key(i)),
            ) {
                maskedBlur(sigma, ew, eh, w.toFloat(), h.toFloat()) { setup(el.toFloat(), et.toFloat(), ew, eh) }
            }
            layer.record(IntSize(er - el, eb - et)) {
                translate(-el.toFloat(), -et.toFloat()) { drawLayer(c) }
            }
            clipRect(bl.toFloat(), bt.toFloat(), br.toFloat(), bb.toFloat()) {
                translate(el.toFloat(), et.toFloat()) { drawLayer(layer) }
            }
        }
        // 带子变少时把多出来的层放掉
        for (i in bands.count until layers.size) releaseLayer(i)
    }

    /** 依次挖掉每条带子再画 [block]：带子都是整像素的矩形，和后面按带子裁切的那几层正好拼上，没有缝 */
    private fun DrawScope.clipOutBands(i: Int, block: DrawScope.() -> Unit) {
        if (i >= bands.count) return block()
        clipRect(
            bands.left[i].toFloat(),
            bands.top[i].toFloat(),
            bands.right[i].toFloat(),
            bands.bottom[i].toFloat(),
            ClipOp.Difference,
        ) { clipOutBands(i + 1, block) }
    }

    private fun releaseLayer(i: Int) {
        val l = layers[i] ?: return
        if (isAttached) requireGraphicsContext().releaseGraphicsLayer(l)
        layers[i] = null
        caches[i].clear()
    }

    private fun release() {
        for (i in layers.indices) releaseLayer(i)
        val c = content ?: return
        if (isAttached) requireGraphicsContext().releaseGraphicsLayer(c)
        content = null
    }

    override fun onDetach() {
        release()
    }
}

/** 要模糊的带子（整像素矩形），空的不记 */
internal class Bands {
    val left = IntArray(4)
    val top = IntArray(4)
    val right = IntArray(4)
    val bottom = IntArray(4)
    var count = 0
        private set

    fun clear() {
        count = 0
    }

    fun add(l: Int, t: Int, r: Int, b: Int) {
        if (r <= l || b <= t || count == 4) return
        left[count] = l
        top[count] = t
        right[count] = r
        bottom[count] = b
        count++
    }

    /** 合成一块：所有带子的外接矩形 */
    fun merge() {
        if (count <= 1) return
        for (i in 1 until count) {
            left[0] = min(left[0], left[i])
            top[0] = min(top[0], top[i])
            right[0] = max(right[0], right[i])
            bottom[0] = max(bottom[0], bottom[i])
        }
        count = 1
    }

    fun key(i: Int): FloatArray = floatArrayOf(left[i].toFloat(), top[i].toFloat(), right[i].toFloat(), bottom[i].toFloat())
}

/** 参数没变就复用上一次的效果对象：滚动时每帧重建 RenderEffect 会让 HWUI 的模糊缓存作废 */
private class EffectCache {
    private var key: FloatArray? = null
    private var value: androidx.compose.ui.graphics.RenderEffect? = null

    fun get(k: FloatArray, make: () -> RenderEffect): androidx.compose.ui.graphics.RenderEffect {
        val v = value
        if (v != null && key?.contentEquals(k) == true) return v
        return make().asComposeRenderEffect().also {
            key = k
            value = it
        }
    }

    fun clear() {
        key = null
        value = null
    }
}

/** 普通高斯模糊（最大 [sigma]）× 遮罩 + 清晰内容 ×（1 − 遮罩）；[setup] 给遮罩着色器设遮罩规则和这条带子在容器里的位置 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun maskedBlur(sigma: Float, w: Float, h: Float, fullW: Float, fullH: Float, setup: RuntimeShader.() -> Unit): RenderEffect {
    fun mask(invert: Boolean, input: RenderEffect?): RenderEffect {
        val shader = RuntimeShader(MASK_AGSL).apply {
            setFloatUniform("uSize", fullW, fullH)
            setFloatUniform("uFrame", 0f, 0f, w, h)
            setFloatUniform("uLen", 0f, 0f, 0f, 0f)
            setFloatUniform("uAmt", 0f, 0f, 0f, 0f)
            setFloatUniform("uInvert", if (invert) 1f else 0f)
            setup()
        }
        val e = RenderEffect.createRuntimeShaderEffect(shader, "content")
        return if (input == null) e else RenderEffect.createChainEffect(e, input)
    }
    // 只有一份「全糊」去和清晰的交叉淡化，过渡段整体偏清楚：全糊那份比原来的最大 sigma 大一些（BlurBoost），和逐像素变半径的观感对上。
    // createBlurEffect 收的是「半径」，HWUI 按 sigma = 0.57735·r + 0.5 换算
    val radius = ((sigma * BlurBoost - 0.5f) / 0.57735f).coerceAtLeast(0.01f)
    val blurred = mask(false, RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
    // 预乘颜色直接相加 = 按遮罩交叉淡化
    return RenderEffect.createBlendModeEffect(mask(true, null), blurred, android.graphics.BlendMode.PLUS)
}

/** 全糊那份的 sigma 比原来的最大 sigma 大多少（对着旧的逐像素变半径截图调的） */
private const val BlurBoost = 1.4f

/** 小于这个 sigma 就当作不模糊（和着色器里的判断一致） */
private const val MinSigma = 0.3f

/** 模糊范围是渐隐长度的几倍 */
private const val BlurReach = 2f

// 模糊过的内容乘遮罩、清晰的内容乘（1 − 遮罩）（uInvert = 1）：遮罩就是原来逐像素 sigma 曲线除以最大 sigma（0..1）。
// 这一层只是容器里的一条带子：uFrame = 带子在容器里的位置和大小，uSize = 容器大小。
// uMode.x = 0：滚动边缘（阴影边遮罩：线 uMode.z、柔和度 uMode.w，再按 1.25·a − 0.25 收紧，和 iOS 一样）
// uMode.x = 1：渐变遮罩的四条边（长度 uLen、深浅 uAmt，越靠边越糊）
private const val MASK_AGSL = """
uniform shader content;
uniform float2 uSize;
uniform float4 uFrame;
uniform float4 uMode;
uniform float4 uLen;
uniform float4 uAmt;
uniform float uInvert;

float edgeFade(float d, float len, float amt) {
    if (len <= 0.0 || amt <= 0.0) return 0.0;
    float t = clamp(d / len, 0.0, 1.0);
    return amt * (1.0 - t * t * (3.0 - 2.0 * t));
}

float maskAt(float2 p) {
    if (uMode.x < 0.5) {
        float z = (uMode.z - p.y) / uMode.w;
        return clamp(1.25 / (1.0 + exp(-1.702 * z)) - 0.25, 0.0, 1.0);
    }
    return max(max(edgeFade(p.y, uLen.x, uAmt.x), edgeFade(uSize.y - p.y, uLen.y, uAmt.y)),
               max(edgeFade(p.x, uLen.z, uAmt.z), edgeFade(uSize.x - p.x, uLen.w, uAmt.w)));
}

half4 main(float2 p) {
    // 这一层外面没有内容
    if (p.x < 0.0 || p.y < 0.0 || p.x > uFrame.z || p.y > uFrame.w) return half4(0.0);
    // 交叉淡化在遮罩一半处只是「一半清晰叠一半全糊」，看着比半径减半的模糊清楚得多；开方把权重往全糊那边提，过渡段的糊度才接近
    float m = sqrt(maskAt(p + uFrame.xy));
    if (uInvert > 0.5) m = 1.0 - m;
    if (m <= 0.002) return half4(0.0);
    return content.eval(p) * half(m);
}
"""
