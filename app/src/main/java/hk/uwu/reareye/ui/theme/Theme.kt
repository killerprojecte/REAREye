package hk.uwu.reareye.ui.theme

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import hk.uwu.reareye.R
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixColorSchemeMode

enum class AppThemeMode(
    val value: Int,
    @param:StringRes val titleRes: Int,
    val colorSchemeMode: MixColorSchemeMode,
    val backend: MixUiBackend = MixUiBackend.Miuix,
    val selectionValue: Int = value,
) {
    MIUIX_SYSTEM(
        value = 3,
        titleRes = R.string.module_theme_mode_miuix_system,
        colorSchemeMode = MixColorSchemeMode.System,
    ),
    MIUIX_LIGHT(
        value = 0,
        titleRes = R.string.module_theme_mode_miuix_light,
        colorSchemeMode = MixColorSchemeMode.Light,
    ),
    MIUIX_DARK(
        value = 1,
        titleRes = R.string.module_theme_mode_miuix_dark,
        colorSchemeMode = MixColorSchemeMode.Dark,
    ),
    MONET_SYSTEM(
        value = 2,
        titleRes = R.string.module_theme_mode_monet_system,
        colorSchemeMode = MixColorSchemeMode.MonetSystem,
    ),
    MONET_LIGHT(
        value = 4,
        titleRes = R.string.module_theme_mode_monet_light,
        colorSchemeMode = MixColorSchemeMode.MonetLight,
    ),
    MONET_DARK(
        value = 5,
        titleRes = R.string.module_theme_mode_monet_dark,
        colorSchemeMode = MixColorSchemeMode.MonetDark,
    ),
    MOCHI_SYSTEM(6, R.string.module_theme_mode_mochi_system, MixColorSchemeMode.System, MixUiBackend.Mochi),
    MOCHI_LIGHT(7, R.string.module_theme_mode_mochi_light, MixColorSchemeMode.Light, MixUiBackend.Mochi),
    MOCHI_DARK(8, R.string.module_theme_mode_mochi_dark, MixColorSchemeMode.Dark, MixUiBackend.Mochi),
    // Migration-only values preserve earlier Mochi + Monet choices without adding menu entries.
    LEGACY_MOCHI_MONET_SYSTEM(9, R.string.module_theme_mode_mochi_system, MixColorSchemeMode.MonetSystem, MixUiBackend.Mochi, 6),
    LEGACY_MOCHI_MONET_LIGHT(10, R.string.module_theme_mode_mochi_light, MixColorSchemeMode.MonetLight, MixUiBackend.Mochi, 7),
    LEGACY_MOCHI_MONET_DARK(11, R.string.module_theme_mode_mochi_dark, MixColorSchemeMode.MonetDark, MixUiBackend.Mochi, 8);

    fun isDark(systemDark: Boolean): Boolean {
        return when (colorSchemeMode) {
            MixColorSchemeMode.System, MixColorSchemeMode.MonetSystem -> systemDark
            MixColorSchemeMode.Light, MixColorSchemeMode.MonetLight -> false
            MixColorSchemeMode.Dark, MixColorSchemeMode.MonetDark -> true
        }
    }

    companion object {
        val default = MIUIX_SYSTEM
        val selectableEntries = listOf(
            MIUIX_SYSTEM,
            MIUIX_LIGHT,
            MIUIX_DARK,
            MONET_SYSTEM,
            MONET_LIGHT,
            MONET_DARK,
            MOCHI_SYSTEM,
            MOCHI_LIGHT,
            MOCHI_DARK,
        )

        fun fromValue(value: Int): AppThemeMode {
            return entries.firstOrNull { it.value == value } ?: default
        }
    }
}

@Composable
@ReadOnlyComposable
fun isAppInDarkTheme(themeMode: AppThemeMode): Boolean {
    return themeMode.isDark(isSystemInDarkTheme())
}

@Composable
fun AppTheme(
    themeMode: AppThemeMode = AppThemeMode.default,
    mochiStyle: MixMochiStyle = MixMochiStyle.Default,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isAppInDarkTheme(themeMode)

    MixUiHost(backend = themeMode.backend, colorMode = themeMode.colorSchemeMode, mochiStyle = mochiStyle) {
        LaunchedEffect(darkTheme) {
            val window = (context as? Activity)?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
        content()
    }
}
