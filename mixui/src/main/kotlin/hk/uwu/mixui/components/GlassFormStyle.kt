package hk.uwu.mixui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Glass forms keep ordinary native controls; only faint input labels gain contrast. */
internal val LocalGlassFormControlContrast = staticCompositionLocalOf { false }
