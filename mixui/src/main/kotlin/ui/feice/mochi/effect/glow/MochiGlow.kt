// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glow

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.elastic.MochiFollowSpring
import ui.feice.mochi.effect.hdr.hdrRadialBrush
import ui.feice.mochi.effect.hdr.linearLong
import ui.feice.mochi.effect.hdr.linearRadialBrush
import ui.feice.mochi.gesture.awaitMochiPress
import ui.feice.mochi.gesture.isInScrollContainer
import ui.feice.mochi.theme.MochiHdr
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.hdrLift

/*
 * 按压高光（iOS 26 交互式玻璃的「按哪亮哪」）：
 * - 柔光：以手指为中心的一大片光，中间几乎一样亮、到半径附近才平滑暗下去
 * - 亮边：沿形状内侧一圈细亮边，离手指越近越亮
 * - 光心不贴手指，用一根弹簧带阻尼地追过去（拖动有拖尾和惯性）
 * 状态（MochiGlowState）和画法分开：同一份状态既能画在普通 Compose 形状上（Modifier.mochiGlow），
 * 也能交给融球渲染器画在 shader 里（drawMochiMetaball 的 glow 参数），两边算法一致。
 */

/** 按压高光的外观与手感 */
@Immutable
public data class MochiGlowSpec(
    /** 光的颜色（带 alpha，alpha 就是最大亮度） */
    val color: Color,
    /** 光心追手指的弹簧：响应时间（秒） */
    val followResponse: Float = 0.32f,
    /** 光心追手指的弹簧：阻尼比（< 1 有一点回弹） */
    val followDamping: Float = 0.78f,
)

public object MochiGlowDefaults {
    /** 默认白光（取色的 plus 高光，见 drawGlowBody）：浅色主题亮一些，深色主题收着 */
    @Composable
    public fun spec(color: Color = Color.White.copy(alpha = if (MochiTheme.colorScheme.isDark) 0.22f else 0.45f)): MochiGlowSpec =
        remember(color) { MochiGlowSpec(color) }

    /**
     * 按大小算光半径：短边 × 1.2 + 长边 × 0.15，限制在 [MinRadius]..[MaxRadius]。
     * 主要看短边：又宽又矮的元素光不会铺满一大片；大面板封顶，不会整块发白。
     */
    public fun radius(size: Size, density: Density): Float = with(density) {
        val short = min(size.width, size.height)
        val long = max(size.width, size.height)
        (short * 1.2f + long * 0.15f).coerceIn(MinRadius.toPx(), MaxRadius.toPx())
    }

    public val MinRadius: Dp = 40.dp
    public val MaxRadius: Dp = 160.dp

    /** 按在元素上时的光半径 = 元素长边 × 这个倍数 */
    @Deprecated("光半径改为按大小计算", ReplaceWith("MochiGlowDefaults.radius(size, density)"))
    public const val ElementRadiusFactor: Float = 1.1f

    /** 大面积表面（面板、弹窗）上的光半径 */
    @Deprecated("光半径改为按大小计算", ReplaceWith("MochiGlowDefaults.radius(size, density)"))
    public val SurfaceRadius: Dp = 200.dp

    /** 大面积表面上的光强度（铺得大就收一点，不然一大片发白） */
    public const val SurfaceIntensity: Float = 0.85f
}

/**
 * 一处按压高光的状态。坐标系由使用方决定（局部坐标、窗口坐标、屏幕坐标都行），画的时候再换算。
 * 通常用 [rememberMochiGlowState] 创建；跨窗口共享触点时可以给 pointer，让光心追它。
 */
@Stable
public class MochiGlowState internal constructor(spec: MochiGlowSpec, private val pointer: (() -> Offset)?) {
    public var spec: MochiGlowSpec by mutableStateOf(spec)
        internal set

    private val intensityAnim = Animatable(0f)
    private val radiusAnim = Animatable(0f)
    private var targetState by mutableStateOf(Offset.Zero)

    /** 当前亮度 0..1 */
    public val intensity: Float get() = intensityAnim.value

    /** 当前光半径 */
    public val radius: Float get() = radiusAnim.value

    /** 光半径正在去的目标值 */
    public val radiusTarget: Float get() = radiusAnim.targetValue

    /** 光心（带阻尼地追着 [target]） */
    public var center: Offset by mutableStateOf(Offset.Zero)
        private set

    /** 手指是否还按着。只有按着时 [grow] 才生效，松手后的淡出不会被拉回来 */
    public var held: Boolean by mutableStateOf(false)
        private set

    /** 光心要追的点 */
    public val target: Offset get() = pointer?.invoke() ?: targetState

    /** 手指移动：光心带阻尼地追过去 */
    public fun moveTo(p: Offset) {
        targetState = p
    }

