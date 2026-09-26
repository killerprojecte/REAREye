package hk.uwu.reareye.ui.components.config.draggable.library.draggable

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import hk.uwu.reareye.ui.components.config.draggable.library.draggable.model.ItemInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

abstract class DraggableState<T>(
    private val coroutineScope: CoroutineScope,
    private val onSwap: (ItemInfo, ItemInfo) -> Unit,
    private val onDragFinished: () -> Unit,
    autoscroller: Autoscroller?,
    speedFactor: Float,
    isItemLocked: ((itemToSwap: ItemInfo) -> Boolean)?,
) {

    internal abstract val isGrid: Boolean
    internal abstract val isVertical: Boolean

    internal abstract val firstVisibleItemIndex: Int
    internal abstract val viewportStartOffset: Int
    internal abstract val viewportEndOffset: Int
    internal abstract val firstVisibleItemScrollOffset: Int
    internal abstract val layoutMode: LayoutMode

    internal abstract val T.itemIndex: Int
    internal abstract val T.itemKey: Any?
    internal abstract val T.top: Int
    internal abstract val T.left: Int
    internal abstract val T.right: Int
    internal abstract val T.bottom: Int
    internal abstract val T.width: Int
    internal abstract val T.height: Int
    internal abstract val visibleItemsInfo: List<T>

    internal abstract suspend fun scrollBy(distance: Float)
    internal abstract suspend fun scrollToItem(index: Int, scrollOffset: Int)

    private var _draggedDistance by mutableStateOf(Offset(0f, 0f))
    private val draggedDistance: Offset
        get() = _draggedDistance

    private var overscrollJob: Job? = null

    internal val cancelDragAnimation = CancelDragAnimation()
    private val autoScroller by lazy {
        autoscroller ?: DefaultAutoscroller(speedFactor)
    }

    private val itemMoveOperationResolver = ItemMoveOperationResolver<T>(
        isItemLocked = isItemLocked,
        layoutMode = { layoutMode },
        itemIndex = { item -> item.itemIndex },
        itemKey = { item -> item.itemKey },
        itemTop = { item -> item.top },
        itemLeft = { item -> item.left },
        itemRight = { item -> item.right },
        itemBottom = { item -> item.bottom },
        itemWidth = { item -> item.width },
        itemHeight = { item -> item.height },
        hoveredItem = { item ->
            hoveredItemKey = item?.itemKey
            hoveredItemIndex = item?.itemIndex
        }
    )

    private var currentlyDraggedItem by mutableStateOf<T?>(null)

    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    internal val draggingItemKey: Any?
        get() = currentlyDraggedItem?.itemKey

    val draggingItemLeft: Float
        get() = draggingLayoutInfo?.let { item ->
            (currentlyDraggedItem?.left ?: 0) + draggedDistance.x - item.left
        } ?: 0f
    val draggingItemTop: Float
        get() = draggingLayoutInfo?.let { item ->
            (currentlyDraggedItem?.top ?: 0) + draggedDistance.y - item.top
        } ?: 0f
    private val draggingLayoutInfo: T?
        get() = visibleItemsInfo
            .firstOrNull { it.itemIndex == draggingItemIndex }

    internal var hoveredItemKey by mutableStateOf<Any?>(null)
    private var hoveredItemIndex by mutableStateOf<Int?>(null)

    internal fun onDragStart(key: Any) {
        visibleItemsInfo.find { item ->
            item.itemKey == key
        }?.let { item ->
            currentlyDraggedItem = item
            draggingItemIndex = item.itemIndex
        }
    }

    internal fun onDrag(dragAmount: Offset) {
        swapMovingItem(dragAmount = dragAmount)

        val overScroll = calculateOverscrollAmount()
        if (overScroll != 0f) {
            trackOverscroll()
        }
    }

    private fun swapMovingItem(
        dragAmount: Offset
    ) {
        currentlyDraggedItem ?: return
        _draggedDistance += dragAmount
        val draggingItemInfo = draggingLayoutInfo ?: return
        val startOffsetY = draggingItemInfo.top + draggingItemTop
        val startOffsetX = draggingItemInfo.left + draggingItemLeft

        val itemToSwap = itemMoveOperationResolver.findItemToSwap(
            draggingItemInfo,
            startOffsetX,
            startOffsetY,
            visibleItemsInfo
        ) ?: return
        if (draggingItemInfo.itemIndex == itemToSwap.itemIndex) return

        if (itemToSwap.itemIndex == firstVisibleItemIndex || draggingItemInfo.itemIndex == firstVisibleItemIndex) {
            coroutineScope.launch {
                onSwap.invoke(
                    ItemInfo(draggingItemInfo.itemIndex, draggingItemInfo.itemKey),
                    ItemInfo(itemToSwap.itemIndex, itemToSwap.itemKey),
                )
                scrollToItem(firstVisibleItemIndex, firstVisibleItemScrollOffset)
            }
        } else {
            onSwap.invoke(
                ItemInfo(draggingItemInfo.itemIndex, draggingItemInfo.itemKey),
                ItemInfo(itemToSwap.itemIndex, itemToSwap.itemKey),
            )
        }

        draggingItemIndex = itemToSwap.itemIndex
    }

    private fun trackOverscroll() {
        val overScroll = calculateOverscrollAmount()
        if (overScroll != 0f && overscrollJob?.isActive != true) {
            overscrollJob = coroutineScope.launch {
                autoscroll(
                    when {
                        overScroll > 0f -> DragDirection.END
                        overScroll < -0f -> DragDirection.START
                        else -> DragDirection.NONE
                    }
                )
            }
        }
    }

    private suspend fun autoscroll(dragDirection: DragDirection = DragDirection.NONE) {
        while (currentlyDraggedItem != null && calculateOverscrollAmount() != 0f) {
            autoScroller.start(
                dragDirection, calculateOverscrollAmount()
            ) { overScroll ->
                scrollBy(overScroll)
            }

            swapMovingItem(Offset(0f, 0f))
        }
    }

    private fun calculateOverscrollAmount(): Float {
        val currentDragItem = draggingLayoutInfo ?: return 0f
        val initialOffset: Float
        val finalOffset: Float
        val scrollDelta: Float

        if (isVertical) {
            initialOffset = currentDragItem.top + draggingItemTop
            finalOffset = initialOffset + currentDragItem.height
            scrollDelta = draggedDistance.y
        } else {
            initialOffset = currentDragItem.left + draggingItemLeft
            finalOffset = initialOffset + currentDragItem.width
            scrollDelta = draggedDistance.x
        }

        return when {
            scrollDelta > 0 -> (finalOffset - viewportEndOffset).coerceAtLeast(0f)
            scrollDelta < 0 -> (initialOffset - viewportStartOffset).coerceAtMost(0f)
            else -> 0f
        }
    }

    fun onDragCanceled() {
        draggingItemIndex?.let { index ->
            val key = draggingItemKey
            val offset = Offset(draggingItemLeft, draggingItemTop)
            coroutineScope.launch {
                cancelDragAnimation.animatableChangePosition(
                    index,
                    key,
                    offset
                )
            }
        }
        currentlyDraggedItem = null
        draggingItemIndex = null
        hoveredItemKey = null
        hoveredItemIndex = null
        _draggedDistance = Offset(0f, 0f)
        cancelOverscrollJob()
        onDragFinished()
    }

    private fun cancelOverscrollJob() {
        overscrollJob?.cancel()
        overscrollJob = null
    }
}

