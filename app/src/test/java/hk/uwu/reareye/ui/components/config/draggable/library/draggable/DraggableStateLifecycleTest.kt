package hk.uwu.reareye.ui.components.config.draggable.library.draggable

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DraggableStateLifecycleTest {
    // Keep animation launches queued: these tests exercise synchronous lifecycle
    // callbacks, not frame rendering. Cancel the scope without running animations.
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private var finishes = 0
    private val state = FixtureState(scope) { finishes++ }

    @After fun close() { scope.cancel() }

    @Test fun idleCancellationAndRemovalDoNotCommit() {
        state.onDragCanceled()
        state.items = emptyList()
        state.onDragCanceled()
        assertEquals(0, finishes)
        assertNull(state.draggingItemIndex)
    }

    @Test fun missingKeyDoesNotStartOrCommit() {
        state.onDragStart("missing")
        state.onDrag(Offset(0f, 5f))
        state.onDragCanceled()
        assertNull(state.draggingItemIndex)
        assertEquals(0, finishes)
    }

    @Test fun indexZeroFinishCommitsOnceAndRepeatedCancelDoesNot() {
        state.onDragStart("first")
        assertEquals(0, state.draggingItemIndex)
        // Both gesture onDragEnd and onDragCancel use this lifecycle entry point.
        state.onDragCanceled()
        assertEquals(1, finishes)
        assertNull(state.draggingItemIndex)
        assertNull(state.draggingItemKey)
        state.onDragCanceled()
        assertEquals(1, finishes)
    }

    @Test fun activeDragRemovedFromLayoutStillCommitsOnce() {
        state.onDragStart("second")
        assertEquals(1, state.draggingItemIndex)
        state.items = emptyList()
        state.onDragCanceled()
        state.onDragCanceled()
        assertEquals(1, finishes)
        assertNull(state.draggingItemIndex)
    }

    @Test fun newDragAfterCancellationGetsItsOwnSingleCommit() {
        state.onDragStart("first")
        state.onDragCanceled()
        state.onDragStart("second")
        state.onDragCanceled()
        state.onDragCanceled()
        assertEquals(2, finishes)
    }

    private data class Row(val index: Int, val key: String)
    private class FixtureState(scope: CoroutineScope, finished: () -> Unit) :
        DraggableState<Row>(scope, { _, _ -> error("Unexpected swap") }, finished, null, 1f, null) {
        var items = listOf(Row(0, "first"), Row(1, "second"))
        override val isGrid = false
        override val isVertical = true
        override val firstVisibleItemIndex = 0
        override val viewportStartOffset = 0
        override val viewportEndOffset = 200
        override val firstVisibleItemScrollOffset = 0
        override val layoutMode = LayoutMode.VERTICAL_LIST
        override val Row.itemIndex get() = index
        override val Row.itemKey get() = key
        override val Row.top get() = index * 100
        override val Row.left get() = 0
        override val Row.right get() = 100
        override val Row.bottom get() = top + 100
        override val Row.width get() = 100
        override val Row.height get() = 100
        override val visibleItemsInfo get() = items
        override suspend fun scrollBy(distance: Float) { error("Unexpected scroll") }
        override suspend fun scrollToItem(index: Int, scrollOffset: Int) { error("Unexpected scroll") }
    }
}