    /** 按下：光从 radius 的 35% 长到 radius，亮度到 intensity */
    public suspend fun press(at: Offset? = null, radius: Float, intensity: Float = 1f) {
        if (at != null) moveTo(at)
        held = true
        coroutineScope {
            radiusAnim.snapTo(radius * 0.35f)
            launch { radiusAnim.animateTo(radius, MochiMotions.responseSpring(0.6f, 1f)) }
            launch { intensityAnim.animateTo(intensity, MochiMotions.responseSpring(0.2f, 1f)) }
        }
    }

    /**
     * 按着的时候换到更大的元素上（比如从按钮滑进面板）：光平滑长大到 radius。
     * 没按着时不做任何事。
     */
    public suspend fun grow(radius: Float, intensity: Float) {
        if (!held) return
        coroutineScope {
            launch { radiusAnim.animateTo(max(radiusAnim.targetValue, radius), MochiMotions.responseSpring(0.5f, 1f)) }
            launch { intensityAnim.animateTo(intensity, MochiMotions.responseSpring(0.3f, 1f)) }
        }
    }

    /** 标记松手（不动画）。需要和别的动画一起启动时先调这个 */
    public fun markReleased() {
        held = false
    }

    /** 松手：光淡出 */
    public suspend fun release() {
        held = false
        intensityAnim.animateTo(0f, MochiMotions.responseSpring(0.5f, 1f))
    }

    /** 直接设定亮度、半径、光心（测试 / 截图用） */
    internal suspend fun snapTo(intensity: Float, radius: Float, center: Offset) {
        intensityAnim.snapTo(intensity)
        radiusAnim.snapTo(radius)
        this.center = center
    }

    /** 光心的阻尼跟随。按下时光心直接放在手指处；每帧积分同一根弹簧，手指一直动也连续 */
    internal suspend fun runFollow() {
        val spring = MochiFollowSpring(spec.followResponse, spec.followDamping)
        while (true) {
            if (intensityAnim.value <= 0.001f) {
                snapshotFlow { intensityAnim.value }.first { it > 0.001f }
                spring.snapTo(target)
                center = target
            }
            spring.response = spec.followResponse
            spring.dampingRatio = spec.followDamping
            center = spring.stepFrame(target)
        }
    }
}

/**
 * 创建并记住一处按压高光。
 * pointer：可选的光心目标（比如几个窗口共享的屏幕坐标触点）；不给就用 [MochiGlowState.moveTo]。
 */
@Composable
public fun rememberMochiGlowState(spec: MochiGlowSpec = MochiGlowDefaults.spec(), pointer: (() -> Offset)? = null): MochiGlowState {
    val state = remember { MochiGlowState(spec, pointer) }
    state.spec = spec
    LaunchedEffect(state) { state.runFollow() }
    return state
}

/**
 * 在 shape 上画按压高光（柔光 + 内侧亮边，滤色叠加）。只负责画，按压由使用方驱动 [MochiGlowState]。
 * origin：这个元素左上角在 state 坐标系里的位置（state 用局部坐标时保持默认）。
 */
public fun Modifier.mochiGlow(state: MochiGlowState, shape: Shape, origin: () -> Offset = { Offset.Zero }): Modifier = this
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        onDrawBehind { drawGlow(state, outline, state.center - origin()) }
    }

/**
 * 按压高光 + 自带手势：按下亮、拖动光跟着走、松手淡出。不消费触摸事件，可以和 clickable 等叠用。
 * 光半径默认是元素长边 × [MochiGlowDefaults.ElementRadiusFactor]。
 */
public fun Modifier.mochiPressGlow(state: MochiGlowState, shape: Shape, radius: Dp? = null): Modifier = this
    .then(PressGlowElement(state, radius))
    .mochiGlow(state, shape)

private data class PressGlowElement(val state: MochiGlowState, val radius: Dp?) : ModifierNodeElement<PressGlowNode>() {
    override fun create(): PressGlowNode = PressGlowNode(state, radius)

    override fun update(node: PressGlowNode) {
        if (node.state != state || node.radius != radius) {
            node.state = state
            node.radius = radius
            node.pointer.resetPointerInputHandler()
        }
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiPressGlow"
    }
}

/**
 * 按压高光的手势：和按压形变共用同一套触摸节奏（[awaitMochiPress]）——滚动容器里延迟亮起、滚动接手就淡出、
 * 快速轻点也补一次亮起再淡出，两者总是同时开始、同时结束
 */
private class PressGlowNode(var state: MochiGlowState, var radius: Dp?) : DelegatingNode() {
    private var release: Job? = null
    private var pressJob: Job? = null

    val pointer = delegate(
        SuspendingPointerInputModifierNode {
            val inScroll = isInScrollContainer()
            awaitEachGesture {
                val r = radius?.toPx() ?: MochiGlowDefaults.radius(size.toSize(), this)
                awaitMochiPress(
                    inScrollContainer = inScroll,
                    onPress = { p ->
                        release?.cancel()
                        pressJob?.cancel()
                        pressJob = coroutineScope.launch { state.press(p, r) }
                    },
                    onMove = { state.moveTo(it) },
                    onRelease = { hold ->
                        release?.cancel()
                        release = coroutineScope.launch {
                            if (hold > 0L) delay(hold)
                            pressJob?.cancel()
                            state.release()
                        }
                    },
                )
            }
        },
    )
}

