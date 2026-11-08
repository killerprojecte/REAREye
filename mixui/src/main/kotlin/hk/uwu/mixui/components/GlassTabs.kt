package hk.uwu.mixui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.MochiGlassSurface
import ui.feice.mochi.theme.MochiTheme

/** Only the pinned header receives this context; page and sheet tabs stay ordinary. */
internal val LocalGlassTabsSurfaceAlpha = staticCompositionLocalOf<Float?> { null }

/**
 * Header tabs using each backend's native glass material over the host's body-only
 * recording. Outside [GlassTopBarScaffold]'s pinnedHeader, use ordinary native tabs.
 *
 * MIUIX inherits the native bottomContent backdrop and underlay. Mochi's native
 * segmented control has no glass API: its transparent track sits on the public
 * MochiGlassSurface with the same controlGlass material as toolbar buttons. Its
 * selection thumb, animation, gestures and semantics remain vendor-owned.
 */
@Composable
fun GlassTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return
    val surfaceAlpha = LocalGlassTabsSurfaceAlpha.current
    if (surfaceAlpha == null) {
        Tabs(tabs, selectedTabIndex, onTabSelected, modifier)
        return
    }
    val selected = selectedTabIndex.coerceIn(tabs.indices)
    when (LocalMixUiBackend.current) {
        MixUiBackend.Miuix -> top.yukonga.miuix.kmp.glass.GlassTabRow(
            tabs = tabs,
            selectedIndex = selected,
            onSelect = onTabSelected,
            // The native bar context supplies the body recording and underlay.
            backdrop = null,
            surfaceAlpha = surfaceAlpha,
            modifier = modifier.testTag("glass-tabs-miuix"),
        )
        MixUiBackend.Mochi -> {
            val backdrop = LocalMochiBackdrop.current
            val material = MochiTheme.materials.controlGlass
            if (backdrop == null || material == null) {
                Tabs(tabs, selected, onTabSelected, modifier)
            } else {
                MochiGlassSurface(
                    modifier = modifier.testTag("glass-tabs-mochi"),
                    backdrop = backdrop,
                    material = material,
                ) {
                    ui.feice.mochi.basic.MochiSegmentedControl(
                        items = tabs.toImmutableList(),
                        selectedIndex = selected,
                        onSelect = onTabSelected,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ui.feice.mochi.basic.MochiSegmentedDefaults.colors(
                            track = Color.Transparent,
                        ),
                    )
                }
            }
        }
    }
}
