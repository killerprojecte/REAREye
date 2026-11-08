// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.activity.BackEventCompat
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.dp
import ui.feice.mochi.effect.glass.source
import ui.feice.mochi.utils.lerp
import ui.feice.mochi.utils.smoothstep

/** 一帧里栈顶页的画面 */
internal class TopFrame(
    val rect: Rect,
    val tl: Float,
    val tr: Float,
    val br: Float,
    val bl: Float,
    val alpha: Float,
    val cover: Float,
    val coverColor: Color,
    val mix: Float,
    val underScale: Float,
    val source: SourceInfo?,
)

/** 来源元素此刻在屏幕上的样子（已经按下层页面的缩放换算） */
internal class SourceInfo(val rect: Rect, val radius: Float, val color: Color)

@Stable
internal class NavGeometry(val nav: MochiNavigator) {
    var size by mutableStateOf(Size.Zero)
    lateinit var spec: MochiNavSpec
    var defaultCorners: MochiScreenCorners = MochiScreenCorners.None
    var enclosingCorners: (() -> MochiScreenCorners)? = null

    /** 在玻璃面板里：面板此刻的不透明度（0 = 玻璃，1 = 不透明），见 [MochiNavSurface.glass] */
    var glassOpaque: (() -> Float)? = null
    private val corners: MochiScreenCorners get() = enclosingCorners?.invoke() ?: defaultCorners

    /** 侧推时屏幕没有物理圆角（模拟器、直角屏）用的圆角（px），保证转场中始终是圆角 */
    var fallbackCorner: Float = 0f

    private fun slideCorner(c: Float): Float = if (c > 0.5f) c else fallbackCorner

    private val full get() = Rect(Offset.Zero, size)

    private fun top(): MochiNavEntry? = nav.entries.lastOrNull()

    fun applyLayer(e: MochiNavEntry, layer: androidx.compose.ui.graphics.GraphicsLayerScope) {
        layer.scaleX = 1f
        layer.scaleY = 1f
        layer.translationX = 0f
        layer.translationY = 0f
        layer.alpha = 1f
        layer.clip = false
        val top = top() ?: return
        if (size.width <= 0f) return
        if (top.source == null && nav.under(top) != null) {
            applySlide(e, layer)
            return
        }
        if (e === top) {
            if (nav.under(e) == null) return // 根页，没有转场
            val f = frame(e)
            val s = (f.rect.width / size.width).coerceAtLeast(1e-3f)
            layer.transformOrigin = TransformOrigin(0f, 0f)
            layer.scaleX = s
            layer.scaleY = s
            layer.translationX = f.rect.left
            layer.translationY = f.rect.top
            layer.alpha = f.alpha
        } else if (nav.under(top) === e) {
            // 下层页：默认保持原位，显式 underScale 才缩小；栈顶完全打开静止时不画（见 isHidden）
            val u = underScale(top)
            layer.scaleX = u
            layer.scaleY = u
            layer.transformOrigin = TransformOrigin.Center
        } else {
            layer.alpha = 0f
        }
    }

    /**
     * 有来源的栈顶页缩回去时，页面坐标里看得见的高度（从顶上算）：页面按宽度缩放，排版高度比卡片高出一截，
     * 接触摸的范围只留这一块，下面多出来的部分不挡下层页的卡片
     */
    fun visibleHeight(e: MochiNavEntry): Float {
        if (e !== top() || e.source == null || size.width <= 0f) return size.height
        val r = frame(e).rect
        return r.height * size.width / r.width.coerceAtLeast(1e-3f)
    }

    /**
     * 侧推：栈顶页 x = W·(1 − p)，下层页 x = −0.3·W·p。转场中两页共用 G2 透明度轮廓，
     * 静止时撤掉轮廓处理（零开销）
     */
    private fun applySlide(e: MochiNavEntry, layer: androidx.compose.ui.graphics.GraphicsLayerScope) {
        layer.translationX = slideX(e)
        // 玻璃面板里页面是透明的：下层页随上面那页盖上来淡出，两页的内容不会叠在一起（近似，原生的做法没逆向）。
        // 面板越不透明淡得越少，全屏时和平常一样只压暗
        val opaque = glassOpaque ?: return
        val above = nav.above(e) ?: return
        if (above.source == null) layer.alpha = 1f - slideProgress(above) * (1f - opaque())
    }

