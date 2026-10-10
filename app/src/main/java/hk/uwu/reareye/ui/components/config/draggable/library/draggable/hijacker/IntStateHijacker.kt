package hk.uwu.reareye.ui.components.config.draggable.library.draggable.hijacker

import androidx.compose.runtime.MutableIntState


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
internal class IntStateHijacker(
    private val state: MutableIntState,
    private val keyRemover: () -> Unit
) : MutableIntState by state {
    override var intValue: Int
        get() {
            keyRemover()
            return state.intValue
        }
        set(value) {
            state.intValue = value
        }

    override var value: Int
        get() {
            keyRemover()
            return state.value
        }
        set(value) {
            state.value = value
        }

    /**
     * Provides direct access to the [state] object's `intValue` property without triggering the
     * [keyRemover].
     */
    val intValueDirect: Int get() = state.intValue
}
