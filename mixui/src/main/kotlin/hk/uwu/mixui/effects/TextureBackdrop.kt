package hk.uwu.mixui.effects

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur as vendorTextureBlur

/** Opaque source for decorative texture effects. No renderer types escape mixui. */
@Stable
class TextureBackdrop internal constructor(internal val layer: LayerBackdrop)

@Composable
fun rememberTextureBackdrop(): TextureBackdrop {
    val layer = rememberLayerBackdrop()
    return remember(layer) { TextureBackdrop(layer) }
}

/** Record only the content that should appear behind a texture, not its consumers. */
fun Modifier.textureBackdropSource(backdrop: TextureBackdrop): Modifier =
    layerBackdrop(backdrop.layer)

/** Blend operations used by the app's decorative branding recipes, in drawing order. */
enum class TextureBlendOperation {
    ColorDodge,
    ColorBurn,
    LinearLight,
    Lab,
    Overlay,
    HardLight,
    Luminosity,
    PlusDarker,
}

@Immutable
data class TextureBlendLayer(val color: Color, val operation: TextureBlendOperation)

/**
 * Shared decorative renderer for both backends, not a native Mochi glass material.
 * [blurRadius] is in dp; the renderer performs the sole conversion to pixels.
 * Omitted noise retains the renderer's default. Color adjustments remain neutral.
 */
fun Modifier.textureBlur(
    backdrop: TextureBackdrop,
    shape: Shape,
    blurRadius: Dp,
    blendLayers: List<TextureBlendLayer> = emptyList(),
    noiseCoefficient: Float = BlurDefaults.NoiseCoefficient,
    contentBlendMode: BlendMode = BlendMode.SrcOver,
    enabled: Boolean = true,
): Modifier = vendorTextureBlur(
    backdrop = backdrop.layer,
    shape = shape,
    blurRadius = blurRadius.value,
    noiseCoefficient = noiseCoefficient,
    colors = textureBlurColors(blendLayers),
    contentBlendMode = contentBlendMode,
    enabled = enabled,
)

internal fun textureBlurColors(layers: List<TextureBlendLayer>): BlurColors = BlurColors(
    blendColors = layers.map { BlendColorEntry(it.color, it.operation.toVendorBlendMode()) },
    brightness = 0f,
    contrast = 1f,
    saturation = 1f,
)

internal fun TextureBlendOperation.toVendorBlendMode(): BlurBlendMode = when (this) {
    TextureBlendOperation.ColorDodge -> BlurBlendMode.ColorDodge
    TextureBlendOperation.ColorBurn -> BlurBlendMode.ColorBurn
    TextureBlendOperation.LinearLight -> BlurBlendMode.LinearLight
    TextureBlendOperation.Lab -> BlurBlendMode.Lab
    TextureBlendOperation.Overlay -> BlurBlendMode.Overlay
    TextureBlendOperation.HardLight -> BlurBlendMode.HardLight
    TextureBlendOperation.Luminosity -> BlurBlendMode.Luminosity
    TextureBlendOperation.PlusDarker -> BlurBlendMode.PlusDarker
}
