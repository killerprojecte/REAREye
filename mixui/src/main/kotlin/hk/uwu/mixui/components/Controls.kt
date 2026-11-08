package hk.uwu.mixui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hk.uwu.mixui.LocalMixUiRenderer

@Composable
fun Switch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) =
    LocalMixUiRenderer.current.Switch(checked, onCheckedChange, modifier, enabled)

@Composable
fun Button(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = ButtonDefaults.CornerRadius,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = ButtonDefaults.MinHeight,
    colors: ButtonColors = glassFormButtonColors(),
    insideMargin: PaddingValues = ButtonDefaults.InsideMargin,
    content: @Composable RowScope.() -> Unit,
) {
    val form = LocalGlassFormStyle.current
    // Explicit primary/custom colors keep their original appearance.
    val outlined = if (form != null && colors == form.buttonColors()) {
        modifier.glassFormOutline(form.outline.copy(alpha = if (enabled) 1f else 0.38f), cornerRadius)
    } else modifier
    LocalMixUiRenderer.current.Button(onClick, outlined, enabled, cornerRadius, minWidth, minHeight, colors, insideMargin, content)
}
