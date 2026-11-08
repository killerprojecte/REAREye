// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.platform.LocalView
import java.util.function.Consumer
import kotlin.math.min

/**
 * 实验性 HDR 高光：打开后窗口切到 HDR 模式，按住时的镜面高光、开关滑块的圆钮和边缘流光
 * 用超过 SDR 白的亮度画。
 *
 * 需要 Android 14（API 34）以上和支持 HDR 的屏幕，其他情况一切按 SDR 画，外观和关闭时完全一样。
 * HDR 模式下屏幕会提高背光，比 SDR 更耗电，默认关闭。
 */
public object MochiHdr {
    /** 全局开关 */
    public var enabled: Boolean by mutableStateOf(false)

    /** 0..1：用掉屏幕可用亮度余量的多少 */
    public var strength: Float by mutableFloatStateOf(0.6f)

    /** 屏幕当前的 HDR / SDR 亮度比（1 = 没有余量）；系统按环境光和亮度实时调整 */
    public val headroom: Float get() = headroomState

    /** 系统和屏幕是否支持 HDR 界面 */
    public val supported: Boolean get() = supportedState

    /**
     * HDR 光效的峰值亮度（SDR 白 = 1，线性光）；关闭或不支持时恰好是 1。
     * 所有 HDR 画法都保证**每个通道都不超过它**：颜色按原色相整体提亮，多层光不叠加超出，
     * 不会因为某个通道顶到屏幕上限被截断而发白、过曝。
     */
    internal val boost: Float
        get() = if (enabled &&
            supportedState
        ) {
            1f + (min(headroomState, MaxBoost) - 1f).coerceAtLeast(0f) * strength.coerceIn(0f, 1f)
        } else {
            1f
        }

    internal var headroomState by mutableFloatStateOf(1f)

    /** 正在管 HDR 窗口模式的那个 MochiTheme（最外层） */
    internal var windowOwner: Any? by mutableStateOf(null)
    internal var supportedState by mutableStateOf(false)

    /** 峰值最多 3 倍 SDR 白：再亮只会刺眼、更耗电，彩色光也更容易顶到屏幕上限 */
    internal const val MaxBoost = 3f
}

/**
 * 同一种颜色亮 boost 倍（线性光下）：转到 LinearExtendedSrgb 再乘。boost ≤ 1 原样返回。
 * 要画出来需要窗口处于 HDR 模式，并且画法支持扩展范围的颜色（Compose 的渐变会截到 8 位，库里用自己的 HDR 画刷）。
 */
public fun Color.hdr(boost: Float): Color {
    if (boost <= 1f) return this
    val l = convert(ColorSpaces.LinearExtendedSrgb)
    val k = boost.coerceAtMost(7f)
    return Color(
        red = (l.red * k).coerceIn(-0.5f, 7.4f),
        green = (l.green * k).coerceIn(-0.5f, 7.4f),
        blue = (l.blue * k).coerceIn(-0.5f, 7.4f),
        alpha = l.alpha,
        colorSpace = ColorSpaces.LinearExtendedSrgb,
    )
}

/**
 * 彩色光的 HDR 提亮（线性光，结果在 LinearExtendedSrgb）：只放大颜色里「彩色」的那部分，白色成分（最小的通道）保持 SDR 亮度。
 *
 * 浅色的光（比如 Apple Intelligence 的浅粉 F5B9EA，三个通道都不低）整体乘倍数时，最小的通道也被抬到超过 SDR 白，
 * 看上去就是一道白光，颜色没了。这里让最亮的通道照样到 gain 倍，较暗的通道按比例拉开，越亮越艳、不会发白。
 * 接近白 / 灰的颜色（饱和度低）退回整体乘倍数，白光照样变亮、不会染上色。
 */
internal fun Color.hdrLift(gain: Float): Color {
    val l = convert(ColorSpaces.LinearExtendedSrgb)
    if (gain <= 1f) return l
    val g = gain.coerceAtMost(7f)
    val hi = maxOf(l.red, l.green, l.blue)
    val lo = minOf(l.red, l.green, l.blue).coerceAtLeast(0f)
    if (hi <= 0f) return l
    // 彩色部分的放大倍数：让最亮的通道到 hi·g，最小的通道不动
    val chroma = if (hi - lo > 1e-4f) (hi * g - lo) / (hi - lo) else g
    // 饱和度低（接近白、灰）时平滑地退回整体乘 g
    val t = ((hi - lo) / hi / 0.3f).coerceIn(0f, 1f)
    val w = t * t * (3f - 2f * t)
    fun ch(v: Float) = (v * g) * (1f - w) + (lo + (v - lo) * chroma) * w
    return Color(
        red = ch(l.red).coerceIn(-0.5f, 7.4f),
        green = ch(l.green).coerceIn(-0.5f, 7.4f),
        blue = ch(l.blue).coerceIn(-0.5f, 7.4f),
        alpha = l.alpha,
        colorSpace = ColorSpaces.LinearExtendedSrgb,
    )
}

/** AGSL 版的 [hdrLift]：输入输出都是线性光 */
internal const val HDR_LIFT_AGSL = """
float3 hdrLift(float3 c, float g) {
    float hi = max(max(c.r, c.g), c.b);
    float lo = max(min(min(c.r, c.g), c.b), 0.0);
    if (g <= 1.0 || hi <= 0.0) return c;
    float chroma = hi - lo > 1e-4 ? (hi * g - lo) / (hi - lo) : g;
    float w = smoothstep(0.0, 1.0, clamp((hi - lo) / hi / 0.3, 0.0, 1.0));
    return mix(c * g, lo + (c - lo) * chroma, w);
}
"""

/** 跟踪屏幕亮度余量，并在 [MochiHdr.enabled] 时把窗口切到 HDR 模式（MochiTheme 最外层调用一次） */
@Composable
internal fun MochiHdrWindowEffect() {
    if (Build.VERSION.SDK_INT < 34) return
    // 嵌套的 MochiTheme 都会调到这里：只让最先出现的那个管窗口（它离开时交出去），不然里层一离开就把窗口模式恢复了
    val token = remember { Any() }
    DisposableEffect(token) {
        if (MochiHdr.windowOwner == null) MochiHdr.windowOwner = token
        onDispose { if (MochiHdr.windowOwner === token) MochiHdr.windowOwner = null }
    }
    if (MochiHdr.windowOwner !== token) return
    val view = LocalView.current
    DisposableEffect(view) {
        val display = view.display ?: return@DisposableEffect onDispose { }
        MochiHdr.supportedState = display.isHdrSdrRatioAvailable
        MochiHdr.headroomState = display.hdrSdrRatio
        if (!display.isHdrSdrRatioAvailable) return@DisposableEffect onDispose { }
        val listener = Consumer<Display> { MochiHdr.headroomState = it.hdrSdrRatio }
        display.registerHdrSdrRatioChangedListener(view.context.mainExecutor, listener)
        onDispose { display.unregisterHdrSdrRatioChangedListener(listener) }
    }
    val on = MochiHdr.enabled && MochiHdr.supported
    DisposableEffect(view, on) {
        val window = view.context.findActivity()?.window
        if (on && window != null) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
            if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(MochiHdr.MaxBoost)
        }
        onDispose {
            if (on && window != null) {
                if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(0f)
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
