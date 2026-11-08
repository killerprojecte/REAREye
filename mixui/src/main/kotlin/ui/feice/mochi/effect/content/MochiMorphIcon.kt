// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.content

import android.graphics.Matrix
import android.graphics.PathMeasure
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.theme.LocalContentColor

/** 图标原位变形的默认参数 */
public object MochiMorphIconDefaults {
    /** 变形的弹簧：和内容变换一样用 snappy（300ms、回弹 0.15） */
    public val Spring: AnimationSpec<Float> = ContentSpring

    /** 变形到一半时的最大模糊（掩盖中间形状不规整的那一下；Android 12 以下没有模糊） */
    public val Blur: Dp = 1.5.dp
}

/**
 * 图标换成另一个时原位变形（SF Symbols 的 magic replace）：两个图标的每一笔（每条轮廓）按位置配对，
 * 沿着轮廓均匀取点，一一对应着从旧的形状挪到新的形状，粗细也跟着变；对不上的笔画从自己的中心长出来 / 缩没。
 * 变到一半轻轻糊一下，掩盖中间形状不规整的那一刻。途中又换图标，从当前的形状接着变，不跳。
 *
 * 填充和描边的笔画各自配对（填充的变成填充、描边的变成描边）；整个图标用 [tint] 一种颜色画（和 tint 过的图标一样）。
 * 两个图标差别很大、不想看到中间形状时，用 [MochiBlurCrossfade] 做模糊替换。
 *
 * @param size 图标的边长，视口按 [ImageVector] 自己的 viewport 缩放进来
 */
@Composable
public fun MochiMorphIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    spec: AnimationSpec<Float> = MochiMorphIconDefaults.Spring,
    blur: Dp = MochiMorphIconDefaults.Blur,
) {
    val target = remember(icon) { MorphShape.of(icon) }
    val state = remember { MorphState(target) }
    LaunchedEffect(target) { state.morphTo(target, spec) }
    Canvas(
        modifier
            .size(size)
            .graphicsLayer {
                val t = state.t.value
                val r = if (state.settled) 0f else blur.toPx() * sin(PI.toFloat() * t.coerceIn(0f, 1f))
                renderEffect = mochiCachedBlur(r)
            },
    ) {
        state.draw(this, tint)
    }
}

/** 沿一条轮廓均匀取的点（视口坐标） */
private class Contour(
    val xs: FloatArray,
    val ys: FloatArray,
    val closed: Boolean,
    val filled: Boolean,
    val width: Float,
    val cap: StrokeCap,
    val join: StrokeJoin,
) {
    val cx: Float = xs.average().toFloat()
    val cy: Float = ys.average().toFloat()

    val length: Float by lazy {
        var l = 0f
        for (i in 1 until xs.size) l += dist(xs[i - 1], ys[i - 1], xs[i], ys[i])
        l
    }

    /** 缩成自己中心的一个点（对不上的笔画从这里长出来 / 缩回去） */
    fun collapsed(): Contour = Contour(
        FloatArray(xs.size) { cx },
        FloatArray(ys.size) { cy },
        closed,
        filled,
        0f,
        cap,
        join,
    )
}

/** 一个图标拆成的轮廓 */
private class MorphShape(val contours: List<Contour>, val viewportWidth: Float, val viewportHeight: Float, val fillType: PathFillType) {
    companion object {
        const val N = 96

        fun of(icon: ImageVector): MorphShape {
            val out = ArrayList<Contour>()
            var evenOdd = false
            fun walk(g: VectorGroup, parent: Matrix) {
                val m = Matrix(parent).apply {
                    val local = Matrix()
                    local.postTranslate(-g.pivotX, -g.pivotY)
                    local.postScale(g.scaleX, g.scaleY)
                    local.postRotate(g.rotation)
                    local.postTranslate(g.translationX + g.pivotX, g.translationY + g.pivotY)
                    preConcat(local)
                }
                for (node in g) {
                    when (node) {
                        is VectorGroup -> walk(node, m)

                        is VectorPath -> {
                            val p = PathParser().addPathNodes(node.pathData).toPath().asAndroidPath()
                            p.transform(m)
                            val v = FloatArray(9).also { m.getValues(it) }
                            val k = sqrt(abs(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_Y] - v[Matrix.MSKEW_X] * v[Matrix.MSKEW_Y]))
                            if (node.pathFillType == PathFillType.EvenOdd) evenOdd = true
                            if (node.fill != null) sample(p, true, 0f, node.strokeLineCap, node.strokeLineJoin, out)
                            if (node.stroke != null && node.strokeLineWidth > 0f) {
                                sample(p, false, node.strokeLineWidth * k, node.strokeLineCap, node.strokeLineJoin, out)
                            }
                        }
                    }
                }
            }
            walk(icon.root, Matrix())
            return MorphShape(out, icon.viewportWidth, icon.viewportHeight, if (evenOdd) PathFillType.EvenOdd else PathFillType.NonZero)
        }

