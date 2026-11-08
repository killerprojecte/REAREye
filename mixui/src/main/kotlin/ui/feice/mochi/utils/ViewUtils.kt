// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.utils

import android.view.View
import androidx.compose.ui.geometry.Offset

/** 当前窗口左上角在屏幕上的位置。按钮和浮层都换算到屏幕坐标再对齐。 */
internal fun View.windowOriginOnScreen(): Offset {
    val onScreen = IntArray(2)
    val inWindow = IntArray(2)
    getLocationOnScreen(onScreen)
    getLocationInWindow(inWindow)
    return Offset((onScreen[0] - inWindow[0]).toFloat(), (onScreen[1] - inWindow[1]).toFloat())
}
