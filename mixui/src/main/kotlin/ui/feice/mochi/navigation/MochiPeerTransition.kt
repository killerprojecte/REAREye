// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.anim.animateToSteady
import ui.feice.mochi.anim.awaitFrameSettle
import ui.feice.mochi.anim.rememberFramePeriodNanos
import ui.feice.mochi.effect.elastic.rubberBand
import ui.feice.mochi.shape.G2RectClipShape4
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.smoothstep

/** 同层级页面过渡的样子 */
public enum class MochiPeerTransitionStyle {
    /** 两页左右并排一起滑过去，途中缩小、中间一条窄缝 */
    Slide,

    /** 原地景深：前一页往后退（缩小）淡出，后一页从后面往前（放大到位）淡入；不横移、不模糊 */
    Depth,
}

/**
 * 同层级页面过渡的手感。
 * @param style 并排滑动还是原地景深淡入淡出
 * @param minScale [MochiPeerTransitionStyle.Slide]：途中两页缩到多小；[MochiPeerTransitionStyle.Depth]：退到最远时的大小
 * @param gap 两页之间那条缝的宽度（按缩小后的页面算，只用于 Slide）
 * @param shrinkDistance 离起点 / 终点多远（单位是「一页」）时缩到最小：小 = 一推就收小、快到位才放大（只用于 Slide）
 * @param spring 切换进度的弹簧
 */
@Immutable
public data class MochiPeerTransitionSpec(
    val minScale: Float = 0.92f,
    val gap: Dp = 8.dp,
    val shrinkDistance: Float = 0.35f,
    val spring: AnimationSpec<Float> = MochiMotions.responseSpring(0.5f, 0.88f),
    val style: MochiPeerTransitionStyle = MochiPeerTransitionStyle.Slide,
) {
    public companion object {
        /** 景深淡入淡出的默认手感：退到 0.94，临界阻尼的弹簧（不过冲，免得到位前页面又缩一下） */
        public val Depth: MochiPeerTransitionSpec = MochiPeerTransitionSpec(
            minScale = 0.94f,
            spring = MochiMotions.responseSpring(0.42f, 1f),
            style = MochiPeerTransitionStyle.Depth,
        )
    }
}

/**
 * 同层级页面之间的过渡（比如底部标签栏切换的几页）：两页左右并排、中间只隔一条窄缝，一起往切换方向的反方向滑；
 * 途中两页都缩小一点、四角圆起来，快到位时新页放大铺满。没有谁盖住谁。往回切时方向反过来。
 *
 * - 所有页排在一条「轨道」上，横移由一根弹簧驱动（非线性：开头推得快，到位前慢下来）；
 *   连续切换时轨道从当前位置接着走，不跳回起点。切到还在场的页，轨道直接往它那里走；
 *   切到新的页，它接在轨道最外侧（切换方向那一侧），轨道一路滑过去
 * - 缩小程度只看「离起点、离终点哪个近」：一推就收小，快到位时放大回去；手指拖动和点击切换走同一条曲线
 * - 给了 [pages] 和 [onPageChange] 时可以左右滑跟手切换：页面跟着手指走，松手按位置和速度决定去旁边一页还是弹回；
 *   两端没有下一页时橡皮筋拉不动
 * - 每一页用 [rememberSaveableStateHolder] 保存状态，切走再切回来，页内状态还在；默认去过的页留在组合里（[retainPages]）
 * - 系统关了动画时瞬间切换
 * - [MochiPeerTransitionStyle.Depth]：不横移，前一页原地往后退、淡出，后一页从后面往前、淡入（左右滑同样跟手）
 * - 转场中每一页画进自己的离屏图层：只缩放 / 移动 / 淡化这张图，文字和路径不随每一帧的缩放重新光栅化；静止时不用离屏层
 *
 * @param targetState 当前显示哪一页
 * @param pages 所有页的顺序（左到右）；给了就按它判断方向，并允许左右滑切换
 * @param onPageChange 左右滑松手决定换页时调用（调用方更新 [targetState]）
 * @param direction 没给 [pages] 时：从 from 切到 to 新页在哪边，正数 = 右边，负数 = 左边
 * @param background 每一页垫的底色（页面本身透明时也看得出一张张的页面）
 * @param backdrop 两页之间那条缝和缩小后四周露出来的底色
 * @param retainPages 切走的页留在组合里（不摆放、不绘制），切回来不用重新组合整页，切换的第一帧不卡；
 *   代价是这些页的副作用（LaunchedEffect 等）一直在跑。false = 切走就离开组合，状态由 [rememberSaveableStateHolder] 保存
 */
