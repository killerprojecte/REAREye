package hk.uwu.reareye.ui.components.config.draggable.library.draggable.hijacker

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import java.lang.reflect.Field

interface LazyHijackerReflectionProvider {
    val firstVisibleItemIndex: Int

    fun getScrollPositionField(): Field
    fun getState(): Any
}

internal class LazyListHijackerReflectionProvider(
    private val listState: LazyListState,
) : LazyHijackerReflectionProvider {
    override val firstVisibleItemIndex: Int
        get() = listState.firstVisibleItemIndex

    override fun getScrollPositionField(): Field {
        return listState.javaClass.getDeclaredField("scrollPosition").apply {
            isAccessible = true
        }
    }

    override fun getState(): Any {
        return listState
    }
}

internal class LazyGridHijackerReflectionProvider(
    private val gridState: LazyGridState,
) : LazyHijackerReflectionProvider {
    override val firstVisibleItemIndex: Int
        get() = gridState.firstVisibleItemIndex

    override fun getScrollPositionField(): Field {
        return gridState.javaClass.getDeclaredField("scrollPosition").apply {
            isAccessible = true
        }
    }

    override fun getState(): Any {
        return gridState
    }
}

internal class LazyStaggeredGridHijackerReflectionProvider(
    private val gridState: LazyStaggeredGridState,
) : LazyHijackerReflectionProvider {
    override val firstVisibleItemIndex: Int
        get() = gridState.firstVisibleItemIndex

    override fun getScrollPositionField(): Field {
        return gridState.javaClass.getDeclaredField("scrollPosition").apply {
            isAccessible = true
        }
    }

    override fun getState(): Any {
        return gridState
    }
}

