package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/**
 * Keeps both sides of the native progressive renderer subscribed to actual page
 * motion. Updating the bar's alpha alone stops invalidating once its ramp reaches
 * one; retained Android layers may then reuse the first sampled display list.
 * Read motion in draw, not composition, and retain the vendor's layers/effects.
 */
internal class MiuixScrollBackdrop(
    val source: LayerBackdrop,
    observeScroll: () -> Unit,
) : Backdrop by source {
    private val observeScroll = observeScroll

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        downscaleFactor: Int,
    ) {
        observeScroll()
        with(source) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
    }
}

@Composable
internal fun rememberMiuixScrollBackdrop(observeScroll: () -> Unit): MiuixScrollBackdrop {
    val currentScroll by rememberUpdatedState(observeScroll)
    val source = rememberLayerBackdrop {
        currentScroll()
        drawContent()
    }
    return remember(source) { MiuixScrollBackdrop(source) { currentScroll() } }
}
