package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import hk.uwu.mixui.theme.MixTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend

/** Native sheet lifetime. Callers own drafts and commit only from their Save action. */
@Composable
fun BottomSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    title: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    endAction: (@Composable () -> Unit)? = null,
    allowDismiss: Boolean = true,
    content: @Composable () -> Unit,
) {
    when (LocalMixUiBackend.current) {
        // The scaffold overlay host executes its content in the app's composition context.
        // A window sheet retains the caller's theme, including native title and actions,
        // so a local preview can differ from the applied app theme without leaking out.
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.window.WindowBottomSheet(
            show = show, onDismissRequest = onDismissRequest,
            title = title, startAction = startAction, endAction = endAction,
            allowDismiss = allowDismiss,
        ) {
            // MIUIX handles the top/IME insets, but leaves navigation/caption bars to
            // its caller (as in its BottomSheetSection example). Reserve the visible
            // content viewport rather than putting an unreachable spacer after it.
            // Consumed IME insets are excluded by windowInsetsPadding automatically.
            Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                content()
            }
        }
        MixUiBackend.Mochi -> {
            val state = ui.feice.mochi.basic.rememberMochiSheetState(
                detents = listOf(ui.feice.mochi.basic.MochiSheetDetent.Large),
                initial = ui.feice.mochi.basic.MochiSheetDetent.Large,
            )
            val currentShow by rememberUpdatedState(show)
            val currentAllowDismiss by rememberUpdatedState(allowDismiss)
            // Mochi has no public dismissal veto. Recover visibility after native hide begins;
            // this is not gesture blocking and may briefly reverse the dismissal animation.
            LaunchedEffect(state) {
                snapshotFlow { state.isVisible }.collect { visible ->
                    if (!visible && currentShow && !currentAllowDismiss) state.show()
                }
            }
            LaunchedEffect(show, allowDismiss) { if (show) state.show() else state.hide() }
            ui.feice.mochi.basic.MochiSheet(state = state, onDismiss = {
                if (currentShow && !currentAllowDismiss) state.show() else onDismissRequest()
            }) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    if (title != null || startAction != null || endAction != null) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Box(Modifier.align(Alignment.CenterStart)) { startAction?.invoke() }
                            title?.let { Text(it, Modifier.align(Alignment.Center), style = MixTheme.textStyles.title3) }
                            Box(Modifier.align(Alignment.CenterEnd)) { endAction?.invoke() }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    content()
                }
            }
        }
    }
}
