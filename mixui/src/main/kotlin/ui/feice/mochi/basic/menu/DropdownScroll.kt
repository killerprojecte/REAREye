// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic.menu

import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import ui.feice.mochi.effect.overscroll.FlingStopVelocity
import ui.feice.mochi.effect.overscroll.MochiFlingBehavior
import ui.feice.mochi.effect.overscroll.MochiOverscrollSpec
import ui.feice.mochi.effect.overscroll.MochiScrollDefaults
import ui.feice.mochi.effect.overscroll.cresto.OffsetOverscrollEffect
import ui.feice.mochi.gesture.MochiController

/** 自绘菜单把可滚动范围与视觉位移分开，越界物理与普通列表共同使用 Cresto 的实现。 */
internal class DropdownScroller(private val controller: MochiController, private val scope: CoroutineScope, density: Density) {
    private var position = 0f
    private var job: Job? = null
    private var owner = 0
    private var speed = 0f
    private val spec = MochiOverscrollSpec()
    private val stopVelocity = with(density) { FlingStopVelocity.toPx() }

    // 允许低速尾段被点选接管，与惯性自然结束的阈值分开。
    private val tapStopVelocity = with(density) { 60.dp.toPx() }
    private val settleDistance = with(density) { 0.5.dp.toPx() }
    private val effect = OffsetOverscrollEffect(
        animationSpec = { spring(spec.dampingRatio, spec.stiffness, 0.5f) },
        maxFraction = { spec.rubberBand },
        animationScope = { scope },
        onOffsetChanged = { controller.scroll = position - it.y },
    )

    /** 自绘菜单没有 Compose 滚动节点，由实际面板测量提供视口。 */
    var viewport: Float
        get() = effect.viewport.height
        set(value) {
            effect.viewport = Size(1f, value.coerceAtLeast(1f))
        }

    val isMoving: Boolean get() = job?.isActive == true

    private fun publish() {
        controller.scroll = position - effect.visibleOffset.y
    }

    private fun consume(delta: Offset): Offset {
        val before = position
        position = (position - delta.y).coerceIn(0f, controller.maxScroll.coerceAtLeast(0f))
        publish()
        return Offset(0f, before - position)
    }

    fun snapTo(value: Float) {
        stop()
        position = value.coerceIn(0f, controller.maxScroll.coerceAtLeast(0f))
        effect.reset()
        publish()
    }

    fun scrollByClamped(delta: Float) {
        position = (position + delta).coerceIn(0f, controller.maxScroll.coerceAtLeast(0f))
        effect.reset()
        publish()
    }

    /** 新的按下接住明显运动；低速尾段结束后不拦截点击。 */
    fun stop(): Boolean {
        val moving = isMoving && (abs(speed) > tapStopVelocity || abs(effect.visibleOffset.y) > settleDistance)
        owner++
        job?.cancel()
        speed = 0f
        return moving
    }

    fun settle() {
        if (effect.visibleOffset != Offset.Zero) fling(0f)
    }

    fun dragBy(dy: Float) {
        effect.applyToScroll(Offset(0f, dy), NestedScrollSource.UserInput, ::consume)
    }

    /** v 是滚动速度（px/s，滚动位置增大为正）；Cresto 使用手指位移的符号。 */
    fun fling(v: Float) {
        val me = ++owner
        job?.cancel()
        speed = v
        job = scope.launch {
            val flingBehavior = MochiFlingBehavior(MochiScrollDefaults.DecelerationDefault, stopVelocity) {
                // 已取消的旧惯性不能覆盖新手势的速度，低速点击判断必须使用当前实际速度。
                if (owner == me) speed = it
            }
            try {
                effect.applyToFling(Velocity(0f, -v)) { available ->
                    val scrollScope = object : ScrollScope {
                        override fun scrollBy(pixels: Float): Float =
                            -effect.applyToScroll(Offset(0f, -pixels), NestedScrollSource.SideEffect, ::consume).y
                    }
                    val left = with(flingBehavior) { scrollScope.performFling(-available.y) }
                    available - Velocity(0f, -left)
                }
            } finally {
                if (owner == me) speed = 0f
            }
        }
    }
}
