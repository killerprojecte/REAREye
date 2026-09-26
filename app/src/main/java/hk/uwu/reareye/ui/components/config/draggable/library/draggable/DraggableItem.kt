package hk.uwu.reareye.ui.components.config.draggable.library.draggable

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyItemScope.DraggableItem(
    modifier: Modifier = Modifier,
    index: Int? = null,
    key: Any? = null,
    state: DraggableState<*>,
    content: @Composable (isDragging: Boolean, hoveredItemKey: Any?) -> Unit,
) = DraggableItem(
    index = index,
    key = key,
    state = state,
    modifier = modifier,
    defaultModifier = Modifier.animateItem(),
    content = content,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LazyGridItemScope.DraggableItem(
    modifier: Modifier = Modifier,
    index: Int? = null,
    key: Any? = null,
    state: DraggableState<*>,
    content: @Composable (isDragging: Boolean, hoveredItemKey: Any?) -> Unit,
) = DraggableItem(
    index = index,
    key = key,
    state = state,
    modifier = modifier,
    defaultModifier = Modifier.animateItem(),
    content = content,
)

@Composable
private fun DraggableItem(
    index: Int?,
    key: Any?,
    state: DraggableState<*>,
    modifier: Modifier,
    defaultModifier: Modifier = Modifier,
    content: @Composable (isDragging: Boolean, hoveredItemKey: Any?) -> Unit,
) {
    val isDragging = if (key != null) {
        key == state.draggingItemKey
    } else {
        index == state.draggingItemIndex
    }

    val released: Boolean = if (index != null) {
        index == state.cancelDragAnimation.position
    } else {
        key == state.cancelDragAnimation.key
    }

    val contentModifier = if (isDragging) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                if (state.isVertical || state.isGrid) translationY = state.draggingItemTop
                if (!state.isVertical || state.isGrid) translationX = state.draggingItemLeft
            }
    } else if (released) {
        Modifier
            .zIndex(1f)
            .graphicsLayer {
                if (state.isVertical || state.isGrid) translationY =
                    state.cancelDragAnimation.offset.y
                if (!state.isVertical || state.isGrid) translationX =
                    state.cancelDragAnimation.offset.x
            }
    } else {
        defaultModifier
    }

    Box(
        modifier = modifier.then(contentModifier)
    ) {
        content(isDragging, state.hoveredItemKey)
    }
}

