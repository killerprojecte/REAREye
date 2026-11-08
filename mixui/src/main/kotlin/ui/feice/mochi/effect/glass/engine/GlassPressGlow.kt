// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.glass.engine

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 按下时手指下的光斑（iOS 的 _UIFlexInteractionLittleGlowView）：一个 93pt 的圆的阴影（半径 46.5），
 * 把下面的玻璃过一组提饱和矩阵后按这个阴影的形状混进来（提的是玻璃自己的颜色，不是叠白）。
 * 按下淡入；拖起来放大到 2 倍、亮度跟着减半；松手放大到 4 倍淡掉。标签栏和 [GlassBody] 共用
 */
@Stable
internal class GlassGlowState(private val scope: CoroutineScope) {
    private val glow = Animatable(0f, 0.0005f)
    private val size = Animatable(1f, 0.0005f)
    private val x = Animatable(0f, 0.05f)
    private val y = Animatable(0f, 0.05f)

    /** 强度 0..1、放大倍数、中心（玻璃里的坐标，px） */
    val progress: Float get() = glow.value
    val scale: Float get() = size.value
    val center: Offset get() = Offset(x.value, y.value)

    fun press(at: Offset) {
        scope.launch { x.snapTo(at.x) }
        scope.launch { y.snapTo(at.y) }
        scope.launch { size.snapTo(1f) }
        scope.launch { glow.animateTo(1f, glowSpring(0.2f, 0.82f)) }
    }

    fun drag(at: Offset) {
        scope.launch { x.animateTo(at.x, glowSpring(0.2f, 0.85f)) }
        scope.launch { y.animateTo(at.y, glowSpring(0.2f, 0.85f)) }
        if (size.targetValue < 2f) scope.launch { size.animateTo(2f, glowSpring(0.5f, 1f)) }
    }

    fun release() {
        scope.launch { glow.animateTo(0f, glowSpring(0.65f, 0.87f)) }
        scope.launch { size.animateTo(4f, glowSpring(0.63f, 0.9f)) }
    }
}

@Composable
internal fun rememberGlassGlowState(): GlassGlowState {
    val scope = rememberCoroutineScope()
    return remember(scope) { GlassGlowState(scope) }
}

/** 跟着 [interactionSource] 的按下 / 松手点亮光斑；按下位置取 [PressInteraction.Press.pressPosition] */
@Composable
internal fun rememberGlassGlow(interactionSource: InteractionSource?): GlassGlowState? {
    if (interactionSource == null) return null
    val state = rememberGlassGlowState()
    LaunchedEffect(interactionSource, state) {
        interactionSource.interactions.collect {
            when (it) {
                is PressInteraction.Press -> state.press(it.pressPosition)
                is PressInteraction.Release, is PressInteraction.Cancel -> state.release()
            }
        }
    }
    return state
}

/** iOS 的弹簧写法（response 秒、dampingRatio）换成 Compose 的 spring（质量 1） */
private fun glowSpring(response: Float, dampingRatio: Float): AnimationSpec<Float> {
    val w = 2f * PI.toFloat() / response
    return spring(dampingRatio = dampingRatio, stiffness = w * w, visibilityThreshold = 0.0005f)
}

/**
 * 光斑：93pt 圆的阴影（阴影半径 46.5，按 CALayer 的习惯当 sigma 的两倍），最高不透明度 0.2845（放大时按倍数变暗），
 * 背后的玻璃过的提饱和矩阵（浅色、深色两组，读自 iOS 的图层）
 */
internal const val GlowAlpha = 0.2845f
internal val GlowRadius = 46.5.dp
internal val GlowSigma = 23.25.dp
internal val GlowReach = GlowRadius + GlowSigma * 3
internal val GlowMatrixLight =
    listOf(2.534025f, -0.65037f, -0.065455f, 0f, -0.193275f, 2.077112f, -0.065637f, 0f, -0.193456f, -0.650006f, 2.661663f, 0f)
internal val GlowMatrixDark =
    listOf(9.29138f, -2.384679f, -0.240001f, 0f, -0.70867f, 7.616038f, -0.240668f, 0f, -0.709337f, -2.383345f, 9.759381f, 0f)

/** 模糊过的圆盘的径向透明度（按 [GlowReach] 归一化），用数值积分算出来：圆盘半径 a、高斯 sigma */
internal val GlowStops: Array<Pair<Float, Color>> = run {
    val a = GlowRadius.value.toDouble()
    val sg = GlowSigma.value.toDouble()
    val reach = GlowReach.value.toDouble()
    fun disk(r: Double): Double {
        // 二维：圆盘和高斯卷积在半径 r 处的值，对圆盘做极坐标数值积分
        var sum = 0.0
        val nr = 48
        val nt = 64
        for (i in 0 until nr) {
            val rho = (i + 0.5) / nr * a
            for (j in 0 until nt) {
                val th = (j + 0.5) / nt * 2.0 * Math.PI
                val dx = r - rho * Math.cos(th)
                val dy = rho * Math.sin(th)
                sum += Math.exp(-(dx * dx + dy * dy) / (2.0 * sg * sg)) * rho
            }
        }
        return sum * (a / nr) * (2.0 * Math.PI / nt) / (2.0 * Math.PI * sg * sg)
    }
    Array(17) { i ->
        val t = i / 16f
        t to Color.Black.copy(alpha = disk(t * reach).toFloat().coerceIn(0f, 1f))
    }
}
