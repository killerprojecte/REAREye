// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ui.feice.mochi.effect.glass.engine.BackdropSampler
import ui.feice.mochi.effect.mask.MochiSmoothGradient
import ui.feice.mochi.effect.mask.mochiScrollFadeMask as maskScrollFade
import ui.feice.mochi.theme.LocalMochiColorScheme
import ui.feice.mochi.theme.LocalMochiMaterials
import ui.feice.mochi.theme.MochiMaterials
import ui.feice.mochi.theme.MochiScrollEdge
import ui.feice.mochi.theme.MochiScrollEdgeLevel
import ui.feice.mochi.utils.smoothstep

/**
 * iOS 26 的「滚动边缘效果」：顶栏 / 底栏朝内容的一侧压住滚进来的内容，不需要分隔线。
 *
 * - 主题没有 [MochiMaterials.scrollEdge]（默认）：栏后面铺一层底色 [color]，朝内容的一侧多延出 [extent] 渐隐成透明
 * - 主题给了（比如 iOS 外观）：按它画 iOS 的做法——按栏后面内容的亮度分档，在遮罩里盖一层页面底色 [color] 或黑色；
 *   遮罩的范围来自主题，[extent] 不起作用。遮罩里内容的渐进模糊由滚动容器自己做：
 *   容器加 [ui.feice.mochi.effect.mask.mochiScrollEdgeBlur]（标题脚手架已经接好）
 *
 * @param bottom false = 顶栏（往下延），true = 底栏（往上延）
 * 自带 [mochiNavOccluder]：从这一页的卡片打开时，栏先盖在变形的容器上再淡掉，不会被一下盖住。
 *
 * @param alpha 整体不透明度（在 draw 阶段读，比如跟着滚动距离从 0 到 1），不会触发重组
 * @param tone 传进来时，按栏后面叠完之后的亮度写进「栏上文字该用深色还是浅色」，栏上的文字用
 *   [MochiScrollEdgeDefaults.textColor] 取色（[MochiTitleBar] 已经接好）
 */
public fun Modifier.mochiScrollEdgeFade(
    color: Color,
    bottom: Boolean = false,
    extent: Dp = 16.dp,
    alpha: () -> Float = { 1f },
    tone: MochiScrollEdgeTone? = null,
): Modifier = // 是遮挡物：从来源打开时盖在变形的容器上再淡掉（见 mochiNavOccluder）
    mochiNavOccluder() then ScrollEdgeElement(color, bottom, extent, alpha, tone)

/**
 * 栏后面（渐进模糊 + 底色 + 压暗叠完之后）看起来有多亮，决定栏上的文字用深色还是浅色。
 * 深色主题滚到大片亮色内容下面时，渐进模糊偏白，白字会看不清；这时换成深色字（浅色主题反过来）。
 */
@Stable
public class MochiScrollEdgeTone {
    /** null：按主题的文字色（背后没内容，或主题没有滚动边缘效果）；true：深色字；false：浅色字 */
    public var darkText: Boolean? by mutableStateOf(null)
        internal set
}

private data class ScrollEdgeElement(
    val color: Color,
    val bottom: Boolean,
    val extent: Dp,
    val alpha: () -> Float,
    val tone: MochiScrollEdgeTone?,
) : ModifierNodeElement<ScrollEdgeNode>() {
    override fun create(): ScrollEdgeNode = ScrollEdgeNode(color, bottom, extent, alpha, tone)

    override fun update(node: ScrollEdgeNode) {
        node.color = color
        node.bottom = bottom
        node.extent = extent
        node.alpha = alpha
        node.tone = tone
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiScrollEdgeFade"
    }
}

