package hk.uwu.mixui.components

import androidx.compose.foundation.layout.fillMaxWidth
import top.yukonga.miuix.kmp.blur.Backdrop
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

/** Body-only backdrop supplied by glass headers and native MIUIX sheets. */
internal val LocalGlassTabsBackdrop = staticCompositionLocalOf<Backdrop?> { null }

// Native overlays clear LocalMochiBackdrop; explicitly hosted joined tracks
// may sample this external page source without changing other overlay controls.
internal val LocalGlassTabsMochiBackdrop = staticCompositionLocalOf<ui.feice.mochi.effect.glass.MochiBackdrop?> { null }

internal val LocalGlassTabsSurfaceAlpha = staticCompositionLocalOf<Float?> { null }

/**
 * Header tabs using each backend's native glass material over the host's body-only
 * recording. Without an explicit glass host context, use ordinary native tabs.
 *
 * MIUIX uses the native GlassSegmentedTabRow joined track and its moving indicator. Mochi's native
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
        MixUiBackend.Miuix -> {
            val backdrop = LocalGlassTabsBackdrop.current
            if (backdrop == null) {
                Tabs(tabs, selected, onTabSelected, modifier)
            } else {
                // Same joined-track component as the upstream Privacy / Security
                // example: one glass outline with a travelling native indicator.
                top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow(
                    tabs = tabs,
                    selectedIndex = selected,
                    onSelect = onTabSelected,
                    backdrop = backdrop,
                    surfaceAlpha = surfaceAlpha,
                    modifier = modifier.testTag("glass-tabs-miuix"),
                )
            }
        }
        MixUiBackend.Mochi -> {
            val backdrop = LocalGlassTabsMochiBackdrop.current ?: LocalMochiBackdrop.current
            val material = MochiTheme.materials.controlGlass
            if (backdrop == null || material == null) {
                Tabs(tabs, selected, onTabSelected, modifier)
            } else {
                MochiGlassSurface(
                    modifier = modifier.testTag("glass-tabs-mochi"),
                    backdrop = backdrop,
                    // The floating-button recipe casts an 80dp outer shadow.
                    // A full-width joined track must not darken an entire form;
                    // keep native glass optics and the segmented thumb shadow.
                    material = material.copy(
                        light = material.light.copy(shadowOpacity = 0f),
                        dark = material.dark.copy(shadowOpacity = 0f),
                    ),
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
