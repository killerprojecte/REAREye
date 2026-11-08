// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.gesture

import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ui.feice.mochi.anim.MochiMotion
import ui.feice.mochi.anim.MochiMotions
import ui.feice.mochi.basic.MochiAlign
import ui.feice.mochi.basic.MochiOpenMode
import ui.feice.mochi.basic.MochiTrigger
import ui.feice.mochi.effect.elastic.MochiRubberBandState
import ui.feice.mochi.effect.elastic.jellyCrossScale
import ui.feice.mochi.effect.elastic.jellyStretch
import ui.feice.mochi.effect.elastic.rememberMochiRubberBandState
import ui.feice.mochi.effect.elastic.rubberStretched
import ui.feice.mochi.effect.glow.MochiGlowDefaults
import ui.feice.mochi.effect.glow.MochiGlowSpec
import ui.feice.mochi.effect.glow.MochiGlowState
import ui.feice.mochi.effect.glow.mochiGlow
import ui.feice.mochi.effect.glow.rememberMochiGlowState
import ui.feice.mochi.effect.metaball.MochiBlob
import ui.feice.mochi.effect.metaball.MochiMetaballColors
import ui.feice.mochi.effect.metaball.MochiMetaballFrame
import ui.feice.mochi.effect.metaball.MochiMetaballShadow
import ui.feice.mochi.effect.metaball.drawMochiMetaball
import ui.feice.mochi.effect.metaball.proximityFuse
import ui.feice.mochi.effect.metaball.rememberMochiMetaballRenderer
import ui.feice.mochi.overlay.InWindowOverlay
import ui.feice.mochi.shape.G2PathCache
import ui.feice.mochi.shape.G2RectClipShape
import ui.feice.mochi.shape.addG2RoundRect
import ui.feice.mochi.shape.continuityFor
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.utils.MochiHapticProfile
import ui.feice.mochi.utils.MochiHaptics
import ui.feice.mochi.utils.lerp
import ui.feice.mochi.utils.smoothstep
import ui.feice.mochi.utils.windowOriginOnScreen

// ───────────────────────── 交互状态 ─────────────────────────

/** 按钮和浮层（两个窗口）共享的指针 / 悬停状态，坐标统一用屏幕坐标 */
@Stable
internal class MochiController {
    var pointer by mutableStateOf<Offset?>(null)
    var touchOnScreen by mutableStateOf(Offset.Zero)
    var hover by mutableIntStateOf(-1)
    var panelOnScreen by mutableStateOf(Rect.Zero)
    var upward by mutableStateOf(false)
    var itemCount = 0
    var itemHeightPx = 1f
    var paddingPx = 0f

    /** 选项上面的说明文字（操作确认的标题）占的高度：手指在它上面不算任何选项 */
    var headerPx = 0f
    var contentReady: () -> Boolean = { false }

    /** 列表滚动量（像素，越界时带橡皮筋，可能 < 0 或 > maxScroll） */
    var scroll by mutableFloatStateOf(0f)
    var maxScroll = 0f

    /** 从按钮按住滑选中：手指贴近面板上下沿时列表自动滚动 */
    var autoScroll by mutableStateOf(false)

    fun track(p: Offset) {
        pointer = p
        touchOnScreen = p
        hover = hitTest(p)
    }

    private fun hitTest(p: Offset): Int {
        val r = panelOnScreen
        if (r == Rect.Zero || itemCount == 0 || !contentReady()) return -1
        if (p.x < r.left || p.x > r.right) return -1
        val y = p.y - r.top - paddingPx - headerPx
        if (y < 0f || y > r.height - paddingPx * 2f - headerPx) return -1
        return ((y + scroll.coerceIn(0f, maxScroll)) / itemHeightPx).toInt().coerceIn(0, itemCount - 1)
    }
}

internal class MochiCallbacks(
    val isExpanded: () -> Boolean,
    val open: (long: Boolean) -> Unit,
    val close: () -> Unit,
    val select: (Int) -> Unit,
    val pressed: (Boolean) -> Unit,
    val panelPressed: (Boolean) -> Unit,
    /** 长按触发时的轻点：给了就执行它（比如返回按钮的「返回」），不给就是轻点也打开菜单 */
    val tap: (() -> Unit)? = null,
)

private enum class PressOutcome { Up, Moved }

/**
 * 按钮上的手势：按下 / 长按 → 打开 → 不松手滑动选择 → 松手。
 * 真按钮和浮层里的克隆按钮共用，两者在屏幕上位置一样。
 */
internal fun Modifier.mochiButtonGesture(
    trigger: MochiTrigger,
    controller: MochiController,
    topLeftOnScreen: () -> Offset,
    cb: MochiCallbacks,
): Modifier = pointerInput(trigger, controller) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        down.consume()
        val wasOpen = cb.isExpanded()
        val slop = viewConfiguration.touchSlop
        controller.touchOnScreen = topLeftOnScreen() + down.position
        cb.pressed(true)
        var opened = false

        if (!wasOpen) {
            if (trigger == MochiTrigger.Press) {
                cb.open(false)
                opened = true
            } else {
                val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    var o = PressOutcome.Up
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                        if (c == null || !c.pressed) {
                            o = PressOutcome.Up
                            break
                        }
                        controller.touchOnScreen = topLeftOnScreen() + c.position
                        if ((c.position - down.position).getDistance() > slop) {
                            o = PressOutcome.Moved
                            break
                        }
                    }
                    o
                }
                when (outcome) {
                    null -> {
                        cb.open(true)
                        opened = true
                    }

                    // 没按满时长就松手 = 轻点
                    PressOutcome.Up -> {
                        cb.pressed(false)
                        val tap = cb.tap
                        if (tap != null) tap() else cb.open(false)
                        return@awaitEachGesture
                    }

                    // 轻点也能开
                    PressOutcome.Moved -> {
                        cb.pressed(false)
                        return@awaitEachGesture
                    } // 滑走了，不是给我们的
                }
            }
        }

        var moved = false
        while (true) {
            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            val onScreen = topLeftOnScreen() + c.position
            controller.touchOnScreen = onScreen
            if (!moved && (c.position - down.position).getDistance() > slop) {
                moved = true
                controller.autoScroll = true
            }
            // 先真的滑动才开始选：包裹模式下面板盖住按钮，轻点不能误选第一项
            if (moved) controller.track(onScreen)
            c.consume()
            if (!c.pressed) break
        }

        val h = controller.hover
        controller.autoScroll = false
        controller.pointer = null
        cb.pressed(false)
        when {
            cb.isExpanded() && h >= 0 && moved -> cb.select(h)

            wasOpen && !moved -> {
                controller.hover = -1
                cb.close()
            }

            opened && moved -> {
                controller.hover = -1
                cb.close()
            }

            // 拖到外面松手 = 取消
            else -> controller.hover = -1 // 轻点打开后保持打开
        }
    }
}
