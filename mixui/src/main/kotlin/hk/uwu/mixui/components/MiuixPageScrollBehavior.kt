package hk.uwu.mixui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBarState
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState

/** Native physics, with a marker distinguishing a real nested gesture from scrollToItem. */
private class PageScrollBehavior(private val native: ScrollBehavior) : ScrollBehavior by native {
    var nativeScrollInProgress by mutableStateOf(false)
        private set

    override val nestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) nativeScrollInProgress = true
            return native.nestedScrollConnection.onPreScroll(available, source)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) nativeScrollInProgress = true
            return native.nestedScrollConnection.onPostScroll(consumed, available, source)
        }

        override suspend fun onPreFling(available: Velocity): Velocity =
            native.nestedScrollConnection.onPreFling(available)

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = try {
            native.nestedScrollConnection.onPostFling(consumed, available)
        } finally {
            nativeScrollInProgress = false
        }
    }
}

/** Include title displacement in the legacy non-list host, without changing native physics. */
@Composable
internal fun rememberMiuixPageScrollBehavior(
    state: TopAppBarState = rememberTopAppBarState(),
    listState: LazyListState? = null,
): ScrollBehavior {
    val native = MiuixScrollBehavior(state = state)
    val behavior = remember(native) { PageScrollBehavior(native) }
    LaunchedEffect(state, listState) {
        if (listState != null) return@LaunchedEffect
        var previous = state.heightOffset
        state.contentOffset = minOf(state.contentOffset, previous)
        snapshotFlow { state.heightOffset }.collect { height ->
            val delta = height - previous
            previous = height
            if (delta != 0f) state.contentOffset = (state.contentOffset + delta).coerceAtMost(0f)
        }
    }
    return behavior
}

private fun LazyListState.materialScrollDistance(): Float {
    val layout = layoutInfo
    val items = layout.visibleItemsInfo
    val averageSpan = if (items.isEmpty()) 0f else
        items.sumOf { it.size }.toFloat() / items.size + layout.mainAxisItemSpacing
    return miuixListScrollDistance(firstVisibleItemIndex, firstVisibleItemScrollOffset, averageSpan)
}

private data class ListScrollObservation(
    val index: Int,
    val offset: Int,
    val scrolling: Boolean,
    val itemCount: Int,
    val nativeGesture: Boolean,
)

/**
 * List position, not native contentOffset, is authoritative after programmatic navigation.
 * The callback is read in vendor draw/layout phases, so even an animated cold-load jump
 * updates the progressive band before the animation has finished. Only title restoration
 * at selection/programmatic-scroll completion writes native state; idle frames do not.
 */
@Composable
internal fun rememberMiuixPageVisualOffset(
    behavior: ScrollBehavior,
    listState: LazyListState?,
): () -> Float {
    val state = behavior.state
    LaunchedEffect(listState, behavior) {
        val list = listState ?: return@LaunchedEffect
        // heightOffsetLimit is a plain vendor field, not snapshot state.
        while (state.heightOffsetLimit == -Float.MAX_VALUE) withFrameNanos { }
        val restoreGate = MiuixPagePositionRestoreGate()
        snapshotFlow {
            ListScrollObservation(
                list.firstVisibleItemIndex,
                list.firstVisibleItemScrollOffset,
                list.isScrollInProgress,
                list.layoutInfo.totalItemsCount,
                (behavior as? PageScrollBehavior)?.nativeScrollInProgress == true,
            )
        }.collect { observation ->
            if (restoreGate.observe(
                    index = observation.index,
                    offset = observation.offset,
                    scrolling = observation.scrolling,
                    hasItems = observation.itemCount > 0,
                    nativeGesture = observation.nativeGesture,
                    canScrollBackward = list.canScrollBackward,
                )) {
                val distance = list.materialScrollDistance()
                state.heightOffset = if (list.canScrollBackward) state.heightOffsetLimit else 0f
                state.contentOffset = miuixPageVisualOffset(distance, state.heightOffset, 0f)
            }
        }
    }
    return remember(listState, state) {
        {
            if (listState == null) {
                minOf(state.contentOffset, state.heightOffset) + state.overscrollOffset
            } else {
                miuixPageVisualOffset(
                    listState.materialScrollDistance(), state.heightOffset, state.overscrollOffset,
                )
            }
        }
    }
}
