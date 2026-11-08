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
import hk.uwu.mixui.MixUiHost
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.MixColorSchemeMode

enum class AppThemeMode(
    val value: Int,
    @param:StringRes val titleRes: Int,
    val colorSchemeMode: MixColorSchemeMode,
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
    );

    fun isDark(systemDark: Boolean): Boolean {
        return when (this) {
            MIUIX_SYSTEM -> systemDark
            MIUIX_LIGHT -> false
            MIUIX_DARK -> true
            MONET_SYSTEM -> systemDark
            MONET_LIGHT -> false
            MONET_DARK -> true
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
    backend: MixUiBackend = MixUiBackend.Miuix,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val darkTheme = isAppInDarkTheme(themeMode)

    MixUiHost(backend = backend, colorMode = themeMode.colorSchemeMode) {
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
