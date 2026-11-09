package hk.uwu.mixui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.motion.mixPressFeedback
import hk.uwu.mixui.theme.MixTheme
import ui.feice.mochi.list.MochiListDefaults

/** Text colors owned by mixui, with no renderer types in the public contract. */
@Immutable
data class ListItemColors(val color: Color, val disabledColor: Color) {
    fun color(enabled: Boolean): Color = if (enabled) color else disabledColor
}

object ListItemDefaults {
    val InsideMargin = PaddingValues(16.dp)

    @Composable
    fun titleColor(
        color: Color = MixTheme.colorScheme.onBackground,
        disabledColor: Color = MixTheme.colorScheme.disabledOnSecondaryVariant,
    ) = ListItemColors(color, disabledColor)

    @Composable
    fun summaryColor(
        color: Color = MixTheme.colorScheme.onSurfaceVariantSummary,
        disabledColor: Color = MixTheme.colorScheme.disabledOnSecondaryVariant,
    ) = ListItemColors(color, disabledColor)
}

/**
 * Complete configuration row, including independent leading, trailing and bottom actions.
 * MIUIX uses its native BasicComponent. Mochi 0.2's native MochiListRow limits title
 * and subtitle lines and has no bottom/held contract, so its list colors and theme
 * are applied to a neutral, unlimited-text layout instead. Disabling this row only
 * disables its own click; children retain control of their own enabled state.
 */
@Composable
fun ListItem(
    title: String? = null,
    modifier: Modifier = Modifier,
    titleColor: ListItemColors = ListItemDefaults.titleColor(),
    summary: String? = null,
    summaryColor: ListItemColors = ListItemDefaults.summaryColor(),
    startAction: (@Composable () -> Unit)? = null,
    endActions: (@Composable RowScope.() -> Unit)? = null,
    bottomAction: (@Composable () -> Unit)? = null,
    insideMargin: PaddingValues = ListItemDefaults.InsideMargin,
    onClick: (() -> Unit)? = null,
    holdDownState: Boolean = false,
    enabled: Boolean = true,
    animateContentSize: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val rowModifier = modifier
        .then(if (animateContentSize) Modifier.animateContentSize(
            animationSpec = spring(dampingRatio = 0.92f, stiffness = 650f),
        ) else Modifier)
        .semantics { if (!enabled) disabled() }
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        if (content != null) top.yukonga.miuix.kmp.basic.BasicComponent(
            modifier = rowModifier,
            startAction = startAction, endActions = endActions, bottomAction = bottomAction,
            insideMargin = insideMargin, onClick = onClick,
            holdDownState = holdDownState, enabled = enabled, content = content,
        ) else top.yukonga.miuix.kmp.basic.BasicComponent(
            modifier = rowModifier, title = title,
            titleColor = top.yukonga.miuix.kmp.basic.BasicComponentColors(titleColor.color, titleColor.disabledColor),
            summary = summary,
            summaryColor = top.yukonga.miuix.kmp.basic.BasicComponentColors(summaryColor.color, summaryColor.disabledColor),
            startAction = startAction, endActions = endActions, bottomAction = bottomAction,
            insideMargin = insideMargin, onClick = onClick,
            holdDownState = holdDownState, enabled = enabled,
        )
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val listColors = MochiListDefaults.colors()
        Column(
            modifier = rowModifier.fillMaxWidth().heightIn(min = 56.dp)
                .mixPressFeedback(enabled = enabled && onClick != null)
                .background(if (enabled && (pressed || holdDownState)) listColors.highlight else Color.Transparent)
                .then(if (onClick != null) Modifier.clickable(
                    interactionSource = interactionSource, indication = null,
                    enabled = enabled, onClick = onClick,
                ) else Modifier)
                .padding(insideMargin),
            verticalArrangement = Arrangement.Center,
        ) {
            BoxWithConstraints {
                val maxActionsWidth = maxWidth * 0.6f
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    startAction?.invoke()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        if (content != null) content() else {
                        if (title != null) Text(title, style = MixTheme.textStyles.headline1,
                            fontWeight = FontWeight.Medium, color = titleColor.color(enabled))
                        if (summary != null) Text(summary, style = MixTheme.textStyles.body2,
                            color = summaryColor.color(enabled))
                        }
                    }
                    if (endActions != null) Row(
                        modifier = Modifier.widthIn(max = maxActionsWidth), content = endActions,
                    )
                }
            }
            if (bottomAction != null) {
                Spacer(Modifier.height(8.dp))
                bottomAction()
            }
        }
    }
}
