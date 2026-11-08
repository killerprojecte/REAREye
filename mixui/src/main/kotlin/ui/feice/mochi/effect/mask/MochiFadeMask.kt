// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.mask

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 渐变遮罩每条边的长度（0 = 这条边不遮）。start / end 按阅读方向，RTL 下自动对调。
 */
@Immutable
public data class MochiFadeEdges(val top: Dp = 0.dp, val bottom: Dp = 0.dp, val start: Dp = 0.dp, val end: Dp = 0.dp) {
    public companion object {
        /** 上下两边 */
        public fun vertical(extent: Dp = MochiFadeMaskDefaults.Extent): MochiFadeEdges = MochiFadeEdges(top = extent, bottom = extent)

        /** 左右两边 */
        public fun horizontal(extent: Dp = MochiFadeMaskDefaults.Extent): MochiFadeEdges = MochiFadeEdges(start = extent, end = extent)

        /** 四边 */
        public fun all(extent: Dp = MochiFadeMaskDefaults.Extent): MochiFadeEdges = MochiFadeEdges(extent, extent, extent, extent)
    }
}

/** 每条边此刻遮多深（0..1，在绘制阶段读，可以每帧变，不会触发重组） */
public fun interface MochiFadeAmount {
    public fun amount(edge: MochiFadeEdge): Float
}

/** 哪一条边 */
public enum class MochiFadeEdge { Top, Bottom, Start, End }

public object MochiFadeMaskDefaults {
    /** 默认渐隐长度 */
    public val Extent: Dp = 24.dp

    /** 四边都按满强度遮 */
    public val Full: MochiFadeAmount = MochiFadeAmount { 1f }
}

/**
 * 通用的容器内渐变遮罩：把这个区域**自己画出来的内容**在指定的边上渐隐成透明（不是盖一层底色），
 * 所以放在任何底上（卡片、图片、渐变、毛玻璃）都对，形状、圆角、阴影都不受影响。
 *
 * - 渐隐曲线是平滑的 S 形（smoothstep 分成多段色标），没有线性渐变那条看得见的「起点线」
 * - 每条边的深浅可以每帧变（[amount]），比如跟着滚动距离：滚到头那一边就是实的
 * - 主题有 [ui.feice.mochi.theme.MochiMaterials.scrollEdge]（iOS 外观）时，同一套边和强度也用来渐进模糊：越靠边越糊（Android 13+）
 *
 * 可滚动区域请用 [mochiScrollFadeMask]，它按滚动位置自动算每条边的强度。
 */
public fun Modifier.mochiFadeMask(
    edges: MochiFadeEdges = MochiFadeEdges.vertical(),
    amount: MochiFadeAmount = MochiFadeMaskDefaults.Full,
): Modifier = this
    // 主题有滚动边缘效果（iOS 外观）时，边上同时渐进模糊（Android 13+），见 FadeBlurElement；
    // 模糊图层也只在有边要遮时才挂，挂上时同样按矩形裁切
    .then(FadeBlurElement(edges, amount))
    // 离屏合成：DstOut 只擦掉这一层自己的内容，不会把下面的东西也擦出洞来。
    // 离屏缓冲按这一层的矩形裁切，所以只在真的有边要遮时才开：不遮时（菜单放得下、滚到头）
    // 内容可以照常画出矩形外（跟着形变平移、放大），不会被一个看不见的方框切掉
    .graphicsLayer {
        (amount as? ScrollFadeAmount)?.density = density
        val active = (edges.top.value > 0f && amount.amount(MochiFadeEdge.Top) > 0f) ||
            (edges.bottom.value > 0f && amount.amount(MochiFadeEdge.Bottom) > 0f) ||
            (edges.start.value > 0f && amount.amount(MochiFadeEdge.Start) > 0f) ||
            (edges.end.value > 0f && amount.amount(MochiFadeEdge.End) > 0f)
        compositingStrategy = if (active) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        (amount as? ScrollFadeAmount)?.density = density
        val rtl = layoutDirection == LayoutDirection.Rtl
        fade(MochiFadeEdge.Top, edges.top.toPx(), amount.amount(MochiFadeEdge.Top))
        fade(MochiFadeEdge.Bottom, edges.bottom.toPx(), amount.amount(MochiFadeEdge.Bottom))
        // start / end 按阅读方向：RTL 时 start 在右边
        val startPx = edges.start.toPx()
        val endPx = edges.end.toPx()
        val startAmount = amount.amount(MochiFadeEdge.Start)
        val endAmount = amount.amount(MochiFadeEdge.End)
        fadeSide(left = true, px = if (rtl) endPx else startPx, a = if (rtl) endAmount else startAmount)
        fadeSide(left = false, px = if (rtl) startPx else endPx, a = if (rtl) startAmount else endAmount)
    }

/**
 * 可滚动区域的渐变遮罩：还能往那边滚，那一边就渐隐；滚到头，那一边就是实的。
 * 只要那一边还有被裁掉的内容就遮满；滚到离头只剩渐隐长度的 1/4 时才平滑变浅，不会一下出现、一下消失，也不会只遮一半露出裁切线。
 * 加在 `verticalScroll(state)` / `horizontalScroll(state)` 的**外面**。
 *
 * @param vertical true = 上下两边，false = 左右两边
 */
public fun Modifier.mochiScrollFadeMask(state: ScrollState, extent: Dp = MochiFadeMaskDefaults.Extent, vertical: Boolean = true): Modifier =
    mochiFadeMask(
        if (vertical) MochiFadeEdges.vertical(extent) else MochiFadeEdges.horizontal(extent),
        ScrollFadeAmount(extent.value, vertical, { state.value.toFloat() }, { (state.maxValue - state.value).toFloat() }),
    )

