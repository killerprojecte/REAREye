package hk.uwu.reareye.ui.components.config.draggable.library.draggable

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import hk.uwu.reareye.ui.components.config.draggable.library.draggable.hijacker.rememberLazyListHijacker
import hk.uwu.reareye.ui.components.config.draggable.library.draggable.model.ItemInfo
import kotlinx.coroutines.CoroutineScope

@Composable
fun rememberDraggableLazyListState(
    state: LazyListState = rememberLazyListState(),
    speedFactor: Float = 1f,
    autoscroller: Autoscroller? = null,
    onSwap: (ItemInfo, ItemInfo) -> Unit,
    isItemLocked: ((itemToSwap: ItemInfo) -> Boolean)? = null,
    onDragFinished: () -> Unit = {},
): DraggableLazyListState {
    val coroutineScope: CoroutineScope = rememberCoroutineScope()
    rememberLazyListHijacker(listState = state, enabled = true)
    val draggableState = remember(state) {
        DraggableLazyListState(
            state,
            speedFactor,
            coroutineScope,
            autoscroller,
            onSwap,
            isItemLocked,
            onDragFinished,
        )
    }

    return draggableState
}

class DraggableLazyListState(
    val state: LazyListState,
    speedFactor: Float,
    coroutineScope: CoroutineScope,
    autoscroller: Autoscroller?,
    onSwap: (ItemInfo, ItemInfo) -> Unit,
    isItemLocked: ((itemToSwap: ItemInfo) -> Boolean)?,
    onDragFinished: () -> Unit,
) : DraggableState<LazyListItemInfo>(
    coroutineScope,
    onSwap,
    onDragFinished,
    autoscroller,
    speedFactor,
    isItemLocked,
) {

    override val isGrid: Boolean
        get() = false
    override val isVertical: Boolean
        get() = state.layoutInfo.orientation == Orientation.Vertical
    override val viewportStartOffset: Int
        get() = state.layoutInfo.viewportStartOffset
    override val viewportEndOffset: Int
        get() = state.layoutInfo.viewportEndOffset
    override val firstVisibleItemScrollOffset: Int
        get() = state.firstVisibleItemScrollOffset
    override val firstVisibleItemIndex: Int
        get() = state.firstVisibleItemIndex
    override val LazyListItemInfo.left: Int
        get() = when {
            isVertical -> 0
            else -> offset
        }
    override val LazyListItemInfo.top: Int
        get() = when {
            !isVertical -> 0
            else -> offset
        }
    override val LazyListItemInfo.right: Int
        get() = when {
            isVertical -> 0
            else -> offset + size
        }
    override val LazyListItemInfo.bottom: Int
        get() = when {
            !isVertical -> 0
            else -> offset + size
        }
    override val LazyListItemInfo.width: Int
        get() = if (isVertical) 0 else size
    override val LazyListItemInfo.height: Int
        get() = if (isVertical) size else 0
    override val visibleItemsInfo: List<LazyListItemInfo>
        get() = state.layoutInfo.visibleItemsInfo
    override val LazyListItemInfo.itemIndex: Int
        get() = index
    override val LazyListItemInfo.itemKey: Any
        get() = key
    override val layoutMode: LayoutMode
        get() = if (state.layoutInfo.orientation == Orientation.Vertical)
            LayoutMode.VERTICAL_LIST else LayoutMode.HORIZONTAL_LIST

    override suspend fun scrollBy(distance: Float) {
        state.scrollBy(distance)
    }

    override suspend fun scrollToItem(index: Int, scrollOffset: Int) {
        state.scrollToItem(index, scrollOffset)
    }
}
