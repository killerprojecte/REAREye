// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.effect.overscroll.MochiOverscrollEffect
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.rememberMochiHaptics
import ui.feice.mochi.utils.smoothstep

/**
 * 下拉刷新的状态（UIRefreshControl）：读 [MochiOverscrollEffect] 的越界位移，拉过阈值就开始刷新
 * （和 iOS 一样不等松手），刷新中内容回弹到转圈下面停住，结束后弹簧收回。
 *
 * 用 [rememberMochiRefreshState] 创建；滚动容器传同一个 overscroll，内容用 [mochiRefreshHold] 让出停住的高度，
 * 转圈用 [MochiRefreshIndicator]。[MochiLargeTitleScaffold] / [ui.feice.mochi.navigation.MochiCompactTitleScaffold]
 * 的 onRefresh 就是这一套。
 */
@Stable
public class MochiRefreshState internal constructor(
    private val overscroll: MochiOverscrollEffect,
    private val triggerPx: Float,
    private val holdPx: Float,
    internal val rearmPx: Float,
) {
    /** 正在刷新（onRefresh 还没返回） */
    public var isRefreshing: Boolean by mutableStateOf(false)
        internal set

    /** 刷新中「停住」的程度：1 = 内容停在转圈下面，结束时弹簧收回 0 */
    internal val holding = Animatable(0f)

    /** 还没刷新时拉了多少（0..1），辐条按它一根根显出来 */
    public fun pullFraction(): Float = (overscroll.offset.y / triggerPx).coerceIn(0f, 1f)

    /**
     * 布局里多占的高度（px）：内容回弹时最低停在转圈下面（越界位移大于停住高度时不占，内容照常跟着回弹走，
     * 回到停住高度后由它接住，所以不会跳）
     */
    public fun hold(): Float = (holdPx - overscroll.offset.y.coerceAtLeast(0f)).coerceAtLeast(0f) * holding.value

    /** 转圈所在的空隙高度（px）：越界位移 + 停住高度 */
    public fun gap(): Float = overscroll.offset.y.coerceAtLeast(0f) + hold()
}

/** 下拉刷新的尺寸 */
public object MochiRefreshDefaults {
    /** 拉到这么远开始刷新 */
    public val Trigger: Dp = 96.dp

    /** 刷新中内容停在这个高度 */
    public val Hold: Dp = 56.dp

    /** 收回到这么近以内才能再次触发 */
    public val Rearm: Dp = 16.dp

    /** 转圈大小 */
    public val IndicatorSize: Dp = 24.dp
}

/**
 * 创建下拉刷新状态。[onRefresh] 为 null 时只返回状态、不触发（方便按条件开关）。
 * 触发时轻震一下（[ui.feice.mochi.utils.MochiHaptics.threshold]）。
 *
 * 滚动容器是 Lazy 列表时**一定要传 [listState]**：Lazy 列表按 key 记住第一个可见项，刷新在最前面插入的新内容
 * 会被留在视口上面，看起来什么都没刷出来。传了以后，刷新结束时列表还在最顶上就继续钉在第 0 项，
 * 新内容从顶上出现、旧内容往下让（配合 `Modifier.animateItem()` 平滑移动）；刷新期间用户自己往下滚了就不动。
 * 新数据要在 [onRefresh] 返回前写进列表。
 */
@Composable
public fun rememberMochiRefreshState(
    overscroll: MochiOverscrollEffect,
    listState: LazyListState? = null,
    onRefresh: (suspend () -> Unit)?,
): MochiRefreshState {
    val density = LocalDensity.current
    val state = remember(overscroll, density) {
        with(density) {
            MochiRefreshState(
                overscroll,
                triggerPx = MochiRefreshDefaults.Trigger.toPx(),
                holdPx = MochiRefreshDefaults.Hold.toPx(),
                rearmPx = MochiRefreshDefaults.Rearm.toPx(),
            )
        }
    }
    if (onRefresh == null) return state
    val current by rememberUpdatedState(onRefresh)
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) {
        var armed = true
        snapshotFlow { overscroll.offset.y }.collect { y ->
            // 收回到一小段以内才能再次触发，免得刷新刚结束、手还拉着又触发一次
            if (y < state.rearmPx) armed = true
            // 只认手指拉过阈值：快速一甩撞到顶的回弹位移再大也不触发（iOS 也是这样）
            if (armed && !state.isRefreshing && overscroll.dragging && state.pullFraction() >= 1f) {
                armed = false
                state.isRefreshing = true
                haptics.threshold()
                scope.launch {
                    state.holding.snapTo(1f)
                    try {
                        current()
                        // 还在顶上：钉住第 0 项，最前面插进来的新内容才看得见（下一次排版生效，不跳）
                        listState?.let {
                            if (it.firstVisibleItemIndex == 0 &&
                                it.firstVisibleItemScrollOffset == 0
                            ) {
                                it.requestScrollToItem(0)
                            }
                        }
                    } finally {
                        state.holding.animateTo(0f, MochiMotions.responseSpring(0.4f, 1f))
                        state.isRefreshing = false
                    }
                }
            }
        }
    }
    return state
}

/** 刷新中让内容往下让出停住的高度（摆放阶段读，不重组） */
public fun Modifier.mochiRefreshHold(state: MochiRefreshState): Modifier = layout { m, c ->
    val p = m.measure(c)
    val h = state.hold().roundToInt()
    layout(p.width, p.height + h) { p.place(0, h) }
}

/**
 * 下拉刷新的转圈：放在滚动内容的上沿处（和它同一个顶边），自己在「上沿」和「被拉下来的内容」之间居中，
 * 拉的时候辐条一根根显出来，刷新中转起来。
 */
@Composable
public fun MochiRefreshIndicator(
    state: MochiRefreshState,
    modifier: Modifier = Modifier,
    color: Color = MochiProgressDefaults.activityColor(),
) {
    MochiActivityIndicator(
        modifier.graphicsLayer {
            val gap = state.gap()
            translationY = gap / 2f - MochiRefreshDefaults.IndicatorSize.toPx() / 2f
            alpha = smoothstep(8.dp.toPx(), 28.dp.toPx(), gap)
        },
        size = MochiRefreshDefaults.IndicatorSize,
        color = color,
        reveal = if (state.isRefreshing) null else ({ state.pullFraction() }),
    )
}
