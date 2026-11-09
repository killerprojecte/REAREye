package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend

/** Boolean selection with vendor-owned visuals, animation and input semantics. */
@Composable
fun Checkbox(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.basic.Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onCheckedChange?.let { callback -> { callback(!checked) } },
            modifier = modifier, enabled = enabled,
        )
        MixUiBackend.Mochi -> ui.feice.mochi.basic.MochiCheckbox(
            checked = checked, onCheckedChange = onCheckedChange,
            modifier = modifier, enabled = enabled,
        )
    }
}
