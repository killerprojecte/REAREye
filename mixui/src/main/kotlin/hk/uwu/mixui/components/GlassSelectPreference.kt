package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.basic.MochiAlign
import ui.feice.mochi.basic.MochiDropdown
import ui.feice.mochi.basic.MochiDropdownDefaults
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.MochiTrigger
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.theme.MochiTheme
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled

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
        is GlassFormBackdrop.Mochi -> CompositionLocalProvider(LocalMochiBackdrop provides backdrop.value) {
            var expanded by remember { mutableStateOf(false) }
            LaunchedEffect(actualEnabled) { if (!actualEnabled) expanded = false }
            val nativeItems = remember(items) { items.map { MochiItem(label = it) }.toImmutableList() }
            val (anchorModifier, changeExpanded) = rememberMochiMenuActivation(
                Modifier.widthIn(min = 34.dp, max = 120.dp), actualEnabled, expanded,
            ) { expanded = it && actualEnabled }
            MochiPreferenceContent(summary = summary, modifier = rowModifier, enabled = actualEnabled,
                onClick = { expanded = true }) {
                MochiListRow(
                    title = title,
                    enabled = actualEnabled,
                    onClick = { if (actualEnabled) expanded = true },
                    trailing = {
                        MochiDropdown(
                            expanded = expanded && actualEnabled,
                            onExpandedChange = changeExpanded,
                            items = nativeItems,
                            selectedIndex = selectedIndex,
                            enabled = actualEnabled,
                            onItemClick = { index ->
                                if (actualEnabled && index in items.indices) {
                                    expanded = false
                                    onSelectedIndexChange(index)
                                }
                            },
                            modifier = anchorModifier,
                            behavior = MochiDropdownDefaults.behavior(
                                trigger = MochiTrigger.LongPress,
                                align = MochiAlign.End,
                            ),
                            buttonMinHeight = 34.dp,
                            buttonPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            ui.feice.mochi.basic.Text(
                                text = items.getOrElse(selectedIndex) { "" },
                                style = MochiTheme.textStyles.body,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
            }
        }
    }
}
