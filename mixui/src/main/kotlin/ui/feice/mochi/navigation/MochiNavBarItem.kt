// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import ui.feice.mochi.effect.content.mochiCachedBlur
import ui.feice.mochi.effect.glass.GlassFill
import ui.feice.mochi.effect.glass.GlassSurface
import ui.feice.mochi.effect.metaball.MochiBlob
import ui.feice.mochi.effect.metaball.MochiMetaballColors
import ui.feice.mochi.effect.metaball.MochiMetaballFrame
import ui.feice.mochi.effect.metaball.MochiMetaballRenderer
import ui.feice.mochi.effect.metaball.drawMochiMetaball
import ui.feice.mochi.effect.metaball.proximityFuse
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.theme.MochiGraphics
import ui.feice.mochi.utils.smoothstep

/*
 * 跨页共享的顶栏（iOS 26：导航转场时顶栏按钮不跟着页面滑走，而是原地变形）。
 *
 * 侧推转场（push / pop，含预测性返回、全屏右滑返回）中，两页顶栏上的按钮都由 MochiNavHost 画在所有页面上面、留在顶栏：
 * - 形状（按钮的胶囊、按钮组的胶囊）按位置配对：左右两侧各自从屏幕边往里数，第 i 个对第 i 个，位置和大小连续地变过去。新页多出来的形状从最里面那个配对的形状里长出来（一个圆往旁边长成一团、再裂成两个），
 *   旧页多出来的反过来被吸回去；所有形状用融球画，挨得近时连成一团，分开时颈被拉细、掐断。一边完全没有形状时原地放大弹出 / 缩小消失
 * - 内容（图标、文字）按 id 配对：内容相同（contentKey 相等）的跟着走，原地不动时纹丝不动；不同的旧的糊掉淡出、新的从很模糊里显出来
 *
 * 顶栏按钮（MochiBarIconButton / MochiBarButton / MochiBarGroup）给 navId 就会参与；MochiBackButton 自带。
 * 别的内容可以用 [mochiNavBarItem] 只登记内容（没有形状，只做模糊替换）。只在 [MochiNavHost] 里生效。
 */

/**
 * 把任意内容登记成顶栏内容（只有内容、没有形状）：侧推转场中留在顶栏原位，换页时模糊替换，不跟着页面滑。
 * 顶栏按钮请直接用 MochiBarIconButton / MochiBarButton / MochiBarGroup 的 navId 参数，那样按钮的形状也会跟着变形。
 *
 * @param id 同一页里唯一；两页 id 相同的内容会接上（位置连续地移过去）
 * @param contentKey 内容的标识（比如图标）。两边相等（且不为 null）时视为同一个内容，原样跟着走；为 null 时一律按「换了内容」处理
 */
public fun Modifier.mochiNavBarItem(id: String, contentKey: Any? = null): Modifier = this then NavBarContentElement(id, contentKey)

/**
 * 顶栏按钮的形状：包在按钮最外面，转场中整个按钮在页里不画（由 MochiNavHost 接管）。cornerRadius 为负数 = 胶囊。
 * [glass]：按钮的玻璃（材质和它在页里取样的背景），转场中 MochiNavHost 也把这个形状画成玻璃（[SharedBarGlass]），不画成 [color] 的纯色；
 * 主机没有整页录制可取样时（页面里有 WebView 这类原生视图，windowGlass = false），转场里的玻璃取样按钮自己所在页的背景
 */
internal fun Modifier.navBarShape(id: String, color: Color, cornerRadius: Float = -1f, glass: GlassFill? = null): Modifier =
    this then NavBarShapeElement(id, color, cornerRadius, glass)

/** [MochiBackButton] 用的共享 id */
internal const val BackBarItemId = "mochi.back"

/** 登记信息的公共部分：所在的页、位置、录下来的画面 */
internal sealed class BarHandle(var id: String) {
    var entry: MochiNavEntry? = null
    var shown: () -> Boolean = AlwaysShown
    var coords: LayoutCoordinates? = null
    var layer: GraphicsLayer? = null
    var size: Size = Size.Zero

    /** 页面里的矩形（未变换的页面坐标系 = 整屏坐标），不受页面滑动影响 */
    fun restRect(page: LayoutCoordinates): Rect? {
        val c = coords?.takeIf { it.isAttached } ?: return null
        if (!page.isAttached) return null
        // 浮层继承 CompositionLocal，但有独立的布局树；只有页内节点才能参与共享顶栏。
        var ancestor: LayoutCoordinates? = c
        while (ancestor !== page) {
            ancestor = ancestor?.parentLayoutCoordinates ?: return null
        }
        return Rect(page.localPositionOf(c, Offset.Zero), size)
    }
}

