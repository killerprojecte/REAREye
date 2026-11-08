// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.basic

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** inverseOnSurface 在部分动态配色中偏灰；默认提示前景向同明暗方向提至高对比，保留可用的色相。 */
internal fun toastContentColor(container: Color, preferred: Color): Color {
    fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }
    val base = preferred.copy(alpha = 1f)
    if (contrast(base, container) >= 12f) return base
    val extreme = if (contrast(Color.White, container) >= contrast(Color.Black, container)) Color.White else Color.Black
    if (contrast(extreme, container) < 12f) return extreme
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        if (contrast(lerp(base, extreme, mid), container) >= 12f) high = mid else low = mid
    }
    return lerp(base, extreme, high)
}
