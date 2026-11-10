package hk.uwu.mixui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density
import top.yukonga.miuix.kmp.blur.Backdrop
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop

/** Test-only probe: fixed geometry/alpha, separately retained source and consumer. */
@Composable
fun MiuixScrollBackdropProbe(
    motion: State<Float>,
    live: Boolean,
    onSourceDraw: () -> Unit,
    onConsumerDraw: () -> Unit,
) {
    val liveSource = if (live) rememberMiuixScrollBackdrop { motion.value } else null
    val source = liveSource?.source ?: rememberLayerBackdrop()
    val delegate = liveSource ?: source
    val consumer = remember(delegate) {
        object : Backdrop by delegate {
            override fun DrawScope.drawBackdrop(
                density: Density,
                coordinates: LayoutCoordinates?,
                layerBlock: (GraphicsLayerScope.() -> Unit)?,
                downscaleFactor: Int,
            ) {
                onConsumerDraw()
                with(delegate) { drawBackdrop(density, coordinates, layerBlock, downscaleFactor) }
            }
        }
    }
    Box(Modifier.size(200.dp)) {
        Box(Modifier.size(200.dp).graphicsLayer()
            .drawWithContent { onSourceDraw(); drawContent() }
            .layerBackdrop(source).background(Color.Red))
        Box(Modifier.size(100.dp).graphicsLayer()
            .progressiveTextureBlur(
                backdrop = consumer,
                shape = RectangleShape,
                gradient = ProgressiveBlur.Top,
                blurRadius = 32f,
            ))
    }
}
