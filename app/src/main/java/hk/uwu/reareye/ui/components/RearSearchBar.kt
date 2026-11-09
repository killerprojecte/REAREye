package hk.uwu.reareye.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hk.uwu.mixui.components.SearchBar
import hk.uwu.mixui.components.SearchBarStyle
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ModuleSearchBarStyle
import hk.uwu.reareye.ui.config.PrefsManager

/** App preference adapter; the reusable control and backend selection live in mixui. */
@Composable
fun RearSearchBar(
    query: String,
    hint: String,
    prefsManager: PrefsManager,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onSearchSubmit: () -> Unit = {},
    onSearchFocusChange: (Boolean) -> Unit = {},
) {
    val preference = ModuleSearchBarStyle.fromValue(
        prefsManager.getInt(ConfigKeys.MODULE_SEARCH_BAR_STYLE, ModuleSearchBarStyle.default.value)
    )
    SearchBar(
        query = query,
        hint = hint,
        modifier = modifier,
        style = when (preference) {
            ModuleSearchBarStyle.DEFAULT -> SearchBarStyle.Default
            ModuleSearchBarStyle.MIUIX -> SearchBarStyle.Native
        },
        onQueryChange = onQueryChange,
        onSearchSubmit = onSearchSubmit,
        onSearchFocusChange = onSearchFocusChange,
    )
}
