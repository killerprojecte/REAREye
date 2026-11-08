// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.elastic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.flow.first

/*
 * 形变（iOS 式弹性）：
 * - 橡皮筋：拖出边界时越远越拉不动（UIScrollView 公式），元素被拉过去并朝手指那侧拉长
 * - 跟手弹簧：每帧积分同一根弹簧，目标每帧都在变也连续跟手（不会像「目标一变就重启动画」那样卡住）
 * - 果冻：按速度沿运动方向拉长、横向收窄，回弹时速度反向就自然变成压扁
 */

// ───────────────────────── 橡皮筋 ─────────────────────────

/**
 * iOS UIScrollView 的橡皮筋公式：f(x) = (1 − 1 / (x·c / d + 1))·d，c = 0.55，d 取元素在这个轴上的尺寸。
 * 刚拉出去时几乎 1:1 跟手，越远越拉不动；再用 max 做软上限（f / (1 + f / max)，永远到不了 max）。
 * @param overshoot 超出边界的距离（带符号）
 */
public fun rubberBand(overshoot: Float, dimension: Float, max: Float): Float {
    if (max <= 0f || dimension <= 0f || overshoot == 0f) return 0f
    val f = (1f - 1f / (abs(overshoot) * 0.55f / dimension + 1f)) * dimension
    return sign(overshoot) * f / (1f + f / max)
}

/** 手指在 bounds 外时，元素该被拉过去多少（两个轴各自过橡皮筋）；手指在里面或没有手指时是 0 */
public fun rubberBandPull(pointer: Offset?, bounds: Rect, max: Float): Offset {
    if (pointer == null || bounds == Rect.Zero) return Offset.Zero
    val dx = when {
        pointer.x < bounds.left -> pointer.x - bounds.left
        pointer.x > bounds.right -> pointer.x - bounds.right
        else -> 0f
    }
    val dy = when {
        pointer.y < bounds.top -> pointer.y - bounds.top
        pointer.y > bounds.bottom -> pointer.y - bounds.bottom
        else -> 0f
    }
    return Offset(rubberBand(dx, bounds.width, max), rubberBand(dy, bounds.height, max))
}

/**
 * 被橡皮筋拉着的矩形：整体被拉过去 60%、朝拉的那侧再拉长 40%，另一轴轻微收窄（体积感）。
 */
public fun Rect.rubberStretched(pull: Offset): Rect {
    var l = left + pull.x * 0.6f
    var r = right + pull.x * 0.6f
    var t = top + pull.y * 0.6f
    var b = bottom + pull.y * 0.6f
    if (pull.x > 0f) r += pull.x * 0.4f else l += pull.x * 0.4f
    if (pull.y > 0f) b += pull.y * 0.4f else t += pull.y * 0.4f
    val nx = abs(pull.y) * 0.08f
    val ny = abs(pull.x) * 0.08f
    return Rect(l + nx, t + ny, r - nx, b - ny)
}

// ───────────────────────── 跟手弹簧 ─────────────────────────

/**
 * 二维的跟手弹簧。目标可以每帧变化：每帧用实际帧间隔积分（240Hz 子步长，大 dt 也稳定），速度连续。
 * @param response 响应时间（秒），越小越快
 * @param dampingRatio 阻尼比，1 = 不回弹，< 1 有回弹
 */
@Stable
public class MochiFollowSpring(public var response: Float, public var dampingRatio: Float) {
    public var value: Offset = Offset.Zero
        private set
    public var velocity: Offset = Offset.Zero
        private set
    private var lastNanos = 0L

    /** 直接跳到 v，速度清零；resetClock 时下一帧重新计时（隔了一段时间再开始跟时用） */
    public fun snapTo(v: Offset, resetClock: Boolean = true) {
        value = v
        velocity = Offset.Zero
        if (resetClock) lastNanos = 0L
    }

    /** 往 target 积分 dt 秒 */
    public fun step(target: Offset, dt: Float): Offset {
        val omega = (2f * PI.toFloat()) / response
        val zeta = dampingRatio
        var x = value
        var v = velocity
        val steps = (dt / (1f / 240f)).toInt().coerceAtLeast(1)
        val h = dt / steps
        repeat(steps) {
            val a = (target - x) * (omega * omega) - v * (2f * zeta * omega)
            v += a * h
            x += v * h
        }
        value = x
        velocity = v
        return x
    }

    /** 等下一帧，返回距上一帧的秒数（第一帧按 120Hz 算，最长按 20Hz 截断） */
    public suspend fun awaitFrameDelta(): Float = withFrameNanos { now ->
        val dt = if (lastNanos == 0L) 1f / 120f else ((now - lastNanos) / 1e9f).coerceIn(0f, 1f / 20f)
        lastNanos = now
        dt
    }

