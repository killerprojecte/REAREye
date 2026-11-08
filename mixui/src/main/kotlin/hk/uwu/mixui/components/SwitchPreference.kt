package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme

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
        val colors = MixTheme.colorScheme
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .padding(insideMargin),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MixTheme.textStyles.body1,
                    color = if (enabled) colors.onSurface else colors.disabledOnSurface,
                )
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MixTheme.textStyles.body2,
                        color = if (enabled) colors.onSurfaceVariantSummary else colors.disabledOnSurface,
                    )
                }
            }
            // The row exposes one labelled accessibility toggle. Keep the native control's
            // pointer callback so Mochi's dragging and haptics still work on the thumb.
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}