private class ScrollEdgeNode(
    var color: Color,
    var bottom: Boolean,
    var extent: Dp,
    var alpha: () -> Float,
    var tone: MochiScrollEdgeTone?,
) : Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode,
    GlobalPositionAwareModifierNode {
    private var coords: LayoutCoordinates? = null
    private var sampler: BackdropSampler? = null

    // 当前这一档的两层颜色，换档时用弹簧过渡（iOS 那边也是弹簧动画，参数没读到，这里取临界阻尼 0.3s，是近似）
    private val replay = Animatable(0f)
    private val dim = Animatable(0f)
    private var levelSet = false

    // 遮罩范围（本地坐标，绘制时算好，取样时用）
    private var regionTop = 0f
    private var regionBottom = 0f

    override fun onAttach() {
        coroutineScope.launch { sampleLoop() }
    }

    override fun onDetach() {
        sampler?.release()
        sampler = null
        coords = null
        levelSet = false
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        coords = coordinates
    }

    /** iOS 每 0.25s 更新一次背后亮度（lumaUpdateRate），按 1/31 分档 */
    private suspend fun sampleLoop() {
        val view = currentValueOf(LocalView)
        val gap = with(currentValueOf(LocalDensity)) { SampleHeight.toPx() }
        while (coroutineScope.isActive) {
            val edge = currentValueOf(LocalMochiMaterials).scrollEdge
            val c = coords?.takeIf { it.isAttached }
            val entry = currentValueOf(LocalMochiNavEntry)
            val moving = entry != null && currentValueOf(LocalMochiNavigator)?.isMovingOrCovered(entry) == true
            // A fully transparent edge has no visible color or tone to update.
            // PixelCopy still synchronizes with GPU work, even for a 16×4 bitmap.
            if (edge != null && c != null && alpha() > 0f && !moving) {
                val s = sampler ?: BackdropSampler().also { sampler = it }
                // 取遮罩外面紧挨着的一条：遮罩里面已经被压暗、栏上还有标题按钮，不是背后内容本来的样子
                val top = if (bottom) regionTop - gap else regionBottom
                val w = c.localToWindow(Offset(0f, top))
                s.request(view, android.graphics.Rect(w.x.toInt(), w.y.toInt(), (w.x + c.size.width).toInt(), (w.y + gap).toInt()))
                s.average?.let { avg ->
                    val luma = ((0.2126f * avg.red + 0.7152f * avg.green + 0.0722f * avg.blue) * 31f).roundToInt() / 31f
                    val isDark = currentValueOf(LocalMochiColorScheme).isDark
                    val level = edge.level(luma, isDark)
                    val rp = if (bottom) level.replayBottom else level.replay
                    if (!levelSet) {
                        levelSet = true
                        replay.snapTo(rp)
                        dim.snapTo(level.dim)
                        invalidateDraw()
                    } else {
                        if (replay.targetValue != rp) coroutineScope.launch { replay.animateTo(rp, LevelSpring) { invalidateDraw() } }
                        if (dim.targetValue !=
                            level.dim
                        ) {
                            coroutineScope.launch { dim.animateTo(level.dim, LevelSpring) { invalidateDraw() } }
                        }
                    }
                    tone?.let { updateTone(it, avg, level, isDark) }
                }
            } else if (alpha() <= 0f) {
                tone?.darkText = null
            }
            delay(if (levelSet) LumaMillis else FirstSampleMillis)
        }
    }

    /**
     * 文字颜色：这一档给了 [MochiScrollEdgeLevel.darkText] 就照它（跟着遮罩换色）；没给时按文字背后实际的亮度：
     * 模糊后的内容先盖一层页面底色（replay）、再盖一层黑（dim），都乘上整体不透明度。
     * 主题是深色（白字）而背后亮过 [LightTextUntil] 时换深色字；浅色主题（黑字）背后暗过 [DarkTextUntil] 时换浅色字。
     * 换回来要越过回差，滚过一小块亮 / 暗的内容不会来回闪
     */
    private fun updateTone(tone: MochiScrollEdgeTone, content: Color, level: MochiScrollEdgeLevel, isDark: Boolean) {
        val a = alpha().coerceIn(0f, 1f)
        // 这一档自己说了文字用什么色：跟着遮罩走（遮罩盖满一半以上才换，栏刚开始出现时还是主题色）
        if (level.darkText != null) {
            tone.darkText = if (a < 0.5f) null else level.darkText.takeIf { it != !isDark }
            return
        }
        fun lum(c: Color) = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
        val rp = (if (bottom) level.replayBottom else level.replay) * a
        val dm = level.dim * a
        val l = (lum(content) * (1f - rp) + lum(color) * rp) * (1f - dm)
        val current = tone.darkText
        tone.darkText = if (a < 0.05f) {
            null
        } else if (isDark) {
            val flip = if (current == true) l > LightTextUntil - ToneHysteresis else l > LightTextUntil
            if (flip) true else null
        } else {
            val flip = if (current == false) l < DarkTextUntil + ToneHysteresis else l < DarkTextUntil
            if (flip) false else null
        }
    }

    override fun ContentDrawScope.draw() {
        val edge = currentValueOf(LocalMochiMaterials).scrollEdge
        val a = alpha().coerceIn(0f, 1f)
        if (a > 0f) {
            if (edge == null) drawFade(a) else drawEdge(edge, a)
        }
        drawContent()
    }

    private fun ContentDrawScope.drawFade(a: Float) {
        val solid = color.copy(alpha = color.alpha * a)
        val clear = solid.copy(alpha = 0f)
        val e = extent.toPx()
        drawRect(solid, Offset.Zero, Size(size.width, size.height))
        if (!bottom) {
            drawRect(
                MochiSmoothGradient.vertical(solid, clear, startY = size.height, endY = size.height + e),
                Offset(0f, size.height),
                Size(size.width, e),
            )
        } else {
            drawRect(MochiSmoothGradient.vertical(clear, solid, startY = -e, endY = 0f), Offset(0f, -e), Size(size.width, e))
        }
    }

    private fun ContentDrawScope.drawEdge(edge: MochiScrollEdge, a: Float) {
        val side = if (bottom) edge.bottom else edge.top
        val sigma = side.softness.toPx().coerceAtLeast(0.5f)
        // 遮罩 50%（收紧前）那条线；朝内容那侧过了 MaskReach·sigma 收紧后就是 0
        val line = if (bottom) side.inset.toPx() else size.height - side.inset.toPx()
        regionTop = if (bottom) line - sigma * MaskReach else 0f
        regionBottom = if (bottom) size.height else line + sigma * MaskReach
        val h = regionBottom - regionTop
        if (h <= 0f || size.width <= 0f) return
        val dir = if (bottom) -1f else 1f
        // 两层颜色都按遮罩画：先页面底色、再黑色（iOS 里两层在同一个遮罩组里）
        val rp = replay.value * a
        val dm = dim.value * a
        if (rp > 0.001f) drawRect(maskBrush(color, rp, line, sigma, dir), Offset(0f, regionTop), Size(size.width, h))
        if (dm > 0.001f) drawRect(maskBrush(Color.Black, dm, line, sigma, dir), Offset(0f, regionTop), Size(size.width, h))
    }

    private fun maskBrush(c: Color, amount: Float, line: Float, sigma: Float, dir: Float): Brush {
        val stops = Array(MaskStops) { i ->
            val t = i / (MaskStops - 1f)
            val y = regionTop + (regionBottom - regionTop) * t
            t to c.copy(alpha = c.alpha * amount * edgeMask(dir * (line - y) / sigma))
        }
        return Brush.verticalGradient(*stops, startY = regionTop, endY = regionBottom)
    }
}