/** [LazyColumn] / [LazyRow] 的渐变遮罩，规则同 [mochiScrollFadeMask] */
public fun Modifier.mochiScrollFadeMask(
    state: LazyListState,
    extent: Dp = MochiFadeMaskDefaults.Extent,
    vertical: Boolean = true,
): Modifier = mochiFadeMask(
    if (vertical) MochiFadeEdges.vertical(extent) else MochiFadeEdges.horizontal(extent),
    ScrollFadeAmount(
        extent.value,
        vertical,
        // 第一项之后都算「离顶边很远」；在第一项里时按偏移连续变
        { if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE else state.firstVisibleItemScrollOffset.toFloat() },
        {
            lazyRemaining(
                state.layoutInfo.visibleItemsInfo.lastOrNull()?.let {
                    it.index to (it.offset + it.size)
                },
                state.layoutInfo.totalItemsCount,
                state.layoutInfo.viewportEndOffset,
            )
        },
    ),
)

/** [LazyVerticalGrid] 的渐变遮罩（上下两边），规则同 [mochiScrollFadeMask] */
public fun Modifier.mochiScrollFadeMask(state: LazyGridState, extent: Dp = MochiFadeMaskDefaults.Extent): Modifier = mochiFadeMask(
    MochiFadeEdges.vertical(extent),
    ScrollFadeAmount(
        extent.value,
        true,
        { if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE else state.firstVisibleItemScrollOffset.toFloat() },
        {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()
            lazyRemaining(
                last?.let {
                    it.index to (it.offset.y + it.size.height)
                },
                state.layoutInfo.totalItemsCount,
                state.layoutInfo.viewportEndOffset,
            )
        },
    ),
)

/** 最后一项离视口底边还有多远：不是最后一项时算「很远」 */
private fun lazyRemaining(last: Pair<Int, Int>?, total: Int, viewportEnd: Int): Float {
    if (last == null) return 0f
    if (last.first < total - 1) return Float.MAX_VALUE
    return (last.second - viewportEnd).coerceAtLeast(0).toFloat()
}

/** 按「往前 / 往后还能滚多少 px」给每条边的深浅；extentDp 在绘制时换成 px */
internal class ScrollFadeAmount(
    private val extentDp: Float,
    private val vertical: Boolean,
    private val before: () -> Float,
    private val after: () -> Float,
) : MochiFadeAmount {
    var density = 1f

    override fun amount(edge: MochiFadeEdge): Float {
        // 被裁掉的边必须遮满：深浅只在离边最后一小段（渐隐长度的 1/4）里过渡。
        // 按整段渐隐长度成比例时，内容只多出半行就只遮了一半，裁切线清清楚楚露在外面
        val ramp = (extentDp * density * RampFraction).coerceAtLeast(1f)
        fun of(px: Float) = MochiSmoothGradient.smoothstep(px / ramp)
        return when (edge) {
            MochiFadeEdge.Top -> if (vertical) of(before()) else 0f
            MochiFadeEdge.Bottom -> if (vertical) of(after()) else 0f
            MochiFadeEdge.Start -> if (!vertical) of(before()) else 0f
            MochiFadeEdge.End -> if (!vertical) of(after()) else 0f
        }
    }
}

private fun DrawScope.fade(edge: MochiFadeEdge, px: Float, a: Float) {
    if (px <= 0f || a <= 0f) return
    val e = px.coerceAtMost(size.height / 2f)
    if (edge == MochiFadeEdge.Top) {
        drawRect(
            eased(a, vertical = true, from = 0f, to = e, solidFirst = true),
            Offset.Zero,
            Size(size.width, e),
            blendMode = BlendMode.DstOut,
        )
    } else {
        drawRect(
            eased(a, vertical = true, from = size.height - e, to = size.height, solidFirst = false),
            Offset(0f, size.height - e),
            Size(size.width, e),
            blendMode = BlendMode.DstOut,
        )
    }
}

private fun DrawScope.fadeSide(left: Boolean, px: Float, a: Float) {
    if (px <= 0f || a <= 0f) return
    val e = px.coerceAtMost(size.width / 2f)
    if (left) {
        drawRect(
            eased(a, vertical = false, from = 0f, to = e, solidFirst = true),
            Offset.Zero,
            Size(e, size.height),
            blendMode = BlendMode.DstOut,
        )
    } else {
        drawRect(
            eased(a, vertical = false, from = size.width - e, to = size.width, solidFirst = false),
            Offset(size.width - e, 0f),
            Size(e, size.height),
            blendMode = BlendMode.DstOut,
        )
    }
}

/**
 * 擦除用的渐变（DstOut：越黑擦得越多）。按 smoothstep 取几个色标：两端都平滑接上，
 * 不会在渐隐开始的地方看到一条线
 */
private fun eased(a: Float, vertical: Boolean, from: Float, to: Float, solidFirst: Boolean): Brush {
    val stops = Array(Stops + 1) { i ->
        val t = i / Stops.toFloat()
        // 从边上（全擦）到里面（不擦）
        val k = 1f - MochiSmoothGradient.smoothstep(t)
        val pos = if (solidFirst) t else 1f - t
        pos to Color.Black.copy(alpha = a * k)
    }.sortedBy { it.first }.toTypedArray()
    return if (vertical) {
        Brush.verticalGradient(
            *stops,
            startY = from,
            endY = to,
        )
    } else {
        Brush.horizontalGradient(*stops, startX = from, endX = to)
    }
}

private const val Stops = MochiSmoothGradient.Stops

/** 滚动遮罩从无到满的距离占渐隐长度的比例 */
private const val RampFraction = 0.25f
