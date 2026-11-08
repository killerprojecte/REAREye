// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import ui.feice.mochi.shape.drawG2RoundRect
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.rememberMochiHaptics

/** 页面指示器的颜色 */
@Immutable
public data class MochiPageControlColors(
    val active: Color,
    val inactive: Color,
    /** 按住拖动时出现的底衬 */
    val track: Color,
)

/** 页面指示器的默认值 */
public object MochiPageControlDefaults {
    /** 点的直径 */
    public val DotSize: Dp = 8.dp

    /** 当前页拉长后的宽度 */
    public val ActiveWidth: Dp = 20.dp

    /** 相邻两点中心的距离 */
    public val Pitch: Dp = 18.dp

    /** 整条的高度（也是按住拖动的热区高度） */
    public val Height: Dp = 28.dp

    /** 最多露出几个点 */
    public const val MaxVisible: Int = 7

    @Composable
    public fun colors(
        active: Color = MochiTheme.colorScheme.onSurface,
        inactive: Color = MochiTheme.colorScheme.onSurface.copy(alpha = 0.25f),
        track: Color = MochiDefaults.fill(),
    ): MochiPageControlColors = remember(active, inactive, track) { MochiPageControlColors(active, inactive, track) }
}

/**
 * 页面指示器（iOS 的 UIPageControl）：
 * - 当前页的点拉长成胶囊，翻页时长度在相邻两点之间连续转移（跟着 [position] 走，不跳）
 * - 点左半边 / 右半边 = 上一页 / 下一页
 * - 按住后左右拖动快速翻页（底衬出现、整条轻微放大），每换一页轻震一下
 * - 页数超过 [maxVisible] 时只露出一段，两端的点缩小
 *
 * @param position 带小数的当前页（比如 PagerState 的 currentPage + currentPageOffsetFraction）
 */
@Composable
public fun MochiPageControl(
    pageCount: Int,
    position: () -> Float,
    onPageSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxVisible: Int = MochiPageControlDefaults.MaxVisible,
    colors: MochiPageControlColors = MochiPageControlDefaults.colors(),
) {
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)
    val select by rememberUpdatedState(onPageSelect)
    val scrub = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val visible = minOf(pageCount, maxVisible)
    val scratch = remember { Path() }
    val width = MochiPageControlDefaults.Pitch * visible + 16.dp
    Canvas(
        modifier
            .width(width)
            .height(MochiPageControlDefaults.Height)
            .semantics { contentDescription = "第 ${position().toInt() + 1} 页，共 $pageCount 页" }
            .graphicsLayer {
                val s = 1f + 0.08f * scrub.value
                scaleX = s
                scaleY = s
            }
            .pointerInput(pageCount, maxVisible) {
                val pitch = MochiPageControlDefaults.Pitch.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // 按住 300ms 不动进入拖动选页；先松手就是点按
                    // true = 松手（点按），false = 移动太多（交给外面的滚动），null = 按住够久（拖动选页）
                    var up: Boolean? = null
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis.coerceAtMost(300L)) {
                        while (up == null) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (c == null || !c.pressed) {
                                up = true
                            } else if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                up = false
                            }
                        }
                    }
                    if (up == true) {
                        val cur = position().let { kotlin.math.round(it).toInt() }
                        val next = if (down.position.x < size.width / 2f) cur - 1 else cur + 1
                        if (next in 0 until pageCount) {
                            haptics.tick()
                            select(next)
                        }
                        return@awaitEachGesture
                    }
                    if (up == false) return@awaitEachGesture
                    scope.launch { scrub.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 600f)) }
                    haptics.longPress()
                    var last = kotlin.math.round(position()).toInt()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        val i = PageControlGeometry.indexAt(c.position.x - size.width / 2f, position(), pageCount, maxVisible, pitch)
                        if (i != last) {
                            last = i
                            haptics.tick()
                            select(i)
                        }
                    }
                    scope.launch { scrub.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f)) }
                }
            },
    ) {
        val pos = position().coerceIn(0f, (pageCount - 1).coerceAtLeast(0).toFloat())
        val dot = MochiPageControlDefaults.DotSize.toPx()
        val active = MochiPageControlDefaults.ActiveWidth.toPx()
        val pitch = MochiPageControlDefaults.Pitch.toPx()
        val sc = scrub.value
        if (sc > 0f) {
            drawG2RoundRect(scratch, Rect(Offset.Zero, size), size.height / 2f, colors.track, alpha = sc.coerceIn(0f, 1f))
        }
        val start = PageControlGeometry.windowStart(pos, pageCount, maxVisible)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val first = (start - 1f).toInt().coerceAtLeast(0)
        val last = (start + visible + 1f).toInt().coerceAtMost(pageCount - 1)
        for (i in first..last) {
            val s = PageControlGeometry.dotScale(i, pos, pageCount, maxVisible)
            if (s <= 0f) continue
            val w = PageControlGeometry.dotWidth(i, pos, dot, active) * s
            val h = dot * s
            val x = cx + (i - start - (visible - 1) / 2f) * pitch
            val t = (1f - kotlin.math.abs(i - pos)).coerceIn(0f, 1f)
            val color = androidx.compose.ui.graphics.lerp(colors.inactive, colors.active, t)
            drawG2RoundRect(scratch, Rect(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f), h / 2f, color)
        }
    }
}

/** 直接接 [PagerState]：点按 / 拖动选页时让 pager 翻过去（点按用动画，拖动时直接跳） */
@Composable
public fun MochiPageControl(
    state: PagerState,
    modifier: Modifier = Modifier,
    maxVisible: Int = MochiPageControlDefaults.MaxVisible,
    colors: MochiPageControlColors = MochiPageControlDefaults.colors(),
) {
    val scope = rememberCoroutineScope()
    MochiPageControl(
        pageCount = state.pageCount,
        position = { state.currentPage + state.currentPageOffsetFraction },
        onPageSelect = { scope.launch { coroutineScope { state.animateScrollToPage(it) } } },
        modifier = modifier,
        maxVisible = maxVisible,
        colors = colors,
    )
}
