package hk.uwu.mixui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.backend.MiuixColors
import hk.uwu.mixui.backend.MiuixTextStyles

/** Application tokens. Neither renderer's theme types cross the module boundary. */
interface MixColors {
    val primary: Color
    val onPrimary: Color
    val primaryVariant: Color
    val onPrimaryVariant: Color
    val error: Color
    val onError: Color
    val errorContainer: Color
    val onErrorContainer: Color
    val disabledPrimary: Color
    val disabledOnPrimary: Color
    val disabledPrimaryButton: Color
    val disabledOnPrimaryButton: Color
    val disabledPrimarySlider: Color
    val primaryContainer: Color
    val onPrimaryContainer: Color
    val secondary: Color
    val onSecondary: Color
    val secondaryVariant: Color
    val onSecondaryVariant: Color
    val disabledSecondary: Color
    val disabledOnSecondary: Color
    val disabledSecondaryVariant: Color
    val disabledOnSecondaryVariant: Color
    val secondaryContainer: Color
    val onSecondaryContainer: Color
    val secondaryContainerVariant: Color
    val onSecondaryContainerVariant: Color
    val tertiaryContainer: Color
    val onTertiaryContainer: Color
    val tertiaryContainerVariant: Color
    val background: Color
    val onBackground: Color
    val onBackgroundVariant: Color
    val surface: Color
    val onSurface: Color
    val surfaceVariant: Color
    val onSurfaceSecondary: Color
    val onSurfaceVariantSummary: Color
    val onSurfaceVariantActions: Color
    val disabledOnSurface: Color
    val surfaceContainer: Color
    val onSurfaceContainer: Color
    val onSurfaceContainerVariant: Color
    val surfaceContainerHigh: Color
    val onSurfaceContainerHigh: Color
    val surfaceContainerHighest: Color
    val onSurfaceContainerHighest: Color
    val outline: Color
    val dividerLine: Color
    val windowDimming: Color
    val sliderKeyPoint: Color
    val sliderKeyPointForeground: Color
    val sliderBackground: Color
}

interface MixTextStyles {
    val main: TextStyle
    val paragraph: TextStyle
    val body1: TextStyle
    val body2: TextStyle
    val button: TextStyle
    val footnote1: TextStyle
    val footnote2: TextStyle
    val headline1: TextStyle
    val headline2: TextStyle
    val subtitle: TextStyle
    val title1: TextStyle
    val title2: TextStyle
    val title3: TextStyle
    val title4: TextStyle
}

@Immutable
data class MixShapes(
    val extraSmall: Dp = 8.dp,
    val small: Dp = 12.dp,
    val medium: Dp = 16.dp,
    val large: Dp = 20.dp,
    val extraLarge: Dp = 28.dp,
    val capsule: Dp = 40.dp,
) {
    val extraSmallShape: Shape = RoundedCornerShape(extraSmall)
    val smallShape: Shape = RoundedCornerShape(small)
    val mediumShape: Shape = RoundedCornerShape(medium)
    val largeShape: Shape = RoundedCornerShape(large)
    val extraLargeShape: Shape = RoundedCornerShape(extraLarge)
    val capsuleShape: Shape = CircleShape

    companion object {
        val Default = MixShapes()
    }
}

internal val LocalMixColors = staticCompositionLocalOf<MixColors?> { null }
internal val LocalMixTextStyles = staticCompositionLocalOf<MixTextStyles?> { null }
internal val LocalMixShapes = staticCompositionLocalOf { MixShapes.Default }

object MixTheme {
    val colorScheme: MixColors
        @Composable @ReadOnlyComposable get() = LocalMixColors.current ?: MiuixColors(top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme)
    val textStyles: MixTextStyles
        @Composable @ReadOnlyComposable get() = LocalMixTextStyles.current ?: MiuixTextStyles(top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles)
    val shapes: MixShapes
        @Composable @ReadOnlyComposable get() = LocalMixShapes.current
}
