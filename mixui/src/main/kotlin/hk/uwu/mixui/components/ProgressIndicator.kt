package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend

@Composable
fun InfiniteProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = Color.Gray,
    size: Dp = 20.dp,
    strokeWidth: Dp = 2.dp,
    orbitingDotSize: Dp = 2.dp,
) {
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator(modifier, color, size, strokeWidth, orbitingDotSize)
    } else {
        ui.feice.mochi.basic.MochiActivityIndicator(modifier = modifier, color = color, size = size)
    }
}

@Composable
fun CircularProgressIndicator(
    modifier: Modifier = Modifier,
    progress: Float? = null,
    strokeWidth: Dp = 4.dp,
    size: Dp = 30.dp,
) {
    if (LocalMixUiBackend.current == MixUiBackend.Miuix) {
        top.yukonga.miuix.kmp.basic.CircularProgressIndicator(modifier = modifier, progress = progress, strokeWidth = strokeWidth, size = size)
    } else {
        ui.feice.mochi.basic.MochiProgressRing(modifier = modifier, progress = progress, strokeWidth = strokeWidth, size = size)
    }
}
