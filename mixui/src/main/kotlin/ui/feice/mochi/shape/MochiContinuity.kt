// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.shape

import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.kyant.capsule.Continuity
import com.kyant.capsule.ContinuousCapsule
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.capsule.continuities.G2Continuity
import com.kyant.capsule.continuities.G2ContinuityProfile
import com.kyant.capsule.core.Point
import com.kyant.capsule.path.PathSegment
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * 库里统一的圆角曲线：就是 Kyant0/Capsule（https://github.com/Kyant0/Capsule）默认的 G2 连续曲率圆角
 * （[Continuity.Default]）。直边 → 贝塞尔过渡 → 圆弧，曲率连续；半径拉到短边一半时自动过渡成胶囊。
 *
 * 库里所有圆角（[ui.feice.mochi.theme.MochiShapes]、同心圆角、按钮、菜单、面板）默认都用它；
 * 也可以直接传给 Kyant 的 `ContinuousRoundedRectangle(radius, MochiContinuity)`。
 */
@Stable
public object MochiContinuity : Continuity by Continuity.Default {
    // 和普通圆弧等价的 G2 曲线：面板背景从屏幕圆角过渡到连续圆角时的起点
    private val Circular = G2Continuity(G2ContinuityProfile.G1Equivalent, G2ContinuityProfile.G1Equivalent)

    /** smoothing 0 = 普通圆弧，1 = 连续圆角，中间按 Kyant 的曲线参数插值，动画里不会突然换一种轮廓 */
    internal fun createRoundedRectanglePathSegments(
        width: Double,
        height: Double,
        topLeft: Double,
        topRight: Double,
        bottomRight: Double,
        bottomLeft: Double,
        smoothing: Double,
    ): List<PathSegment> {
        val c = if (smoothing >= 1.0) this else Circular.lerp(Continuity.Default, smoothing.coerceAtLeast(0.0))
        return c.createRoundedRectanglePathSegments(width, height, topLeft, topRight, bottomRight, bottomLeft)
    }

    // 轮廓按 [toConvexPath] 拼：Kyant 默认拼出来的路径在 Skia 看来不是凸的，见那里的说明。胶囊也走这里（半径 = 短边一半）
    override fun createRoundedRectangleOutline(
        size: Size,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
    ): Outline = Outline.Generic(
        createRoundedRectanglePathSegments(
            size.width.toDouble(),
            size.height.toDouble(),
            topLeft.toDouble(),
            topRight.toDouble(),
            bottomRight.toDouble(),
            bottomLeft.toDouble(),
        ).toConvexPath(),
    )

    // 委托给 Continuity.Default 的默认方法不会回调上面的覆盖，胶囊要单独接过来
    override fun createCapsuleOutline(size: Size): Outline {
        val r = size.minDimension / 2f
        return createRoundedRectangleOutline(size, r, r, r, r)
    }

    override fun toString(): String = "MochiContinuity"
}

/** 连续圆角矩形（Kyant 默认曲线）：半径大到边长一半时平滑过渡成胶囊 */
@Stable
@Suppress("FunctionName")
public fun MochiRoundedRectangle(radius: Dp): ContinuousRoundedRectangle = ContinuousRoundedRectangle(radius, MochiContinuity)

/** 胶囊：和 [MochiRoundedRectangle] 半径拉满时一致 */
public val MochiCapsule: Shape = ContinuousCapsule(MochiContinuity)

