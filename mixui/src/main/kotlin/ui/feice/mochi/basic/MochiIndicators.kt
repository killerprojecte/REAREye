// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.basic.parts.drawG2Capsule
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.theme.MochiTheme

/**
 * iOS 的转圈加载（UIActivityIndicatorView）：8 根胶囊辐条，一道亮光顺时针平滑扫过——
 * 每根先很快亮起（周期的 10%），再慢慢暗回去（到 50%），之后保持底色，相邻辐条错开 1/8 周期。
 * 放在列表行尾表示这一行在加载，不打断整个列表。
 *
 * @param color 辐条颜色，默认浅色主题黑 60%、深色主题白 80%；亮暗在它的透明度上再乘 0.3..1
 * @param reveal 不为 null 时不转，按返回值（0..1）从顶上开始顺时针一根根把辐条显出来（下拉刷新还没松手时的样子）
 */
@Composable
public fun MochiActivityIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = MochiProgressDefaults.activityColor(),
    reveal: (() -> Float)? = null,
) {
    val transition = rememberInfiniteTransition(label = "activity")
    val phase by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(CycleMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val tick = remember { G2PathCache() }
    Canvas(
        modifier
            .size(size)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val r = this.size.minDimension / 2f
        val length = r * 0.6f
        val width = r * 0.25f
        // 辐条横放在中心右侧，旋转到各自的角度：从内半径 0.4r 伸到外沿
        val rect = Rect(center.x + r - length, center.y - width / 2f, center.x + r, center.y + width / 2f)
        val shown = reveal?.invoke()?.let { (it.coerceIn(0f, 1f) * Spokes).toInt() }
        for (k in 0 until Spokes) {
            if (shown != null && k >= shown) break
            // 第 k 根从顶上顺时针数；亮光顺时针走，所以后一根比前一根晚 1/8 周期
            val a = if (shown != null) RevealAlpha else spokeAlpha((phase - k.toFloat() / Spokes + 1f) % 1f)
            rotate(-90f + k * 360f / Spokes, center) {
                drawG2Capsule(tick, rect, color, alpha = a)
            }
        }
    }
}

/** 一根辐条在周期内 t（0..1）时的亮度：0→10% 亮起，10%→50% 暗回，之后保持 [RestAlpha] */
private fun spokeAlpha(t: Float): Float = when {
    t < 0.1f -> RestAlpha + (1f - RestAlpha) * (t / 0.1f)
    t < 0.5f -> 1f - (1f - RestAlpha) * ((t - 0.1f) / 0.4f)
    else -> RestAlpha
}

private const val Spokes = 8
private const val CycleMillis = 1000
private const val RestAlpha = 0.3f
private const val RevealAlpha = 0.55f

/** 加载与进度指示的默认值 */
public object MochiProgressDefaults {
    /** 进度条、环形进度轨道相对进度色的透明度 */
    public const val TrackAlpha: Float = 0.3f

    /** 转圈辐条的默认颜色：浅色主题黑 60%，深色主题白 80% */
    @Composable
    public fun activityColor(): Color =
        if (MochiTheme.colorScheme.isDark) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f)
}

/**
 * 进度条：G2 胶囊轨道 + 胶囊进度，进度变化用弹簧过渡。适合放在信息卡片里（iOS 储存空间卡那种）。
 * 轨道默认是进度色的 30%。
 * @param progress 0..1
 */
@Composable
public fun MochiProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MochiTheme.colorScheme.primary,
    trackColor: Color = color.copy(alpha = color.alpha * MochiProgressDefaults.TrackAlpha),
    height: Dp = 6.dp,
) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), MochiMotions.responseSpring(0.5f, 0.9f), label = "progress")
    val track = remember { G2PathCache() }
    val bar = remember { G2PathCache() }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) },
    ) {
        val h = this.size.height
        drawG2Capsule(track, Rect(Offset.Zero, this.size), trackColor)
        if (p > 0f) {
            // 进度很小时也保持胶囊形（至少一个圆那么宽）
            val w = (this.size.width * p).coerceAtLeast(h)
            drawG2Capsule(bar, Rect(0f, 0f, w, h), color)
        }
    }
}
