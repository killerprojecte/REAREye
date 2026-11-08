// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.shape

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp

/*
 * 同心圆角规范：容器里的元素和容器四周留了 inset 的间距时，元素的圆角 = 容器圆角 − inset，
 * 两条圆弧同心，转角处间距处处相等（iOS 26 的 concentric corners）。
 * - 胶囊套胶囊天然同心（高度差 = 2 × inset）
 * - 间距大于容器圆角时内圈退化成 minimum，不会变成负数或直角
 * 用法：容器用 MochiConcentricContainer(corner, padding) 包住内容，里面的元素用 MochiConcentric.shape() 取形状；
 * 多层嵌套时每一层都按自己的 padding 往里减。
 */
public object MochiConcentric {
    /** 默认最小圆角：间距太大时内圈也不会变成直角 */
    public val Minimum: Dp = 4.dp

    /** 和外圈圆角 outer 同心、离外圈 inset 的内圈圆角 */
    public fun inner(outer: Dp, inset: Dp, minimum: Dp = Minimum): Dp = (outer - inset).coerceAtLeast(minimum)

    /** 当前容器里贴着内边距放的元素该用的圆角；不在 [MochiConcentricContainer] 里时是 null */
    public val current: Dp?
        @Composable @ReadOnlyComposable
        get() = LocalMochiConcentricCorner.current

    /** 当前容器里贴着内边距放的元素该用的形状（连续曲率圆角）；不在容器里时用 fallback 圆角 */
    @Composable
    @ReadOnlyComposable
    public fun shape(fallback: Dp = 16.dp, extraInset: Dp = 0.dp): Shape {
        val r = LocalMochiConcentricCorner.current?.let { inner(it, extraInset) } ?: fallback
        return MochiRoundedRectangle(r)
    }
}

/**
 * 同心容器：告诉里面的元素「外圈圆角是 corner、四周留了 padding」，
 * 里面用 [MochiConcentric.current] / [MochiConcentric.shape] 取到对齐的圆角。只提供数值，不改布局。
 */
@Composable
public fun MochiConcentricContainer(corner: Dp, padding: Dp, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMochiConcentricCorner provides MochiConcentric.inner(corner, padding), content = content)
}

internal val LocalMochiConcentricCorner = staticCompositionLocalOf<Dp?> { null }