    /** The same G2 geometry is used by the page alpha mask and occluder overlays. */
    fun outline(e: MochiNavEntry): NavPageOutline? {
        val top = top() ?: return null
        if (size.width <= 0f) return null
        if (top.source == null && nav.under(top) != null) {
            if (!e.inTransition && nav.above(e)?.inTransition != true) return null
            return NavPageOutline(
                full.translate(slideX(e), 0f),
                slideCorner(corners.topLeft),
                slideCorner(corners.topRight),
                slideCorner(corners.bottomRight),
                slideCorner(corners.bottomLeft),
            )
        }
        if (e === top && nav.under(e) != null && e.inTransition) {
            val f = frame(e)
            return NavPageOutline(f.rect, f.tl, f.tr, f.br, f.bl)
        }
        if (nav.under(top) === e) {
            val u = underScale(top)
            if (u < 1f) return NavPageOutline(mapUnder(full, u), corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft)
        }
        return null
    }

    /** 上面那页完全打开、静止时，这一页完全被盖住，不用画 */
    fun isHidden(e: MochiNavEntry): Boolean {
        val above = nav.above(e) ?: return false
        return !above.inTransition
    }

    /**
     * 侧推页左边缘外侧的投影（屏幕坐标，画在这一页下面的独立一层）。形状是页面圆角轮廓往外扩一圈阴影宽度
     * （圆角也跟着加大，和页面圆角同心），所以两端顺着页面圆角弯过去、不会被截断；
     * 浓淡跟着页面占屏幕的宽度走，完全滑出屏幕时正好消失
     */
    fun drawSlideShadow(e: MochiNavEntry, scope: DrawScope) {
        if (e.source != null || nav.under(e) == null) return
        if (!e.inTransition && nav.above(e)?.inTransition != true) return
        val p = slideProgress(e)
        if (p <= 0.001f) return
        val edge = slideX(e)
        if (edge >= size.width) return
        // 投影两端的圆角段填在页面角里，页面透明（玻璃面板半屏）时会露出来；这时不画
        if ((glassOpaque?.invoke() ?: 1f) < 1f) return
        scope.drawSlideEdgeShadow(edge, slideCorner(corners.topLeft), slideCorner(corners.bottomLeft), SlideShadowAlpha * p)
    }

    /** 侧推里这一页此刻的横向位置（屏幕坐标） */
    fun slideX(e: MochiNavEntry): Float {
        var x = if (nav.under(e) != null) size.width * (1f - slideProgress(e)) else 0f
        val above = nav.above(e)
        if (above != null && above.source == null) x -= SlideUnderShift * size.width * slideProgress(above)
        return x
    }

    /** 栈顶页上盖来源底色；下层页上盖暗色 */
    fun drawOver(e: MochiNavEntry, scope: DrawScope) {
        val top = top() ?: return
        if (top.source == null) {
            // 侧推：这一页被上面那页盖着时压暗，并在上面那页左边缘外侧画一道投影
            // （画在这一页上：上面那页自己被裁成圆角，画不到边外）。连续返回时每一对相邻页各画各的
            val above = nav.above(e) ?: return
            if (above.source != null || !above.inTransition) return
            val p = slideProgress(above)
            if (p > 0.001f) scope.drawRect(spec.scrim.copy(alpha = spec.scrim.alpha * p))
            return
        }
        if (e === top) {
            if (nav.under(e) == null || !e.inTransition) return
            val f = frame(e)
            // 页面还没显出来的部分：先铺卡片的底色，再把卡片录下来的画面按容器宽度缩放着画上去，
            // 它和容器一起长大、渐渐淡出（页面坐标系里容器宽度就是整屏宽）
            if (f.cover > 0.001f) scope.drawRect(f.coverColor.copy(alpha = f.coverColor.alpha * f.cover))
            val h = top.source?.let { nav.source(it) }
            val layer = h?.layer
            val card = 1f - smoothstep(CardFadeFrom, CardFadeTo, e.progress.value.coerceIn(0f, 1f))
            if (f.source != null && layer != null && h.size.width > 0f && card > 0.001f) {
                val k = size.width / h.size.width
                // 离屏层只开卡片画面那么大（不开整屏）：每帧少分配、少合成一整屏
                val bounds = Rect(0f, 0f, scope.size.width, (h.size.height * k).coerceAtMost(scope.size.height))
                // 不能改 layer.alpha 再改回来：图层的透明度是渲染线程上才读的属性，改回 1 之后实际画出来一直是不透明的，
                // 卡片就会在淡出阈值处一下消失（打开）/ 一下冒出来（关闭）。用一层带透明度的离屏层把它整体淡掉
                scope.drawIntoCanvas { canvas ->
                    canvas.saveLayer(bounds, Paint().apply { alpha = card })
                    scope.scale(k, pivot = Offset.Zero) { drawLayer(layer) }
                    canvas.restore()
                }
            }
        } else if (nav.under(top) === e && top.inTransition) {
            val k = navUnderDim(top.progress.value, top.back.value)
            if (k > 0.001f) scope.drawRect(spec.scrim.copy(alpha = spec.scrim.alpha * k))
        }
    }

