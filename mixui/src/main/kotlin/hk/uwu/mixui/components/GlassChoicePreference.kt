package hk.uwu.mixui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.icons.Icons
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.glass.GlassDropdownPopup
import ui.feice.mochi.effect.glass.LocalMochiBackdrop

/** Option payload with lossless text and caller-owned selection, including multiple ticks. */
data class ChoiceOption(val title: String, val summary: String? = null, val selected: Boolean = false)

private class PreferenceOverlays {
    val entries = mutableStateMapOf<Any, @Composable BoxScope.(Offset) -> Unit>()
}
private val LocalPreferenceOverlays = staticCompositionLocalOf<PreferenceOverlays?> { null }

/** Page-only recording belongs in [page]; these popup siblings are never recorded. */
@Composable
internal fun GlassPreferenceOverlayHost(backdrop: GlassFormBackdrop, page: @Composable () -> Unit) {
    val overlays = remember { PreferenceOverlays() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        CompositionLocalProvider(
            LocalGlassPreferenceBackdrop provides backdrop,
            LocalPreferenceOverlays provides overlays,
        ) { page() }
        overlays.entries.forEach { (id, entry) -> key(id) { entry(origin) } }
    }
}

/**
 * Rich/multiple choice menu. Uses native glass popup surfaces, never a flattened
 * single-selection dropdown. Neither native menu-item contract can display full
 * option descriptions, so the content is a lossless neutral selectable row.
 */
@Composable
fun GlassChoicePreference(
    title: String,
    options: List<ChoiceOption>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOptionClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    multiple: Boolean = false,
) {
    val backdrop = checkNotNull(LocalGlassPreferenceBackdrop.current) {
        "GlassChoicePreference requires GlassTopBarScaffold or GlassPreferenceScene"
    }
    val active = enabled && options.isNotEmpty()
    LaunchedEffect(active) { if (!active && expanded) onExpandedChange(false) }
    val select: (Int) -> Unit = { index ->
        if (active && index in options.indices) {
            onExpandedChange(false)
            onOptionClick(index)
        }
    }
    val rows: @Composable ColumnScope.() -> Unit = {
        options.forEachIndexed { index, option ->
            ChoiceOptionRow(option, multiple, onClick = { select(index) })
        }
    }
    when (backdrop) {
        is GlassFormBackdrop.Miuix -> {
            var bounds by remember { mutableStateOf(Rect.Zero) }
            PreferenceRow(title = title, summary = summary, enabled = active,
                modifier = modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
                holdDownState = expanded, onClick = { onExpandedChange(true) })
            val overlays = checkNotNull(LocalPreferenceOverlays.current)
            val token = remember { Any() }
            // The registry retains the native popup while it completes its exit animation.
            // Latest callback/state is read without replacing the entry during composition.
            val entry by rememberUpdatedState<@Composable BoxScope.(Offset) -> Unit>({ origin ->
                GlassDropdownPopup(
                    show = expanded && active,
                    onDismissRequest = { onExpandedChange(false) },
                    anchorBounds = bounds.translate(-origin),
                    backdrop = backdrop.value,
                    content = rows,
                )
            })
            DisposableEffect(overlays, token) {
                overlays.entries[token] = { origin -> entry(origin) }
                onDispose { overlays.entries.remove(token) }
            }
        }
        is GlassFormBackdrop.Mochi -> CompositionLocalProvider(LocalMochiBackdrop provides backdrop.value) {
            ui.feice.mochi.basic.MochiPopoverButton(
                expanded = expanded && active,
                onExpandedChange = { onExpandedChange(it && active) },
                popover = rows, modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    if (!active) disabled()
                }, enabled = active,
                buttonPadding = PaddingValues(0.dp), contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                PreferenceRow(title = title, summary = summary, enabled = active)
            }
        }
    }
}

@Composable
private fun ChoiceOptionRow(option: ChoiceOption, multiple: Boolean, onClick: () -> Unit) {
    val colors = MixTheme.colorScheme
    Row(
        Modifier.fillMaxWidth()
            .then(if (multiple) Modifier.toggleable(value = option.selected,
                role = Role.Checkbox, onValueChange = { onClick() })
                else Modifier.selectable(selected = option.selected, role = Role.RadioButton, onClick = onClick))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(option.title, style = MixTheme.textStyles.body1, color = colors.onSurface)
            option.summary?.let { Text(it, style = MixTheme.textStyles.body2, color = colors.onSurfaceVariantSummary) }
        }
        Icon(Icons.Basic.Check, contentDescription = null,
            tint = if (option.selected) colors.primary else Color.Transparent, modifier = Modifier.size(20.dp))
    }
}
