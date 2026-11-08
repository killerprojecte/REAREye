package hk.uwu.mixui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.effect.glass.LocalMochiBackdrop

/** The page scene excludes popup hosts. Supplied separately from form-only scope. */
internal val LocalGlassPreferenceBackdrop = staticCompositionLocalOf<GlassFormBackdrop?> { null }

/**
 * Native single-selection preference. No persistence is owned by this control.
 * Both backends request dismissal before delivering selection to the caller.
 * Requires GlassTopBarScaffold or GlassPreferenceScene; the host supplies real page pixels.
 */
@Composable
fun GlassSelectPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    val backdrop = checkNotNull(LocalGlassPreferenceBackdrop.current) {
        "GlassSelectPreference requires GlassTopBarScaffold or GlassPreferenceScene"
    }
    val actualEnabled = enabled && items.isNotEmpty()
    val rowModifier = modifier.semantics(mergeDescendants = true) {
        if (!actualEnabled) disabled()
    }
    when (backdrop) {
        is GlassFormBackdrop.Miuix -> top.yukonga.miuix.kmp.glass.GlassDropdownPreference(
            title = title, items = items, selectedIndex = selectedIndex,
            onSelectedIndexChange = onSelectedIndexChange,
            modifier = rowModifier, summary = summary, enabled = actualEnabled,
            showValue = false, backdrop = backdrop.value, renderInRootScaffold = true,
        )
        is GlassFormBackdrop.Mochi -> {
            var expanded by remember { mutableStateOf(false) }
            LaunchedEffect(actualEnabled) { if (!actualEnabled) expanded = false }
            val entries = remember(items) {
                items.map { ui.feice.mochi.basic.MochiItem(label = it) }.toImmutableList()
            }
            CompositionLocalProvider(LocalMochiBackdrop provides backdrop.value) {
                ui.feice.mochi.basic.MochiDropdown(
                    expanded = expanded && actualEnabled,
                    onExpandedChange = { expanded = it && actualEnabled },
                    items = entries, selectedIndex = selectedIndex,
                    onItemClick = { index ->
                        expanded = false
                        if (actualEnabled && index in items.indices) onSelectedIndexChange(index)
                    },
                    // The app already dismisses before persistence. A delayed native close
                    // must not close a menu the user has just reopened.
                    behavior = ui.feice.mochi.basic.MochiDropdownDefaults.behavior(dismissDelayMillis = 0),
                    modifier = rowModifier.fillMaxWidth(), enabled = actualEnabled,
                    buttonPadding = PaddingValues(0.dp),
                ) {
                    // Only the native dropdown owns gestures and accessibility actions.
                    PreferenceRow(title = title, summary = summary,
                        enabled = actualEnabled)
                }
            }
        }
    }
}