@Composable
public fun <T : Any> MochiPeerTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    pages: List<T>? = null,
    onPageChange: (T) -> Unit = {},
    direction: (from: T, to: T) -> Int = { from, to -> if (pages != null) pages.indexOf(to).compareTo(pages.indexOf(from)) else 1 },
    background: Color = MochiDefaults.groupedBackground(),
    backdrop: Color = MochiTheme.colorScheme.surfaceContainerHighest,
    spec: MochiPeerTransitionSpec = MochiPeerTransitionSpec(),
    retainPages: Boolean = true,
    content: @Composable (T) -> Unit,
) {
    // 去过的页（retainPages 时切走后也留在组合里）
    val retained = remember { LinkedHashSet<T>() }
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val corners = rememberMochiScreenCorners()
    val framePeriod = rememberFramePeriodNanos()
    val currentDirection by rememberUpdatedState(direction)
    val currentPages by rememberUpdatedState(pages)
    val currentOnPageChange by rememberUpdatedState(onPageChange)
    val currentTarget by rememberUpdatedState(targetState)
    val depth = spec.style == MochiPeerTransitionStyle.Depth
    // 轨道上的各页（slot = 在轨道上的位置，单位是「一页」）；camera = 现在看着轨道的哪个位置
    val layers = remember { mutableStateListOf(PeerLayer(targetState, 0)) }
    val camera = remember { Animatable(0f) }
    // 缩小程度按「离起点、离终点哪个近」算：origin = 这一段从哪出发，goal = 要去哪。
    // 两个都由这里显式维护（不读 Animatable.targetValue：松手、换目标的那一帧它还没更新，缩放会跳一下）
    var origin by remember { mutableFloatStateOf(0f) }
    var goal by remember { mutableFloatStateOf(0f) }
    // 松手换页时带过去的速度（页 / 秒），换页的弹簧接着它走
    var handoffVelocity by remember { mutableFloatStateOf(0f) }
    // 镜头此刻的速度（动画按帧推进、每帧 snapTo，Animatable.velocity 读不到）
    var cameraVelocity by remember { mutableFloatStateOf(0f) }
    // 等新页画好的那几帧：它用几乎看不见的透明度先真正画一次（透明度 0 时渲染线程直接跳过不画），
    // 第一次绘制（文字、离屏层、着色器）的开销落在动画开始之前
    var warming by remember { mutableStateOf<Any?>(null) }

    // 弹簧按帧推进：某一帧卡住时只是慢一点，不会一下跳过半段（连跳几下才到目标页）
    suspend fun glide(to: Float, v: Float) {
        camera.animateToSteady(to, spec.spring, v, framePeriod) { cameraVelocity = it }
    }

    // 换一个要去的位置：起点挪到让缩小程度保持不变的地方，所以中途换目标时缩放是连续的
    fun retarget(newGoal: Float) {
        val c = camera.value
        val d = min(abs(c - origin), abs(c - goal))
        val dir = if (newGoal >= c) 1f else -1f
        origin = c - dir * d
        goal = newGoal
    }

    fun settle(target: PeerLayer<T>) {
        // 到位：只留下目标页（被切走的页离开组合，状态由 holder 保存）。轨道不归零：
        // 归零要同时改页面位置和镜头，两步之间只要画一帧就会闪
        layers.removeAll { it !== target }
        origin = target.slot.toFloat()
        goal = origin
    }

    // 景深样式没有轨道：每一页各自一条显隐进度（vis），目标页走向 1、其余走向 0。
    // 不管之前留下了哪些页（比如手指横向带了一下、旁边那页还在弹回去），过渡都只在「现在看得见的」和目标页之间进行，
    // 不会像轨道那样一路经过中间的页。中途掉头时各页从当前进度接着走
    suspend fun depthTo(target: PeerLayer<T>, handoff: Float, warm: Boolean = false) {
        coroutineScope {
            fun go(l: PeerLayer<T>) {
                val to = if (l === target) 1f else 0f
                // 松手换页：目标页带着手指的速度显出，其余同速隐去；否则各自接着当前速度
                val v0 = if (handoff != 0f) (if (l === target) handoff else -handoff) else l.velocity
                if (l.vis.value != to) {
                    launch { l.vis.animateToSteady(to, spec.spring, v0, framePeriod) { l.velocity = it } }
                }
            }
            for (l in layers.toList()) if (l !== target) go(l)
            if (warm) {
                // 新页先用几乎看不见的透明度真正画一次（第一次绘制的开销落在它显出之前），画好、帧率回稳再显出；
                // 其余的页这期间照常隐去
                warming = target.key
                try {
                    awaitFrameSettle(framePeriod)
                } finally {
                    warming = null
                }
            }
            go(target)
        }
        // 中途被别的动画接管（换了目标页、又开始拖）就不收拾，交给接管的那一次
        if (target.key == currentTarget && target.vis.value == 1f) layers.removeAll { it !== target }
    }

    LaunchedEffect(targetState) {
        if (depth) {
            val existing = layers.firstOrNull { it.key == targetState }
            val target = existing ?: PeerLayer(targetState, 0, visible = 0f).also { layers.add(it) }
            // 要显出的页画在最上面
            if (layers.last() !== target) {
                layers.remove(target)
                layers.add(target)
            }
            val fresh = existing == null && handoffVelocity == 0f
            // 原来静止（只有一页）：先等新页画好再一起开始，前一页不会先淡掉一截、露出底色
            if (fresh && layers.size == 2) {
                warming = targetState
                try {
                    awaitFrameSettle(framePeriod)
                } finally {
                    warming = null
                }
            }
            val v = handoffVelocity
            handoffVelocity = 0f
            // 切换途中又换了新页：其余的页照常隐去，新页一边预热
            depthTo(target, v, warm = fresh && layers.size > 2)
            return@LaunchedEffect
        }
        val existing = layers.firstOrNull { it.key == targetState }
        val target = existing ?: run {
            val front = layers.minBy { abs(it.slot - goal) }
            val dir = if (currentDirection(front.key, targetState) < 0) -1 else 1
            // 新页接在轨道最外侧：滑过去时只经过还在场的页，不会跳
            val slot = if (dir > 0) layers.maxOf { it.slot } + 1 else layers.minOf { it.slot } - 1
            PeerLayer(targetState, slot).also { layers.add(it) }
        }
        // 新进组合的页（点标签切过去，不是手指拖出来的）：先等它排版、画完、帧率回稳再滑，
        // 不然第一次切到某页时开头几帧被排版吞掉，画面一下跳到半路。轨道静止（原来只有一页）时才等，切换途中再点不等
        if (existing == null && layers.size == 2 && handoffVelocity == 0f) {
            warming = targetState
            try {
                awaitFrameSettle(framePeriod)
            } finally {
                warming = null
            }
        }
        // 速度接着走：松手换页带手指的速度，连续点击带当前动画的速度（清零就会顿一下）
        val v = if (handoffVelocity != 0f) handoffVelocity else cameraVelocity
        handoffVelocity = 0f
        if (abs(camera.value - target.slot) > 0.0005f || layers.size > 1) {
            retarget(target.slot.toFloat())
            glide(target.slot.toFloat(), v)
        }
        settle(target)
    }

    // 这一帧页面缩到多少：离起点、离终点哪个近按哪个算
    fun scale(): Float {
        val d = min(abs(camera.value - origin), abs(camera.value - goal))
        return 1f - (1f - spec.minScale) * smoothstep(0f, spec.shrinkDistance, d)
    }

    // 相邻两页中心距（px）
    fun pitch(width: Float, density: Float): Float = width * scale() + spec.gap.value * density

    // 相邻两页的距离（px）：景深样式不横移，一页的进度按整屏宽度算
    fun pitchOf(width: Float, density: Float): Float = if (depth) width else pitch(width, density)

    val swipe = if (pages == null) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var overSlop = 0f
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    change.consume()
                    overSlop = over
                } ?: return@awaitEachGesture
                // 只在静止时（只有一页）开始跟手；切换途中的拖动交给页面本身
                val all = currentPages ?: return@awaitEachGesture
                if (layers.size != 1) return@awaitEachGesture
                val current = layers.first()
                origin = current.slot.toFloat()
                val index = all.indexOf(current.key)
                val w = size.width.toFloat()
                scope.launch { camera.stop() }
                cameraVelocity = 0f
                val tracker = VelocityTracker()
                tracker.addPosition(drag.uptimeMillis, drag.position)
                var dx = overSlop
                var neighbor: PeerLayer<T>? = null
                fun follow() {
                    // 手指往左 = 去右边那一页
                    val dir = if (dx < 0f) 1 else -1
                    val next = all.getOrNull(index + dir)
                    if (next != null && neighbor?.key != next) {
                        neighbor?.let { layers.remove(it) }
                        neighbor = PeerLayer(next, current.slot + dir, visible = if (depth) 0f else 1f).also { layers.add(it) }
                    }
                    goal = (current.slot + if (next != null) dir else 0).toFloat()
                    val p = pitchOf(w, density)
                    val raw = -dx / p
                    val pos = if (next == null) rubberBand(raw, 1f, 0.12f) else raw.coerceIn(-1f, 1f)
                    if (depth) {
                        val n = neighbor
                        scope.launch { current.vis.snapTo(1f - abs(pos)) }
                        if (n != null) scope.launch { n.vis.snapTo(abs(pos)) }
                    } else {
                        scope.launch { camera.snapTo(current.slot + pos) }
                    }
                }
                follow()
                horizontalDrag(drag.id) { change ->
                    dx += change.positionChange().x
                    tracker.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                    follow()
                }
                // 松手：过了一半，或者朝那边甩得够快，就换到旁边一页；否则弹回
                val vx = tracker.calculateVelocity().x
                val v = -vx / pitchOf(w, density)
                val offset = if (depth) {
                    // 景深：拖了多少看旁边那页显出了多少，方向按手指
                    (neighbor?.vis?.value ?: (1f - current.vis.value)) * (if (dx < 0f) 1f else -1f)
                } else {
                    camera.value - current.slot
                }
                val n = neighbor
                val go = n != null && (abs(offset) > 0.5f || (abs(v) > FlingPagesPerSecond && sign(v) == sign(offset)))
                if (go && n != null) {
                    // goal 已经是旁边那一页，换页动画从这一帧的位置、缩放和速度接着走
                    // （景深：旁边那页显出的速度 = 朝它那边拖的速度）
                    handoffVelocity = if (depth) v * sign(offset) else v
                    currentOnPageChange(n.key)
                } else if (depth) {
                    scope.launch {
                        current.velocity = -v * sign(offset)
                        n?.velocity = v * sign(offset)
                        depthTo(current, 0f)
                    }
                } else {
                    retarget(current.slot.toFloat())
                    scope.launch {
                        glide(current.slot.toFloat(), v)
                        if (layers.firstOrNull { it.key == currentTarget } === current) settle(current)
                    }
                }
            }
        }
    }

    Box(
        modifier.then(swipe).drawBehind {
            if (depth) {
                if (layers.size > 1) drawRect(background)
            } else if (scale() < 0.999f) {
                drawRect(backdrop)
            }
        },
    ) {
        // 组合哪些页：在场的页，加上（retainPages 时）去过的页。去过但不在场的页留在组合里、不摆放也不绘制，
        // 切回来时不用重新组合、测量整页
        if (!retainPages) retained.retainAll(layers.map { it.key }.toSet())
        layers.forEach { retained.add(it.key) }
        val shown = retained.toList()
        Layout(
            content = {
                for (k in shown) {
                    key(k) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val layer = layers.firstOrNull { it.key == k } ?: return@graphicsLayer
                                    val moving = layers.size > 1 ||
                                        (if (depth) layer.vis.value < 0.9995f else abs(camera.value - layer.slot) > 0.0005f)
                                    // 转场中整页画进离屏图层：每帧只合成这张图，页面内容只在第一帧画一次
                                    compositingStrategy = if (moving) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                                    if (depth) {
                                        // 离这一页越远退得越远、越淡；进出用同一条曲线，中途掉头也是连续的
                                        val v = layer.vis.value.coerceIn(0f, 1f)
                                        val s = spec.minScale + (1f - spec.minScale) * v
                                        scaleX = s
                                        scaleY = s
                                        alpha = smoothstep(DepthFadeStart, 1f, v).coerceAtLeast(if (warming == k) WarmAlpha else 0f)
                                        translationX = 0f
                                        clip = false
                                        return@graphicsLayer
                                    }
                                    val s = scale()
                                    // 相邻两页中心距 = 缩小后的页宽 + 缝，所以缝宽始终是 gap
                                    translationX = (layer.slot - camera.value) * (size.width * s + spec.gap.toPx())
                                    scaleX = s
                                    scaleY = s
                                    val round = ((1f - s) / (1f - spec.minScale)).coerceIn(0f, 1f)
                                    if (round > 0.001f) {
                                        clip = true
                                        // 四角圆起来：缩到最小时是屏幕圆角（屏幕本身是直角时给一点圆角，看得出一张张的页面）
                                        val min = MinCorner.toPx()
                                        shape = G2RectClipShape4(
                                            Rect(Offset.Zero, size),
                                            corners.topLeft.coerceAtLeast(min) * round,
                                            corners.topRight.coerceAtLeast(min) * round,
                                            corners.bottomRight.coerceAtLeast(min) * round,
                                            corners.bottomLeft.coerceAtLeast(min) * round,
                                        )
                                    } else {
                                        clip = false
                                    }
                                }
                                .drawWithContent {
                                    drawRect(background)
                                    drawContent()
                                },
                        ) {
                            // 切走、留在组合里的页不算在场：它登记的遮挡物、来源、顶栏不参与导航转场
                            val outer = LocalMochiPeerPageShown.current
                            val shown = remember(outer) { { outer() && layers.any { it.key == k } } }
                            CompositionLocalProvider(LocalMochiPeerPageShown provides shown) {
                                holder.SaveableStateProvider(k) { content(k) }
                            }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                // 只摆放在场的页（按轨道上的先后，后加入的画在上面）；不摆放的页不绘制、不接收触摸
                for (layer in layers) {
                    val i = shown.indexOf(layer.key)
                    if (i >= 0) placeables[i].place(0, 0)
                }
            }
        }
    }
}

