// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.hdr

import android.graphics.LinearGradient
import android.graphics.RadialGradient
import android.graphics.SweepGradient
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toArgb
import ui.feice.mochi.theme.MochiHdr
import ui.feice.mochi.theme.hdrLift

// 扩展亮度的画刷：Compose 自带的渐变用 int 颜色构造（截到 8 位 sRGB），HDR 下要直接用 long 颜色构造系统渐变。
// 所有色标统一转到 LinearExtendedSrgb（同一个渐变里的颜色必须同一个色彩空间）。boost ≤ 1 时退回普通 Compose 画刷，
// SDR 下和原来逐像素一样。

// 懒加载：ColorSpace 是 API 26 的类，只在 HDR 路径（API 29+）上才碰到
private val LinearExt by lazy { android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB) }

/** 转成系统的 long 颜色（线性扩展 sRGB），按 [hdrLift] 提亮到 boost 倍（彩色光不发白） */
internal fun Color.hdrLong(boost: Float): Long {
    val c = hdrLift(boost.coerceAtLeast(1f))
    return android.graphics.Color.pack(c.red, c.green, c.blue, c.alpha, LinearExt)
}

/** 直接给线性光的 rgb（可以超过 1）打包成系统的 long 颜色 */
internal fun linearLong(r: Float, g: Float, b: Float, a: Float = 1f): Long = android.graphics.Color.pack(r, g, b, a, LinearExt)

/** 线性光的径向渐变，色标直接是 long 颜色（[linearLong]）；只在 HDR 路径（API 29+）上用 */
internal fun linearRadialBrush(pos: FloatArray, colors: LongArray, center: Offset, radius: Float): Brush = FixedShaderBrush(
    RadialGradient(center.x, center.y, radius.coerceAtLeast(0.01f), colors, pos, android.graphics.Shader.TileMode.CLAMP),
)

private fun useHdr(boost: Float) = boost > 1.001f && Build.VERSION.SDK_INT >= 29

private class FixedShaderBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

internal fun hdrRadialBrush(stops: Array<Pair<Float, Color>>, center: Offset, radius: Float, boost: Float): Brush {
    if (!useHdr(boost)) return Brush.radialGradient(*stops, center = center, radius = radius)
    val colors = LongArray(stops.size) { stops[it].second.hdrLong(boost) }
    val pos = FloatArray(stops.size) { stops[it].first }
    return FixedShaderBrush(
        RadialGradient(center.x, center.y, radius.coerceAtLeast(0.01f), colors, pos, android.graphics.Shader.TileMode.CLAMP),
    )
}

internal fun hdrLinearBrush(stops: Array<Pair<Float, Color>>, start: Offset, end: Offset, boost: Float): Brush {
    if (!useHdr(boost)) return Brush.linearGradient(*stops, start = start, end = end)
    val colors = LongArray(stops.size) { stops[it].second.hdrLong(boost) }
    val pos = FloatArray(stops.size) { stops[it].first }
    return FixedShaderBrush(LinearGradient(start.x, start.y, end.x, end.y, colors, pos, android.graphics.Shader.TileMode.CLAMP))
}

/** 扫掠渐变；rotation 是起点角度（度） */
internal fun hdrSweepBrush(stops: Array<Pair<Float, Color>>, center: Offset, rotation: Float, boost: Float): Brush {
    val m = android.graphics.Matrix().apply { setRotate(rotation, center.x, center.y) }
    if (!useHdr(boost)) {
        val ints = IntArray(stops.size) { stops[it].second.toArgb() }
        val pos = FloatArray(stops.size) { stops[it].first }
        return FixedShaderBrush(SweepGradient(center.x, center.y, ints, pos).apply { setLocalMatrix(m) })
    }
    val colors = LongArray(stops.size) { stops[it].second.hdrLong(boost) }
    val pos = FloatArray(stops.size) { stops[it].first }
    return FixedShaderBrush(SweepGradient(center.x, center.y, colors, pos).apply { setLocalMatrix(m) })
}

/** 纯色：HDR 下用两个色标相同的渐变代替（Paint 的纯色同样会被截到 8 位） */
internal fun hdrSolidBrush(color: Color, boost: Float): Brush {
    if (!useHdr(boost)) return SolidColor(color)
    val c = color.hdrLong(boost)
    return FixedShaderBrush(LinearGradient(0f, 0f, 1f, 0f, longArrayOf(c, c), null, android.graphics.Shader.TileMode.CLAMP))
}

/**
 * 纯色画刷，[MochiHdr] 打开时亮 boost 倍（超过 SDR 白）。自己画 HDR 内容时用：
 * `drawRoundRect(mochiHdrBrush(Color.White), …)`。Compose 的 `drawRect(color)` 会把颜色截到 8 位，画不出 HDR。
 * 在绘制阶段调用，亮度余量变化时会自动重画。
 */
public fun mochiHdrBrush(color: Color, boost: Float = MochiHdr.boost): Brush = hdrSolidBrush(color, boost)