        private fun sample(
            p: android.graphics.Path,
            filled: Boolean,
            width: Float,
            cap: StrokeCap,
            join: StrokeJoin,
            out: MutableList<Contour>,
        ) {
            val pm = PathMeasure(p, false)
            val pos = FloatArray(2)
            do {
                val len = pm.length
                if (len <= 0f) continue
                val closed = pm.isClosed
                val xs = FloatArray(N)
                val ys = FloatArray(N)
                for (i in 0 until N) {
                    val d = if (closed) len * i / N else len * i / (N - 1)
                    pm.getPosTan(d, pos, null)
                    xs[i] = pos[0]
                    ys[i] = pos[1]
                }
                out += Contour(xs, ys, closed, filled, width, cap, join)
            } while (pm.nextContour())
        }
    }
}

/** 一对正在互相变形的轮廓。每帧插值写进自己的缓冲和 Path，变形期间每帧不分配新对象 */
private class Pair2(val a: Contour, val b: Contour, val ghost: Boolean) {
    val path = Path()
}

private class MorphState(initial: MorphShape) {
    val t = Animatable(1f)
    var target: MorphShape = initial
    private var pairs: List<Pair2> = initial.contours.map { Pair2(it, it, false) }

    /** 变完了：直接画目标形状，不再模糊 */
    val settled: Boolean get() = !t.isRunning && t.value == 1f

    suspend fun morphTo(next: MorphShape, spec: AnimationSpec<Float>) {
        if (next === target) return
        // 从当前画面接着变：先把此刻的形状定下来（已经缩没的笔画丢掉）
        val now = t.value
        val current = pairs.filter { !(it.ghost && now >= 1f) }.map { lerp(it.a, it.b, now) }
        pairs = match(current, next.contours)
        target = next
        t.snapTo(0f)
        t.animateTo(1f, spec)
    }

    private val fill = Path()

    fun draw(scope: DrawScope, tint: Color) = with(scope) {
        val k = minOf(size.width / target.viewportWidth, size.height / target.viewportHeight)
        val tt = t.value
        fill.rewind()
        fill.fillType = target.fillType
        var hasFill = false
        val half = tt >= 0.5f
        for (pr in pairs) {
            val a = pr.a
            val b = pr.b
            val path = pr.path
            path.rewind()
            val n = a.xs.size
            for (i in 0 until n) {
                val x = (a.xs[i] + (b.xs[i] - a.xs[i]) * tt) * k
                val y = (a.ys[i] + (b.ys[i] - a.ys[i]) * tt) * k
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (if (half) b.closed else a.closed) path.close()
            if (a.filled) {
                fill.addPath(path)
                hasFill = true
            } else {
                val w = (a.width + (b.width - a.width) * tt) * k
                if (w > 0f) {
                    drawPath(path, tint, style = Stroke(width = w, cap = if (half) b.cap else a.cap, join = if (half) b.join else a.join))
                }
            }
        }
        if (hasFill) drawPath(fill, tint)
    }
}

private fun Contour.toPath(k: Float): Path = Path().apply {
    moveTo(xs[0] * k, ys[0] * k)
    for (i in 1 until xs.size) lineTo(xs[i] * k, ys[i] * k)
    if (closed) close()
}

private fun lerp(a: Contour, b: Contour, t: Float): Contour {
    val xs = FloatArray(a.xs.size) { a.xs[it] + (b.xs[it] - a.xs[it]) * t }
    val ys = FloatArray(a.ys.size) { a.ys[it] + (b.ys[it] - a.ys[it]) * t }
    val w = (a.width + (b.width - a.width) * t).coerceAtLeast(0f)
    val half = t >= 0.5f
    return Contour(
        xs,
        ys,
        if (half) b.closed else a.closed,
        a.filled,
        w,
        if (half) b.cap else a.cap,
        if (half) b.join else a.join,
    )
}

/**
 * 两组轮廓配对：同类（填充 / 描边）里按「中心距离 + 长度差」从最像的一对开始贪心配；
 * 剩下的跟一个缩成点的自己配（从中心长出来 / 缩回去）。配好后再把点的起点对齐，免得变形时整圈打转
 */
private fun match(from: List<Contour>, to: List<Contour>): List<Pair2> {
    val result = ArrayList<Pair2>()
    val usedA = BooleanArray(from.size)
    val usedB = BooleanArray(to.size)
    val candidates = ArrayList<Triple<Float, Int, Int>>()
    for (i in from.indices) {
        for (j in to.indices) {
            if (from[i].filled != to[j].filled) continue
            val d = dist(from[i].cx, from[i].cy, to[j].cx, to[j].cy)
            val dl = abs(from[i].length - to[j].length)
            candidates += Triple(d + dl * 0.25f, i, j)
        }
    }
    candidates.sortBy { it.first }
    for ((_, i, j) in candidates) {
        if (usedA[i] || usedB[j]) continue
        usedA[i] = true
        usedB[j] = true
        result += Pair2(from[i], align(from[i], to[j]), false)
    }
    for (i in from.indices) if (!usedA[i]) result += Pair2(from[i], from[i].collapsed(), true)
    for (j in to.indices) if (!usedB[j]) result += Pair2(to[j].collapsed(), to[j], false)
    return result
}

/**
 * 把 b 的点重新排一下顺序，让它和 a 一一对应时挪动的总距离最小：
 * 闭合的轮廓转起点（不翻方向，保住填充的绕向，挖空的洞才不会被填上），开放的轮廓试一下反过来
 */
private fun align(a: Contour, b: Contour): Contour {
    val n = a.xs.size
    fun cost(shift: Int, reverse: Boolean): Float {
        var s = 0f
        for (i in 0 until n) {
            val j = if (reverse) n - 1 - i else (i + shift) % n
            val dx = a.xs[i] - b.xs[j]
            val dy = a.ys[i] - b.ys[j]
            s += dx * dx + dy * dy
        }
        return s
    }
    var bestShift = 0
    var bestReverse = false
    var best = cost(0, false)
    if (a.closed && b.closed) {
        for (s in 1 until n) {
            val c = cost(s, false)
            if (c < best) {
                best = c
                bestShift = s
            }
        }
    } else if (!b.closed) {
        if (cost(0, true) < best) bestReverse = true
    }
    if (bestShift == 0 && !bestReverse) return b
    val xs = FloatArray(n) { i -> b.xs[if (bestReverse) n - 1 - i else (i + bestShift) % n] }
    val ys = FloatArray(n) { i -> b.ys[if (bestReverse) n - 1 - i else (i + bestShift) % n] }
    return Contour(xs, ys, b.closed, b.filled, b.width, b.cap, b.join)
}

private fun dist(x0: Float, y0: Float, x1: Float, y1: Float): Float {
    val dx = x1 - x0
    val dy = y1 - y0
    return sqrt(dx * dx + dy * dy)
}

/** 图标「描绘」动画的默认参数 */
public object MochiDrawIconDefaults {
    /** 画出来 / 擦掉的弹簧（不过冲：笔画不该画过头） */
    public val Spring: AnimationSpec<Float> = MochiMotions.responseSpring(0.7f, 1f)