/** 形状：胶囊或圆角矩形 + 底色 */
internal class BarShapeHandle(id: String) : BarHandle(id) {
    var color: Color = Color.Transparent
    var cornerRadius: Float = -1f
    var glass: GlassFill? = null
}

/** 内容：录下来的图标 / 文字 */
internal class BarContentHandle(id: String) : BarHandle(id) {
    var contentKey: Any? = null
}

private data class NavBarContentElement(val id: String, val contentKey: Any?) : ModifierNodeElement<NavBarNode>() {
    override fun create(): NavBarNode = NavBarNode(BarContentHandle(id)).also { update(it) }

    override fun update(node: NavBarNode) {
        (node.handle as BarContentHandle).contentKey = contentKey
        node.updateId(id)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiNavBarItem"
        properties["id"] = id
        properties["contentKey"] = contentKey
    }
}

private data class NavBarShapeElement(val id: String, val color: Color, val cornerRadius: Float, val glass: GlassFill?) :
    ModifierNodeElement<NavBarNode>() {
    override fun create(): NavBarNode = NavBarNode(BarShapeHandle(id)).also { update(it) }

    override fun update(node: NavBarNode) {
        val h = node.handle as BarShapeHandle
        h.color = color
        h.cornerRadius = cornerRadius
        h.glass = glass
        node.updateId(id)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "navBarShape"
        properties["id"] = id
    }
}

private class NavBarNode(val handle: BarHandle) :
    Modifier.Node(),
    CompositionLocalConsumerModifierNode,
    LayoutAwareModifierNode,
    DrawModifierNode {
    private var nav: MochiNavigator? = null

    // 导航器和所在的页对一个节点来说一辈子不变（换页会换节点），挂上时读一次就够
    @SuppressLint("SuspiciousCompositionLocalModifierRead")
    override fun onAttach() {
        val n = currentValueOf(LocalMochiNavigator) ?: return
        nav = n
        handle.entry = currentValueOf(LocalMochiNavEntry)
        handle.shown = currentValueOf(LocalMochiPeerPageShown)
        handle.layer = requireGraphicsContext().createGraphicsLayer()
        n.registerBar(handle)
    }

    override fun onDetach() {
        nav?.unregisterBar(handle)
        nav = null
        handle.layer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        handle.layer = null
        handle.coords = null
    }

    fun updateId(id: String) {
        if (id == handle.id) return
        nav?.unregisterBar(handle)
        handle.id = id
        nav?.registerBar(handle)
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
        // 总是录一遍（里面的内容也因此录好自己的那层，新页第一帧就被接管时也有画面）；被接管时这里不画，不然会跟着页面滑
        layer.record { this@draw.drawContent() }
        handle.size = size
        if (n.isBarCarried(handle)) return
        layer.alpha = 1f
        layer.renderEffect = null
        drawLayer(layer)
    }
}

/**
 * 一帧里的一个形状（rect 是此刻的矩形）。corner 为负数 = 胶囊：圆角永远是此刻短边的一半，
 * 变形途中鼓起、缩放时也一直是胶囊，不会变成偏方的圆角矩形
 */
private class MorphShape(
    val rect: Rect,
    val corner: Float,
    val color: Color,
    val alpha: Float,
    val leading: Boolean,
    val glass: GlassFill? = null,
) {
    val radius: Float
        get() {
            val half = minOf(rect.width, rect.height) / 2f
            return if (corner < 0f) half else corner.coerceAtMost(half)
        }
}

/** 一帧里的一项内容：此刻的中心、透明度、模糊，以及它在所在形状里的相对位置（形状变形时跟着走） */
private class MorphContent(val handle: BarContentHandle, var center: Offset, var alpha: Float, var blur: Float) {
    var shape = -1
    var fx = 0.5f
    var fy = 0.5f

    fun anchorTo(shapes: List<MorphShape>) {
        if (shape < 0) return
        val r = shapes[shape].rect
        center = Offset(r.left + fx * r.width, r.top + fy * r.height)
    }
}

/** MochiNavHost 里每帧复用的路径缓存和融球渲染器 */
internal class BarPathCache {
    val renderer = MochiMetaballRenderer()
    private val caches = ArrayList<G2PathCache>()

