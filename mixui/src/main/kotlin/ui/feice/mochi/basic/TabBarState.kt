// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.selected
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import ui.feice.mochi.effect.elastic.MochiFollowSpring
import ui.feice.mochi.effect.elastic.rubberBand
import ui.feice.mochi.theme.lerp
import ui.feice.mochi.utils.MochiHaptics
import ui.feice.mochi.utils.lerp

/** 选中块的位置（以「第几项」为单位，可以是小数）、拖在后面的水滴、底栏的跟手位移，和它们的弹簧 */
@Stable
internal class TabBarState(initial: Float) {
    var pos by mutableFloatStateOf(initial)
        private set

    /** 每秒移动几项（≈ 每秒移动几个自身宽度，给果冻用） */
    var vel by mutableFloatStateOf(0f)
        private set

    /** 拖在选中块后面的水滴（以「第几项」为单位） */
    var tail by mutableFloatStateOf(initial)
        private set

    // Bound visual separation smoothly, without snapping or resetting the follower's velocity
    // when a fast tap skips several items. All droplet/neck geometry reads this same position.
    val dropletPosition: Float get() = pos + rubberBand(tail - pos, 1f, 0.5f)

    /** 底栏（连同选中块、图标）被手指带着的位移（px） */
    var offset by mutableStateOf(Offset.Zero)
        private set

    var held by mutableStateOf(false)

    /** 手指位置（以「第几项」为单位，没截断） */
    var finger by mutableFloatStateOf(initial)

    /** 手指相对按下点的位移（px），每帧在循环里读 */
    var drag: Offset = Offset.Zero

    /** 松手后、新的 selectedIndex 传进来之前，先往这里去（不然会先被拉回旧位置一帧） */
    private var settle: Int? = null
    private var settleFrames = 0

    fun release(target: Int?) {
        held = false
        drag = Offset.Zero
        settle = target
        settleFrames = 6
    }

    private fun target(n: Int, selected: Int): Float {
        if (held) {
            val c = finger.coerceIn(0f, n - 1f)
            return c + rubberBand(finger - c, 1f, 0.35f)
        }
        val s = settle
        if (s != null) {
            if (s == selected || settleFrames-- <= 0) settle = null else return s.toFloat()
        }
        return selected.toFloat()
    }

    private fun atRest(t: Float, a: MochiFollowSpring, b: MochiFollowSpring, o: MochiFollowSpring): Boolean = !held &&
        settle == null &&
        abs(pos - t) < 0.001f &&
        abs(a.velocity.x) < 0.01f &&
        abs(tail - t) < 0.001f &&
        abs(b.velocity.x) < 0.01f &&
        o.value.getDistance() < 0.1f &&
        o.velocity.getDistance() < 1f

    suspend fun run(n: Int, selected: () -> Int, haptics: MochiHaptics, bar: Size, maxShift: Offset) {
        val spring = MochiFollowSpring(0.42f, 0.72f)
        // 水滴只稍慢于选中块；拖动时更紧，松手仍沿同一弹簧连续追上。
        val tailSpring = MochiFollowSpring(0.24f, 0.9f)
        // 底栏位移：松手带一点果冻回弹
        val shift = MochiFollowSpring(0.3f, 0.62f)
        spring.snapTo(Offset(pos, 0f))
        tailSpring.snapTo(Offset(tail, 0f))
        shift.snapTo(offset)
        var nearest = selected()
        while (true) {
            val t = target(n, selected())
            if (atRest(t, spring, tailSpring, shift)) {
                pos = t
                tail = t
                vel = 0f
                offset = Offset.Zero
                spring.snapTo(Offset(t, 0f))
                tailSpring.snapTo(Offset(t, 0f))
                shift.snapTo(Offset.Zero)
                snapshotFlow { held to selected() }.first { (h, s) -> h || s.toFloat() != pos }
                continue
            }
            val dt = spring.awaitFrameDelta()
            spring.response = if (held) 0.22f else 0.42f
            spring.dampingRatio = if (held) 0.86f else 0.72f
            pos = spring.step(Offset(t, 0f), dt).x
            vel = spring.velocity.x
            tailSpring.response = if (held) 0.16f else 0.24f
            tail = tailSpring.step(Offset(pos, 0f), dt).x
            val d = drag
            val shiftTarget = if (held) {
                Offset(rubberBand(d.x, bar.width, maxShift.x), rubberBand(d.y, bar.height * 4f, maxShift.y))
            } else {
                Offset.Zero
            }
            offset = shift.step(shiftTarget, dt)
            val k = pos.roundToInt().coerceIn(0, n - 1)
            if (held && k != nearest) haptics.tick()
            nearest = k
        }
    }
}

internal fun Rect.scaleAround(sx: Float, sy: Float): Rect {
    val c = center
    val hw = width * sx / 2f
    val hh = height * sy / 2f
    return Rect(c.x - hw, c.y - hh, c.x + hw, c.y + hh)
}

internal fun lerpRect(a: Rect, b: Rect, t: Float): Rect = if (t == 0f) {
    a
} else {
    Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))
}
