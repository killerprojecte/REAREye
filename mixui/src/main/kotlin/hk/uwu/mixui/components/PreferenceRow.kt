package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.icons.Icons
import hk.uwu.mixui.theme.MixTheme
import ui.feice.mochi.list.MochiListDefaults

/**
 * Configuration action row with complete, wrapping descriptions and a held state.
 * Mochi 0.2's standard list row truncates summaries to two lines and has no held
 * state. This neutral layout uses its theme and list colors instead of losing
 * configuration text through that narrower native contract.
 */
@Composable
fun PreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startAction: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    holdDownState: Boolean = false,
) {
    val rowModifier = modifier.semantics(mergeDescendants = onClick != null) {
        if (!enabled) disabled()
    }
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        top.yukonga.miuix.kmp.preference.ArrowPreference(
            title = title, modifier = rowModifier, summary = summary,
            startAction = startAction, onClick = onClick,
            enabled = enabled, holdDownState = holdDownState,
        )
    } else {
        val interactionSource = remember { MutableInteractionSource() }
        val pressed by interactionSource.collectIsPressedAsState()
        val listColors = MochiListDefaults.colors()
        val colors = MixTheme.colorScheme
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        Row(
            modifier = rowModifier.fillMaxWidth().heightIn(min = 48.dp)
                .background(if (enabled && (holdDownState || pressed)) listColors.highlight else Color.Transparent)
                .then(if (onClick != null) Modifier.clickable(
                    interactionSource = interactionSource, indication = null,
                    enabled = enabled, role = Role.Button, onClick = onClick,
                ) else Modifier)
                .padding(horizontal = 16.dp, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            startAction?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MixTheme.textStyles.body1,
                    color = if (enabled) colors.onSurface else colors.disabledOnSurface)
                if (summary != null) Text(summary, style = MixTheme.textStyles.body2,
                    color = if (enabled) colors.onSurfaceVariantSummary else colors.disabledOnSurface)
            }
            Icon(
                imageVector = Icons.Basic.ArrowRight, contentDescription = null,
                tint = if (enabled) listColors.chevron else colors.disabledOnSurface,
                modifier = Modifier.size(width = 10.dp, height = 16.dp).graphicsLayer {
                    scaleX = if (rtl) -1f else 1f
                },
            )
        }
    }
}