    fun path(i: Int, r: Rect, radius: Float): Path {
        while (caches.size <= i) caches += G2PathCache()
        return caches[i].get(r, radius, 1f)
    }
}

/**
 * 在所有页面上面画侧推转场中的顶栏（MochiNavHost 的最上层调用）。见文件开头的说明。
 * 正在动的一串页（[MochiNavigator.slideChain]）从起点页的顶栏开始，按每一页的进度依次变形过去，最后画一次：
 * 快速连续推入 / 返回时也只有一套顶栏，某一页转场结束、这一串变短时画面不跳
 */
internal fun DrawScope.drawSharedBarItems(nav: MochiNavigator, paths: BarPathCache, glass: Boolean = false) {
    val f = sharedBarFrame(nav, size.width, ContentBlur.toPx()) ?: return
    drawMorphShapes(if (glass) f.shapes.filter { it.glass == null } else f.shapes, f.envelope, paths)
    // 先画淡出的，再画淡入 / 接着走的
    f.contents.sortedBy { it.alpha }.forEach { drawContentLayer(it.handle, it.center, it.alpha, it.blur) }
}

/** 一帧的共享顶栏：此刻的形状、内容和融球颈的包络 */
private class SharedBarFrame(val shapes: List<MorphShape>, val contents: List<MorphContent>, val envelope: Float)

/**
 * 侧推转场里画成玻璃的共享顶栏形状（[navBarShape] 的 glass），按从左到右的顺序；没在转场时为空。
 * [width] 是导航主机的宽，[blurMax] 内容模糊的上限（px，只影响内容，这里用不到但算法共用）
 */
internal fun sharedBarGlassShapes(nav: MochiNavigator, width: Float, blurMax: Float): List<SharedGlassShape> =
    sharedBarFrame(nav, width, blurMax)?.shapes?.filter { it.glass != null }?.sortedBy { it.rect.center.x }
        ?.map { SharedGlassShape(it.rect, it.radius, it.alpha, it.glass!!) }.orEmpty()

/** 转场里一块玻璃形状：此刻的矩形、圆角（px）、透明度，和按钮在页里的玻璃（主机没有整页录制时取样它所在页的背景） */
internal class SharedGlassShape(val rect: Rect, val radius: Float, val alpha: Float, val fill: GlassFill)

private fun sharedBarFrame(nav: MochiNavigator, width: Float, blurMax: Float): SharedBarFrame? {
    val chain = nav.slideChain()
    if (chain.size < 2) return null
    val base = chain.first()
    val bc = base.contentCoords?.takeIf { it.isAttached } ?: return null
    val mid = width / 2f

    // 起点：起点页的顶栏原样
    var shapes = nav.barsOf(base).filterIsInstance<BarShapeHandle>().mapNotNull { h ->
        h.restRect(bc)?.let { MorphShape(it, h.cornerRadius, h.color, 1f, it.center.x < mid, h.glass) }
    }
    var contents = nav.barsOf(base).filterIsInstance<BarContentHandle>().mapNotNull { h ->
        h.restRect(bc)?.let { r -> MorphContent(h, r.center, 1f, 0f).also { it.attach(r, shapes) } }
    }
    // 颈只在转场中段连（开头、结尾和静止页面一样不连）；一串里任何一步在中段都连，快速连推时颈不会突然断开
    var envelope = 0f
    for (k in 1 until chain.size) {
        val page = chain[k]
        val pc = page.contentCoords?.takeIf { it.isAttached } ?: continue
        val m = slideProgress(page).coerceIn(0f, 1f)
        envelope = maxOf(envelope, fuseEnvelope(m))
        val targetShapes = nav.barsOf(page).filterIsInstance<BarShapeHandle>().mapNotNull { h -> h.restRect(pc)?.let { h to it } }
        val step = morphStep(shapes, targetShapes, m, mid)
        // 旧的内容跟着自己的形状走
        contents.forEach { c ->
            c.shape = if (c.shape >= 0) step.fromOld[c.shape] else -1
            c.anchorTo(step.shapes)
        }
        val next = ArrayList<MorphContent>()
        val used = HashSet<MorphContent>()
        val carried = HashSet<MorphContent>()
        for (h in nav.barsOf(page).filterIsInstance<BarContentHandle>()) {
            val r = h.restRect(pc) ?: continue
            val inc = MorphContent(h, r.center, 0f, 0f)
            inc.attach(r, targetShapes.map { it.second }, step.fromNew)
            inc.anchorTo(step.shapes)
            val old = contents.filter { it.handle.id == h.id && it !in used }.maxByOrNull { it.alpha }
            if (old != null) {
                used += old
                val anchored = inc.center
                inc.center = lerp(old.center, anchored, m)
                if (h.contentKey != null && h.contentKey == old.handle.contentKey) {
                    // 同一个内容：接着旧的走（透明度、模糊都连续），移动时中途稍微糊一下
                    val moving = (old.center - anchored).getDistance() > 0.5f
                    inc.alpha = old.alpha
                    inc.blur = old.blur * (1f - m) + if (moving) blurMax * 0.5f * pop(m) else 0f
                    carried += old
                    next += inc
                    continue
                }
                old.center = inc.center
            }
            inc.alpha = smoothstep(0.45f, 0.85f, m)
            inc.blur = blurMax * (1f - smoothstep(0.4f, 0.95f, m))
            next += inc
        }
        // 没接上的旧内容：糊掉、淡出
        for (c in contents) {
            if (c in carried) continue
            c.alpha *= 1f - smoothstep(0.1f, 0.5f, m)
            c.blur = maxOf(c.blur, blurMax * smoothstep(0f, 0.45f, m))
            if (c.alpha > 0.003f) next += c
        }
        shapes = step.shapes
        contents = next
    }
    return SharedBarFrame(shapes, contents, envelope)
}

