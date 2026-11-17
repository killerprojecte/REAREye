package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Presentation belongs to the actual modal body, never the recorded page. */
internal enum class FormPresentation { Page, Dialog, Sheet }
internal val LocalFormPresentation = staticCompositionLocalOf { FormPresentation.Page }

@Composable
internal fun miuixSheetControlFill(enabled: Boolean = true): Color? {
    if (LocalMixUiBackend.current != MixUiBackend.Miuix ||
        LocalFormPresentation.current != FormPresentation.Sheet) return null
    val palette = MiuixTheme.colorScheme
    // Use the native disabled neutral, not a second alpha layer over the sheet.
    if (!enabled) return palette.disabledSecondaryVariant
    return if (palette.surface.luminance() > 0.5f) Color.White
        else palette.surfaceContainer.copy(alpha = 1f)
}

@Composable
internal fun Modifier.miuixSheetPreferenceSurface(enabled: Boolean = true): Modifier {
    val fill = miuixSheetControlFill(enabled) ?: return this
    val radius = top.yukonga.miuix.kmp.basic.CardDefaults.CornerRadius
    val squircleEnabled = isSquircleEnabled()
    return squircleBackground(color = fill, cornerRadius = radius)
        .drawWithCache {
            val outline = Path().apply {
                addSquircleRect(size.width, size.height, radius.toPx(), squircleEnabled = squircleEnabled)
            }
            onDrawWithContent {
                // Native rows paint rectangular pressed feedback. Clip that feedback
                // to the SAME native continuous curve, without an offscreen layer
                // that could hide popup/row content after switching sheet modes.
                clipPath(outline) { this@onDrawWithContent.drawContent() }
            }
        }
}

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
