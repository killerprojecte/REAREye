// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.shape

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/**
 * 超椭圆（Lamé 曲线 |x/a|ⁿ + |y/b|ⁿ = 1）：整条边都在弯，没有「直边 + 圆角」的接缝，
 * 适合方形的图标块（像 iOS 的 app 图标）。圆角矩形的圆角相对边长比较小时看起来生硬，换成它就柔和了。
 *
 * @param exponent n：越大越接近方形，越小越接近圆。4 ≈ 很圆润，5（默认）≈ iOS 图标，8 以上已经很方
 */
@Immutable
public class MochiSuperellipse(private val exponent: Float = 5f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val a = size.width / 2f
        val b = size.height / 2f
        val e = 2f / exponent.coerceAtLeast(2f)
        val path = Path()
        for (i in 0..Segments) {
            val t = 2f * PI.toFloat() * i / Segments
            val c = cos(t)
            val s = sin(t)
            val x = a + a * sign(c) * abs(c).pow(e)
            val y = b + b * sign(s) * abs(s).pow(e)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean = other is MochiSuperellipse && other.exponent == exponent

    override fun hashCode(): Int = exponent.hashCode()

    override fun toString(): String = "MochiSuperellipse($exponent)"

    private companion object {
        // 每段约 1.4°：几十 dp 的图标上和真曲线差不到 0.1px
        const val Segments = 256
    }
}
