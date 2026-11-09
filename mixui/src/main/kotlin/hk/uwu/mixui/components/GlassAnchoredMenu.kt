package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.icons.Icons
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import top.yukonga.miuix.kmp.glass.GlassPopupSizing
import ui.feice.mochi.effect.glass.LocalMochiBackdrop

/** A null [selected] denotes an action rather than a selectable option. */
data class GlassMenuOption(
    val title: String,
    val summary: String? = null,
    val selected: Boolean? = null,
    val enabled: Boolean = true,
    val dismissOnClick: Boolean = true,
    val dividerBefore: Boolean = false,
    val icon: (@Composable (Modifier) -> Unit)? = null,
)

/**
 * A native glass menu for an existing toolbar button or long-press target.
 * The caller keeps its anchor gestures; this wrapper only measures their bounds.
 * Requires GlassPreferenceScene or one of mixui's glass/large-title page hosts.
 * Popup and outside-touch layers are never part of the recorded page backdrop.
 * [menuContent] preserves specialized row layouts; its caller owns row selection
 * and closes [expanded] before invoking an action, just like the default rows.
 */
@Composable
fun GlassAnchoredMenu(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    options: List<GlassMenuOption>,
    onOptionClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    width: Dp = 280.dp,
    maxHeight: Dp? = null,
    menuContent: (@Composable ColumnScope.() -> Unit)? = null,
    anchor: @Composable () -> Unit,
) {
    val backdrop = checkNotNull(LocalGlassPreferenceBackdrop.current) {
        "GlassAnchoredMenu requires GlassPreferenceScene or a mixui glass/large-title scaffold"
    }
    val active = enabled && (menuContent != null || options.isNotEmpty())
    LaunchedEffect(active) { if (!active && expanded) onExpandedChange(false) }
    val rows: @Composable ColumnScope.() -> Unit = menuContent ?: {
        options.forEachIndexed { index, option ->
            if (option.dividerBefore) Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Spacer(Modifier.fillMaxWidth().height(0.5.dp)
                    .background(MixTheme.colorScheme.outline.copy(alpha = 0.35f)))
            }
            GlassMenuRow(option, enabled = active && expanded && option.enabled) {
                // Ignore input during closing and disabled options, including accessibility.
                if (active && expanded && option.enabled) {
                    if (option.dismissOnClick) onExpandedChange(false)
                    onOptionClick(index)
                }
            }
        }
    }
    when (backdrop) {
        is GlassFormBackdrop.Miuix -> {
            var bounds by remember { mutableStateOf(Rect.Zero) }
            Box(modifier.onGloballyPositioned { bounds = it.boundsInRoot() }) { anchor() }
            GlassPreferenceOverlay { origin ->
                GlassDropdownPopup(
                    show = expanded && active,
                    onDismissRequest = { onExpandedChange(false) },
                    anchorBounds = bounds.translate(-origin),
                    backdrop = backdrop.value,
                    sizing = GlassPopupSizing(minWidth = minOf(220.dp, width), maxWidth = width,
                        maxHeight = maxHeight ?: GlassPopupSizing().maxHeight),
                    content = rows,
                )
            }
        }
        is GlassFormBackdrop.Mochi -> CompositionLocalProvider(LocalMochiBackdrop provides backdrop.value) {
            // Maven Mochi 0.2.0 hosts this in its own DecorView sibling and preserves
            // window/local context. It samples the page, never its own popup/scrim.
            ui.feice.mochi.basic.MochiPopoverBox(
                expanded = expanded && active,
                onExpandedChange = { onExpandedChange(it && active) },
                popover = {
                    if (maxHeight != null) {
                        Column(Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()), content = rows)
                    } else rows()
                },
                modifier = modifier,
                width = width,
                align = ui.feice.mochi.basic.MochiAlign.End,
                contentPadding = PaddingValues(vertical = 8.dp),
                anchor = anchor,
            )
        }
    }
}

/** Register outside recording while retaining the caller's density, direction and window locals. */
@Composable
internal fun GlassPreferenceOverlay(content: @Composable BoxScope.(Offset) -> Unit) {
    val overlays = checkNotNull(LocalPreferenceOverlays.current)
    val token = remember { Any() }
    val locals = currentCompositionLocalContext
    val current by rememberUpdatedState<@Composable BoxScope.(Offset) -> Unit>({ origin ->
        CompositionLocalProvider(locals) { content(origin) }
    })
    DisposableEffect(overlays, token) {
        overlays.entries[token] = { origin -> current(origin) }
        onDispose { overlays.entries.remove(token) }
    }
}

@Composable
private fun GlassMenuRow(option: GlassMenuOption, enabled: Boolean, onClick: () -> Unit) {
    val colors = MixTheme.colorScheme
    val interaction = option.selected?.let {
        Modifier.selectable(selected = it, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    } ?: Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().then(interaction).padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        option.icon?.invoke(Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(option.title, style = MixTheme.textStyles.body1,
                color = colors.onSurface.copy(alpha = if (enabled) 1f else 0.4f))
            option.summary?.let {
                Text(it, style = MixTheme.textStyles.body2,
                    color = colors.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else 0.4f))
            }
        }
        if (option.selected == true) Icon(Icons.Basic.Check, contentDescription = null,
            tint = colors.primary.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp))
    }
}
