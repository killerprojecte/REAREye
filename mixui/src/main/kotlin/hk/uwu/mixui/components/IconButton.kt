package hk.uwu.mixui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.motion.mixPressFeedback

object IconButtonDefaults {
    val CornerRadius = 40.dp
    val MinHeight = 40.dp
    val MinWidth = 40.dp
}

/** Compact action with a shared footprint and persistent popup-selection feedback. */
@Composable
fun IconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
    backgroundColor: Color = Color.Unspecified,
    cornerRadius: Dp = IconButtonDefaults.CornerRadius,
    minHeight: Dp = IconButtonDefaults.MinHeight,
    minWidth: Dp = IconButtonDefaults.MinWidth,
    content: @Composable () -> Unit,
) {
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.basic.IconButton(
            onClick, modifier.mixPressFeedback(enabled), enabled, holdDownState,
            backgroundColor, cornerRadius, minHeight, minWidth, content,
        )
        MixUiBackend.Mochi -> {
            val scale by animateFloatAsState(if (enabled && holdDownState) 0.96f else 1f, label = "icon-selection")
            val color = if (backgroundColor == Color.Unspecified) Color.Transparent else backgroundColor
            val colors = ui.feice.mochi.basic.MochiButtonDefaults.colors().copy(
                container = color, disabledContainer = color,
                content = currentContentColor(), disabledContent = currentContentColor().copy(alpha = 0.38f),
            )
            // MochiButton retains rectangular constraints, unlike its fixed-size icon variant.
            ui.feice.mochi.basic.MochiButton(
                onClick = onClick,
                // Keep the native button action independent inside clickable cards.
                // Mochi owns onClick; this only prevents its semantics merging into the parent.
                modifier = modifier.semantics(mergeDescendants = true) {}
                    .defaultMinSize(minWidth = minWidth).graphicsLayer { scaleX = scale; scaleY = scale },
                enabled = enabled, colors = colors, cornerRadius = cornerRadius,
                minHeight = minHeight, contentPadding = PaddingValues(0.dp),
            ) { content() }
        }
    }
}