/** 遮罩：阴影边（标准正态累积，用 logistic 近似）再按 1.25·a − 0.25 收紧；[z] 往栏里面为正 */
private fun edgeMask(z: Float): Float = (1.25f / (1f + exp(-1.702f * z)) - 0.25f).coerceIn(0f, 1f)

private const val LumaMillis = 250L

// 文字换色的亮度界限（sRGB 编码值的相对亮度近似）：白字在 0.6 以上的背景上对比度已经低于 3:1
private const val LightTextUntil = 0.6f
private const val DarkTextUntil = 0.25f
private const val ToneHysteresis = 0.08f
private const val ToneMillis = 300
private const val FirstSampleMillis = 32L
private val SampleHeight = 16.dp
private const val MaskStops = 24

/** 收紧前 0.2 以下收紧后就是 0：logistic 近似下 z ≈ −0.82 */
private const val MaskReach = 0.85f
private val LevelSpring = spring<Float>(dampingRatio = 1f, stiffness = (2f * PI.toFloat() / 0.3f).let { it * it })

/**
 * 滚动边缘渐隐该有多浓：内容停在顶部时是 0（栏下面没有内容，渐隐不出现，不会盖住第一行内容和栏上的按钮），
 * 往上滚进栏下面 [extent] 这么远时到 1。iOS 26 的滚动边缘效果也只在内容滚到栏下面时才出现。
 * 传给 [mochiScrollEdgeFade] 的 alpha 或 [MochiTitleBar] 的 backgroundProgress；在绘制阶段读，不重组。
 */
public object MochiScrollEdgeDefaults {
    /** 滚多远渐隐到满 */
    public val Extent: Dp = 16.dp

    /**
     * 栏上文字的颜色：跟着 [tone] 在 [default]（主题色）、深色字、浅色字之间 0.3s 过渡。
     * [secondary] 为 true 时是副标题那种次要文字（更淡一些）
     */
    @Composable
    public fun textColor(tone: MochiScrollEdgeTone, default: Color, secondary: Boolean = false): Color {
        val target = when (tone.darkText) {
            true -> Color.Black.copy(alpha = if (secondary) 0.6f else 0.88f)
            false -> Color.White.copy(alpha = if (secondary) 0.7f else 1f)
            null -> default
        }
        return animateColorAsState(target, tween(ToneMillis), label = "scrollEdgeText").value
    }

    @Composable
    public fun progress(state: ScrollState, extent: Dp = Extent): () -> Float {
        val px = with(LocalDensity.current) { extent.toPx() }
        return remember(state, px) { { smoothstep(0f, px, state.value.toFloat()) } }
    }

    @Composable
    public fun progress(state: LazyListState, extent: Dp = Extent): () -> Float {
        val px = with(LocalDensity.current) { extent.toPx() }
        return remember(state, px) {
            { if (state.firstVisibleItemIndex > 0) 1f else smoothstep(0f, px, state.firstVisibleItemScrollOffset.toFloat()) }
        }
    }

    @Composable
    public fun progress(state: LazyGridState, extent: Dp = Extent): () -> Float {
        val px = with(LocalDensity.current) { extent.toPx() }
        return remember(state, px) {
            { if (state.firstVisibleItemIndex > 0) 1f else smoothstep(0f, px, state.firstVisibleItemScrollOffset.toFloat()) }
        }
    }
}

/** 已并入通用渐变遮罩 [ui.feice.mochi.effect.mask.mochiScrollFadeMask]（同样的规则，曲线更平滑，也支持 Lazy 列表和左右两边） */
@Deprecated(
    "Moved to ui.feice.mochi.effect.mask.mochiScrollFadeMask",
    ReplaceWith("mochiScrollFadeMask(state, extent)", "ui.feice.mochi.effect.mask.mochiScrollFadeMask"),
)
public fun Modifier.mochiScrollFadeMask(state: ScrollState, extent: Dp = 12.dp): Modifier = maskScrollFade(state, extent)
