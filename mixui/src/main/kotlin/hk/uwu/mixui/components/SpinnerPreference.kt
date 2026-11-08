package hk.uwu.mixui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.basic.MochiAlign
import ui.feice.mochi.basic.MochiDropdown
import ui.feice.mochi.basic.MochiDropdownDefaults
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.list.MochiListRow

/**
 * Single-selection text preference. This intentionally accepts strings, not rich
 * DropdownItem objects: Mochi 0.2 native menus cannot represent disabled options,
 * summaries, submenus or independent per-option selected flags.
 */
@Composable
fun SpinnerPreference(
    options: List<String>,
    selectedIndex: Int,
    title: String,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        top.yukonga.miuix.kmp.preference.WindowSpinnerPreference(
            items = options.map { top.yukonga.miuix.kmp.basic.DropdownItem(title = it) },
            selectedIndex = selectedIndex,
            title = title,
            summary = summary,
            enabled = enabled,
            modifier = modifier,
            onSelectedIndexChange = onSelectedIndexChange,
        )
    } else {
        var expanded by rememberSaveable { mutableStateOf(false) }
        val items = remember(options) { options.map { MochiItem(label = it) }.toImmutableList() }
        val actualEnabled = enabled && options.isNotEmpty()
        // Keep the dropdown mounted when closed: the native component owns its
        // animated close lifecycle, overlay removal, back handling and drag-select.
        MochiListRow(
            title = title,
            subtitle = summary,
            modifier = modifier,
            enabled = actualEnabled,
            trailing = {
                MochiDropdown(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    items = items,
                    onItemClick = { index ->
                        if (actualEnabled && index in options.indices) {
                            onSelectedIndexChange(index)
                        }
                    },
                    enabled = actualEnabled,
                    selectedIndex = selectedIndex,
                    behavior = MochiDropdownDefaults.behavior(align = MochiAlign.End),
                    buttonMinHeight = 34.dp,
                    buttonPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    Text(options.getOrElse(selectedIndex) { "" })
                }
            },
        )
    }
}