/**
 * 把 G2 轮廓的分段拼成一条 Skia 认得出「凸」的闭合路径。
 *
 * Kyant 自带的 toPath 拼出来的卡片、胶囊路径，在 Skia 看来大多不是凸的（真机实测 `isConvex` = false）：
 * G2 曲线在两段之间的接口处切线完全共线（这正是曲率连续的做法），换成 float 后两边控制线的叉积
 * 落在 ±1e-3 附近，算出一个微小的反向转角，Skia 就判成凹的。不是凸的大路径 GPU 走不了快速的凸多边形抗锯齿：
 * 小的进路径图集，大的退到 CPU 上画遮罩再上传纹理，滚动时位置带小数，遮罩每帧重新生成
 * （主题页一张 1104 宽的卡片，每帧约一张遮罩上传）。
 *
 * 做法：圆弧按不超过 90° 一段换成三次贝塞尔（和圆的径向误差约 0.03%），整条轮廓只用直线和三次贝塞尔；
 * 每个接口两边的切线几乎共线时，把两边的控制点沿内法线挪 [JointNudge]（远小于一个像素），
 * 接口处的转角稳定落在凸的一侧，浮点误差翻不过来。长度近乎为 0 的段跳过，最后 close。整圈的 Circle 段直接加圆
 */
internal fun List<PathSegment>.toConvexPath(): Path {
    val path = Path()
    if (isEmpty()) return path
    first().let { s ->
        if (s is PathSegment.Circle) {
            path.addOval(Rect(s.center.offset(), s.radius.toFloat()))
            return path
        }
    }
    // 每段 8 个数：起点、两个控制点、终点（直线的控制点 = 端点，标记在 lines 里）
    val pts = ArrayList<DoubleArray>(size + 8)
    val lines = ArrayList<Boolean>(size + 8)
    var x0 = first().from.x
    var y0 = first().from.y
    fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x3: Double, y3: Double) {
        if (abs(x3 - x0) < Epsilon && abs(y3 - y0) < Epsilon && abs(x1 - x0) < Epsilon && abs(y1 - y0) < Epsilon) return
        pts += doubleArrayOf(x0, y0, x1, y1, x2, y2, x3, y3)
        lines += false
        x0 = x3
        y0 = y3
    }
    for (s in this) {
        when (s) {
            is PathSegment.Line -> {
                if (abs(s.to.x - x0) < Epsilon && abs(s.to.y - y0) < Epsilon) continue
                pts += doubleArrayOf(x0, y0, x0, y0, s.to.x, s.to.y, s.to.x, s.to.y)
                lines += true
                x0 = s.to.x
                y0 = s.to.y
            }

            is PathSegment.Cubic -> cubic(s.p1.x, s.p1.y, s.p2.x, s.p2.y, s.p3.x, s.p3.y)

            is PathSegment.Arc -> {
                if (abs(s.sweepAngle) < 1e-9 || s.radius < Epsilon) continue
                // 个别尺寸下 Kyant 给的圆弧起点和上一段终点对不上（只圆两个相邻角、半径 8dp 左右时见过），
                // 和它自己用 arcTo 画的一样先连一段直线过去，形状保持原样（这种路径仍然不是凸的）
                if (abs(s.from.x - x0) >= Epsilon || abs(s.from.y - y0) >= Epsilon) {
                    pts += doubleArrayOf(x0, y0, x0, y0, s.from.x, s.from.y, s.from.x, s.from.y)
                    lines += true
                    x0 = s.from.x
                    y0 = s.from.y
                }
                val n = ceil(abs(s.sweepAngle) / (PI / 2) - 1e-9).toInt().coerceAtLeast(1)
                val step = s.sweepAngle / n
                val k = 4.0 / 3.0 * tan(step / 4) * s.radius
                var a = s.startAngle
                for (i in 0 until n) {
                    val b = a + step
                    // 终点按角度算，和 Kyant 用 arcTo 画到的位置一致（下一段贝塞尔从当前点接着画，它自己记的起点不用）
                    val ex = s.center.x + s.radius * cos(b)
                    val ey = s.center.y + s.radius * sin(b)
                    cubic(x0 - k * sin(a), y0 + k * cos(a), ex + k * sin(b), ey - k * cos(b), ex, ey)
                    a = b
                }
            }

            else -> return Path().also { p -> forEach { it.drawTo(p) } }
        }
    }
    if (pts.isEmpty()) return path
    nudgeJoints(pts, lines)
    path.moveTo(pts[0][0].toFloat(), pts[0][1].toFloat())
    for (i in pts.indices) {
        val p = pts[i]
        if (lines[i]) {
            path.lineTo(p[6].toFloat(), p[7].toFloat())
        } else {
            path.cubicTo(p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat(), p[7].toFloat())
        }
    }
    path.close()
    return path
}

