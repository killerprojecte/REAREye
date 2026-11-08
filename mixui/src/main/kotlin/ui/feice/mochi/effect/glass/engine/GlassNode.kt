// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass.engine

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.Backdrop
import kotlin.math.ceil
import ui.feice.mochi.theme.MochiGlassRecipe

/**
 * 玻璃本体降采样的倍数：贴边以外的部分（折射、几路模糊、环境色、面色）在缩小这么多倍的小图层里算，
 * 放大回来后再用全分辨率跑一遍贴边的细节（黑环、定向高光、边光，连同外阴影）。
 * 面色是低频的（背后先模糊过），缩 3 倍和全分辨率逐像素平均只差 0.36/255，差超过 8/255 的像素不到玻璃面积的 1%，
 * 而且都在最外一圈抗锯齿上；算这部分的像素数降到 1/9。固定不变：以前按帧耗时在 2～4 之间自动调，
 * 卡的时候调粗、所有玻璃跟着重建，反而更卡，玻璃的样子也在用的过程中悄悄变
 */
internal const val GlassDownsample = 3

/**
 * 一块玻璃（Android 13+）。整块只有四个渲染节点：
 * 1. 取样：把背后 [page]（面色读 [face]）按降采样的比例录进小图层（纯色画布这类不跟位置走的来源先按玻璃原尺寸录一份再缩小）；
 * 2. 模糊：取样横着排几路，挂两遍模糊（[glassFacePlan]）；
 * 3. 面色：读模糊那层，挂面色着色器；
 * 4. 收尾：比玻璃大一圈（外阴影、黑环），录面色放大回来的图（定向高光要读原样背景时左边再录一份背景），过收尾着色器（[glassFinishEffect]）。
 *
 * 以前同样的事拆在十几个图层里（每路模糊一个、面色录一遍、透明度一层、外轮廓一层……），系统每帧准备绘制时要逐个遍历，
 * 节点越多越慢，转场时主线程要等它。参数（配方、尺寸、圆角、融合）没变时效果对象原样复用，系统直接用上次算好的结果。
 *
 * [params] 每次绘制时读：配方、圆角（px）、融合；[scaleX] / [scaleY] 是绕中心的按压形变（背景不跟着放大）；
 * [alpha] 整块的透明度（挂在收尾层上，范围包住外阴影）；[surface] 画在面色上面（没有背景时的纯色底等），跟着面色一起过收尾
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun Modifier.glassNode(
    page: Backdrop,
    face: Backdrop,
    params: (Size) -> GlassParams,
    scaleX: () -> Float,
    scaleY: () -> Float,
    alpha: () -> Float,
    surface: (DrawScope.() -> Unit)?,
): Modifier = this then GlassElement(page, face, params, scaleX, scaleY, alpha, surface)

/** 一块玻璃这一帧的参数：配方、圆角（px，全分辨率）、融合（见 [GlassMerge]） */
internal class GlassParams(val recipe: MochiGlassRecipe, val cornerRadius: Float, val merge: GlassMerge?)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private data class GlassElement(
    val page: Backdrop,
    val face: Backdrop,
    val params: (Size) -> GlassParams,
    val scaleX: () -> Float,
    val scaleY: () -> Float,
    val alpha: () -> Float,
    val surface: (DrawScope.() -> Unit)?,
) : ModifierNodeElement<GlassNode>() {
    override fun create(): GlassNode = GlassNode(page, face, params, scaleX, scaleY, alpha, surface)

    override fun update(node: GlassNode) {
        node.page = page
        node.face = face
        node.params = params
        node.scaleX = scaleX
        node.scaleY = scaleY
        node.alpha = alpha
        node.surface = surface
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassNode"
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class GlassNode(
    var page: Backdrop,
    var face: Backdrop,
    var params: (Size) -> GlassParams,
    var scaleX: () -> Float,
    var scaleY: () -> Float,
    var alpha: () -> Float,
    var surface: (DrawScope.() -> Unit)?,
) : Modifier.Node(),
    DrawModifierNode,
    GlobalPositionAwareModifierNode {
    private var canvas: GraphicsLayer? = null
    private var source: GraphicsLayer? = null
    private var lanes: GraphicsLayer? = null
    private var atlas: GraphicsLayer? = null
    private var finish: GraphicsLayer? = null
    private var shaders: GlassFaceShaders? = null
    private var finishShader: RuntimeShader? = null

    private var faceKey: Any? = null
    private var plan: GlassFacePlan? = null
    private var atlasSize: IntSize? = null
    private var finishKey: Any? = null
    private var finishEffect: RenderEffect? = null
    private var position: Offset? = null

    // 挂在图层上的效果：同一个对象就不再赋值。重新赋值（哪怕参数一样）系统会当成效果变了，上次算好的结果作废
    private var appliedPlan: GlassFacePlan? = null
    private var appliedFinish: RenderEffect? = null

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        // 来源跟着位置走（页面、浮层底下的内容）时，挪动后要按新位置重录取样；纯色画布这类和位置无关，挪动不用管
        if (!page.isCoordinatesDependent && !face.isCoordinatesDependent) return
        val next = coordinates.positionInWindow()
        if (next != position) {
            position = next
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (size.width < 1f || size.height < 1f) return
        val p = params(size)
        val r = p.recipe
        val k = GlassDownsample
        val w = ceil(size.width).toInt()
        val h = ceil(size.height).toInt()
        val sx = scaleX()
        val sy = scaleY()
        val context = requireGraphicsContext()
        val coords = requireLayoutCoordinates()
        // 嵌套录制不能把外面这个会变的 DrawScope 当 Density 传进去：回调里用 dp 时会绕回它自己
        val d = Density(density, fontScale)

        // 1. 取样：小图层的像素 u 对应玻璃里（按压形变前）的 k·u，按压时玻璃绕中心放大 s 倍，屏幕上在 c + s(k·u − c)，
        // 背景就从那里取：先按 1/(s·k) 缩小，再挪 c(1/k − 1/(s·k))
        val bw = bucket(w, k)
        val bh = bucket(h, k)
        val nextFaceKey = listOf(r, bw, bh, w, h, p.cornerRadius, p.merge ?: Unit, density)
        val shaders = shaders ?: GlassFaceShaders().also { shaders = it }
        val plan = if (nextFaceKey == faceKey) {
            checkNotNull(plan)
        } else {
            glassFacePlan(
                Density(density / k),
                r,
                Size(size.width / k, size.height / k),
                bw,
                bh,
                shaders,
                p.cornerRadius / k,
                p.merge?.scaled(1f / k),
            ).also {
                plan = it
                faceKey = nextFaceKey
            }
        }
        val pad = plan.padding
        val lane = IntSize(bw + pad * 2, bh + pad * 2)
        val c = Offset(size.width / 2f, size.height / 2f)
        val drawFace = sourceDrawer(face, d, coords, w, h, context)
        val src = source ?: context.createGraphicsLayer().also {
            // 录制的尺寸不裁内容：不裁的话后面每路模糊都会读整页
            it.clip = true
            source = it
        }
        src.record(d, layoutDirection, lane) {
            translate(pad.toFloat(), pad.toFloat()) {
                withTransform({
                    translate(c.x * (1f / k - 1f / (sx * k)), c.y * (1f / k - 1f / (sy * k)))
                    scale(1f / (sx * k), 1f / (sy * k), Offset.Zero)
                }) { drawFace() }
            }
        }

        // 2. 模糊：取样横着排几路，挂两遍模糊；3. 面色：整块读模糊那层，挂面色着色器。
        // 这两层只引用下一层，取样里的内容变了系统自己会更新，不用重录
        val blurLayer = lanes ?: context.createGraphicsLayer().also { lanes = it }
        val faceLayer = atlas ?: context.createGraphicsLayer().also { atlas = it }
        val nextAtlas = IntSize(lane.width * plan.lanes, lane.height)
        if (atlasSize != nextAtlas) {
            blurLayer.record(d, layoutDirection, nextAtlas) {
                for (i in 0 until plan.lanes) {
                    translate(i * lane.width.toFloat(), 0f) {
                        clipRect(0f, 0f, lane.width.toFloat(), lane.height.toFloat()) { drawLayer(src) }
                    }
                }
            }
            faceLayer.record(d, layoutDirection, nextAtlas) { drawLayer(blurLayer) }
            atlasSize = nextAtlas
        }
        if (appliedPlan !== plan) {
            blurLayer.renderEffect = plan.blur?.asComposeRenderEffect()
            faceLayer.renderEffect = plan.face.asComposeRenderEffect()
            appliedPlan = plan
        }

        // 3. 收尾：比玻璃大一圈（外阴影、黑环都在玻璃外面）。整层最后绕中心放大 (sx, sy)，里面的背景先按反方向缩回去，
        // 读到的还是原位的内容；面色在取样时已经算进了放大，直接按 k 倍铺回
        val reach = r.outlineReach().roundToPx()
        val fw = w + reach * 2
        val fh = h + reach * 2
        // 只有定向高光（细描边）要读轮廓外的原样背景；没有它（iOS 26 等）左边那份不录
        val pageLane = if (r.highlightHeight.value > 0f) fw else 0
        val glassSize = Size(size.width, size.height)
        val nextFinishKey = listOf(r, glassSize, reach, p.cornerRadius, p.merge ?: Unit, density, pageLane)
        val effect = if (nextFinishKey == finishKey) {
            finishEffect
        } else {
            val shader = finishShader ?: glassFinishShader().also { finishShader = it }
            glassFinishEffect(
                d,
                shader,
                r,
                glassSize,
                Offset(reach.toFloat(), reach.toFloat()),
                p.cornerRadius,
                pageLane.toFloat(),
                p.merge,
            ).also {
                finishEffect = it
                finishKey = nextFinishKey
            }
        } ?: return
        val out = finish ?: context.createGraphicsLayer().also {
            it.clip = true
            finish = it
        }
        if (appliedFinish !== effect) {
            out.renderEffect = effect.asComposeRenderEffect()
            appliedFinish = effect
        }
        out.alpha = alpha()
        val fc = Offset(fw / 2f, fh / 2f)
        val drawPage = if (pageLane > 0) sourceDrawer(page, d, coords, w, h, context, own = false) else null
        out.record(d, layoutDirection, IntSize(fw + pageLane, fh)) {
            if (drawPage != null) {
                clipRect(0f, 0f, fw.toFloat(), fh.toFloat()) {
                    scale(1f / sx, 1f / sy, pivot = fc) { translate(reach.toFloat(), reach.toFloat()) { drawPage() } }
                }
            }
            translate(pageLane.toFloat(), 0f) {
                clipRect(0f, 0f, fw.toFloat(), fh.toFloat()) {
                    translate(reach.toFloat(), reach.toFloat()) {
                        scale(k.toFloat(), k.toFloat(), Offset.Zero) {
                            clipRect(0f, 0f, bw.toFloat(), bh.toFloat()) {
                                translate(-pad.toFloat(), -pad.toFloat()) { drawLayer(faceLayer) }
                            }
                        }
                        surface?.invoke(this)
                    }
                }
            }
        }
        // 只画左边那份（右边的背景是给着色器读的）。先裁到放大后的范围：效果的输出没有边界，
        // 不裁的话系统按当前裁剪区（往往是整个窗口）分配中间纹理、在整屏像素上跑着色器
        val hw = fw * sx / 2f
        val hh = fh * sy / 2f
        translate(-reach.toFloat(), -reach.toFloat()) {
            clipRect(fc.x - hw, fc.y - hh, fc.x + hw, fc.y + hh) {
                scale(sx, sy, pivot = fc) { drawLayer(out) }
            }
        }
    }

    /**
     * 画来源的函数：位置相关的来源（页面）按玻璃左上角对齐直接画；纯色画布这类不跟位置走的，画法按当前画布尺寸铺，
     * 先按玻璃原尺寸 [w] × [h] 录进一层再整层画（不然缩小取样时只铺到一角）。[own] 为 false 时不占用那一层（收尾读的原样背景）
     */
    private fun DrawScope.sourceDrawer(
        backdrop: Backdrop,
        d: Density,
        coords: LayoutCoordinates,
        w: Int,
        h: Int,
        context: androidx.compose.ui.graphics.GraphicsContext,
        own: Boolean = true,
    ): DrawScope.() -> Unit {
        if (backdrop.isCoordinatesDependent || !own) return { with(backdrop) { drawBackdrop(d, coords, null) } }
        val layer = canvas ?: context.createGraphicsLayer().also { canvas = it }
        layer.record(d, layoutDirection, IntSize(w, h)) { with(backdrop) { drawBackdrop(d, coords, null) } }
        return { drawLayer(layer) }
    }

    override fun onDetach() {
        val context = requireGraphicsContext()
        listOfNotNull(canvas, source, lanes, atlas, finish).forEach { context.releaseGraphicsLayer(it) }
        canvas = null
        source = null
        lanes = null
        atlas = null
        finish = null
        faceKey = null
        plan = null
        atlasSize = null
        finishKey = null
        finishEffect = null
        position = null
        appliedPlan = null
        appliedFinish = null
    }
}

/** 小图层每边按这么多像素分档：玻璃宽高动画时大多落在同一档，图层不用每帧重新分配 */
private const val BucketStep = 8

/** 全分辨率 [px] 缩小 [k] 倍后向上取到 [BucketStep] 的倍数 */
private fun bucket(px: Int, k: Int): Int =
    ((ceil(px / k.toFloat()).toInt() + BucketStep - 1) / BucketStep * BucketStep).coerceAtLeast(BucketStep)
