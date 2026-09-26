package hk.uwu.reareye.ui.components.config.draggable.library.draggable

import android.graphics.Rect
import hk.uwu.reareye.ui.components.config.draggable.library.draggable.model.ItemInfo
import kotlin.math.pow
import kotlin.math.sqrt

class ItemMoveOperationResolver<T>(
    private val isItemLocked: ((itemToSwap: ItemInfo) -> Boolean)?,
    private val layoutMode: () -> LayoutMode,
    private val itemIndex: (T) -> Int,
    private val itemKey: (T) -> Any?,
    private val itemTop: (T) -> Int,
    private val itemLeft: (T) -> Int,
    private val itemRight: (T) -> Int,
    private val itemBottom: (T) -> Int,
    private val itemWidth: (T) -> Int,
    private val itemHeight: (T) -> Int,
    private val hoveredItem: (T?) -> Unit,
) {

    companion object {
        const val SQUARED_EXPONENT = 2.0
    }

    fun findItemToSwap(
        currentDraggingItem: T,
        x: Float,
        y: Float,
        visibleItems: List<T>
    ): T? {
        val left = x
        val top = y
        val right = left + itemWidth(currentDraggingItem)
        val bottom = top + itemHeight(currentDraggingItem)
        val dragRect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
        findHoveredItem(currentDraggingItem, visibleItems, dragRect)
        return getClosestItem(
            currentDraggingItem,
            findOverlappedItems(currentDraggingItem, left, top, dragRect, visibleItems),
        )
    }

    private fun findHoveredItem(
        currentDraggingItem: T,
        visibleItems: List<T>,
        dragRect: Rect,
    ) {
        val items = visibleItems
            .filter { item ->
                if (itemIndex(item) == itemIndex(currentDraggingItem)
                    || itemKey(item) == itemKey(currentDraggingItem)
                    || isItemLocked(item)
                ) {
                    return@filter false
                }

                dragRect.overlaps(
                    Rect(
                        itemLeft(item),
                        itemTop(item),
                        itemRight(item),
                        itemBottom(item)
                    ),
                    coveredArea = 0.25
                )
            }


        hoveredItem(
            getClosestItem(
                currentDraggingItem,
                items,
            )
        )
    }

    private fun findOverlappedItems(
        currentDraggingItem: T,
        draggingItemLeft: Float,
        draggingItemTop: Float,
        draggingItemRect: Rect,
        visibleItems: List<T>
    ): List<T> {
        return visibleItems
            .filter { item ->
                if (itemIndex(item) == itemIndex(currentDraggingItem)
                    || itemKey(item) == itemKey(currentDraggingItem)
                    || isItemLocked(item)
                ) {
                    return@filter false
                }

                draggingItemRect.overlaps(
                    Rect(
                        itemLeft(item),
                        itemTop(item),
                        itemRight(item),
                        itemBottom(item)
                    )
                )
            }
            .filter { item ->
                val deltaX = (draggingItemLeft - itemLeft(currentDraggingItem))
                val deltaY = (draggingItemTop - itemTop(currentDraggingItem))

                when (layoutMode()) {
                    LayoutMode.GRID -> {
                        when {
                            deltaX > 0 && deltaY > 0 -> {
                                itemLeft(item) < draggingItemLeft && itemTop(item) < draggingItemTop
                            }

                            deltaX < 0 && deltaY < 0 -> {
                                itemLeft(item) > draggingItemLeft && itemTop(item) > draggingItemTop
                            }

                            deltaX > 0 && deltaY < 0 -> {
                                itemLeft(item) < draggingItemLeft && itemTop(item) > draggingItemTop
                            }

                            deltaX < 0 && deltaY > 0 -> {
                                itemLeft(item) > draggingItemLeft && itemTop(item) < draggingItemTop
                            }

                            else -> false
                        }
                    }

                    LayoutMode.VERTICAL_LIST -> {
                        if (deltaY > 0) itemTop(item) < draggingItemTop else itemTop(item) > draggingItemTop
                    }

                    LayoutMode.HORIZONTAL_LIST -> {
                        if (deltaX > 0) itemLeft(item) < draggingItemLeft else itemLeft(item) > draggingItemLeft
                    }
                }
            }
    }

    private fun getClosestItem(
        currentDraggingItem: T,
        overlappingItems: List<T>
    ): T? {
        return overlappingItems.minByOrNull { item ->
            getCenterDistance(currentDraggingItem, item)
        }
    }

    private fun getCenterDistance(item1: T, item2: T): Double {
        val centerX1 = (itemLeft(item1) + itemRight(item1)) / SQUARED_EXPONENT
        val centerY1 = (itemTop(item1) + itemBottom(item1)) / SQUARED_EXPONENT

        val centerX2 = (itemLeft(item2) + itemRight(item2)) / SQUARED_EXPONENT
        val centerY2 = (itemTop(item2) + itemBottom(item2)) / SQUARED_EXPONENT

        //Calculate distance using Euclidean Distance formula
        return sqrt(
            (centerX2 - centerX1).pow(SQUARED_EXPONENT) + (centerY2 - centerY1).pow(SQUARED_EXPONENT)
        )
    }

    private fun Rect.overlaps(other: Rect, coveredArea: Double = 0.01): Boolean {
        return when (layoutMode()) {
            LayoutMode.GRID ->
                left + (other.width() * coveredArea) < other.right &&
                        right > other.left + (other.width() * coveredArea) &&
                        top + (other.height() * coveredArea) < other.bottom &&
                        bottom > other.top + (other.height() * coveredArea)

            LayoutMode.VERTICAL_LIST ->
                top + (other.height() * coveredArea) < other.bottom
                        && bottom > other.top + (other.height() * coveredArea)

            LayoutMode.HORIZONTAL_LIST ->
                left + (other.width() * coveredArea) < other.right
                        && right > other.left + (other.width() * coveredArea)
        }
    }

    private fun isItemLocked(item: T): Boolean {
        return isItemLocked?.invoke(
            ItemInfo(
                index = itemIndex(item),
                key = itemKey(item),
            )
        ) ?: false
    }
}

