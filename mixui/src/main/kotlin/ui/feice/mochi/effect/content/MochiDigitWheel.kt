// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.content

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ui.feice.mochi.utils.smoothstep

/*
 * 计数器的一位数字：一个 0..9 循环的滚轮。数字变化时滚轮真的转过去（6 变 1 依次经过 5、4、3、2），
 * 画成一段圆弧，越快越小、越淡、越糊；高位出现时整格从下面升上来，消失时沉下去。
 *
 * 所有量都在绘制阶段读，动画期间不重组。单位「格」= 相邻两个数字的间距（[DigitGlyphs.pitch]）。
 *
 * 滚轮动画（弧面滚轮、按速度的变焦 / 淡化 / 模糊、反向冻结、按剩余距离现算的弹簧）由 Bear
 * （https://github.com/mif9499）设计实现，这里改成用主题字阶排版、模糊走 [mochiBlur]。
 */

/** 弯曲滚轮：纵向 1 格对应 45° 圆弧，只是绘制时的映射 */
private val WheelRadiansPerCell: Float = (PI / 4).toFloat()
private val WheelRadiusCells: Float = 1f / WheelRadiansPerCell
private val HalfPi: Float = (PI / 2).toFloat()

/** 格距相对数字墨迹高度：小于 1，轮盘上相邻的数字在纵向互相压住一点 */
private const val WheelPitchPerInk = 0.8f

/** 变焦：满速时整格缩到 1 − 0.4。字号和间距同一个比例，多出来的数字是整体缩小挤进来的，没有挤压感 */
private const val WheelZoomAmount = 0.4f

/** 下沉：离中心一格缩到 1 − 0.5（滚面的深度感），按 smoothstep 走，滚过中心时没有折角 */
private const val WheelSinkAmount = 0.5f

/** 按屏幕位置淡出：0.5 格以内不透明，0.85 格外看不见。静止时相邻数字在 0.95 格，所以只看到一行 */
private const val WheelFadeStartCells = 0.5f
private const val WheelFadeEndCells = 0.85f

/** |偏移| 小于它的字算「中心那个」，最后画、压在上面 */
private const val CenterLiftCells = 0.5f

/** 到淡出带终点就完全透明了：高速时按它算可见的格数，不遍历一圈画不出来的数字 */
private val VisibleTurnRadians: Float = asin((WheelFadeEndCells / WheelRadiusCells).coerceAtMost(1f))

private val TurnSides = intArrayOf(-1, 1)
private val CenterTurn = intArrayOf(0)

/** 快到这个速度（格/秒）模糊满档；模糊上限锁在一格过渡的程度（峰值约 7 格/秒），滚得再快也不更糊 */
private const val MotionBlurVelocity = 12f
private const val SingleStepPeakVelocity = 7f
private val MaxBlurFraction: Float = smoothstep(0f, MotionBlurVelocity, SingleStepPeakVelocity)

/** 变焦满速（格/秒）：一格一格地变几乎不缩，连加、大跨度才明显缩小 */
private const val ZoomFullVelocity = 30f

/** 满速时透明度再乘 1 − 0.45 */
private const val MotionFade = 0.45f

/** 退场的格子往下沉多远（格）：走满 2 格时无论停在哪个相位都已离开本格。进场从 +1 升到 0 */
private const val RetireShift = 2f

/** 位数变化时宽度的过渡：临界阻尼，干脆不弹 */
private val DigitSlideSpring = spring<Float>(dampingRatio = 1f, stiffness = 1200f, visibilityThreshold = 0.01f)

/** 一种字体字号下的 0–9：排好版的字形和量出来的墨迹 */
internal class DigitGlyphs(
    val layouts: Array<TextLayoutResult>,
    /** 一格的大小 = 一个数字（等宽数字）排出来的大小，和同一行 Text 里的数字完全一样 */
    val width: Int,
    val height: Int,
    /** 数字墨迹的中心离格子顶边多远（px），滚动、缩放都绕着它 */
    val inkCenter: Float,
    val inkHalf: Float,
    /** 相邻两个数字的间距（px） */
    val pitch: Float,
)

