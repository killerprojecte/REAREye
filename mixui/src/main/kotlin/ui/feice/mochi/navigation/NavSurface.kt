// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package ui.feice.mochi.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import ui.feice.mochi.effect.glass.GlassFill

/**
 * Visual layers follow the enclosing surface; page content retains its stable, safe measurement.
 * [glass]：面板是玻璃（页面透明、透出面板的玻璃）。这时每页的底色按 [opaque]（面板此刻的不透明度）画，侧推时下层页淡出
 */
internal class MochiNavSurface(
    val color: Color,
    val corners: () -> MochiScreenCorners,
    val height: () -> Int,
    val contentHeight: () -> Int,
    val glass: GlassFill? = null,
    val opaque: () -> Float = { 1f },
)

internal val LocalMochiNavSurface = staticCompositionLocalOf<MochiNavSurface?> { null }
