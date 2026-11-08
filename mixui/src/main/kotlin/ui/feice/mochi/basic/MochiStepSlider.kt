// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import ui.feice.mochi.basic.parts.ControlSmoothing
import ui.feice.mochi.effect.shadow.drawMochiShadow
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.rememberMochiHaptics

/** 分档滑条的配色 */
@Immutable
public data class MochiStepSliderColors(
    /** 外面的白色胶囊 */
    val container: Color,
    /** 从左边铺到拇指的那段 */
    val fill: Color,
    /** 拇指（外面一圈是 [fill]） */
    val thumb: Color,
    /** 还没到的刻度点 */
    val tick: Color,
    /** 已经铺过的刻度点（在 [fill] 上） */
    val tickOnFill: Color,
)

public object MochiStepSliderDefaults {
    /** 整条高度 */
    public val Height: Dp = 56.dp

    /** 外胶囊到里面那段的留白（四周一样，同心） */
    public val Padding: Dp = 8.dp

    /** 拇指外面那圈 [MochiStepSliderColors.fill] 的宽度 */
    public val ThumbRing: Dp = 4.dp

    @Composable
    public fun colors(
        container: Color = MochiDefaults.groupedContainer(),
        fill: Color = MochiTheme.colorScheme.primary,
        thumb: Color = Color.White,
        tick: Color = MochiTheme.colorScheme.onSurface.copy(alpha = 0.18f),
        tickOnFill: Color = Color.White.copy(alpha = 0.28f),
    ): MochiStepSliderColors = MochiStepSliderColors(container, fill, thumb, tick, tickOnFill)
}

/**
 * 分档滑条：几个离散档位之间选一个（推理强度、画面档位这类）。
 *
 * 一条加粗的胶囊：主色从左边铺到拇指，白色圆拇指嵌在主色那段的右端（外面一圈主色），每一档一个小圆点，
 * 铺过的点在主色上是淡白色，没到的是灰色；外胶囊、主色段、拇指三者同心。
 *
 * - 点哪里就去最近的那一档；按住拖动时拇指跟手，经过每一档轻震一下并立即换档（不等松手），松手弹簧吸附到最近的档（带一点回弹）；
 * - 外面改 [value] 时同样用弹簧滑过去；
 * - 按下时拇指轻轻放大。
 *
 * @param value 当前档位，0 until [count]
 * @param count 档位数（≥ 2）
 */
@Composable
public fun MochiStepSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    count: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MochiStepSliderColors = MochiStepSliderDefaults.colors(),
) {
    val n = count.coerceAtLeast(2)
    val haptics = rememberMochiHaptics()
    val scope = rememberCoroutineScope()
    val pos = remember { Animatable(value.coerceIn(0, n - 1).toFloat()) }
    val press = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        if (!dragging) pos.animateTo(value.coerceIn(0, n - 1).toFloat(), SnapSpring)
    }
    val containerPath = remember { G2PathCache() }
    val fillPath = remember { G2PathCache() }
    val dark = MochiTheme.colorScheme.isDark
    val shadowColor = MochiTheme.colorScheme.shadow
    val shadow = MochiTheme.shadows.low
    Box(
        modifier
            .fillMaxWidth()
            .height(MochiStepSliderDefaults.Height)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..(n - 1).toFloat(), n - 2)
                if (enabled) {
                    setProgress { target ->
                        change(target.roundToInt().coerceIn(0, n - 1))
                        true
                    }
                }
            }
            .then(
                if (!enabled) {
                    Modifier
                } else {
                    Modifier.pointerInput(n) {
                        val pad = MochiStepSliderDefaults.Padding.toPx()
                        fun fraction(x: Float): Float {
                            val h = size.height - pad * 2f
                            val travel = (size.width - pad * 2f - h).coerceAtLeast(1f)
                            return ((x - pad - h / 2f) / travel * (n - 1)).coerceIn(0f, (n - 1).toFloat())
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            dragging = true
                            scope.launch { press.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 700f)) }
                            var last = pos.value.roundToInt()
                            // 按下：直接滑到手指那里（不吸附），跟手开始
                            scope.launch { pos.animateTo(fraction(down.position.x), spring(dampingRatio = 1f, stiffness = 900f)) }
                            while (true) {
                                val e = awaitPointerEvent()
                                val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                val f = fraction(c.position.x)
                                scope.launch { pos.snapTo(f) }
                                val near = f.roundToInt()
                                if (near != last) {
                                    last = near
                                    haptics.tick()
                                    // 拖动中实时换档，不等松手
                                    if (near != current) change(near)
                                }
                                c.consume()
                            }
                            dragging = false
                            val target = pos.value.roundToInt().coerceIn(0, n - 1)
                            scope.launch { press.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                            scope.launch { pos.animateTo(target.toFloat(), SnapSpring) }
                            if (target != current) change(target)
                        }
                    }
                },
            )
            .drawBehind {
                val alpha = if (enabled) 1f else MochiDefaults.DisabledAlpha
                val pad = MochiStepSliderDefaults.Padding.toPx()
                val ring = MochiStepSliderDefaults.ThumbRing.toPx()
                val h = size.height - pad * 2f
                val travel = (size.width - pad * 2f - h).coerceAtLeast(1f)
                fun cx(i: Float) = pad + h / 2f + travel * i / (n - 1)
                // 外胶囊 + 一层很淡的柔和投影
                val outer = Rect(0f, 0f, size.width, size.height)
                val outerPath = containerPath.get(outer, size.height / 2f, ControlSmoothing)
                drawMochiShadow(outer, size.height / 2f, shadow.resolve(this, dark), shadowColor)
                drawPath(outerPath, colors.container, alpha = alpha)
                // 刻度点
                val dot = h * 0.11f
                val p = pos.value
                val thumbX = cx(p.coerceIn(0f, (n - 1).toFloat()))
                for (i in 0 until n) {
                    drawCircle(colors.tick, dot, Offset(cx(i.toFloat()), size.height / 2f), alpha = alpha)
                }
                // 主色段：从左端铺到拇指右边，拖出两端时不超出
                val fill = Rect(pad, pad, thumbX + h / 2f, pad + h)
                drawPath(fillPath.get(fill, h / 2f, ControlSmoothing), colors.fill, alpha = alpha)
                // 拇指
                val r = (h / 2f - ring) * (1f + 0.06f * press.value)
                // 铺过的点画在主色上（被拇指盖住的不画）
                for (i in 0 until n) {
                    val x = cx(i.toFloat())
                    if (x < thumbX - r - dot) drawCircle(colors.tickOnFill, dot, Offset(x, size.height / 2f), alpha = alpha)
                }
                drawCircle(colors.thumb, r, Offset(thumbX, size.height / 2f), alpha = alpha)
            },
    )
}

// 吸附到档：带一点回弹（约 4%）
private val SnapSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 420f, visibilityThreshold = 0.001f)
