package hk.uwu.reareye.ui.capture

import androidx.compose.runtime.Composable
import hk.uwu.mixui.MixUiBackend
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.AppThemeMode

/** Kept separate so the same fixture body can be overlaid on the MIUIX-only baseline. */
@Composable
internal fun CaptureTheme(backend: String, dark: Boolean, content: @Composable () -> Unit) {
    AppTheme(
        themeMode = if (dark) AppThemeMode.MIUIX_DARK else AppThemeMode.MIUIX_LIGHT,
        backend = if (backend == "mochi") MixUiBackend.Mochi else MixUiBackend.Miuix,
        content = content,
    )
}

/** Supply the same overlay host that the production root provides. */
@Composable
internal fun CaptureDialogHost(content: @Composable () -> Unit) {
    hk.uwu.mixui.components.Scaffold(contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)) { content() }
}