    fun underScale(top: MochiNavEntry): Float = openUnderScale(top)

    /** 被「从来源打开」的页盖着时，代画这一页里收起的栏（[mochiHideWhenCovered]），按页面坐标、带下滑位移 */
    fun drawCarriedBars(e: MochiNavEntry, scope: DrawScope) {
        if (!nav.isCarriedUnderSource(e)) return
        val root = e.contentCoords?.takeIf { it.isAttached } ?: return
        for (h in nav.hiddenOf(e)) {
            val layer = h.layer ?: continue
            val c = h.coords?.takeIf { it.isAttached } ?: continue
            val dy = nav.hideOffset(h)
            // 已经整个滑出屏幕：不画。玻璃栏每画一次都要把背后重新模糊一遍，转场后半段全省掉
            if (dy >= h.height + h.below - 0.5f) continue
            val r = root.localBoundingBoxOf(c, clipBounds = false)
            scope.translate(r.left, r.top + dy) { drawLayer(layer) }
        }
    }

    /** 遮挡物在收起的栏里面时，跟着栏一起往下滑的量（栏被代画时它自己的图层不动） */
    private fun carriedOffset(under: MochiNavEntry, c: LayoutCoordinates): Float {
        for (h in nav.hiddenOf(under)) {
            val hc = h.coords ?: continue
            var p: LayoutCoordinates? = c
            while (p != null) {
                if (p === hc) return nav.hideOffset(h)
                p = p.parentCoordinates
            }
        }
        return 0f
    }

    /**
     * 从来源打开 / 缩回来源时，把下层页登记的遮挡物（顶栏、底栏、滚动边缘渐隐，见 [mochiNavOccluder]）
     * 按下层页此刻的缩放画在所有页面上面，不透明度随打开进度淡掉：起点和落地时卡片仍在栏下面（和点之前一模一样），
     * 中途栏渐渐退到容器后面，而不是容器一出发就把栏一下盖住
     */
    private val occluderMask = NavPageMask()

    fun drawOccluders(scope: DrawScope) {
        val top = top() ?: return
        if (top.source == null || !top.inTransition || size.width <= 0f) return
        val under = nav.under(top) ?: return
        val list = nav.occludersOf(under)
        if (list.isEmpty()) return
        val a = 1f - smoothstep(0f, OccluderFadeTo, top.progress.value.coerceIn(0f, 1f))
        if (a <= 0.004f) return
        val root = under.contentCoords?.takeIf { it.isAttached } ?: return
        val u = openUnderScale(top)
        val page = mapUnder(full, u)
        // 下层页缩小后裁成屏幕圆角，栏的副本也裁在同一个轮廓里，四角不会露出直角
        // 先算出每个栏副本此刻的位置；已经滑出页面的不画，淡出用的离屏层也只开这些栏合起来那么大（不开整屏）
        val placed = ArrayList<Pair<GraphicsLayer, Rect>>(list.size)
        var bounds = Rect.Zero
        for (h in list) {
            val layer = h.layer ?: continue
            val c = h.coords?.takeIf { it.isAttached } ?: continue
            val r = mapUnder(root.localBoundingBoxOf(c, clipBounds = false).translate(0f, carriedOffset(under, c)), u)
            if (!r.overlaps(page)) continue
            placed += layer to r
            bounds = if (bounds.isEmpty) {
                r
            } else {
                Rect(minOf(bounds.left, r.left), minOf(bounds.top, r.top), maxOf(bounds.right, r.right), maxOf(bounds.bottom, r.bottom))
            }
        }
        if (placed.isEmpty()) return
        with(scope) {
            drawIntoCanvas { canvas ->
                // 整组一起淡：栏底色和上面的图标、文字不会各自半透明叠出深浅。
                // 离屏层往外多开一圈：栏的投影、玻璃的外阴影和边光都画在栏的矩形外面，只开栏那么大会被切成直角，
                // 转场一结束换回真的栏时阴影又一下冒出来
                val reach = OccluderShadowReach.toPx()
                canvas.saveLayer(bounds.inflate(reach).intersect(page), Paint().apply { alpha = a })
                for ((layer, r) in placed) {
                    translate(r.left, r.top) { scale(u, pivot = Offset.Zero) { drawLayer(layer) } }
                }
                // 下层页此刻的压暗也压在栏的副本上，和栏下面那份一样深
                val k = navUnderDim(top.progress.value, top.back.value)
                if (k > 0.001f) drawRect(spec.scrim.copy(alpha = spec.scrim.alpha * k), blendMode = BlendMode.SrcAtop)
                occluderMask.clearOutside(
                    this,
                    NavPageOutline(page, corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft),
                )
                canvas.restore()
            }
        }
    }

