package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import kotlinx.collections.immutable.toImmutableList
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRowDefaults
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import kotlin.math.abs
import ui.feice.mochi.effect.glass.LocalMochiBackdrop
import ui.feice.mochi.effect.glass.MochiGlassSurface
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBackdrop
import ui.feice.mochi.theme.MochiTheme

/** Body-only backdrop supplied by glass headers and native MIUIX sheets. */
internal val LocalGlassTabsBackdrop = staticCompositionLocalOf<Backdrop?> { null }

// Native overlays clear LocalMochiBackdrop; explicitly hosted joined tracks
// may sample this external page source without changing other overlay controls.
internal val LocalGlassTabsMochiBackdrop = staticCompositionLocalOf<ui.feice.mochi.effect.glass.MochiBackdrop?> { null }

internal val LocalGlassTabsSurfaceAlpha = staticCompositionLocalOf<Float?> { null }

/** Only native MIUIX sheet bodies promote ordinary Tabs to a joined glass track. */
internal val LocalMiuixSheetTabs = staticCompositionLocalOf { false }

/**
 * Header tabs using each backend's native glass material over the host's body-only
 * recording. Without an explicit glass host context, use ordinary native tabs.
 *
 * MIUIX delegates rendering and drag selection to its native GlassSegmentedTabRow.
 * Mochi's native
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
                MiuixGlassTabs(
                    tabs = tabs,
                    selectedIndex = selected,
                    onSelect = { if (it != selected) onTabSelected(it) },
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
/** Joined glass tabs on a scrolling form surface, without inheriting toolbar material. */
@Composable
fun InlineGlassTabs(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    surfaceColor: Color = MixTheme.colorScheme.surfaceContainer,
) {
    if (tabs.isEmpty()) return
    val backend = LocalMixUiBackend.current
    val miuix = if (backend == MixUiBackend.Miuix && isRuntimeShaderSupported()) rememberLayerBackdrop() else null
    val mochi = if (backend == MixUiBackend.Mochi) rememberMochiBackdrop(surfaceColor) else null
    val record = when {
        miuix != null -> Modifier.layerBackdrop(miuix)
        mochi != null -> Modifier.mochiBackdropSource(mochi)
        else -> Modifier
    }
    Box(modifier, propagateMinConstraints = true) {
        // Record just the underlying surface. The track and labels are siblings, so
        // a selection or scroll frame cannot be sampled back into its own glass.
        Box(Modifier.matchParentSize().then(record).background(surfaceColor))
        CompositionLocalProvider(
            LocalGlassTabsBackdrop provides miuix,
            LocalGlassTabsMochiBackdrop provides mochi,
            LocalGlassTabsSurfaceAlpha provides 1f,
        ) {
            GlassTabs(tabs, selectedTabIndex, onTabSelected)
        }
    }
}

// Native handles the indicator and selection. The host only vetoes pointer releases
// belonging to its vertical scroller or stationary taps in the gaps between labels.
@Composable
internal fun MiuixGlassTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: Backdrop,
    surfaceAlpha: Float,
    modifier: Modifier = Modifier,
    height: Dp = GlassSegmentedTabRowDefaults.Height,
) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val release = remember { GlassTabPointerRelease() }
    GlassSegmentedTabRow(
        tabs = tabs,
        selectedIndex = selectedIndex,
        onSelect = { if (!release.veto) onSelect(it) },
        backdrop = backdrop,
        surfaceAlpha = surfaceAlpha,
        height = height,
        modifier = modifier.pointerInput(tabs.size, rtl) {
            val padding = GlassSegmentedTabRowDefaults.TrackPadding.toPx()
            val gap = GlassSegmentedTabRowDefaults.TabGap.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var vertical: Boolean? = null
                while (true) {
                    val change = awaitPointerEvent(PointerEventPass.Initial).changes
                        .firstOrNull { it.id == down.id } ?: break
                    val dx = abs(change.position.x - down.position.x)
                    val dy = abs(change.position.y - down.position.y)
                    if (vertical == null && maxOf(dx, dy) >= viewConfiguration.touchSlop) {
                        vertical = dy > dx
                    }
                    if (!change.pressed) {
                        val logicalX = if (rtl) size.width - change.position.x else change.position.x
                        val innerX = logicalX - padding
                        val innerWidth = size.width - 2 * padding
                        val cell = (innerWidth - gap * (tabs.size - 1)) / tabs.size
                        val tappedGap = vertical == null && cell > 0f && innerX >= 0f &&
                            innerX < innerWidth && innerX % (cell + gap) >= cell
                        release.veto = vertical == true || tappedGap
                        try {
                            // The vendor calls onSelect during its Main pass. Do not consume the
                            // event, or let the veto leak into the next keyboard/accessibility action.
                            awaitPointerEvent(PointerEventPass.Final)
                        } finally {
                            release.veto = false
                        }
                        break
                    }
                }
            }
        },
    )
}

private class GlassTabPointerRelease(var veto: Boolean = false)
