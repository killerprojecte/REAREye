// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.navigation

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView

/**
 * 屏幕四个物理圆角的半径（px）。转场时沿用这些半径生成 G2 连续曲率轮廓；不是用普通圆弧剪切。整屏静止时不额外处理轮廓。
 * 系统不提供（API 31 以下、屏幕本身是直角）时是 0，和屏幕一致。
 */
@Immutable
public data class MochiScreenCorners(val topLeft: Float, val topRight: Float, val bottomRight: Float, val bottomLeft: Float) {
    public companion object {
        /** 直角屏幕 */
        public val None: MochiScreenCorners = MochiScreenCorners(0f, 0f, 0f, 0f)
    }
}

/**
 * 读取当前屏幕的物理圆角（API 31+ 的 `WindowInsets.getRoundedCorner`），旋转屏幕后重新读取。
 * 窗口还没挂上时先返回 [MochiScreenCorners.None]，挂上后的第一帧更新。
 */
@Composable
public fun rememberMochiScreenCorners(): MochiScreenCorners {
    val view = LocalView.current
    val orientation = LocalConfiguration.current.orientation
    var corners by remember(view, orientation) { mutableStateOf(view.readScreenCorners()) }
    LaunchedEffect(view, orientation) {
        // insets 在窗口挂上、布局一次之后才有；最多等几帧
        repeat(10) {
            val c = view.readScreenCorners()
            if (c != null) {
                corners = c
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }
    return corners ?: MochiScreenCorners.None
}

/** 还拿不到 insets 时返回 null */
private fun View.readScreenCorners(): MochiScreenCorners? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return MochiScreenCorners.None
    val insets = rootWindowInsets ?: return null
    fun r(position: Int): Float = insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
    return MochiScreenCorners(
        topLeft = r(RoundedCorner.POSITION_TOP_LEFT),
        topRight = r(RoundedCorner.POSITION_TOP_RIGHT),
        bottomRight = r(RoundedCorner.POSITION_BOTTOM_RIGHT),
        bottomLeft = r(RoundedCorner.POSITION_BOTTOM_LEFT),
    )
}