/**
 * 普通 Compose 形状上的高光画法，和融球 shader 里的 glowOver 是同一条曲线：柔光 0.85·exp(−r³·1.6)（r = 距离 / 半径）。
 * 渐变按曲线密集取色标、一直延伸到几乎为 0 的地方，没有拐折和硬边。不画贴边的亮边：光只从手指处柔和地亮起、扩散。
 * @param base 底色。知道底色时 HDR 下手指附近再盖一层按原色提亮的光泽（[drawHdrSheen]）
 */
internal fun DrawScope.drawGlow(
    state: MochiGlowState,
    outline: Outline,
    center: Offset,
    intensityScale: Float = 1f,
    base: Color = Color.Unspecified,
) {
    val a = state.intensity * intensityScale
    if (a <= 0.01f) return
    val color = state.spec.color
    val gr = state.radius.coerceAtLeast(1f)
    drawGlowBody(outline, color, color.alpha * a, center, gr)
    if (base.isSpecified) {
        val path = (outline as? Outline.Generic)?.path ?: Path().apply { addOutline(outline) }
        drawHdrSheen(path, a, center, gr, base)
    }
}

/**
 * HDR 按压光泽：只在 [MochiHdr] 打开时画，强度只跟按压程度走（不乘高光颜色的 alpha，深色模式下一样亮）。
 * 手指附近**覆盖**一层按原色相提亮的底色（[hdrLift]，最亮处到峰值的 60%），不改色相、不发白。
 * 不用 Modulate 乘法：HDR 窗口的画布不是线性编码，乘法会把底色压暗，按下去反而变灰
 */
internal fun DrawScope.drawHdrSheen(path: Path, intensity: Float, center: Offset, radius: Float, base: Color) {
    val peak = MochiHdr.boost
    if (peak <= 1.001f || intensity <= 0.01f || Build.VERSION.SDK_INT < 29 || base.alpha <= 0.5f) return
    val k = intensity.coerceIn(0f, 1f)
    val n = SpecPos.size
    val lit = base.copy(alpha = 1f).hdrLift(1f + (peak - 1f) * 0.6f)
    val sheen = LongArray(n) { i ->
        val r = SpecPos[i] * SpecEnd
        val al = if (i == n - 1) 0f else (k * exp(-r * r * r * 1.6f)).coerceIn(0f, 1f)
        linearLong(lit.red, lit.green, lit.blue, al)
    }
    drawPath(path, linearRadialBrush(SpecPos, sheen, center, radius * SpecEnd))
}

private const val SpecEnd = 3f
private val SpecPos = FloatArray(13) { it / 12f }

/**
 * 柔光本体：相加（plus）一层光。不用颜色减淡：浅色底上很容易冲成一片白，浅色按钮上的字就看不清了
 */
internal fun DrawScope.drawGlowBody(outline: Outline, color: Color, alpha: Float, center: Offset, radius: Float) {
    drawOutline(outline, glowBrush(color, alpha * GlowPlusWeight, center, radius, BodyStops), blendMode = BlendMode.Plus)
}

/** 柔光本体相加的量 */
internal const val GlowPlusWeight = 0.28f

/** 把轮廓绕中心缩进 inset（按包围盒等比缩，1～2dp 的缩进里圆角的变化看不出来） */
internal fun insetPath(path: Path, b: Rect, inset: Float): Path {
    if (b.width <= 2f * inset || b.height <= 2f * inset) return path
    val m = android.graphics.Matrix()
    m.setScale((b.width - 2f * inset) / b.width, (b.height - 2f * inset) / b.height, b.center.x, b.center.y)
    val out = android.graphics.Path()
    path.asAndroidPath().transform(m, out)
    return out.asComposePath()
}

/** 按 shader 的曲线取的色标：(r, 亮度)，r 以光半径为单位，最后一个点亮度几乎为 0 */
private val BodyStops = FloatArray(12) { it * 0.16f }.let { rs -> rs to FloatArray(12) { 0.85f * exp(-rs[it] * rs[it] * rs[it] * 1.6f) } }

private fun glowBrush(
    color: Color,
    alpha: Float,
    center: Offset,
    radius: Float,
    stops: Pair<FloatArray, FloatArray>,
    boost: Float = 1f,
): Brush {
    val (rs, vs) = stops
    val end = rs.last()
    val pairs = Array(rs.size) { i ->
        val v = if (i == rs.lastIndex) 0f else vs[i]
        rs[i] / end to color.copy(alpha = (alpha * v).coerceIn(0f, 1f))
    }
    return hdrRadialBrush(pairs, center, radius * end, boost)
}
