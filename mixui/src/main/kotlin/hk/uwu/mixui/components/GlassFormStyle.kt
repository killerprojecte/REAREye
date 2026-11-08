package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Ordinary, opaque native controls; glass is reserved for the dialog background. */
internal val LocalGlassFormControlContrast = staticCompositionLocalOf { false }

@Composable
internal fun glassFormControlFill(): Color? {
    if (!LocalGlassFormControlContrast.current || LocalMixUiBackend.current != MixUiBackend.Miuix) return null
    val palette = MiuixTheme.colorScheme
    // The normal light secondary container nearly disappears against glass.
    // Derive a stronger solid neutral from the ordinary native palette, without
    // adding glass, blur, an outline, or changing the control's native geometry.
    return if (palette.surface.luminance() > 0.5f)
        palette.onSurface.copy(alpha = 0.07f).compositeOver(palette.secondaryContainer).copy(alpha = 1f)
    else palette.secondaryContainer.copy(alpha = 1f)
}

@Composable
internal fun glassFormButtonColors(): ButtonColors {
    val fill = glassFormControlFill() ?: return ButtonDefaults.buttonColors()
    return ButtonDefaults.buttonColors(color = fill, contentColor = MiuixTheme.colorScheme.onSurface)
}
