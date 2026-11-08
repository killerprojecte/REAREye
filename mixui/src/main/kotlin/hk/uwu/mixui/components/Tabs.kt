package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import kotlinx.collections.immutable.toImmutableList

/** Page tabs retain each backend's native shape, indicator and selection motion. */
@Composable
fun Tabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val selected = selectedTabIndex.coerceIn(tabs.indices)
    when (LocalMixUiBackend.current) {
        MixUiBackend.Mochi -> ui.feice.mochi.basic.MochiSegmentedControl(
            items = tabs.toImmutableList(),
            selectedIndex = selected,
            onSelect = onTabSelected,
            modifier = modifier.testTag("tabs-mochi"),
        )
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.basic.TabRowWithContour(
            tabs = tabs,
            selectedTabIndex = selected,
            onTabSelected = onTabSelected,
            modifier = modifier.testTag("tabs-miuix"),
        )
    }
}
