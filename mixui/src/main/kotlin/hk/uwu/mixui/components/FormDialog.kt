package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
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
            renderInRootScaffold = true,
            content = content,
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