@Composable
internal fun rememberDigitGlyphs(style: TextStyle): DigitGlyphs {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    return remember(style, measurer) {
        val layouts = Array(10) { measurer.measure(it.toString(), style, maxLines = 1) }
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (l in layouts) {
            val b = l.getPathForRange(0, 1).getBounds()
            if (b.height > 0f) {
                top = minOf(top, b.top)
                bottom = maxOf(bottom, b.bottom)
            }
        }
        val height = layouts[0].size.height
        if (top > bottom) {
            top = height * 0.25f
            bottom = height * 0.75f
        }
        DigitGlyphs(
            layouts = layouts,
            width = layouts.maxOf { it.size.width },
            height = height,
            inkCenter = (top + bottom) / 2f,
            inkHalf = (bottom - top) / 2f,
            pitch = (bottom - top) * WheelPitchPerInk,
        )
    }
}

/** 滚轮位置 [index] 和这个位置上的数字；相邻位置差一个数字，数字按位置循环推出来 */
private class WheelTrack(var digit: Int) {
    var index = 0

    /**
     * 换成数字 [d]：[direction] > 0 往前滚（新数字从下面上来）、< 0 往回滚、0 走近的那边。
     * 同一个数字再推一次不动（组合阶段可能重复调用）
     */
    fun push(d: Int?, direction: Int): Int {
        if (d == null || d == digit) return index
        index += when {
            direction > 0 -> (d - digit + 10) % 10
            direction < 0 -> -((digit - d + 10) % 10)
            else -> ((d - digit + 15) % 10) - 5
        }
        digit = d
        return index
    }

    fun digitAt(turn: Int): Int = ((digit + turn - index) % 10 + 10) % 10
}

/**
 * 一位数字的滚轮。[digit] = null 时这一位正在消失（沉下去、宽度收回 0）；从 null 变回数字就从半路升回来。
 * [born] = 这一位是后来才出现的（从下面升上来、宽度从 0 长出来），否则一开始就停在位上
 */