    /** 不看关闭，只按打开进度和拖动进度算的下层页缩放（关闭开始时从这里接上） */
    private fun openUnderScale(top: MochiNavEntry): Float {
        val p = top.progress.value.coerceIn(0f, 1f)
        val opened = lerp(1f, spec.underScale, p)
        // 拖动返回时下层页往回长一半，预告「松手就回到这里」
        return lerp(opened, lerp(spec.underScale, 1f, 0.5f), top.back.value)
    }

    private fun source(top: MochiNavEntry, u: Float): SourceInfo? {
        val key = top.source ?: return null
        val h = nav.source(key) ?: return null
        val under = nav.under(top) ?: return null
        if (h.entry !== under) return null
        val c = h.coords?.takeIf { it.isAttached } ?: return null
        val root = under.contentCoords?.takeIf { it.isAttached } ?: return null
        val local = root.localBoundingBoxOf(c, clipBounds = false)
        if (local.width <= 0f || !local.overlaps(full)) return null
        return SourceInfo(mapUnder(local, u), h.radius * u, h.color)
    }

    /** 下层页坐标 → 屏幕坐标（下层页绕中心缩放了 u） */
    private fun mapUnder(r: Rect, u: Float): Rect {
        val c = full.center
        return Rect(c + (r.topLeft - c) * u, c + (r.bottomRight - c) * u)
    }

