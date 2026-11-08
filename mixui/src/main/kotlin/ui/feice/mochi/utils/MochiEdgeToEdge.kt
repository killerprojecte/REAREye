// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.utils

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * Mochi 应用的边到边设置：状态栏、导航栏完全透明，并关掉系统强制的对比度遮罩。
 *
 * 只调 `enableEdgeToEdge()` 时，导航栏用的是 `SystemBarStyle.auto`：系统会在导航栏（以及键盘弹出时它背后那条）
 * 强制铺一层半透明底色，和页面、面板的颜色对不上，看起来像一条隔断。Mochi 的页面自己处理安全区和滚动边缘，
 * 不需要这层遮罩。图标深浅由应用跟着自己的主题设置（`WindowCompat.getInsetsController`）。
 *
 * 在 `super.onCreate` 之前调用。
 */
public fun ComponentActivity.mochiEnableEdgeToEdge() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
        window.isStatusBarContrastEnforced = false
    }
}
