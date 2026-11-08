package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.motion.mixPressFeedback
import hk.uwu.mixui.theme.MixTheme

@Immutable
data class CardColors(val color: Color, val contentColor: Color)
enum class PressFeedbackType { None, Sink, Tilt }
object CardDefaults {
    val CornerRadius = 16.dp
    val InsideMargin = PaddingValues(0.dp)
    @Composable fun defaultColors(
        color: Color = MixTheme.colorScheme.surfaceContainer,
        contentColor: Color = MixTheme.colorScheme.onSurfaceContainer,
    ) = CardColors(color, contentColor)
}

@Composable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        top.yukonga.miuix.kmp.basic.Card(
            modifier = modifier, cornerRadius = cornerRadius, insideMargin = insideMargin,
            colors = top.yukonga.miuix.kmp.basic.CardColors(colors.color, colors.contentColor),
            pressFeedbackType = when (pressFeedbackType) {
                PressFeedbackType.None -> top.yukonga.miuix.kmp.utils.PressFeedbackType.None
                PressFeedbackType.Sink -> top.yukonga.miuix.kmp.utils.PressFeedbackType.Sink
                PressFeedbackType.Tilt -> top.yukonga.miuix.kmp.utils.PressFeedbackType.Tilt
            },
            showIndication = showIndication, holdDownState = holdDownState,
            onClick = onClick, onLongPress = onLongPress, content = content,
        )
    } else {
        val shape = ui.feice.mochi.shape.MochiRoundedRectangle(cornerRadius)
        val selectedScale by androidx.compose.animation.core.animateFloatAsState(
            if (holdDownState) 0.96f else 1f, label = "card-selection")
        val interaction = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
        val clickable = if (onClick != null || onLongPress != null) Modifier.combinedClickable(
            interactionSource = interaction,
            indication = if (showIndication) androidx.compose.foundation.LocalIndication.current else null,
            onClick = { onClick?.invoke() }, onLongClick = onLongPress,
        ) else Modifier
        CompositionLocalProvider(
            ui.feice.mochi.theme.LocalContentColor provides colors.contentColor,
            top.yukonga.miuix.kmp.theme.LocalContentColor provides colors.contentColor,
        ) {
            Column(
                modifier.graphicsLayer { scaleX = selectedScale; scaleY = selectedScale }
                    .mixPressFeedback(onClick != null || onLongPress != null,
                        scale = if (pressFeedbackType == PressFeedbackType.Sink) 0.97f else 0.96f,
                        follow = if (pressFeedbackType == PressFeedbackType.Tilt) 3.dp else 0.dp)
                    .clip(shape).background(colors.color).then(clickable).padding(insideMargin),
                content = content,
            )
        }
    }
}