/** 内容挂到所在的形状上：找包含它中心的那个形状，记下相对位置 */
private fun MorphContent.attach(rest: Rect, shapes: List<MorphShape>) {
    val i = shapes.indexOfFirst { it.rect.contains(rest.center) }
    if (i < 0) return
    shape = i
    val r = shapes[i].rect
    fx = if (r.width > 0f) (rest.center.x - r.left) / r.width else 0.5f
    fy = if (r.height > 0f) (rest.center.y - r.top) / r.height else 0.5f
}

/** 新页的内容挂到新页的形状上（形状在这一步结果里的序号由 indexMap 给出） */
private fun MorphContent.attach(rest: Rect, restShapes: List<Rect>, indexMap: IntArray) {
    val i = restShapes.indexOfFirst { it.contains(rest.center) }
    if (i < 0) return
    shape = indexMap[i]
    val r = restShapes[i]
    fx = if (r.width > 0f) (rest.center.x - r.left) / r.width else 0.5f
    fy = if (r.height > 0f) (rest.center.y - r.top) / r.height else 0.5f
}

private fun BarShapeHandle.radiusFor(r: Rect) = if (cornerRadius < 0f) minOf(r.width, r.height) / 2f else cornerRadius

/** 一步变形的结果：此刻的形状，以及旧形状 / 新页形状各自变成了结果里的第几个 */
private class MorphStep(val shapes: List<MorphShape>, val fromOld: IntArray, val fromNew: IntArray)

/**
 * 从当前的形状变向一页的形状（进度 m）。左右两侧各自从屏幕边往里排，第 i 个对第 i 个；
 * 多出来的从最里面那个配对的形状里长出来 / 被吸回去；一边完全没有时原地放大弹出 / 缩小消失。
 * 变形带 iOS 的弹性（[elasticRect]）：中途整体鼓起，到终点冲过头一点再落回
 */
private fun morphStep(cur: List<MorphShape>, target: List<Pair<BarShapeHandle, Rect>>, m: Float, mid: Float): MorphStep {
    val out = ArrayList<MorphShape>()
    val fromOld = IntArray(cur.size) { -1 }
    val fromNew = IntArray(target.size) { -1 }
    for (leading in booleanArrayOf(true, false)) {
        fun order(r: Rect) = if (leading) r.left else -r.right
        val a = cur.indices.filter { cur[it].leading == leading }.sortedBy { order(cur[it].rect) }
        val b = target.indices.filter { (target[it].second.center.x < mid) == leading }.sortedBy { order(target[it].second) }
        val n = minOf(a.size, b.size)
        fun morph(from: MorphShape, to: Pair<BarShapeHandle, Rect>, color: Color): MorphShape {
            val (h, r) = to
            val rect = elasticRect(from.rect, r, m)
            // 两头都是胶囊就一直是胶囊；否则圆角在两头之间过渡
            val corner = if (from.corner < 0f && h.cornerRadius < 0f) -1f else lerpF(from.radius, h.radiusFor(r), m)
            return MorphShape(rect, corner, color, lerpF(from.alpha, 1f, m), leading, h.glass ?: from.glass)
        }
        for (i in 0 until n) {
            val from = cur[a[i]]
            val to = target[b[i]]
            fromOld[a[i]] = out.size
            fromNew[b[i]] = out.size
            out += morph(from, to, lerp(from.color, to.first.color, m))
        }
        for (j in n until b.size) {
            val to = target[b[j]]
            fromNew[b[j]] = out.size
            out += if (n > 0) morph(cur[a[n - 1]], to, to.first.color) else appear(to.first, to.second, m, leading)
        }
        for (j in n until a.size) {
            val from = cur[a[j]]
            fromOld[a[j]] = out.size
            out += if (n > 0) {
                MorphShape(elasticRect(from.rect, target[b[n - 1]].second, m), from.corner, from.color, from.alpha, leading, from.glass)
            } else {
                vanish(from, m)
            }
        }
    }
    return MorphStep(out, fromOld, fromNew)
}

