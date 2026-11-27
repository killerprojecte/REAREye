// Copyright 2026, compose-miuix-ui contributors
// SPDX-License-Identifier: Apache-2.0

package hk.uwu.mixui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import hk.uwu.mixui.motion.rememberReduceMotionEnabled

@Composable
internal fun rememberFrameTimeSeconds(
    playing: Boolean = true,
): () -> Float {
    val active = playing && !rememberReduceMotionEnabled()
    var time by remember { mutableFloatStateOf(0f) }
    var startOffset by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(active) {
        if (!active) {
            startOffset = time
            return@LaunchedEffect
        }

        val start = withFrameNanos { it }

        while (active) {
            val now = withFrameNanos { it }

            time =
                startOffset +
                        (now - start) / 1_000_000_000f
        }
    }

    return { time }
}