@Composable
internal fun CounterWheel(digit: Int?, direction: Int, born: Boolean, glyphs: DigitGlyphs, color: Color) {
    val track = remember { WheelTrack(digit ?: 0) }
    val target = track.push(digit, direction)
    val absent = digit == null
    val roll = remember { Animatable(target.toFloat()) }
    val shift = remember { Animatable(if (born || absent) 1f else 0f) }
    val presence = remember { Animatable(if (born || absent) 0f else 1f) }
    // 这一格正从哪一侧接近目标（+1 从下往上）。越过目标点后按它剔掉目标背后的字；
    // 必须在组合阶段跟目标一起换，否则新目标的第一帧拿旧方向剔除，整格会空一帧
    val approachDir = remember { mutableIntStateOf(1) }
    val approachTarget = remember { mutableIntStateOf(target) }
    SideEffect {
        if (approachTarget.intValue != target) {
            approachTarget.intValue = target
            approachDir.intValue = if (target - roll.value >= 0f) 1 else -1
        }
    }
    val retiring by rememberUpdatedState(absent)
    val hold = rememberMotionHold(
        position = { roll.value + shift.value },
        speed = { abs(roll.velocity) + abs(shift.velocity) },
    )

    // 弹簧按「还剩几格 + 现在多快」现算：滚一格和滚九格回弹一样大，连按时动量接着走
    LaunchedEffectTarget(target.toFloat()) { t ->
        // 格子在视口外（新生 / 退场途中）：轮盘直接落到目标，免得升上来的时候当着人转
        if (abs(shift.value) > WheelFadeEndCells) roll.snapTo(t)
        // 新目标要倒着走：速度必然过零，先冻住当前的运动量，模糊不会在那一瞬间闪回清晰
        if ((t - roll.value) * roll.velocity < 0f) hold.freeze(abs(roll.velocity) + abs(shift.velocity))
        roll.animateTo(t, retargetSpring(t - roll.value, roll.velocity))
    }
    LaunchedEffectTarget(if (absent) RetireShift else 0f) { t ->
        if ((t - shift.value) * shift.velocity < 0f) hold.freeze(abs(roll.velocity) + abs(shift.velocity))
        shift.animateTo(t, retargetSpring(t - shift.value, shift.velocity))
    }
    LaunchedEffectTarget(if (absent) 0f else 1f) { presence.animateTo(it, DigitSlideSpring) }

    val density = LocalDensity.current
    val w = glyphs.width
    val h = glyphs.height
    val pitch = glyphs.pitch
    val maxBlur = w / 4f
    // 画的范围比格子大：上下各留出两段淡出带 + 半个字形 + 模糊外溢（模糊图层按这个大小建，超出的会被切掉）
    val reach = 2f * WheelFadeEndCells * pitch + glyphs.inkHalf + maxBlur
    val padY = ceil(maxOf(reach - glyphs.inkCenter, glyphs.inkCenter + reach - h, 0f)).toInt()
    val padX = ceil(maxBlur).toInt()

    // 效果用的运动量：滚轮转速 + 进出的位移速度，再和反向冻结值取大。读 value 让模糊半径每帧跟着重算
    fun speed(): Float {
        roll.value
        shift.value
        val v = abs(roll.velocity) + abs(shift.velocity)
        return if (v.isFinite()) maxOf(v, hold.speed) else hold.speed
    }

    Spacer(
        Modifier
            .layout { m, _ ->
                val p = m.measure(Constraints.fixed(w + padX * 2, h + padY * 2))
                val slot = (w * presence.value.coerceAtLeast(0f)).roundToInt()
                layout(slot, h) { p.place((slot - w) / 2 - padX, -padY) }
            }
            .mochiBlur(continuous = true) {
                val f = smoothstep(0f, MotionBlurVelocity, speed()).coerceAtMost(MaxBlurFraction)
                with(density) { (f * maxBlur).toDp() }
            }
            .drawBehind {
                val shiftCells = shift.value
                // 整格都在淡出带外（新生刚起步 / 退场快沉完）：这一帧什么都不用画
                if (abs(shiftCells) >= WheelFadeEndCells) return@drawBehind
                val position = roll.value
                val s = speed()
                val zoom = 1f - WheelZoomAmount * (s / ZoomFullVelocity).coerceIn(0f, 1f)
                val alphaScale = 1f - MotionFade * smoothstep(0f, MotionBlurVelocity, s)
                val shiftFade = 1f - smoothstep(WheelFadeStartCells, WheelFadeEndCells, abs(shiftCells))
                val cx = padX + w / 2f
                val cy = padY + glyphs.inkCenter
                val travel = approachDir.intValue
                val passedTarget = (position - target) * travel > 0
                val base = floor(position).toInt()
                val turns = ceil(VisibleTurnRadians / (WheelRadiansPerCell * zoom)).toInt().coerceIn(1, 8)
                // 两趟：先画靠外的，再画中心那个，重叠时中间的压在上面
                for (pass in 0..1) {
                    for (d in turns downTo 0) {
                        for (side in if (d == 0) CenterTurn else TurnSides) {
                            val turn = base + side * d
                            // 越过目标点之后，目标那一侧之外的字不画：不管弹多远，背后的数字都露不出来
                            if (passedTarget && !retiring && (turn - target) * travel > 0) continue
                            val u = turn - position
                            val angle = u * WheelRadiansPerCell * zoom
                            if (angle <= -HalfPi || angle >= HalfPi) continue
                            val arc = WheelRadiusCells * sin(angle)
                            if (abs(arc) >= WheelFadeEndCells) continue
                            val screen = arc + shiftCells
                            if ((abs(screen) < CenterLiftCells) != (pass == 1)) continue
                            val rollFade = 1f - smoothstep(WheelFadeStartCells, WheelFadeEndCells, abs(arc))
                            val alpha = rollFade * shiftFade * alphaScale
                            if (alpha <= 0.01f) continue
                            val sink = (1f - WheelSinkAmount * smoothstep(0f, 1f, abs(u))).coerceAtLeast(0.1f)
                            withTransform({
                                translate(cx, cy + screen * pitch)
                                scale(zoom * sink, zoom * sink * cos(angle), pivot = Offset.Zero)
                            }) {
                                drawText(
                                    glyphs.layouts[track.digitAt(turn)],
                                    color = color,
                                    topLeft = Offset(-w / 2f, -glyphs.inkCenter),
                                    alpha = alpha,
                                )
                            }
                        }
                    }
                }
            },
    )
}

/*
 * —— 弹簧：重定向时按「还剩几格 + 现在多快」现算 ——
 * 过冲锁死在 OvershootCells（和离多近、冲多快无关）；峰值速度按 7.05·距离^0.75 走，各距离手感接近、长距离不拖；
 * 带进来的初速已经比这个快时不再加速，而是加刚度刹车。最后用真实初速反解阻尼，让带初速的过冲也落在 OvershootCells。
 * 刻意不锁到位时间：远的格子走得久一点，各位错开落定，比齐刷刷同时收住自然。
 */

/** 落地时的过冲（格）：要小于 1 − 淡出带，背后的数字才永远露不出来 */
private const val OvershootCells = 0.0948f
private const val PeakVelocityAnchor = 7.05f
private const val PeakVelocityExponent = 0.75f
private const val MinDistance = 0.35f
private const val MinDamping = 0.40f
private const val MaxDamping = 0.93f
private const val MinStiffness = 8f
private const val MaxStiffness = 8000f
private const val SolverSteps = 32
private const val VisibilityThreshold = 0.002f
private val TwoPi = (2.0 * PI).toFloat()

/** [remaining] = 目标 − 当前位置（格，有符号），[velocity] = 当前速度（格/秒）。都要在 animateTo 之前读 */
private fun retargetSpring(remaining: Float, velocity: Float): SpringSpec<Float> {
    val distance = abs(remaining).coerceAtLeast(MinDistance)
    val toward = if (remaining >= 0f) velocity else -velocity
    val targetPeak = PeakVelocityAnchor * distance.pow(PeakVelocityExponent)
    val damping = dampingForDistance(distance)
    val base = stiffnessForPeak(distance, damping, targetPeak)
    val carry = (toward / targetPeak).coerceAtLeast(0f)
    val stiffness = if (carry > 1f) (base * carry * carry).coerceAtMost(MaxStiffness) else base
    return spring(
        dampingRatio = dampingForOvershoot(distance, toward, stiffness),
        stiffness = stiffness,
        visibilityThreshold = VisibilityThreshold,
    )
}

/** x(t) = R·e^(−ζωt)·cos(ωd·t − φ) 里的相位量 */
private class Phase(val omega: Float, val omegaD: Float, val radius: Float, val phi: Float)

private fun phase(distance: Float, damping: Float, stiffness: Float, velocity: Float): Phase {
    val omega = sqrt(stiffness)
    val omegaD = omega * sqrt((1f - damping * damping).coerceAtLeast(1e-4f))
    val x0 = -distance
    val b = (velocity + damping * omega * x0) / omegaD
    var phi = atan2(b, x0)
    if (phi > 0f) phi -= TwoPi
    return Phase(omega, omegaD, sqrt(x0 * x0 + b * b), phi)
}

/** 静止起步：过冲率只由阻尼定，闭式反解让它正好等于 OvershootCells / 距离 */
private fun dampingForDistance(distance: Float): Float {
    val c = ln(distance / OvershootCells) / PI.toFloat()
    return (c / sqrt(1f + c * c)).coerceIn(MinDamping, 0.92f)
}

private fun peakSpeedFromRest(distance: Float, damping: Float, stiffness: Float): Float {
    val p = phase(distance, damping, stiffness, 0f)
    val decay = damping * p.omega
    val amplitude = p.radius * p.omega * sqrt((1f - damping * damping).coerceAtLeast(0f))
    val psi = atan2(p.omegaD, decay)
    val turn = -atan(decay / p.omegaD)
    var best = 0f
    for (m in 0..5) {
        val t = (p.phi + psi + turn + m * PI.toFloat()) / p.omegaD
        if (t < 0f) continue
        val speed = amplitude * exp(-decay * t)
        if (speed > best) best = speed
    }
    return best
}

