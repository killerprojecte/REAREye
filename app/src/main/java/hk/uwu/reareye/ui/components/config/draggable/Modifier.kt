package hk.uwu.reareye.ui.components.config.draggable

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import hk.uwu.reareye.ui.components.config.draggable.library.draggable.DraggableState

fun Modifier.longPressDraggable(
    draggableState: DraggableState<*>,
    key: Any,
) = then(
    Modifier.pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = {
                draggableState.onDragStart(key)
            },
            onDragEnd = {
                draggableState.onDragCanceled()
            },
            onDragCancel = {
                draggableState.onDragCanceled()
            },
        ) { change, dragAmount ->
            change.consume()
            draggableState.onDrag(dragAmount)
        }
    })

fun Modifier.draggable(
    draggableState: DraggableState<*>,
    key: Any,
) = then(
    Modifier
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = {
                    draggableState.onDragStart(key)
                },
                onDragEnd = {
                    draggableState.onDragCanceled()
                },
                onDragCancel = {
                    draggableState.onDragCanceled()
                },
            ) { change, dragAmount ->
                change.consume()
                draggableState.onDrag(dragAmount)
            }
        }
)
