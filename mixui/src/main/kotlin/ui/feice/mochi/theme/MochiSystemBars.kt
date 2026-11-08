// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 状态栏、导航栏的图标跟着主题明暗走：浅色主题用深色图标，深色主题用浅色图标。
 * 和 HDR 一样只由最外层（最先出现）的 MochiTheme 管，里层强制深色的子树（看图页之类）不会把整个窗口改掉。
 * 只在明暗变化时写一次窗口：之后页面自己改（比如全屏视频要浅色图标），不会被每次重组改回来。
 */
@Composable
internal fun MochiSystemBarsEffect(dark: Boolean) {
    val view = LocalView.current
    val token = remember { Any() }
    DisposableEffect(token) {
        onDispose { if (SystemBarsOwner.owner === token) SystemBarsOwner.owner = null }
    }
    DisposableEffect(view, dark) {
        if (SystemBarsOwner.owner == null) SystemBarsOwner.owner = token
        val window = view.context.findActivity()?.window
        if (SystemBarsOwner.owner === token && window != null && !view.isInEditMode) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        onDispose { }
    }
}

private object SystemBarsOwner {
    var owner: Any? = null
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
