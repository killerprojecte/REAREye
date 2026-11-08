package hk.uwu.mixui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.mixui.LocalMixUiBackend
import hk.uwu.mixui.MixUiBackend
import hk.uwu.mixui.theme.MixTheme
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.shape.mochiBorder

/** Only editable glass forms opt into these opaque *control* surfaces, never the dialog itself.
 * Fixed neutral pairs keep text readable independently of the recorded content behind the glass.
 * Light/dark label contrast is >7:1; the resting outline against its control fill is >4:1.
 */
@Immutable
internal data class GlassFormStyle(val fill: Color, val content: Color, val label: Color, val outline: Color) {
    fun buttonColors() = ButtonColors(fill, fill, content, content.copy(alpha = 0.38f))
}

internal val LocalGlassFormStyle = staticCompositionLocalOf<GlassFormStyle?> { null }

@Composable
internal fun glassFormStyle(): GlassFormStyle = if (MixTheme.colorScheme.background.luminance() < 0.5f) {
    GlassFormStyle(Color(0xFF303030), Color(0xFFFAFAFA), Color(0xFFDDDDDD), Color(0xFF999999))
} else {
    GlassFormStyle(Color.White, Color(0xFF242424), Color(0xFF515151), Color(0xFF737373))
}

@Composable
internal fun glassFormButtonColors(): ButtonColors =
    LocalGlassFormStyle.current?.buttonColors() ?: ButtonDefaults.buttonColors()

/** Draw after the native fill, using each renderer's own corner geometry. */
@Composable
internal fun Modifier.glassFormOutline(color: Color, radius: Dp, mochi: Boolean = LocalMixUiBackend.current == MixUiBackend.Mochi): Modifier {
    if (mochi) return mochiBorder(1.dp, color, MochiRoundedRectangle(radius))
    val squircle = isSquircleEnabled()
    return drawWithCache {
        val width = 1.dp.toPx()
        val half = width / 2
        val path = Path().apply {
            addSquircleRect(size.width - width, size.height - width,
                (radius.toPx() - half).coerceAtLeast(0f), squircleEnabled = squircle)
        }
        onDrawWithContent {
            drawContent()
            translate(half, half) { drawPath(path, color, style = Stroke(width)) }
        }
    }
}
