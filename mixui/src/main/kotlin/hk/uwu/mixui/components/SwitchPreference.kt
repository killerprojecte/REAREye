package hk.uwu.mixui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend

/** A labelled switch row whose public contract is independent of either UI library. */
@Composable
fun SwitchPreference(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    insideMargin: PaddingValues = PaddingValues(16.dp),
) {
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        top.yukonga.miuix.kmp.preference.SwitchPreference(
            checked = checked,
            onCheckedChange = onCheckedChange,
            title = title,
            modifier = modifier,
            summary = summary,
            enabled = enabled,
            insideMargin = insideMargin,
        )
    } else {
        MochiPreferenceContent(summary = summary, modifier = modifier, enabled = enabled,
            onClick = { onCheckedChange(!checked) }) {
            ui.feice.mochi.list.MochiListRow(
                title = title,
                enabled = enabled,
                onClick = { if (enabled) onCheckedChange(!checked) },
                modifier = Modifier.semantics {
                    role = Role.Switch
                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                },
                trailing = {
                    ui.feice.mochi.basic.MochiSwitch(
                        checked = checked,
                        onCheckedChange = onCheckedChange,
                        enabled = enabled,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                },
            )
        }
    }
}
