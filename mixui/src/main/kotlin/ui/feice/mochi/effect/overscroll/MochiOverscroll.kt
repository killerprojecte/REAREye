// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.effect.overscroll

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ln
import ui.feice.mochi.effect.overscroll.cresto.OffsetOverscrollEffect

/**
 * Cresto（Kyant & Nevoit）越界回弹参数。
 * @param rubberBand 上游 maxFraction：指数阻力系数，越小越拉不动
 * @param stiffness 回弹弹簧刚度；上游默认为 150
 * @param dampingRatio 回弹阻尼比；上游默认为 1
 */
@Immutable
public data class MochiOverscrollSpec(val rubberBand: Float = 0.65f, val stiffness: Float = 150f, val dampingRatio: Float = 1f)

/** 保留 Mochi API，滚动消耗、回弹、惯性交接和显示曲线全部交给 Cresto 的 OffsetOverscrollEffect。 */
@Stable
public class MochiOverscrollEffect(spec: MochiOverscrollSpec = MochiOverscrollSpec()) : OverscrollEffect {
    public var spec: MochiOverscrollSpec by mutableStateOf(spec)

    private val delegate = OffsetOverscrollEffect(
        animationSpec = { spring(this.spec.dampingRatio, this.spec.stiffness, 0.5f) },
        maxFraction = { this.spec.rubberBand },
    )

    /** 图层实际显示的越界位移（px），供大标题和下拉刷新联动。 */
    public val offset: Offset get() = delegate.visibleOffset

    /** 越界是不是手指正拖着：松手后的惯性撞到边、回弹都是 false。下拉刷新只认手指拉出来的位移 */
    internal var dragging: Boolean by mutableStateOf(false)
        private set

    // 沿用上游的 false：回弹视觉动画不会让新的点击被当成“仅停止滚动”。
    override val isInProgress: Boolean get() = delegate.isInProgress
    override val node: DelegatableNode get() = delegate.node

    // mochiContainedScroll 挡在外层前面吃掉的剩余：只在自己的滚动、惯性进行中记，交回 Cresto 当成没被消耗，照常回弹
    private var scrolling = false
    private var flinging = false
    private var heldScroll = Offset.Zero
    private var heldFling = Velocity.Zero

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        dragging = source == NestedScrollSource.UserInput
        return delegate.applyToScroll(delta, source) { d ->
            scrolling = true
            heldScroll = Offset.Zero
            val consumed = try {
                performScroll(d)
            } finally {
                scrolling = false
            }
            consumed - heldScroll
        }
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        dragging = false
        delegate.applyToFling(velocity) { v ->
            flinging = true
            heldFling = Velocity.Zero
            val consumed = try {
                performFling(v)
            } finally {
                flinging = false
            }
            consumed - heldFling
        }
    }

    /** [mochiContainedScroll] 挂的连接：吃掉内层滚到头剩下的位移和速度，外层收不到 */
    internal val containment: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (scrolling) heldScroll += available
            return available
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            if (flinging) heldFling += available
            return available
        }
    }
}

/**
 * 嵌在可滚页面里的滚动区域（预览卡片、卡片里的列表）：滚到头时拖动和惯性都不往外传，外面的页面不跟着动，
 * 剩下的位移和速度交给这块区域自己的 [effect] 拉出越界、回弹（iOS 里套在页面中的 UIScrollView 也是这样）。
 * 挂在滚动容器外面，[effect] 同时传给容器的 `overscrollEffect`：
 *
 * ```kotlin
 * val overscroll = rememberMochiOverscrollEffect()
 * Column(Modifier.height(320.dp).mochiContainedScroll(overscroll).verticalScroll(state, overscrollEffect = overscroll)) { … }
 * LazyColumn(Modifier.mochiContainedScroll(overscroll), overscrollEffect = overscroll) { … }
 * ```
 */
public fun Modifier.mochiContainedScroll(effect: MochiOverscrollEffect): Modifier = nestedScroll(effect.containment)

/** 为子树中的滚动容器创建 Cresto 回弹效果。 */
@Immutable
public class MochiOverscrollFactory(public val spec: MochiOverscrollSpec = MochiOverscrollSpec()) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = MochiOverscrollEffect(spec)
    override fun equals(other: Any?): Boolean = other is MochiOverscrollFactory && other.spec == spec
    override fun hashCode(): Int = spec.hashCode()
}

/** 让子树中的 Lazy 列表、verticalScroll / horizontalScroll 使用 Cresto 越界回弹。 */
@Composable
public fun ProvideMochiOverscroll(spec: MochiOverscrollSpec = MochiOverscrollSpec(), content: @Composable () -> Unit) {
    val factory = remember(spec) { MochiOverscrollFactory(spec) }
    CompositionLocalProvider(LocalOverscrollFactory provides factory, content = content)
}

/** 单个列表使用的 Cresto 越界回弹。参数变化保留同一个节点。 */
@Composable
public fun rememberMochiOverscrollEffect(spec: MochiOverscrollSpec = MochiOverscrollSpec()): MochiOverscrollEffect {
    val effect = remember { MochiOverscrollEffect(spec) }
    effect.spec = spec
    return effect
}

public object MochiScrollDefaults {
    /** 默认自然减速：每毫秒保留 0.998 的速度，与 Normal 一致 */
    public const val DecelerationDefault: Float = 0.998f

    /** UIScrollView.DecelerationRate.normal：每毫秒速度 × 0.998 */
    public const val DecelerationNormal: Float = 0.998f

    /** UIScrollView.DecelerationRate.fast：每毫秒速度 × 0.99 */
    public const val DecelerationFast: Float = 0.99f

    /**
     * 指数惯性减速。自然停止时不再触发回弹；滚到边缘时才把剩余速度交给越界回弹。
     * @param decelerationRate 每毫秒的速度保留比例
     */
    @Composable
    public fun flingBehavior(decelerationRate: Float = DecelerationDefault): FlingBehavior {
        val stop = with(LocalDensity.current) { FlingStopVelocity.toPx() }
        return remember(decelerationRate, stop) { MochiFlingBehavior(decelerationRate, stop) }
    }
}

/**
 * 接近静止才结束；默认减速率下剩余路程约 0.5dp，避免在仍明显滑动时突然截断。
 */
internal val FlingStopVelocity = 1.dp

internal class MochiFlingBehavior(rate: Float, private val stopVelocity: Float, private val onVelocityChanged: (Float) -> Unit = {}) :
    FlingBehavior {
    // exponentialDecay：v(t) = v0·e^(−4.2·m·t)；要 v0·rate^(1000·t)，所以 m = −1000·ln(rate) / 4.2
    private val decay = exponentialDecay<Float>(
        frictionMultiplier = -1000f * ln(rate.coerceIn(0.9f, 0.9999f)) / 4.2f,
        absVelocityThreshold = stopVelocity,
    )

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) <= stopVelocity) {
            onVelocityChanged(0f)
            return 0f
        }
        var left = 0f
        var last = 0f
        onVelocityChanged(initialVelocity)
        try {
            AnimationState(0f, initialVelocity).animateDecay(decay) {
                val d = value - last
                val consumed = scrollBy(d)
                last = value
                onVelocityChanged(velocity)
                // 自然减速完成返回 0；只有真正撞到边缘才返回残速，防止列表停下后又冒出一段回弹。
                if (abs(d - consumed) > 0.5f) {
                    left = velocity
                    cancelAnimation()
                }
            }
        } finally {
            onVelocityChanged(0f)
        }
        return left
    }
}