/**
 * 这一页此刻是否在场（在 [MochiPeerTransition] 里显示或正在过渡）。去过、切走但留在组合里的页是 false：
 * 页里登记给导航的东西（遮挡物、来源卡片、收起的栏、共享顶栏）这时不能参与转场，不然会画出别的标签页的栏
 */
internal val LocalMochiPeerPageShown = staticCompositionLocalOf<() -> Boolean> { AlwaysShown }

internal val AlwaysShown: () -> Boolean = { true }

private class PeerLayer<T>(val key: T, val slot: Int, visible: Float = 1f) {
    /** 景深样式：这一页显出了多少（0 = 退到最远、看不见，1 = 到位） */
    val vis = Animatable(visible)

    /** [vis] 此刻的速度（按帧推进的动画每帧 snapTo，Animatable 自己读不到） */
    var velocity = 0f
}

/** 屏幕本身是直角时，缩小的页面也给一点圆角 */
private val MinCorner = 24.dp

/** 景深样式：离目标页还剩多少（1 - 这个值）时开始淡入；中点附近两页都很淡，像 iOS 的淡入淡出切换 */
private const val DepthFadeStart = 0.35f

/** 新页预热时的透明度：渲染线程会真正画它，肉眼看不出来 */
private const val WarmAlpha = 1f / 255f

/** 甩得多快（页 / 秒）就算要换页，不用拖过一半 */
private const val FlingPagesPerSecond = 1.2f
