package hk.uwu.reareye.ui.capture

import androidx.compose.runtime.Composable
import hk.uwu.mixui.MixMochiStyle
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.AppThemeMode

/** Kept separate so the same fixture body can be overlaid on the MIUIX-only baseline. */
@Composable
internal fun CaptureTheme(
    backend: String,
    dark: Boolean,
    mochiStyle: MixMochiStyle = MixMochiStyle.Default,
    themeModeOverride: AppThemeMode? = null,
    content: @Composable () -> Unit,
) {
    AppTheme(
        themeMode = themeModeOverride ?: if (backend == "mochi") {
            if (dark) AppThemeMode.MOCHI_DARK else AppThemeMode.MOCHI_LIGHT
        } else {
            if (dark) AppThemeMode.MIUIX_DARK else AppThemeMode.MIUIX_LIGHT
        },
        mochiStyle = mochiStyle,
        content = content,
    )
}

/** Supply the same overlay host that the production root provides. */
@Composable
internal fun CaptureDialogHost(content: @Composable () -> Unit) {
    hk.uwu.mixui.components.Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { content() }
}
