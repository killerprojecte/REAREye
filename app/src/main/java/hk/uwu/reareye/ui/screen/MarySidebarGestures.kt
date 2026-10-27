package hk.uwu.reareye.ui.screen

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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

/** Shared drag progress; opening is rendered in the Activity until its pointer stream ends. */
@Stable
internal class MarySidebarGestureState {
    var progress: Float? by mutableStateOf(null)
        private set
    var isOpening by mutableStateOf(false)
        private set
    var openingSettle: Boolean? by mutableStateOf(null)
        private set
    private var generation = 0

    fun begin(value: Float, opening: Boolean): Int {
        generation++
        isOpening = opening
        openingSettle = null
        progress = value.coerceIn(0f, 1f)
        return generation
    }

    fun update(token: Int, value: Float) {
        if (active(token)) progress = value.coerceIn(0f, 1f)
    }

    fun active(token: Int) = token == generation && progress != null

    fun finishOpening(token: Int, open: Boolean) {
        if (active(token) && isOpening) openingSettle = open
    }

    fun rejectOpening() {
        if (isOpening && progress != null) {
            generation++
            openingSettle = false
        }
    }

    fun cancel() {
        generation++
        progress = null
        isOpening = false
        openingSettle = null
    }
}

/** Full-page horizontal opening; Foundation arbitrates with descendant scroll/drag nodes. */
@Composable
internal fun Modifier.marySidebarOpenGesture(
    state: MarySidebarGestureState,
    beforeOpen: () -> Unit,
    onOpen: () -> Unit,
): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val distance = with(density) { 64.dp.toPx() }
    val flingDistance = with(density) { 32.dp.toPx() }
    val minimumFling = with(density) { 900.dp.toPx() }
    val maxPanelWidth = with(density) { 360.dp.toPx() }
    val before by rememberUpdatedState(beforeOpen)
    val open by rememberUpdatedState(onOpen)
    val tracker = remember(state, rtl) { MaryOpenTracker(state) }
    val interactions = remember(tracker) { MaryOpenInteractions(tracker) }
    DisposableEffect(tracker) {
        onDispose { tracker.cancelActive(disposed = true) }
    }
    val drag = rememberDraggableState {
        tracker.active?.let { observation ->
            observation.token?.let { token ->
                if (!observation.cancelled && state.active(token)) {
                    state.update(token, observation.dx / observation.panelWidth)
                }
            }
        }
    }
    return this
        // Observe only: do not steal Initial-pass events from text selection or child scrolling.
        .pointerInput(tracker, state, rtl, maxPanelWidth) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val observation = MaryOpenObservation(minOf(size.width * 0.82f, maxPanelWidth).coerceAtLeast(1f))
                tracker.current = observation
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (event.changes.count { it.pressed } > 1) observation.cancelled = true
                        observation.dx = (change.position.x - down.position.x) * if (rtl) -1f else 1f
                        val dy = change.position.y - down.position.y
                        if (observation.intent == 0 &&
                            change.uptimeMillis - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                        ) observation.cancelled = true
                        if (observation.intent == 0 &&
                            (abs(observation.dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)
                        ) {
                            observation.intent = if (observation.dx > abs(dy)) 1 else -1
                        }
                        if (!change.pressed) {
                            observation.released = true
                            break
                        }
                    }
                } finally {
                    if (!observation.released) {
                        observation.cancelled = true
                        observation.token?.let { if (state.active(it)) state.finishOpening(it, false) }
                    }
                }
            }
        }
        .draggable(
            state = drag,
            orientation = Orientation.Horizontal,
            interactionSource = interactions,
            reverseDirection = rtl,
            onDragStarted = {
                tracker.active?.let { observation ->
                    if (!observation.cancelled && observation.intent == 1) {
                        observation.token = state.begin(0f, opening = true)
                    }
                }
            },
            onDragStopped = { velocity ->
                val observation = tracker.active
                val token = observation?.token
                // Draggable also calls this callback on CANCEL. Only its matching Stop interaction
                // plus a real UP may open; never confuse cancellation with a slow zero-velocity drag.
                val accepted = observation != null && token != null && state.active(token) &&
                    tracker.stoppedNormally && observation.released && !observation.cancelled &&
                    observation.intent == 1 && when {
                        velocity <= -minimumFling -> false
                        velocity >= minimumFling -> observation.dx >= flingDistance
                        else -> observation.dx >= distance
                    }
                token?.let { if (state.active(it)) state.finishOpening(it, accepted) }
                tracker.active = null
                if (accepted) {
                    before()
                    open()
                }
            },
        )
}

private class MaryOpenObservation(val panelWidth: Float) {
    var dx = 0f
    var intent = 0
    var released = false
    var cancelled = false
    var token: Int? = null
}

private class MaryOpenTracker(private val state: MarySidebarGestureState) {
    var current: MaryOpenObservation? = null
    var active: MaryOpenObservation? = null
    var interaction: DragInteraction.Start? = null
    var stoppedNormally = false

    fun cancelActive(disposed: Boolean = false) {
        stoppedNormally = false
        active?.let { observation ->
            observation.cancelled = true
            observation.token?.let {
                if (state.active(it)) {
                    // A normal release may remove the opener while its visual completion runs.
                    if (disposed && state.openingSettle == null) state.cancel()
                    else if (!disposed) state.finishOpening(it, false)
                }
            }
        }
    }

    fun record(event: Interaction) {
        when (event) {
            is DragInteraction.Start -> {
                active = current
                interaction = event
                stoppedNormally = false
            }
            is DragInteraction.Stop -> if (event.start === interaction) stoppedNormally = true
            is DragInteraction.Cancel -> if (event.start === interaction) cancelActive()
        }
    }
}

/** Synchronous record: an asynchronous interactions collector could race onDragStopped. */
private class MaryOpenInteractions(
    private val tracker: MaryOpenTracker,
    private val delegate: MutableInteractionSource = MutableInteractionSource(),
) : MutableInteractionSource by delegate {
    override suspend fun emit(interaction: Interaction) {
        tracker.record(interaction)
        delegate.emit(interaction)
    }

    override fun tryEmit(interaction: Interaction): Boolean {
        tracker.record(interaction)
        return delegate.tryEmit(interaction)
    }
}

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