    private fun gestureRect(base: Rect, e: MochiNavEntry): Rect {
        val g = e.back.value
        if (g == 0f) return base
        val sc = lerp(1f, spec.backScale, g)
        val dir = if (e.backEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        val maxDy = size.height * 0.04f
        val dy = (e.backDy * 0.1f).coerceIn(-maxDy, maxDy) * (g * 4f).coerceAtMost(1f)
        val c = base.center + Offset(dir * size.width * 0.03f * g, dy)
        val half = Offset(base.width * sc / 2f, base.height * sc / 2f)
        return Rect(c - half, c + half)
    }

    fun frame(e: MochiNavEntry, asOpen: Boolean = false): TopFrame {
        val p = e.progress.value
        val pc = p.coerceIn(0f, 1f)
        val u = openUnderScale(e)
        val src = source(e, u)
        var alpha = 1f
        // 打开、关闭、掉头都是同一个映射：矩形只由进度决定，所以任何时候换方向画面都连续
        val base = if (src != null) {
            // 大小跟 progress（smooth），中心跟 move（带一点回弹）：两根弹簧，位移到位时轻轻冲过再落回
            val m = e.move.value
            val w = lerp(src.rect.width, full.width, p)
            val h = lerp(src.rect.height, full.height, p)
            val c = Offset(lerp(src.rect.center.x, full.center.x, m), lerp(src.rect.center.y, full.center.y, m))
            Rect(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f)
        } else {
            // 来源不在了（被滚走 / 销毁）：原地缩放淡入淡出
            alpha = pc
            val c = full.center
            val k = lerp(0.84f, 1f, p)
            Rect(c - Offset(size.width * k / 2f, size.height * k / 2f), c + Offset(size.width * k / 2f, size.height * k / 2f))
        }
        val rect = gestureRect(base, e)
        val mix = p
        // 卡片画面渐隐、页面渐显，都只看进度：任何时候掉头都连续
        val cover = if (src != null) 1f - smoothstep(PageFadeFrom, PageFadeTo, pc) else 0f
        // 圆角：页面在整屏到中段之间时严格等于屏幕圆角；只在靠近来源的后半程过渡到来源的圆角
        val w = if (src != null) {
            val kSrc = src.rect.width / size.width
            val k = rect.width / size.width
            smoothstep(0f, 0.5f, ((k - kSrc) / (1f - kSrc).coerceAtLeast(1e-3f)).coerceIn(0f, 1f))
        } else {
            1f
        }
        val sr = src?.radius ?: 0f
        return TopFrame(
            rect = rect,
            tl = lerp(sr, corners.topLeft, w),
            tr = lerp(sr, corners.topRight, w),
            br = lerp(sr, corners.bottomRight, w),
            bl = lerp(sr, corners.bottomLeft, w),
            alpha = alpha,
            cover = cover,
            coverColor = src?.color ?: Color.Transparent,
            mix = mix,
            underScale = u,
            source = src,
        )
    }

    /** 关闭开始：记下此刻的画面，关闭动画从这里连续地缩回来源 */
    fun captureClose(e: MochiNavEntry) {
        val f = frame(e, asOpen = true)
        e.closeFrom = f.rect
        e.closeUnder = f.underScale
        e.closeMix = f.mix.coerceIn(0f, 1f)
        e.closeCover = f.cover
        e.closeCaptured = true
    }

    /** 共享元素飞行（已停用：转场改成「卡片的底变形 + 内容淡入淡出」，元素不再单独飞） */
    @Suppress("unused")
    fun drawShared(scope: DrawScope) {
        if (DisableSharedFlight) return
        val top = top() ?: return
        if (top.source == null || !top.inTransition) return
        val under = nav.under(top) ?: return
        val topRoot = top.contentCoords?.takeIf { it.isAttached } ?: return
        val underRoot = under.contentCoords?.takeIf { it.isAttached } ?: return
        val f = frame(top)
        if (f.source == null) return
        val s = f.rect.width / size.width
        val m = f.mix
        val fade = smoothstep(0.2f, 0.55f, m.coerceIn(0f, 1f))
        for (pe in nav.elementsOf(top)) {
            val ce = nav.element(pe.key, under) ?: continue
            val pLayer = pe.layer ?: continue
            val cLayer = ce.layer ?: continue
            val pc = pe.coords?.takeIf { it.isAttached } ?: continue
            val cc = ce.coords?.takeIf { it.isAttached } ?: continue
            val pLocal = topRoot.localBoundingBoxOf(pc, clipBounds = false)
            val cLocal = underRoot.localBoundingBoxOf(cc, clipBounds = false)
            if (pLocal.width <= 0f || cLocal.width <= 0f) continue
            val cScreen = mapUnder(cLocal, f.underScale)
            val pScreen = Rect(f.rect.topLeft + pLocal.topLeft * s, Size(pLocal.width * s, pLocal.height * s))
            // 元素比页面先到位：进度到 65% 时已经贴在页面上的位置。页面内容是在后半程才显出来的，
            // 显出来时元素已经在原位，不会出现「标题还在半路、压在页面正文上」的错位
            val d = lerpRect(cScreen, pScreen, smoothstep(0f, 0.65f, m.coerceIn(0f, 1f)))
            scope.drawFlying(cLayer, d, cLocal.size, 1f - fade)
            scope.drawFlying(pLayer, d, pLocal.size, fade)
        }
    }

    /**
     * 把元素的画面按原比例「装进」飞行中的矩形 d（左上对齐）。两边排版不同时（比如文字折行不一样）
     * 不会被拉成一大片，只是交叉淡化。
     */
    private fun DrawScope.drawFlying(layer: androidx.compose.ui.graphics.layer.GraphicsLayer, d: Rect, size: Size, a: Float) {
        if (a <= 0.001f || size.width <= 0f || size.height <= 0f) return
        layer.alpha = a
        val k = minOf(d.width / size.width, d.height / size.height)
        translate(d.left, d.top) {
            scale(k, pivot = Offset.Zero) { drawLayer(layer) }
        }
        layer.alpha = 1f
    }
}

/** 下层页的顶栏 / 底栏副本在打开进度到这里时完全淡掉 */
private const val OccluderFadeTo = 0.5f

/** 栏的副本淡出时离屏层往外多开的范围：盖住栏的投影和玻璃的外阴影 */
private val OccluderShadowReach = 40.dp

/** 共享元素不再单独飞行 */
private const val DisableSharedFlight = true

/** 容器变形里卡片画面淡出的进度区间（一开始就跟着容器长大并淡出） */
private const val CardFadeFrom = 0.04f

private const val CardFadeTo = 0.38f

/** 容器变形里页面内容淡入的进度区间（和卡片的淡出交叠，中间不会只剩一块底色） */
private const val PageFadeFrom = 0.12f

private const val PageFadeTo = 0.5f

private fun lerpRect(a: Rect, b: Rect, t: Float): Rect = Rect(
    lerp(a.left, b.left, t),
    lerp(a.top, b.top, t),
    lerp(a.right, b.right, t),
    lerp(a.bottom, b.bottom, t),
)
