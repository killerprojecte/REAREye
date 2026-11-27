package hk.uwu.mixui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme

/** Ordinary solid/tinted surface. Mochi 0.2 has no plain surface, so use neutral Compose
 * layout with its theme colors, never substitute a glass container. */
@Composable
fun Surface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MixTheme.colorScheme.surface,
    contentColor: Color = MixTheme.colorScheme.onSurface,
    border: BorderStroke? = null,
    shadowElevation: Dp = 0.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
    content: @Composable () -> Unit,
) {
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        if (onClick == null) top.yukonga.miuix.kmp.basic.Surface(
            modifier = modifier, shape = shape, color = color, contentColor = contentColor,
            border = border, shadowElevation = shadowElevation, content = content,
        ) else top.yukonga.miuix.kmp.basic.Surface(
            onClick = onClick, enabled = enabled, modifier = modifier,
            shape = shape, color = color, contentColor = contentColor,
            border = border, shadowElevation = shadowElevation,
            interactionSource = interactionSource, indication = indication, content = content,
        )
    } else {
        val source = interactionSource ?: remember { MutableInteractionSource() }
        val interaction = if (onClick == null) Modifier.semantics { isTraversalGroup = true }
        else Modifier.clickable(interactionSource = source, indication = indication,
            enabled = enabled, onClick = onClick)
        CompositionLocalProvider(
            ui.feice.mochi.theme.LocalContentColor provides contentColor,
            top.yukonga.miuix.kmp.theme.LocalContentColor provides contentColor,
        ) {
            Box(
                modifier.shadow(shadowElevation, shape, clip = false)
                    .then(if (border != null) Modifier.border(border, shape) else Modifier)
                    .clip(shape).background(color).then(interaction),
                propagateMinConstraints = true,
            ) { content() }
        }
    }
}
