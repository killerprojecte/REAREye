package hk.uwu.mixui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import hk.uwu.mixui.LocalMixUiRenderer
import hk.uwu.mixui.theme.MixTheme

@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) =
    LocalMixUiRenderer.current.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier
            .semantics { if (!enabled) disabled() }
            .mixKeyboardFocusRing(
                enabled = enabled && onCheckedChange != null,
                cornerRadius = MixTheme.shapes.capsule,
                matchDescendantFocus = true,
            ),
        enabled = enabled,
    )

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = MixTheme.shapes.medium,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: ButtonColors = glassFormButtonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    content: @Composable RowScope.() -> Unit,
) = LocalMixUiRenderer.current.Button(
    onClick = onClick,
    modifier = modifier
        .semantics(mergeDescendants = true) {
            role = Role.Button
            if (!enabled) disabled()
        }
        .mixKeyboardFocusRing(
            enabled = enabled,
            cornerRadius = cornerRadius,
            matchDescendantFocus = true,
        ),
    enabled = enabled,
    cornerRadius = cornerRadius,
    minWidth = minWidth,
    minHeight = minHeight,
    colors = colors,
    insideMargin = insideMargin,
    content = content,
)
