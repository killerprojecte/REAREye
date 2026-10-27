package hk.uwu.reareye.ui.screen

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Shared gesture bookkeeping; only a close drag moves the already-mounted modal. */
@Stable
internal class MarySidebarGestureState {
    var progress: Float? by mutableStateOf(null)
        private set
    var isOpening by mutableStateOf(false)
        private set
    private var generation = 0

    fun begin(value: Float, opening: Boolean): Int {
        generation++
        isOpening = opening
        progress = value.coerceIn(0f, 1f)
        return generation
    }

    fun update(token: Int, value: Float) {
        if (active(token)) progress = value.coerceIn(0f, 1f)
    }

    fun active(token: Int) = token == generation && progress != null

    fun cancel() {
        generation++
        progress = null
        isOpening = false
    }
}

/**
 * Only attach to the transcript, never an invisible edge overlay over the composer/navigation.
 * Keep Android's system-back strip untouched; our 32dp activation strip starts inside it.
 */
@Composable
internal fun Modifier.marySidebarEdgeGesture(
    state: MarySidebarGestureState,
    beforeOpen: () -> Unit,
    onOpen: () -> Unit,
): Modifier = marySidebarSwipe(
    state = state, opening = true, enabled = true, initialProgress = { 0f },
    // Do not create a new Android window while this Activity still owns the DOWN stream.
    onSettle = { open -> if (open) { beforeOpen(); onOpen() } },
)

@Composable
internal fun Modifier.marySidebarCloseGesture(
    state: MarySidebarGestureState,
    enabled: Boolean,
    initialProgress: () -> Float,
    onClose: () -> Unit,
): Modifier = marySidebarSwipe(
    state = state, opening = false, enabled = enabled, initialProgress = initialProgress,
    onSettle = { open -> if (!open) onClose() },
)

@Composable
private fun Modifier.marySidebarSwipe(
    state: MarySidebarGestureState,
    opening: Boolean,
    enabled: Boolean,
    initialProgress: () -> Float,
    onSettle: (Boolean) -> Unit,
): Modifier {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val rtl = layoutDirection == LayoutDirection.Rtl
    val systemInset = if (rtl) WindowInsets.systemGestures.getRight(density, layoutDirection)
        else WindowInsets.systemGestures.getLeft(density, layoutDirection)
    val strip = with(density) { 32.dp.toPx() }
    val maxPanelWidth = with(density) { 360.dp.toPx() }
    val minimumFling = with(density) { 900.dp.toPx() }
    val settle by rememberUpdatedState(onSettle)
    val initial by rememberUpdatedState(initialProgress)
    return pointerInput(state, opening, enabled, rtl, systemInset, strip, maxPanelWidth, minimumFling) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val fromStart = if (rtl) size.width - down.position.x else down.position.x
            if (opening && (fromStart < systemInset || fromStart > systemInset + strip)) {
                return@awaitEachGesture
            }
            val sign = if (rtl) -1f else 1f
            val panelWidth = minOf(size.width * 0.82f, maxPanelWidth).coerceAtLeast(1f)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var token: Int? = null
            var origin = 0f
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    // Never steal a vertical list scroll, a child gesture, or a multi-touch action.
                    if (event.changes.count { it.pressed } > 1) {
                        if (token != null) event.changes.forEach { it.consume() }
                        break
                    }
                    if (change.isConsumed) break
                    tracker.addPosition(change.uptimeMillis, change.position)
                    val dx = (change.position.x - down.position.x) * sign
                    val dy = change.position.y - down.position.y
                    if (!change.pressed) {
                        val active = token
                        if (active != null && state.active(active)) {
                            val velocity = tracker.calculateVelocity().x * sign
                            val open = if (abs(velocity) >= minimumFling && abs(dx) > viewConfiguration.touchSlop) {
                                velocity > 0f
                            } else {
                                (state.progress ?: origin) >= 0.5f
                            }
                            change.consume()
                            settle(open)
                        }
                        break
                    }
                    if (token == null) {
                        if (abs(dx) <= viewConfiguration.touchSlop && abs(dy) <= viewConfiguration.touchSlop) continue
                        if (abs(dy) >= abs(dx) || (opening && dx <= 0f) || (!opening && dx >= 0f)) break
                        origin = initial()
                        token = state.begin(origin, opening)
                    }
                    val active = token
                    if (active == null || !state.active(active)) break
                    state.update(active, origin + dx / panelWidth)
                    change.consume()
                }
            } finally {
                // Also runs for pointer cancellation / host disposal. Settle back to existing show.
                token?.let { if (state.active(it)) state.cancel() }
            }
        }
    }
}
