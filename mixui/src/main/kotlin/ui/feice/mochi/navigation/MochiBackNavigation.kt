// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

@file:Suppress("ktlint:compose:compositionlocal-allowlist")

package ui.feice.mochi.navigation

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/** Enables system predictive-back previews in navigation hosts and sheets; ordinary back remains available. */
public val LocalMochiPredictiveBack: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }
