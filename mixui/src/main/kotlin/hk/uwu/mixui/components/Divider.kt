package hk.uwu.mixui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.theme.MixTheme

/** Renderer-neutral divider; explicit caller colors and thickness remain unchanged. */
@Composable
fun HorizontalDivider(
    modifier: Modifier = Modifier,
    thickness: Dp = 0.75.dp,
    color: Color = MixTheme.colorScheme.dividerLine,
) {
    Canvas(modifier.fillMaxWidth().height(thickness)) {
        drawLine(color, Offset(0f, thickness.toPx() / 2),
            Offset(size.width, thickness.toPx() / 2), strokeWidth = thickness.toPx())
    }
}
