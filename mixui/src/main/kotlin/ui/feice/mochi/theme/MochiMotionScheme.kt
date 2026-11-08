// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package ui.feice.mochi.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable
import ui.feice.mochi.anim.MochiMotion
import ui.feice.mochi.anim.MochiMotions

/**
 * 动效 token。spatial（位置 / 尺寸，可以过冲）和 effects（透明度 / 颜色，不过冲）各分快 / 默认 / 慢，
 * 数值取自 MD3 Expressive（androidx ExpressiveMotionTokens）。
 * [menu] 是融球菜单整套的开关动画，默认是按 iOS 26 手感估的预设。
 */
@Immutable
public data class MochiMotionScheme(
    val fastSpatial: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.6f, stiffness = 800f),
    val defaultSpatial: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 380f),
    val slowSpatial: FiniteAnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 200f),
    val fastEffects: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 3800f),
    val defaultEffects: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 1600f),
    val slowEffects: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 800f),
    val menu: MochiMotion = MochiMotions.Ios26,
)

public fun defaultMotionScheme(): MochiMotionScheme = MochiMotionScheme()
