package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.theme.MixTheme

/**
 * Draws a high-contrast focus ring only when focused in non-touch (Keyboard / D-pad) input mode.
 * Does not create an extra focus target node, preserving existing Tab traversal and touch visuals.
 */
@Composable
internal fun Modifier.mixKeyboardFocusRing(
    enabled: Boolean = true,
    cornerRadius: Dp = MixTheme.shapes.medium,
    matchDescendantFocus: Boolean = false,
): Modifier {
    if (!enabled) return this
    var focused by remember { mutableStateOf(false) }
    val isKeyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val outlineColor = MixTheme.colorScheme.primary
    return this
        .onFocusChanged { state ->
            focused = if (matchDescendantFocus) state.hasFocus else state.isFocused
        }
        .drawWithContent {
            drawContent()
            if (focused && isKeyboardMode) {
                val strokeWidth = 2.dp.toPx()
                val halfStroke = strokeWidth / 2f
                val maxRadius = ((minOf(size.width, size.height) - strokeWidth).coerceAtLeast(0f)) / 2f
                val radius = (cornerRadius.toPx() - halfStroke).coerceIn(0f, maxRadius)
                drawRoundRect(
                    color = outlineColor,
                    topLeft = Offset(halfStroke, halfStroke),
                    size = Size(
                        (size.width - strokeWidth).coerceAtLeast(0f),
                        (size.height - strokeWidth).coerceAtLeast(0f),
                    ),
                    cornerRadius = CornerRadius(radius, radius),
                    style = Stroke(width = strokeWidth),
                )
            }
        }
}