/** 一边没有任何形状时：原地从小放大弹出（m 0 → 1），略微冲过头再落回 */
private fun appear(h: BarShapeHandle, r: Rect, m: Float, leading: Boolean): MorphShape {
    val e = smoothstep(0.1f, 0.85f, m)
    val s = AppearFrom + (1f - AppearFrom) * e + AppearPop * pop(e)
    val half = Offset(r.width * s / 2f, r.height * s / 2f)
    val rr = Rect(r.center - half, r.center + half)
    return MorphShape(rr, if (h.cornerRadius < 0f) -1f else h.cornerRadius * s, h.color, e, leading, h.glass)
}

/** 新页这一侧没有形状：原地缩小、淡出 */
private fun vanish(from: MorphShape, m: Float): MorphShape {
    val e = 1f - smoothstep(0.15f, 0.9f, m)
    val s = AppearFrom + (1f - AppearFrom) * e
    val half = Offset(from.rect.width * s / 2f, from.rect.height * s / 2f)
    return MorphShape(
        Rect(from.rect.center - half, from.rect.center + half),
        if (from.corner <
            0f
        ) {
            -1f
        } else {
            from.corner * s
        },
        from.color,
        from.alpha * e,
        from.leading,
        from.glass,
    )
}

/** 画一帧的所有形状：挨着的两两用融球连起来（离得近就长出颈，拉开就掐断），开头和结尾不连，和静止的页面完全一样 */
private fun DrawScope.drawMorphShapes(shapes: List<MorphShape>, envelope: Float, paths: BarPathCache) {
    if (shapes.isEmpty()) return
    val sorted = shapes.sortedBy { it.rect.center.x }

    val fuseMax = FuseRadius.toPx() * envelope
    val reach = FuseReach.toPx()
    val joined = BooleanArray(sorted.size)
    if (MochiGraphics.shaders && fuseMax > 0.5f) {
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (a.alpha < 0.99f || b.alpha < 0.99f) continue
            val fuse = fuseMax * proximityFuse(a.rect, b.rect, reach)
            if (fuse <= 0.5f) continue
            joined[i] = true
            joined[i + 1] = true
            drawMochiMetaball(
                paths.renderer,
                MochiMetaballFrame(MochiBlob(a.rect, a.radius), MochiBlob(b.rect, b.radius), fuse),
                MochiMetaballColors(a.color, b.color, Color.Transparent),
            )
        }
    }
    sorted.forEachIndexed { i, s ->
        if (!joined[i]) drawPath(paths.path(i, s.rect, s.radius), s.color, alpha = s.alpha)
    }
}

private fun DrawScope.drawContentLayer(h: BarHandle, center: Offset, alpha: Float, blur: Float) {
    val layer = h.layer ?: return
    if (alpha <= 0.003f) return
    layer.alpha = alpha.coerceIn(0f, 1f)
    layer.renderEffect = if (blur > 0.3f && Build.VERSION.SDK_INT >= 31) mochiCachedBlur(blur) else null
    withTransform({ translate(center.x - h.size.width / 2f, center.y - h.size.height / 2f) }) { drawLayer(layer) }
}

/**
 * 形状从 from 变到 to 的弹性过程（对着 iOS 26 录屏估的）：
 * - 大小和位置按带回弹的曲线走，到终点冲过头一点再落回
 * - 中途整体鼓起，高度鼓得比宽度多（玻璃被「吹」起来那一下）
 * 两端一样的形状（比如返回按钮）纹丝不动。这是设计出来的动感，和融球合并时多出来的鼓包（已在 shader 里限制住）不是一回事
 */