    /** 等下一帧并往 target 积分一帧 */
    public suspend fun stepFrame(target: Offset): Offset = step(target, awaitFrameDelta())
}

// ───────────────────────── 橡皮筋状态 ─────────────────────────

/**
 * 「手指拖出边界，元素被橡皮筋拉过去」的状态：拉的时候快而稳（响应 0.24s、阻尼 0.86），
 * 回到边界内 / 松手时带一点回弹（0.36s、0.7）。[pull] 交给 [Rect.rubberStretched] 或 [mochiRubberBand] 用。
 */
@Stable
public class MochiRubberBandState internal constructor(
    maxPull: Float,
    private val pointer: () -> Offset?,
    private val bounds: () -> Rect,
    private val adjust: (Offset) -> Offset,
) {
    /** 最多拉多远（px，软上限） */
    public var maxPull: Float by mutableStateOf(maxPull)

    /** 当前拉动量（px） */
    public var pull: Offset by mutableStateOf(Offset.Zero)
        private set

    /** 手指位置对应的目标拉动量 */
    public val target: Offset get() = adjust(rubberBandPull(pointer(), bounds(), maxPull))

    internal suspend fun run() {
        val spring = MochiFollowSpring(0.24f, 0.86f)
        while (true) {
            if (target == Offset.Zero && pull == Offset.Zero && spring.velocity == Offset.Zero) {
                // 静止：等手指再拖出去
                snapshotFlow { target }.first { it != Offset.Zero }
                spring.snapTo(Offset.Zero)
            }
            val dt = spring.awaitFrameDelta()
            val t = target
            val back = t == Offset.Zero
            spring.response = if (back) 0.36f else 0.24f
            spring.dampingRatio = if (back) 0.7f else 0.86f
            val x = spring.step(t, dt)
            if (back && (x - t).getDistance() < 0.3f && spring.velocity.getDistance() < 5f) {
                spring.snapTo(Offset.Zero, resetClock = false)
            }
            pull = spring.value
        }
    }
}

/**
 * 创建橡皮筋状态。pointer / bounds 用同一个坐标系（比如屏幕坐标），每帧读取；
 * adjust 可以再加工目标（比如往某个方向拉得少一点）。
 */
@Composable
public fun rememberMochiRubberBandState(
    pointer: () -> Offset?,
    bounds: () -> Rect,
    maxPull: Dp = 72.dp,
    adjust: (Offset) -> Offset = { it },
): MochiRubberBandState {
    val max = with(LocalDensity.current) { maxPull.toPx() }
    val state = remember { MochiRubberBandState(max, pointer, bounds, adjust) }
    state.maxPull = max
    LaunchedEffect(state) { state.run() }
    return state
}

/** 把元素按 [MochiRubberBandState.pull] 拉过去并拉长（见 [Rect.rubberStretched]） */
public fun Modifier.mochiRubberBand(state: MochiRubberBandState): Modifier = graphicsLayer {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@graphicsLayer
    val r = Rect(0f, 0f, w, h).rubberStretched(state.pull)
    transformOrigin = TransformOrigin(0f, 0f)
    translationX = r.left
    translationY = r.top
    scaleX = r.width / w
    scaleY = r.height / h
}

// ───────────────────────── 果冻 ─────────────────────────

/** 按速度（单位：每秒移动「自身尺寸」的倍数）算拉伸量：正 = 沿运动方向拉长，负 = 压扁，已限幅 */
public fun jellyStretch(velocity: Float): Float = (velocity * 0.045f).coerceIn(-0.10f, 0.16f)

/** 沿运动方向拉长 s 时，另一轴的缩放（体积近似守恒） */
public fun jellyCrossScale(stretch: Float): Float = 1f / (1f + stretch * 0.6f)

/**
 * 果冻形变：按 velocity 沿 vertical / 水平方向拉长、另一轴收窄。
 * @param velocity 每帧读取的速度（每秒移动自身尺寸的倍数）
 * @param gel 强度，0 = 关
 */
public fun Modifier.mochiJelly(
    velocity: () -> Float,
    gel: Float = 0.6f,
    vertical: Boolean = true,
    origin: TransformOrigin = TransformOrigin.Center,
): Modifier = graphicsLayer {
    val s = jellyStretch(velocity()) * gel
    transformOrigin = origin
    if (vertical) {
        scaleY = 1f + s
        scaleX = jellyCrossScale(s)
    } else {
        scaleX = 1f + s
        scaleY = jellyCrossScale(s)
    }
}
