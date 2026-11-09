package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import kotlinx.collections.immutable.persistentListOf

/**
 * Native in-window editable form. Keep composed during exit; the backend owns
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
        MixUiBackend.Mochi -> ui.feice.mochi.basic.MochiDialog(
            visible = show,
            title = title,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            actions = persistentListOf(),
            dismissOnOutsideTap = true,
            dismissOnBack = true,
            content = { content() },
        )
    }
}