private fun elasticRect(from: Rect, to: Rect, m: Float): Rect {
    val changed = abs(from.left - to.left) + abs(from.right - to.right) + abs(from.top - to.top) + abs(from.bottom - to.bottom) > 1f
    if (!changed) return to
    val r = lerpRect(from, to, backOut(m))
    val k = pop(m)
    val dw = r.width * SwellX * k / 2f
    val dh = r.height * SwellY * k / 2f
    return Rect(r.left - dw, r.top - dh, r.right + dw, r.bottom + dh)
}

/** 带回弹的缓出：0 → 1，接近终点时冲过头约 5% 再落回 */
private fun backOut(t: Float): Float {
    val c1 = OvershootFactor
    val u = t - 1f
    return 1f + (c1 + 1f) * u * u * u + c1 * u * u
}

/** 0 → 1 → 0 的鼓起曲线，峰值在 0.5 */
private fun pop(t: Float): Float = 4f * t * (1f - t)

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

private fun lerpRect(a: Rect, b: Rect, t: Float) =
    Rect(lerpF(a.left, b.left, t), lerpF(a.top, b.top, t), lerpF(a.right, b.right, t), lerpF(a.bottom, b.bottom, t))

/** 内容换掉时最模糊处（视频里几乎糊成一团） */
private val ContentBlur = 12.dp

/** 变形中途鼓起：高度、宽度各多出多少（估计值） */
private const val SwellY = 0.14f
private const val SwellX = 0.05f

/** 回弹曲线的系数：约冲过头 5% 再落回（估计值） */
private const val OvershootFactor = 1.2f

/** 两块形状之间颈的最大融合半径；离得比 [FuseReach] 远就不连 */
private val FuseRadius = 18.dp
private val FuseReach = 14.dp

/** 一边没有形状时从多小开始放大弹出，中途冲过头多少 */
private const val AppearFrom = 0.55f
private const val AppearPop = 0.12f

/** 一步变形的进度 m 时颈的强度：开头和结尾为 0，第一帧和最后一帧和静止的页面完全一样 */
private fun fuseEnvelope(m: Float): Float = smoothstep(0f, 0.12f, m) * (1f - smoothstep(0.88f, 1f, m))

/**
 * 侧推转场里把玻璃按钮的共享形状画成玻璃（[navBarShape] 的 glass）：最多 [SharedGlassSlots] 块，
 * 每块在排版阶段读这一帧的形状（[sharedBarGlassShapes]），按它的位置、大小、圆角画，不重组。
 * [fill] 取样整页的录制，这一层要放在录制范围外面（不然取样到自己）；形状上的图标由 [drawSharedBarItems] 画在它上面。
 * [fill] 为 null（主机不录整页）时每块用按钮自己的玻璃，取样它所在页的背景：转场里玻璃按钮还是玻璃，不变成纯色
 */
@Composable
internal fun SharedBarGlass(nav: MochiNavigator, fill: GlassFill?) {
    val blurMax = with(LocalDensity.current) { ContentBlur.toPx() }
    var width by remember { mutableFloatStateOf(0f) }
    fun shape(i: Int) = sharedBarGlassShapes(nav, width, blurMax).getOrNull(i)
    // 每块用哪份玻璃：只在换了的时候重组，形状每帧变不重组
    val fills by remember(nav, fill) {
        derivedStateOf { List(SharedGlassSlots) { i -> fill ?: shape(i)?.fill } }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width.toFloat() }) {
        repeat(SharedGlassSlots) { i ->
            val slotFill = fills[i] ?: return@repeat
            GlassSurface(
                slotFill,
                null,
                null,
                Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val r = shape(i)?.rect
                        val w = r?.width?.roundToInt()?.coerceAtLeast(1) ?: 1
                        val h = r?.height?.roundToInt()?.coerceAtLeast(1) ?: 1
                        val placeable = measurable.measure(Constraints.fixed(w, h))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(r?.left?.roundToInt() ?: 0, r?.top?.roundToInt() ?: 0)
                        }
                    },
                alpha = { shape(i)?.alpha ?: 0f },
                cornerRadiusPx = { shape(i)?.radius ?: 0f },
            )
        }
    }
}

/** 转场里同时画成玻璃的共享形状最多几块（左边返回按钮 + 右边一两个按钮 / 按钮组，变形途中多出来的也够用） */
private const val SharedGlassSlots = 4
