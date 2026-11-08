// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import androidx.activity.BackEventCompat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import ui.feice.mochi.effect.glass.source

/**
 * 全屏右滑返回（iOS 26）：手指在页面任意位置往右划，页面跟着手走；松手时只要还在起点右边就按速度判断：
 * 往左甩得够快就弹回去，往右或停住就返回。
 * - 只看子元素没消费的触摸：横向滚动、滑块、左滑行、开关这些自己处理横向拖动的元素照常，竖着划照常滚动
 * - 侧推页：页面 1:1 跟手往右滑出，露出下面那页；从来源打开的页：和预测性返回一样跟手缩小，返回时缩回来源
 * - 只有完全打开、没在转场的栈顶页才接这个手势
 */
internal suspend fun PointerInputScope.fullSwipeBack(e: MochiNavEntry, geo: NavGeometry, scope: CoroutineScope) {
    val nav = geo.nav
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val top = nav.entries.lastOrNull { !it.closing }
        // 打开途中也能直接往回划（不等转场停稳），只有正在关闭或已经在拖的不接
        if (top !== e || nav.under(e) == null || e.closing || e.backActive || !nav.canPop) return@awaitEachGesture
        val slop = viewConfiguration.touchSlop
        val w = size.width.toFloat().coerceAtLeast(1f)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var dx = 0f
        var dy = 0f
        var claimed = false
        var base = 1f
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            if (!claimed) {
                // 子元素已经接手（横向滚动、滑块……）：不抢
                if (ch.isConsumed) return@awaitEachGesture
                dx += ch.position.x - ch.previousPosition.x
                dy += ch.position.y - ch.previousPosition.y
                if (abs(dy) > slop && abs(dy) >= abs(dx)) return@awaitEachGesture
                if (dx < -slop) return@awaitEachGesture
                if (dx > slop && dx > abs(dy) * 1.2f) {
                    // 接手的这一下就消费掉：外层同样看「没被消费的拖动」的手势（侧边栏、外层导航）不会在同一个事件里一起接手
                    ch.consume()
                    claimed = true
                    // 从此刻的打开进度接着往回拖，不先跳到完全打开
                    base = e.progress.value.coerceIn(0f, 1f)
                    // 侧推页的进度改由手指控制；从来源打开的页拖的是 back，打开动画照常走完
                    if (e.source == null) scope.launch { e.progress.stop() }
                    e.backActive = true
                    e.backEdge = BackEventCompat.EDGE_LEFT
                    dx = 0f
                    dy = 0f
                }
            } else {
                // 连同这一帧里攒下的历史点一起算速度：快速往回甩的几个点常挤在同一帧里
                tracker.addPointerInputChange(ch)
                ch.consume()
                dx += ch.position.x - ch.previousPosition.x
                dy += ch.position.y - ch.previousPosition.y
                val x = dx.coerceAtLeast(0f)
                if (e.source == null) {
                    scope.launch { e.progress.snapTo((base - x / w).coerceIn(0f, 1f)) }
                } else {
                    e.backDy = dy
                    scope.launch { e.back.snapTo((x / (w * FullSwipeSourceSpan)).coerceIn(0f, 1f)) }
                }
            }
        }
        if (!claimed) return@awaitEachGesture
        val v = tracker.calculateVelocity().x
        val back = e.source == null
        // 只要还在起点右边就看松手速度：明显往左甩是取消，往右或停住都返回（不用先划过一半）
        val commit = dx > 0f && v > -FullSwipeCancelVelocity.toPx()
        e.backActive = false
        if (commit) {
            if (back) e.releaseVelocity = -(v / w).coerceAtLeast(0f)
            nav.pop()
        } else if (back) {
            scope.launch { e.progress.animateTo(1f, geo.spec.push, initialVelocity = -v / w) }
        } else {
            scope.launch { e.back.animateTo(0f, geo.spec.cancel) }
        }
    }
}

/** 从来源打开的页：手指划过屏幕宽的这么多，缩小到底 */
private const val FullSwipeSourceSpan = 0.7f

/** 松手时往左快过这个就取消返回 */
private val FullSwipeCancelVelocity = 150.dp
