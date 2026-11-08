// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.shape

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.unit.Dp
import com.kyant.capsule.Continuity
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.capsule.continuities.G1Continuity
import kotlin.math.min

/*
 * 圆角统一用 [MochiContinuity]，也就是 Kyant0/Capsule（https://github.com/Kyant0/Capsule）默认的 G2 连续曲率圆角：
 * 直边 → 贝塞尔过渡 → 圆弧；半径超过短边一半时自动过渡成胶囊。
 */
/** smoothing ≤ 0 退回普通圆角（G1），否则用 Kyant 默认的 G2 连续曲率 */
internal fun continuityFor(smoothing: Float): Continuity = if (smoothing <= 0f) G1Continuity else MochiContinuity

/** 往 Path 里加一个连续曲率圆角矩形（rect 是绝对坐标） */
internal fun Path.addG2RoundRect(rect: Rect, radius: Float, smoothing: Float) {
    if (rect.width <= 0f || rect.height <= 0f) return
    val r = radius.coerceIn(0f, min(rect.width, rect.height) / 2f).toDouble()
    val p = continuityFor(smoothing)
        .createRoundedRectanglePathSegments(rect.width.toDouble(), rect.height.toDouble(), r, r, r, r)
        .toConvexPath()
    addPath(p, rect.topLeft)
}

/** 四个角各自半径的连续曲率圆角矩形（rect 是绝对坐标；顺序：左上、右上、右下、左下） */
internal fun Path.addG2RoundRect(rect: Rect, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
    if (rect.width <= 0f || rect.height <= 0f) return
    val half = min(rect.width, rect.height) / 2f
    val p = MochiContinuity
        .createRoundedRectanglePathSegments(
            rect.width.toDouble(),
            rect.height.toDouble(),
            topLeft.coerceIn(0f, half).toDouble(),
            topRight.coerceIn(0f, half).toDouble(),
            bottomRight.coerceIn(0f, half).toDouble(),
            bottomLeft.coerceIn(0f, half).toDouble(),
        )
        .toConvexPath()
    addPath(p, rect.topLeft)
}

/**
 * 在 Canvas / drawBehind 里画连续曲率圆角矩形（含胶囊）。自绘的圆角块（高亮块、指示点、进度条、选中胶囊）都用它，
 * 不用 `drawRoundRect`：那是圆弧圆角，和库里其他 G2 形状放在一起一眼就能看出不一样。
 * [scratch] 由调用方 remember 一个复用（drawPath 记录时会复制路径，所以同一帧里连画多个也没问题）。
 */
internal fun DrawScope.drawG2RoundRect(
    scratch: Path,
    rect: Rect,
    radius: Float,
    color: Color,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
) {
    if (rect.width <= 0f || rect.height <= 0f) return
    scratch.reset()
    scratch.addG2RoundRect(rect, radius, 1f)
    drawPath(scratch, color, alpha = alpha, style = style)
}

/** 四个角各自半径的版本（顺序：左上、右上、右下、左下） */
internal fun DrawScope.drawG2RoundRect(
    scratch: Path,
    rect: Rect,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
    color: Color,
    alpha: Float = 1f,
) {
    if (rect.width <= 0f || rect.height <= 0f) return
    scratch.reset()
    scratch.addG2RoundRect(rect, topLeft, topRight, bottomRight, bottomLeft)
    drawPath(scratch, color, alpha = alpha)
}

/** 旧 API：连续曲率圆角。新代码请用 MochiTheme.shapes 或 MochiRoundedRectangle */
@Deprecated(
    "用 MochiTheme.shapes 或 MochiRoundedRectangle",
    ReplaceWith("MochiRoundedRectangle(radius)", "ui.feice.mochi.shape.MochiRoundedRectangle"),
)
@Suppress("FunctionName")
public fun G2RoundedShape(radius: Dp, smoothing: Float = 1f): Shape = ContinuousRoundedRectangle(radius, continuityFor(smoothing))

