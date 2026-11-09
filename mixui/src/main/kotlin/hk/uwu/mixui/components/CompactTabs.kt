package hk.uwu.mixui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import kotlinx.collections.immutable.toImmutableList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import hk.uwu.mixui.theme.MixTheme

/** Shared compact layout with each renderer's selection, semantics and animated indicator.
 * [fillWidth] gives a dedicated, equal-width mode row; otherwise the group wraps its labels.
 * The visible track is compact; the surrounding row reserves room for minimum touch expansion.
 * [glass] opts MIUIX menus into the native joined glass track; Mochi is unchanged.
 * Labels are measured at the actual font scale instead of squeezed into a fixed maximum width.
 * Intrinsic-sizing parents (e.g. DropdownMenu) must supply their known width via [modifier].
 */
@Composable
fun CompactTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillWidth: Boolean = false,
    glass: Boolean = false,
) {
    if (tabs.isEmpty()) return
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MixTheme.textStyles.body2.copy(fontWeight = FontWeight.Bold)
    val nativeGlass = glass && LocalMixUiBackend.current == MixUiBackend.Miuix
    val styles = if (nativeGlass) listOf(
        top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.subtitle,
        top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.body2,
    ) else listOf(style)
    val sizes = tabs.flatMap { label -> styles.map { measurer.measure(label, style = it, maxLines = 1).size } }
    val tabPadding = if (nativeGlass) 32.dp else 16.dp
    val tabWidth = with(density) { sizes.maxOf { it.width }.toDp() }.plus(tabPadding).coerceAtLeast(56.dp)
    val trackHeight = with(density) { sizes.maxOf { it.height }.toDp() }.plus(14.dp)
        .coerceAtLeast(if (nativeGlass) 40.dp else 36.dp)
    val spacing = if (nativeGlass) 8.dp else 2.dp
    val width = tabWidth * tabs.size + spacing * (tabs.size - 1) + if (nativeGlass) 6.dp else 10.dp
    val colors = MixTheme.colorScheme
    Box(modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(width)).heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        // Miuix0.9.4 expands equal cells to available width when their content minima fit;
        // its own LazyRow scrolls when labels need more room. Avoid a second subcompose layout
        // here: DropdownMenu queries intrinsic size before measuring its popup content.
        if (LocalMixUiBackend.current == MixUiBackend.Mochi) {
            ui.feice.mochi.basic.MochiSegmentedControl(
                selectedIndex = selectedTabIndex.coerceIn(tabs.indices),
                onSelect = onTabSelected,
                items = tabs.toImmutableList(),
                modifier = Modifier.fillMaxWidth().testTag("compact-tabs-track"),
                colors = ui.feice.mochi.basic.MochiSegmentedDefaults.colors(
                    track = colors.onSurface.copy(alpha = 0.055f), thumb = colors.surface,
                    content = colors.onSurfaceVariantSummary, selectedContent = colors.onSurface,
                ),
                behavior = ui.feice.mochi.basic.MochiSegmentedDefaults.behavior(height = trackHeight),
            )
        } else {
            // Explicit opt-in: ordinary dialog controls retain their existing surfaces.
            // A menu samples its external page recording, never its own track or popup.
            val backdrop = if (glass) (LocalGlassPreferenceBackdrop.current as? GlassFormBackdrop.Miuix)?.value else null
            if (backdrop != null) top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow(
                tabs = tabs, selectedIndex = selectedTabIndex.coerceIn(tabs.indices),
                onSelect = onTabSelected, backdrop = backdrop, height = trackHeight,
                modifier = Modifier.fillMaxWidth().testTag("compact-glass-tabs-miuix"),
            ) else TabRowWithContour(
                tabs = tabs,
                selectedTabIndex = selectedTabIndex.coerceIn(tabs.indices),
                onTabSelected = onTabSelected,
                modifier = Modifier.fillMaxWidth().testTag("compact-tabs-track"),
                minWidth = tabWidth,
                maxWidth = tabWidth,
                height = trackHeight,
                cornerRadius = 8.dp,
                itemSpacing = spacing,
                colors = TabRowDefaults.tabRowColors(
                    backgroundColor = colors.onSurface.copy(alpha = 0.055f),
                    contentColor = colors.onSurfaceVariantSummary,
                    selectedBackgroundColor = colors.surface,
                    selectedContentColor = colors.onSurface,
                ),
                indication = null,
            )
        }
    }
}
