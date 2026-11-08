// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ui.feice.mochi.effect.content.MochiCounterText
import ui.feice.mochi.shape.mochiConcentricOutline
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiIcon
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.rememberMochiHaptics

/**
 * 步进器（iOS 的 UIStepper，iOS 26 的胶囊样子）：左边是当前值，右边一对挨着的 − / + 胶囊按钮。
 *
 * - 点一下加减 [step]，每一下轻震；值用计数器样式滚动（变大往上、变小往下）
 * - 按住不放：0.5 秒后开始连续加减，越按越快（和 iOS 一样）
 * - 到了 [range] 的一端，对应的按钮变灰、停止连发并释放按压形变
 * - 两个按钮在一个按钮组里：按下的那个放大，和旁边那个像水滴一样连在一起
 *
 * @param showValue false 时只有两个按钮（值显示在别处）
 * @param format 值怎么显示
 */
@Composable
public fun MochiStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    range: IntRange = 0..Int.MAX_VALUE,
    step: Int = 1,
    showValue: Boolean = true,
    format: (Long) -> String = { it.toString() },
) {
    val current by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(onValueChange)
    val haptics = rememberMochiHaptics()
    fun bump(sign: Int): Boolean {
        val next = (current.toLong() + sign.toLong() * step).coerceIn(range.first.toLong(), range.last.toLong()).toInt()
        if (next == current) return false
        haptics.tick()
        onChange(next)
        return true
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showValue) {
            MochiCounterText(value.toLong(), style = MochiTheme.textStyles.body, format = format)
            Spacer(Modifier.width(12.dp))
        }
        // Measure the whole stepper as one centered unit; the group only publishes its outline
        // for the horizontal inset and must not move independently of the counter.
        MochiButtonGroup(modifier = Modifier.mochiConcentricOutline(), spacing = 2.dp) {
            StepperHalf(enabled && value > range.first, { bump(-1) }, plus = false)
            StepperHalf(enabled && value < range.last, { bump(1) }, plus = true)
        }
    }
}

@Composable
private fun StepperHalf(enabled: Boolean, bump: () -> Boolean, plus: Boolean) {
    val scope = rememberCoroutineScope()
    val currentBump by rememberUpdatedState(bump)
    // 按住连发时，松手那一下的 onClick 不再多加一次
    val held = remember { BooleanArray(1) }
    MochiButton(
        onClick = { if (!held[0]) currentBump() },
        modifier = Modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                // 只旁观、不消费：按钮自己的按压、放大、高光照常
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                held[0] = false
                val job = scope.launch {
                    delay(RepeatDelay)
                    held[0] = true
                    var interval = RepeatStart
                    while (currentBump()) {
                        delay(interval.toLong())
                        interval = max(RepeatMin, interval * 0.86f)
                    }
                }
                try {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        // 不抢按钮的点击；移动被滚动/拖动接管后也立即停止连发。
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (final.changes.firstOrNull { it.id == down.id }?.isConsumed != false) break
                    }
                } finally {
                    // 到达边界变灰会重启 pointerInput，不能把 composition scope 中的连发留在后台。
                    job.cancel()
                }
            }
        },
        enabled = enabled,
        style = MochiButtonStyle.Tonal,
        contentPadding = PaddingValues(0.dp),
        minHeight = 34.dp,
        behavior = MochiButtonDefaults.behavior(maxGrow = 3.dp),
    ) {
        val tint = LocalContentColor.current
        MochiIcon(if (plus) "control.plus" else "control.minus", Modifier.size(width = 46.dp, height = 34.dp), tint = tint) { slot ->
            Canvas(slot) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = 6.5.dp.toPx()
                val w = 2.dp.toPx()
                drawLine(tint, c - Offset(r, 0f), c + Offset(r, 0f), w, StrokeCap.Round)
                if (plus) drawLine(tint, c - Offset(0f, r), c + Offset(0f, r), w, StrokeCap.Round)
            }
        }
    }
}

/** 按住多久开始连发（ms） */
private const val RepeatDelay = 500L

/** 连发的起始间隔和最短间隔（ms），每一下间隔 × 0.86 */
private const val RepeatStart = 180f
private const val RepeatMin = 40f
