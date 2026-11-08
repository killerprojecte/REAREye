package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Ordinary native actions, ordered cancel then confirm in the current layout direction. */
@Composable
fun DialogActions(
    cancelText: String,
    confirmText: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    confirmEnabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(cancelText) }
        Button(onClick = onConfirm, enabled = confirmEnabled,
            colors = ButtonDefaults.buttonColorsPrimary(), modifier = Modifier.weight(1f)) { Text(confirmText) }
    }
}