/** 旧 API：胶囊。新代码请用 MochiTheme.shapes.full 或 MochiCapsule */
@Deprecated(
    "用 MochiTheme.shapes.full 或 MochiCapsule",
    ReplaceWith("MochiCapsule", "ui.feice.mochi.shape.MochiCapsule"),
)
public val G2PillShape: Shape = MochiCapsule

/**
 * 动画里每帧变化的裁剪形状（rect 是相对于被裁剪 layer 的坐标）。
 * 按值比较：几何没变时 graphicsLayer 认为 shape 没变，不会每帧重建 outline。
 */
internal class G2RectClipShape(private val rect: Rect, private val radiusPx: Float, private val smoothing: Float) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ) = androidx.compose.ui.graphics.Outline.Generic(Path().apply { addG2RoundRect(rect, radiusPx, smoothing) })

    override fun equals(other: Any?): Boolean =
        other is G2RectClipShape && other.rect == rect && other.radiusPx == radiusPx && other.smoothing == smoothing

    override fun hashCode(): Int = (rect.hashCode() * 31 + radiusPx.hashCode()) * 31 + smoothing.hashCode()
}

/** 四角各自半径的动画裁剪形状（rect 是相对于被裁剪 layer 的坐标），按值比较 */
internal class G2RectClipShape4(
    private val rect: Rect,
    private val topLeft: Float,
    private val topRight: Float,
    private val bottomRight: Float,
    private val bottomLeft: Float,
) : Shape {
    override fun createOutline(
        size: androidx.compose.ui.geometry.Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ) = androidx.compose.ui.graphics.Outline.Generic(Path().apply { addG2RoundRect(rect, topLeft, topRight, bottomRight, bottomLeft) })

    override fun equals(other: Any?): Boolean = other is G2RectClipShape4 &&
        other.rect == rect &&
        other.topLeft == topLeft &&
        other.topRight == topRight &&
        other.bottomRight == bottomRight &&
        other.bottomLeft == bottomLeft

    override fun hashCode(): Int {
        var h = rect.hashCode()
        h = h * 31 + topLeft.hashCode()
        h = h * 31 + topRight.hashCode()
        h = h * 31 + bottomRight.hashCode()
        return h * 31 + bottomLeft.hashCode()
    }
}

/**
 * 每帧画同一个形状时复用 Path：尺寸 / 圆角没变就不重新生成曲线，只是位置变了就平移。
 * 一个缓存只给一个形状用（按钮、面板、高亮块各一个）。
 */
internal class G2PathCache {
    private val local = Path()
    private val placed = Path()
    private var w = -1f
    private var h = -1f
    private var r = -1f
    private var s = -1f
    private var x = Float.NaN
    private var y = Float.NaN

    fun get(rect: Rect, radius: Float, smoothing: Float): Path {
        if (rect.width != w || rect.height != h || radius != r || smoothing != s) {
            w = rect.width
            h = rect.height
            r = radius
            s = smoothing
            local.rewind()
            local.addG2RoundRect(Rect(0f, 0f, rect.width, rect.height), radius, smoothing)
            x = Float.NaN
        }
        if (rect.left != x || rect.top != y) {
            x = rect.left
            y = rect.top
            placed.rewind()
            placed.addPath(local, rect.topLeft)
        }
        return placed
    }
}

// —— shader 里的 SDF 只用来算阴影和融球颈，用同样「角区 1.528r」的超椭圆近似 ——

internal class G2Corner(val extent: Float, val exponent: Float)

private const val SQRT2 = 1.4142135f

internal fun g2Corner(radius: Float, halfW: Float, halfH: Float, smoothing: Float): G2Corner {
    val halfMin = min(halfW, halfH)
    if (halfMin <= 0f) return G2Corner(0f, 2f)
    val r = radius.coerceIn(0f, halfMin)
    val ext = min(r * (1f + 0.528f * smoothing.coerceIn(0f, 1f)), halfMin)
    if (ext < 1e-3f) return G2Corner(0f, 2f)
    val a = ((SQRT2 - 1f) * r / (SQRT2 * ext)).coerceIn(1e-4f, 0.29289f)
    val n = -kotlin.math.ln(2f) / kotlin.math.ln(1f - a)
    return G2Corner(ext, n)
}
