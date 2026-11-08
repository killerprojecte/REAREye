// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.anim

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.PI

/**
 * 用「时长 + 回弹」写弹簧（SwiftUI 的 `Spring(duration:bounce:)`）：
 * - 刚度 k = (2π / duration)²（质量 1），duration 是一个周期的时长
 * - bounce ≥ 0：阻尼比 = 1 − bounce（0 = 临界阻尼不过冲，越大回弹越多）
 * - bounce < 0：阻尼比 = 1 / (1 + bounce)（过阻尼，越负越「黏」）
 */
public fun <T> mochiSpring(durationMillis: Int = 500, bounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> {
    require(durationMillis > 0) { "durationMillis must be positive" }
    val b = bounce.coerceIn(-0.99999, 1.0)
    val d = durationMillis / 1000.0
    val stiffness = (2.0 * PI / d).let { it * it }.toFloat()
    val damping = if (b >= 0) (1.0 - b).toFloat() else (1.0 / (1.0 + b)).toFloat()
    return spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
}

/**
 * SwiftUI 的四种弹簧预设。extraBounce 在预设的回弹上再加一点（可以是负的）。
 * 内容变换（模糊替换、计数器、图标变形）默认用 [snappy]，时长 300ms。
 */
public object MochiSprings {
    /** 不回弹，平滑停住 */
    public fun <T> smooth(durationMillis: Int = 500, extraBounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> =
        mochiSpring(durationMillis, 0.0 + extraBounce, visibilityThreshold)

    /** 一点点回弹（0.1） */
    public fun <T> crisp(durationMillis: Int = 300, extraBounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> =
        mochiSpring(durationMillis, 0.1 + extraBounce, visibilityThreshold)

    /** 利落、带一点回弹（0.15） */
    public fun <T> snappy(durationMillis: Int = 500, extraBounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> =
        mochiSpring(durationMillis, 0.15 + extraBounce, visibilityThreshold)

    /** 明显回弹（0.3） */
    public fun <T> bouncy(durationMillis: Int = 500, extraBounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> =
        mochiSpring(durationMillis, 0.3 + extraBounce, visibilityThreshold)
}
