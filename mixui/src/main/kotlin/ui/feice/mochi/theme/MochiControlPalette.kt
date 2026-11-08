// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist") // Deliberate theme token, alongside LocalMochiColorScheme.

package ui.feice.mochi.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Optional control colors; unspecified roles retain the original Mochi defaults. */
@Immutable
public data class MochiControlPalette(
    val thumb: Color,
    val inactiveTrack: Color = Color.Unspecified,
    val sliderThumb: Color = thumb,
    /** List row chevron; unspecified keeps the translucent Mochi tint. */
    val listChevron: Color = Color.Unspecified,
    /** Switch on-track; unspecified keeps the primary color (iOS uses its own green here). */
    val switchTrack: Color = Color.Unspecified,
    /** Translucent fill behind controls ([MochiDefaults.fill]); unspecified keeps the onSurface-based Mochi fill. */
    val fill: Color = Color.Unspecified,
    /** List row divider; unspecified keeps 60% outlineVariant. Translucent native separators can be supplied as-is. */
    val listDivider: Color = Color.Unspecified,
    /** Selected tab icon and label; unspecified keeps onSecondaryContainer on solid bars and primary on glass bars. */
    val tabSelected: Color = Color.Unspecified,
)

/** Appearance override for control defaults; null preserves the original Mochi colors. */
public val LocalMochiControlPalette: androidx.compose.runtime.ProvidableCompositionLocal<MochiControlPalette?> =
    staticCompositionLocalOf { null }
