package hk.uwu.mixui.backend

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import com.materialkolor.dynamiccolor.DynamicScheme
import com.materialkolor.scheme.SchemeTonalSpot
import hk.uwu.mixui.MixColorSchemeMode
import ui.feice.mochi.theme.MochiColorScheme
import ui.feice.mochi.theme.DefaultKeyColor

/**
 * Both libraries share MaterialKolor 6 at runtime. Mochi 0.2's controller was built
 * against a binary-incompatible 4.x constructor, so mixui owns palette generation
 * and passes the public color data to Mochi, preserving its tonal-spot defaults.
 */
@Composable
internal fun rememberMochiPalette(mode: MixColorSchemeMode): MochiColorScheme {
    val dark = when (mode) {
        MixColorSchemeMode.Dark, MixColorSchemeMode.MonetDark -> true
        MixColorSchemeMode.Light, MixColorSchemeMode.MonetLight -> false
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val monet = mode in setOf(MixColorSchemeMode.MonetSystem, MixColorSchemeMode.MonetLight, MixColorSchemeMode.MonetDark)
    val seed = remember(context, configuration, monet) {
        if (monet) Color(context.getColor(android.R.color.system_accent1_500)) else DefaultKeyColor
    }
    return remember(seed, dark) { mochiPalette(seed, dark) }
}

internal fun mochiPalette(seed: Color, dark: Boolean): MochiColorScheme {
    val scheme = SchemeTonalSpot(sourceColorHct = Hct.fromInt(seed.toArgb()), isDark = dark,
        contrastLevel = 0.0, specVersion = ColorSpec.SpecVersion.SPEC_2025,
        platform = DynamicScheme.Platform.PHONE)
    return MochiColorScheme(
        isDark = dark,
        primary = Color(scheme.primary),
        onPrimary = Color(scheme.onPrimary),
        primaryContainer = Color(scheme.primaryContainer),
        onPrimaryContainer = Color(scheme.onPrimaryContainer),
        inversePrimary = Color(scheme.inversePrimary),
        secondary = Color(scheme.secondary),
        onSecondary = Color(scheme.onSecondary),
        secondaryContainer = Color(scheme.secondaryContainer),
        onSecondaryContainer = Color(scheme.onSecondaryContainer),
        tertiary = Color(scheme.tertiary),
        onTertiary = Color(scheme.onTertiary),
        tertiaryContainer = Color(scheme.tertiaryContainer),
        onTertiaryContainer = Color(scheme.onTertiaryContainer),
        background = Color(scheme.background),
        onBackground = Color(scheme.onBackground),
        surface = Color(scheme.surface),
        onSurface = Color(scheme.onSurface),
        surfaceVariant = Color(scheme.surfaceVariant),
        onSurfaceVariant = Color(scheme.onSurfaceVariant),
        surfaceTint = Color(scheme.surfaceTint),
        inverseSurface = Color(scheme.inverseSurface),
        inverseOnSurface = Color(scheme.inverseOnSurface),
        error = Color(scheme.error),
        onError = Color(scheme.onError),
        errorContainer = Color(scheme.errorContainer),
        onErrorContainer = Color(scheme.onErrorContainer),
        outline = Color(scheme.outline),
        outlineVariant = Color(scheme.outlineVariant),
        scrim = Color(scheme.scrim),
        shadow = Color(scheme.shadow),
        surfaceBright = Color(scheme.surfaceBright),
        surfaceDim = Color(scheme.surfaceDim),
        surfaceContainerLowest = Color(scheme.surfaceContainerLowest),
        surfaceContainerLow = Color(scheme.surfaceContainerLow),
        surfaceContainer = Color(scheme.surfaceContainer),
        surfaceContainerHigh = Color(scheme.surfaceContainerHigh),
        surfaceContainerHighest = Color(scheme.surfaceContainerHighest),
    )
}