/** 接口两边切线几乎共线时，两边贝塞尔的控制点沿内法线挪一点（直线没有控制点，不动；Kyant 的直线都正好沿坐标轴，本身没有误差） */
private fun nudgeJoints(pts: List<DoubleArray>, lines: List<Boolean>) {
    // 走向：按控制多边形的有向面积，>0 是屏幕坐标里的顺时针，内法线 = 切线顺时针转 90°
    var area = 0.0
    for (p in pts) for (j in 0 until 3) area += p[j * 2] * p[j * 2 + 3] - p[j * 2 + 2] * p[j * 2 + 1]
    val turn = if (area >= 0) 1.0 else -1.0
    for (i in pts.indices) {
        val a = pts[i]
        val b = pts[(i + 1) % pts.size]
        // 进来的切线：终点减去最后一个不重合的控制点；出去的切线：第一个不重合的控制点减去起点
        val (ux, uy) = tangentIn(a)
        val (vx, vy) = tangentOut(b)
        val lu = hypot(ux, uy)
        val lv = hypot(vx, vy)
        if (lu < Epsilon || lv < Epsilon) continue
        val cross = (ux * vy - uy * vx) / (lu * lv)
        val dot = (ux * vx + uy * vy) / (lu * lv)
        // 两边都正好沿同一条坐标轴（直边和贴边的控制点）：换成 float 也还是严格共线，不用挪，挪了反而把贴边的控制点拐出凹角。
        // 斜着共线的（比如胶囊 45° 处）在 double 里叉积可能正好是 0，换成 float 就不是了，照样要挪
        if ((ux == 0.0 && vx == 0.0) || (uy == 0.0 && vy == 0.0) || abs(cross) > JointCollinear || dot < 0) continue
        // 内法线（单位向量）：切线方向转 90°，转向跟着轮廓走向
        val nx = -uy / lu * turn
        val ny = ux / lu * turn
        // 至少挪过几个 float 的最小间隔：坐标大、控制柄短时按比例挪的量会被 float 舍掉
        val floor = JointUlps * Math.ulp(maxOf(abs(a[6]), abs(a[7])).toFloat()).toDouble()
        if (!lines[i]) {
            val d = maxOf(JointNudge * lu, floor)
            a[4] += nx * d
            a[5] += ny * d
        }
        if (!lines[(i + 1) % pts.size]) {
            val d = maxOf(JointNudge * lv, floor)
            b[2] += nx * d
            b[3] += ny * d
        }
    }
}

private fun tangentIn(p: DoubleArray): Pair<Double, Double> {
    for (j in 2 downTo 0) {
        val dx = p[6] - p[j * 2]
        val dy = p[7] - p[j * 2 + 1]
        if (abs(dx) >= Epsilon || abs(dy) >= Epsilon) return dx to dy
    }
    return 0.0 to 0.0
}

private fun tangentOut(p: DoubleArray): Pair<Double, Double> {
    for (j in 1..3) {
        val dx = p[j * 2] - p[0]
        val dy = p[j * 2 + 1] - p[1]
        if (abs(dx) >= Epsilon || abs(dy) >= Epsilon) return dx to dy
    }
    return 0.0 to 0.0
}

/** 接口两边切线夹角的正弦小于这个就算共线 */
private const val JointCollinear = 1e-3

/** 共线接口处控制点往里挪的量（占控制柄长度的比例），见 [toConvexPath] */
private const val JointNudge = 1e-4

/** 共线接口处控制点至少往里挪几个 float 最小间隔 */
private const val JointUlps = 8

/** 小于这个距离（px）的点当成同一个点 */
private const val Epsilon = 1e-3

private fun Point.offset(): Offset = Offset(x.toFloat(), y.toFloat())