    /** 后一笔比前一笔晚多少开始（占整段进度的比例） */
    public const val Stagger: Float = 0.12f
}

/**
 * 图标「描绘」（SF Symbols 7 的 Draw On / Draw Off）：[drawn] 变成 true 时，描边的笔画沿着自己的方向一笔笔画出来，
 * 一笔比一笔晚一点开始；变成 false 时原路擦掉。填充的部分跟着淡入淡出。途中切换从当前进度接着走。
 *
 * @param initiallyDrawn 第一次出现时是不是已经画好（false = 一出现就从空白画出来）
 */
@Composable
public fun MochiDrawIcon(
    icon: ImageVector,
    drawn: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    initiallyDrawn: Boolean = drawn,
    spec: AnimationSpec<Float> = MochiDrawIconDefaults.Spring,
    stagger: Float = MochiDrawIconDefaults.Stagger,
) {
    val shape = remember(icon) { MorphShape.of(icon) }
    val p = remember { Animatable(if (initiallyDrawn) 1f else 0f) }
    LaunchedEffect(drawn) { p.animateTo(if (drawn) 1f else 0f, spec) }
    Canvas(modifier.size(size)) {
        val k = minOf(this.size.width / shape.viewportWidth, this.size.height / shape.viewportHeight)
        val n = shape.contours.size
        if (n == 0) return@Canvas
        val st = stagger.coerceIn(0f, 0.9f / n.coerceAtLeast(1))
        val span = 1f - st * (n - 1)
        val fill = Path().apply { fillType = shape.fillType }
        var fillAlpha = 0f
        shape.contours.forEachIndexed { i, c ->
            val local = ((p.value - st * i) / span).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed
            if (c.filled) {
                fill.addPath(c.toPath(k))
                fillAlpha = maxOf(fillAlpha, local)
            } else {
                drawPath(c.partialPath(k, local), tint, style = Stroke(width = c.width * k, cap = StrokeCap.Round, join = c.join))
            }
        }
        if (fillAlpha > 0f) drawPath(fill, tint, alpha = fillAlpha)
    }
}

/** 沿轮廓从起点画到 t（0..1）处的那一段 */
private fun Contour.partialPath(k: Float, t: Float): Path {
    val count = if (closed) xs.size else xs.size - 1
    val pos = t * count
    val whole = pos.toInt().coerceAtMost(count)
    return Path().apply {
        moveTo(xs[0] * k, ys[0] * k)
        for (i in 1..whole) {
            val j = i % xs.size
            lineTo(xs[j] * k, ys[j] * k)
        }
        val frac = pos - whole
        if (whole < count && frac > 0f) {
            val a = whole % xs.size
            val b = (whole + 1) % xs.size
            lineTo((xs[a] + (xs[b] - xs[a]) * frac) * k, (ys[a] + (ys[b] - ys[a]) * frac) * k)
        }
    }
}