private fun stiffnessForPeak(distance: Float, damping: Float, target: Float): Float {
    var lo = MinStiffness
    var hi = MaxStiffness
    repeat(SolverSteps) {
        val mid = sqrt(lo * hi)
        if (peakSpeedFromRest(distance, damping, mid) < target) lo = mid else hi = mid
    }
    return sqrt(lo * hi)
}

private fun overshoot(distance: Float, velocity: Float, damping: Float, stiffness: Float): Float {
    val p = phase(distance, damping, stiffness, velocity)
    val decay = damping * p.omega
    val amplitude = p.radius * sqrt((1f - damping * damping).coerceAtLeast(0f))
    val turn = -atan(decay / p.omegaD)
    val period = TwoPi / p.omegaD
    var best = 0f
    for (m in 0..5) {
        var t = (turn + p.phi + m * TwoPi) / p.omegaD
        while (t <= 0f) t += period
        val x = amplitude * exp(-decay * t)
        if (x > best) best = x
    }
    return best
}

private fun dampingForOvershoot(distance: Float, velocity: Float, stiffness: Float): Float {
    var lo = 0.02f
    var hi = 0.99f
    repeat(SolverSteps) {
        val mid = (lo + hi) / 2f
        if (overshoot(distance, velocity, mid, stiffness) > OvershootCells) lo = mid else hi = mid
    }
    return ((lo + hi) / 2f).coerceIn(MinDamping, MaxDamping)
}

/*
 * —— 反向冻结 ——
 * 换目标要求轮盘倒着走时，速度会先减到 0 再反向加速，效果直接读速度会在那一瞬间闪回清晰。
 * 冻住当时的运动量，等反过来的速度追上它再放开；正常减速到停、弹簧自带的过冲都不冻结。
 */

/** 反向速度一直追不上（目标很近）时靠它放掉（秒） */
private const val ReleaseSeconds = 0.2f
private const val IdleSpeed = 0.5f
private const val MaxFrameSeconds = 0.05f

private class MotionHold {
    var speed by mutableFloatStateOf(0f)
        private set

    fun freeze(atSpeed: Float) {
        if (atSpeed > speed) speed = atSpeed
    }

    fun clear() {
        speed = 0f
    }

    fun decay(seconds: Float) {
        speed *= exp(-seconds / ReleaseSeconds)
    }
}

/** 位置第一次变化才开始逐帧推进，停住且冻结值放完就停，不动时不空转 */
@Composable
private fun rememberMotionHold(position: () -> Float, speed: () -> Float): MotionHold {
    val hold = remember { MotionHold() }
    val latestPosition by rememberUpdatedState(position)
    val latestSpeed by rememberUpdatedState(speed)
    LaunchedEffect(hold) {
        while (true) {
            snapshotFlow { latestPosition() }.drop(1).first()
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                val seconds = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(MaxFrameSeconds)
                last = now
                val current = latestSpeed().let { if (it.isFinite()) it else 0f }
                if (current >= hold.speed) hold.clear() else hold.decay(seconds)
                if (current <= IdleSpeed && hold.speed <= IdleSpeed) {
                    hold.clear()
                    break
                }
            }
        }
    }
    return hold
}

/**
 * 目标变了只把新目标交给 [onTarget]，不重启协程：重启会先取消上一次 animateTo，取消时速度被清零；
 * 在上一次还在跑时发起新的一次，新动画在调用点读到的是活的速度，连按的动量才接得上
 */
@Composable
private fun LaunchedEffectTarget(target: Float, onTarget: suspend (Float) -> Unit) {
    val latestTarget by rememberUpdatedState(target)
    val latestOnTarget by rememberUpdatedState(onTarget)
    LaunchedEffect(Unit) {
        val scope = CoroutineScope(coroutineContext)
        snapshotFlow { latestTarget }.collect { value -> scope.launch { latestOnTarget(value) } }
    }
}
