package hk.uwu.reareye.ui.components.config

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Shared drag state for reorderable configuration cards. */
@Stable
class RearLongPressDragState {
    var draggedId by mutableStateOf<Any?>(null)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set
    private var edgeScrollJob: Job? = null
    private var edgeScrollDirection: Int = 0
    private var lastSwapEventTime: Long = Long.MIN_VALUE

    internal fun begin(id: Any) {
        edgeScrollJob?.cancel()
        draggedId = id
        offsetY = 0f
        lastSwapEventTime = Long.MIN_VALUE
    }

    internal fun updateOffset(delta: Float) {
        offsetY += delta
    }

    internal fun adjustOffset(delta: Float) {
        offsetY -= delta
    }

    internal fun canSwapAt(eventTime: Long): Boolean = lastSwapEventTime != eventTime

    internal fun markSwap(eventTime: Long) {
        lastSwapEventTime = eventTime
    }

    internal fun updateEdgeScroll(
        scope: CoroutineScope,
        listState: LazyListState,
        direction: Int,
    ) {
        val normalizedDirection = direction.coerceIn(-1, 1)
        if (normalizedDirection == edgeScrollDirection &&
            (normalizedDirection == 0 || edgeScrollJob?.isActive == true)
        ) {
            return
        }
        edgeScrollJob?.cancel()
        edgeScrollJob = null
        edgeScrollDirection = normalizedDirection
        if (normalizedDirection == 0) return
        edgeScrollJob = scope.launch {
            while (isActive && draggedId != null && edgeScrollDirection == normalizedDirection) {
                listState.scrollBy(normalizedDirection * 18f)
                delay(16)
            }
        }
    }

    internal fun end() {
        edgeScrollJob?.cancel()
        edgeScrollJob = null
        edgeScrollDirection = 0
        draggedId = null
        offsetY = 0f
        lastSwapEventTime = Long.MIN_VALUE
    }
}

@Composable
fun rememberRearLongPressDragState(): RearLongPressDragState = remember { RearLongPressDragState() }

fun Modifier.rearDragVisual(
    id: Any,
    state: RearLongPressDragState,
): Modifier = graphicsLayer {
    if (state.draggedId == id) {
        translationY = state.offsetY
        alpha = 0.96f
        scaleX = 0.985f
        scaleY = 0.985f
    } else {
        translationY = 0f
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
    }
}.shadow(
    elevation = if (state.draggedId == id) 8.dp else 0.dp,
    shape = RoundedCornerShape(16.dp),
    clip = false,
)

/**
 * Long-press reorder behavior shared by card and wallpaper lists. Layout keys are translated to
 * stable domain ids; overview and non-reorderable rows return null and are ignored.
 */
@Composable
fun Modifier.rearLongPressDrag(
    id: Any,
    state: RearLongPressDragState,
    listState: LazyListState,
    scope: CoroutineScope,
    layoutKeyToId: (Any) -> Any?,
    onMove: (fromId: Any, toId: Any) -> Unit,
    onDragStart: () -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
): Modifier {
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnStart by rememberUpdatedState(onDragStart)
    val currentOnEnd by rememberUpdatedState(onDragEnd)
    val currentOnCancel by rememberUpdatedState(onDragCancel)
    return pointerInput(id, listState) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                state.begin(id)
                currentOnStart()
            },
            onDragCancel = {
                if (state.draggedId == id) {
                    currentOnCancel()
                    state.end()
                }
            },
            onDragEnd = {
                if (state.draggedId == id) {
                    currentOnEnd()
                    state.end()
                }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                if (state.draggedId != id) return@detectDragGesturesAfterLongPress
                // Keep one pointer sample from teleporting the card across multiple rows.
                val deltaY = dragAmount.y.coerceIn(-72f, 72f)
                state.updateOffset(deltaY)

                val currentInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                    layoutKeyToId(it.key) == id
                } ?: return@detectDragGesturesAfterLongPress
                val draggedCenter = currentInfo.offset + currentInfo.size / 2f + state.offsetY
                val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
                val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
                val edgeDistance = 80f
                val edgeDelta = when {
                    draggedCenter < viewportStart + edgeDistance -> -22f
                    draggedCenter > viewportEnd - edgeDistance -> 22f
                    else -> 0f
                }
                state.updateEdgeScroll(
                    scope = scope,
                    listState = listState,
                    direction = edgeDelta.compareTo(0f),
                )

                val direction = deltaY.compareTo(0f)
                if (direction == 0) return@detectDragGesturesAfterLongPress
                if (!state.canSwapAt(change.uptimeMillis)) {
                    return@detectDragGesturesAfterLongPress
                }
                val adjacent = listState.layoutInfo.visibleItemsInfo
                    .asSequence()
                    .mapNotNull { info -> layoutKeyToId(info.key)?.let { info to it } }
                    .filter { (_, targetId) -> targetId != id }
                    .filter { (info, _) ->
                        val targetCenter = info.offset + info.size / 2f
                        if (direction > 0) targetCenter > currentInfo.offset + currentInfo.size / 2f
                        else targetCenter < currentInfo.offset + currentInfo.size / 2f
                    }
                    .let { candidates ->
                        if (direction > 0) {
                            candidates.minByOrNull { (info, _) -> info.offset }
                        } else {
                            candidates.maxByOrNull { (info, _) -> info.offset }
                        }
                    } ?: return@detectDragGesturesAfterLongPress
                val targetCenter = adjacent.first.offset + adjacent.first.size / 2f
                // Leave a small dead zone around the center line. Without it a finger
                // hovering on the boundary can swap the same two rows back and forth.
                val crossingSlop = 10f
                val crossed = if (direction > 0) {
                    draggedCenter > targetCenter + crossingSlop
                } else {
                    draggedCenter < targetCenter - crossingSlop
                }
                if (!crossed) return@detectDragGesturesAfterLongPress
                state.markSwap(change.uptimeMillis)
                currentOnMove(id, adjacent.second)
                state.adjustOffset(
                    direction * (adjacent.first.size.toFloat() + 8f),
                )
            },
        )
    }
}
