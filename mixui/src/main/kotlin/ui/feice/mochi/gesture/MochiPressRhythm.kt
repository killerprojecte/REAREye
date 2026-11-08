// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.gesture

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.traverseAncestors

/**
 * 按压视觉反馈（按压形变、按压高光）共用的触摸节奏，解决「谁先抢到这一按」：
 *
 * - **不在滚动容器里**：按下立刻反馈，松手恢复（和以前一样，按钮要跟手）
 * - **在滚动容器里**（列表、网格里的卡片和行）：和 iOS 的 delaysContentTouches、Compose clickable 一样，
 *   按下先等 [PressDelayMillis]，手指没动才开始反馈，所以只是想滚动时卡片不会闪一下；
 *   一旦滚动接手（移动被上层消费），反馈立刻收回，不会一边滚一边保持按下的样子
 * - **快速轻点**（还没到延迟就松手）：照样补一次完整的按下 + 回弹，并保证按下至少停留 [TapPulseMillis]，
 *   和点击触发的转场同时进行，不会因为太快而什么反馈都没有
 *
 * 回调都在触摸协程里同步调用；[onRelease] 的参数是还需要保持按下多久（毫秒，0 = 立刻），由调用方延迟后再松开。
 */
internal suspend fun AwaitPointerEventScope.awaitMochiPress(
    inScrollContainer: Boolean,
    onPress: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onRelease: (holdMillis: Long) -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var pressed = false
    var last = down.position
    if (!inScrollContainer) {
        pressed = true
        onPress(down.position)
    }
    while (true) {
        val waited = if (!pressed) {
            val remaining = PressDelayMillis - ((currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis) - down.uptimeMillis)
            if (remaining <= 0L) {
                pressed = true
                onPress(last)
                null
            } else {
                withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Final) } ?: run {
                    pressed = true
                    onPress(last)
                    null
                }
            }
        } else {
            null
        }
        // Final 阶段：上层的滚动在 Main 阶段消费了移动，这里才看得到
        val event = waited ?: awaitPointerEvent(PointerEventPass.Final)
        val c = event.changes.firstOrNull { it.id == down.id } ?: break
        last = c.position
        if (!c.pressed) {
            if (pressed) {
                onRelease(0L)
            } else {
                // 快速轻点：补一次按下，至少停留一小会儿再回弹
                onPress(last)
                onRelease(TapPulseMillis)
            }
            return
        }
        if (inScrollContainer && c.isConsumed && c.positionChanged()) {
            // 滚动接手了：收回反馈，这一按不再算
            if (pressed) onRelease(0L)
            return
        }
        if (pressed) onMove(c.position)
    }
    if (pressed) onRelease(0L)
}

/** 这个节点是否在一个可滚动容器（LazyColumn、verticalScroll……）里面 */
internal fun DelegatableNode.isInScrollContainer(): Boolean {
    val key = ScrollContainerKey ?: return false
    var found = false
    traverseAncestors(key) {
        found = true
        false
    }
    return found
}

/**
 * Compose foundation 的滚动容器节点用来让子节点找到它的 key（clickable 判断「要不要延迟按下」用的就是它）。
 * 它在 foundation 里是 internal，这里按名字取一次；取不到（版本变了）就当不在滚动容器里，行为退回立刻反馈。
 * consumer-rules.pro 里保留了这个字段，开了混淆的应用也能取到。
 */
private val ScrollContainerKey: Any? by lazy {
    runCatching {
        Class.forName("androidx.compose.foundation.gestures.ScrollableContainerNode").getField("TraverseKey").get(null)
    }.getOrNull()
}

/** 在滚动容器里按下后等多久才开始反馈（和 Android 的 tap timeout 同量级） */
internal const val PressDelayMillis: Long = 90L

/** 快速轻点时按下至少保持多久，回弹才看得出来 */
internal const val TapPulseMillis: Long = 90L
