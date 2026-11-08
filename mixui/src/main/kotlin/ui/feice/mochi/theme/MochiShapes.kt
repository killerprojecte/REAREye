// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.shape.MochiRoundedRectangle

/**
 * MD3 Expressive 形状刻度。全部是 Kyant0/Capsule 默认的连续曲率（G2）圆角（[ui.feice.mochi.shape.MochiContinuity]）；[full] 是胶囊。
 */
@Immutable
public data class MochiShapes(
    val extraSmall: Shape = MochiRoundedRectangle(4.dp),
    val small: Shape = MochiRoundedRectangle(8.dp),
    val medium: Shape = MochiRoundedRectangle(12.dp),
    val large: Shape = MochiRoundedRectangle(16.dp),
    val largeIncreased: Shape = MochiRoundedRectangle(20.dp),
    val extraLarge: Shape = MochiRoundedRectangle(28.dp),
    val extraLargeIncreased: Shape = MochiRoundedRectangle(32.dp),
    val extraExtraLarge: Shape = MochiRoundedRectangle(48.dp),
    val full: Shape = MochiCapsule,
)

public fun defaultShapes(): MochiShapes = MochiShapes()
