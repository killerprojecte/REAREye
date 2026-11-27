package hk.uwu.mixui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.glass.GlassSegmentedTabRow
import kotlin.math.*

/** Debug-only native optical negative control. Never part of release API. */
@Composable
fun NativeSheetSamplingProbe(
    phase: Boolean, lowFrequency: Boolean, directDimmed: Boolean,
    bandWidth: Int, directBounds: Rect?, tabs: List<String>,
    content: @Composable () -> Unit,
) {
    val directSource = rememberLayerBackdrop()
    val scrim = top.yukonga.miuix.kmp.glass.GlassBottomSheetDefaults.ScrimColor
    Canvas(Modifier.fillMaxSize().layerBackdrop(directSource)) {
        drawRect(if (phase) Color.White else Color.Black)
        val band = bandWidth.toFloat()
        for (i in 0..ceil((size.width + size.height) / band).toInt()) {
            if (i % 2 != 0) continue
            val edge = i * band
            drawPath(Path().apply {
                moveTo(edge, 0f); lineTo(edge + band, 0f)
                lineTo(edge + band - size.height, size.height)
                lineTo(edge - size.height, size.height); close()
            }, if (phase) Color.Black else Color.White)
        }
        if (lowFrequency) drawRect(if (phase) Color(0xFFFFC000) else Color(0xFF0030FF))
        if (directBounds != null && directDimmed) drawRect(scrim)
    }
    // Exact old wiring negative: same native track samples P,
    // without Sheet material/scrim, in the identical window rect.
    directBounds?.let { rect ->
        val density = LocalDensity.current
        GlassSegmentedTabRow(tabs = tabs, selectedIndex = 1,
            onSelect = {}, backdrop = directSource, surfaceAlpha = 1f,
            modifier = Modifier.offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
                .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
                .testTag("direct-native-track"))
    }
    content()
}
