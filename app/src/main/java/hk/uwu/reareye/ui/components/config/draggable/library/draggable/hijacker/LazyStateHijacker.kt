package hk.uwu.reareye.ui.components.config.draggable.library.draggable.hijacker

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember

@Composable
fun rememberLazyGridStateHijacker(
    gridState: LazyGridState,
    enabled: Boolean = true
): LazyStateHijacker {
    return remember(gridState) {
        LazyStateHijacker(LazyGridHijackerReflectionProvider(gridState), enabled)
    }.apply {
        this.enabled = enabled
    }
}

@Composable
fun rememberLazyListHijacker(
    listState: LazyListState,
    enabled: Boolean = true
): LazyStateHijacker {
    return remember(listState) {
        LazyStateHijacker(LazyListHijackerReflectionProvider(listState), enabled)
    }.apply {
        this.enabled = enabled
    }
}

/*
 * The following code is derived from lazylist-state-hijack, which is under the MIT License.
 * Copyright (c) 2023 by Gergely Kőrössy
 *
 * Source: https://github.com/gregkorossy/lazylist-state-hijack
 *
 * MIT License:
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
class LazyStateHijacker(
    private val fieldProvider: LazyHijackerReflectionProvider,
    enabled: Boolean = true
) {
    private val scrollPositionField = fieldProvider.getScrollPositionField()
    private val scrollPositionObj = scrollPositionField.get(fieldProvider.getState())

    private val lastKeyRemover: () -> Unit =
        scrollPositionField.type.getDeclaredField("lastKnownFirstItemKey").run {
            isAccessible = true

            fun() { set(scrollPositionObj, null) }
        }

    private val indexField = scrollPositionField.type.getDeclaredField("index\$delegate").apply {
        isAccessible = true
    }

    /**
     * Controls whether the hijack implementation is used or the default one.
     *
     * *Note: this is not backed by [androidx.compose.runtime.State]. You should not listen to the
     * changes of this in compose.*
     */
    var enabled: Boolean = enabled
        set(value) {
            if (field == value) {
                return
            }

            field = value

            setProps(value)
        }

    init {
        setProps(enabled)
    }

    private fun setProps(enable: Boolean) {
        val oldValue = indexField.get(scrollPositionObj).run {
            if (this is IntStateHijacker) {
                intValueDirect
            } else {
                fieldProvider.firstVisibleItemIndex
            }
        }

        val mutableIntState: MutableIntState = if (enable) {
            IntStateHijacker(
                state = mutableIntStateOf(oldValue),
                keyRemover = lastKeyRemover
            )
        } else {
            mutableIntStateOf(oldValue)
        }

        indexField.set(scrollPositionObj, mutableIntState)
    }
}

