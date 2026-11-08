// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.content.MochiCounterText
import ui.feice.mochi.shape.drawG2RoundRect
import ui.feice.mochi.theme.MochiTheme

/** 仪表的默认值 */
public object MochiGaugeDefaults {
    /** 圆形仪表的开口：弧占 270° */
    public const val Sweep: Float = 270f

    /** 刻度数（含两端） */
    public const val Ticks: Int = 11
}

/**
 * 圆形仪表（iOS 的 Gauge，.accessoryCircular 一类）：开口朝下的弧（[MochiProgressRing] 的 sweep 版），
 * 弧内侧一圈刻度，中间是数值（变化时按计数器滚动）和单位，开口两边是最小 / 最大值。
 *
 * @param value 当前值（在 [range] 里）
 * @param format 中间显示的整数，默认四舍五入
 */
@Composable
public fun MochiGauge(
    value: Float,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    size: Dp = 132.dp,
    strokeWidth: Dp = 10.dp,
    label: String? = null,
    minLabel: String? = null,
    maxLabel: String? = null,
    color: Color = MochiTheme.colorScheme.primary,
    format: (Float) -> Long = { it.roundToLong() },
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val tickColor = MochiTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        MochiProgressRing(
            fraction,
            Modifier.size(size),
            size = size,
            strokeWidth = strokeWidth,
            color = color,
            sweep = MochiGaugeDefaults.Sweep,
        )
        Canvas(Modifier.size(size)) {
            val sw = strokeWidth.toPx()
            val r = this.size.minDimension / 2f - sw - 6.dp.toPx()
            val start = 90f + (360f - MochiGaugeDefaults.Sweep) / 2f
            for (i in 0 until MochiGaugeDefaults.Ticks) {
                val a = Math.toRadians((start + MochiGaugeDefaults.Sweep * i / (MochiGaugeDefaults.Ticks - 1)).toDouble())
                val major = i % 5 == 0
                val len = if (major) 6.dp.toPx() else 3.dp.toPx()
                val c = center
                val o = Offset(c.x + (r * cos(a)).toFloat(), c.y + (r * sin(a)).toFloat())
                val inner = Offset(c.x + ((r - len) * cos(a)).toFloat(), c.y + ((r - len) * sin(a)).toFloat())
                drawLine(tickColor, inner, o, if (major) 1.6.dp.toPx() else 1.dp.toPx(), StrokeCap.Round)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MochiCounterText(
                format(value),
                style = MochiTheme.textStyles.body.copy(fontSize = (size.value * 0.24f).sp, fontWeight = FontWeight.SemiBold),
                color = MochiTheme.colorScheme.onSurface,
            )
            if (label !=
                null
            ) {
                Text(label, style = MochiTheme.textStyles.footnote, color = MochiTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (minLabel != null || maxLabel != null) {
            Row(Modifier.align(Alignment.BottomCenter).width(size * 0.62f)) {
                if (minLabel !=
                    null
                ) {
                    Text(minLabel, style = MochiTheme.textStyles.footnote, color = MochiTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (maxLabel !=
                    null
                ) {
                    Text(maxLabel, style = MochiTheme.textStyles.footnote, color = MochiTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * 线性仪表（iOS 的 Gauge .linearCapacity）：上面一行是标题和当前值，下面是圆头的容量条，两端标最小 / 最大值。
 * 值变化时填充用弹簧过渡、数字按计数器滚动。
 */
@Composable
public fun MochiLinearGauge(
    value: Float,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    label: String? = null,
    valueSuffix: String = "",
    minLabel: String? = null,
    maxLabel: String? = null,
    color: Color = MochiTheme.colorScheme.primary,
    trackColor: Color = color.copy(alpha = MochiProgressDefaults.TrackAlpha),
    format: (Float) -> Long = { it.roundToLong() },
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    val p by animateFloatAsState(fraction, MochiMotions.responseSpring(0.5f, 0.9f), label = "gauge")
    Column(
        modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, range) },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (label != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(label, style = MochiTheme.textStyles.subhead, color = MochiTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                MochiCounterText(
                    format(
                        value,
                    ),
                    style = MochiTheme.textStyles.subhead,
                    color = MochiTheme.colorScheme.onSurfaceVariant,
                    format = {
                        "$it$valueSuffix"
                    },
                )
            }
        }
        val scratch = remember { Path() }
        Canvas(Modifier.fillMaxWidth().height(LinearHeight)) {
            val h = this.size.height
            drawG2RoundRect(scratch, Rect(Offset.Zero, this.size), h / 2f, trackColor)
            val w = (this.size.width * p).coerceAtLeast(if (p > 0f) h else 0f)
            if (w > 0f) drawG2RoundRect(scratch, Rect(0f, 0f, w, h), h / 2f, color)
        }
        if (minLabel != null || maxLabel != null) {
            Row(Modifier.fillMaxWidth()) {
                if (minLabel !=
                    null
                ) {
                    Text(minLabel, style = MochiTheme.textStyles.footnote, color = MochiTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                if (maxLabel !=
                    null
                ) {
                    Text(maxLabel, style = MochiTheme.textStyles.footnote, color = MochiTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val LinearHeight = 8.dp
