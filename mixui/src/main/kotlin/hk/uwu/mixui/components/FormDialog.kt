package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend

/**
 * In-window editable form. Mochi uses mixui’s stable compatibility host. Keep composed during exit; the backend owns
 * root placement, keyboard avoidance and dismissal. Unlike GlassFormDialog this
 * does not require a recorded glass scene. The caller owns submit/cancel actions.
 */
@Composable
fun FormDialog(
    show: Boolean,
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.overlay.OverlayDialog(
            show = show,
            title = title,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            // A scene-backed Sheet already owns a full-window local Scaffold;
            // the page's root popup host is underneath that Sheet.
            renderInRootScaffold = LocalFormPresentation.current != FormPresentation.Sheet,
            content = {
                CompositionLocalProvider(LocalFormPresentation provides FormPresentation.Dialog) {
                    content()
                }
            },
        )
        MixUiBackend.Mochi -> StableMochiFormDialog(
            show = show,
            title = title,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            content = { content() },
        )
    }
}
